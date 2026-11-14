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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

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
        return loadInstalledApps(context, null);
    }

    public static List<CacheAppInfo> loadInstalledApps(Context context, MeasurementProgressListener listener) throws Exception {
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
        ExecutorService workers = Executors.newFixedThreadPool(Math.min(4, Math.max(1, installed.size())));
        CompletionService<CacheAppInfo> completion = new ExecutorCompletionService<>(workers);
        try {
            for (ApplicationInfo app : installed) {
                completion.submit(() -> {
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
                    try { label = pm.getApplicationLabel(app).toString(); }
                    catch (RuntimeException e) { label = pkg; }
                    android.graphics.drawable.Drawable icon;
                    try { icon = pm.getApplicationIcon(app); }
                    catch (RuntimeException e) { icon = null; }
                    return new CacheAppInfo(pkg, label, icon, cacheBytes,
                            (app.flags & ApplicationInfo.FLAG_SYSTEM) != 0,
                            protectedPackages.contains(pkg) || isProtected(pkg));
                });
            }
            for (int completed = 1; completed <= installed.size(); completed++) {
                try { result.add(completion.take().get()); }
                catch (ExecutionException e) { /* Skip a package whose metadata cannot be read. */ }
                if (listener != null) listener.onProgress(completed, installed.size(), "");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("La lectura de caché fue interrumpida.", e);
        } finally {
            workers.shutdownNow();
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
        return recalculateCacheSizes(context, apps, null);
    }

    /** Recalculate with incremental progress so large app lists never look frozen. */
    public static List<CacheAppInfo> recalculateCacheSizes(Context context, List<CacheAppInfo> apps,
                                                            MeasurementProgressListener listener)
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
        List<CacheAppInfo> candidates = new ArrayList<>();
        for (CacheAppInfo app : apps) {
            if (isDeepCleanCandidate(app)) candidates.add(app);
            else refreshed.add(app);
        }
        if (candidates.isEmpty()) return refreshed;
        int completed = 0;
        for (CacheAppInfo app : candidates) {
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
            completed++;
            if (listener != null) listener.onProgress(completed, candidates.size(), app.packageName);
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
            if (isDeepCleanCandidate(app) && !protectedPackages.contains(app.packageName))
                targets.add(app);
        }
        if (targets.isEmpty()) return new ClearResult(0, 0, 0L, 0, 0L);
        long targetBytes = 0L;
        for (CacheAppInfo target : targets) targetBytes += target.cacheBytes;

        int success = 0;
        int failure = 0;
        long verifiedFreed = 0L;
        int unmeasured = 0;
        AtomicInteger clearedRequests = new AtomicInteger();
        AtomicInteger liveUnmeasured = new AtomicInteger();
        AtomicLong liveVerifiedFreed = new AtomicLong();
        Object progressLock = new Object();
        ExecutorService workers = Executors.newFixedThreadPool(Math.min(3, targets.size()));
        CompletionService<CacheClearTaskResult> completion = new ExecutorCompletionService<>(workers);
        try {
            for (CacheAppInfo target : targets) {
                completion.submit(() -> {
                    boolean cleared = ShizukuPlusUtils.clearAppCache(target.packageName) == 1;
                    synchronized (progressLock) {
                        int requestNumber = clearedRequests.incrementAndGet();
                        // Move the visible progress as soon as Shizuku confirms each
                        // request; StorageStats verification can be slower on OEMs.
                        if (listener != null)
                            listener.onProgress(requestNumber, targets.size(), target.packageName, cleared,
                                    liveVerifiedFreed.get(), liveUnmeasured.get());
                    }
                    long freed = 0L;
                    boolean measured = true;
                    if (cleared) {
                        try {
                            freed = Math.max(0L, target.cacheBytes - queryCacheBytes(context, target.packageName));
                            liveVerifiedFreed.addAndGet(freed);
                        } catch (Exception ignored) {
                            measured = false;
                            liveUnmeasured.incrementAndGet();
                        }
                    }
                    return new CacheClearTaskResult(target.packageName, cleared, freed, measured);
                });
            }
            for (int i = 0; i < targets.size(); i++) {
                CacheClearTaskResult result;
                try {
                    Future<CacheClearTaskResult> future = completion.take();
                    result = future.get();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("La limpieza fue interrumpida.", e);
                } catch (ExecutionException e) {
                    synchronized (progressLock) {
                        int requestNumber = clearedRequests.incrementAndGet();
                        if (listener != null)
                            listener.onProgress(requestNumber, targets.size(), "app", false,
                                    liveVerifiedFreed.get(), liveUnmeasured.get());
                    }
                    result = new CacheClearTaskResult("app", false, 0L, false);
                }
                if (result.cleared) success++; else failure++;
                verifiedFreed += result.verifiedFreed;
                if (result.cleared && !result.measured) unmeasured++;
            }
        } finally {
            workers.shutdownNow();
        }
        return new ClearResult(success, failure, verifiedFreed, unmeasured, targetBytes);
    }

    private static boolean isDeepCleanCandidate(CacheAppInfo app) {
        return app != null && app.cacheBytes > DEEP_CLEAN_MIN_CACHE_BYTES && !app.protectedApp
                && !isProtected(app.packageName);
    }

    private static final class CacheClearTaskResult {
        final String packageName;
        final boolean cleared;
        final long verifiedFreed;
        final boolean measured;
        CacheClearTaskResult(String packageName, boolean cleared, long verifiedFreed, boolean measured) {
            this.packageName = packageName;
            this.cleared = cleared;
            this.verifiedFreed = verifiedFreed;
            this.measured = measured;
        }
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

    public interface MeasurementProgressListener {
        void onProgress(int completed, int total, String packageName);
    }

    public static final class ClearResult {
        public final int cleared;
        public final int failed;
        public final long verifiedFreedBytes;
        public final int unmeasuredApps;
        public final long targetBytes;
        ClearResult(int cleared, int failed, long verifiedFreedBytes, int unmeasuredApps, long targetBytes) {
            this.cleared = cleared;
            this.failed = failed;
            this.verifiedFreedBytes = verifiedFreedBytes;
            this.unmeasuredApps = unmeasuredApps;
            this.targetBytes = targetBytes;
        }
    }

}
