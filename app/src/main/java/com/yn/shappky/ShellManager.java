package com.yn.shappky.util;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Handler;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;

import rikka.shizuku.Shizuku;
import rikka.shizuku.ShizukuRemoteProcess;

public class ShellManager {
    private final Context context;
    private final Handler handler;
    private final ExecutorService executor;
    private Boolean hasRoot; // Null indicates not checked yet
    @SuppressWarnings("deprecation")
    private Shizuku.OnRequestPermissionResultListener shizukuPermissionListener; // Only for Shizuku

    public ShellManager(Context context, Handler handler, ExecutorService executor) {
        this.context = context;
        this.handler = handler;
        this.executor = executor;
    }

    /**
     * Set the permission listener for Shizuku, if Shizuku is used.
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
     * Check if the device has root access. Caches the result.
     * This method blocks until the check is complete.
     */
    public boolean hasRootAccess() {
        if (hasRoot == null) {
            Process process = null;
            DataOutputStream os = null;
            BufferedReader reader = null;
            try {
                process = Runtime.getRuntime().exec("su");
                os = new DataOutputStream(process.getOutputStream());
                os.writeBytes("id\n"); // A simple command to verify root
                os.writeBytes("exit\n");
                os.flush();
                int exitValue = process.waitFor();
                hasRoot = (exitValue == 0); // 0 indicates success
            } catch (IOException | InterruptedException e) {
                hasRoot = false;
            } finally {
                try {
                    if (os != null) os.close();
                    if (reader != null) reader.close();
                    if (process != null) process.destroy();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
        return hasRoot;
    }

    /**
     * Check if Shizuku is available and has necessary permissions.
     */
    public boolean hasShizukuPermission() {
        return Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
    }

    /**
     * Check for shell permissions (Root first, then Shizuku).
     * If root is available, no further action is needed for permissions.
     * If root is not available, it attempts to check/request Shizuku permission.
     */
    public void checkShellPermissions() {
        if (Shizuku.pingBinder()) {
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                Shizuku.requestPermission(0); // Request Shizuku permission if not granted
            } 
        } 
    }

    /**
     * Check if any shell permission (Root or Shizuku) is available.
     */
    public boolean hasAnyShellPermission() {
        return hasRootAccess() || hasShizukuPermission();
    }

    /**
     * Run a shell command prioritizing Root, then Shizuku.
     * This method executes on the executor thread and posts success/failure to main handler.
     */
    public void runShellCommand(String command, Runnable onSuccess) {
        executor.execute(() -> {
            boolean executed = false;
            if (hasRootAccess()) {
                if (executeRootCommand(command, onSuccess, null)) {
                    executed = true;
                }
            }
            if (!executed && hasShizukuPermission()) {
                if (executeShizukuCommand(command, onSuccess)) {
                    executed = true;
                }
            }

            if (!executed) {
                if (onSuccess != null) { // Call onComplete even if no permissions to avoid deadlock in some cases
                    handler.post(onSuccess);
                }
            }
        });
    }

    /**
     * Run a shell command prioritizing Root, then Shizuku, and process its output line by line.
     * This method executes on the executor thread and posts output to main handler.
     */
    public void runShellCommandWithOutput(String command, Consumer<String> outputProcessor) {
        executor.execute(() -> {
            boolean executed = false;
            if (hasRootAccess()) {
                if (executeRootCommand(command, null, outputProcessor)) {
                    executed = true;
                }
            }
            if (!executed && hasShizukuPermission()) {
                if (executeShizukuCommandWithOutput(command, outputProcessor)) {
                    executed = true;
                }
            }
        });
    }

    /**
     * Run a shell command prioritizing Root, then Shizuku, and return the full output as a String.
     * This method is blocking and should be called from a background thread.
     * Returns null if no permissions or an error occurs.
     */
    public String runShellCommandAndGetFullOutput(String command) {
        if (hasRootAccess()) {
            return executeRootCommandAndGetFullOutput(command);
        } else if (hasShizukuPermission()) {
            return executeShizukuCommandAndGetFullOutput(command);
        } else {
            return null;
        }
    }

    // --- Private helper methods for command execution ---

    /**
     * Executes a command using root.
     * Returns true on successful execution (even if command output indicates error), false on unrecoverable error.
     */
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
                // Also read error stream
                BufferedReader errorReader = new BufferedReader(new InputStreamReader(process.getErrorStream()));
                while ((line = errorReader.readLine()) != null) {
                    final String finalLine = line;
                    handler.post(() -> outputProcessor.accept("ERROR: " + finalLine)); // Differentiate error output
                }
            }
            process.waitFor();
            if (onSuccess != null) {
                handler.post(onSuccess);
            }
            return true;
        } catch (IOException | InterruptedException e) {
            e.printStackTrace();
            return false;
        } finally {
            try {
                if (os != null) os.close();
                if (reader != null) reader.close();
                if (process != null) process.destroy();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    /**
     * Executes a command using Shizuku.
     * Returns true on successful execution, false on unrecoverable error.
     */
    private boolean executeShizukuCommand(String command, Runnable onSuccess) {
        ShizukuRemoteProcess remote = null;
        try {
            remote = Shizuku.newProcess(new String[]{"sh", "-c", command}, null, "/");
            // No need to read output if not processing, but consume to prevent buffer issues
            BufferedReader reader = new BufferedReader(new InputStreamReader(remote.getInputStream()));
            while (reader.readLine() != null) {
                // Consume output
            }
            reader.close();
            remote.waitFor();
            if (onSuccess != null) {
                handler.post(onSuccess);
            }
            return true;
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        } finally {
            if (remote != null) {
                remote.destroy();
            }
        }
    }

    /**
     * Executes a command using Shizuku and processes output line by line.
     * Returns true on successful execution, false on unrecoverable error.
     */
    private boolean executeShizukuCommandWithOutput(String command, Consumer<String> outputProcessor) {
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
            // Also read error stream
            BufferedReader errorReader = new BufferedReader(new InputStreamReader(remote.getErrorStream()));
            while ((line = errorReader.readLine()) != null) {
                final String finalLine = line;
                handler.post(() -> outputProcessor.accept("ERROR: " + finalLine));
            }
            remote.waitFor();
            return true;
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        } finally {
            if (remote != null) {
                remote.destroy();
            }
        }
    }

    /**
     * Executes a command using root and returns the full output as a String.
     * This method is blocking.
     */
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
            // Also read error stream
            BufferedReader errorReader = new BufferedReader(new InputStreamReader(process.getErrorStream()));
            while ((line = errorReader.readLine()) != null) {
                output.append("ERROR: ").append(line).append("\n");
            }
            process.waitFor();
            return output.toString();
        } catch (IOException | InterruptedException e) {
            e.printStackTrace();
            return null;
        } finally {
            try {
                if (os != null) os.close();
                if (reader != null) reader.close();
                if (process != null) process.destroy();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    /**
     * Executes a command using Shizuku and returns the full output as a String.
     * This method is blocking.
     */
    private String executeShizukuCommandAndGetFullOutput(String command) {
        ShizukuRemoteProcess remote = null;
        StringBuilder output = new StringBuilder();
        BufferedReader reader = null;
        try {
            remote = Shizuku.newProcess(new String[]{"sh", "-c", command}, null, "/");
            reader = new BufferedReader(new InputStreamReader(remote.getInputStream()));
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
            }
            // Also read error stream
            BufferedReader errorReader = new BufferedReader(new InputStreamReader(remote.getErrorStream()));
            while ((line = errorReader.readLine()) != null) {
                output.append("ERROR: ").append(line).append("\n");
            }
            remote.waitFor();
            return output.toString();
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        } finally {
            if (remote != null) {
                remote.destroy();
            }
            try {
                if (reader != null) reader.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }
}
