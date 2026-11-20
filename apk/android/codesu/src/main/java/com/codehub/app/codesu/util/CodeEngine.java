package com.codehub.app.codesu.util;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.os.BatteryManager;
import android.os.Build;
import android.os.SystemClock;

import androidx.core.app.NotificationCompat;

import com.codehub.app.codesu.App;
import com.codehub.app.codesu.MainActivity;
import com.codehub.app.codesu.R;
import com.codehub.app.codesu.data.Killer;
import com.codehub.app.codesu.model.AppInfo;

import java.util.Iterator;
import java.util.List;

/**
 * Dueño único de los perfiles de CodeEngine.
 *
 * <p>Cinco perfiles: ultra ahorro, ahorro, adaptable, equilibrado y rendimiento.
 * Los ajustes del sistema se escriben con Shizuku y se restauran al salir del
 * perfil. La detección (batería crítica / juego en primer plano) aplica un perfil
 * temporal y vuelve al perfil base cuando el estado desaparece. Nunca toca
 * datos, sesiones, paquetes protegidos, CPU ni GPU.</p>
 */
public final class CodeEngine {

    public static final String PREFS = "settings";
    public static final String PREF_PROFILE = "codeEngineProfile";
    public static final String PREF_AUTO = "codeEngineAuto";
    public static final String PREF_ALLOW_DOZE = "codeEngineAllowDoze";
    public static final String PREF_BATTERY_DETECTION = "powerBatterySaver";
    public static final String PREF_GAME_DETECTION = "powerGameMode";
    public static final String PREF_OVERRIDE = "codeEngineOverride";
    public static final String PREF_OVERRIDE_REASON = "codeEngineOverrideReason";
    public static final String PREF_OVERRIDE_KEEP = "codeEngineOverrideKeep";
    public static final String PREF_APPLIED = "codeEngineApplied";
    public static final String PREF_LAST_REASON = "codeEngineLastReason";
    public static final String PREF_LAST_RESULT = "codeEngineLastResult";
    public static final String PREF_LAST_AT = "codeEngineLastAt";
    public static final String PREF_LAST_BATTERY_AT = "codeEngineLastBatteryAt";
    public static final String PREF_ORIGINAL_ANIM = "codeEngineOriginalAnim";
    public static final String PREF_ORIGINAL_BRIGHTNESS = "codeEngineOriginalBrightness";
    public static final String PREF_ORIGINAL_BRIGHTNESS_MODE = "codeEngineOriginalBrightnessMode";
    public static final String PREF_DOZE_APPLIED = "codeEngineDozeApplied";

    public static final String PROFILE_ULTRA = "ultra_saver";
    public static final String PROFILE_SAVER = "saver";
    public static final String PROFILE_ADAPTIVE = "adaptive";
    public static final String PROFILE_BALANCED = "balanced";
    public static final String PROFILE_PERFORMANCE = "performance";

    public static final String REASON_BATTERY = "battery";
    public static final String REASON_GAME = "game";

    private static final String CHANNEL_ID = "codesu_code_engine";
    private static final int NOTIF_ID = 1104;
    private static final int CRITICAL_BATTERY = 5;
    private static final int ADAPTIVE_LOW_BATTERY = 20;
    private static final long BATTERY_COOLDOWN_MS = 3L * 60L * 1000L;
    private static final String[] ANIMATION_KEYS = {
            "window_animation_scale", "transition_animation_scale", "animator_duration_scale"
    };

    private CodeEngine() {}

    public static String[] profiles() {
        return new String[]{PROFILE_ULTRA, PROFILE_SAVER, PROFILE_ADAPTIVE,
                PROFILE_BALANCED, PROFILE_PERFORMANCE};
    }

    public static boolean isValidProfile(String profile) {
        for (String candidate : profiles())
            if (candidate.equals(profile)) return true;
        return false;
    }

    public static String label(String profile) {
        if (PROFILE_ULTRA.equals(profile)) return "Ultra ahorro";
        if (PROFILE_SAVER.equals(profile)) return "Ahorro de batería";
        if (PROFILE_ADAPTIVE.equals(profile)) return "Batería adaptable";
        if (PROFILE_PERFORMANCE.equals(profile)) return "Rendimiento · modo juego";
        return "Equilibrado";
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static String baseProfile(Context context) {
        String value = context == null ? null
                : prefs(context).getString(PREF_PROFILE, PROFILE_BALANCED);
        return isValidProfile(value) ? value : PROFILE_BALANCED;
    }

    /** Perfil realmente aplicado: el temporal manda mientras siga activo. */
    public static String currentProfile(Context context) {
        if (context == null) return PROFILE_BALANCED;
        SharedPreferences prefs = prefs(context);
        String override = prefs.getString(PREF_OVERRIDE, "");
        return isValidProfile(override) ? override : baseProfile(context);
    }

    public static boolean isAuto(Context context) {
        return context != null && prefs(context).getBoolean(PREF_AUTO, true);
    }

    public static boolean isDozeAllowed(Context context) {
        return context != null && prefs(context).getBoolean(PREF_ALLOW_DOZE, false);
    }

    public static boolean isBatteryDetectionEnabled(Context context) {
        return context != null && prefs(context).getBoolean(PREF_BATTERY_DETECTION, true);
    }

    public static boolean isGameDetectionEnabled(Context context) {
        return context != null && prefs(context).getBoolean(PREF_GAME_DETECTION, true);
    }

    public static String lastResult(Context context) {
        return context == null ? "" : prefs(context).getString(PREF_LAST_RESULT, "");
    }

    public static long lastAppliedAt(Context context) {
        return context == null ? 0L : prefs(context).getLong(PREF_LAST_AT, 0L);
    }

    public static String overrideReason(Context context) {
        return context == null ? "" : prefs(context).getString(PREF_OVERRIDE_REASON, "");
    }

    /** Elección manual: fija el perfil base, descarta cualquier temporal y lo aplica. */
    public static void setBaseProfile(Context context, String profile) {
        if (context == null || !isValidProfile(profile)) return;
        prefs(context).edit().putString(PREF_PROFILE, profile)
                .putString(PREF_OVERRIDE, "").putString(PREF_OVERRIDE_REASON, "")
                .putString(PREF_OVERRIDE_KEEP, "").apply();
        apply(context, profile, "perfil elegido", null);
    }

    public static void setAuto(Context context, boolean enabled) {
        if (context == null) return;
        prefs(context).edit().putBoolean(PREF_AUTO, enabled).apply();
        if (!enabled) clearOverride(context, null);
    }

    public static void setBatteryDetectionEnabled(Context context, boolean enabled) {
        if (context == null) return;
        prefs(context).edit().putBoolean(PREF_BATTERY_DETECTION, enabled).apply();
        if (!enabled) clearOverride(context, REASON_BATTERY);
    }

    public static void setGameDetectionEnabled(Context context, boolean enabled) {
        if (context == null) return;
        prefs(context).edit().putBoolean(PREF_GAME_DETECTION, enabled).apply();
        if (!enabled) clearOverride(context, REASON_GAME);
    }

    public static void setDozeAllowed(Context context, boolean enabled) {
        if (context == null) return;
        SharedPreferences prefs = prefs(context);
        prefs.edit().putBoolean(PREF_ALLOW_DOZE, enabled).apply();
        if (!enabled && prefs.getBoolean(PREF_DOZE_APPLIED, false)) {
            String result = ShizukuPlusUtils.forceDeviceIdle(false);
            if (result.isEmpty()) prefs.edit().putBoolean(PREF_DOZE_APPLIED, false).apply();
        }
    }

    /** Batería al 5% sin cargador: ultra ahorro; al cargar o subir de 10%, perfil anterior. */
    public static void onBattery(Context context, int level, boolean charging) {
        if (!isAuto(context) || !isBatteryDetectionEnabled(context)) return;
        if (!charging && level >= 0 && level <= CRITICAL_BATTERY) {
            long now = SystemClock.elapsedRealtime();
            SharedPreferences prefs = prefs(context);
            long last = prefs.getLong(PREF_LAST_BATTERY_AT, 0L);
            if (REASON_BATTERY.equals(overrideReason(context))
                    && PROFILE_ULTRA.equals(prefs.getString(PREF_OVERRIDE, ""))) return;
            if (last > 0L && now - last < BATTERY_COOLDOWN_MS) return;
            prefs.edit().putLong(PREF_LAST_BATTERY_AT, now).apply();
            override(context, PROFILE_ULTRA, REASON_BATTERY, null, "batería al " + level + "%");
        } else if (charging || level >= 10) {
            clearOverride(context, REASON_BATTERY);
        }
    }

    /** Juego en primer plano: rendimiento; al salir, perfil anterior. */
    public static void onForegroundApp(Context context, String packageName) {
        if (!isAuto(context) || !isGameDetectionEnabled(context)) return;
        if (REASON_BATTERY.equals(overrideReason(context))) return;
        boolean game = isGameApp(context, packageName);
        if (game) override(context, PROFILE_PERFORMANCE, REASON_GAME,
                packageName, "juego en primer plano");
        else clearOverride(context, REASON_GAME);
    }

    public static boolean killBackgroundApps(Context context, String keepPackage, int level) {
        if (context == null) return false;
        try {
            List<AppInfo> apps = AppListHelper.getFilteredAppsList(context, true);
            if (keepPackage != null && !keepPackage.isEmpty()) {
                Iterator<AppInfo> iterator = apps.iterator();
                while (iterator.hasNext())
                    if (keepPackage.equals(iterator.next().getPkgName())) iterator.remove();
            }
            return !apps.isEmpty() && Killer.killListOfApps(apps, level);
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    /** Aplica un perfil de forma asíncrona; los resultados nunca bloquean la UI. */
    public static void apply(Context context, String profile, String reason, String keepPackage) {
        if (context == null || !isValidProfile(profile)) return;
        final Context app = context.getApplicationContext();
        final SharedPreferences prefs = prefs(app);
        prefs.edit().putString(PREF_APPLIED, profile)
                .putString(PREF_LAST_REASON, reason == null ? "" : reason).apply();
        Runnable task = () -> {
            StringBuilder log = new StringBuilder();
            boolean shizuku = ShizukuPlusUtils.checkShizuku();
            boolean adaptiveLow = PROFILE_ADAPTIVE.equals(profile) && isLowBattery(app);
            boolean tune = !PROFILE_BALANCED.equals(profile);
            if (tune && shizuku) {
                ensureOriginals(prefs);
                if (PROFILE_PERFORMANCE.equals(profile)) {
                    log.append(restoreBrightness(prefs));
                    log.append(applyAnimations("0.5"));
                } else if (PROFILE_ADAPTIVE.equals(profile) && !adaptiveLow) {
                    log.append(restoreSettings(prefs));
                } else {
                    log.append(applySaverSettings(profile));
                }
            } else if (!tune) {
                log.append(restoreSettings(prefs));
            } else {
                log.append("Shizuku inactivo: ajustes del sistema sin cambios. ");
            }
            log.append(applyDoze(app, prefs, profile, shizuku));
            boolean killApps = PROFILE_ULTRA.equals(profile) || PROFILE_SAVER.equals(profile)
                    || PROFILE_PERFORMANCE.equals(profile) || adaptiveLow;
            if (killApps) {
                int level = PROFILE_PERFORMANCE.equals(profile) ? 1 : 0;
                String foreground = (keepPackage == null || keepPackage.isEmpty())
                        ? foregroundPackage(app) : keepPackage;
                boolean killed = killBackgroundApps(app, foreground, level);
                log.append(killed ? "Apps en segundo plano cerradas. "
                        : "Sin apps cerradas: revisa Shizuku/root. ");
            }
            String result = log.toString().trim();
            prefs.edit().putString(PREF_LAST_RESULT, result)
                    .putLong(PREF_LAST_AT, System.currentTimeMillis()).apply();
            if (App.handler != null) App.handler.post(() -> notifyProfile(app, profile, result));
        };
        if (App.executorService != null) App.executorService.execute(task);
        else new Thread(task, "codeengine-apply").start();
    }

    private static void override(Context context, String profile, String reason,
                                 String keepPackage, String detail) {
        SharedPreferences prefs = prefs(context);
        if (profile.equals(prefs.getString(PREF_OVERRIDE, ""))
                && reason.equals(prefs.getString(PREF_OVERRIDE_REASON, ""))) return;
        prefs.edit().putString(PREF_OVERRIDE, profile).putString(PREF_OVERRIDE_REASON, reason)
                .putString(PREF_OVERRIDE_KEEP, keepPackage == null ? "" : keepPackage).apply();
        apply(context, profile, detail, keepPackage);
    }

    private static void clearOverride(Context context, String reason) {
        SharedPreferences prefs = prefs(context);
        if (prefs.getString(PREF_OVERRIDE, "").isEmpty()) return;
        String active = prefs.getString(PREF_OVERRIDE_REASON, "");
        if (reason != null && !reason.equals(active)) return;
        prefs.edit().putString(PREF_OVERRIDE, "").putString(PREF_OVERRIDE_REASON, "")
                .putString(PREF_OVERRIDE_KEEP, "").apply();
        apply(context, baseProfile(context), "uso normal", null);
    }

    private static void ensureOriginals(SharedPreferences prefs) {
        if (prefs.contains(PREF_ORIGINAL_ANIM)) return;
        StringBuilder anim = new StringBuilder();
        for (String key : ANIMATION_KEYS) {
            if (anim.length() > 0) anim.append(',');
            anim.append(numericOr(ShizukuPlusUtils.getSystemSetting("global", key), "1.0"));
        }
        String brightness = numericOr(ShizukuPlusUtils.getSystemSetting("system", "screen_brightness"), "128");
        String mode = numericOr(ShizukuPlusUtils.getSystemSetting("system", "screen_brightness_mode"), "0");
        prefs.edit().putString(PREF_ORIGINAL_ANIM, anim.toString())
                .putString(PREF_ORIGINAL_BRIGHTNESS, brightness)
                .putString(PREF_ORIGINAL_BRIGHTNESS_MODE, mode).apply();
    }

    private static String applyAnimations(String scale) {
        StringBuilder errors = new StringBuilder();
        for (String key : ANIMATION_KEYS) {
            String result = ShizukuPlusUtils.setSystemSetting("global", key, scale);
            if (!result.isEmpty()) errors.append(key).append(": ").append(result).append(". ");
        }
        return errors.length() == 0 ? "Animaciones ajustadas. " : errors.toString();
    }

    private static String applySaverSettings(String profile) {
        String scale = PROFILE_ULTRA.equals(profile) ? "0.0" : "0.5";
        String brightness = PROFILE_ULTRA.equals(profile) ? "30" : "55";
        StringBuilder errors = new StringBuilder();
        errors.append(applyAnimations(scale));
        String mode = ShizukuPlusUtils.setSystemSetting("system", "screen_brightness_mode", "0");
        if (!mode.isEmpty()) errors.append("brillo automático: ").append(mode).append(". ");
        String result = ShizukuPlusUtils.setSystemSetting("system", "screen_brightness", brightness);
        if (!result.isEmpty()) errors.append("brillo: ").append(result).append(". ");
        return errors.length() == 0 ? "Ajustes de ahorro aplicados. " : errors.toString();
    }

    private static String restoreBrightness(SharedPreferences prefs) {
        StringBuilder errors = new StringBuilder();
        String mode = numericOr(prefs.getString(PREF_ORIGINAL_BRIGHTNESS_MODE, ""), "0");
        String modeResult = ShizukuPlusUtils.setSystemSetting("system", "screen_brightness_mode", mode);
        if (!modeResult.isEmpty()) errors.append("brillo automático: ").append(modeResult).append(". ");
        String brightness = numericOr(prefs.getString(PREF_ORIGINAL_BRIGHTNESS, ""), "128");
        String brightnessResult = ShizukuPlusUtils.setSystemSetting("system", "screen_brightness", brightness);
        if (!brightnessResult.isEmpty()) errors.append("brillo: ").append(brightnessResult).append(". ");
        return errors.length() == 0 ? "Brillo restaurado. " : errors.toString();
    }

    private static boolean isLowBattery(Context context) {
        try {
            BatteryManager manager = (BatteryManager) context.getSystemService(Context.BATTERY_SERVICE);
            if (manager == null || manager.isCharging()) return false;
            int level = manager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
            return level > 0 && level <= ADAPTIVE_LOW_BATTERY;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private static String restoreSettings(SharedPreferences prefs) {
        if (!prefs.contains(PREF_ORIGINAL_ANIM)) return "Ajustes sin cambios. ";
        String[] originals = prefs.getString(PREF_ORIGINAL_ANIM, "").split(",");
        StringBuilder errors = new StringBuilder();
        for (int i = 0; i < ANIMATION_KEYS.length; i++) {
            String value = i < originals.length ? numericOr(originals[i], "1.0") : "1.0";
            String result = ShizukuPlusUtils.setSystemSetting("global", ANIMATION_KEYS[i], value);
            if (!result.isEmpty()) errors.append(ANIMATION_KEYS[i]).append(": ").append(result).append(". ");
        }
        String mode = numericOr(prefs.getString(PREF_ORIGINAL_BRIGHTNESS_MODE, ""), "0");
        String modeResult = ShizukuPlusUtils.setSystemSetting("system", "screen_brightness_mode", mode);
        if (!modeResult.isEmpty()) errors.append("brillo automático: ").append(modeResult).append(". ");
        String brightness = numericOr(prefs.getString(PREF_ORIGINAL_BRIGHTNESS, ""), "128");
        String brightnessResult = ShizukuPlusUtils.setSystemSetting("system", "screen_brightness", brightness);
        if (!brightnessResult.isEmpty()) errors.append("brillo: ").append(brightnessResult).append(". ");
        if (errors.length() == 0) {
            prefs.edit().remove(PREF_ORIGINAL_ANIM).remove(PREF_ORIGINAL_BRIGHTNESS)
                    .remove(PREF_ORIGINAL_BRIGHTNESS_MODE).apply();
            return "Ajustes restaurados. ";
        }
        return errors.toString();
    }

    private static String applyDoze(Context context, SharedPreferences prefs,
                                    String profile, boolean shizuku) {
        boolean wanted = PROFILE_ULTRA.equals(profile) && isDozeAllowed(context) && shizuku;
        boolean applied = prefs.getBoolean(PREF_DOZE_APPLIED, false);
        if (wanted && !applied) {
            String result = ShizukuPlusUtils.forceDeviceIdle(true);
            if (result.isEmpty()) {
                prefs.edit().putBoolean(PREF_DOZE_APPLIED, true).apply();
                return "Doze forzado. ";
            }
            return "Doze: " + result + ". ";
        }
        if (!wanted && applied) {
            String result = ShizukuPlusUtils.forceDeviceIdle(false);
            if (result.isEmpty()) {
                prefs.edit().putBoolean(PREF_DOZE_APPLIED, false).apply();
                return "Doze restaurado. ";
            }
            return "Doze: " + result + ". ";
        }
        return "";
    }

    private static String valueOr(String value, String fallback) {
        if (value == null) return fallback;
        String clean = value.trim();
        return clean.isEmpty() || "null".equalsIgnoreCase(clean) ? fallback : clean;
    }

    /** Descarta mensajes de error de Shizuku antes de guardar un valor original. */
    private static String numericOr(String value, String fallback) {
        String clean = valueOr(value, fallback);
        return clean.matches("[0-9.]+") ? clean : fallback;
    }

    private static boolean isGameApp(Context context, String packageName) {
        if (packageName == null || packageName.isEmpty()
                || Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false;
        try {
            ApplicationInfo info = context.getPackageManager().getApplicationInfo(packageName, 0);
            return info.category == ApplicationInfo.CATEGORY_GAME;
        } catch (Exception ignored) {
            return false;
        }
    }

    /** App en primer plano según UsageStats; null si no hay acceso concedido. */
    public static String foregroundPackage(Context context) {
        if (context == null || !CacheCleaner.hasUsageAccess(context)) return null;
        try {
            UsageStatsManager manager = (UsageStatsManager)
                    context.getSystemService(Context.USAGE_STATS_SERVICE);
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

    /** Detecta el juego en primer plano solo si hay acceso al uso concedido. */
    public static String foregroundGamePackage(Context context) {
        String pkg = foregroundPackage(context);
        return isGameApp(context, pkg) ? pkg : null;
    }

    private static void notifyProfile(Context context, String profile, String result) {
        try {
            NotificationManager manager = (NotificationManager)
                    context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (manager == null) return;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                    && manager.getNotificationChannel(CHANNEL_ID) == null) {
                NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                        "CodeEngine · perfiles de rendimiento", NotificationManager.IMPORTANCE_LOW);
                channel.setDescription("Cambios de perfil y ajustes restaurados por CodeEngine");
                manager.createNotificationChannel(channel);
            }
            Intent open = new Intent(context, MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP
                            | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            PendingIntent pending = PendingIntent.getActivity(context, NOTIF_ID, open,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            Notification notification = new NotificationCompat.Builder(context, CHANNEL_ID)
                    .setContentTitle("CodeEngine · " + label(profile))
                    .setContentText(result.isEmpty() ? "Perfil aplicado" : result)
                    .setStyle(new NotificationCompat.BigTextStyle().bigText(result))
                    .setSmallIcon(R.drawable.ic_snow)
                    .setContentIntent(pending)
                    .setAutoCancel(true)
                    .setOngoing(false)
                    .build();
            manager.notify(NOTIF_ID, notification);
        } catch (RuntimeException ignored) {
            // Sin permiso de notificaciones los perfiles siguen aplicándose.
        }
    }
}
