package com.codehub.app.codesu.model;

import android.graphics.drawable.Drawable;

public final class CacheAppInfo {
    public final String packageName;
    public final String label;
    public final Drawable icon;
    public final long cacheBytes;
    public final boolean systemApp;
    public final boolean protectedApp;

    public CacheAppInfo(String packageName, String label, Drawable icon, long cacheBytes,
                        boolean systemApp, boolean protectedApp) {
        this.packageName = packageName;
        this.label = label;
        this.icon = icon;
        this.cacheBytes = Math.max(0L, cacheBytes);
        this.systemApp = systemApp;
        this.protectedApp = protectedApp;
    }
}
