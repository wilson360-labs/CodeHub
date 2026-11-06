package com.codehub.app;

import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.os.IBinder;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import rikka.shizuku.Shizuku;

/** Runs a user's saved CodeSu filters once, only for currently running apps. */
public final class CodeSuAutoKillerWorker extends Worker {
    private final Context appContext;

    public CodeSuAutoKillerWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
        appContext = context.getApplicationContext();
    }

    @NonNull
    @Override
    public Result doWork() {
        String trigger = getInputData().getString(CodeSuAutoKillerScheduler.KEY_TRIGGER);
        android.content.SharedPreferences prefs = appContext.getSharedPreferences("codesu_preferences", Context.MODE_PRIVATE);
        int expectedMode = CodeSuAutoKillerScheduler.TRIGGER_DAILY.equals(trigger)
            ? CodeSuAutoKillerScheduler.MODE_DAILY : CodeSuAutoKillerScheduler.MODE_SCREEN_OFF;
        try {
            if (prefs.getInt("auto_mode", CodeSuAutoKillerScheduler.MODE_OFF) != expectedMode) return Result.success();
            if (SystemCleaner.INSTANCE.refreshStatus() != SystemCleaner.Status.READY) {
                remember(prefs, "Auto Killer omitido: Shizuku no está activo o CodeHub no tiene permiso.");
                return Result.success();
            }
            ICodeSuRemoteService service = bindService();
            try {
                String[] activePackages = service.runningPackages();
                Set<String> running = new HashSet<>();
                if (activePackages != null) running.addAll(Arrays.asList(activePackages));
                Set<String> critical = CodeSuAppPolicy.criticalPackages(appContext);
                Set<String> selected = prefs.getStringSet("selected_packages", new HashSet<>());
                if (selected == null) selected = new HashSet<>();
                boolean hideCritical = prefs.getBoolean("hide_critical_apps", prefs.getBoolean("protect_essential_apps", true));
                int source = prefs.getInt("source_mode", CodeSuAppPolicy.SOURCE_USER);
                int selection = prefs.getInt("selection_mode", 0);
                ArrayList<String> targets = new ArrayList<>();
                for (String packageName : running) {
                    if (hideCritical && critical.contains(packageName)) continue;
                    if (!CodeSuAppPolicy.matchesSource(appContext, packageName, source)) continue;
                    boolean isSelected = selected.contains(packageName);
                    if ((selection == 0 && isSelected) || (selection != 0 && !isSelected)) targets.add(packageName);
                }
                if (targets.isEmpty()) {
                    remember(prefs, "Auto Killer: no había apps activas dentro de tu selección.");
                    return Result.success();
                }
                int level = prefs.getInt("kill_mode", CodeSuUserService.LEVEL_FORCE_STOP);
                android.os.Bundle result = service.stopPackages(targets.toArray(new String[0]), level);
                if (result == null) throw new IllegalStateException("CodeSu no devolvió el resultado del motor.");
                ArrayList<String> stopped = result.getStringArrayList("stopped");
                ArrayList<String> failed = result.getStringArrayList("failed");
                remember(prefs, "Auto Killer: " + (stopped == null ? 0 : stopped.size())
                    + " cerradas · " + (failed == null ? 0 : failed.size()) + " fallidas.");
            } finally {
                unbindService();
            }
            return Result.success();
        } catch (Throwable error) {
            remember(prefs, "Auto Killer no completado: " + error.getClass().getSimpleName() + ".");
            return Result.success();
        } finally {
            if (CodeSuAutoKillerScheduler.TRIGGER_DAILY.equals(trigger)
                    && prefs.getInt("auto_mode", CodeSuAutoKillerScheduler.MODE_OFF) == CodeSuAutoKillerScheduler.MODE_DAILY) {
                CodeSuAutoKillerScheduler.scheduleDaily(appContext);
            }
        }
    }

    private final Object lock = new Object();
    private CountDownLatch connected;
    private ICodeSuRemoteService remote;
    private Shizuku.UserServiceArgs args;
    private ServiceConnection connection;

    private ICodeSuRemoteService bindService() throws Exception {
        args = new Shizuku.UserServiceArgs(new ComponentName(appContext, CodeSuUserService.class))
            .daemon(false).processNameSuffix("codesu").debuggable(false).version(2);
        connected = new CountDownLatch(1);
        connection = new ServiceConnection() {
            @Override public void onServiceConnected(ComponentName name, IBinder binder) {
                synchronized (lock) { remote = ICodeSuRemoteService.Stub.asInterface(binder); }
                connected.countDown();
            }
            @Override public void onServiceDisconnected(ComponentName name) {
                synchronized (lock) { remote = null; }
            }
        };
        try {
            Shizuku.bindUserService(args, connection);
            if (!connected.await(20, TimeUnit.SECONDS)) throw new IllegalStateException("Shizuku UserService agotó el tiempo.");
        } catch (Exception error) {
            unbindService();
            throw error;
        }
        synchronized (lock) {
            if (remote == null) throw new IllegalStateException("Shizuku no entregó la conexión al UserService.");
            return remote;
        }
    }

    private void unbindService() {
        if (args != null && connection != null) {
            try { Shizuku.unbindUserService(args, connection, true); } catch (Throwable ignored) {}
            args = null;
            connection = null;
            synchronized (lock) { remote = null; }
        }
    }

    private static void remember(android.content.SharedPreferences prefs, String message) {
        prefs.edit().putString("last_auto_result", message).putLong("last_auto_time", System.currentTimeMillis()).apply();
    }
}
