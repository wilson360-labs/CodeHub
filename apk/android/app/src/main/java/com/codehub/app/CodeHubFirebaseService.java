package com.codehub.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;

import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;

public class CodeHubFirebaseService extends FirebaseMessagingService {

    private static final String CHANNEL_UPDATES = "codehub_updates";
    private static final String CHANNEL_WEATHER  = "codehub_weather";
    private static final String CHANNEL_GENERAL  = "codehub_default";

    @Override
    public void onNewToken(@NonNull String token) {
        super.onNewToken(token);
        // Register token with backend
        new Thread(new Runnable() {
            @Override
            public void run() {
                FcmHelper.registerToken(getApplicationContext(), token);
            }
        }).start();
    }

    @Override
    public void onMessageReceived(@NonNull RemoteMessage message) {
        super.onMessageReceived(message);

        String type = message.getData().getOrDefault("type", "general");
        String title = message.getNotification() != null ? message.getNotification().getTitle() : message.getData().get("title");
        String body  = message.getNotification() != null ? message.getNotification().getBody()  : message.getData().get("body");
        String url   = message.getData().get("url");

        if (title == null || title.isEmpty()) title = "CodeHub";
        if (body == null) body = "";

        String channelId;
        int notifId;
        switch (type) {
            case "new_app":
            case "app_update":
            case "announcement":
                channelId = CHANNEL_UPDATES;
                notifId = 3001 + (int)(System.currentTimeMillis() % 100);
                break;
            case "weather":
                channelId = CHANNEL_WEATHER;
                notifId = 3002;
                break;
            default:
                channelId = CHANNEL_GENERAL;
                notifId = 3000;
                break;
        }

        showNotification(channelId, notifId, title, body, url);
    }

    private void showNotification(String channelId, int notifId, String title, String body, String url) {
        Context ctx = getApplicationContext();
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            String name;
            switch (channelId) {
                case CHANNEL_WEATHER:  name = "Clima"; break;
                case CHANNEL_UPDATES: name = "Actualizaciones"; break;
                default:              name = "CodeHub"; break;
            }
            NotificationChannel ch = new NotificationChannel(channelId, name,
                channelId.equals(CHANNEL_WEATHER) ? NotificationManager.IMPORTANCE_HIGH : NotificationManager.IMPORTANCE_DEFAULT);
            ch.setDescription("Notificaciones de " + name.toLowerCase());
            nm.createNotificationChannel(ch);
        }

        Intent intent = new Intent(ctx, MainActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        if (url != null && !url.isEmpty()) intent.putExtra("open_url", url);
        PendingIntent pi = PendingIntent.getActivity(ctx, notifId, intent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification notif = new NotificationCompat.Builder(ctx, channelId)
            .setSmallIcon(R.drawable.ic_launcher_real)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(new NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build();

        nm.notify(notifId, notif);
    }
}
