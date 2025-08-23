package com.yn.shappky.util;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.SharedPreferences;
import android.os.Handler;
import android.provider.Settings;
import android.widget.Toast;

import com.yn.shappky.model.AppModel;
import com.yn.shappky.util.ShellManager; 

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

import rikka.shizuku.Shizuku;

public class BackgroundAppManager {
    private final Context context;
    private final Handler handler;
    private final ExecutorService executor;
    private final ShellManager shellManager;
    private final List<AppModel> currentAppsList = new ArrayList<>();
    private boolean showSystemApps = false;
    private SharedPreferences sharedpreferences;
    private static final String PREFERENCES_NAME = "AppPreferences";
    private static final String KEY_HIDDEN_APPS = "hidden_apps";
    
    public BackgroundAppManager(Context context, Handler handler, ExecutorService executor, ShellManager shellManager) {
        this.context = context;
        this.handler = handler;
        this.executor = executor;
        this.shellManager = shellManager;
        this.sharedpreferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE);
    }

    private String formatMemorySize(long kb) {
        if (kb < 1024) return kb + " KB";
        else if (kb < 1024 * 1024) return String.format("%.2f MB", kb / 1024f);
        else return String.format("%.2f GB", kb / (1024f * 1024f));
    }

    // Load background apps using 'ps' command via Shizuku
    public void loadBackgroundApps(Consumer<List<AppModel>> callback) {
        executor.execute(() -> {
            List<AppModel> result = new ArrayList<>();
            PackageManager packageManager = context.getPackageManager();
            Set<String> runningPackagesFromPs = new HashSet<>();
            Set<String> hiddenApps = getHiddenApps();

            // Get current keyboard package
            String currentKeyboardPackage = Settings.Secure.getString(context.getContentResolver(), Settings.Secure.DEFAULT_INPUT_METHOD);
            if (currentKeyboardPackage != null && currentKeyboardPackage.contains("/")) {
                currentKeyboardPackage = currentKeyboardPackage.split("/")[0];
            }

            // Get current launcher package
            Intent launcherIntent = new Intent(Intent.ACTION_MAIN);
            launcherIntent.addCategory(Intent.CATEGORY_HOME);
            ResolveInfo resolveInfo = packageManager.resolveActivity(launcherIntent, PackageManager.MATCH_DEFAULT_ONLY);
            String currentLauncherPackage = (resolveInfo != null && resolveInfo.activityInfo != null)
                    ? resolveInfo.activityInfo.packageName : null;

            // Execute shell command to get running processes
            if (shellManager.hasAnyShellPermission()) {
                String command = "ps -A -o rss,name | grep '\\.' | grep -v '[-:@]'";
                try {
                    String fullOutput = shellManager.runShellCommandAndGetFullOutput(command);
                    if (fullOutput != null) {
                        BufferedReader reader = new BufferedReader(new StringReader(fullOutput));
                        String line;
                        while ((line = reader.readLine()) != null) {
                            String[] parts = line.trim().split("\\s+");
                            if (parts.length >= 2) {
                                String packageName = parts[1].trim();
                                String appRam = parts[0].trim();
                                if (!packageName.isEmpty() && packageName.contains(".") && !packageName.startsWith("ERROR:")) {
                                    try {
                                        packageManager.getApplicationInfo(packageName, 0);
                                        runningPackagesFromPs.add(packageName + ":" + appRam); // Store with RAM
                                    } catch (PackageManager.NameNotFoundException ignored) {}
                                }
                            }
                        }
                        reader.close();
                    } else {
                        handler.post(() -> Toast.makeText(context, "Failed to get running apps output", Toast.LENGTH_SHORT).show());
                    }
                } catch (Exception e) {
                    handler.post(() -> Toast.makeText(context, "Error getting running apps: " + e.getMessage(), Toast.LENGTH_SHORT).show());
                }
            }

            // Process running packages
            for (String packageEntry : runningPackagesFromPs) {
                String[] parts = packageEntry.split(":");
                String packageName = parts[0];
                long ramUsage = parts.length > 1 ? Long.parseLong(parts[1]) : 0;

                try {
                    if (packageName.equals("com.yn.shappky") ||
                        packageName.equals("com.android.systemui") ||
                        packageName.equals(currentKeyboardPackage) ||
                        packageName.equals(currentLauncherPackage) ||
                        hiddenApps.contains(packageName)) {
                        continue;
                    }

                    ApplicationInfo appInfo = packageManager.getApplicationInfo(packageName, 0);
                    boolean isSystemApp = (appInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0;

                    if (!showSystemApps && isSystemApp) {
                        continue;
                    }

                    result.add(new AppModel(
                            packageManager.getApplicationLabel(appInfo).toString(),
                            packageName,
                            formatMemorySize(ramUsage),
                            packageManager.getApplicationIcon(appInfo),
                            isSystemApp
                    ));
                } catch (PackageManager.NameNotFoundException ignored) {}
            }
            Collections.sort(result, (a1, a2) -> {
                if (!a1.isSystemApp() && a2.isSystemApp()) return -1;
                if (a1.isSystemApp() && !a2.isSystemApp()) return 1;
                return a1.getAppName().compareToIgnoreCase(a2.getAppName());
            });

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

    // Load all installed applications
    public void loadAllApps(Consumer<List<AppModel>> callback) {
        executor.execute(() -> {
            PackageManager pm = context.getPackageManager();
            List<ApplicationInfo> packages = pm.getInstalledApplications(PackageManager.GET_META_DATA);
            List<AppModel> allApps = new ArrayList<>();
            for (ApplicationInfo appInfo : packages) {
                if (appInfo.packageName.equals(context.getPackageName())) {
                    continue; // Skip our own app
                }
                boolean isSystem = (appInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
                allApps.add(new AppModel(
                        pm.getApplicationLabel(appInfo).toString(),
                        appInfo.packageName,
                        "-", // RAM placeholder
                        pm.getApplicationIcon(appInfo),
                        isSystem
                ));
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

        String command = packageNames.stream()
                .map(pkg -> "am force-stop " + pkg)
                .collect(Collectors.joining("; "));
        shellManager.runShellCommand(command, onComplete);
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
        shellManager.runShellCommand("am force-stop " + packageName, onComplete);
    }

    // Toggle visibility of system apps
    public void setShowSystemApps(boolean show) {
        this.showSystemApps = show;
    }

    // Return a copy of the current apps list
    public List<AppModel> getAppsList() {
        return new ArrayList<>(currentAppsList);
    }
}
