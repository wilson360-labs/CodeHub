package com.codehub.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Receiver para AlarmManager periódico heredado. Ya no se programa una alarma
 * nueva (el sync va por WorkManager), pero se conserva para que una alarma
 * pendiente de una versión anterior no rompa nada: al disparar simplemente
 * encola una pasada del worker.
 */
public class SyncAlarmReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        CodeHubSyncScheduler.syncNow(context);
    }
}
