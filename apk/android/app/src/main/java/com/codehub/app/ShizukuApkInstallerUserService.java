package com.codehub.app;

import android.system.Os;
import android.os.Build;
import android.os.SystemClock;

import androidx.annotation.Keep;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** Runs as Shizuku shell/root and streams a verified APK into Package Manager. */
@Keep
public final class ShizukuApkInstallerUserService extends ICodeHubApkInstaller.Stub {
    private static final long MAX_APK_BYTES = 250L * 1024L * 1024L;
    private static final long INSTALL_TIMEOUT_SECONDS = 120L;

    private File stagedApk;
    private FileOutputStream output;
    private long expectedBytes;
    private long writtenBytes;

    public ShizukuApkInstallerUserService() {}

    @Override
    public synchronized void beginInstall(long apkSize) throws android.os.RemoteException {
        abortInstall();
        if (apkSize <= 0L || apkSize > MAX_APK_BYTES) {
            throw new android.os.RemoteException("Tamaño de APK no permitido.");
        }
        try {
            File stagingDirectory = new File("/data/local/tmp");
            stagedApk = new File(stagingDirectory, "codehub-update-" + UUID.randomUUID() + ".apk");
            output = new FileOutputStream(stagedApk, false);
            Os.chmod(stagedApk.getAbsolutePath(), 0600);
            expectedBytes = apkSize;
            writtenBytes = 0L;
        } catch (Exception error) {
            abortInstall();
            throw new android.os.RemoteException("No se pudo preparar el APK en el servicio Shizuku: " + safe(error));
        }
    }

    @Override
    public synchronized void appendInstallChunk(byte[] chunk) throws android.os.RemoteException {
        if (output == null || chunk == null || chunk.length == 0) {
            throw new android.os.RemoteException("La sesión de instalación no está abierta.");
        }
        if (writtenBytes + chunk.length > expectedBytes) {
            abortInstall();
            throw new android.os.RemoteException("El APK recibido supera el tamaño declarado.");
        }
        try {
            output.write(chunk);
            writtenBytes += chunk.length;
        } catch (IOException error) {
            abortInstall();
            throw new android.os.RemoteException("No se pudo transferir el APK a Shizuku: " + safe(error));
        }
    }

    @Override
    public synchronized String commitInstall(String relaunchComponent) throws android.os.RemoteException {
        File apk = stagedApk;
        try {
            if (output == null || apk == null) return "ERROR:No hay una transferencia de APK activa.";
            output.flush();
            output.close();
            output = null;
            if (writtenBytes != expectedBytes || apk.length() != expectedBytes) {
                return "ERROR:La transferencia del APK quedó incompleta.";
            }

            Process process = new ProcessBuilder("/system/bin/pm", "install", "--user", "current", "-i",
                    "com.codehub.app", "-r", apk.getAbsolutePath())
                    .redirectErrorStream(true)
                    .start();
            ByteArrayOutputStream response = new ByteArrayOutputStream();
            Thread reader = new Thread(() -> {
                try (InputStream stream = process.getInputStream()) {
                    byte[] buffer = new byte[2048];
                    int count;
                    while ((count = stream.read(buffer)) != -1) {
                        if (response.size() < 32 * 1024) {
                            response.write(buffer, 0, Math.min(count, 32 * 1024 - response.size()));
                        }
                    }
                } catch (IOException ignored) { }
            }, "codehub-pm-output");
            reader.start();
            if (!waitForProcess(process, INSTALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroy();
                return "ERROR:Package Manager superó el límite de 120 segundos.";
            }
            reader.join(2_000L);
            String result = response.toString("UTF-8").trim();
            if (process.exitValue() != 0 || !result.toLowerCase(java.util.Locale.ROOT).startsWith("success")) {
                return "ERROR:" + (result.isEmpty() ? "Package Manager rechazó la instalación." : result);
            }

            if (relaunchComponent != null && !relaunchComponent.isEmpty()) {
                if (!relaunchComponent.matches("[A-Za-z0-9._]+/[A-Za-z0-9._]+")) {
                    return "OK:" + result + "\nWARN:No se relanzó CodeHub: componente inválido.";
                }
                try {
                    Thread.sleep(900L);
                    Process launch = new ProcessBuilder("/system/bin/am", "start", "-n", relaunchComponent)
                            .redirectErrorStream(true).start();
                    launch.waitFor(10L, TimeUnit.SECONDS);
                    launch.destroy();
                } catch (Exception ignored) { }
            }
            return "OK:" + result;
        } catch (Exception error) {
            throw new android.os.RemoteException("Falló la instalación Shizuku: " + safe(error));
        } finally {
            abortInstall();
        }
    }

    @Override
    public synchronized void abortInstall() {
        if (output != null) {
            try { output.close(); } catch (IOException ignored) { }
            output = null;
        }
        if (stagedApk != null) {
            try { stagedApk.delete(); } catch (Throwable ignored) { }
            stagedApk = null;
        }
        expectedBytes = 0L;
        writtenBytes = 0L;
    }

    @Override
    public void destroy() {
        abortInstall();
        System.exit(0);
    }

    private static String safe(Throwable error) {
        String message = error == null ? null : error.getMessage();
        if (message == null || message.trim().isEmpty()) return "error desconocido";
        return message.length() > 220 ? message.substring(0, 220) : message;
    }

    /** Process.waitFor(timeout) is only available from Android 8 (API 26). */
    private static boolean waitForProcess(Process process, long timeout, TimeUnit unit)
            throws InterruptedException {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            return process.waitFor(timeout, unit);
        }
        long deadline = SystemClock.elapsedRealtime() + unit.toMillis(timeout);
        while (SystemClock.elapsedRealtime() < deadline) {
            try {
                process.exitValue();
                return true;
            } catch (IllegalThreadStateException stillRunning) {
                Thread.sleep(100L);
            }
        }
        return false;
    }
}
