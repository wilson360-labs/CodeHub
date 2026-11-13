package com.codehub.app;

import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.os.IBinder;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import rikka.shizuku.Shizuku;

/**
 * Ejecuta una pasada del Auto Killer con los filtros guardados de CodeSu.
 * Lo usan la alarma diaria del sistema (CodeSuDailyAlarmReceiver, semántica
 * KillMyApps) y el run de WorkManager al apagar la pantalla.
 */
public final class CodeSuAutoKillerRunner {

    private static final Object lock = new Object();
    private static CountDownLatch connected;
    private static ICodeSuRemoteService remote;
    private static Shizuku.UserServiceArgs args;
    private static ServiceConnection connection;

    private CodeSuAutoKillerRunner() {}

    /** @return mensaje resumen para `last_auto_result`; lanza si el motor no está listo. */
    public static String run(Context context) throws Exception {
        Context app = context.getApplicationContext();
        android.content.SharedPreferences prefs = app.getSharedPreferences("codesu_preferences", Context.MODE_PRIVATE);
        if (SystemCleaner.INSTANCE.refreshStatus() != SystemCleaner.Status.READY) {
            throw new IllegalStateException("Shizuku no está activo o CodeHub no tiene permiso.");
        }
        ICodeSuRemoteService service = bindService(app);
        try {
            Set<String> critical = CodeSuAppPolicy.criticalPackages(app);
            Set<String> running = new HashSet<>();
            java.util.Collections.addAll(running, service.runningPackages());
            Set<String> selected = prefs.getStringSet("selected_packages", new HashSet<>());
            if (selected == null) selected = new HashSet<>();
            int source = prefs.getInt("source_mode", CodeSuAppPolicy.SOURCE_ALL);
            int selection = prefs.getInt("selection_mode", 0);
            Set<String> installed = new HashSet<>();
            for (android.content.pm.ApplicationInfo application : app.getPackageManager()
                    .getInstalledApplications(android.content.pm.PackageManager.GET_META_DATA)) {
                if (!application.packageName.equals(app.getPackageName())
                        && !application.packageName.equals("moe.shizuku.privileged.api")) installed.add(application.packageName);
            }
            ArrayList<String> targets = new ArrayList<>();
            for (String packageName : installed) {
                // Nunca force-stop al launcher, system UI, CodeHub, Shizuku u
                // otra app esencial. Los toggles de UI solo controlan visibilidad.
                if (critical.contains(packageName)) continue;
                if (!running.contains(packageName)) continue;
                if (!CodeSuAppPolicy.matchesSource(app, packageName, source)) continue;
                boolean isSelected = selected.contains(packageName);
                if ((selection == 0 && isSelected) || (selection != 0 && !isSelected)) targets.add(packageName);
            }
            if (targets.isEmpty()) return "Auto Killer: no hay apps en ejecución dentro de tu selección.";
            int level = prefs.getInt("kill_mode", CodeSuUserService.LEVEL_FORCE_STOP);
            android.os.Bundle result = service.stopPackages(targets.toArray(new String[0]), level);
            if (result == null) throw new IllegalStateException("CodeSu no devolvió el resultado del motor.");
            ArrayList<String> stopped = result.getStringArrayList("stopped");
            ArrayList<String> failed = result.getStringArrayList("failed");
            return "Auto Killer: " + (stopped == null ? 0 : stopped.size())
                + " cerradas · " + (failed == null ? 0 : failed.size()) + " fallidas.";
        } finally {
            unbindService();
        }
    }

    private static ICodeSuRemoteService bindService(Context appContext) throws Exception {
        synchronized (lock) {
            if (remote != null) return remote;
            args = new Shizuku.UserServiceArgs(new ComponentName(appContext, CodeSuUserService.class))
                .daemon(false).processNameSuffix("codesu").debuggable(false).version(CodeSuUserService.USER_SERVICE_VERSION);
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
            ICodeSuRemoteService connectedService = remote;
            if (connectedService == null) {
                unbindService();
                throw new IllegalStateException("Shizuku no entregó la conexión al UserService.");
            }
            return connectedService;
        }
    }

    private static void unbindService() {
        synchronized (lock) {
            if (args != null && connection != null) {
                try { Shizuku.unbindUserService(args, connection, true); } catch (Throwable ignored) {}
            }
            args = null;
            connection = null;
            remote = null;
        }
    }
}
