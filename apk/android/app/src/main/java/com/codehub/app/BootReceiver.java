package com.codehub.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import com.google.firebase.messaging.FirebaseMessaging;

public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(final Context context, Intent intent) {
        if (intent == null || intent.getAction() == null) return;
        String action = intent.getAction();
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

            // Detector de sismos por acelerómetro (capa C): reanudarlo tras
            // el reboot solo si el usuario no lo desactivó.
            try {
                SharedPreferences qp = context.getSharedPreferences("codehub", Context.MODE_PRIVATE);
                if (qp.getBoolean("quake_detector_enabled", true)) {
                    Intent qi = new Intent(context, QuakeDetectorService.class);
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                        context.startForegroundService(qi);
                    } else {
                        context.startService(qi);
                    }
                }
            } catch (Exception ignored) {}
        }
    }
}
