package com.codehub.app;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.provider.Settings;
import android.provider.AlarmClock;
import android.provider.Telephony;
import android.telecom.TelecomManager;

import java.util.HashSet;
import java.util.Set;

/** Shared app classification for CodeSu's live list and automatic runs. */
public final class CodeSuAppPolicy {
    public static final int SOURCE_USER = 0;
    public static final int SOURCE_LAUNCHABLE = 1;
    public static final int SOURCE_SYSTEM = 2;
    public static final int SOURCE_ALL = 3;

    private CodeSuAppPolicy() {}

    public static Set<String> criticalPackages(Context context) {
        HashSet<String> packages = new HashSet<>();
        packages.add("android");
        packages.add("com.codehub.app");
        packages.add("moe.shizuku.privileged.api");
        packages.add("com.android.shell");
        packages.add("com.android.systemui");
        packages.add("com.android.settings");
        packages.add("com.android.phone");
        packages.add("com.android.server.telecom");
        packages.add("com.android.providers.settings");
        packages.add("com.android.permissioncontroller");
        packages.add("com.android.packageinstaller");
        packages.add("com.google.android.gms");
        packages.add("com.google.android.gsf");
        PackageManager pm = context.getPackageManager();
        try {
            String home = pm.resolveActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY)
                .activityInfo.packageName;
            if (home != null) packages.add(home);
        } catch (Exception ignored) {}
        try {
            String ime = Settings.Secure.getString(context.getContentResolver(), Settings.Secure.DEFAULT_INPUT_METHOD);
            if (ime != null) packages.add(ime.substring(0, ime.indexOf('/') >= 0 ? ime.indexOf('/') : ime.length()));
        } catch (Exception ignored) {}
        try {
            String dialer = ((TelecomManager) context.getSystemService(Context.TELECOM_SERVICE)).getDefaultDialerPackage();
            if (dialer != null) packages.add(dialer);
        } catch (Exception ignored) {}
        try {
            String sms = Telephony.Sms.getDefaultSmsPackage(context);
            if (sms != null) packages.add(sms);
        } catch (Exception ignored) {}
        try {
            android.content.pm.ResolveInfo alarm = pm.resolveActivity(new Intent(AlarmClock.ACTION_SET_ALARM), PackageManager.MATCH_DEFAULT_ONLY);
            if (alarm != null && alarm.activityInfo != null) packages.add(alarm.activityInfo.packageName);
        } catch (Exception ignored) {}
        return packages;
    }

    public static boolean matchesSource(Context context, String packageName, int sourceMode) {
        try {
            ApplicationInfo info = context.getPackageManager().getApplicationInfo(packageName, 0);
            boolean system = (info.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
            if (sourceMode == SOURCE_ALL) return true;
            if (sourceMode == SOURCE_SYSTEM) return system;
            if (sourceMode == SOURCE_LAUNCHABLE) return context.getPackageManager().getLaunchIntentForPackage(packageName) != null;
            return !system;
        } catch (Exception ignored) {
            return false;
        }
    }
}
