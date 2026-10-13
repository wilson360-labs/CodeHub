package com.codehub.app;

import android.app.Application;
import android.util.Log;

import org.koin.androix.startup.KoinStartup;
import org.koin.dsl.KoinConfiguration;

public class CodeHubApp extends Application implements KoinStartup {

    private File crashDir;

    /**
     * Arranque del contenedor Koin del reproductor Echo (módulo :echo).
     *
     * Echo enciende su Koin desde `dev.brahmkshatriya.echo.MainApplication`
     * implementando `KoinStartup`; como la Application de CodeHub es esta, el
     * hook no se dispararía. `koin-androidx-startup` descubre la clase que
     * implementa `KoinStartup` (esta) y llama a este método antes de
     * `onCreate`, dejando Koin + el WorkManager factory listos para la UI de
     * Echo.
     */
    @Override
    public KoinConfiguration onKoinStartup() {
        return dev.brahmkshatriya.echo.EchoBoot.config(this);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        crashDir = getFilesDir();
        Thread.setDefaultUncaughtExceptionHandler(new CrashHandler(this));
        // Si quedó un crash.log de una sesión anterior que no pudo enviarse
        // (sin internet en el momento del crash), lo reintenta ahora.
        CrashReporter.flushPendingLog(this);
        // Appodeal (mediación) se inicializa en MainActivity tras el gate de
        // UMP (ConsentManager) — el adaptador de AdMob se inicializa solo.
        // Lanzar Foreground Service lo antes posible para WakeLock + sync
        CodeHubSyncService.startIfNotRunning(this);
    }

    private static final class CrashHandler implements Thread.UncaughtExceptionHandler {
        private final Thread.UncaughtExceptionHandler previous =
            Thread.getDefaultUncaughtExceptionHandler();
        private final CodeHubApp app;

        CrashHandler(CodeHubApp app) { this.app = app; }

        /** Versión real del APK (versionName) en el crash.log — antes quedaba
         *  hardcodeada como 1.2.0 y los reportes de crash mostraban la versión
         *  equivocada (hoy la serie se sincroniza con sw.js: 7.x). */
        private String appVersion() {
            try { return app.getPackageManager().getPackageInfo(app.getPackageName(), 0).versionName; }
            catch (Exception e) { return "1.2.0"; }
        }

        @Override
        public void uncaughtException(Thread t, Throwable e) {
            // Intento síncrono con timeout corto — el proceso está a punto de
            // morir, así que esto se hace ANTES de tocar el archivo/handler
            // anterior para maximizar la chance de que el reporte salga.
            try { CrashReporter.reportFatal(app, t, e); } catch (Throwable ignored) {}
            try {
                File dir = app.crashDir;
                if (dir == null) dir = app.getFilesDir();
                if (dir != null && !dir.exists()) dir.mkdirs();
                if (dir != null) {
                    File f = new File(dir, "crash.log");
                    PrintWriter pw = new PrintWriter(new FileWriter(f, true));
                    pw.println("=== UNCAUGHT EXCEPTION ===");
                    pw.println("Thread: " + t.getName());
                    pw.println("Time: " + System.currentTimeMillis());
                    pw.println("Device: " + android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL
                        + " (Android " + android.os.Build.VERSION.RELEASE + ", API " + android.os.Build.VERSION.SDK_INT + ")");
                    pw.println("App Version: " + appVersion());
                    e.printStackTrace(pw);
                    pw.println("========================");
                    pw.println();
                    pw.close();
                }
            } catch (Exception ignored) {}
            Log.e("CodeHub", "UNCAUGHT: " + e.getMessage(), e);
            if (previous != null) {
                previous.uncaughtException(t, e);
            }
        }
    }
}
