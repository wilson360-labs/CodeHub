package com.codehub.app;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import androidx.work.Data;
import androidx.work.ExistingWorkPolicy;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;

import java.util.Calendar;
import java.util.concurrent.TimeUnit;

/**
 * Scheduling del Auto Killer de CodeSu:
 * - Al apagar pantalla: WorkManager one-time con retardo configurable.
 * - Diario: alarma RTC_WAKEUP del sistema (semántica KillMyApps); WorkManager
 *   se posponía con ahorro de batería y el run nunca llegaba a la hora elegida.
 */
public final class CodeSuAutoKillerScheduler {
    public static final int MODE_OFF = 0;
    public static final int MODE_SCREEN_OFF = 1;
    public static final int MODE_DAILY = 2;
    public static final String KEY_TRIGGER = "codesu_auto_trigger";
    public static final String TRIGGER_SCREEN = "screen";
    public static final String TRIGGER_DAILY = "daily";
    private static final String SCREEN_WORK = "codesu_auto_screen_off";
    private static final String PREFS = "codesu_preferences";
    private static final int DAILY_ALARM_REQUEST = 2002;

    private CodeSuAutoKillerScheduler() {}

    public static void configure(Context context, int mode, int hour, int minute) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt("auto_mode", mode)
            .putInt("auto_hour", hour)
            .putInt("auto_minute", minute)
            .apply();
        cancelScreenOff(context);
        if (mode == MODE_DAILY) scheduleDailyAlarm(context);
        else cancelDailyAlarm(context);
    }

    public static void onScreenOff(Context context) {
        android.content.SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (prefs.getInt("auto_mode", MODE_OFF) != MODE_SCREEN_OFF) return;
        int delay = prefs.getInt("screen_off_delay_minutes", 1);
        OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(CodeSuAutoKillerWorker.class)
            .setInputData(new Data.Builder().putString(KEY_TRIGGER, TRIGGER_SCREEN).build())
            .setInitialDelay(Math.max(0, delay), TimeUnit.MINUTES)
            .build();
        WorkManager.getInstance(context).enqueueUniqueWork(SCREEN_WORK, ExistingWorkPolicy.REPLACE, request);
    }

    public static void onScreenOn(Context context) {
        cancelScreenOff(context);
    }

    /** Alarma diaria RTC_WAKEUP: dispara incluso en Doze (exacta si el sistema lo permite). */
    public static void scheduleDailyAlarm(Context context) {
        android.content.SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (prefs.getInt("auto_mode", MODE_OFF) != MODE_DAILY) return;
        AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (alarmManager == null) return;
        Calendar now = Calendar.getInstance();
        Calendar next = (Calendar) now.clone();
        next.set(Calendar.HOUR_OF_DAY, prefs.getInt("auto_hour", 22));
        next.set(Calendar.MINUTE, prefs.getInt("auto_minute", 0));
        next.set(Calendar.SECOND, 0);
        next.set(Calendar.MILLISECOND, 0);
        if (!next.after(now)) next.add(Calendar.DAY_OF_YEAR, 1);
        PendingIntent pending = dailyPendingIntent(context);
        if (canScheduleExact(context)) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next.getTimeInMillis(), pending);
        } else {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next.getTimeInMillis(), pending);
        }
        prefs.edit().putLong("daily_alarm_next_at", next.getTimeInMillis()).apply();
    }

    /** Las alarmas se borran tras un reboot o una actualización del APK: rearmar si el modo diario sigue activo. */
    public static void rescheduleIfDue(Context context) {
        if (context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt("auto_mode", MODE_OFF) == MODE_DAILY) {
            scheduleDailyAlarm(context);
        }
    }

    public static void cancelAll(Context context) {
        cancelScreenOff(context);
        cancelDailyAlarm(context);
    }

    private static PendingIntent dailyPendingIntent(Context context) {
        return PendingIntent.getBroadcast(
            context,
            DAILY_ALARM_REQUEST,
            new Intent(context, CodeSuDailyAlarmReceiver.class),
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
    }

    private static void cancelDailyAlarm(Context context) {
        AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (alarmManager != null) alarmManager.cancel(dailyPendingIntent(context));
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove("daily_alarm_next_at").apply();
    }

    private static boolean canScheduleExact(Context context) {
        if (Build.VERSION.SDK_INT < 31) return true;
        try {
            AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
            return alarmManager != null && alarmManager.canScheduleExactAlarms();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void cancelScreenOff(Context context) {
        WorkManager.getInstance(context).cancelUniqueWork(SCREEN_WORK);
    }
}
