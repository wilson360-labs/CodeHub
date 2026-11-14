package com.codehub.app.codesu.worker;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;
import androidx.work.Data;
import androidx.work.ForegroundInfo;
import androidx.work.Worker;
import androidx.work.WorkerParameters;
import androidx.work.WorkManager;

import java.util.List;

import com.codehub.app.codesu.R;
import com.codehub.app.codesu.model.CacheAppInfo;
import com.codehub.app.codesu.util.CacheCleaner;

/** User-started cache operation that remains alive when CodeSu leaves the foreground. */
public final class CacheCleanerWorker extends Worker {
    public static final String UNIQUE_NAME = "codesu_global_cache_clean";
    public static final String TAG = "codesu_cache_clean";
    public static final String KEY_PERCENT = "percent";
    public static final String KEY_PHASE = "phase";
    public static final String KEY_CURRENT = "current";
    public static final String KEY_CLEARED = "cleared";
    public static final String KEY_FAILED = "failed";
    public static final String KEY_FREED = "freed";
    public static final String KEY_TARGET = "target";
    public static final String KEY_ERROR = "error";
    private static final String CHANNEL_ID = "codesu_cache_cleanup";
    private static final int NOTIFICATION_ID = 7421;

    public CacheCleanerWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        Context context = getApplicationContext();
        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R)
                return failure("La limpieza protegida requiere Android 11 o posterior.");
            if (!CacheCleaner.hasUsageAccess(context))
                return failure("Activa el acceso al uso para medir caché.");
            if (!CacheCleaner.isShizukuReady())
                return failure("Shizuku está apagado o CodeSu no tiene permiso.");
            setForegroundAsync(foregroundInfo(0, "Preparando limpieza segura…")).get();

            List<CacheAppInfo> apps = CacheCleaner.loadInstalledApps(context, (completed, total, packageName) -> {
                int percent = total == 0 ? 35 : (int) (completed * 35L / total);
                publish(percent, "Midiendo caché", packageName, 0, 0, 0L, 0L);
            });
            int eligible = 0;
            for (CacheAppInfo app : apps) {
                if (app.cacheBytes > CacheCleaner.DEEP_CLEAN_MIN_CACHE_BYTES
                        && !app.protectedApp && !CacheCleaner.isProtected(app.packageName)) eligible++;
            }
            if (eligible == 0) return failure("Ninguna app segura supera 1 MB de caché.");

            publish(40, "Conectando con Shizuku", "Preparando apps…", 0, 0, 0L, 0L);
            CacheCleaner.ClearResult result = CacheCleaner.clearEligibleCaches(context, apps,
                    (completed, total, packageName, cleared, freed, unmeasured) -> {
                        int percent = total == 0 ? 95 : 40 + (int) (completed * 55L / total);
                        publish(percent, "Limpiando caché", packageName, completed, total, freed, 0L);
                    });
            int attempted = result.cleared + result.failed;
            int successRate = attempted == 0 ? 0 : Math.round(result.cleared * 100f / attempted);
            int spaceRate = result.targetBytes <= 0 ? 0
                    : Math.min(100, Math.round(result.verifiedFreedBytes * 100f / result.targetBytes));
            context.getSharedPreferences("codesu_cache_cleaner", Context.MODE_PRIVATE).edit()
                    .putInt("last_success_rate", successRate)
                    .putInt("last_space_rate", spaceRate)
                    .putLong("last_freed_bytes", result.verifiedFreedBytes)
                    .apply();
            Data output = new Data.Builder()
                    .putInt(KEY_CLEARED, result.cleared)
                    .putInt(KEY_FAILED, result.failed)
                    .putLong(KEY_FREED, result.verifiedFreedBytes)
                    .putLong(KEY_TARGET, result.targetBytes)
                    .putInt("unmeasured", result.unmeasuredApps)
                    .putInt("success_rate", successRate)
                    .putInt("space_rate", spaceRate)
                    .putString(KEY_ERROR, result.failureSummary.isEmpty()
                            ? (result.cleared == 0 ? "Shizuku no confirmó la limpieza de ninguna app." : "")
                            : result.failureSummary)
                    .build();
            publish(100, result.failed == 0 ? "Limpieza completada" : "Limpieza parcial",
                    result.cleared + " apps confirmadas", result.cleared, result.failed,
                    result.verifiedFreedBytes, result.targetBytes);
            return result.cleared == 0 ? Result.failure(output) : Result.success(output);
        } catch (SecurityException e) {
            return failure("Shizuku perdió el permiso durante la limpieza. Vuelve a autorizar CodeSu.");
        } catch (Exception e) {
            String detail = e.getMessage();
            return failure(detail == null || detail.trim().isEmpty()
                    ? "Error inesperado durante la limpieza de caché." : detail);
        }
    }

    private Result failure(String message) {
        Data data = new Data.Builder().putString(KEY_ERROR, message).putInt(KEY_CLEARED, 0)
                .putInt(KEY_FAILED, 0).putLong(KEY_FREED, 0L).build();
        return Result.failure(data);
    }

    private void publish(int percent, String phase, String current, int completed, int total,
                         long freed, long target) {
        int bounded = Math.max(0, Math.min(100, percent));
        Data data = new Data.Builder().putInt(KEY_PERCENT, bounded).putString(KEY_PHASE, phase)
                .putString(KEY_CURRENT, current).putInt(KEY_CLEARED, completed)
                .putInt(KEY_FAILED, total).putLong(KEY_FREED, freed).putLong(KEY_TARGET, target).build();
        setProgressAsync(data);
        setForegroundAsync(foregroundInfo(bounded, phase + " · " + current));
    }

    private ForegroundInfo foregroundInfo(int progress, String text) {
        Context context = getApplicationContext();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (manager != null && manager.getNotificationChannel(CHANNEL_ID) == null) {
                manager.createNotificationChannel(new NotificationChannel(CHANNEL_ID,
                        "Limpieza de caché CodeSu", NotificationManager.IMPORTANCE_LOW));
            }
        }
        Notification notification = new NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.codesu_ic_launcher_foreground)
                .setContentTitle("CodeSu · limpieza de caché")
                .setContentText(text)
                .setProgress(100, Math.max(0, Math.min(100, progress)), false)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(NotificationCompat.CATEGORY_PROGRESS)
                .build();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            return new ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        return new ForegroundInfo(NOTIFICATION_ID, notification);
    }
}
