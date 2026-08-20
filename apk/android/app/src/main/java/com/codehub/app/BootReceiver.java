package com.codehub.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import com.google.firebase.messaging.FirebaseMessaging;

public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getAction() == null) return;
        String action = intent.getAction();
        if (Intent.ACTION_BOOT_COMPLETED.equals(action) ||
            Intent.ACTION_MY_PACKAGE_REPLACED.equals(action) ||
            "android.intent.action.QUICKBOOT_POWERON".equals(action)) {
            // Re-register FCM token on boot
            FirebaseMessaging.getInstance().getToken().addOnCompleteListener(task -> {
                if (!task.isSuccessful() || task.getResult() == null) return;
                String token = task.getResult();
                SharedPreferences prefs = context.getSharedPreferences("codehub", Context.MODE_PRIVATE);
                prefs.edit().putString("fcm_token", token).apply();
                new Thread(() -> FcmHelper.registerToken(context.getApplicationContext(), token)).start();
            });
        }
    }
}
