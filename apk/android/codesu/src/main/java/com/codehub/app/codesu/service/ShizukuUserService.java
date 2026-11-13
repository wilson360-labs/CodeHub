package com.codehub.app.codesu.service;

import java.io.IOException;
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

    private static boolean isProtectedTarget(String pkgName) {
        for (String protectedPkg : ENGINE_PROTECTED)
            if (protectedPkg.equals(pkgName))
                return true;
        for (String prefix : WEBVIEW_PROVIDER_PREFIXES)
            if (pkgName.startsWith(prefix))
                return true;
        return false;
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