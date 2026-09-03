package com.codehub.app;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.location.Location;
import android.os.Build;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;

import com.google.android.gms.location.CurrentLocationRequest;
import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;
import com.google.android.gms.tasks.CancellationTokenSource;

import org.json.JSONObject;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Foreground Service que mantiene la app viva en segundo plano.
 *
 * - Adquiere WakeLock parcial para que la CPU siga corriendo con pantalla apagada.
 * - Muestra notificación persistente de "CodeHub activo".
 * - Cada ~15 min re-sincroniza token FCM + ubicación con el backend.
 * - Al recibir FCM push, el servicio ya está vivo para procesarlo más rápido.
 */
public class CodeHubSyncService extends Service {

    private static final String CHANNEL_SYNC = "codehub_sync";
    private static final int NOTIF_ID = 9999;
    private static final long SYNC_INTERVAL_MS = 15 * 60 * 1000; // 15 minutos
    private static final long ALARM_INTERVAL_MS = 15 * 60 * 1000;

    private PowerManager.WakeLock wakeLock;
    private FusedLocationProviderClient fusedLocation;
    private boolean isRunning = false;

    // ── SINGLETON START ────────────────────────────────────────
    public static void startIfNotRunning(Context ctx) {
        try {
            Intent intent = new Intent(ctx, CodeHubSyncService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(intent);
            } else {
                ctx.startService(intent);
            }
        } catch (Exception ignored) {}
    }

    public static void stop(Context ctx) {
        try { ctx.stopService(new Intent(ctx, CodeHubSyncService.class)); } catch (Exception ignored) {}
    }

    // ── LIFECYCLE ──────────────────────────────────────────────
    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        acquireWakeLock();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(NOTIF_ID, buildNotification());

        if (!isRunning) {
            isRunning = true;
            // Sync inmediato al iniciar
            new Thread(new Runnable() {
                @Override public void run() { syncNow(); }
            }).start();
            // Sync periódico vía AlarmManager (resiste Doze mejor que Timer)
            scheduleAlarm();
            // Ubicación periódica
            startLocationUpdates();
        }

        // START_STICKY: si el sistema mata el servicio, recrearlo automáticamente
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        isRunning = false;
        releaseWakeLock();
        stopLocationUpdates();
        // Re-programar alarm para que el servicio renazca
        scheduleAlarm();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    // ── WAKELOCK ───────────────────────────────────────────────
    private void acquireWakeLock() {
        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (pm != null) {
                wakeLock = pm.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "CodeHub:SyncWakeLock"
                );
                wakeLock.acquire(4 * 60 * 60 * 1000L); // 4 horas, se renueva cada sync
            }
        } catch (Exception ignored) {}
    }

    private void releaseWakeLock() {
        try {
            if (wakeLock != null && wakeLock.isHeld()) {
                wakeLock.release();
            }
        } catch (Exception ignored) {}
    }

    // ── NOTIFICATION (foreground) ───────────────────────────────
    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                CHANNEL_SYNC,
                "CodeHub Activo",
                NotificationManager.IMPORTANCE_LOW
            );
            ch.setDescription("Mantiene CodeHub sincronizado en segundo plano");
            ch.setShowBadge(false);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(ch);
        }
    }

    private Notification buildNotification() {
        Intent mainIntent = new Intent(this, MainActivity.class);
        mainIntent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, 0, mainIntent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(this, CHANNEL_SYNC);
        } else {
            builder = new Notification.Builder(this);
        }

        return builder.setSmallIcon(R.drawable.ic_launcher_real)
            .setContentTitle("CodeHub activo")
            .setContentText("Sincronizando en segundo plano")
            .setContentIntent(pi)
            .setOngoing(true)
            .build();
    }

    // ── SYNC ───────────────────────────────────────────────────
    private void syncNow() {
        try {
            // Renovar WakeLock en cada ciclo de sync
            if (wakeLock != null && wakeLock.isHeld()) {
                wakeLock.release();
            }
            acquireWakeLock();

            SharedPreferences prefs = getSharedPreferences("codehub", MODE_PRIVATE);
            String fcmToken = prefs.getString("fcm_token", "");

            // 1) Re-registrar FCM token con ubicación actual
            if (!fcmToken.isEmpty()) {
                double lat = Double.longBitsToDouble(prefs.getLong("lat_bits", 0));
                double lon = Double.longBitsToDouble(prefs.getLong("lon_bits", 0));
                registerTokenWithBackend(fcmToken, lat, lon);
            }

            // 2) Pedir ubicación fresca
            refreshLocation();
        } catch (Exception ignored) {}
    }

    private void registerTokenWithBackend(String token, double lat, double lon) {
        try {
            String versionName = "1.2.0";
            try { versionName = getPackageManager().getPackageInfo(getPackageName(), 0).versionName; } catch (Exception ignored) {}

            JSONObject body = new JSONObject();
            body.put("token", token);
            body.put("lat", lat);
            body.put("lon", lon);
            body.put("appName", "CodeHub");
            body.put("appVersion", versionName);
            body.put("platform", "android");
            body.put("userAgent", Build.MANUFACTURER + " " + Build.MODEL + " (Android " + Build.VERSION.RELEASE + ")");

            HttpURLConnection conn = (HttpURLConnection) new URL(
                "https://codehub-98s6.onrender.com/api/push/fcm-subscribe"
            ).openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setDoOutput(true);
            OutputStream os = conn.getOutputStream();
            os.write(body.toString().getBytes(StandardCharsets.UTF_8));
            os.close();
            conn.getResponseCode();
            conn.disconnect();

            // También actualizar ubicación por separado
            if (lat != 0 || lon != 0) {
                JSONObject locBody = new JSONObject();
                locBody.put("token", token);
                locBody.put("lat", lat);
                locBody.put("lon", lon);
                HttpURLConnection locConn = (HttpURLConnection) new URL(
                    "https://codehub-98s6.onrender.com/api/push/fcm-location"
                ).openConnection();
                locConn.setRequestMethod("POST");
                locConn.setConnectTimeout(10000);
                locConn.setReadTimeout(10000);
                locConn.setRequestProperty("Content-Type", "application/json");
                locConn.setDoOutput(true);
                OutputStream los = locConn.getOutputStream();
                los.write(locBody.toString().getBytes(StandardCharsets.UTF_8));
                los.close();
                locConn.getResponseCode();
                locConn.disconnect();
            }
        } catch (Exception ignored) {}
    }

    // ── LOCATION ───────────────────────────────────────────────
    // Usa FusedLocationProviderClient (GPS + WiFi + red vía Google) en
    // lugar de LocationManager crudo — evita que este servicio en
    // segundo plano sobreescriba con un fix de NETWORK_PROVIDER
    // impreciso una coordenada GPS buena que ya tenía la app.
    private FusedLocationProviderClient getFused() {
        if (fusedLocation == null) fusedLocation = LocationServices.getFusedLocationProviderClient(this);
        return fusedLocation;
    }

    private boolean hasLocationPermission() {
        boolean fine = checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION)
            == android.content.pm.PackageManager.PERMISSION_GRANTED;
        boolean coarse = checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION)
            == android.content.pm.PackageManager.PERMISSION_GRANTED;
        return fine || coarse;
    }

    private void startLocationUpdates() {
        try {
            if (!hasLocationPermission()) return;

            // Última ubicación conocida por Fused mientras llega el fix fresco
            getFused().getLastLocation()
                .addOnSuccessListener(loc -> { if (loc != null) saveLocation(loc); })
                .addOnFailureListener(e -> {});

            refreshLocation();
        } catch (Exception ignored) {}
    }

    private void refreshLocation() {
        try {
            if (!hasLocationPermission()) return;
            CurrentLocationRequest request = new CurrentLocationRequest.Builder()
                    .setPriority(Priority.PRIORITY_HIGH_ACCURACY)
                    .setMaxUpdateAgeMillis(0)
                    .build();
            CancellationTokenSource cts = new CancellationTokenSource();
            getFused().getCurrentLocation(request, cts.getToken())
                .addOnSuccessListener(loc -> { if (loc != null) saveLocation(loc); })
                .addOnFailureListener(e -> {});
            // No dejar la petición colgada si Play Services nunca responde
            new android.os.Handler(Looper.getMainLooper()).postDelayed(cts::cancel, 20000);
        } catch (Exception ignored) {}
    }

    private void stopLocationUpdates() {
        // FusedLocationProviderClient.getCurrentLocation() es de una sola
        // vez (no requiere removeUpdates); el CancellationTokenSource de
        // cada llamada se cancela solo por su propio timeout.
    }

    private void saveLocation(Location loc) {
        try {
            double lat = loc.getLatitude();
            double lon = loc.getLongitude();
            SharedPreferences prefs = getSharedPreferences("codehub", MODE_PRIVATE);
            prefs.edit()
                .putLong("lat_bits", Double.doubleToRawLongBits(lat))
                .putLong("lon_bits", Double.doubleToRawLongBits(lon))
                .apply();
            WeatherWidgetProvider.requestRefresh(this);
            // Enviar al backend
            String token = prefs.getString("fcm_token", "");
            if (!token.isEmpty()) {
                final double fLat = lat, fLon = lon;
                final String fToken = token;
                new Thread(new Runnable() {
                    @Override public void run() {
                        registerTokenWithBackend(fToken, fLat, fLon);
                    }
                }).start();
            }
        } catch (Exception ignored) {}
    }

    // ── ALARM PERIÓDICO ────────────────────────────────────────
    private void scheduleAlarm() {
        try {
            AlarmManager am = (AlarmManager) getSystemService(ALARM_SERVICE);
            if (am == null) return;

            Intent intent = new Intent(this, SyncAlarmReceiver.class);
            PendingIntent pi = PendingIntent.getBroadcast(this, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

            long triggerAt = SystemClock.elapsedRealtime() + ALARM_INTERVAL_MS;

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi);
            } else {
                am.setExact(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi);
            }
        } catch (Exception ignored) {}
    }
}
