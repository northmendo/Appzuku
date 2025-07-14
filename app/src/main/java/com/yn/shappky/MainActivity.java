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
    private MenuItem selectAllItem;
    private MenuItem unselectAllItem;

    // Handle Shizuku permission results
    private final Shizuku.OnRequestPermissionResultListener permissionListener = (requestCode, grantResult) -> {
        if (grantResult == PackageManager.PERMISSION_GRANTED) {
            loadBackgroundApps();
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
                updateSelectMenuVisibility(); 
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
            updateSelectMenuVisibility(); 
            binding.swiperefreshlayout1.setRefreshing(false);
        });
    }

    // Kill selected apps and manage FAB visibility
    private void killSelectedApps() {
        List<String> packagesToKill = appsDataList.stream()
                .filter(AppModel::isSelected)
                .map(AppModel::getPackageName)
                .collect(Collectors.toList());

        // Hide FAB
        binding.fab.hide();

        // Clear selection
        for (AppModel app : appsDataList) {
            app.setSelected(false);
        }
        listAdapter.notifyDataSetChanged();

        // Kill apps and show FAB on completion
        appManager.killPackages(packagesToKill, () -> {
            loadBackgroundApps();
            binding.fab.show();
        });
    }

    // Toggle between "Select All" and "Unselect All" based on whether any item is selected
    private void updateSelectMenuVisibility() {
       boolean hasSelection = appsDataList.stream().anyMatch(AppModel::isSelected);       
       if (hasSelection) {
       	binding.fab.show();
       } else {
       	binding.fab.hide();
       } 
        if (selectAllItem != null && unselectAllItem != null) {
            selectAllItem.setVisible(!hasSelection);
            unselectAllItem.setVisible(hasSelection);
         }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.main, menu);

        selectAllItem = menu.findItem(R.id.action_select_all);
        unselectAllItem = menu.findItem(R.id.action_unselect_all);

        View selectView = selectAllItem.getActionView();
        View unselectView = unselectAllItem.getActionView();
        unselectAllItem.setVisible(false);
         
        if (selectView != null) {
            ImageButton selectBtn = selectView.findViewById(R.id.select_all_action);
            selectBtn.setOnClickListener(v -> {
                for (AppModel app : appsDataList) {
                    app.setSelected(true);
                }
                listAdapter.notifyDataSetChanged();
                updateSelectMenuVisibility(); 
            });
        }

        if (unselectView != null) {
                ImageButton unselectBtn = unselectView.findViewById(R.id.unselect_all_action);
            unselectBtn.setOnClickListener(v -> {
                    for (AppModel app : appsDataList) {
                         app.setSelected(false);
                } 
                listAdapter.notifyDataSetChanged();
                updateSelectMenuVisibility(); 
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
        } else if (itemId == R.id.action_donate) {
            openUrl("https://www.paypal.com/ncp/payment/7X44EWSM9KAVW");
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
        } catch (Exception e) {}
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