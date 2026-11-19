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
import android.content.SharedPreferences;

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
import org.json.JSONArray;
import org.json.JSONObject;

import com.codehub.app.codesu.model.CacheAppInfo;
import rikka.shizuku.Shizuku;

/** Shizuku cache operations. It never invokes app-data clearing commands. */
public final class CacheCleaner {

    public static final long DEEP_CLEAN_MIN_CACHE_BYTES = 1024L * 1024L;
    private static final String SNAPSHOT_PREFS = "codesu_cache_snapshots";

    /** Persists the sizes already displayed in the list; workers must not rescan before clearing. */
    public static String saveCleanupSnapshot(Context context, List<CacheAppInfo> apps) throws Exception {
        String id = java.util.UUID.randomUUID().toString();
        JSONArray snapshot = new JSONArray();
        Set<String> seenPackages = new HashSet<>();
        for (CacheAppInfo app : apps) {
            if (!isDeepCleanCandidate(app) || !seenPackages.add(app.packageName)) continue;
            snapshot.put(new JSONObject().put("package", app.packageName).put("label", app.label)
                    .put("bytes", app.cacheBytes).put("system", app.systemApp)
                    .put("protected", app.protectedApp));
        }
        context.getSharedPreferences(SNAPSHOT_PREFS, Context.MODE_PRIVATE).edit()
                .putString(id, snapshot.toString()).apply();
        return id;
    }

    public static List<CacheAppInfo> loadCleanupSnapshot(Context context, String id) throws Exception {
        if (id == null || id.trim().isEmpty()) throw new IllegalArgumentException("No se encontró la medición guardada.");
        SharedPreferences prefs = context.getSharedPreferences(SNAPSHOT_PREFS, Context.MODE_PRIVATE);
        String raw = prefs.getString(id, null);
        if (raw == null) throw new IllegalStateException("La medición expiró. Actualiza la lista e inténtalo otra vez.");
        JSONArray rows = new JSONArray(raw);
        List<CacheAppInfo> apps = new ArrayList<>(rows.length());
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.optJSONObject(i);
            if (row == null) continue;
            String packageName = row.optString("package", "");
            if (packageName.isEmpty() || isProtected(packageName)) continue;
            apps.add(new CacheAppInfo(packageName, row.optString("label", packageName), null,
                    row.optLong("bytes", 0L), row.optBoolean("system", false),
                    row.optBoolean("protected", false)));
        }
        return apps;
    }

    public static void removeCleanupSnapshot(Context context, String id) {
        if (id == null || id.trim().isEmpty()) return;
        context.getSharedPreferences(SNAPSHOT_PREFS, Context.MODE_PRIVATE).edit().remove(id).apply();
    }

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
                || packageName.startsWith("com.google.android.providers.")
                || packageName.startsWith("com.google.android.webview")
                || packageName.startsWith("com.android.webview")
                || packageName.startsWith("com.google.android.trichromelibrary")
                || packageName.startsWith("org.chromium.webview_shell");
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

    /** Limpieza estilo Cache Cleaner (zip): global (freeStorage/binder + trim-caches) y/o per-app con verificación. */
    public static ClearResult clearEligibleCaches(Context context, List<CacheAppInfo> apps, ProgressListener listener)
            throws Exception {
        return clearEligibleCaches(context, apps, listener, null);
    }

    public static ClearResult clearEligibleCaches(Context context, List<CacheAppInfo> apps,
                                                    ProgressListener listener,
                                                    CancellationCheck cancellation) throws Exception {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R)
            throw new IllegalStateException("La limpieza selectiva con protección de apps requiere Android 11 o posterior.");
        if (!isShizukuReady()) throw new SecurityException("Shizuku no está activo o CodeSu no tiene autorización.");

        List<CacheAppInfo> targets = new ArrayList<>();
        Set<String> protectedPackages = getProtectedPackages(context);
        Set<String> seenPackages = new HashSet<>();
        for (CacheAppInfo app : apps) {
            if (isDeepCleanCandidate(app) && !protectedPackages.contains(app.packageName)
                    && seenPackages.add(app.packageName))
                targets.add(app);
        }
        if (targets.isEmpty()) return new ClearResult(0, 0, 0L, 0, 0L, "");
        long targetBytes = 0L;
        for (CacheAppInfo target : targets) targetBytes += target.cacheBytes;

        int success = 0;
        int failure = 0;
        int unmeasured = 0;
        List<String> failures = new ArrayList<>();

        long beforeTotal = sumCacheBytesSafe(context, targets);
        // Ruta global primero en lotes grandes (como zip): binder freeStorageAndNotify + fallback trim-caches.
        // No borra datos/sesiones; solo recorte de caché. Se omite si no hay candidatos o lote pequeño.
        if (targets.size() >= 10 && targetBytes > 50L * 1024L * 1024L) {
            if (listener != null) listener.onProgress(0, targets.size(), "Limpieza global", false, false, 0L, 0L);
            runGlobalTrim(context, targets, beforeTotal, listener, cancellation);
            if (cancellation != null && cancellation.isCancelled())
                throw new InterruptedException("Limpieza cancelada por el usuario.");
        }

        // Per-app estilo zip: binder deleteAsUser -> pm clear --cache-only --user 0 -> --user current -> combinado
        long[] freedRef = new long[]{0L};
        int[] unmeasuredRef = new int[]{0};
        int consecutiveFailures = 0;
        for (int index = 0; index < targets.size(); index++) {
            if (cancellation != null && cancellation.isCancelled())
                throw new InterruptedException("Limpieza cancelada por el usuario.");
            CacheAppInfo target = targets.get(index);
            boolean cleared;
            try {
                cleared = clearOneAppZipStyle(context, target, listener, index, targets.size(), freedRef, unmeasuredRef, cancellation);
            } catch (InterruptedException ie) {
                throw ie;
            } catch (Exception e) {
                cleared = false;
            }
            if (cleared) {
                success++;
                consecutiveFailures = 0;
            } else {
                failure++;
                consecutiveFailures++;
                if (failures.size() < 5) failures.add(target.label + " (" + target.packageName + "): sin efecto confirmado");
                if (listener != null) listener.onProgress(index + 1, targets.size(), target.packageName,
                        true, false, freedRef[0], unmeasuredRef[0]);
            }
            if (index + 1 < targets.size()) {
                if (cancellation != null && cancellation.isCancelled())
                    throw new InterruptedException("Limpieza cancelada por el usuario.");
                long pause = cleared ? 700L : 2000L;
                if (consecutiveFailures >= 2) pause = Math.max(pause, 3500L);
                try { Thread.sleep(pause); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); throw ie; }
            }
        }
        long verifiedFreed = freedRef[0];
        unmeasured = unmeasuredRef[0];
        if (verifiedFreed <= 0L) {
            long afterTotal = sumCacheBytesSafe(context, targets);
            verifiedFreed = Math.max(0L, beforeTotal - afterTotal);
        }
        String failureSummary = android.text.TextUtils.join("\n", failures);
        return new ClearResult(success, failure, verifiedFreed, unmeasured, targetBytes, failureSummary);
    }

    /** Recorte global estilo zip: binder freeStorageAndNotify (espera/poll) y fallback trim-caches. */
    public static long runGlobalTrim(Context context, List<CacheAppInfo> targets, long beforeBytes,
                                     ProgressListener listener, CancellationCheck cancellation) {
        if (cancellation != null && cancellation.isCancelled()) return -1L;
        String freeRes = ShizukuPlusUtils.freeStorageAndNotify();
        if (looksEffective(freeRes)) {
            for (int i = 0; i < 12; i++) {
                if (cancellation != null && cancellation.isCancelled()) return -1L;
                try { Thread.sleep(3000L); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return -1L; }
                long now = sumCacheBytesSafe(context, targets);
                long freed = Math.max(0L, beforeBytes - now);
                if (freed >= Math.max(2L * 1024L * 1024L, beforeBytes / 10L) || (beforeBytes > 0 && now * 5L <= beforeBytes)) {
                    return freed;
                }
                if (listener != null) listener.onProgress(0, targets.size(), "Limpieza global", false, false, freed, 0);
            }
        }
        if (cancellation != null && cancellation.isCancelled()) return -1L;
        String trimRes = ShizukuPlusUtils.trimCaches(0L);
        if (looksEffective(trimRes) || trimRes == null || trimRes.trim().isEmpty()) {
            try { Thread.sleep(1500L); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return -1L; }
            long now = sumCacheBytesSafe(context, targets);
            return Math.max(0L, beforeBytes - now);
        }
        try { Thread.sleep(1500L); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return -1L; }
        long now = sumCacheBytesSafe(context, targets);
        long freed = Math.max(0L, beforeBytes - now);
        return freed > 0 ? freed : -1L;
    }

    public static long sumCacheBytesSafe(Context context, List<CacheAppInfo> apps) {
        long total = 0L;
        for (CacheAppInfo app : apps) {
            try { total += queryCacheBytes(context, app.packageName); }
            catch (Exception ignored) { total += Math.max(0L, app.cacheBytes); }
        }
        return total;
    }

    private static boolean clearOneAppZipStyle(Context context, CacheAppInfo target, ProgressListener listener,
                                               int index, int total, long[] verifiedFreedRef, int[] unmeasuredRef,
                                               CancellationCheck cancellation) throws InterruptedException {
        if (cancellation != null && cancellation.isCancelled())
            throw new InterruptedException("Limpieza cancelada por el usuario.");
        long start = target.cacheBytes;
        try { start = Math.max(start, queryCacheBytes(context, target.packageName)); } catch (Exception ignored) { }
        if (listener != null) listener.onProgress(index, total, target.packageName, false, false, verifiedFreedRef[0], unmeasuredRef[0]);

        // 1) binder directo (deleteApplicationCacheFilesAsUser)
        ShizukuPlusUtils.clearAppCacheBinder(target.packageName);
        if (cancellation != null && cancellation.isCancelled()) throw new InterruptedException("Limpieza cancelada por el usuario.");
        if (awaitAndVerifyDrop(context, target.packageName, start, cancellation)) {
            recordCleared(context, target, start, verifiedFreedRef, unmeasuredRef);
            if (listener != null) listener.onProgress(index + 1, total, target.packageName, true, true, verifiedFreedRef[0], unmeasuredRef[0]);
            return true;
        }
        // 2) pm clear --cache-only --user 0
        ShizukuPlusUtils.clearAppCacheShell(target.packageName, "0", target.cacheBytes);
        if (cancellation != null && cancellation.isCancelled()) throw new InterruptedException("Limpieza cancelada por el usuario.");
        if (awaitAndVerifyDrop(context, target.packageName, start, cancellation)) {
            recordCleared(context, target, start, verifiedFreedRef, unmeasuredRef);
            if (listener != null) listener.onProgress(index + 1, total, target.packageName, true, true, verifiedFreedRef[0], unmeasuredRef[0]);
            return true;
        }
        // 3) pm clear --cache-only --user current
        String d = ShizukuPlusUtils.clearAppCacheShell(target.packageName, "current", target.cacheBytes);
        if (cancellation != null && cancellation.isCancelled()) throw new InterruptedException("Limpieza cancelada por el usuario.");
        if (awaitAndVerifyDrop(context, target.packageName, start, cancellation) || looksEffective(d)) {
            recordCleared(context, target, start, verifiedFreedRef, unmeasuredRef);
            if (listener != null) listener.onProgress(index + 1, total, target.packageName, true, true, verifiedFreedRef[0], unmeasuredRef[0]);
            return true;
        }
        // 4) combinado/compat
        d = ShizukuPlusUtils.clearAppCacheDetailed(target.packageName, target.cacheBytes);
        if (cancellation != null && cancellation.isCancelled()) throw new InterruptedException("Limpieza cancelada por el usuario.");
        if (awaitAndVerifyDrop(context, target.packageName, start, cancellation) || looksEffective(d)) {
            recordCleared(context, target, start, verifiedFreedRef, unmeasuredRef);
            if (listener != null) listener.onProgress(index + 1, total, target.packageName, true, true, verifiedFreedRef[0], unmeasuredRef[0]);
            return true;
        }
        return false;
    }

    private static boolean awaitAndVerifyDrop(Context context, String pkg, long start,
                                              CancellationCheck cancellation) throws InterruptedException {
        for (int i = 0; i < 20; i++) {
            if (cancellation != null && cancellation.isCancelled())
                throw new InterruptedException("Limpieza cancelada por el usuario.");
            try { Thread.sleep(1000L); } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw e; }
            try {
                long now = queryCacheBytes(context, pkg);
                if (start <= 0L) return true;
                if (now + 64L * 1024L < start) return true;
                if (now <= 64L * 1024L && start > 64L * 1024L) return true;
            } catch (Exception ignored) { }
        }
        return false;
    }

    private static void recordCleared(Context context, CacheAppInfo target, long start,
                                      long[] verifiedFreedRef, int[] unmeasuredRef) {
        try {
            long now = queryCacheBytes(context, target.packageName);
            long freed = Math.max(0L, start - now);
            if (freed == 0L && start > 0L) freed = Math.min(start, target.cacheBytes);
            verifiedFreedRef[0] += freed;
        } catch (Exception ignored) {
            unmeasuredRef[0]++;
            if (start > 0L) verifiedFreedRef[0] += Math.min(start, target.cacheBytes);
        }
    }

    /** Compat: trim-caches directo. Flujo principal usa runGlobalTrim/clearEligibleCaches. */
    public static String runTrimCachesCommand(CancellationCheck cancellation) {
        String res = ShizukuPlusUtils.trimCaches(0L);
        if (cancellation != null && cancellation.isCancelled()) return "Limpieza cancelada por el usuario.";
        return res == null ? "" : res;
    }

    public static boolean isDeepCleanCandidate(CacheAppInfo app) {
        return app != null && app.cacheBytes > DEEP_CLEAN_MIN_CACHE_BYTES && !app.protectedApp
                && !isProtected(app.packageName)
                // Android 16 system packages can include OEM UI and service components.
                && !(Build.VERSION.SDK_INT >= 36 && app.systemApp);
    }

    public static long queryCacheBytes(Context context, String packageName) throws Exception {
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
        void onProgress(int completed, int total, String packageName, boolean finished, boolean cleared,
                        long verifiedFreedBytes, int unmeasuredApps);
    }

    public interface CancellationCheck {
        boolean isCancelled();
    }

    public interface MeasurementProgressListener {
        void onProgress(int completed, int total, String packageName);
    }

    public static boolean looksEffective(String detail) {
        if (detail == null) return false;
        String d = detail.trim();
        return d.isEmpty() || "ok".equalsIgnoreCase(d) || "success".equalsIgnoreCase(d);
    }

    public static final class ClearResult {
        public final int cleared;
        public final int failed;
        public final long verifiedFreedBytes;
        public final int unmeasuredApps;
        public final long targetBytes;
        public final String failureSummary;
        ClearResult(int cleared, int failed, long verifiedFreedBytes, int unmeasuredApps, long targetBytes,
                    String failureSummary) {
            this.cleared = cleared;
            this.failed = failed;
            this.verifiedFreedBytes = verifiedFreedBytes;
            this.unmeasuredApps = unmeasuredApps;
            this.targetBytes = targetBytes;
            this.failureSummary = failureSummary == null ? "" : failureSummary;
        }
    }

}
