package com.codehub.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;

/** Dynamic screen-state listener; registering dynamically follows Android's screen broadcast rules. */
public final class CodeSuScreenReceiver extends BroadcastReceiver {
    public static IntentFilter filter() {
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_SCREEN_ON);
        return filter;
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        if (Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) CodeSuAutoKillerScheduler.onScreenOff(context);
        else if (Intent.ACTION_SCREEN_ON.equals(intent.getAction())) CodeSuAutoKillerScheduler.onScreenOn(context);
    }

    public static void register(Context context, CodeSuScreenReceiver receiver) {
        try {
            if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, filter(), Context.RECEIVER_NOT_EXPORTED);
            else context.registerReceiver(receiver, filter());
        } catch (Throwable ignored) {}
    }
}
