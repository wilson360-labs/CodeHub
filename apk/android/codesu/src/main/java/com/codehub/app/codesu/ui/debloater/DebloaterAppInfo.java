package com.codehub.app.codesu.ui.debloater;

import android.content.pm.ApplicationInfo;

/** One row of the debloater list. Holds device state plus local suggestion metadata. */
public final class DebloaterAppInfo {
    public final ApplicationInfo appInfo;
    public final String label;
    public final String packageName;
    public final String category;
    public final String description;
    public final boolean removed;
    public boolean selected;

    public DebloaterAppInfo(ApplicationInfo appInfo, String label, String category,
                            String description, boolean removed) {
        this.appInfo = appInfo;
        this.label = label;
        this.packageName = appInfo.packageName;
        this.category = category;
        this.description = description;
        this.removed = removed;
    }

    public boolean isEnabledApp() {
        return appInfo != null && appInfo.enabled;
    }

    public boolean isDisabledApp() {
        return appInfo != null && !appInfo.enabled;
    }
}
