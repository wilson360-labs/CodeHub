package com.codehub.app.codesu.util;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;
import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;

import com.codehub.app.codesu.App;
import com.codehub.app.codesu.MainActivity;
import com.codehub.app.codesu.R;
import com.codehub.app.codesu.data.Killer;
import com.codehub.app.codesu.model.AppInfo;
import com.codehub.app.codesu.worker.PowerCheckWorker;

import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Modos de uso cotidiano de CodeSu:
 *
 * <ul>
 *   <li><b>Normal</b>: no hace nada automáticamente.</li>
 *   <li><b>Ahorro</b>: con la batería al 5% y sin cargador, cierra apps seguras
 *       (KillMyApps) y sugiere conectar el cable para que la próxima tarea vaya rápido.</li>
 *   <li><b>Rendimiento / juego</b>: al detectar una app categoría juego en primer plano,
 *       libera el resto de apps en segundo plano una sola vez por sesión.</li>
 * </ul>
 *
 * <p>Todos los cierres pasan por {@link Killer}, que ya excluye CodeHub, Shizuku,
 * la shell y los providers de WebView. Nunca se cierra la app en primer plano.</p>
 */
public final class PowerGuard {

    public static final String PREF_BATTERY_SAVER = "powerBatterySaver";
    public static final String PREF_GAME_MODE = "powerGameMode";
    public static final String PREF_MODE = "powerMode";
    public static final String MODE_NORMAL = "normal";
    public static final String MODE_SAVER = "saver";
    public static final String MODE_GAME = "game";

    public static final String ACTION_CHECK_NOW = "com.codehub.app.codesu.action.POWER_CHECK_NOW";
    public static final String ACTION_RUN_KILL = "com.codehub.app.codesu.action.POWER_RUN_KILL";

    private static final String CHANNEL_ID = "codesu_power_modes";
    private static final String WORK_NAME = "codesu_power_check";
    private static final int NOTIF_SAVER = 1102;
    private static final int NOTIF_GAME = 1103;
    private static final int CRITICAL_BATTERY = 5;
    private static final long ACTION_COOLDOWN_MS = 3L * 60L * 1000L;
    private static final long GAME_POLL_MS = 6_000L;

    private static final Handler HANDLER = new Handler(Looper.getMainLooper());
    private static final Object LOCK = new Object();
    private static int clients;
    private static volatile boolean registered;
    private static volatile boolean saverActive;
    private static volatile boolean gameActive;
    private static volatile String foregroundPackage;

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
            try {
                evaluateGame(App.context);
            } catch (RuntimeException ignored) {
                // Una ROM puede devolver estadísticas incompletas: reintentamos en el próximo ciclo.
            }
            HANDLER.postDelayed(this, GAME_POLL_MS);
        }
    };

    private PowerGuard() {}

    public static boolean isBatterySaverEnabled() {
        return App.settings != null && App.settings.getBoolean(PREF_BATTERY_SAVER, true);
    }

    public static boolean isGameModeEnabled() {
        return App.settings != null && App.settings.getBoolean(PREF_GAME_MODE, true);
    }

    public static boolean isAnyModeEnabled() {
        return isBatterySaverEnabled() || isGameModeEnabled();
    }

    public static String currentMode(Context context) {
        if (context == null) return MODE_NORMAL;
        String mode = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
                .getString(PREF_MODE, MODE_NORMAL);
        return mode == null ? MODE_NORMAL : mode;
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
        if (isGameModeEnabled()) {
            try {
                evaluateGame(context.getApplicationContext());
            } catch (RuntimeException ignored) {
            }
        }
    }

    /** Evalúa batería con el Intent sticky o, si no hay, con BatteryManager directo. */
    public static void evaluateBattery(Context context, Intent batteryIntent) {
        if (context == null) return;
        int level;
        boolean charging;
        if (batteryIntent != null && batteryIntent.hasExtra(BatteryManager.EXTRA_LEVEL)) {
            int scale = batteryIntent.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
            level = scale <= 0 ? 0 : batteryIntent.getIntExtra(BatteryManager.EXTRA_LEVEL, 0) * 100 / scale;
            int plugged = batteryIntent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0);
            charging = plugged != 0;
        } else {
            BatteryManager manager = (BatteryManager) context.getSystemService(Context.BATTERY_SERVICE);
            level = manager == null ? -1 : manager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
            charging = manager != null && manager.isCharging();
        }
        if (level < 0) return;
        if (charging || level >= 10) {
            if (saverActive) clearSaver(context);
            return;
        }
        if (level <= CRITICAL_BATTERY) activateSaver(context, level);
    }

    private static void activateSaver(Context context, int level) {
        if (!isBatterySaverEnabled()) return;
        long now = System.currentTimeMillis();
        long last = App.settings == null ? 0L : App.settings.getLong("powerLastCriticalAt", 0L);
        if (saverActive || now - last < ACTION_COOLDOWN_MS) return;
        saverActive = true;
        if (App.settings != null)
            App.settings.edit().putLong("powerLastCriticalAt", now)
                    .putString(PREF_MODE, MODE_SAVER).apply();
        updateNotification(context, NOTIF_SAVER,
                String.format(Locale.getDefault(), "Ahorro de batería · %d%%", level),
                "Cerramos apps en segundo plano. Conecta el cargador para que KillMyApps y la limpieza de caché sean más rápidos.");
        killBackgroundApps(context, null, "ahorro de batería", ok -> {
            if (!ok) updateNotification(context, NOTIF_SAVER,
                    String.format(Locale.getDefault(), "Batería al %d%%", level),
                    "Conecta el cargador. Activa Shizuku o root en CodeSu para cerrar apps automáticamente.");
        });
    }

    private static void clearSaver(Context context) {
        saverActive = false;
        cancelNotification(context, NOTIF_SAVER);
        if (!gameActive) {
            if (App.settings != null) App.settings.edit().putString(PREF_MODE, MODE_NORMAL).apply();
            cancelNotification(context, NOTIF_GAME);
        }
    }

    private static void evaluateGame(Context context) {
        if (!isGameModeEnabled()) {
            if (gameActive) clearGame(context);
            return;
        }
        String pkg = foregroundPackage(context);
        boolean game = pkg != null && isGameApp(context, pkg);
        if (game && !gameActive) {
            gameActive = true;
            foregroundPackage = pkg;
            if (App.settings != null)
                App.settings.edit().putString(PREF_MODE, MODE_GAME).apply();
            updateNotification(context, NOTIF_GAME, "Modo juego · rendimiento activo",
                    "CodeSu liberó apps en segundo plano. Al salir del juego volvemos al modo normal.");
            killBackgroundApps(context, pkg, "modo juego", null);
        } else if (!game && gameActive) {
            clearGame(context);
        } else {
            foregroundPackage = pkg;
        }
    }

    private static void clearGame(Context context) {
        gameActive = false;
        foregroundPackage = null;
        cancelNotification(context, NOTIF_GAME);
        if (!saverActive && App.settings != null)
            App.settings.edit().putString(PREF_MODE, MODE_NORMAL).apply();
    }

    /** Cierra apps según la lista/engine que el usuario ya configuró en CodeSu. */
    public static void killBackgroundApps(Context context, String keepPackage, String reason,
                                          java.util.function.Consumer<Boolean> done) {
        Runnable task = () -> {
            boolean ok = false;
            try {
                List<AppInfo> apps = AppListHelper.getFilteredAppsList(context, true);
                if (keepPackage != null && !keepPackage.isEmpty()) {
                    Iterator<AppInfo> iterator = apps.iterator();
                    while (iterator.hasNext()) {
                        if (keepPackage.equals(iterator.next().getPkgName())) iterator.remove();
                    }
                }
                ok = !apps.isEmpty() && Killer.killListOfApps(apps);
            } catch (RuntimeException ignored) {
                // Sin Shizuku/root no se puede cerrar: el aviso lo indica en la notificación.
            } finally {
                final boolean result = ok;
                HANDLER.post(() -> {
                    if (done != null) done.accept(result);
                });
            }
        };
        if (App.executorService != null)
            App.executorService.execute(task);
        else
            new Thread(task, "codesu-power-kill").start();
    }

    private static String foregroundPackage(Context context) {
        if (!CacheCleaner.hasUsageAccess(context)) return null;
        try {
            UsageStatsManager manager = (UsageStatsManager) context.getSystemService(Context.USAGE_STATS_SERVICE);
            if (manager == null) return null;
            long now = System.currentTimeMillis();
            List<UsageStats> stats = manager.queryUsageStats(
                    UsageStatsManager.INTERVAL_BEST, now - 12_000L, now);
            if (stats == null) return null;
            String best = null;
            long last = 0L;
            for (UsageStats stat : stats) {
                if (stat.getLastTimeUsed() > last) {
                    last = stat.getLastTimeUsed();
                    best = stat.getPackageName();
                }
            }
            return best;
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static boolean isGameApp(Context context, String packageName) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false;
        try {
            ApplicationInfo info = context.getPackageManager().getApplicationInfo(packageName, 0);
            return info.category == ApplicationInfo.CATEGORY_GAME;
        } catch (PackageManager.NameNotFoundException | RuntimeException ignored) {
            return false;
        }
    }

    private static void createChannel(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null) return;
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                "CodeSu · modos de uso", NotificationManager.IMPORTANCE_HIGH);
        channel.setDescription("Ahorro de batería al 5% y modo juego / rendimiento");
        manager.createNotificationChannel(channel);
    }

    private static void updateNotification(Context context, int id, String title, String text) {
        try {
            createChannel(context);
            Intent open = new Intent(context, MainActivity.class);
            open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP
                    | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            PendingIntent pending = PendingIntent.getActivity(context, id, open,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            Notification notification = new NotificationCompat.Builder(context, CHANNEL_ID)
                    .setContentTitle(title)
                    .setContentText(text)
                    .setStyle(new NotificationCompat.BigTextStyle().bigText(text))
                    .setSmallIcon(R.drawable.ic_snow)
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setCategory(NotificationCompat.CATEGORY_STATUS)
                    .setContentIntent(pending)
                    .setAutoCancel(true)
                    .build();
            NotificationManager manager = (NotificationManager)
                    context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (manager != null) manager.notify(id, notification);
        } catch (RuntimeException ignored) {
            // Sin permiso de notificaciones los modos siguen ejecutándose.
        }
    }

    private static void cancelNotification(Context context, int id) {
        try {
            NotificationManager manager = (NotificationManager)
                    context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (manager != null) manager.cancel(id);
        } catch (RuntimeException ignored) {
        }
    }
}
