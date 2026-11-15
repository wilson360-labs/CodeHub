package com.codehub.app.codesu.service;

import android.os.SystemClock;

import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;

import com.codehub.app.codesu.IKillAppService;

/**
 * UserService de CodeSu que corre como shell en el proceso de Shizuku.
 * Protege los paquetes que CodeHub necesita vivos (host, Shizuku, shell y
 * providers de WebView): forzar su stop rompe el WebView de CodeHub porque
 * AMS los declara como dependencia de paquete (pkgDeps).
 */
public class ShizukuUserService extends IKillAppService.Stub {

    private static final long CACHE_CLEAR_TIMEOUT_MS = 30_000L;

    /** Paquete host donde vive el módulo (App.context no está inicializado aquí). */
    public static final String HOST_PACKAGE = "com.codehub.app";

    private static final String[] ENGINE_PROTECTED = {
            HOST_PACKAGE,
            "moe.shizuku.privileged.api",
            "com.android.shell",
    };

    private static final String[] WEBVIEW_PROVIDER_PREFIXES = {
            "com.google.android.webview",
            "com.android.webview",
            "com.google.android.trichromelibrary",
            "com.android.chrome",
            "org.chromium.webview_shell",
    };

    private static final String[] CACHE_PROTECTED_PACKAGES = {
            "android", HOST_PACKAGE, "moe.shizuku.privileged.api",
            "com.android.systemui", "com.android.settings", "com.android.shell",
            "com.android.phone", "com.android.packageinstaller", "com.android.permissioncontroller",
            "com.google.android.gms", "com.google.android.gsf", "com.google.android.ims",
            "com.google.android.setupwizard", "com.google.android.vending",
    };

    private static boolean isProtectedTarget(String pkgName) {
        for (String protectedPkg : ENGINE_PROTECTED)
            if (protectedPkg.equals(pkgName))
                return true;
        for (String prefix : WEBVIEW_PROVIDER_PREFIXES)
            if (pkgName.startsWith(prefix))
                return true;
        return false;
    }

    private static boolean isProtectedCacheTarget(String pkgName) {
        if (pkgName == null || pkgName.trim().isEmpty()) return true;
        for (String protectedPkg : CACHE_PROTECTED_PACKAGES)
            if (protectedPkg.equals(pkgName)) return true;
        for (String prefix : WEBVIEW_PROVIDER_PREFIXES)
            if (pkgName.startsWith(prefix)) return true;
        return pkgName.startsWith("com.android.providers.")
                || pkgName.startsWith("com.google.android.providers.");
    }

    /** Uses PackageManager's cache-only operation in the Shizuku shell process. */
    @Override
    public int clearAppCache(String pkgName) {
        return clearAppCacheDetailed(pkgName).isEmpty() ? 1 : 0;
    }

    @Override
    public String clearAppCacheDetailed(String pkgName) {
        if (isProtectedCacheTarget(pkgName)) return "Paquete protegido por seguridad.";
        Process process = null;
        try {
            process = new ProcessBuilder("/system/bin/pm", "clear", "--user", "current", "--cache-only", pkgName)
                    .redirectErrorStream(true)
                    .start();
            Process running = process;
            ByteArrayOutputStream captured = new ByteArrayOutputStream();
            Thread outputReader = new Thread(() -> {
                try (InputStream stream = running.getInputStream()) {
                    byte[] buffer = new byte[512];
                    int count;
                    while ((count = stream.read(buffer)) >= 0) {
                        if (count > 0) synchronized (captured) { captured.write(buffer, 0, count); }
                    }
                } catch (IOException ignored) { }
            }, "codesu-cache-pm-output");
            outputReader.setDaemon(true);
            outputReader.start();

            // Drain output while waiting; otherwise a full pipe can deadlock pm clear.
            long deadline = SystemClock.elapsedRealtime() + CACHE_CLEAR_TIMEOUT_MS;
            int exitCode;
            while (true) {
                try {
                    exitCode = running.exitValue();
                    break;
                } catch (IllegalThreadStateException processStillRunning) {
                    if (SystemClock.elapsedRealtime() >= deadline) {
                        running.destroy();
                        long destroyDeadline = SystemClock.elapsedRealtime() + 500L;
                        while (SystemClock.elapsedRealtime() < destroyDeadline) {
                            try { running.exitValue(); break; }
                            catch (IllegalThreadStateException processStillRunningAfterDestroy) { Thread.sleep(50L); }
                        }
                        try { running.exitValue(); }
                        catch (IllegalThreadStateException processStillRunningAfterTimeout) {
                            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) running.destroyForcibly();
                        }
                        try { outputReader.join(500L); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                        return "pm clear superó el límite de 30 s" + outputSuffix(captured);
                    }
                    Thread.sleep(100L);
                }
            }
            try { outputReader.join(1500L); } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return "Se interrumpió la lectura de respuesta de Package Manager.";
            }
            String output = outputSuffix(captured);
            if (exitCode != 0) return "pm clear terminó con código " + exitCode + output;
            if (!output.toLowerCase(java.util.Locale.ROOT).matches("(?s).*\\bsuccess\\b.*"))
                return "Package Manager no confirmó la limpieza" + output;
            return "";
        } catch (Exception e) {
            return "No se pudo ejecutar pm clear: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        } finally {
            if (process != null) process.destroy();
        }
    }

    private static String outputSuffix(ByteArrayOutputStream captured) {
        String output;
        synchronized (captured) {
            output = new String(captured.toByteArray(), java.nio.charset.StandardCharsets.UTF_8).trim();
        }
        if (output.isEmpty()) return "";
        if (output.length() > 240) output = output.substring(0, 240) + "…";
        return ": " + output;
    }

    @Override
    public boolean killApp(String pkgName, int level) {
        if (isProtectedTarget(pkgName))
            return false;
        Process process = null;
        try {
            process = Runtime.getRuntime().exec(new String[]{"sh"});
            OutputStream os = process.getOutputStream();

            appendKillCommands(os, pkgName, level);

            os.write("exit\n".getBytes());
            os.flush();
            os.close();
            process.getInputStream().close();
            process.getErrorStream().close();
            process.waitFor();
            return process.exitValue() == 0;
        } catch (Exception e) {
            return false;
        } finally {
            if (process != null) {
                process.destroyForcibly();
            }
        }
    }

    @Override
    public int killListOfApps(List<String> packages, int level) {
        if (packages == null || packages.isEmpty())
            return 0;

        Process process = null;
        try {
            process = Runtime.getRuntime().exec(new String[]{"sh"});
            OutputStream os = process.getOutputStream();
            boolean killedSelf = false;

            for (String pkg : packages) {
                if (HOST_PACKAGE.equals(pkg)) {
                    killedSelf = true;
                    continue;
                }
                if (isProtectedTarget(pkg))
                    continue;
                appendKillCommands(os, pkg, level);
            }

            os.write("exit\n".getBytes());
            os.flush();
            os.close();
            process.getInputStream().close();
            process.getErrorStream().close();
            process.waitFor();
            return killedSelf ? 1 : 0;
        } catch (Exception e) {
            return -1;
        } finally {
            if (process != null) {
                process.destroyForcibly();
            }
        }
    }

    private static void appendKillCommands(OutputStream os, String pkgName, int level) throws IOException {
        switch (level) {
            case 0:
                os.write(("am kill " + pkgName + "\n").getBytes());
                os.write(("am set-inactive " + pkgName + " true\n").getBytes());
                break;

            case 2:
                os.write(("am force-stop " + pkgName + "\n").getBytes());
                os.write(("cmd package suspend " + pkgName + " 2>/dev/null && cmd package unsuspend " + pkgName + " 2>/dev/null\n").getBytes());
                break;

            case 1:
            default:
                os.write(("am force-stop " + pkgName + "\n").getBytes());
                break;
        }
    }
}
