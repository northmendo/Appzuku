package com.northmendo.Appzuku;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.util.Log;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import rikka.shizuku.Shizuku;
import rikka.shizuku.ShizukuRemoteProcess;

/**
 * Manages shell command execution via Root or Shizuku.
 * Prioritizes Root access over Shizuku when both are available.
 */
public class ShellManager {
    private static final String TAG = "ShellManager";

    private final Context context;
    private final Handler handler;
    private final ExecutorService executor;

    // Root access state - use AtomicBoolean for thread safety
    private volatile Boolean hasRoot = null;
    private final AtomicBoolean rootCheckInProgress = new AtomicBoolean(false);

    @SuppressWarnings("deprecation")
    private Shizuku.OnRequestPermissionResultListener shizukuPermissionListener;

    public ShellManager(Context context, Handler handler, ExecutorService executor) {
        this.context = context;
        this.handler = handler;
        this.executor = executor;

        // Start root check in background immediately
        initializeRootCheck();
    }

    /**
     * Initialize root access check in background.
     * This prevents blocking the main thread.
     */
    private void initializeRootCheck() {
        if (rootCheckInProgress.compareAndSet(false, true)) {
            executor.execute(() -> {
                try {
                    hasRoot = checkRootAccessBlocking();
                    Log.d(TAG, "Root access check complete: " + hasRoot);
                } finally {
                    rootCheckInProgress.set(false);
                }
            });
        }
    }

    /**
     * Blocking check for root access. Should only be called from background thread.
     */
    private boolean checkRootAccessBlocking() {
        Process process = null;
        DataOutputStream os = null;
        try {
            process = Runtime.getRuntime().exec("su");
            os = new DataOutputStream(process.getOutputStream());
            os.writeBytes("id\n");
            os.writeBytes("exit\n");
            os.flush();
            int exitValue = process.waitFor();
            return exitValue == 0;
        } catch (IOException | InterruptedException e) {
            Log.d(TAG, "Root not available: " + e.getMessage());
            return false;
        } finally {
            try {
                if (os != null) os.close();
                if (process != null) process.destroy();
            } catch (IOException ignored) {}
        }
    }

    /**
     * Set the permission listener for Shizuku.
     */
    public void setShizukuPermissionListener(Shizuku.OnRequestPermissionResultListener listener) {
        this.shizukuPermissionListener = listener;
        Shizuku.addRequestPermissionResultListener(shizukuPermissionListener);
    }

    /**
     * Remove the Shizuku permission listener.
     */
    public void removeShizukuPermissionListener() {
        if (shizukuPermissionListener != null) {
            Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener);
        }
    }

    /**
     * Check if the device has root access.
     * Returns cached result if available, otherwise returns false (check may still be in progress).
     * For blocking check, use from background thread only.
     */
    public boolean hasRootAccess() {
        if (hasRoot == null) {
            // If called before check completes, do blocking check on current thread
            // This should only happen if called from background thread
            if (!Thread.currentThread().getName().equals("main")) {
                hasRoot = checkRootAccessBlocking();
            } else {
                // On main thread, return false and let Shizuku be used
                return false;
            }
        }
        return hasRoot;
    }

    /**
     * Check if Shizuku is available and has necessary permissions.
     */
    public boolean hasShizukuPermission() {
        try {
            return Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
        } catch (Exception e) {
            Log.w(TAG, "Error checking Shizuku permission", e);
            return false;
        }
    }

    /**
     * Check for shell permissions and request Shizuku if needed.
     */
    public void checkShellPermissions() {
        try {
            if (Shizuku.pingBinder()) {
                if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                    Shizuku.requestPermission(0);
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Error checking shell permissions", e);
        }
    }

    /**
     * Check if any shell permission (Root or Shizuku) is available.
     * This is non-blocking and will return true if Shizuku is available
     * even if root check hasn't completed yet.
     */
    public boolean hasAnyShellPermission() {
        // Check Shizuku first (non-blocking)
        if (hasShizukuPermission()) {
            return true;
        }
        // Check cached root result (null means check in progress)
        return hasRoot != null && hasRoot;
    }

    /**
     * Run a shell command prioritizing Root, then Shizuku.
     * Executes on background thread and posts callback to main handler.
     */
    public void runShellCommand(String command, Runnable onSuccess) {
        executor.execute(() -> {
            boolean executed = false;
            if (hasRootAccess()) {
                executed = executeRootCommand(command, onSuccess, null);
            }
            if (!executed && hasShizukuPermission()) {
                executed = executeShizukuCommand(command, onSuccess);
            }
            if (!executed && onSuccess != null) {
                handler.post(onSuccess);
            }
        });
    }

    /**
     * Run a shell command and process its output line by line.
     */
    public void runShellCommandWithOutput(String command, Consumer<String> outputProcessor) {
        executor.execute(() -> {
            boolean executed = false;
            if (hasRootAccess()) {
                executed = executeRootCommand(command, null, outputProcessor);
            }
            if (!executed && hasShizukuPermission()) {
                executed = executeShizukuCommandWithOutput(command, outputProcessor);
            }
        });
    }

    /**
     * Run a shell command and return the full output.
     * This method is blocking and should be called from a background thread.
     */
    public String runShellCommandAndGetFullOutput(String command) {
        if (hasRootAccess()) {
            return executeRootCommandAndGetFullOutput(command);
        } else if (hasShizukuPermission()) {
            return executeShizukuCommandAndGetFullOutput(command);
        }
        return null;
    }

    // --- Private helper methods ---

    private boolean executeRootCommand(String command, Runnable onSuccess, Consumer<String> outputProcessor) {
        Process process = null;
        DataOutputStream os = null;
        BufferedReader reader = null;
        try {
            process = Runtime.getRuntime().exec("su");
            os = new DataOutputStream(process.getOutputStream());
            os.writeBytes(command + "\n");
            os.writeBytes("exit\n");
            os.flush();

            if (outputProcessor != null) {
                reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
                String line;
                while ((line = reader.readLine()) != null) {
                    final String finalLine = line;
                    handler.post(() -> outputProcessor.accept(finalLine));
                }
                BufferedReader errorReader = new BufferedReader(new InputStreamReader(process.getErrorStream()));
                while ((line = errorReader.readLine()) != null) {
                    final String finalLine = line;
                    handler.post(() -> outputProcessor.accept("ERROR: " + finalLine));
                }
                errorReader.close();
            }
            process.waitFor();
            if (onSuccess != null) {
                handler.post(onSuccess);
            }
            return true;
        } catch (IOException | InterruptedException e) {
            Log.e(TAG, "Root command failed", e);
            return false;
        } finally {
            try {
                if (os != null) os.close();
                if (reader != null) reader.close();
                if (process != null) process.destroy();
            } catch (IOException ignored) {}
        }
    }

    private boolean executeShizukuCommand(String command, Runnable onSuccess) {
        ShizukuRemoteProcess remote = null;
        BufferedReader reader = null;
        try {
            remote = Shizuku.newProcess(new String[]{"sh", "-c", command}, null, "/");
            reader = new BufferedReader(new InputStreamReader(remote.getInputStream()));
            while (reader.readLine() != null) {
                // Consume output
            }
            remote.waitFor();
            if (onSuccess != null) {
                handler.post(onSuccess);
            }
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Shizuku command failed", e);
            return false;
        } finally {
            try {
                if (reader != null) reader.close();
            } catch (IOException ignored) {}
            if (remote != null) {
                remote.destroy();
            }
        }
    }

    private boolean executeShizukuCommandWithOutput(String command, Consumer<String> outputProcessor) {
        ShizukuRemoteProcess remote = null;
        BufferedReader reader = null;
        try {
            remote = Shizuku.newProcess(new String[]{"sh", "-c", command}, null, "/");
            reader = new BufferedReader(new InputStreamReader(remote.getInputStream()));
            String line;
            while ((line = reader.readLine()) != null) {
                final String finalLine = line;
                handler.post(() -> outputProcessor.accept(finalLine));
            }
            BufferedReader errorReader = new BufferedReader(new InputStreamReader(remote.getErrorStream()));
            while ((line = errorReader.readLine()) != null) {
                final String finalLine = line;
                handler.post(() -> outputProcessor.accept("ERROR: " + finalLine));
            }
            errorReader.close();
            remote.waitFor();
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Shizuku command with output failed", e);
            return false;
        } finally {
            try {
                if (reader != null) reader.close();
            } catch (IOException ignored) {}
            if (remote != null) {
                remote.destroy();
            }
        }
    }

    private String executeRootCommandAndGetFullOutput(String command) {
        Process process = null;
        DataOutputStream os = null;
        BufferedReader reader = null;
        StringBuilder output = new StringBuilder();
        try {
            process = Runtime.getRuntime().exec("su");
            os = new DataOutputStream(process.getOutputStream());
            os.writeBytes(command + "\n");
            os.writeBytes("exit\n");
            os.flush();

            reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
            }
            BufferedReader errorReader = new BufferedReader(new InputStreamReader(process.getErrorStream()));
            while ((line = errorReader.readLine()) != null) {
                output.append("ERROR: ").append(line).append("\n");
            }
            errorReader.close();
            process.waitFor();
            return output.toString();
        } catch (IOException | InterruptedException e) {
            Log.e(TAG, "Root command get output failed", e);
            return null;
        } finally {
            try {
                if (os != null) os.close();
                if (reader != null) reader.close();
                if (process != null) process.destroy();
            } catch (IOException ignored) {}
        }
    }

    private String executeShizukuCommandAndGetFullOutput(String command) {
        ShizukuRemoteProcess remote = null;
        BufferedReader reader = null;
        StringBuilder output = new StringBuilder();
        try {
            remote = Shizuku.newProcess(new String[]{"sh", "-c", command}, null, "/");
            reader = new BufferedReader(new InputStreamReader(remote.getInputStream()));
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
            }
            BufferedReader errorReader = new BufferedReader(new InputStreamReader(remote.getErrorStream()));
            while ((line = errorReader.readLine()) != null) {
                output.append("ERROR: ").append(line).append("\n");
            }
            errorReader.close();
            remote.waitFor();
            return output.toString();
        } catch (Exception e) {
            Log.e(TAG, "Shizuku command get output failed", e);
            return null;
        } finally {
            try {
                if (reader != null) reader.close();
            } catch (IOException ignored) {}
            if (remote != null) {
                remote.destroy();
            }
        }
    }
}
