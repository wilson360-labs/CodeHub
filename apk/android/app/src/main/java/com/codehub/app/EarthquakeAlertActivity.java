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

    private PowerManager.WakeLock wakeLock;

    public static void launch(Context ctx, float mag, double distKm, String dir,
                              String place, long timeMs, boolean isSim) {
        Intent i = new Intent(ctx, EarthquakeAlertActivity.class);
        i.putExtra(EXTRA_MAG, mag);
        i.putExtra(EXTRA_DIST, distKm);
        i.putExtra(EXTRA_DIR, dir);
        i.putExtra(EXTRA_PLACE, place);
        i.putExtra(EXTRA_TIME, timeMs);
        i.putExtra(EXTRA_SIM, isSim);
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

        TextView title = findViewById(R.id.quake_alert_title);
        TextView subtitle = findViewById(R.id.quake_alert_subtitle);
        TextView magTv = findViewById(R.id.quake_alert_mag);
        TextView distTv = findViewById(R.id.quake_alert_distance);
        TextView placeTv = findViewById(R.id.quake_alert_place);
        TextView simTv = findViewById(R.id.quake_alert_sim);
        TextView safeBtn = findViewById(R.id.quake_alert_safe);
        TextView sourceTv = findViewById(R.id.quake_alert_source);

        magTv.setText(String.format(Locale.US, "M %.1f", mag));

        if (mag >= 7.0) {
            title.setText("⚠️ SISMO MAYOR");
            title.setTextColor(0xFFFF3B3B);
            magTv.setTextColor(0xFFFF3B3B);
        } else if (mag >= 6.0) {
            title.setText("⚠️ SISMO FUERTE");
            title.setTextColor(0xFFFF6B6B);
        } else {
            title.setText("⚠️ ALERTA DE SISMO");
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
            title.setText("🧪 SIMULACRO DE SISMO");
            title.setTextColor(0xFFFFD700);
            magTv.setTextColor(0xFFFFD700);
            subtitle.setText("Esto es una prueba. No hay sismo real.");
        }

        sourceTv.setText(sim ? "Simulacro · CodeHub" : "Fuente: USGS · CodeHub");

        safeBtn.setOnClickListener(v -> finish());

        // Auto-cerrar después de 5 min si el usuario no interactúa
        new Handler(Looper.getMainLooper()).postDelayed(this::finish, 5 * 60 * 1000L);
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
