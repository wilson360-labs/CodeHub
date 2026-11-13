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
            throw new IllegalStateException("Cache estimates require Android 8.0 or newer.");
        if (!hasUsageAccess(context))
            throw new SecurityException("Grant Usage access to read per-app cache estimates.");

        PackageManager pm = context.getPackageManager();
        StorageStatsManager statsManager = (StorageStatsManager)
                context.getSystemService(Context.STORAGE_STATS_SERVICE);
        if (statsManager == null) throw new IllegalStateException("Storage stats service unavailable.");

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
            throw new IllegalStateException("Selective cache cleaning with protected-app exclusions requires Android 11+.");
        if (!isShizukuReady()) throw new SecurityException("Shizuku is not running or CodeSu is not authorized.");

        List<CacheAppInfo> targets = new ArrayList<>();
        Set<String> protectedPackages = getProtectedPackages(context);
        for (CacheAppInfo app : apps) {
            if (app.cacheBytes > 0 && !app.protectedApp && !protectedPackages.contains(app.packageName)
                    && !isProtected(app.packageName))
                targets.add(app);
        }
        if (targets.isEmpty()) return new ClearResult(0, 0, 0L);

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
        ProcessResult processResult = readProcess(newProcess.invoke(null, command, null, null), targets, listener);
        return processResult.result;
    }

    private static ProcessResult readProcess(Object processObject, List<CacheAppInfo> targets,
                                             ProgressListener listener) throws Exception {
        if (!(processObject instanceof java.lang.Process))
            throw new IllegalStateException("Shizuku did not create the cache-cleaner process.");
        java.lang.Process process = (java.lang.Process) processObject;
        int success = 0;
        int failure = 0;
        long estimatedFreed = 0L;
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
                    for (CacheAppInfo app : targets) {
                        if (app.packageName.equals(pkg)) { estimatedFreed += app.cacheBytes; break; }
                    }
                } else {
                    failure++;
                }
                if (listener != null) listener.onProgress(completed, targets.size(), pkg, ok);
            }
        } finally {
            process.getErrorStream().close();
        }
        process.waitFor();
        if (completed < targets.size()) failure += targets.size() - completed;
        return new ProcessResult(new ClearResult(success, failure, estimatedFreed));
    }

    public interface ProgressListener {
        void onProgress(int completed, int total, String packageName, boolean cleared);
    }

    public static final class ClearResult {
        public final int cleared;
        public final int failed;
        public final long estimatedFreedBytes;
        ClearResult(int cleared, int failed, long estimatedFreedBytes) {
            this.cleared = cleared;
            this.failed = failed;
            this.estimatedFreedBytes = estimatedFreedBytes;
        }
    }

    private static final class ProcessResult {
        final ClearResult result;
        ProcessResult(ClearResult result) { this.result = result; }
    }
}
