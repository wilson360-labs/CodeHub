package com.codehub.app;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.provider.Settings;
import android.provider.AlarmClock;
import android.provider.Telephony;
import android.telecom.TelecomManager;
import android.webkit.WebView;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/** Shared app classification for CodeSu's live list and automatic runs. */
public final class CodeSuAppPolicy {
    public static final int SOURCE_USER = 0;
    public static final int SOURCE_LAUNCHABLE = 1;
    public static final int SOURCE_SYSTEM = 2;
    public static final int SOURCE_ALL = 3;

    private CodeSuAppPolicy() {}

    /** Apps cuyo stop rompe procesos esenciales; alineada con CRITICAL_PACKAGES de KillMyApps upstream. */
    private static final String[] STATIC_CRITICAL_PACKAGES = {
            // Núcleo CodeSu / Shizuku
            "android",
            "com.codehub.app",
            "moe.shizuku.privileged.api",
            "com.android.shell",
            // Núcleo AOSP (upstream)
            "com.android.systemui",
            "com.android.settings",
            "com.android.phone",
            "com.android.server.telecom",
            "com.android.providers.settings",
            "com.android.providers.downloads",
            "com.android.providers.media",
            "com.android.providers.media.module",
            "com.android.bluetooth",
            "com.android.nfc",
            "com.android.permissioncontroller",
            "com.android.packageinstaller",
            // Google / microG (upstream)
            "com.google.android.gms",
            "com.google.android.gsf",
            "org.microg.gms.droidguard",
            // MIUI / HyperOS (upstream)
            "com.miui.securitycenter",
            "com.miui.powerkeeper",
            "com.miui.guardprovider",
            "com.xiaomi.finddevice",
            "com.miui.core",
            "com.miui.home",
            // Samsung One UI (upstream)
            "com.samsung.android.lool",
            "com.samsung.android.securitylogagent",
            "com.samsung.android.biometrics.app.setting",
            "com.samsung.android.incallui",
            "com.samsung.android.sm_cn",
            // ColorOS / OxygenOS (upstream)
            "com.coloros.safecenter",
            "com.oplus.battery",
            "com.oplus.safe",
            // EMUI (upstream)
            "com.huawei.systemmanager",
    };

    /**
     * Prefijos de providers de WebView (Chrome/Trichrome/variants canarias). Al forzar su stop,
     * AMS mata por pkgDeps los procesos que ya cargaron Chromium (p. ej. CodeHub) sin dejar rastro
     * en el buffer de crashes.
     */
    private static final String[] CRITICAL_PACKAGE_PREFIXES = {
            "com.google.android.webview",
            "com.android.webview",
            "com.google.android.trichromelibrary",
            "com.android.chrome",
            "org.chromium.webview_shell",
    };

    public static Set<String> criticalPackages(Context context) {
        HashSet<String> packages = new HashSet<>(Arrays.asList(STATIC_CRITICAL_PACKAGES));
        if (Build.VERSION.SDK_INT >= 26) {
            try {
                WebView current = WebView.getCurrentWebViewPackage();
                if (current != null && current.getPackageInfo() != null
                        && current.getPackageInfo().packageName != null) {
                    packages.add(current.getPackageInfo().packageName);
                }
            } catch (Exception ignored) {}
        }
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

    /** Providers de WebView detectados por prefijo (Trichrome/canary varían por versión). */
    public static boolean matchesProtectedPrefix(String packageName) {
        if (packageName == null) return false;
        for (String prefix : CRITICAL_PACKAGE_PREFIXES) {
            if (packageName.startsWith(prefix)) return true;
        }
        return false;
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
