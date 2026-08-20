package com.codehub.app;

import android.app.Application;
import android.util.Log;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;

public class CodeHubApp extends Application {

    private File crashDir;

    @Override
    public void onCreate() {
        super.onCreate();
        crashDir = getFilesDir();
        Thread.setDefaultUncaughtExceptionHandler(new CrashHandler(this));
        // Si quedó un crash.log de una sesión anterior que no pudo enviarse
        // (sin internet en el momento del crash), lo reintenta ahora.
        CrashReporter.flushPendingLog(this);
    }

    private static final class CrashHandler implements Thread.UncaughtExceptionHandler {
        private final Thread.UncaughtExceptionHandler previous =
            Thread.getDefaultUncaughtExceptionHandler();
        private final CodeHubApp app;

        CrashHandler(CodeHubApp app) { this.app = app; }

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
                    pw.println("App Version: 1.2.0");
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
