package com.codehub.app;

import android.content.Context;

import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;

import java.util.concurrent.TimeUnit;

/**
 * Programa la sincronización de CodeHub (token FCM + ubicación) con
 * WorkManager. Sustituye al Foreground Service {@code dataSync}, que en
 * Android 15/16 agota su límite de tiempo y crashea la app.
 *
 * - {@link #schedule(Context)} deja un trabajo periódico (mínimo 15 min).
 * - {@link #syncNow(Context)} dispara una pasada inmediata.
 */
public final class CodeHubSyncScheduler {

    private CodeHubSyncScheduler() {}

    public static void schedule(Context ctx) {
        try {
            Constraints constraints = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build();
            PeriodicWorkRequest request = new PeriodicWorkRequest.Builder(
                    CodeHubSyncWorker.class, 15, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .build();
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(
                CodeHubSyncWorker.NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request);
        } catch (Throwable ignored) {}
    }

    public static void syncNow(Context ctx) {
        try {
            OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(CodeHubSyncWorker.class)
                .build();
            WorkManager.getInstance(ctx).enqueueUniqueWork(
                CodeHubSyncWorker.NAME + "_now",
                ExistingWorkPolicy.REPLACE,
                request);
        } catch (Throwable ignored) {}
    }
}
