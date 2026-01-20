package com.northmendo.Appzuku;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.Log;

import static com.northmendo.Appzuku.PreferenceKeys.*;

public class KillTriggerReceiver extends BroadcastReceiver {
    private static final String TAG = "KillTriggerReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (Intent.ACTION_SCREEN_OFF.equals(action)) {
            SharedPreferences prefs = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE);
            if (prefs.getBoolean(KEY_KILL_ON_SCREEN_OFF, false)) {
                Log.d(TAG, "Screen off detected, starting kill cycle");
                Intent serviceIntent = new Intent(context, ShappkyService.class);
                serviceIntent.setAction("TRIGGER_KILL");
                // Use startForegroundService on Android 8+ when app is in background
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent);
                } else {
                    context.startService(serviceIntent);
                }
            }
        }
    }
}
