package com.northmendo.Appzuku;

import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.os.Handler;
import android.os.Looper;

import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;

import com.northmendo.Appzuku.databinding.ActivitySettingsBinding;

import java.util.Set;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import androidx.work.ExistingPeriodicWorkPolicy;

import static com.northmendo.Appzuku.PreferenceKeys.*;
import static com.northmendo.Appzuku.AppConstants.*;

public class SettingsActivity extends BaseActivity {
    private static final String TAG = "SettingsActivity";

    private ActivitySettingsBinding binding;
    private BackgroundAppManager appManager;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivitySettingsBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        // Initialize app manager for dialogs
        ShellManager shellManager = new ShellManager(this, handler, executor);
        appManager = new BackgroundAppManager(this.getApplicationContext(), handler, executor, shellManager);

        setupToolbar();
        loadSettings();
        setupListeners();
    }

    private void setupToolbar() {
        setSupportActionBar(binding.toolbar);
        binding.toolbar.setNavigationOnClickListener(v -> finish());
    }

    private void loadSettings() {
        // Load theme
        int theme = sharedPreferences.getInt(KEY_THEME,
                androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
        updateThemeText(theme);

        // Load background service state
        boolean serviceEnabled = sharedPreferences.getBoolean(KEY_AUTO_KILL_ENABLED, false);
        binding.switchAutoKill.setChecked(serviceEnabled);

        // Load periodic kill state
        boolean periodicKillEnabled = sharedPreferences.getBoolean(KEY_PERIODIC_KILL_ENABLED, false);
        binding.switchPeriodicKill.setChecked(periodicKillEnabled);

        // Load kill interval
        int killInterval = sharedPreferences.getInt(KEY_KILL_INTERVAL, DEFAULT_KILL_INTERVAL_MS);
        updateKillIntervalText(killInterval);

        // Load kill on screen off
        binding.switchKillScreenOff.setChecked(sharedPreferences.getBoolean(KEY_KILL_ON_SCREEN_OFF, false));

        // Load RAM threshold
        boolean ramThresholdEnabled = sharedPreferences.getBoolean(KEY_RAM_THRESHOLD_ENABLED, false);
        binding.switchRamThreshold.setChecked(ramThresholdEnabled);
        int ramThreshold = sharedPreferences.getInt(KEY_RAM_THRESHOLD, DEFAULT_RAM_THRESHOLD_PERCENT);
        updateRamThresholdText(ramThreshold);

        // Update visibility of automation options
        updateAutomationOptionsVisibility(serviceEnabled, periodicKillEnabled);

        // Load show system apps
        boolean showSystemApps = sharedPreferences.getBoolean(KEY_SHOW_SYSTEM_APPS, false);
        binding.switchShowSystem.setChecked(showSystemApps);

        // Load show persistent apps
        boolean showPersistentApps = sharedPreferences.getBoolean(KEY_SHOW_PERSISTENT_APPS, false);
        binding.switchShowPersistent.setChecked(showPersistentApps);

        // Set version text
        try {
            String versionName = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
            binding.textVersion.setText("Appzuku v" + versionName);
        } catch (Exception e) {
            binding.textVersion.setText("Appzuku");
        }
    }

    private void setupListeners() {
        // Theme selector
        binding.layoutTheme.setOnClickListener(v -> showThemeDialog());

        // Background Service toggle
        binding.switchAutoKill.setOnCheckedChangeListener((buttonView, isChecked) -> {
            sharedPreferences.edit().putBoolean(KEY_AUTO_KILL_ENABLED, isChecked).apply();
            boolean periodicEnabled = binding.switchPeriodicKill.isChecked();
            updateAutomationOptionsVisibility(isChecked, periodicEnabled);

            if (isChecked) {
                // Start the background service
                startService(new Intent(this, ShappkyService.class));
                // Schedule backup worker (min 15m interval)
                PeriodicWorkRequest request = new PeriodicWorkRequest.Builder(
                        AutoKillWorker.class, 15, TimeUnit.MINUTES)
                        .build();
                WorkManager.getInstance(this).enqueueUniquePeriodicWork(
                        "AutoKillWorker", ExistingPeriodicWorkPolicy.UPDATE, request);
            } else {
                // Stop the service
                stopService(new Intent(this, ShappkyService.class));
                WorkManager.getInstance(this).cancelUniqueWork("AutoKillWorker");
            }
        });

        // Periodic Auto-Kill toggle
        binding.switchPeriodicKill.setOnCheckedChangeListener((buttonView, isChecked) -> {
            sharedPreferences.edit().putBoolean(KEY_PERIODIC_KILL_ENABLED, isChecked).apply();
            boolean serviceEnabled = binding.switchAutoKill.isChecked();
            updateAutomationOptionsVisibility(serviceEnabled, isChecked);
        });

        // Kill on screen off
        binding.switchKillScreenOff.setOnCheckedChangeListener((buttonView, isChecked) -> {
            sharedPreferences.edit().putBoolean(KEY_KILL_ON_SCREEN_OFF, isChecked).apply();
        });

        // RAM threshold
        binding.switchRamThreshold.setOnCheckedChangeListener((buttonView, isChecked) -> {
            sharedPreferences.edit().putBoolean(KEY_RAM_THRESHOLD_ENABLED, isChecked).apply();
        });
        binding.layoutRamThresholdToggle.setOnClickListener(v -> {
            if (binding.switchRamThreshold.isChecked()) {
                showRamThresholdDialog();
            }
        });

        // Kill interval selector
        binding.layoutKillInterval.setOnClickListener(v -> showKillIntervalDialog());

        // Show system apps toggle
        binding.switchShowSystem.setOnCheckedChangeListener((buttonView, isChecked) -> {
            sharedPreferences.edit().putBoolean(KEY_SHOW_SYSTEM_APPS, isChecked).apply();
        });

        // Show persistent apps toggle
        binding.switchShowPersistent.setOnCheckedChangeListener((buttonView, isChecked) -> {
            sharedPreferences.edit().putBoolean(KEY_SHOW_PERSISTENT_APPS, isChecked).apply();
        });

        // Whitelist
        binding.layoutWhitelist.setOnClickListener(v -> showWhitelistDialog());

        // Hidden apps
        binding.layoutHiddenApps.setOnClickListener(v -> showHiddenAppsDialog());

        // Autostart Prevention
        binding.layoutAutostart.setOnClickListener(v -> showAutostartDialog());

        // Kill Mode
        binding.layoutKillMode.setOnClickListener(v -> showKillModeDialog());
        binding.layoutBlacklist.setOnClickListener(v -> showBlacklistDialog());

        // Help
        binding.layoutHelp.setOnClickListener(v -> startActivity(new Intent(this, HelpActivity.class)));

        // Clear Cache
        binding.layoutClearCache.setOnClickListener(v -> {
            binding.layoutClearCache.setEnabled(false);
            appManager.clearCaches(() -> binding.layoutClearCache.setEnabled(true));
        });

        // Statistics
        binding.layoutStats.setOnClickListener(v -> showStatsDialog());

        // GitHub
        binding.layoutGithub.setOnClickListener(v -> openUrl("https://github.com/northmendo/Appzuku"));

        // Check for Updates
        binding.layoutCheckUpdates.setOnClickListener(v -> openUrl("https://github.com/northmendo/Appzuku/releases"));

        // Donate
        binding.layoutDonate
                .setOnClickListener(v -> openUrl("https://www.paypal.com/donate/?hosted_button_id=DDJRFUXHSHRVN"));

        updateKillModeVisibility();
    }

    private void updateKillModeVisibility() {
        int mode = appManager.getKillMode();
        binding.textKillMode.setText(mode == 0 ? "Whitelist (Default)" : "Blacklist");
        binding.layoutBlacklist.setVisibility(mode == 1 ? View.VISIBLE : View.GONE);
        binding.layoutWhitelist.setVisibility(mode == 0 ? View.VISIBLE : View.GONE);
    }

    private void showKillModeDialog() {
        String[] modes = { "Whitelist Mode (Kill all except...)", "Blacklist Mode (Kill only...)" };
        new AlertDialog.Builder(this)
                .setTitle("Select Kill Mode")
                .setSingleChoiceItems(modes, appManager.getKillMode(), (dialog, which) -> {
                    appManager.setKillMode(which);
                    updateKillModeVisibility();
                    dialog.dismiss();
                })
                .show();
    }

    private void showBlacklistDialog() {
        LayoutInflater inflater = this.getLayoutInflater();
        View dialogView = inflater.inflate(R.layout.dialog_filter, null);
        ListView listView = dialogView.findViewById(R.id.filter_list_view);
        ProgressBar progressBar = dialogView.findViewById(R.id.filter_loading_progress);
        EditText searchBox = dialogView.findViewById(R.id.filter_search);

        AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setTitle("Blacklisted apps (Target to kill)")
                .setView(dialogView);

        AlertDialog dialog = builder.create();
        dialog.getWindow().setBackgroundDrawable(
                new ColorDrawable(ContextCompat.getColor(this, R.color.background_primary)));
        dialog.setButton(AlertDialog.BUTTON_POSITIVE, "Save", (d, w) -> {
        });
        dialog.setButton(AlertDialog.BUTTON_NEGATIVE, "Cancel", (d, w) -> d.dismiss());
        searchBox.setVisibility(View.GONE);
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(Color.WHITE);
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(Color.WHITE);

        appManager.loadAllApps(allApps -> {
            Set<String> blacklisted = appManager.getBlacklistedApps();
            FilterAppsAdapter filterAdapter = new FilterAppsAdapter(this, allApps, blacklisted);
            listView.setAdapter(filterAdapter);
            progressBar.setVisibility(View.GONE);
            listView.setVisibility(View.VISIBLE);
            searchBox.setVisibility(View.VISIBLE);

            searchBox.addTextChangedListener(new TextWatcher() {
                @Override
                public void beforeTextChanged(CharSequence s, int start, int count, int after) {
                }

                @Override
                public void onTextChanged(CharSequence s, int start, int before, int count) {
                    filterAdapter.getFilter().filter(s);
                }

                @Override
                public void afterTextChanged(Editable s) {
                }
            });

            dialog.setButton(AlertDialog.BUTTON_POSITIVE, "Save", (d, w) -> {
                appManager.saveBlacklistedApps(filterAdapter.getSelectedPackages());
            });
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(Color.WHITE);
        });
    }

    private void showStatsDialog() {
        executor.execute(() -> {
            // Get stats for last 12 hours
            long twelveHoursAgo = System.currentTimeMillis() - STATS_HISTORY_DURATION_MS;
            java.util.List<com.northmendo.Appzuku.db.AppStats> statsList = com.northmendo.Appzuku.db.AppDatabase
                    .getInstance(this).appStatsDao().getAllStatsSince(twelveHoursAgo);

            final List<String> highRelaunchPackages = new ArrayList<>();
            StringBuilder sb = new StringBuilder();

            if (statsList.isEmpty()) {
                sb.append("No activity in the last 12 hours.");
            } else {
                java.text.SimpleDateFormat timeFormat = new java.text.SimpleDateFormat("h:mm a",
                        java.util.Locale.getDefault());

                for (com.northmendo.Appzuku.db.AppStats stats : statsList) {
                    if (stats.killCount > 0 || stats.relaunchCount > 0) {
                        // App name - prefer stored appName, fall back to package name
                        String displayName;
                        if (stats.appName != null && !stats.appName.isEmpty()) {
                            displayName = stats.appName;
                        } else {
                            displayName = stats.packageName;
                            int lastDot = displayName.lastIndexOf('.');
                            if (lastDot != -1 && lastDot < displayName.length() - 1) {
                                displayName = displayName.substring(lastDot + 1);
                            }
                        }
                        sb.append("● ").append(displayName).append("\n");

                        // Kill stats
                        if (stats.killCount > 0) {
                            sb.append("   Killed: ").append(stats.killCount).append("x");
                            if (stats.lastKillTime > 0) {
                                sb.append(" (last: ").append(timeFormat.format(new java.util.Date(stats.lastKillTime)))
                                        .append(")");
                            }
                            sb.append("\n");
                        }

                        // Relaunch stats
                        if (stats.relaunchCount > 0) {
                            sb.append("   Relaunched: ").append(stats.relaunchCount).append("x");
                            if (stats.lastRelaunchTime > 0) {
                                sb.append(" (last: ")
                                        .append(timeFormat.format(new java.util.Date(stats.lastRelaunchTime)))
                                        .append(")");
                            }
                            sb.append("\n");

                            if (stats.relaunchCount > RELAUNCH_GREEDY_THRESHOLD) {
                                highRelaunchPackages.add(stats.packageName);
                            }
                        }
                        sb.append("\n");
                    }
                }
            }

            String message = sb.length() > 0 ? sb.toString().trim() : "No activity in the last 12 hours.";
            handler.post(() -> {
                AlertDialog.Builder builder = new AlertDialog.Builder(this)
                        .setTitle("Kill History (Last 12h)")
                        .setMessage(message)
                        .setPositiveButton("OK", null);

                if (!highRelaunchPackages.isEmpty()) {
                    builder.setNeutralButton("Disable Autostart for Greedy Apps", (d, w) -> {
                        Set<String> currentDisabled = appManager.getAutostartDisabledApps();
                        currentDisabled.addAll(highRelaunchPackages);
                        appManager.saveAutostartDisabledApps(currentDisabled);
                        appManager.loadAllApps(allApps -> {
                            appManager.applyAutostartPrevention(allApps, currentDisabled);
                        });
                    });
                }
                builder.show();
            });
        });
    }

    private void updateThemeText(int themeValue) {
        for (int i = 0; i < THEME_VALUES.length; i++) {
            if (THEME_VALUES[i] == themeValue) {
                binding.textTheme.setText(THEME_LABELS[i]);
                return;
            }
        }
    }

    private void showThemeDialog() {
        int currentTheme = sharedPreferences.getInt(KEY_THEME,
                androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
        int selectedIndex = 0;
        for (int i = 0; i < THEME_VALUES.length; i++) {
            if (THEME_VALUES[i] == currentTheme) {
                selectedIndex = i;
                break;
            }
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Select App Theme");
        builder.setSingleChoiceItems(THEME_LABELS, selectedIndex, (dialog, which) -> {
            int newTheme = THEME_VALUES[which];
            sharedPreferences.edit().putInt(KEY_THEME, newTheme).apply();
            updateThemeText(newTheme);
            androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(newTheme);
            dialog.dismiss();
            recreate(); // Recreate activity to apply theme
        });
        builder.setNegativeButton("Cancel", null);

        AlertDialog dialog = builder.create();
        dialog.getWindow().setBackgroundDrawable(
                new ColorDrawable(ContextCompat.getColor(this, R.color.background_primary)));
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(Color.WHITE);
    }

    private void updateAutomationOptionsVisibility(boolean serviceEnabled, boolean periodicEnabled) {
        float serviceAlpha = serviceEnabled ? 1.0f : 0.5f;
        float periodicAlpha = (serviceEnabled && periodicEnabled) ? 1.0f : 0.5f;

        // Sub-options depend on service being enabled
        binding.layoutPeriodicKill.setAlpha(serviceAlpha);
        binding.switchPeriodicKill.setEnabled(serviceEnabled);

        binding.layoutScreenLock.setAlpha(serviceAlpha);
        binding.switchKillScreenOff.setEnabled(serviceEnabled);

        binding.layoutRamThresholdToggle.setAlpha(serviceAlpha);
        binding.switchRamThreshold.setEnabled(serviceEnabled);

        // Kill interval depends on both service AND periodic kill being enabled
        binding.layoutKillInterval.setAlpha(periodicAlpha);
        binding.layoutKillInterval.setClickable(serviceEnabled && periodicEnabled);
    }

    private void updateKillIntervalText(int intervalMs) {
        for (int i = 0; i < KILL_INTERVALS_MS.length; i++) {
            if (KILL_INTERVALS_MS[i] == intervalMs) {
                binding.textKillInterval.setText(KILL_INTERVAL_LABELS[i]);
                return;
            }
        }
        binding.textKillInterval.setText("Every " + (intervalMs / 1000) + " seconds");
    }

    private void showKillIntervalDialog() {
        if (!binding.switchAutoKill.isChecked() || !binding.switchPeriodicKill.isChecked()) {
            return;
        }

        int currentInterval = sharedPreferences.getInt(KEY_KILL_INTERVAL, DEFAULT_KILL_INTERVAL_MS);
        int selectedIndex = 1; // default
        for (int i = 0; i < KILL_INTERVALS_MS.length; i++) {
            if (KILL_INTERVALS_MS[i] == currentInterval) {
                selectedIndex = i;
                break;
            }
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Kill Interval");
        builder.setSingleChoiceItems(KILL_INTERVAL_LABELS, selectedIndex, (dialog, which) -> {
            int newInterval = KILL_INTERVALS_MS[which];
            sharedPreferences.edit().putInt(KEY_KILL_INTERVAL, newInterval).apply();
            updateKillIntervalText(newInterval);
            dialog.dismiss();
        });
        builder.setNegativeButton("Cancel", null);

        AlertDialog dialog = builder.create();
        dialog.getWindow().setBackgroundDrawable(
                new ColorDrawable(ContextCompat.getColor(this, R.color.background_primary)));
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(Color.WHITE);
    }

    private void showWhitelistDialog() {
        LayoutInflater inflater = this.getLayoutInflater();
        View dialogView = inflater.inflate(R.layout.dialog_filter, null);
        ListView listView = dialogView.findViewById(R.id.filter_list_view);
        ProgressBar progressBar = dialogView.findViewById(R.id.filter_loading_progress);
        EditText searchBox = dialogView.findViewById(R.id.filter_search);

        AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setTitle("Whitelisted apps (never kill)")
                .setView(dialogView);

        AlertDialog whitelistDialog = builder.create();
        whitelistDialog.getWindow().setBackgroundDrawable(
                new ColorDrawable(ContextCompat.getColor(this, R.color.background_primary)));

        whitelistDialog.setButton(AlertDialog.BUTTON_NEGATIVE, "Cancel", (dialog, which) -> dialog.dismiss());
        whitelistDialog.setButton(AlertDialog.BUTTON_POSITIVE, "Save", (dialog, which) -> {
        });

        progressBar.setVisibility(View.VISIBLE);
        listView.setVisibility(View.GONE);
        searchBox.setVisibility(View.GONE);
        whitelistDialog.show();

        whitelistDialog.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(Color.WHITE);
        whitelistDialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(Color.WHITE);

        appManager.loadAllApps(allApps -> {
            Set<String> whitelistedApps = appManager.getWhitelistedApps();
            FilterAppsAdapter filterAdapter = new FilterAppsAdapter(this, allApps, whitelistedApps);
            listView.setAdapter(filterAdapter);
            listView.setOnItemClickListener(null);

            progressBar.setVisibility(View.GONE);
            listView.setVisibility(View.VISIBLE);
            searchBox.setVisibility(View.VISIBLE);

            searchBox.addTextChangedListener(new TextWatcher() {
                @Override
                public void beforeTextChanged(CharSequence s, int start, int count, int after) {
                }

                @Override
                public void onTextChanged(CharSequence s, int start, int before, int count) {
                    filterAdapter.getFilter().filter(s);
                }

                @Override
                public void afterTextChanged(Editable s) {
                }
            });

            whitelistDialog.setButton(AlertDialog.BUTTON_POSITIVE, "Save", (dialog, which) -> {
                Set<String> packagesToWhitelist = filterAdapter.getSelectedPackages();
                appManager.saveWhitelistedApps(packagesToWhitelist);
            });
            whitelistDialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(Color.WHITE);
        });
    }

    private void showHiddenAppsDialog() {
        LayoutInflater inflater = this.getLayoutInflater();
        View dialogView = inflater.inflate(R.layout.dialog_filter, null);
        ListView listView = dialogView.findViewById(R.id.filter_list_view);
        ProgressBar progressBar = dialogView.findViewById(R.id.filter_loading_progress);
        EditText searchBox = dialogView.findViewById(R.id.filter_search);

        AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setTitle("Select the apps you want to hide")
                .setView(dialogView);

        AlertDialog filterDialog = builder.create();
        filterDialog.getWindow().setBackgroundDrawable(
                new ColorDrawable(ContextCompat.getColor(this, R.color.background_primary)));

        filterDialog.setButton(AlertDialog.BUTTON_NEGATIVE, "Cancel", (dialog, which) -> dialog.dismiss());
        filterDialog.setButton(AlertDialog.BUTTON_POSITIVE, "Save", (dialog, which) -> {
        });

        progressBar.setVisibility(View.VISIBLE);
        listView.setVisibility(View.GONE);
        searchBox.setVisibility(View.GONE);
        filterDialog.show();

        filterDialog.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(Color.WHITE);
        filterDialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(Color.WHITE);

        appManager.loadAllApps(allApps -> {
            Set<String> hiddenApps = appManager.getHiddenApps();
            FilterAppsAdapter filterAdapter = new FilterAppsAdapter(this, allApps, hiddenApps);
            listView.setAdapter(filterAdapter);
            listView.setOnItemClickListener(null);

            progressBar.setVisibility(View.GONE);
            listView.setVisibility(View.VISIBLE);
            searchBox.setVisibility(View.VISIBLE);

            searchBox.addTextChangedListener(new TextWatcher() {
                @Override
                public void beforeTextChanged(CharSequence s, int start, int count, int after) {
                }

                @Override
                public void onTextChanged(CharSequence s, int start, int before, int count) {
                    filterAdapter.getFilter().filter(s);
                }

                @Override
                public void afterTextChanged(Editable s) {
                }
            });

            filterDialog.setButton(AlertDialog.BUTTON_POSITIVE, "Save", (dialog, which) -> {
                Set<String> packagesToHide = filterAdapter.getSelectedPackages();
                appManager.saveHiddenApps(packagesToHide);
            });
            filterDialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(Color.WHITE);
        });
    }

    private void showAutostartDialog() {
        LayoutInflater inflater = this.getLayoutInflater();
        View dialogView = inflater.inflate(R.layout.dialog_filter, null);
        ListView listView = dialogView.findViewById(R.id.filter_list_view);
        ProgressBar progressBar = dialogView.findViewById(R.id.filter_loading_progress);
        EditText searchBox = dialogView.findViewById(R.id.filter_search);

        AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setTitle("Autostart Prevention")
                .setView(dialogView);

        AlertDialog autostartDialog = builder.create();
        autostartDialog.getWindow().setBackgroundDrawable(
                new ColorDrawable(ContextCompat.getColor(this, R.color.background_primary)));

        autostartDialog.setButton(AlertDialog.BUTTON_NEGATIVE, "Cancel", (dialog, which) -> dialog.dismiss());
        autostartDialog.setButton(AlertDialog.BUTTON_POSITIVE, "Save", (dialog, which) -> {
        });

        progressBar.setVisibility(View.VISIBLE);
        listView.setVisibility(View.GONE);
        searchBox.setVisibility(View.GONE);
        autostartDialog.show();

        autostartDialog.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(Color.WHITE);
        autostartDialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(Color.WHITE);

        appManager.loadAllApps(allApps -> {
            Set<String> disabledApps = appManager.getAutostartDisabledApps();
            FilterAppsAdapter filterAdapter = new FilterAppsAdapter(this, allApps, disabledApps);
            listView.setAdapter(filterAdapter);
            listView.setOnItemClickListener(null);

            progressBar.setVisibility(View.GONE);
            listView.setVisibility(View.VISIBLE);
            searchBox.setVisibility(View.VISIBLE);

            searchBox.addTextChangedListener(new TextWatcher() {
                @Override
                public void beforeTextChanged(CharSequence s, int start, int count, int after) {
                }

                @Override
                public void onTextChanged(CharSequence s, int start, int before, int count) {
                    filterAdapter.getFilter().filter(s);
                }

                @Override
                public void afterTextChanged(Editable s) {
                }
            });

            autostartDialog.setButton(AlertDialog.BUTTON_POSITIVE, "Save", (dialog, which) -> {
                Set<String> packagesToDisable = filterAdapter.getSelectedPackages();
                appManager.saveAutostartDisabledApps(packagesToDisable);
                appManager.applyAutostartPrevention(allApps, packagesToDisable);
            });
            autostartDialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(Color.WHITE);
        });
    }

    private void updateRamThresholdText(int threshold) {
        binding.textRamThreshold.setText("Kill only when RAM usage > " + threshold + "%");
    }

    private void showRamThresholdDialog() {
        int current = sharedPreferences.getInt(KEY_RAM_THRESHOLD, DEFAULT_RAM_THRESHOLD_PERCENT);
        int selected = 1;
        for (int i = 0; i < RAM_THRESHOLD_VALUES.length; i++) {
            if (RAM_THRESHOLD_VALUES[i] == current) {
                selected = i;
                break;
            }
        }

        new AlertDialog.Builder(this)
                .setTitle("Select RAM Threshold")
                .setSingleChoiceItems(RAM_THRESHOLD_LABELS, selected, (dialog, which) -> {
                    sharedPreferences.edit().putInt(KEY_RAM_THRESHOLD, RAM_THRESHOLD_VALUES[which]).apply();
                    updateRamThresholdText(RAM_THRESHOLD_VALUES[which]);
                    dialog.dismiss();
                })
                .show();
    }

    private void openUrl(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception e) {
            Log.e(TAG, "Failed to open URL: " + url, e);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdownNow();
        binding = null;
    }
}
