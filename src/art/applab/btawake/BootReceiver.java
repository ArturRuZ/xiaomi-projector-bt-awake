package art.applab.btawake;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

public class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context c, Intent i) {
        Log.i(AwakeService.TAG, "boot: " + i.getAction());
        c.startForegroundService(new Intent(c, AwakeService.class));
    }
}
