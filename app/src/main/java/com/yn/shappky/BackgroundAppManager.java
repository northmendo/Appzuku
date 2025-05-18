package com.yn.shappky.util;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Handler;
import android.provider.Settings;
import android.widget.Toast;

import com.yn.shappky.model.AppModel;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import rikka.shizuku.Shizuku;
import rikka.shizuku.ShizukuRemoteProcess;

public class BackgroundAppManager {
    private final Context context;
    private final Handler handler;
    private final ExecutorService executor;
    private final ShizukuManager shizukuManager;
    private final List<AppModel> currentAppsList = new ArrayList<>();
    private boolean showSystemApps = false;

    public BackgroundAppManager(Context context, Handler handler, ExecutorService executor, ShizukuManager shizukuManager) {
        this.context = context;
        this.handler = handler;
        this.executor = executor;
        this.shizukuManager = shizukuManager;
    }

    // Load background apps using 'ps' command via Shizuku
    public void loadBackgroundApps(Consumer<List<AppModel>> callback) {
        executor.execute(() -> {
            List<AppModel> result = new ArrayList<>();
            PackageManager packageManager = context.getPackageManager();
            Set<String> runningPackagesFromPs = new HashSet<>();

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

            // Execute Shizuku command to get running processes
            try {
                if (shizukuManager.hasShizukuPermission()) {
                    String[] command = {"sh", "-c", "ps -A  | grep '.' | awk '{print $NF}' | sed 's/:.*//'"};
                    ShizukuRemoteProcess process = Shizuku.newProcess(command, null, null);
                    BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
                    String line;
                    while ((line = reader.readLine()) != null) {
                        String packageName = line.trim();
                        if (packageName.isEmpty() || !packageName.contains(".")) {
                            continue;
                        }
                        try {
                            packageManager.getApplicationInfo(packageName, 0);
                            runningPackagesFromPs.add(packageName);
                        } catch (PackageManager.NameNotFoundException ignored) {}
                    }
                    reader.close();
                    process.waitFor();
                    process.destroy();
                } else {
                    handler.post(() -> Toast.makeText(context, "Shizuku permission required to list running apps", Toast.LENGTH_SHORT).show());
                }
            } catch (Exception e) {
                handler.post(() -> Toast.makeText(context, "Error getting running apps", Toast.LENGTH_SHORT).show());
            }

            // Process running packages
            for (String packageName : runningPackagesFromPs) {
                try {
                    if (packageName.equals("com.yn.shappky") ||
                        packageName.equals("com.android.systemui") ||
                        packageName.equals(currentKeyboardPackage) ||
                        packageName.equals(currentLauncherPackage)) {
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
                            packageManager.getApplicationIcon(appInfo),
                            isSystemApp
                    ));
                } catch (PackageManager.NameNotFoundException ignored) {}
            }

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

    // Kill specified packages using Shizuku
    public void killPackages(List<String> packageNames, Runnable onComplete) {
        if (!shizukuManager.hasShizukuPermission()) {
            handler.post(() -> Toast.makeText(context, "Shizuku permission required", Toast.LENGTH_SHORT).show());
            shizukuManager.checkShizuku();
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
        shizukuManager.runShellCommand(command, onComplete);
    }

    // Kill a single app by package name
    public void killApp(String packageName, Runnable onComplete) {
        if (!shizukuManager.hasShizukuPermission()) {
            handler.post(() -> Toast.makeText(context, "Shizuku permission required", Toast.LENGTH_SHORT).show());
            shizukuManager.checkShizuku();
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
        shizukuManager.runShellCommand("am force-stop " + packageName, onComplete);
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