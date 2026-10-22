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

        // Alerta de sismo (real o simulacro). Llega como DATA-ONLY de alta
        // prioridad, así que este método corre SIEMPRE (app abierta, en
        // segundo plano o cerrada).
        //   quake_alert / quake_sim → pantalla completa + alarma
        //   quake_info              → notificación heads-up (sismo perceptible)
        if (type.equals("quake_alert") || type.equals("quake_sim") || type.equals("quake_info")) {
            handleQuake(message, type, title, body);
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

    /** Canal de alarma de sismos. ID nuevo (v2): Android no deja cambiar el
     *  sonido/importancia de un canal ya creado, y el anterior no tenía alarma. */
    private static final String CHANNEL_QUAKES = "codehub_quakes_v2";

    private void ensureQuakeChannel(NotificationManager nm) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        if (nm.getNotificationChannel(CHANNEL_QUAKES) != null) return;
        NotificationChannel ch = new NotificationChannel(CHANNEL_QUAKES, "Sismos (alarma)",
                NotificationManager.IMPORTANCE_HIGH);
        ch.setDescription("Alertas de sismos cercanos a tu ubicación");
        ch.enableVibration(true);
        ch.setVibrationPattern(new long[]{0, 500, 200, 500, 200, 500, 200, 900});
        ch.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        ch.setBypassDnd(true); // solo surte efecto si el usuario concede acceso a "No molestar"
        android.media.AudioAttributes aa = new android.media.AudioAttributes.Builder()
                .setUsage(android.media.AudioAttributes.USAGE_ALARM)
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build();
        ch.setSound(android.provider.Settings.System.DEFAULT_ALARM_ALERT_URI != null
                ? android.provider.Settings.System.DEFAULT_ALARM_ALERT_URI
                : android.provider.Settings.System.DEFAULT_NOTIFICATION_URI, aa);
        nm.createNotificationChannel(ch);
    }

    private void handleQuake(RemoteMessage message, String type, String title, String body) {
        final Context ctx = getApplicationContext();
        try {
            java.util.Map<String, String> d = message.getData();
            float mag = parseF(d.get("mag"), 5.0f);
            double dist = parseD(d.get("dist"), 50);
            String dir = d.get("dir") != null ? d.get("dir") : "N";
            String place = d.get("place") != null ? d.get("place") : "Sismo detectado";
            long now = System.currentTimeMillis();
            long time = parseL(d.get("time"), now);
            long eta = parseL(d.get("eta"), 0L);          // llegada estimada de la onda S (epoch ms)
            boolean sim = type.equals("quake_sim");
            boolean alarm = !type.equals("quake_info");
            String alertType = d.get("alertType") != null ? d.get("alertType") : "earthquake";
            String evId = d.get("evId") != null ? d.get("evId") : "";

            // Defensa extra: un aviso real con más de 30 min NO se muestra como
            // alarma (p. ej. el teléfono estuvo apagado y FCM lo entregó tarde).
            if (!sim && now - time > 30 * 60 * 1000L) return;

            NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            ensureQuakeChannel(nm);

            // Mismo sismo → misma notificación (se reemplaza, no se apila)
            int notifId = evId.isEmpty() ? 4243 : (4300 + Math.abs(evId.hashCode() % 500));

            Intent tap = new Intent(ctx, MainActivity.class);
            tap.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            tap.putExtra("open_url", "/#sismos-section");
            PendingIntent tapPi = PendingIntent.getActivity(ctx, notifId, tap,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

            NotificationCompat.Builder nb = new NotificationCompat.Builder(ctx, CHANNEL_QUAKES)
                    .setSmallIcon(R.drawable.ic_stat_quake)
                    .setContentTitle(title)
                    .setContentText(body)
                    .setStyle(new NotificationCompat.BigTextStyle().bigText(body))
                    .setPriority(alarm ? NotificationCompat.PRIORITY_MAX : NotificationCompat.PRIORITY_HIGH)
                    .setCategory(alarm ? NotificationCompat.CATEGORY_ALARM : NotificationCompat.CATEGORY_MESSAGE)
                    .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                    .setWhen(time)
                    .setShowWhen(true)
                    .setContentIntent(tapPi)
                    .setAutoCancel(true)
                    .setTimeoutAfter(10 * 60 * 1000L);

            if (alarm) {
                Intent full = new Intent(ctx, EarthquakeAlertActivity.class);
                full.putExtra(EarthquakeAlertActivity.EXTRA_MAG, mag);
                full.putExtra(EarthquakeAlertActivity.EXTRA_DIST, dist);
                full.putExtra(EarthquakeAlertActivity.EXTRA_DIR, dir);
                full.putExtra(EarthquakeAlertActivity.EXTRA_PLACE, place);
                full.putExtra(EarthquakeAlertActivity.EXTRA_TIME, time);
                full.putExtra(EarthquakeAlertActivity.EXTRA_ETA, eta);
                full.putExtra(EarthquakeAlertActivity.EXTRA_SIM, sim);
                full.putExtra(EarthquakeAlertActivity.EXTRA_TYPE, alertType);
                full.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP
                        | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
                PendingIntent fullPi = PendingIntent.getActivity(ctx, notifId + 1000, full,
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
                // Full-screen intent: abre la alerta sobre el bloqueo, como una llamada
                nb.setFullScreenIntent(fullPi, true);
            }
            nm.notify(notifId, nb.build());

            // Con la app en primer plano (o con permiso de overlay) abrir directo
            if (alarm) {
                try {
                    EarthquakeAlertActivity.launch(ctx, mag, dist, dir, place, time, sim, alertType, eta);
                } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}
    }

    private static float parseF(String v, float def) { try { return v == null ? def : Float.parseFloat(v); } catch (Exception e) { return def; } }
    private static double parseD(String v, double def) { try { return v == null ? def : Double.parseDouble(v); } catch (Exception e) { return def; } }
    private static long parseL(String v, long def) { try { return v == null ? def : (long) Double.parseDouble(v); } catch (Exception e) { return def; } }

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
