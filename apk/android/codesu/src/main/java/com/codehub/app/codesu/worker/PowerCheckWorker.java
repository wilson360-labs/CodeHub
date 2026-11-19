package com.codehub.app.codesu.worker;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.codehub.app.codesu.util.PowerGuard;

/** Comprobación periódica de batería/modo juego cuando la app no está abierta. */
public final class PowerCheckWorker extends Worker {

    public PowerCheckWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        try {
            PowerGuard.evaluateNow(getApplicationContext());
            return Result.success();
        } catch (RuntimeException error) {
            return Result.retry();
        }
    }
}
