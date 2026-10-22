package com.codehub.app;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.TextView;

import java.util.Locale;

/**
 * Pantalla completa de alerta de sismo. Se muestra encima de la app y del
 * bloqueo cuando se detecta un sismo grande (M≥5.0) o en modo simulacro.
 *
 * Uso:
 *   EarthquakeAlertActivity.launch(ctx, mag, distKm, dir, place, timeMs, isSim);
 */
public class EarthquakeAlertActivity extends Activity {

    public static final String EXTRA_MAG     = "mag";
    public static final String EXTRA_DIST    = "dist";
    public static final String EXTRA_DIR     = "dir";
    public static final String EXTRA_PLACE   = "place";
    public static final String EXTRA_TIME    = "time";
    public static final String EXTRA_SIM     = "sim";
    public static final String EXTRA_TYPE    = "alertType";
    /** Llegada estimada de la onda S a tu ubicación (epoch ms; 0 = desconocida). */
    public static final String EXTRA_ETA     = "eta";

    private PowerManager.WakeLock wakeLock;

    public static void launch(Context ctx, float mag, double distKm, String dir,
                              String place, long timeMs, boolean isSim) {
        launch(ctx, mag, distKm, dir, place, timeMs, isSim, "earthquake");
    }

    public static void launch(Context ctx, float mag, double distKm, String dir,
                              String place, long timeMs, boolean isSim, String alertType) {
        launch(ctx, mag, distKm, dir, place, timeMs, isSim, alertType, 0L);
    }

    public static void launch(Context ctx, float mag, double distKm, String dir,
                              String place, long timeMs, boolean isSim, String alertType, long etaMs) {
        Intent i = new Intent(ctx, EarthquakeAlertActivity.class);
        i.putExtra(EXTRA_ETA, etaMs);
        i.putExtra(EXTRA_MAG, mag);
        i.putExtra(EXTRA_DIST, distKm);
        i.putExtra(EXTRA_DIR, dir);
        i.putExtra(EXTRA_PLACE, place);
        i.putExtra(EXTRA_TIME, timeMs);
        i.putExtra(EXTRA_SIM, isSim);
        i.putExtra(EXTRA_TYPE, alertType != null ? alertType : "earthquake");
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP
                | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
        ctx.startActivity(i);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Mostrar encima del bloqueo y despertar la pantalla
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        } else {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                    | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD);

        // Wake lock para mantener la pantalla encendida
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (pm != null) {
            wakeLock = pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK
                    | PowerManager.ACQUIRE_CAUSES_WAKEUP, "CodeHub:QuakeAlert");
            wakeLock.acquire(5 * 60 * 1000L); // 5 min max
        }

        setContentView(R.layout.activity_earthquake_alert);

        float mag = getIntent().getFloatExtra(EXTRA_MAG, 5.0f);
        double dist = getIntent().getDoubleExtra(EXTRA_DIST, 0);
        String dir = getIntent().getStringExtra(EXTRA_DIR);
        String place = getIntent().getStringExtra(EXTRA_PLACE);
        long time = getIntent().getLongExtra(EXTRA_TIME, System.currentTimeMillis());
        boolean sim = getIntent().getBooleanExtra(EXTRA_SIM, false);
        String alertType = getIntent().getStringExtra(EXTRA_TYPE);
        if (alertType == null) alertType = "earthquake";

        TextView title = findViewById(R.id.quake_alert_title);
        TextView subtitle = findViewById(R.id.quake_alert_subtitle);
        TextView magTv = findViewById(R.id.quake_alert_mag);
        TextView distTv = findViewById(R.id.quake_alert_distance);
        TextView placeTv = findViewById(R.id.quake_alert_place);
        TextView simTv = findViewById(R.id.quake_alert_sim);
        TextView safeBtn = findViewById(R.id.quake_alert_safe);
        TextView sourceTv = findViewById(R.id.quake_alert_source);

        // Ajustar UI según tipo de alerta
        boolean isHurricane = "hurricane".equals(alertType) || "cyclone".equals(alertType);
        boolean isTsunami = "tsunami".equals(alertType);
        boolean isFlood = "flood".equals(alertType);

        String alertIcon = "⚠️";
        String alertTitle = "ALERTA DE SISMO";
        int alertColor = 0xFFFF6B6B;
        String subtitleText = "Sismo detectado cerca de tu ubicación";
        String sourceText = sim ? "Simulacro · CodeHub" : "Fuente: EMSC / USGS · CodeHub";

        if (isHurricane) {
            alertIcon = "🌀";
            alertTitle = "ALERTA DE HURACÁN";
            alertColor = 0xFF38BDF8;
            subtitleText = "Huracán detectado en tu zona";
            sourceText = sim ? "Simulacro · CodeHub" : "Fuente: NHC/NOAA · CodeHub";
        } else if (isTsunami) {
            alertIcon = "🌊";
            alertTitle = "ALERTA DE TSUNAMI";
            alertColor = 0xFF38BDF8;
            subtitleText = "Tsunami detectado - busca altura";
            sourceText = sim ? "Simulacro · CodeHub" : "Fuente: PTWC · CodeHub";
        } else if (isFlood) {
            alertIcon = "💧";
            alertTitle = "ALERTA DE INUNDACIÓN";
            alertColor = 0xFF60A5FA;
            subtitleText = "Riesgo de inundación en tu zona";
            sourceText = sim ? "Simulacro · CodeHub" : "Fuente: NOAA · CodeHub";
        }

        // Actualizar icono de la barra de alerta
        TextView iconBubble = findViewById(R.id.quake_alert_icon);
        if (iconBubble != null) iconBubble.setText(alertIcon);

        title.setText(alertTitle);
        title.setTextColor(alertColor);
        subtitle.setText(subtitleText);
        magTv.setTextColor(alertColor);

        if (isHurricane || isTsunami || isFlood) {
            // Para huracanes/tsunamis/inundaciones, la magnitud muestra categoría o nivel
            magTv.setText(String.format(java.util.Locale.US, "%.0f", mag));
            magTv.setTextSize(48);
        } else {
            magTv.setText(String.format(java.util.Locale.US, "M %.1f", mag));
        }

        if (mag >= 7.0 && !isHurricane) {
            title.setText("⚠️ SISMO MAYOR");
            title.setTextColor(0xFFFF3B3B);
            magTv.setTextColor(0xFFFF3B3B);
        } else if (mag >= 6.0 && !isHurricane) {
            title.setText("⚠️ SISMO FUERTE");
            title.setTextColor(0xFFFF6B6B);
        }

        if (dir != null && !dir.isEmpty()) {
            distTv.setText(String.format(Locale.US, "a %d km %s de tu ubicación",
                    Math.round(dist), dir));
        } else {
            distTv.setText(String.format(Locale.US, "a %d km de tu ubicación", Math.round(dist)));
        }

        long agoMin = Math.max(1, (System.currentTimeMillis() - time) / 60000L);
        String timeStr = agoMin < 90 ? ("hace " + agoMin + " min") : formatTime(time);
        if (place != null && !place.isEmpty()) {
            placeTv.setText(place + " · " + timeStr);
        } else {
            placeTv.setText("Sismo reciente · " + timeStr);
        }

        if (sim) {
            simTv.setVisibility(View.VISIBLE);
            title.setText("SIMULACRO: " + alertTitle);
            title.setTextColor(0xFFFFD700);
            magTv.setTextColor(0xFFFFD700);
            subtitle.setText("Esto es una prueba. No hay " + (isHurricane ? "huracan" : isTsunami ? "tsunami" : isFlood ? "inundacion" : "sismo") + " real.");
        }

        sourceTv.setText(sourceText);

        // Cuenta regresiva de la onda S (solo sismos reales/simulacro con ETA)
        final long etaMs = getIntent().getLongExtra(EXTRA_ETA, 0L);
        if (etaMs > 0 && !isHurricane && !isTsunami && !isFlood) {
            final TextView sub = subtitle;
            final boolean simulated = sim;
            final Handler h = new Handler(Looper.getMainLooper());
            h.post(new Runnable() {
                @Override public void run() {
                    if (isFinishing() || isDestroyed()) return;
                    long left = Math.round((etaMs - System.currentTimeMillis()) / 1000.0);
                    if (left > 0) {
                        sub.setText((simulated ? "Simulacro · " : "") + "Onda S en ~" + left + " s — agáchate, cúbrete y sujétate");
                        h.postDelayed(this, 1000);
                    } else {
                        sub.setText((simulated ? "Simulacro · " : "") + "La onda sísmica ya debería sentirse — protégete y espera réplicas");
                    }
                }
            });
        }

        safeBtn.setOnClickListener(v -> finish());

        // Auto-cerrar después de 5 min si el usuario no interactúa
        new Handler(Looper.getMainLooper()).postDelayed(this::finish, 5 * 60 * 1000L);
    }

    /** singleTask: una alerta nueva mientras hay otra en pantalla debe reemplazarla. */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        recreate();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (wakeLock != null && wakeLock.isHeld()) {
            try { wakeLock.release(); } catch (Exception ignored) {}
        }
    }

    private String formatTime(long ts) {
        java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("HH:mm", Locale.getDefault());
        return sdf.format(new java.util.Date(ts));
    }
}
