package com.codehub.app.codesu.util;

import android.content.ComponentName;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.IBinder;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;

import com.codehub.app.codesu.App;
import com.codehub.app.codesu.IKillAppService;
import com.codehub.app.codesu.model.AppInfo;
import com.codehub.app.codesu.service.ShizukuUserService;
import rikka.shizuku.Shizuku;

public class ShizukuPlusUtils {

    private static volatile IKillAppService killAppService = null;
    private static volatile CountDownLatch bindLatch = null;

    private static final ServiceConnection userServiceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            killAppService = IKillAppService.Stub.asInterface(service);
            if (bindLatch != null) {
                bindLatch.countDown();
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            killAppService = null;
        }
    };

    private static Shizuku.UserServiceArgs userServiceArgs() {
        // Lazy: App.context solo existe tras App.init() (CodeHubApp.onCreate).
        return new Shizuku.UserServiceArgs(new ComponentName(App.context.getPackageName(), ShizukuUserService.class.getName()))
                .daemon(false)
                .processNameSuffix("codesu")
                .debuggable(false)
                .version(6);
    }

    public static boolean checkShizuku() {
        try {
            return Shizuku.pingBinder()
                    && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
        } catch (Exception e) {
            return false;
        }
    }

    private static synchronized boolean ensureServiceBound() {
        if (killAppService != null)
            return true;
        if (!checkShizuku())
            return false;

        CountDownLatch latch = new CountDownLatch(1);
        bindLatch = latch;
        try {
            Shizuku.bindUserService(userServiceArgs(), userServiceConnection);
            boolean connected = latch.await(10, java.util.concurrent.TimeUnit.SECONDS)
                    && killAppService != null;
            if (!connected) {
                bindLatch = null;
                Shizuku.unbindUserService(userServiceArgs(), userServiceConnection, false);
            }
            return connected;
        } catch (Exception e) {
            bindLatch = null;
            try {
                Shizuku.unbindUserService(userServiceArgs(), userServiceConnection, false);
            } catch (Exception ignored) {
                return false;
            }
            return false;
        }
    }

    public static boolean killApp(String pkgName, int level) {
        if (!ensureServiceBound())
            return false;
        try {
            return killAppService.killApp(pkgName, level);
        } catch (Exception e) {
            return false;
        }
    }

    public static int killListOfApps(List<AppInfo> appList, int level) {
        if (!ensureServiceBound())
            return -1;
        try {
            List<String> pkgList = new ArrayList<>(appList.size());
            for (AppInfo app : appList) {
                pkgList.add(app.getPkgName());
            }
            return killAppService.killListOfApps(pkgList, level);
        } catch (Exception e) {
            return -1;
        }
    }

    /** Returns 1 when the package manager confirms a cache clear, 0 on failure, -1 if Shizuku is unavailable. */
    public static int clearAppCache(String packageName) {
        return clearAppCacheDetailed(packageName).isEmpty() ? 1 : 0;
    }

    public static String clearAppCacheDetailed(String packageName) {
        return clearAppCacheDetailed(packageName, 0L);
    }

    public static String manageSystemApp(String packageName, int action) {
        if (!ensureServiceBound()) return "El servicio Shizuku de CodeSu no está conectado.";
        try {
            String result = killAppService.manageSystemApp(packageName, action);
            return result == null ? "Shizuku no devolvió resultado." : result;
        } catch (Exception e) {
            return "No se pudo administrar la app: "
                    + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
    }

    public static String clearAppCacheDetailed(String packageName, long estimatedCacheBytes) {
        if (!ensureServiceBound()) return "El servicio Shizuku de CodeSu no está conectado.";
        try {
            String result = killAppService.clearAppCacheDetailedWithEstimate(packageName, estimatedCacheBytes);
            return result == null ? "Shizuku no devolvió el resultado de Package Manager." : result;
        } catch (Exception e) {
            return "Shizuku no pudo ejecutar la limpieza: "
                    + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
    }

    public static void cancelCurrentCacheClear() {
        if (!ensureServiceBound()) return;
        try { killAppService.cancelCurrentCacheClear(); }
        catch (Exception ignored) { }
    }
}
