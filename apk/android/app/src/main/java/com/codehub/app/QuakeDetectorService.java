package com.codehub.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import androidx.core.app.NotificationCompat;
import org.json.JSONObject;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Detector de sismos por acelerómetro (capa C de la alerta temprana).
 * Escucha el sensor en primer plano y, al detectar un pico STA/LTA, dispara
 * la alarma local a los 0 s y reporta al backend para fanout FCM a vecinos.
 */
public class QuakeDetectorService extends Service implements SensorEventListener {
    private static final String TAG = "QuakeDetector";
    private static final String CHANNEL = "codehub_quake_detector";
    private static final int NOTIF_ID = 9001;
    private static final double STA_ALPHA = 0.3;   // ~1 s
    private static final double LTA_ALPHA = 0.005; // ~30 s
    private static final double TRIGGER = 4.5;
    private static final double PEAK_G = 0.18;

    private SensorManager sm;
    private Sensor accel;
    private double sta = 0, lta = 0;
    private long lastTrigger = 0;
    private final AtomicBoolean cooldown = new AtomicBoolean(false);
    // Inteligencia anti-falsos-positivos: solo se considera "sismo" si el
    // dispositivo lleva >= STATIONARY_REQUIRED_MS sin movimiento significativo
    // (usuario no lo está usando ni colocando). Si se movió en los últimos
    // MOTION_GRACE_MS, se ignora (intercepta tomar/colocar el teléfono).
    private long lastMotionTime = 0;
    private long stationarySince = 0;
    private static final long STATIONARY_REQUIRED_MS = 5 * 60 * 1000L;
    private static final long MOTION_GRACE_MS = 30 * 1000L;
    private static final double MOTION_THRESHOLD_G = 0.05;
    // Dos modos bien diferenciados (UX): el usuario ve en la notificación
    // persistente en cuál está. HUMAN_USE = ignorando (no alerta);
    // AT_REST = protegiendo (alerta activa).
    private static final int MODE_HUMAN_USE = 0;
    private static final int MODE_AT_REST = 1;
    private int currentMode = MODE_HUMAN_USE;

    @Override
    public void onCreate() {
        super.onCreate();
        sm = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        accel = sm != null ? sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) : null;
        createChannel();
        startForeground(NOTIF_ID, buildNotif("CodeHub protegiendo sismos"));
        if (accel != null) sm.registerListener(this, accel, SensorManager.SENSOR_DELAY_GAME);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        if (sm != null && accel != null) sm.unregisterListener(this);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent i) { return null; }

    @Override
    public void onSensorChanged(SensorEvent e) {
        if (e.sensor.getType() != Sensor.TYPE_ACCELEROMETER) return;
        double x = e.values[0], y = e.values[1], z = e.values[2];
        double mag = Math.sqrt(x * x + y * y + z * z);
        double dev = Math.abs(mag - 9.81);
        long now = System.currentTimeMillis();
        // Inteligencia de contexto: ¿el teléfono se está usando/moviendo?
        boolean wasHumanUse = (currentMode == MODE_HUMAN_USE);
        if (dev / 9.81 >= MOTION_THRESHOLD_G) {
            lastMotionTime = now;
            stationarySince = 0; // estaba quieto, ahora se mueve
        } else if (stationarySince == 0) {
            stationarySince = now; // acaba de quedar quieto
        }
        // Determinar modo actual
        boolean atRest = (stationarySince != 0)
                && (now - stationarySince) >= STATIONARY_REQUIRED_MS
                && (now - lastMotionTime) >= MOTION_GRACE_MS;
        int newMode = atRest ? MODE_AT_REST : MODE_HUMAN_USE;
        if (newMode != currentMode) {
            currentMode = newMode;
            refreshModeNotification();
        }
        sta = sta + STA_ALPHA * (dev - sta);
        lta = lta + LTA_ALPHA * (dev - lta);
        double ratio = (lta > 0.001) ? (sta / lta) : 0;
        if (!cooldown.get() && ratio >= TRIGGER && dev >= PEAK_G * 9.81
                && (now - lastTrigger) > 30000L
                && currentMode == MODE_AT_REST) {
            lastTrigger = now;
            cooldown.set(true);
            new Handler(Looper.getMainLooper()).postDelayed(() -> cooldown.set(false), 30000);
            triggerQuake(dev / 9.81);
        }
    }

    @Override
    public void onAccuracyChanged(Sensor s, int a) {}

    private void triggerQuake(double peakG) {
        fireLocalAlarm();
        reportToBackend(peakG);
    }

    private void fireLocalAlarm() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (nm.getNotificationChannel("codehub_quakes_v2") == null) {
                NotificationChannel ch = new NotificationChannel("codehub_quakes_v2", "Sismos (alarma)", NotificationManager.IMPORTANCE_HIGH);
                ch.enableVibration(true);
                nm.createNotificationChannel(ch);
            }
        }
        Intent tap = new Intent(this, MainActivity.class);
        tap.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        tap.putExtra("open_url", "/#sismos-section");
        PendingIntent pi = PendingIntent.getActivity(this, 4243, tap,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification n = new NotificationCompat.Builder(this, "codehub_quakes_v2")
                .setSmallIcon(R.drawable.ic_stat_quake)
                .setContentTitle("🚨 ALERTA SÍSMICA")
                .setContentText("Sismo detectado por tu dispositivo. Protégete.")
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setFullScreenIntent(pi, true)
                .setAutoCancel(true)
                .build();
        nm.notify(4243, n);
    }

    private void reportToBackend(double peakG) {
        new Thread(() -> {
            try {
                double lat = 0, lon = 0;
                android.location.LocationManager lm = (android.location.LocationManager) getSystemService(Context.LOCATION_SERVICE);
                if (lm != null) {
                    for (String p : lm.getProviders(true)) {
                        android.location.Location l = lm.getLastKnownLocation(p);
                        if (l != null) { lat = l.getLatitude(); lon = l.getLongitude(); break; }
                    }
                }
                if (lat == 0 && lon == 0) return;
                double mag = peakG < 0.2 ? 4.0 : peakG < 0.5 ? 4.8 : peakG < 1.0 ? 5.5 : 6.5;
                JSONObject json = new JSONObject();
                json.put("lat", lat);
                json.put("lon", lon);
                json.put("mag", mag);
                json.put("deviceId", android.provider.Settings.Secure.getString(getContentResolver(), android.provider.Settings.Secure.ANDROID_ID));
                URL url = new URL("https://codehub-98s6.onrender.com/api/quake/report");
                HttpURLConnection c = (HttpURLConnection) url.openConnection();
                c.setRequestMethod("POST");
                c.setRequestProperty("Content-Type", "application/json");
                c.setDoOutput(true);
                c.setConnectTimeout(5000);
                c.setReadTimeout(5000);
                c.getOutputStream().write(json.toString().getBytes("UTF-8"));
                c.getResponseCode();
                c.disconnect();
            } catch (Exception e) {
                Log.w(TAG, "report failed: " + e.getMessage());
            }
        }).start();
    }

    private void refreshModeNotification() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        String text = (currentMode == MODE_AT_REST)
                ? "CodeHub protegiendo sismos — EN REPOSO (alerta activa)"
                : "CodeHub — MODO USO (no alerta mientras usas el móvil)";
        nm.notify(NOTIF_ID, buildNotif(text));
    }
    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null && nm.getNotificationChannel(CHANNEL) == null) {
                NotificationChannel ch = new NotificationChannel(CHANNEL, "Detector de sismos", NotificationManager.IMPORTANCE_LOW);
                nm.createNotificationChannel(ch);
            }
        }
    }

    private Notification buildNotif(String text) {
        return new NotificationCompat.Builder(this, CHANNEL)
                .setContentTitle("CodeHub")
                .setContentText(text)
                .setSmallIcon(R.drawable.ic_stat_quake)
                .setOngoing(true)
                .build();
    }
}
