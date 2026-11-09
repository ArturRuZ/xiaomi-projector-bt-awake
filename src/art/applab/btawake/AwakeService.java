package art.applab.btawake;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;

/**
 * Keeps the Bluetooth remote alive on Xiaomi Smart Projector L1 Pro without stopping the system from sleeping.
 *
 * The vendor bug crashes the Bluetooth stack on wake-up from suspend. The crash itself is harmless (the stack
 * restarts in seconds), but every crash may leave a dead ConnectionRecord in system_server, all on the single
 * connection that BluetoothManagerService holds to com.android.bluetooth. Once ~3000 pile up, system_server can no
 * longer bind any service and Bluetooth stays off until reboot.
 *
 * Turning Bluetooth off makes BluetoothManagerService unbind that connection, which drops all the dead records at
 * once. So this service cycles Bluetooth off/on when the projector goes to standby and every 12 h while it stays
 * there, long before the limit. It must happen while Bluetooth is still working: at the limit there is no live
 * connection left to unbind and only a reboot helps.
 */
public class AwakeService extends Service {
    static final String TAG = "BtAwake";
    static final String CH = "btawake";
    static final String ACTION_PERIODIC = "art.applab.btawake.PERIODIC";
    static final String ACTION_CYCLE_NOW = "art.applab.btawake.CYCLE_NOW";
    static final long DELAY_AFTER_SCREEN_OFF = 30_000;    // let a quick off/on by the user pass untouched
    static final long PERIOD = 12 * 60 * 60 * 1000L;       // leak rate is ~10/h in standby, limit ~3000
    static final long STEP_TIMEOUT = 30_000;
    static final long SETTLE = 15_000;                     // let the remote reconnect before the system sleeps
    static final int MAX_ENABLE_TRIES = 3;

    enum State { IDLE, WAIT_OFF, WAIT_ON }

    final Handler h = new Handler(Looper.getMainLooper());
    PowerManager pm;
    PowerManager.WakeLock lock;
    BluetoothAdapter ad;
    SharedPreferences prefs;
    State state = State.IDLE;
    int enableTries;

    final Runnable startOnStandby = () -> cycle("standby");
    final Runnable stepTimeout = this::onStepTimeout;
    final Runnable finish = this::finish;

    final BroadcastReceiver rx = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            String a = i.getAction();
            if (Intent.ACTION_SCREEN_OFF.equals(a)) onScreenOff();
            else if (Intent.ACTION_SCREEN_ON.equals(a)) onScreenOn();
            else if (ACTION_PERIODIC.equals(a)) onPeriodic();
            else if (BluetoothAdapter.ACTION_STATE_CHANGED.equals(a))
                onBtState(i.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1));
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        pm = getSystemService(PowerManager.class);
        lock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BtAwake:cycle");
        lock.setReferenceCounted(false);
        ad = getSystemService(BluetoothManager.class).getAdapter();
        prefs = getSharedPreferences("state", MODE_PRIVATE);

        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CH, "BT Awake", NotificationManager.IMPORTANCE_MIN));
        startForeground(1, new Notification.Builder(this, CH)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("BT Awake").setContentText("Clears the Bluetooth connection leak in standby")
            .setOngoing(true).build());

        IntentFilter f = new IntentFilter(Intent.ACTION_SCREEN_OFF);
        f.addAction(Intent.ACTION_SCREEN_ON);
        f.addAction(ACTION_PERIODIC);
        f.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
        registerReceiver(rx, f);

        // A previous cycle was interrupted (process killed while Bluetooth was off): turn it back on.
        if (prefs.getBoolean("we_turned_off", false) && ad != null && !ad.isEnabled()) {
            Log.w(TAG, "previous cycle interrupted, re-enabling Bluetooth");
            hold(STEP_TIMEOUT * (MAX_ENABLE_TRIES + 1));
            state = State.WAIT_ON; enableTries = 0; enable();
        } else {
            prefs.edit().putBoolean("we_turned_off", false).apply();
        }
        armPeriodic();
        Log.i(TAG, "service started, interactive=" + pm.isInteractive() + ", bt=" + (ad != null && ad.isEnabled()));
        if (!pm.isInteractive() && state == State.IDLE) onScreenOff();
    }

    @Override public int onStartCommand(Intent i, int flags, int id) {
        if (i != null && ACTION_CYCLE_NOW.equals(i.getAction())) { hold(STEP_TIMEOUT * 4); cycle("manual"); }
        return START_STICKY;
    }

    void onScreenOff() {
        hold(DELAY_AFTER_SCREEN_OFF + STEP_TIMEOUT * 4);
        h.removeCallbacks(startOnStandby);
        h.postDelayed(startOnStandby, DELAY_AFTER_SCREEN_OFF);
        Log.i(TAG, "screen OFF -> Bluetooth cycle in " + DELAY_AFTER_SCREEN_OFF / 1000 + " s");
    }

    void onScreenOn() {
        h.removeCallbacks(startOnStandby);
        if (state == State.IDLE) { h.removeCallbacks(finish); release(); }
        // A cycle already in progress is allowed to finish: never leave Bluetooth off.
    }

    void onPeriodic() {
        armPeriodic();
        if (pm.isInteractive()) { Log.i(TAG, "periodic: screen on, skipped (next standby will cycle)"); return; }
        hold(STEP_TIMEOUT * 4);
        cycle("periodic");
    }

    void cycle(String why) {
        if (state != State.IDLE) { Log.i(TAG, why + ": cycle already running"); return; }
        if (ad == null || !ad.isEnabled()) { Log.i(TAG, why + ": Bluetooth is off, nothing to do"); finish(); return; }
        Log.i(TAG, why + ": turning Bluetooth off");
        prefs.edit().putBoolean("we_turned_off", true).commit();
        state = State.WAIT_OFF;
        if (!ad.disable()) {
            Log.e(TAG, "disable() refused - is BLUETOOTH_CONNECT granted? (adb install -g)");
            prefs.edit().putBoolean("we_turned_off", false).apply();
            state = State.IDLE; finish(); return;
        }
        h.postDelayed(stepTimeout, STEP_TIMEOUT);
    }

    void onBtState(int s) {
        if (state == State.WAIT_OFF && s == BluetoothAdapter.STATE_OFF) {
            h.removeCallbacks(stepTimeout);
            state = State.WAIT_ON; enableTries = 0;
            h.postDelayed(this::enable, 1000);
        } else if (state == State.WAIT_ON && s == BluetoothAdapter.STATE_ON) {
            h.removeCallbacks(stepTimeout);
            prefs.edit().putBoolean("we_turned_off", false).apply();
            Log.i(TAG, "Bluetooth back ON, leaked connections cleared");
            state = State.IDLE;
            h.postDelayed(finish, SETTLE);
        }
    }

    void enable() {
        enableTries++;
        boolean ok = ad.enable();
        Log.i(TAG, "turning Bluetooth on (try " + enableTries + ", accepted=" + ok + ")");
        h.removeCallbacks(stepTimeout);
        h.postDelayed(stepTimeout, STEP_TIMEOUT);
    }

    void onStepTimeout() {
        if (state == State.WAIT_OFF) {
            Log.w(TAG, "Bluetooth did not reach OFF in time, turning it on again");
            state = State.WAIT_ON; enableTries = 0; enable();
        } else if (state == State.WAIT_ON) {
            if (ad.isEnabled()) { onBtState(BluetoothAdapter.STATE_ON); return; }
            if (enableTries < MAX_ENABLE_TRIES) { enable(); return; }
            Log.e(TAG, "Bluetooth did not come back after " + enableTries + " tries");
            state = State.IDLE; finish();   // flag stays set: next service start retries
        }
    }

    void finish() {
        if (state == State.IDLE && !h.hasCallbacks(startOnStandby)) release();
    }

    void hold(long ms) { lock.acquire(ms); }
    void release() { if (lock.isHeld()) lock.release(); }

    void armPeriodic() {
        PendingIntent pi = PendingIntent.getBroadcast(this, 0,
            new Intent(ACTION_PERIODIC).setPackage(getPackageName()),
            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        getSystemService(AlarmManager.class).setAndAllowWhileIdle(
            AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + PERIOD, pi);
    }

    @Override public IBinder onBind(Intent i) { return null; }
    @Override public void onDestroy() {
        unregisterReceiver(rx); h.removeCallbacksAndMessages(null); release();
        Log.i(TAG, "service destroyed"); super.onDestroy();
    }
}
