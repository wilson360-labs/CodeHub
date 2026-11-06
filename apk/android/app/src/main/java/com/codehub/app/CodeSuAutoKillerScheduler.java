package com.codehub.app;

import android.content.Context;

import androidx.work.Data;
import androidx.work.ExistingWorkPolicy;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;

import java.util.Calendar;
import java.util.concurrent.TimeUnit;

/** WorkManager scheduling for opt-in screen-off and daily Auto Killer runs. */
public final class CodeSuAutoKillerScheduler {
    public static final int MODE_OFF = 0;
    public static final int MODE_SCREEN_OFF = 1;
    public static final int MODE_DAILY = 2;
    public static final String KEY_TRIGGER = "codesu_auto_trigger";
    public static final String TRIGGER_SCREEN = "screen";
    public static final String TRIGGER_DAILY = "daily";
    private static final String SCREEN_WORK = "codesu_auto_screen_off";
    private static final String DAILY_WORK = "codesu_auto_daily";
    private static final String PREFS = "codesu_preferences";

    private CodeSuAutoKillerScheduler() {}

    public static void configure(Context context, int mode, int hour, int minute) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt("auto_mode", mode)
            .putInt("auto_hour", hour)
            .putInt("auto_minute", minute)
            .apply();
        cancelScreenOff(context);
        WorkManager manager = WorkManager.getInstance(context);
        manager.cancelUniqueWork(DAILY_WORK);
        if (mode == MODE_DAILY) scheduleDaily(context);
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

    public static void scheduleDaily(Context context) {
        android.content.SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (prefs.getInt("auto_mode", MODE_OFF) != MODE_DAILY) return;
        Calendar now = Calendar.getInstance();
        Calendar next = (Calendar) now.clone();
        next.set(Calendar.HOUR_OF_DAY, prefs.getInt("auto_hour", 22));
        next.set(Calendar.MINUTE, prefs.getInt("auto_minute", 0));
        next.set(Calendar.SECOND, 0);
        next.set(Calendar.MILLISECOND, 0);
        if (!next.after(now)) next.add(Calendar.DAY_OF_YEAR, 1);
        OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(CodeSuAutoKillerWorker.class)
            .setInputData(new Data.Builder().putString(KEY_TRIGGER, TRIGGER_DAILY).build())
            .setInitialDelay(Math.max(1, next.getTimeInMillis() - now.getTimeInMillis()), TimeUnit.MILLISECONDS)
            .build();
        WorkManager.getInstance(context).enqueueUniqueWork(DAILY_WORK, ExistingWorkPolicy.APPEND_OR_REPLACE, request);
    }

    public static void cancelAll(Context context) {
        WorkManager manager = WorkManager.getInstance(context);
        manager.cancelUniqueWork(SCREEN_WORK);
        manager.cancelUniqueWork(DAILY_WORK);
    }

    private static void cancelScreenOff(Context context) {
        WorkManager.getInstance(context).cancelUniqueWork(SCREEN_WORK);
    }
}
