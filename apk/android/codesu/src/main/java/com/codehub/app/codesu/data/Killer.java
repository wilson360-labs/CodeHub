package com.codehub.app.codesu.data;

import android.app.ActivityManager;
import android.content.Context;

import java.util.List;

import com.codehub.app.codesu.App;
import com.codehub.app.codesu.model.AppInfo;
import com.codehub.app.codesu.util.ShizukuPlusUtils;
import com.codehub.app.codesu.util.ShizukuUtils;
import com.codehub.app.codesu.util.SuUtils;

public class Killer {

    private static int killerMode = 1;
    private static int killLevel = 1;

    private static boolean checkAccess() {
        killerMode = App.settings.getInt(App.KILLER_MODE, 1);
        killLevel = App.settings.getInt(App.KILLER_LEVEL, 1);
        if (killerMode == 1)
            return SuUtils.checkSU();
        if (killerMode == 2)
            return ShizukuUtils.checkShizuku();
        else if (killerMode == 3)
            return ShizukuPlusUtils.checkShizuku();
        return false;
    }

    public static boolean killListOfApps(List<AppInfo> appList) {
        if (!checkAccess())
            return false;

        if (killerMode == 1) {
            int result = SuUtils.killListOfApps(appList, killLevel);
            if (result == 0)
                return true;
            else if (result == 1) {
                selfDestruct();
                return true;
            } else
                return false;
        } else if (killerMode == 2) {
            int result = ShizukuUtils.killListOfApps(appList, killLevel);
            if (result == 0)
                return true;
            else if (result == 1) {
                selfDestruct();
                return true;
            } else
                return false;
        } else if (killerMode == 3) {
            int result = ShizukuPlusUtils.killListOfApps(appList, killLevel);
            if (result == 0)
                return true;
            else if (result == 1) {
                selfDestruct();
                return true;
            } else
                return false;
        } else
            return false;
    }

    public static boolean killApp(String pkgName) {
        // "self" ahora es el paquete de CodeHub: el usuario puede pedir cerrar
        // CodeHub desde la lista; el comportamiento upstream (suicidio) se
        // conserva para esa petición explícita.
        if (App.context.getPackageName().equals(pkgName))
            selfDestruct();

        if (!checkAccess())
            return false;

        if (killerMode == 1)
            return SuUtils.killApp(pkgName, killLevel);

        else if (killerMode == 2)
            return ShizukuUtils.killApp(pkgName, killLevel);

        else if (killerMode == 3)
            return ShizukuPlusUtils.killApp(pkgName, killLevel);

        else
            return false;
    }

    private static void selfDestruct() {
        try {
            ActivityManager activityManager = (ActivityManager) App.context.getSystemService(Context.ACTIVITY_SERVICE);
            if (activityManager != null) {
                List<ActivityManager.AppTask> appTasks = activityManager.getAppTasks();
                if (appTasks != null) {
                    for (ActivityManager.AppTask task : appTasks) {
                        task.finishAndRemoveTask();
                    }
                }
            }
        } finally {
            android.os.Process.killProcess(android.os.Process.myPid());
        }
    }
}
