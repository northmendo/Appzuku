package com.yn.shappky.util;

import android.os.Handler;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;

public class RamMonitor {
    private final Handler handler;
    private final ProgressBar ramUsageBar;
    private final TextView ramUsageText;
    private boolean isMonitoring = false;

    public RamMonitor(Handler handler, ProgressBar ramUsageBar, TextView ramUsageText) {
        this.handler = handler;
        this.ramUsageBar = ramUsageBar;
        this.ramUsageText = ramUsageText;
    }

    /**
     * Start monitoring RAM usage with periodic updates
     */
    public void startMonitoring() {
        if (isMonitoring) {
            return;
        }
        
        isMonitoring = true;
        Runnable ramUsageRunnable = new Runnable() {
            @Override
            public void run() {
                if (!isMonitoring) {
                    return;
                }
                
                try {
                    Process process = Runtime.getRuntime().exec("cat /proc/meminfo");
                    BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
                    String line;
                    int memMax = 0, memFree = 0;

                    // Read the first few lines of meminfo to get total and available memory
                    for (int i = 0; i < 3 && (line = reader.readLine()) != null; i++) {
                        if (line.startsWith("MemTotal")) {
                            memMax = Integer.parseInt(line.split("\\s+")[1]);
                        } else if (line.startsWith("MemAvailable")) {
                            memFree = Integer.parseInt(line.split("\\s+")[1]);
                        }
                    }
                    reader.close();
                    process.waitFor();

                    // Update UI with RAM usage information
                    if (memMax > 0 && memFree >= 0) {
                        int memUsed = memMax - memFree;
                        ramUsageBar.setMax(memMax);
                        ramUsageBar.setProgress(memUsed);
                        ramUsageText.setText(String.format("RAM Usage: %dMB / %dMB", memUsed / 1024, memMax / 1024));
                    }
                } catch (IOException | NumberFormatException | InterruptedException e) {
                    e.printStackTrace();
                }
                
                // Schedule the next update
                handler.postDelayed(this, 1000);
            }
        };
        
        handler.post(ramUsageRunnable);
    }

    /**
     * Stop monitoring RAM usage
     */
    public void stopMonitoring() {
        isMonitoring = false;
    }
}
