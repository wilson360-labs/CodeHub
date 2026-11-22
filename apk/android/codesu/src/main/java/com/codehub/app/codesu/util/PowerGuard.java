package com.codehub.app.codesu.util;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import androidx.core.content.ContextCompat;
import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;

import com.codehub.app.codesu.App;
import com.codehub.app.codesu.model.AppInfo;
import com.codehub.app.codesu.worker.PowerCheckWorker;

import java.util.Iterator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Puente dejado por compatibilidad: decide cuándo evaluar batería y primer plano,
 * pero los perfiles y sus reglas viven en {@link CodeEngine}.
 *
 * <p>Los cierres siguen pasando por la lista filtrada de CodeSu (KillMyApps),
 * que excluye CodeHub, Shizuku, la shell, los providers de WebView y la propia
 * app en primer plano cuando se pasa su paquete.</p>
 */
public final class PowerGuard {

    public static final String PREF_BATTERY_SAVER = CodeEngine.PREF_BATTERY_DETECTION;
    public static final String PREF_GAME_MODE = CodeEngine.PREF_GAME_DETECTION;
    public static final String PREF_MODE = CodeEngine.PREF_PROFILE;
    public static final String MODE_NORMAL = CodeEngine.PROFILE_BALANCED;
    public static final String MODE_SAVER = CodeEngine.PROFILE_SAVER;
    public static final String MODE_GAME = CodeEngine.PROFILE_PERFORMANCE;

    public static final String ACTION_CHECK_NOW = "com.codehub.app.codesu.action.POWER_CHECK_NOW";
    public static final String ACTION_RUN_KILL = "com.codehub.app.codesu.action.POWER_RUN_KILL";

    private static final String WORK_NAME = "codesu_power_check";
    private static final long GAME_POLL_MS = 6_000L;
    /** UsageStats puede ir con retraso en Android 16: no sueltes el juego a la primera lectura. */
    private static final long GAME_GRACE_MS = 45_000L;

    private static final Handler HANDLER = new Handler(Looper.getMainLooper());
    private static final Object LOCK = new Object();
    private static final AtomicBoolean GAME_CHECK_IN_FLIGHT = new AtomicBoolean();
    private static int clients;
    private static volatile boolean registered;
    private static volatile String lastGamePackage;
    private static volatile long lastGameSeenAt;

    private static final BroadcastReceiver BATTERY_RECEIVER = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null) return;
            String action = intent.getAction();
            if (Intent.ACTION_BATTERY_CHANGED.equals(action)
                    || Intent.ACTION_BATTERY_LOW.equals(action)
                    || ACTION_CHECK_NOW.equals(action)) {
                evaluateBattery(context, intent);
            }
        }
    };

    private static final Runnable GAME_POLL = new Runnable() {
        @Override
        public void run() {
            if (!registered || App.context == null) return;
            if (GAME_CHECK_IN_FLIGHT.compareAndSet(false, true)) {
                Runnable check = () -> {
                    try {
                        evaluateGame(App.context);
                    } catch (RuntimeException ignored) {
                        // Una ROM puede devolver estadísticas incompletas: reintentamos luego.
                    } finally {
                        GAME_CHECK_IN_FLIGHT.set(false);
                    }
                };
                if (App.executorService != null) App.executorService.execute(check);
                else new Thread(check, "codesu-game-poll").start();
            }
            HANDLER.postDelayed(this, GAME_POLL_MS);
        }
    };

    private PowerGuard() {}

    public static boolean isBatterySaverEnabled() {
        return App.context != null && CodeEngine.isBatteryDetectionEnabled(App.context);
    }

    public static boolean isGameModeEnabled() {
        return App.context != null && CodeEngine.isGameDetectionEnabled(App.context);
    }

    public static boolean isAnyModeEnabled() {
        return isBatterySaverEnabled() || isGameModeEnabled();
    }

    public static String currentMode(Context context) {
        return CodeEngine.currentProfile(context);
    }

    /** Programa una comprobación de batería incluso con la app cerrada. */
    public static void initialize(Context context) {
        if (context == null) return;
        try {
            PeriodicWorkRequest request = new PeriodicWorkRequest.Builder(
                    PowerCheckWorker.class, 15L, TimeUnit.MINUTES)
                    .setConstraints(new Constraints.Builder().build())
                    .build();
            WorkManager.getInstance(context.getApplicationContext()).enqueueUniquePeriodicWork(
                    WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request);
        } catch (RuntimeException ignored) {
            // WorkManager puede no estar disponible en builds exóticos: el receiver sigue activo.
        }
        evaluateBattery(context.getApplicationContext(), null);
    }

    /** Registra el monitor en primer plano; varias pantallas/servicios pueden compartirlo. */
    public static void start(Context context) {
        if (context == null) return;
        synchronized (LOCK) {
            clients++;
            if (registered) return;
            IntentFilter filter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
            filter.addAction(Intent.ACTION_BATTERY_LOW);
            filter.addAction(ACTION_CHECK_NOW);
            try {
                ContextCompat.registerReceiver(context.getApplicationContext(), BATTERY_RECEIVER,
                        filter, ContextCompat.RECEIVER_NOT_EXPORTED);
                registered = true;
            } catch (RuntimeException ignored) {
                registered = false;
            }
            HANDLER.removeCallbacks(GAME_POLL);
            HANDLER.post(GAME_POLL);
        }
        evaluateBattery(context.getApplicationContext(), null);
    }

    public static void stop(Context context) {
        synchronized (LOCK) {
            clients = Math.max(0, clients - 1);
            if (clients > 0 || !registered) return;
            registered = false;
            HANDLER.removeCallbacks(GAME_POLL);
            if (context != null) {
                try {
                    context.getApplicationContext().unregisterReceiver(BATTERY_RECEIVER);
                } catch (IllegalArgumentException ignored) {
                    // Ya desregistrado por el sistema.
                }
            }
        }
    }

    /** Pasada completa (batería + juego) para el Worker y el receiver de arranque. */
    public static void evaluateNow(Context context) {
        if (context == null) return;
        evaluateBattery(context.getApplicationContext(), null);
        try {
            evaluateGame(context.getApplicationContext());
        } catch (RuntimeException ignored) {
        }
    }

    /** Evalúa batería con el Intent sticky o, si no hay, con BatteryManager directo. */
    public static void evaluateBattery(Context context, Intent batteryIntent) {
        if (context == null) return;
        int level;
        boolean charging;
        if (batteryIntent != null && batteryIntent.hasExtra(BatteryManager.EXTRA_LEVEL)) {
            int scale = batteryIntent.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
            level = scale <= 0 ? 0
                    : batteryIntent.getIntExtra(BatteryManager.EXTRA_LEVEL, 0) * 100 / scale;
            int plugged = batteryIntent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0);
            charging = plugged != 0;
        } else {
            BatteryManager manager = (BatteryManager) context.getSystemService(Context.BATTERY_SERVICE);
            level = manager == null ? -1 : manager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
            charging = manager != null && manager.isCharging();
        }
        if (level < 0) return;
        CodeEngine.onBattery(context.getApplicationContext(), level, charging);
    }

    /** El primer plano lo decide CodeEngine; aquí solo se detecta la app. */
    private static void evaluateGame(Context context) {
        if (context == null) return;
        String game = CodeEngine.foregroundGamePackage(context.getApplicationContext());
        long now = SystemClock.elapsedRealtime();
        if (game != null) {
            lastGamePackage = game;
            lastGameSeenAt = now;
        } else if (lastGamePackage != null && now - lastGameSeenAt < GAME_GRACE_MS) {
            // Mantén el perfil de juego aunque UsageStats omita una lectura.
            game = lastGamePackage;
        } else {
            lastGamePackage = null;
        }
        CodeEngine.onForegroundApp(context.getApplicationContext(), game);
    }

    /** Acción explícita del receiver; mantiene la preferencia de engine del usuario. */
    public static void killBackgroundApps(Context context, String keepPackage, String reason,
                                          java.util.function.Consumer<Boolean> done) {
        Runnable task = () -> {
            boolean ok = CodeEngine.killBackgroundApps(context, keepPackage, -1);
            HANDLER.post(() -> {
                if (done != null) done.accept(ok);
            });
        };
        if (App.executorService != null)
            App.executorService.execute(task);
        else
            new Thread(task, "codesu-power-kill").start();
    }
}
