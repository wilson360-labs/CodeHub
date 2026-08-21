package com.codehub.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Receiver para AlarmManager periódico. Reinicia el Foreground Service
 * cuando la alarma dispara (cada 15 min), incluso si el sistema mató
 * el servicio anteriormente. Esto garantiza que CodeHub nunca muera
 * completamente en segundo plano.
 */
public class SyncAlarmReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        CodeHubSyncService.startIfNotRunning(context);
    }
}
