package com.codehub.app.codesu.util;

import android.app.AppOpsManager;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.app.usage.StorageStats;
import android.app.usage.StorageStatsManager;
import android.os.Build;
import android.os.Process;
import android.os.UserHandle;
import android.os.storage.StorageManager;
import android.provider.Settings;
import android.telecom.TelecomManager;
import android.provider.Telephony;
import android.content.Intent;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.codehub.app.codesu.model.CacheAppInfo;
import rikka.shizuku.Shizuku;

/** Shizuku cache operations. It never invokes app-data clearing commands. */
public final class CacheCleaner {

    public static final long DEEP_CLEAN_MIN_CACHE_BYTES = 1024L * 1024L;

    private static final Set<String> PROTECTED_PACKAGES = new HashSet<>(Arrays.asList(
            "android",
            "com.codehub.app",
            "moe.shizuku.privileged.api",
            "com.android.systemui",
            "com.android.settings",
            "com.android.shell",
            "com.android.phone",
            "com.android.packageinstaller",
            "com.android.permissioncontroller",
            "com.google.android.gms",
            "com.google.android.gsf",
            "com.google.android.ims",
            "com.google.android.setupwizard",
            "com.google.android.vending"
    ));

    private CacheCleaner() {}

    public static boolean hasUsageAccess(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false;
        try {
            AppOpsManager appOps = (AppOpsManager) context.getSystemService(Context.APP_OPS_SERVICE);
            if (appOps == null) return false;
            int mode = appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,
                    Process.myUid(), context.getPackageName());
            return mode == AppOpsManager.MODE_ALLOWED;
        } catch (RuntimeException e) {
            return false;
        }
    }

    public static List<CacheAppInfo> loadInstalledApps(Context context) throws Exception {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O)
            throw new IllegalStateException("La medición de caché requiere Android 8.0 o posterior.");
        if (!hasUsageAccess(context))
            throw new SecurityException("Activa el acceso al uso para consultar la caché de cada app.");

        PackageManager pm = context.getPackageManager();
        StorageStatsManager statsManager = (StorageStatsManager)
                context.getSystemService(Context.STORAGE_STATS_SERVICE);
        if (statsManager == null) throw new IllegalStateException("Las estadísticas de almacenamiento no están disponibles.");

        List<ApplicationInfo> installed;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            installed = pm.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0));
        } else {
            //noinspection deprecation
            installed = pm.getInstalledApplications(0);
        }

        UserHandle user = Process.myUserHandle();
        Set<String> protectedPackages = getProtectedPackages(context);
        List<CacheAppInfo> result = new ArrayList<>(installed.size());
        for (ApplicationInfo app : installed) {
            String pkg = app.packageName;

            long cacheBytes = 0L;
            try {
                StorageStats stats = statsManager.queryStatsForPackage(
                        app.storageUuid == null ? StorageManager.UUID_DEFAULT : app.storageUuid, pkg, user);
                cacheBytes = stats.getCacheBytes();
            } catch (Exception ignored) {
                // Some OEM/system packages do not expose stats; keep them listed at zero.
            }

            String label;
            try {
                label = pm.getApplicationLabel(app).toString();
            } catch (RuntimeException e) {
                label = pkg;
            }
            result.add(new CacheAppInfo(pkg, label, pm.getApplicationIcon(app), cacheBytes,
                    (app.flags & ApplicationInfo.FLAG_SYSTEM) != 0,
                    protectedPackages.contains(pkg) || isProtected(pkg)));
        }
        Collections.sort(result, (left, right) -> {
            int byCache = Long.compare(right.cacheBytes, left.cacheBytes);
            return byCache != 0 ? byCache : left.label.compareToIgnoreCase(right.label);
        });
        return result;
    }

    /** Refreshes cached-byte estimates immediately before a deep-clean operation. */
    public static List<CacheAppInfo> recalculateCacheSizes(Context context, List<CacheAppInfo> apps)
            throws Exception {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O)
            throw new IllegalStateException("La medición de caché requiere Android 8.0 o posterior.");
        if (!hasUsageAccess(context))
            throw new SecurityException("Activa el acceso al uso para volver a medir la caché.");

        PackageManager pm = context.getPackageManager();
        StorageStatsManager statsManager = (StorageStatsManager)
                context.getSystemService(Context.STORAGE_STATS_SERVICE);
        if (statsManager == null) throw new IllegalStateException("Las estadísticas de almacenamiento no están disponibles.");
        UserHandle user = Process.myUserHandle();
        List<CacheAppInfo> refreshed = new ArrayList<>(apps.size());
        for (CacheAppInfo app : apps) {
            long cacheBytes = 0L;
            try {
                ApplicationInfo applicationInfo = pm.getApplicationInfo(app.packageName, 0);
                StorageStats stats = statsManager.queryStatsForPackage(
                        applicationInfo.storageUuid == null ? StorageManager.UUID_DEFAULT : applicationInfo.storageUuid,
                        app.packageName, user);
                cacheBytes = stats.getCacheBytes();
            } catch (Exception ignored) {
                // Treat unavailable/stale stats as zero so they cannot enter the deep-clean target set.
            }
            refreshed.add(new CacheAppInfo(app.packageName, app.label, app.icon, cacheBytes,
                    app.systemApp, app.protectedApp));
        }
        Collections.sort(refreshed, (left, right) -> {
            int byCache = Long.compare(right.cacheBytes, left.cacheBytes);
            return byCache != 0 ? byCache : left.label.compareToIgnoreCase(right.label);
        });
        return refreshed;
    }

    public static boolean isProtected(String packageName) {
        if (packageName == null || PROTECTED_PACKAGES.contains(packageName)) return true;
        return packageName.startsWith("com.android.providers.")
                || packageName.startsWith("com.google.android.providers.");
    }

    private static Set<String> getProtectedPackages(Context context) {
        Set<String> result = new HashSet<>(PROTECTED_PACKAGES);
        PackageManager pm = context.getPackageManager();
        try {
            Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                android.content.pm.ResolveInfo resolved = pm.resolveActivity(home,
                        PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY));
                if (resolved != null && resolved.activityInfo != null)
                    result.add(resolved.activityInfo.packageName);
            } else {
                //noinspection deprecation
                android.content.pm.ResolveInfo resolved = pm.resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY);
                if (resolved != null && resolved.activityInfo != null)
                    result.add(resolved.activityInfo.packageName);
            }
        } catch (RuntimeException ignored) {}
        try {
            TelecomManager telecom = (TelecomManager) context.getSystemService(Context.TELECOM_SERVICE);
            if (telecom != null && telecom.getDefaultDialerPackage() != null)
                result.add(telecom.getDefaultDialerPackage());
        } catch (RuntimeException ignored) {}
        try {
            String sms = Telephony.Sms.getDefaultSmsPackage(context);
            if (sms != null) result.add(sms);
        } catch (RuntimeException ignored) {}
        try {
            String ime = Settings.Secure.getString(context.getContentResolver(), Settings.Secure.DEFAULT_INPUT_METHOD);
            if (ime != null && ime.contains("/")) result.add(ime.substring(0, ime.indexOf('/')));
        } catch (RuntimeException ignored) {}
        return result;
    }

    public static boolean isShizukuReady() {
        try {
            return Shizuku.pingBinder()
                    && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Clears cache only for eligible packages; app data and credentials are untouched. */
    public static ClearResult clearEligibleCaches(Context context, List<CacheAppInfo> apps, ProgressListener listener)
            throws Exception {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R)
            throw new IllegalStateException("La limpieza selectiva con protección de apps requiere Android 11 o posterior.");
        if (!isShizukuReady()) throw new SecurityException("Shizuku no está activo o CodeSu no tiene autorización.");

        List<CacheAppInfo> targets = new ArrayList<>();
        Set<String> protectedPackages = getProtectedPackages(context);
        for (CacheAppInfo app : apps) {
            if (app.cacheBytes > DEEP_CLEAN_MIN_CACHE_BYTES && !app.protectedApp
                    && !protectedPackages.contains(app.packageName)
                    && !isProtected(app.packageName))
                targets.add(app);
        }
        if (targets.isEmpty()) return new ClearResult(0, 0, 0L, 0);

        String script = "for p in \"$@\"; do "
                + "out=$(pm clear --cache-only --user current \"$p\" 2>&1); "
                + "case \"$out\" in *Success*) echo \"CODESU_CACHE_OK:$p\";; "
                + "*) echo \"CODESU_CACHE_FAIL:$p\";; esac; "
                + "done; exit 0";
        String[] command = new String[4 + targets.size()];
        command[0] = "sh";
        command[1] = "-c";
        command[2] = script;
        command[3] = "codesu-cache";
        for (int i = 0; i < targets.size(); i++) command[i + 4] = targets.get(i).packageName;

        Method newProcess = Shizuku.class.getDeclaredMethod(
                "newProcess", String[].class, String[].class, String.class);
        newProcess.setAccessible(true);
        ProcessResult processResult = readProcess(context, newProcess.invoke(null, command, null, null), targets, listener);
        return processResult.result;
    }

    private static ProcessResult readProcess(Context context, Object processObject, List<CacheAppInfo> targets,
                                             ProgressListener listener) throws Exception {
        if (!(processObject instanceof java.lang.Process))
            throw new IllegalStateException("Shizuku no pudo iniciar el proceso de limpieza de caché.");
        java.lang.Process process = (java.lang.Process) processObject;
        int success = 0;
        int failure = 0;
        long verifiedFreed = 0L;
        int unmeasured = 0;
        int completed = 0;
        Set<String> reported = new HashSet<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                boolean ok = line.startsWith("CODESU_CACHE_OK:");
                boolean failed = line.startsWith("CODESU_CACHE_FAIL:");
                if (!ok && !failed) continue;
                String pkg = line.substring(line.indexOf(':', "CODESU_CACHE_".length()) + 1);
                if (!reported.add(pkg)) continue;
                completed++;
                if (ok) {
                    success++;
                    CacheAppInfo target = findTarget(targets, pkg);
                    if (target != null) {
                        try {
                            long afterBytes = queryCacheBytes(context, pkg);
                            verifiedFreed += Math.max(0L, target.cacheBytes - afterBytes);
                        } catch (Exception ignored) {
                            unmeasured++;
                        }
                    }
                } else {
                    failure++;
                }
                if (listener != null)
                    listener.onProgress(completed, targets.size(), pkg, ok, verifiedFreed, unmeasured);
            }
        } finally {
            process.getErrorStream().close();
        }
        process.waitFor();
        if (completed < targets.size()) failure += targets.size() - completed;
        return new ProcessResult(new ClearResult(success, failure, verifiedFreed, unmeasured));
    }

    private static CacheAppInfo findTarget(List<CacheAppInfo> targets, String packageName) {
        for (CacheAppInfo app : targets) if (app.packageName.equals(packageName)) return app;
        return null;
    }

    private static long queryCacheBytes(Context context, String packageName) throws Exception {
        PackageManager pm = context.getPackageManager();
        ApplicationInfo app = pm.getApplicationInfo(packageName, 0);
        StorageStatsManager statsManager = (StorageStatsManager)
                context.getSystemService(Context.STORAGE_STATS_SERVICE);
        if (statsManager == null) throw new IllegalStateException("Las estadísticas de almacenamiento no están disponibles.");
        StorageStats stats = statsManager.queryStatsForPackage(
                app.storageUuid == null ? StorageManager.UUID_DEFAULT : app.storageUuid,
                packageName, Process.myUserHandle());
        return Math.max(0L, stats.getCacheBytes());
    }

    public interface ProgressListener {
        void onProgress(int completed, int total, String packageName, boolean cleared,
                        long verifiedFreedBytes, int unmeasuredApps);
    }

    public static final class ClearResult {
        public final int cleared;
        public final int failed;
        public final long verifiedFreedBytes;
        public final int unmeasuredApps;
        ClearResult(int cleared, int failed, long verifiedFreedBytes, int unmeasuredApps) {
            this.cleared = cleared;
            this.failed = failed;
            this.verifiedFreedBytes = verifiedFreedBytes;
            this.unmeasuredApps = unmeasuredApps;
        }
    }

    private static final class ProcessResult {
        final ClearResult result;
        ProcessResult(ClearResult result) { this.result = result; }
    }
}
