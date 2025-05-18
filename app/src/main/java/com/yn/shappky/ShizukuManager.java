package com.yn.shappky.util;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;

import rikka.shizuku.Shizuku;
import rikka.shizuku.ShizukuRemoteProcess;

public class ShizukuManager {
    private final Context context;
    private final Handler handler;
    private final ExecutorService executor;
    private Shizuku.OnRequestPermissionResultListener permissionListener;

    public ShizukuManager(Context context, Handler handler, ExecutorService executor) {
        this.context = context;
        this.handler = handler;
        this.executor = executor;
    }

    /**
     * Set the permission listener to handle Shizuku permission results
     */
    public void setPermissionListener(Shizuku.OnRequestPermissionResultListener listener) {
        this.permissionListener = listener;
        Shizuku.addRequestPermissionResultListener(permissionListener);
    }

    /**
     * Remove the permission listener when no longer needed
     */
    public void removePermissionListener() {
        if (permissionListener != null) {
            Shizuku.removeRequestPermissionResultListener(permissionListener);
        }
    }

    /**
     * Check if Shizuku is available and has necessary permissions
     */
    public void checkShizuku() {
        if (Shizuku.pingBinder()) {
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                Shizuku.requestPermission(0);
            } else {
                handler.post(() -> Toast.makeText(context, "Shizuku permission granted", Toast.LENGTH_SHORT).show());
            }
        } else {
            handler.post(() -> Toast.makeText(context, "Shizuku is not running", Toast.LENGTH_SHORT).show());
        }
    }

    /**
     * Check if Shizuku is available and has permission
     */
    public boolean hasShizukuPermission() {
        return Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
    }

    /**
     * Run a shell command using Shizuku in the background
     */
    public void runShellCommand(String command, Runnable onSuccess) {
        if (!hasShizukuPermission()) {
            handler.post(() -> Toast.makeText(context, "Shizuku permission required", Toast.LENGTH_SHORT).show());
            checkShizuku();
            return;
        }

        executor.execute(() -> {
            ShizukuRemoteProcess remote = null;
            try {
                remote = Shizuku.newProcess(new String[]{"sh", "-c", command}, null, "/");
                BufferedReader reader = new BufferedReader(new InputStreamReader(remote.getInputStream()));
                while (reader.readLine() != null) {
                    // Process output if needed
                }
                reader.close();
                remote.waitFor();
                if (onSuccess != null) {
                    handler.post(onSuccess);
                }
            } catch (Exception e) {
                e.printStackTrace();
            } finally {
                if (remote != null) {
                    remote.destroy();
                }
            }
        });
    }

    /**
     * Run a shell command and process the output
     */
    public void runShellCommandWithOutput(String command, Consumer<String> outputProcessor) {
        if (!hasShizukuPermission()) {
            return;
        }

        executor.execute(() -> {
            ShizukuRemoteProcess remote = null;
            try {
                remote = Shizuku.newProcess(new String[]{"sh", "-c", command}, null, "/");
                BufferedReader reader = new BufferedReader(new InputStreamReader(remote.getInputStream()));
                String line;
                while ((line = reader.readLine()) != null) {
                    final String finalLine = line;
                    handler.post(() -> outputProcessor.accept(finalLine));
                }
                reader.close();
                remote.waitFor();
            } catch (Exception e) {
                e.printStackTrace();
            } finally {
                if (remote != null) {
                    remote.destroy();
                }
            }
        });
    }
}
