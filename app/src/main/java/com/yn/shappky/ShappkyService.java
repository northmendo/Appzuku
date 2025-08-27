package com.yn.shappky;

import android.app.Service;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.SharedPreferences;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.provider.Settings;
import android.widget.Toast;

import androidx.core.app.NotificationCompat;

import com.yn.shappky.util.ShellManager;
import android.content.ComponentName;
import android.service.quicksettings.TileService;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

/**
 * A foreground service that periodically kills background applications
 */
public class ShappkyService extends Service {

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler handler = new Handler();
    private static boolean isRunning = false;

    private static final String CHANNEL_ID = "ShappkyChannel";
    private static final String PREFERENCES_NAME = "AppPreferences";
    private static final String KEY_HIDDEN_APPS = "hidden_apps";

    private ShellManager shellManager;

    public static boolean isRunning() {
        return isRunning;
    }
    
    @Override
    public void onCreate() {
        super.onCreate();
        shellManager = new ShellManager(this, handler, executor);
        createNotificationChannel();

        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Shappky Service")
                .setContentText("Killing background apps...")
                .setSmallIcon(R.drawable.ic_shappky)
                .setOngoing(true)
                .build();

        startForeground(1, notification);
        isRunning = true;
        requestTileUpdate();
        startKillerLoop();
    }

    private void startKillerLoop() {
        executor.execute(() -> {
            while (isRunning) {
                try {
                    killBackgroundApps();
                    Thread.sleep(18000); 
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        });
    }

    private void killBackgroundApps() {
        if (!shellManager.hasAnyShellPermission()) {
            shellManager.checkShellPermissions();
            handler.post(() -> Toast.makeText(this, "Shizuku/Root permission required", Toast.LENGTH_SHORT).show());
            return;
        }

        final SharedPreferences sharedpreferences = getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE);
        final Set<String> hiddenApps = sharedpreferences.getStringSet(KEY_HIDDEN_APPS, new HashSet<>());

        String rawKeyboardPackage = Settings.Secure.getString(getContentResolver(), Settings.Secure.DEFAULT_INPUT_METHOD);
        final String currentKeyboardPackage = (rawKeyboardPackage != null && rawKeyboardPackage.contains("/"))
                ? rawKeyboardPackage.split("/")[0]
                : null;

        final Intent launcherIntent = new Intent(Intent.ACTION_MAIN);
        launcherIntent.addCategory(Intent.CATEGORY_HOME);
        final ResolveInfo resolveInfo = getPackageManager().resolveActivity(launcherIntent, PackageManager.MATCH_DEFAULT_ONLY);
        final String currentLauncherPackage = (resolveInfo != null && resolveInfo.activityInfo != null)
                ? resolveInfo.activityInfo.packageName : null;

        final String dumpOutput = shellManager.runShellCommandAndGetFullOutput("dumpsys activity activities");
        if (dumpOutput == null) { return; }

        String psOutput = shellManager.runShellCommandAndGetFullOutput("ps -A -o rss,name | grep '\\.' | grep -v '[-:@]' | awk '{print $2}'");
        if (psOutput == null) { return; }

        Set<String> runningPackages = new HashSet<>();
        final PackageManager pm = getPackageManager();
        try (BufferedReader reader = new BufferedReader(new StringReader(psOutput))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String packageName = line.trim();
                if (!packageName.isEmpty() && packageName.contains(".")) {
                    try {
                        pm.getApplicationInfo(packageName, 0);
                        runningPackages.add(packageName);
                    } catch (PackageManager.NameNotFoundException ignored) {}
                }
            }
        } catch (IOException e) { e.printStackTrace(); }

        // Filter the list to decide which apps to kill
        List<String> toKill = runningPackages.stream()
                .filter(pkg -> {
                    try {
                        if (hiddenApps.contains(pkg) || isProtected(pkg, currentKeyboardPackage, currentLauncherPackage) || dumpOutput.contains(pkg)) {
                            return false;
                        }
                        ApplicationInfo appInfo = pm.getApplicationInfo(pkg, 0);
                        return (appInfo.flags & ApplicationInfo.FLAG_PERSISTENT) == 0;
                    } catch (PackageManager.NameNotFoundException e) {
                        return false;
                    }
                })
                .collect(Collectors.toList());
        
        // Execute kill commands
        if (!toKill.isEmpty()) {
            String killCommand = toKill.stream()
                    .map(pkg -> "am force-stop " + pkg)
                    .collect(Collectors.joining("; "));

            String finalCommand = killCommand + "; am kill-all";
            
            final int killedCount = toKill.size();
            String amOutput = shellManager.runShellCommandAndGetFullOutput(finalCommand);
            if (amOutput == null) { return; }
        } else {
            handler.post(() -> Toast.makeText(this, "No apps to kill", Toast.LENGTH_SHORT).show());
        }
    } 

    private boolean isProtected(String packageName, String currentKeyboardPackage, String currentLauncherPackage) {
        return packageName.equals("com.yn.shappky") ||
               packageName.equals("com.google.android.gms") ||
               packageName.equals("com.android.systemui") ||
               packageName.equals("com.android.bluetooth") ||
               packageName.equals("com.android.camera") ||
               (currentKeyboardPackage != null && packageName.equals(currentKeyboardPackage)) ||
               (currentLauncherPackage != null && packageName.equals(currentLauncherPackage));
    }

    @Override
    public void onDestroy() {
        isRunning = false; 
        requestTileUpdate();
        super.onDestroy();
        executor.shutdownNow(); 
        Toast.makeText(this, "Shappky Service Stopped", Toast.LENGTH_SHORT).show();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
    
    private void requestTileUpdate() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            TileService.requestListeningState(this, new ComponentName(this, ShappkyQuickTile.class));
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "Shappky Foreground Service", NotificationManager.IMPORTANCE_LOW);
            getSystemService(NotificationManager.class).createNotificationChannel(channel);
        }
    }
}