package com.northmendo.Appzuku;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * Broadcast receiver for Boot Completed.
 * Previously used to re-apply AppOps.
 * With the new 'pm disable' method, settings are persistent by the system.
 */
public class BootReceiver extends BroadcastReceiver {
    private static final String TAG = "BootReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            Log.d(TAG, "Boot completed. Autostart prevention is handled via persistent component disabling.");
        }
    }
}
