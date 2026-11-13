package com.codehub.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import androidx.core.app.NotificationCompat;

import com.google.firebase.messaging.FirebaseMessaging;

public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(final Context context, Intent intent) {
        if (intent == null || intent.getAction() == null) return;
        String action = intent.getAction();
        if (Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            handleSelfUpdateCompleted(context);
        }
        if (Intent.ACTION_BOOT_COMPLETED.equals(action) ||
            Intent.ACTION_MY_PACKAGE_REPLACED.equals(action) ||
            "android.intent.action.QUICKBOOT_POWERON".equals(action)) {
            try {
                FirebaseMessaging.getInstance().getToken().addOnCompleteListener(new com.google.android.gms.tasks.OnCompleteListener<String>() {
                    @Override
                    public void onComplete(com.google.android.gms.tasks.Task<String> task) {
                        try {
                            if (!task.isSuccessful() || task.getResult() == null) return;
                            final String token = task.getResult();
                            SharedPreferences prefs = context.getSharedPreferences("codehub", Context.MODE_PRIVATE);
                            prefs.edit().putString("fcm_token", token).apply();
                            new Thread(new Runnable() {
                                @Override
                                public void run() {
                                    FcmHelper.registerToken(context.getApplicationContext(), token);
                                }
                            }).start();
                        } catch (Exception ignored) {}
                    }
                });
            } catch (Exception ignored) {}

            // Reagendar el sync periódico (WorkManager sobrevive al reboot por
            // su propio receiver; esto es redundancia defensiva) y una pasada ya.
            try { CodeHubSyncScheduler.schedule(context); } catch (Throwable ignored) {}
            try { CodeHubSyncScheduler.syncNow(context); } catch (Throwable ignored) {}

            // El detector de sismos se inicia desde MainActivity cuando ya
            // esta visible; no promover un FGS desde este receiver de fondo.
        }
    }

    private void handleSelfUpdateCompleted(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(AppUpdateChecker.PREFS, Context.MODE_PRIVATE);
        if (!prefs.getBoolean(AppUpdateChecker.PREF_UPDATE_PENDING, false)) return;

        long startedAt = prefs.getLong(AppUpdateChecker.PREF_UPDATE_PENDING_AT, 0L);
        long expectedCode = prefs.getLong(AppUpdateChecker.PREF_UPDATE_PENDING_CODE, 0L);
        String expectedVersion = prefs.getString(AppUpdateChecker.PREF_UPDATE_PENDING_VERSION, "");
        String build = prefs.getString(AppUpdateChecker.PREF_UPDATE_PENDING_BUILD, "");
        if (startedAt <= 0L || System.currentTimeMillis() - startedAt > 2L * 60 * 60 * 1000) {
            clearPendingUpdate(prefs);
            return;
        }

        long installedCode = 0L;
        String installedVersion = "";
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            installedCode = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                    ? info.getLongVersionCode() : info.versionCode;
            installedVersion = info.versionName == null ? "" : info.versionName;
        } catch (Exception ignored) { }
        boolean expectedInstalled = expectedCode > 0L
                ? installedCode >= expectedCode
                : !expectedVersion.isEmpty() && expectedVersion.equals(installedVersion);
        clearPendingUpdate(prefs);
        if (!expectedInstalled) return;

        Intent launch = new Intent(context, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent openApp = PendingIntent.getActivity(context, 40322, launch, flags);

        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                    && manager.getNotificationChannel("codehub_apk_updates") == null) {
                manager.createNotificationChannel(new NotificationChannel(
                        "codehub_apk_updates", "Actualizaciones de CodeHub", NotificationManager.IMPORTANCE_DEFAULT));
            }
            boolean notificationsAllowed = manager.areNotificationsEnabled()
                    && (Build.VERSION.SDK_INT < 33 || context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                    == PackageManager.PERMISSION_GRANTED);
            if (notificationsAllowed) {
                try {
                    String text = "CodeHub v" + installedVersion + (build.isEmpty() ? "" : " · " + build);
                    manager.notify(40322, new NotificationCompat.Builder(context, "codehub_apk_updates")
                            .setSmallIcon(R.drawable.ic_launcher_real)
                            .setContentTitle("Actualización instalada")
                            .setContentText(text)
                            .setCategory(NotificationCompat.CATEGORY_SYSTEM)
                            .setAutoCancel(true)
                            .setContentIntent(openApp)
                            .build());
                } catch (Throwable ignored) { }
            }
        }

        // El camino Shizuku también lanza MainActivity desde el proceso shell;
        // esta apertura es el mejor esfuerzo para la confirmación del instalador.
        try { context.startActivity(launch); } catch (Throwable ignored) { }
    }

    private void clearPendingUpdate(SharedPreferences prefs) {
        prefs.edit()
                .remove(AppUpdateChecker.PREF_UPDATE_PENDING)
                .remove(AppUpdateChecker.PREF_UPDATE_PENDING_AT)
                .remove(AppUpdateChecker.PREF_UPDATE_PENDING_CODE)
                .remove(AppUpdateChecker.PREF_UPDATE_PENDING_VERSION)
                .remove(AppUpdateChecker.PREF_UPDATE_PENDING_BUILD)
                .apply();
    }
}
