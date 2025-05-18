package com.yn.shappky;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.ImageButton;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.yn.shappky.adapter.BackgroundAppsAdapter;
import com.yn.shappky.databinding.ActivityMainBinding;
import com.yn.shappky.model.AppModel;
import com.yn.shappky.util.BackgroundAppManager;
import com.yn.shappky.util.RamMonitor;
import com.yn.shappky.util.ShizukuManager;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

import rikka.shizuku.Shizuku;

public class MainActivity extends AppCompatActivity {
    private ActivityMainBinding binding;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private ShizukuManager shizukuManager;
    private BackgroundAppManager appManager;
    private RamMonitor ramMonitor;
    private BackgroundAppsAdapter listAdapter;
    private final List<AppModel> appsDataList = new ArrayList<>();

    // Handle Shizuku permission results
    private final Shizuku.OnRequestPermissionResultListener permissionListener = (requestCode, grantResult) -> {
        if (grantResult == PackageManager.PERMISSION_GRANTED) {
            runOnUiThread(() -> Toast.makeText(this, "Shizuku permission granted", Toast.LENGTH_SHORT).show());
            loadBackgroundApps();
        } else {
            runOnUiThread(() -> Toast.makeText(this, "Shizuku permission denied", Toast.LENGTH_SHORT).show());
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        // Setup toolbar
        setSupportActionBar(binding.toolbar);
        binding.toolbar.setTitleTextColor(Color.WHITE);
        getWindow().getDecorView().setBackgroundColor(Color.parseColor("#17181C"));

        // Initialize components
        shizukuManager = new ShizukuManager(this, handler, executor);
        appManager = new BackgroundAppManager(this, handler, executor, shizukuManager);
        ramMonitor = new RamMonitor(handler, binding.ramUsage, binding.ramUsageText);
        listAdapter = new BackgroundAppsAdapter(this, appsDataList);
        binding.listview1.setAdapter(listAdapter);

        // Configure listeners
        setupListeners();

        // Initialize Shizuku and load apps
        shizukuManager.setPermissionListener(permissionListener);
        shizukuManager.checkShizuku();
        loadBackgroundApps();
        ramMonitor.startMonitoring();
    }

    // Setup event listeners
    private void setupListeners() {
        binding.swiperefreshlayout1.setOnRefreshListener(this::loadBackgroundApps);
        binding.fab.setOnClickListener(view -> killSelectedApps());
        listAdapter.setOnAppActionListener(packageName -> appManager.killApp(packageName, this::loadBackgroundApps));
        binding.listview1.setOnItemClickListener((parent, view, position, id) -> {
            if (position >= 0 && position < appsDataList.size()) {
                AppModel clickedApp = appsDataList.get(position);
                clickedApp.setSelected(!clickedApp.isSelected());
                listAdapter.notifyDataSetChanged();
            }
        });
    }

    // Load background apps
    private void loadBackgroundApps() {
        binding.swiperefreshlayout1.setRefreshing(true);
        List<String> selectedPackages = appsDataList.stream()
                .filter(AppModel::isSelected)
                .map(AppModel::getPackageName)
                .collect(Collectors.toList());

        appManager.loadBackgroundApps(result -> {
            appsDataList.clear();
            appsDataList.addAll(result);
            binding.runningApps.setText("Running apps: " + appsDataList.size());
            listAdapter.notifyDataSetChanged();
            binding.swiperefreshlayout1.setRefreshing(false);
        });
    }

    // Kill selected apps and manage FAB visibility
    private void killSelectedApps() {
        List<String> packagesToKill = appsDataList.stream()
                .filter(AppModel::isSelected)
                .map(AppModel::getPackageName)
                .collect(Collectors.toList());

        if (packagesToKill.isEmpty()) {
            Toast.makeText(this, "No apps selected to kill", Toast.LENGTH_SHORT).show();
            return;
        }

        // Hide FAB
        binding.fab.setVisibility(View.GONE);

        // Clear selection
        for (AppModel app : appsDataList) {
            app.setSelected(false);
        }
        listAdapter.notifyDataSetChanged();

        // Kill apps and show FAB on completion
        appManager.killPackages(packagesToKill, () -> {
            loadBackgroundApps();
            binding.fab.setVisibility(View.VISIBLE);
            Toast.makeText(this, "Attempted to kill " + packagesToKill.size() + " selected apps", Toast.LENGTH_SHORT).show();
        });
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.main, menu);
        MenuItem item = menu.findItem(R.id.action_select_all);
        View actionView = item.getActionView();
        if (actionView != null) {
            ImageButton btnAction = actionView.findViewById(R.id.btn_action);
            btnAction.setOnClickListener(v -> {
                for (AppModel app : appsDataList) {
                    app.setSelected(true);
                }
                v.postDelayed(() -> listAdapter.notifyDataSetChanged(), 200);
            });
        }
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int itemId = item.getItemId();
        if (itemId == R.id.action_show_system) {
            item.setChecked(!item.isChecked());
            appManager.setShowSystemApps(item.isChecked());
            for (AppModel app : appsDataList) {
                app.setSelected(false);
            }
            loadBackgroundApps();
            return true;
        } else if (itemId == R.id.action_github) {
            openUrl("https://github.com/YasserNull/shappky");
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    // Open URL in browser
    private void openUrl(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception e) {
            Toast.makeText(this, "Failed to open URL", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // Clean up resources
        shizukuManager.removePermissionListener();
        executor.shutdownNow();
        handler.removeCallbacksAndMessages(null);
        ramMonitor.stopMonitoring();
        binding = null;
    }
}