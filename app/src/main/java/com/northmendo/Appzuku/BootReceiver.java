package com.northmendo.Appzuku;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Broadcast receiver that re-applies autostart prevention settings on device
 * boot.
 * This ensures that apps blocked from auto-starting remain blocked after
 * reboot.
 */
public class BootReceiver extends BroadcastReceiver {
    private static final String TAG = "BootReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            Log.d(TAG, "Boot completed, re-applying autostart prevention settings");

            // Create temporary executor and handler for this operation
            ExecutorService executor = Executors.newSingleThreadExecutor();
            Handler handler = new Handler(Looper.getMainLooper());

            executor.execute(() -> {
                try {
                    ShellManager shellManager = new ShellManager(context, handler, executor);
                    BackgroundAppManager appManager = new BackgroundAppManager(context, handler, executor,
                            shellManager);

                    // Small delay to ensure system is ready
                    Thread.sleep(5000);

                    // Get saved autostart disabled apps
                    Set<String> disabledApps = appManager.getAutostartDisabledApps();

                    if (!disabledApps.isEmpty()) {
                        Log.d(TAG, "Re-applying autostart prevention for " + disabledApps.size() + " apps");

                        // Load all installed apps to pass to applyAutostartPrevention
                        appManager.loadAllApps(allApps -> {
                            appManager.applyAutostartPrevention(allApps, disabledApps);
                            Log.d(TAG, "Autostart prevention re-applied successfully");

                            // Clean up executor
                            executor.shutdown();
                        });
                    } else {
                        Log.d(TAG, "No apps have autostart disabled");
                        executor.shutdown();
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Error re-applying autostart prevention", e);
                    executor.shutdown();
                }
            });
        }
    }
}
