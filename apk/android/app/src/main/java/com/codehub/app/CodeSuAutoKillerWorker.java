package com.codehub.app;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

/**
 * Run del Auto Killer al apagar la pantalla (WorkManager + delay configurable).
 * El Auto Killer diario usa la alarma del sistema (CodeSuDailyAlarmReceiver,
 * semántica KillMyApps) porque WorkManager se pospone con ahorro de batería.
 */
public final class CodeSuAutoKillerWorker extends Worker {

    public CodeSuAutoKillerWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        Context appContext = getApplicationContext();
        android.content.SharedPreferences prefs = appContext.getSharedPreferences("codesu_preferences", Context.MODE_PRIVATE);
        try {
            if (prefs.getInt("auto_mode", CodeSuAutoKillerScheduler.MODE_OFF) != CodeSuAutoKillerScheduler.MODE_SCREEN_OFF) {
                return Result.success();
            }
            String message = CodeSuAutoKillerRunner.run(appContext);
            remember(prefs, message);
        } catch (Throwable error) {
            remember(prefs, "Auto Killer no completado: " + error.getClass().getSimpleName() + ".");
        }
        return Result.success();
    }

    private static void remember(android.content.SharedPreferences prefs, String message) {
        prefs.edit().putString("last_auto_result", message).putLong("last_auto_time", System.currentTimeMillis()).apply();
    }
}
