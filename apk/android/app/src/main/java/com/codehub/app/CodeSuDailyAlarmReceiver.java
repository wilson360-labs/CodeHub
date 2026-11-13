package com.codehub.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Auto Killer diario de CodeSu (semántica KillMyApps): alarma RTC_WAKEUP del
 * sistema que dispara incluso con ahorro de batería. Primero reprograma el
 * siguiente día (si el kill falla, la alarma no se pierde) y luego intenta el
 * run con los filtros guardados de CodeSu.
 */
public class CodeSuDailyAlarmReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        Context app = context.getApplicationContext();
        android.content.SharedPreferences prefs = app.getSharedPreferences(CodeSuAutoKillerScheduler.PREFS, Context.MODE_PRIVATE);
        if (prefs.getInt("auto_mode", CodeSuAutoKillerScheduler.MODE_OFF) != CodeSuAutoKillerScheduler.MODE_DAILY) return;
        // Rearmar PRIMERO: aunque Shizuku esté apagado y el run falle, la
        // alarma del día siguiente queda programada.
        CodeSuAutoKillerScheduler.scheduleDailyAlarm(app);
        PendingResult pending = goAsync();
        new Thread(() -> {
            try {
                String message = CodeSuAutoKillerRunner.run(app);
                prefs.edit().putString("last_auto_result", message)
                    .putLong("last_auto_time", System.currentTimeMillis()).apply();
            } catch (Throwable error) {
                prefs.edit().putString("last_auto_result",
                        "Auto Killer no completado: " + error.getClass().getSimpleName() + ".")
                    .putLong("last_auto_time", System.currentTimeMillis()).apply();
            } finally {
                pending.finish();
            }
        }, "CodeSu-daily-alarm").start();
    }
}
