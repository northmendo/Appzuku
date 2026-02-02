package com.northmendo.Appzuku;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static com.northmendo.Appzuku.PreferenceKeys.*;
import static com.northmendo.Appzuku.AppConstants.*;

public class AutoKillWorker extends Worker {

    public AutoKillWorker(@NonNull Context context, @NonNull WorkerParameters workerParams) {
        super(context, workerParams);
    }

    @NonNull
    @Override
    public Result doWork() {
        SharedPreferences prefs = getApplicationContext().getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE);

        if (!prefs.getBoolean(KEY_AUTO_KILL_ENABLED, false)) {
            return Result.success();
        }

        // Initialize components for background work
        Handler handler = new Handler(Looper.getMainLooper());
        ExecutorService executor = Executors.newSingleThreadExecutor();

        try {
            ShellManager shellManager = new ShellManager(getApplicationContext(), handler, executor);
            BackgroundAppManager appManager = new BackgroundAppManager(getApplicationContext(), handler, executor,
                    shellManager);

            // Wait for root check or just proceed if Shizuku
            if (!shellManager.hasAnyShellPermission()) {
                // Try to wait a bit for root check or fail
                try {
                    Thread.sleep(ROOT_CHECK_TIMEOUT_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return Result.failure();
                }
                if (!shellManager.hasAnyShellPermission()) {
                    return Result.failure();
                }
            }

            // Synchronous waiting for async kill
            CountDownLatch latch = new CountDownLatch(1);
            appManager.performAutoKill(latch::countDown);

            try {
                latch.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return Result.retry();
            }

            // Prune old stats periodically
            long pruneThreshold = System.currentTimeMillis() - STATS_PRUNE_THRESHOLD_MS;
            com.northmendo.Appzuku.db.AppDatabase.getInstance(getApplicationContext())
                    .appStatsDao().deleteOldStats(pruneThreshold);

            return Result.success();
        } finally {
            // Fixed: Always shut down executor to prevent thread leaks
            executor.shutdownNow();
        }
    }
}
