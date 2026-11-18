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

    private volatile Process activeCacheClearProcess;

    private static final long CACHE_CLEAR_BASE_TIMEOUT_MS = 30_000L;
    private static final long CACHE_CLEAR_MAX_TIMEOUT_MS = 45_000L;
    private static final long CACHE_CLEAR_BYTES_PER_EXTRA_5S = 100L * 1024L * 1024L;

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
        return clearAppCacheDetailedWithEstimate(pkgName, 0L);
    }

    @Override
    public String clearAppCacheDetailedWithEstimate(String pkgName, long estimatedCacheBytes) {
        if (isProtectedCacheTarget(pkgName)) return "Paquete protegido por seguridad.";
        Process process = null;
        try {
            long extraSteps = Math.max(0L, estimatedCacheBytes) / CACHE_CLEAR_BYTES_PER_EXTRA_5S;
            long timeoutMs = Math.min(CACHE_CLEAR_MAX_TIMEOUT_MS,
                    CACHE_CLEAR_BASE_TIMEOUT_MS + extraSteps * 5_000L);
            process = new ProcessBuilder("/system/bin/cmd", "package", "clear", "--user", "current", "--cache-only", pkgName)
                    .redirectErrorStream(true)
                    .start();
            activeCacheClearProcess = process;
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

            // Drain output while waiting; otherwise a full pipe can deadlock the package command.
            long deadline = SystemClock.elapsedRealtime() + timeoutMs;
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
                        return "Package Manager superó el límite de " + (timeoutMs / 1000L)
                                + " s para esta app" + outputSuffix(captured);
                    }
                    Thread.sleep(100L);
                }
            }
            try { outputReader.join(1500L); } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return "Se interrumpió la lectura de respuesta de Package Manager.";
            }
            String output = outputSuffix(captured);
            if (exitCode != 0) return "Package Manager terminó con código " + exitCode + output;
            if (!output.toLowerCase(java.util.Locale.ROOT).matches("(?s).*\\bsuccess\\b.*"))
                return "Package Manager no confirmó la limpieza" + output;
            return "";
        } catch (Exception e) {
            return "Shizuku no pudo limpiar la caché: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        } finally {
            if (process != null) process.destroy();
            if (activeCacheClearProcess == process) activeCacheClearProcess = null;
        }
    }

    @Override
    public void cancelCurrentCacheClear() {
        Process running = activeCacheClearProcess;
        if (running != null) running.destroy();
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

    private static boolean isProtectedSystemPackage(String pkg) {
        if (pkg == null) return true;
        String[] protectedPackages = {
                HOST_PACKAGE, "android", "com.android.systemui", "com.android.settings",
                "com.android.shell", "com.android.phone", "com.android.permissioncontroller",
                "com.android.packageinstaller", "com.google.android.gms", "com.google.android.gsf",
                "com.google.android.vending", "moe.shizuku.privileged.api",
                "com.android.launcher3", "com.sec.android.app.launcher",
                "com.google.android.apps.nexuslauncher", "com.miui.home",
                "com.oppo.launcher", "com.huawei.android.launcher", "com.motorola.launcher3",
                "com.google.android.inputmethod.latin", "com.samsung.android.honeyboard"
        };
        for (String item : protectedPackages) if (item.equals(pkg)) return true;
        return pkg.startsWith("com.android.providers.") || pkg.startsWith("com.google.android.providers.")
                || pkg.startsWith("com.android.system.") || pkg.startsWith("com.google.android.webview")
                || pkg.startsWith("com.android.webview") || pkg.startsWith("com.google.android.trichromelibrary")
                || pkg.startsWith("com.android.launcher") || pkg.startsWith("com.google.android.inputmethod");
    }

    /** Reversible per-user system-app disable/enable. It never uninstalls APKs. */
    @Override
    public String manageSystemApp(String packageName, int action) {
        if (packageName == null || !packageName.matches("[A-Za-z0-9._]+"))
            return "Nombre de paquete no válido.";
        if (isProtectedSystemPackage(packageName)) return "App protegida por seguridad.";
        if (action < 0 || action > 3) return "Acción no válida.";
        Process listing = null;
        try {
            listing = new ProcessBuilder("/system/bin/cmd", "package", "list", "packages", "-s", "-u")
                    .redirectErrorStream(true).start();
            ByteArrayOutputStream listed = new ByteArrayOutputStream();
            try (InputStream stream = listing.getInputStream()) {
                byte[] buffer = new byte[2048];
                int count;
                while ((count = stream.read(buffer)) >= 0) if (count > 0) listed.write(buffer, 0, count);
            }
            long deadline = SystemClock.elapsedRealtime() + 10_000L;
            while (true) {
                try { listing.exitValue(); break; }
                catch (IllegalThreadStateException running) {
                    if (SystemClock.elapsedRealtime() >= deadline) {
                        listing.destroy();
                        return "Package Manager tardó demasiado.";
                    }
                    Thread.sleep(50L);
                }
            }
            boolean systemPackage = false;
            for (String line : listed.toString("UTF-8").split("\\r?\\n"))
                if (line.trim().equals("package:" + packageName)) { systemPackage = true; break; }
            if (!systemPackage) return "Solo se permiten apps del sistema detectadas por Package Manager.";

            String operation = action == 0 ? "disable-user" : action == 1 ? "enable"
                    : action == 2 ? "uninstall" : "install-existing";
            Process command = new ProcessBuilder("/system/bin/cmd", "package", operation,
                    "--user", "current", packageName).redirectErrorStream(true).start();
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            Process runningCommand = command;
            Thread reader = new Thread(() -> {
                try (InputStream stream = runningCommand.getInputStream()) {
                    byte[] buffer = new byte[1024];
                    int count;
                    while ((count = stream.read(buffer)) >= 0) if (count > 0) synchronized (output) { output.write(buffer, 0, count); }
                } catch (IOException ignored) { }
            }, "codesu-debloater-output");
            reader.setDaemon(true);
            reader.start();
            deadline = SystemClock.elapsedRealtime() + 15_000L;
            int exit;
            while (true) {
                try { exit = command.exitValue(); break; }
                catch (IllegalThreadStateException running) {
                    if (SystemClock.elapsedRealtime() >= deadline) {
                        command.destroy();
                        return "La operación superó 15 segundos.";
                    }
                    Thread.sleep(80L);
                }
            }
            try { reader.join(700L); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            String message;
            synchronized (output) { message = output.toString("UTF-8").trim(); }
            if (exit != 0) return message.isEmpty() ? "Package Manager rechazó la operación." : message;
            return "OK: " + (message.isEmpty() ? "Operación completada para este usuario." : message);
        } catch (Exception e) {
            return "Shizuku no pudo completar la operación: "
                    + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        } finally {
            if (listing != null) listing.destroy();
        }
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
