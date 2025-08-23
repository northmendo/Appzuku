package com.yn.shappky.model;

import android.graphics.drawable.Drawable;

public class AppModel {
    private String appName;
    private String packageName;
    private String appRam;
    private Drawable appIcon;
    private boolean isSystemApp;
    private boolean selected;

    // Initialize app model
    public AppModel(String appName, String packageName, String appRam, Drawable appIcon, boolean isSystemApp) {
        this.appName = appName;
        this.packageName = packageName;
        this.appRam = appRam;
        this.appIcon = appIcon;
        this.isSystemApp = isSystemApp;
        this.selected = false;
    }

    // Get and set app name
    public String getAppName() {
        return appName;
    }

    public void setAppName(String appName) {
        this.appName = appName;
    }

    // Get and set package name
    public String getPackageName() {
        return packageName;
    }

    public void setPackageName(String packageName) {
        this.packageName = packageName;
    }

    // Get and set app ram usage
    public String getAppRam() {
        return appRam;
    }

    public void setAppRam(String appRam) {
        this.appRam = appRam;
    }

    // Get and set app icon
    public Drawable getAppIcon() {
        return appIcon;
    }

    public void setAppIcon(Drawable appIcon) {
        this.appIcon = appIcon;
    }

    // Get and set system app status
    public boolean isSystemApp() {
        return isSystemApp;
    }

    public void setSystemApp(boolean systemApp) {
        isSystemApp = systemApp;
    }

    // Get and set selection state
    public boolean isSelected() {
        return selected;
    }

    public void setSelected(boolean selected) {
        this.selected = selected;
    }
}
