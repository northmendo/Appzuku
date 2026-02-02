package com.northmendo.Appzuku;

import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.SharedPreferences;
import android.os.Handler;
import android.util.Log;
import android.widget.Toast;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import androidx.core.app.NotificationCompat;
import android.os.Build;
import java.io.IOException;

import java.io.BufferedReader;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.Collections;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.Comparator;

import static com.northmendo.Appzuku.PreferenceKeys.*;
import static com.northmendo.Appzuku.AppConstants.*;

public class BackgroundAppManager {
    private static final String TAG = "BackgroundAppManager";
    private final Context context;
    private final Handler handler;
    private final ExecutorService executor;
    private final ShellManager shellManager;
    private final List<AppModel> currentAppsList = new ArrayList<>();
    private boolean showSystemApps = false;
    private boolean showPersistentApps = false;
    private SharedPreferences sharedpreferences;

    public BackgroundAppManager(Context context, Handler handler, ExecutorService executor, ShellManager shellManager) {
        this.context = context;
        this.handler = handler;
        this.executor = executor;
        this.shellManager = shellManager;
        this.sharedpreferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE);
    }

    /**
     * Toggles autostart prevention for a specific app by enabling/disabling its BOOT_COMPLETED receivers.
     */
    public void toggleAutostart(AppModel app, Runnable onComplete) {
         if (!shellManager.hasAnyShellPermission()) {
            shellManager.checkShellPermissions();
            return;
        }

        executor.execute(() -> {
            boolean newState = !app.isAutostartBlocked();
            // If blocking (newState=true), we want to DISABLE the components.
            // If unblocking (newState=false), we want to ENABLE them.
            String stateCmd = newState ? "disable" : "enable";
            
            StringBuilder command = new StringBuilder();
            for (String component : app.getBootReceiverComponents()) {
                // Component is just class name, need full component name: pkg/class
                command.append("pm ").append(stateCmd).append(" ").append(app.getPackageName()).append("/").append(component).append("; ");
            }

            if (command.length() > 0) {
                shellManager.runShellCommand(command.toString(), () -> {
                    app.setAutostartBlocked(newState);
                    handler.post(() -> {
                         Toast.makeText(context, "Autostart " + (newState ? "Disabled" : "Enabled"), Toast.LENGTH_SHORT).show();
                         if (onComplete != null) onComplete.run();
                    });
                });
            } else {
                 handler.post(() -> {
                     Toast.makeText(context, "No autostart components found for this app", Toast.LENGTH_SHORT).show();
                     if (onComplete != null) onComplete.run();
                 });
            }
        });
    }

    /**
     * Scans all installed apps for BOOT_COMPLETED receivers and updates the AppModels.
     */
    public void loadAutostartApps(Consumer<List<AppModel>> callback) {
        executor.execute(() -> {
            PackageManager pm = context.getPackageManager();
            Intent intent = new Intent(Intent.ACTION_BOOT_COMPLETED);
            // Query for all receivers that handle BOOT_COMPLETED, including disabled ones
            List<ResolveInfo> receivers = pm.queryBroadcastReceivers(intent, 
                    PackageManager.MATCH_DISABLED_COMPONENTS | PackageManager.GET_META_DATA);
            
            // Map: PackageName -> List of Receiver Class Names
            java.util.Map<String, List<String>> pkgReceivers = new java.util.HashMap<>();
            // Map: PackageName -> Are all receivers disabled?
            java.util.Map<String, Boolean> pkgDisabledState = new java.util.HashMap<>();

            for (ResolveInfo info : receivers) {
                String pkg = info.activityInfo.packageName;
                String cls = info.activityInfo.name;
                
                pkgReceivers.computeIfAbsent(pkg, k -> new ArrayList<>()).add(cls);
                
                // Check if currently disabled
                ComponentName comp = new ComponentName(pkg, cls);
                int state = pm.getComponentEnabledSetting(comp);
                boolean isEnabled;
                if (state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED || 
                    state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER) {
                    isEnabled = false; // Explicitly blocked
                } else {
                    // Treat ENABLED and DEFAULT as "Enabled" (Not Blocked) for the purpose of the UI.
                    // This ensures apps disabled by default in manifest are not auto-selected as "Blocked by User".
                    isEnabled = true;
                }
                
                // If ANY receiver is enabled, the app is NOT fully blocked.
                // Logic: Blocked if ALL relevant receivers are disabled.
                pkgDisabledState.merge(pkg, !isEnabled, (oldVal, newVal) -> oldVal && newVal);
            }

            List<AppModel> result = new ArrayList<>();
             for (String pkg : pkgReceivers.keySet()) {
                 if (pkg.equals(context.getPackageName())) continue; // Skip self
                 
                 try {
                     ApplicationInfo appInfo = pm.getApplicationInfo(pkg, 0);
                     if (getHiddenApps().contains(pkg)) continue;
                     
                     AppModel model = new AppModel(
                        pm.getApplicationLabel(appInfo).toString(),
                        pkg,
                        "-", 0,
                        pm.getApplicationIcon(appInfo),
                        (appInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0,
                        (appInfo.flags & ApplicationInfo.FLAG_PERSISTENT) != 0,
                        ProtectedApps.isProtected(context, pkg)
                     );
                     
                     model.setBootReceiverComponents(pkgReceivers.get(pkg));
                     Boolean allDisabled = pkgDisabledState.get(pkg);
                     model.setAutostartBlocked(allDisabled != null && allDisabled);
                     
                     result.add(model);
                 } catch (PackageManager.NameNotFoundException e) {
                     continue;
                 }
             }
             
             Collections.sort(result, (a, b) -> a.getAppName().compareToIgnoreCase(b.getAppName()));
             
             handler.post(() -> callback.accept(result));
        });
    }

    public void performAutoKill(Runnable onComplete) {
        if (!shellManager.hasAnyShellPermission()) {
            if (onComplete != null)
                onComplete.run();
            return;
        }

        executor.execute(() -> {
            Set<String> hiddenApps = getHiddenApps();
            Set<String> whitelistedApps = getWhitelistedApps();
            Set<String> blacklistedApps = getBlacklistedApps();
            int killMode = getKillMode(); // 0 = Whitelist, 1 = Blacklist

            String dumpOutput = shellManager.runShellCommandAndGetFullOutput("dumpsys activity activities");
            if (dumpOutput == null) {
                if (onComplete != null)
                    handler.post(onComplete);
                return;
            }

            String psOutput = shellManager.runShellCommandAndGetFullOutput(
                    "ps -A -o rss,name | grep '\\.' | grep -v '[-:@]' | awk '{print $2}'");
            if (psOutput == null) {
                if (onComplete != null)
                    handler.post(onComplete);
                return;
            }

            Set<String> runningPackages = new HashSet<>();
            PackageManager pm = context.getPackageManager();
            try (BufferedReader reader = new BufferedReader(new StringReader(psOutput))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String packageName = line.trim();
                    if (!packageName.isEmpty() && packageName.contains(".")) {
                        try {
                            pm.getApplicationInfo(packageName, 0);
                            runningPackages.add(packageName);
                        } catch (PackageManager.NameNotFoundException ignored) {
                        }
                    }
                }
            } catch (IOException ignored) {
            }

            List<String> toKill = runningPackages.stream()
                    .filter(pkg -> {
                        try {
                            // Common checks: foreground, hidden, protected
                            if (hiddenApps.contains(pkg) || ProtectedApps.isProtected(context, pkg)
                                    || dumpOutput.contains(pkg)) {
                                return false;
                            }

                            if (killMode == 1) { // Blacklist Mode
                                return blacklistedApps.contains(pkg);
                            } else { // Whitelist Mode (Default)
                                if (whitelistedApps.contains(pkg))
                                    return false;
                                // In whitelist mode, check persistent flag
                                ApplicationInfo appInfo = pm.getApplicationInfo(pkg, 0);
                                return (appInfo.flags & ApplicationInfo.FLAG_PERSISTENT) == 0;
                            }
                        } catch (PackageManager.NameNotFoundException e) {
                            return false;
                        }
                    })
                    .collect(Collectors.toList());

            if (!toKill.isEmpty()) {
                // DB Logging
                long now = System.currentTimeMillis();
                com.northmendo.Appzuku.db.AppDatabase db = com.northmendo.Appzuku.db.AppDatabase.getInstance(context);
                for (String pkg : toKill) {
                    com.northmendo.Appzuku.db.AppStats stats = db.appStatsDao().getStats(pkg);
                    if (stats == null) {
                        stats = new com.northmendo.Appzuku.db.AppStats(pkg);
                        // Populate appName from PackageManager
                        try {
                            ApplicationInfo appInfo = pm.getApplicationInfo(pkg, 0);
                            stats.appName = pm.getApplicationLabel(appInfo).toString();
                        } catch (PackageManager.NameNotFoundException ignored) {
                        }
                        db.appStatsDao().insert(stats);
                    }
                    db.appStatsDao().incrementKill(pkg, now);
                }

                String killCommand = toKill.stream()
                        .map(pkg -> "am force-stop " + pkg)
                        .collect(Collectors.joining("; "));
                String finalCommand = killCommand + "; am kill-all";

                shellManager.runShellCommandAndGetFullOutput(finalCommand);

                sendKillNotification(toKill.size());

                // Update widget to reflect new RAM state
                updateWidget();

                // Short delay to allow system to react, then check for relaunches
                try {
                    Thread.sleep(RELAUNCH_CHECK_DELAY_MS);
                } catch (InterruptedException ignored) {
                }
                checkRelaunches(toKill, db);
            }

            if (onComplete != null)
                handler.post(onComplete);
        });
    }

    private void checkRelaunches(List<String> recentlyKilled, com.northmendo.Appzuku.db.AppDatabase db) {
        String psOutput = shellManager.runShellCommandAndGetFullOutput("ps -A -o name | grep '\\.'");
        if (psOutput == null)
            return;

        long now = System.currentTimeMillis();
        try (BufferedReader reader = new BufferedReader(new StringReader(psOutput))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String pkg = line.trim();
                if (recentlyKilled.contains(pkg)) {
                    db.appStatsDao().incrementRelaunch(pkg, now);
                }
            }
        } catch (IOException ignored) {
        }
    }

    private void sendKillNotification(int count) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID_ACTIONS, "Appzuku Actions",
                    NotificationManager.IMPORTANCE_LOW);
            context.getSystemService(NotificationManager.class).createNotificationChannel(channel);
        }

        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL_ID_ACTIONS)
                .setSmallIcon(R.drawable.ic_shappky)
                .setContentTitle("Auto-Kill Executed")
                .setContentText("Stopped " + count + " background apps")
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setAutoCancel(true);

        context.getSystemService(NotificationManager.class).notify(2, builder.build());
    }

    private String formatMemorySize(long kb) {
        if (kb < 1024)
            return kb + " KB";
        else if (kb < 1024 * 1024)
            return String.format(java.util.Locale.US, "%.2f MB", kb / 1024f);
        else
            return String.format(java.util.Locale.US, "%.2f GB", kb / (1024f * 1024f));
    }

    private long parseMemoryToKb(String ram) {
        if (ram == null || ram.isEmpty() || ram.equals("-"))
            return 0;
        ram = ram.trim().toUpperCase();
        try {
            if (ram.endsWith("KB"))
                return (long) Float.parseFloat(ram.replace("KB", "").trim().replace(",", "."));
            if (ram.endsWith("MB"))
                return (long) (Float.parseFloat(ram.replace("MB", "").trim().replace(",", ".")) * 1024);
            if (ram.endsWith("GB"))
                return (long) (Float.parseFloat(ram.replace("GB", "").trim().replace(",", ".")) * 1024 * 1024);
        } catch (NumberFormatException e) {
            e.printStackTrace();
        }
        return 0;
    }

    // Load background apps using 'ps' command via Shizuku
    public void loadBackgroundApps(Consumer<List<AppModel>> callback) {
        executor.execute(() -> {
            List<AppModel> result = new ArrayList<>();
            PackageManager packageManager = context.getPackageManager();
            Set<String> runningPackagesFromPs = new HashSet<>();
            Set<String> hiddenApps = getHiddenApps();
            Set<String> whitelistedApps = getWhitelistedApps();

            // Execute shell command to get running processes
            if (shellManager.hasAnyShellPermission()) {
                String command = "ps -A -o rss,name | grep '\\.' | grep -v '[-:@]'";
                try {
                    String fullOutput = shellManager.runShellCommandAndGetFullOutput(command);
                    if (fullOutput != null) {
                        try (BufferedReader reader = new BufferedReader(new StringReader(fullOutput))) {
                            String line;
                            while ((line = reader.readLine()) != null) {
                                String[] parts = line.trim().split("\\s+");
                                if (parts.length >= 2) {
                                    String packageName = parts[1].trim();
                                    String appRam = parts[0].trim();
                                    if (!packageName.isEmpty() && packageName.contains(".")
                                            && !packageName.startsWith("ERROR:")) {
                                        try {
                                            packageManager.getApplicationInfo(packageName, 0);
                                            runningPackagesFromPs.add(packageName + ":" + appRam); // Store with RAM
                                        } catch (PackageManager.NameNotFoundException ignored) {
                                        }
                                    }
                                }
                            }
                        }
                    } else {
                        handler.post(() -> Toast
                                .makeText(context, "Failed to get running apps output", Toast.LENGTH_SHORT).show());
                    }
                } catch (Exception e) {
                    handler.post(() -> Toast
                            .makeText(context, "Error getting running apps: " + e.getMessage(), Toast.LENGTH_SHORT)
                            .show());
                }
            }

            // Process running packages
            for (String packageEntry : runningPackagesFromPs) {
                String[] parts = packageEntry.split(":");
                String packageName = parts[0];
                long ramUsage = 0;
                try {
                    ramUsage = parts.length > 1 ? Long.parseLong(parts[1]) : 0;
                } catch (NumberFormatException e) {
                    Log.w(TAG, "Failed to parse RAM value for " + packageName, e);
                }

                try {
                    if (hiddenApps.contains(packageName)) {
                        continue;
                    }

                    // Use centralized protected apps check
                    boolean isProtected = ProtectedApps.isProtected(context, packageName);

                    ApplicationInfo appInfo = packageManager.getApplicationInfo(packageName, 0);

                    boolean isPersistentApp = (appInfo.flags & ApplicationInfo.FLAG_PERSISTENT) != 0;
                    boolean isSystemApp = (appInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0;

                    if (!showSystemApps && isSystemApp || !showPersistentApps && isPersistentApp) {
                        continue;
                    }

                    AppModel appModel = new AppModel(
                            packageManager.getApplicationLabel(appInfo).toString(),
                            packageName,
                            formatMemorySize(ramUsage),
                            ramUsage,
                            packageManager.getApplicationIcon(appInfo),
                            isSystemApp,
                            isPersistentApp,
                            isProtected);
                    // Set whitelist status
                    appModel.setWhitelisted(whitelistedApps.contains(packageName));
                    result.add(appModel);
                } catch (PackageManager.NameNotFoundException ignored) {
                }
            }
            sortAppList(result, SORT_MODE_DEFAULT);

            // Update UI with results
            handler.post(() -> {
                currentAppsList.clear();
                currentAppsList.addAll(result);
                if (callback != null) {
                    callback.accept(new ArrayList<>(result));
                }
            });
        });
    }

    /**
     * Updates the running state and RAM usage of the provided app list.
     * This runs asynchronously and updates the models in-place.
     */
    public void updateRunningState(List<AppModel> apps, Runnable onComplete) {
        if (!shellManager.hasAnyShellPermission()) {
            if (onComplete != null) handler.post(onComplete);
            return;
        }

        executor.execute(() -> {
            String psOutput = shellManager.runShellCommandAndGetFullOutput(
                    "ps -A -o rss,name | grep '\\.' | grep -v '[-:@]'");
            
            java.util.Map<String, Long> runningMap = new java.util.HashMap<>();
            if (psOutput != null) {
                try (BufferedReader reader = new BufferedReader(new StringReader(psOutput))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        String[] parts = line.trim().split("\\s+");
                        if (parts.length >= 2) {
                            String packageName = parts[1].trim();
                            try {
                                long ramUsage = Long.parseLong(parts[0].trim());
                                runningMap.put(packageName, ramUsage);
                            } catch (NumberFormatException e) {
                                Log.w(TAG, "Failed to parse RAM value for " + packageName, e);
                            }
                        }
                    }
                } catch (Exception ignored) {}
            }
            
            for (AppModel app : apps) {
                if (runningMap.containsKey(app.getPackageName())) {
                    long ram = runningMap.get(app.getPackageName());
                    app.setAppRamBytes(ram);
                    app.setAppRam(formatMemorySize(ram));
                } else {
                    app.setAppRamBytes(0);
                    app.setAppRam("-");
                }
            }
            
            if (onComplete != null) handler.post(onComplete);
        });
    }

    /**
     * Sort app list based on the specified sort mode
     */
    public void sortAppList(List<AppModel> apps, int sortMode) {
        if (apps == null || apps.isEmpty()) {
            return;
        }

        switch (sortMode) {
            case SORT_MODE_RAM_DESC:
                // Most RAM to least RAM
                Collections.sort(apps, (a1, a2) -> Long.compare(a2.getAppRamBytes(), a1.getAppRamBytes()));
                break;
            case SORT_MODE_RAM_ASC:
                // Least RAM to most RAM
                Collections.sort(apps, (a1, a2) -> Long.compare(a1.getAppRamBytes(), a2.getAppRamBytes()));
                break;
            case SORT_MODE_NAME_ASC:
                // Name A to Z
                Collections.sort(apps, (a1, a2) -> a1.getAppName().compareToIgnoreCase(a2.getAppName()));
                break;
            case SORT_MODE_NAME_DESC:
                // Name Z to A
                Collections.sort(apps, (a1, a2) -> a2.getAppName().compareToIgnoreCase(a1.getAppName()));
                break;
            case SORT_MODE_DEFAULT:
            default:
                // Default: System → Persistent → Name
                Collections.sort(apps,
                        Comparator.comparing(AppModel::isSystemApp)
                                .thenComparing(AppModel::isPersistentApp)
                                .thenComparing(a -> a.getAppName().toLowerCase()));
                break;
        }
    }

    // Load all installed applications
    public void loadAllApps(Consumer<List<AppModel>> callback) {
        executor.execute(() -> {
            PackageManager pm = context.getPackageManager();
            List<ApplicationInfo> packages = pm.getInstalledApplications(PackageManager.GET_META_DATA);
            List<AppModel> allApps = new ArrayList<>();
            for (ApplicationInfo appInfo : packages) {
                if (appInfo.packageName.equals(context.getPackageName())) {
                    continue;
                }
                boolean isSystem = (appInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
                boolean isPersistent = (appInfo.flags & ApplicationInfo.FLAG_PERSISTENT) != 0;
                allApps.add(new AppModel(
                        pm.getApplicationLabel(appInfo).toString(),
                        appInfo.packageName,
                        "-", // RAM placeholder
                        0, // Raw RAM bytes
                        pm.getApplicationIcon(appInfo),
                        isSystem,
                        isPersistent,
                        false));
            }
            // Sort alphabetically
            Collections.sort(allApps, (a1, a2) -> a1.getAppName().compareToIgnoreCase(a2.getAppName()));
            handler.post(() -> callback.accept(allApps));
        });
    }

    // Get the set of hidden app package names
    public Set<String> getHiddenApps() {
        return sharedpreferences.getStringSet(KEY_HIDDEN_APPS, new HashSet<>());
    }

    // Save the set of hidden app package names
    public void saveHiddenApps(Set<String> hiddenApps) {
        sharedpreferences.edit().putStringSet(KEY_HIDDEN_APPS, hiddenApps).apply();
    }

    // Get the set of whitelisted (never kill) app package names
    public Set<String> getWhitelistedApps() {
        return new HashSet<>(sharedpreferences.getStringSet(KEY_WHITELISTED_APPS, new HashSet<>()));
    }

    // Save the set of whitelisted app package names
    public void saveWhitelistedApps(Set<String> whitelistedApps) {
        sharedpreferences.edit().putStringSet(KEY_WHITELISTED_APPS, whitelistedApps).apply();
    }

    // Get the set of apps with autostart disabled
    public Set<String> getAutostartDisabledApps() {
        return new HashSet<>(sharedpreferences.getStringSet(KEY_AUTOSTART_DISABLED_APPS, new HashSet<>()));
    }

    // Save the set of apps with autostart disabled
    public void saveAutostartDisabledApps(Set<String> packageNames) {
        sharedpreferences.edit().putStringSet(KEY_AUTOSTART_DISABLED_APPS, packageNames).apply();
    }

    public Set<String> getBlacklistedApps() {
        return new HashSet<>(sharedpreferences.getStringSet(KEY_BLACKLISTED_APPS, new HashSet<>()));
    }

    public void saveBlacklistedApps(Set<String> apps) {
        sharedpreferences.edit().putStringSet(KEY_BLACKLISTED_APPS, apps).apply();
    }

    public int getKillMode() {
        return sharedpreferences.getInt(KEY_KILL_MODE, 0);
    }

    public void setKillMode(int mode) {
        sharedpreferences.edit().putInt(KEY_KILL_MODE, mode).apply();
    }

    /**
     * Apply autostart prevention by enabling/disabling BOOT_COMPLETED receivers.
     * 
     * @param allApps      List of all apps (must have bootReceiverComponents populated)
     * @param disabledApps Set of apps that should have their receivers disabled
     */
    public void applyAutostartPrevention(List<AppModel> allApps, Set<String> disabledApps) {
        if (!shellManager.hasAnyShellPermission()) {
            shellManager.checkShellPermissions();
            return;
        }

        executor.execute(() -> {
            StringBuilder commandBuilder = new StringBuilder();
            
            for (AppModel app : allApps) {
                List<String> receivers = app.getBootReceiverComponents();
                if (receivers == null || receivers.isEmpty()) {
                    continue;
                }
                
                boolean shouldBlock = disabledApps.contains(app.getPackageName());
                String action = shouldBlock ? "disable" : "enable";
                
                for (String receiver : receivers) {
                    commandBuilder.append("pm ").append(action).append(" ")
                        .append(app.getPackageName()).append("/").append(receiver).append("; ");
                }
            }

            if (commandBuilder.length() > 0) {
                shellManager.runShellCommand(commandBuilder.toString(), () -> {
                    handler.post(() -> Toast.makeText(context, "Autostart settings applied", Toast.LENGTH_SHORT).show());
                });
            }
        });
    }

    // Toggle whitelist status for a package
    public boolean toggleWhitelist(String packageName) {
        Set<String> whitelisted = getWhitelistedApps();
        boolean isNowWhitelisted;
        if (whitelisted.contains(packageName)) {
            whitelisted.remove(packageName);
            isNowWhitelisted = false;
        } else {
            whitelisted.add(packageName);
            isNowWhitelisted = true;
        }
        saveWhitelistedApps(whitelisted);
        return isNowWhitelisted;
    }

    // Check if an app is whitelisted
    public boolean isWhitelisted(String packageName) {
        return getWhitelistedApps().contains(packageName);
    }

    // Kill specified packages using shell
    public void killPackages(List<String> packageNames, Runnable onComplete) {
        if (!shellManager.hasAnyShellPermission()) {
            shellManager.checkShellPermissions();
            if (onComplete != null) {
                handler.post(onComplete);
            }
            return;
        }

        if (packageNames == null || packageNames.isEmpty()) {
            if (onComplete != null) {
                handler.post(onComplete);
            }
            return;
        }
        long totalKb = 0;
        for (String pkg : packageNames) {
            for (AppModel app : currentAppsList) {
                if (app.getPackageName().equals(pkg)) {
                    totalKb += app.getAppRamBytes(); // Use raw bytes for better accuracy
                    break;
                }
            }
        }

        String command = packageNames.stream()
                .map(pkg -> "am force-stop " + pkg)
                .collect(Collectors.joining("; "));
        final long finalTotalKb = totalKb;
        shellManager.runShellCommand(command, () -> {
            String message = "Free up " + formatMemorySize(finalTotalKb);
            handler.post(() -> Toast.makeText(context, message, Toast.LENGTH_LONG).show());
            if (onComplete != null) {
                onComplete.run();
            }
        });

    }

    // Kill a single app by package name
    public void killApp(String packageName, Runnable onComplete) {
        if (!shellManager.hasAnyShellPermission()) {
            shellManager.checkShellPermissions();
            if (onComplete != null) {
                handler.post(onComplete);
            }
            return;
        }
        if (packageName == null || packageName.isEmpty()) {
            if (onComplete != null) {
                handler.post(onComplete);
            }
            return;
        }
        // Find the app's RAM before killing
        long appRamBytes = 0;
        for (AppModel app : currentAppsList) {
            if (app.getPackageName().equals(packageName)) {
                appRamBytes = app.getAppRamBytes();
                break;
            }
        }
        final long finalAppRamBytes = appRamBytes;
        shellManager.runShellCommand("am force-stop " + packageName, () -> {
            if (finalAppRamBytes > 0) {
                String message = "Free up " + formatMemorySize(finalAppRamBytes);
                handler.post(() -> Toast.makeText(context, message, Toast.LENGTH_LONG).show());
            }
            if (onComplete != null) {
                onComplete.run();
            }
        });
    }

    // Uninstall an app using shell command
    public void uninstallPackage(String packageName, Runnable onComplete) {
        if (!shellManager.hasAnyShellPermission()) {
            shellManager.checkShellPermissions();
            if (onComplete != null) {
                handler.post(onComplete);
            }
            return;
        }
        if (packageName == null || packageName.isEmpty()) {
            if (onComplete != null) {
                handler.post(onComplete);
            }
            return;
        }

        String command = "pm uninstall " + packageName;
        shellManager.runShellCommand(command, () -> {
            handler.post(() -> Toast.makeText(context, "Uninstall command sent for " + packageName, Toast.LENGTH_SHORT).show());
            if (onComplete != null) {
                onComplete.run();
            }
        });
    }

    // Toggle visibility of system apps
    public void setShowSystemApps(boolean show) {
        this.showSystemApps = show;
    }

    // Toggle visibility of persistent apps
    public void setShowPersistentApps(boolean show) {
        this.showPersistentApps = show;
    }

    // Return a copy of the current apps list
    public List<AppModel> getAppsList() {
        return new ArrayList<>(currentAppsList);
    }

    public void clearCaches(Runnable onComplete) {
        if (!shellManager.hasAnyShellPermission()) {
            if (onComplete != null)
                handler.post(onComplete);
            return;
        }

        executor.execute(() -> {
            // Standard Android command to trim caches to 0
            shellManager.runShellCommand("pm trim-caches 4096G", () -> {
                handler.post(() -> {
                    Toast.makeText(context, "Caches cleared", Toast.LENGTH_SHORT).show();
                    if (onComplete != null)
                        onComplete.run();
                });
            });
        });
    }

    /**
     * Updates the home screen widget to reflect current RAM state.
     */
    private void updateWidget() {
        try {
            AppWidgetManager appWidgetManager = AppWidgetManager.getInstance(context);
            ComponentName widgetComponent = new ComponentName(context, AppzukuWidget.class);
            int[] appWidgetIds = appWidgetManager.getAppWidgetIds(widgetComponent);
            for (int appWidgetId : appWidgetIds) {
                AppzukuWidget.updateAppWidget(context, appWidgetManager, appWidgetId);
            }
        } catch (Exception ignored) {
            // Widget may not exist or other issue - fail silently
        }
    }
}