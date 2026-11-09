package art.applab.btawake;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;

/** Держит PARTIAL_WAKE_LOCK, пока экран выключен: система не уходит в suspend,
 *  USB-чип Bluetooth (btmtk_usb) не проходит цикл suspend/resume, стек не падает. */
public class AwakeService extends Service {
    static final String TAG = "BtAwake";
    static final String CH = "btawake";
    PowerManager.WakeLock lock;
    final BroadcastReceiver screen = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            if (Intent.ACTION_SCREEN_OFF.equals(i.getAction())) hold();
            else if (Intent.ACTION_SCREEN_ON.equals(i.getAction())) drop();
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        lock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BtAwake:standby");
        lock.setReferenceCounted(false);
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CH, "BT Awake", NotificationManager.IMPORTANCE_MIN));
        Notification n = new Notification.Builder(this, CH)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("BT Awake").setContentText("Держит систему бодрой в ожидании")
            .setOngoing(true).build();
        startForeground(1, n);
        IntentFilter f = new IntentFilter(Intent.ACTION_SCREEN_OFF);
        f.addAction(Intent.ACTION_SCREEN_ON);
        registerReceiver(screen, f);
        if (!pm.isInteractive()) hold();
        Log.i(TAG, "service started, interactive=" + pm.isInteractive());
    }

    void hold() {
        if (!lock.isHeld()) { lock.acquire(); Log.i(TAG, "screen OFF -> wakelock acquired"); }
    }
    void drop() {
        if (lock.isHeld()) { lock.release(); Log.i(TAG, "screen ON -> wakelock released"); }
    }

    @Override public int onStartCommand(Intent i, int flags, int id) { return START_STICKY; }
    @Override public IBinder onBind(Intent i) { return null; }
    @Override public void onDestroy() {
        unregisterReceiver(screen); drop(); Log.i(TAG, "service destroyed"); super.onDestroy();
    }
}
