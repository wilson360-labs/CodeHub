package com.codehub.app;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Environment;
import android.os.PowerManager;
import android.os.StatFs;
import android.os.SystemClock;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.FileReader;

/** Única fuente de métricas del dispositivo: panel web y widget del launcher. */
public final class DeviceMetrics {

    private static long sCpuLastTotal = -1;
    private static long sCpuLastIdle = -1;

    private DeviceMetrics() {}

    public static JSONObject snapshot(Context context) {
        JSONObject o = new JSONObject();
        try {
            Intent batteryStatus = context.registerReceiver(null,
                new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            int level = -1, scale = -1, status = -1;
            boolean plugged = false;
            if (batteryStatus != null) {
                level = batteryStatus.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
                scale = batteryStatus.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
                status = batteryStatus.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
                plugged = batteryStatus.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0;
            }
            int pct = (level >= 0 && scale > 0) ? Math.round(level * 100f / scale) : -1;
            boolean charging = status == BatteryManager.BATTERY_STATUS_CHARGING
                || status == BatteryManager.BATTERY_STATUS_FULL;
            o.put("batteryPercent", pct);
            o.put("batteryCharging", charging);
            o.put("batteryPlugged", plugged);

            int tempRaw = batteryStatus != null
                ? batteryStatus.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) : -1;
            o.put("batteryTempC", tempRaw > 0 ? Math.round(tempRaw / 10f) : -1);

            o.put("cpuPercent", readCpuPercent());
            o.put("cpuCores", Runtime.getRuntime().availableProcessors());

            android.util.DisplayMetrics dm = context.getResources().getDisplayMetrics();
            if (dm != null) {
                o.put("screenWidthPx", dm.widthPixels);
                o.put("screenHeightPx", dm.heightPixels);
                o.put("screenDensity", dm.densityDpi + "dpi");
            }

            o.put("uptimeSeconds", SystemClock.uptimeMillis() / 1000L);

            ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            if (am != null) {
                ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
                am.getMemoryInfo(mi);
                o.put("ramTotalBytes", mi.totalMem);
                o.put("ramFreeBytes", mi.availMem);
            }

            try {
                StatFs sf = new StatFs(Environment.getDataDirectory().getPath());
                o.put("storageTotalBytes", sf.getTotalBytes());
                o.put("storageFreeBytes", sf.getAvailableBytes());
            } catch (Exception ignored) {}

            ConnectivityManager cm =
                (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            String network = "none";
            if (cm != null) {
                NetworkInfo ni = cm.getActiveNetworkInfo();
                if (ni != null && ni.isConnected()) {
                    int t = ni.getType();
                    if (t == ConnectivityManager.TYPE_WIFI) network = "wifi";
                    else if (t == ConnectivityManager.TYPE_MOBILE) network = "mobile";
                    else network = "other";
                }
            }
            o.put("network", network);

            o.put("ignoringBatteryOptimizations", isIgnoringBatteryOptimizations(context));
            o.put("versionName", versionName(context));
            o.put("versionCode", versionCode(context));
            o.put("deviceModel", Build.MANUFACTURER + " " + Build.MODEL);
            o.put("androidVersion", Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")");
        } catch (Exception ignored) {}
        return o;
    }

    public static boolean isIgnoringBatteryOptimizations(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true;
        try {
            PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            return pm != null && pm.isIgnoringBatteryOptimizations(context.getPackageName());
        } catch (Exception e) {
            return false;
        }
    }

    public static String versionName(Context context) {
        try {
            PackageManager pm = context.getPackageManager();
            return pm.getPackageInfo(context.getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "1.0.0";
        }
    }

    public static int versionCode(Context context) {
        try {
            PackageManager pm = context.getPackageManager();
            return pm.getPackageInfo(context.getPackageName(), 0).versionCode;
        } catch (Exception e) {
            return 1;
        }
    }

    /** Delta de ticks idle/total en /proc/stat. La primera llamada solo toma muestra. */
    private static double readCpuPercent() {
        try (BufferedReader r = new BufferedReader(new FileReader("/proc/stat"))) {
            String line = r.readLine();
            if (line == null || !line.startsWith("cpu")) return -1;
            String[] parts = line.trim().split("\\s+");
            long idle = 0, total = 0;
            for (int i = 1; i < parts.length; i++) {
                long v = Long.parseLong(parts[i]);
                total += v;
                if (i == 4 || i == 5) idle += v;
            }
            if (sCpuLastTotal <= 0) {
                sCpuLastTotal = total;
                sCpuLastIdle = idle;
                return -1;
            }
            long dTotal = total - sCpuLastTotal;
            long dIdle = idle - sCpuLastIdle;
            sCpuLastTotal = total;
            sCpuLastIdle = idle;
            if (dTotal <= 0) return -1;
            return Math.max(0.0, Math.min(100.0, (dTotal - dIdle) * 100.0 / dTotal));
        } catch (Exception e) {
            return -1;
        }
    }
}
