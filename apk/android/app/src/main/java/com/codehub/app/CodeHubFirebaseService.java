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

        // Alerta de sismo (real o simulacro): lanzar pantalla completa
        if (type.equals("quake_alert") || type.equals("quake_sim")) {
            try {
                float mag = Float.parseFloat(message.getData().getOrDefault("mag", "5.0"));
                double dist = Double.parseDouble(message.getData().getOrDefault("dist", "50"));
                String dir = message.getData().getOrDefault("dir", "N");
                String place = message.getData().getOrDefault("place", "Sismo detectado");
                long time = Long.parseLong(message.getData().getOrDefault("time", String.valueOf(System.currentTimeMillis())));
                boolean sim = type.equals("quake_sim");
                String alertType = message.getData().getOrDefault("alertType", "earthquake");

                // Intent para la pantalla completa
                Intent fullScreenIntent = new Intent(ctx, EarthquakeAlertActivity.class);
                fullScreenIntent.putExtra(EarthquakeAlertActivity.EXTRA_MAG, mag);
                fullScreenIntent.putExtra(EarthquakeAlertActivity.EXTRA_DIST, dist);
                fullScreenIntent.putExtra(EarthquakeAlertActivity.EXTRA_DIR, dir);
                fullScreenIntent.putExtra(EarthquakeAlertActivity.EXTRA_PLACE, place);
                fullScreenIntent.putExtra(EarthquakeAlertActivity.EXTRA_TIME, time);
                fullScreenIntent.putExtra(EarthquakeAlertActivity.EXTRA_SIM, sim);
                fullScreenIntent.putExtra("alertType", alertType);
                fullScreenIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP
                        | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);

                PendingIntent fullScreenPi = PendingIntent.getActivity(ctx, 4243, fullScreenIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

                // Notificación de alta prioridad con full-screen intent
                // (esto es lo que permite mostrar la alerta incluso con la app cerrada
                // y con la pantalla bloqueada, como las llamadas entrantes)
                NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
                if (nm != null) {
                    // Asegurar canal
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        NotificationChannel ch = nm.getNotificationChannel("codehub_quakes");
                        if (ch == null) {
                            ch = new NotificationChannel("codehub_quakes", "Sismos (alarma)",
                                    NotificationManager.IMPORTANCE_HIGH);
                            ch.setDescription("Alertas de sismos cercanos a tu ubicación");
                            ch.enableVibration(true);
                            nm.createNotificationChannel(ch);
                        }
                    }

                    Notification notif = new NotificationCompat.Builder(ctx, "codehub_quakes")
                            .setSmallIcon(R.drawable.ic_stat_quake)
                            .setContentTitle(sim ? "🧪 SIMULACRO DE SISMO" : "⚠️ ALERTA DE SISMO")
                            .setContentText("M " + String.format(java.util.Locale.US, "%.1f", mag)
                                    + " a " + Math.round(dist) + " km " + dir + " de tu ubicación")
                            .setPriority(NotificationCompat.PRIORITY_MAX)
                            .setCategory(NotificationCompat.CATEGORY_ALARM)
                            .setFullScreenIntent(fullScreenPi, true)
                            .setAutoCancel(true)
                            .setOngoing(true)
                            .build();

                    nm.notify(4243, notif);
                }

                // También intentar lanzar directamente (funciona si la app está en primer plano)
                try {
                    EarthquakeAlertActivity.launch(ctx, mag, dist, dir, place, time, sim);
                } catch (Exception ignored) {}
            } catch (Exception ignored) {}
            return;
        }

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
