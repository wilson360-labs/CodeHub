package com.codehub.app.codesu.util;

import android.content.pm.PackageManager;

import java.io.IOException;
import java.io.OutputStream;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.codehub.app.codesu.App;
import com.codehub.app.codesu.model.AppInfo;
import rikka.shizuku.Shizuku;

public class ShizukuUtils {

    private static final long TIMEOUT_SECONDS = 10;
    private static final int SHIZUKU_REQ_CODE = 1001;
    private static Process process = null;

    public static boolean checkShizuku() {
        try {
            if (!Shizuku.pingBinder())
                return false;

            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED)
                return true;

            CountDownLatch permissionLatch = new CountDownLatch(1);
            AtomicInteger grantResult = new AtomicInteger(PackageManager.PERMISSION_DENIED);

            Shizuku.OnRequestPermissionResultListener listener = (requestCode, result) -> {
                if (requestCode == SHIZUKU_REQ_CODE) {
                    grantResult.set(result);
                    permissionLatch.countDown();
                }
            };

            Shizuku.addRequestPermissionResultListener(listener);
            try {
                if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED)
                    return true;

                Shizuku.requestPermission(SHIZUKU_REQ_CODE);
                if (!permissionLatch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                    return false;

                return grantResult.get() == PackageManager.PERMISSION_GRANTED
                        && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
            } finally {
                Shizuku.removeRequestPermissionResultListener(listener);
            }
        } catch (Exception e) {
            return false;
        }
    }

    public static boolean killApp(String pkgName, int level) {
        try {
            // Shizuku.newProcess es privado en shizuku-api 13.1.x: invocación
            // reflexiva (mismo puente que ShizukuInstaller de CodeHub).
            java.lang.reflect.Method newProcess = Shizuku.class.getDeclaredMethod(
                    "newProcess", String[].class, String[].class, String.class);
            newProcess.setAccessible(true);
            process = (Process) newProcess.invoke(null, new String[]{"sh"}, null, null);
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
                process = null;
            }
        }
    }

    public static int killListOfApps(List<AppInfo> appList, int level) {
        try {
            // Shizuku.newProcess es privado en shizuku-api 13.1.x: invocación
            // reflexiva (mismo puente que ShizukuInstaller de CodeHub).
            java.lang.reflect.Method newProcess = Shizuku.class.getDeclaredMethod(
                    "newProcess", String[].class, String[].class, String.class);
            newProcess.setAccessible(true);
            process = (Process) newProcess.invoke(null, new String[]{"sh"}, null, null);
            OutputStream os = process.getOutputStream();
            boolean killedSelf = false;

            for (AppInfo app : appList) {
                String pkg = app.getPkgName();
                if (pkg.equals(App.context.getPackageName())) {
                    killedSelf = true;
                    continue;
                }
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
                process = null;
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