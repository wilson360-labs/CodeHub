package com.codehub.app.codesu.receiver;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.codehub.app.codesu.util.AutoKillHelper;
import com.codehub.app.codesu.util.PowerGuard;

/** Arranca los modos de uso al iniciar el teléfono y reacciona a batería baja. */
public final class PowerGuardReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getAction() == null) return;
        String action = intent.getAction();
        if (Intent.ACTION_BOOT_COMPLETED.equals(action)
                || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)
                || "android.intent.action.QUICKBOOT_POWERON".equals(action)) {
            PowerGuard.initialize(context);
            PowerGuard.evaluateNow(context);
            try {
                AutoKillHelper.syncServiceState();
            } catch (RuntimeException ignored) {
                // Si el FGS no puede arrancar, el Worker y este receiver siguen cubriendo la batería.
            }
        } else if (Intent.ACTION_BATTERY_LOW.equals(action)
                || PowerGuard.ACTION_CHECK_NOW.equals(action)) {
            PowerGuard.evaluateBattery(context, intent);
        } else if (PowerGuard.ACTION_RUN_KILL.equals(action)) {
            PowerGuard.killBackgroundApps(context, null, "solicitud desde notificación", null);
        }
    }
}
