package com.yn.shappky.model;

import android.graphics.drawable.Drawable;

public class AppModel {
    private String appName;
    private String packageName;
    private String appRam;
    private Drawable appIcon;
    private boolean isSystemApp;
    private boolean isPersistentApp;
    private boolean selected;
    private boolean isProtected;

    // Initialize app model
    public AppModel(String appName, String packageName, String appRam, Drawable appIcon, boolean isSystemApp,boolean isPersistentApp, boolean isProtected) {
        this.appName = appName;
        this.packageName = packageName;
        this.appRam = appRam;
        this.appIcon = appIcon;
        this.isSystemApp = isSystemApp;
        this.isPersistentApp = isPersistentApp;
        this.isProtected = isProtected;
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

    // Get and set Persistent app status
    public boolean isPersistentApp() {
        return isPersistentApp;
    }

    public void setPersistentApp(boolean PersistentApp) {
        isPersistentApp = PersistentApp;
    }

    // Get and set selection state
    public boolean isSelected() {
        return selected;
    }

    public void setSelected(boolean selected) {
        this.selected = selected;
    }

    // Get and set protection state
    public boolean isProtected() {
        return isProtected;
    }

    public void setProtected(boolean aProtected) {
        isProtected = aProtected;
    }
}
