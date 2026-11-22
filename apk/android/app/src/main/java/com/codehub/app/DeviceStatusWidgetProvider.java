package com.codehub.app;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.widget.RemoteViews;

import org.json.JSONObject;

import java.util.Locale;

/** Widget del launcher con la misma fuente de métricas que el panel web. */
public class DeviceStatusWidgetProvider extends AppWidgetProvider {

    @Override
    public void onUpdate(Context context, AppWidgetManager manager, int[] appWidgetIds) {
        for (int id : appWidgetIds) {
            manager.updateAppWidget(id, buildViews(context));
        }
    }

    @Override
    public void onEnabled(Context context) {
        super.onEnabled(context);
        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        if (manager != null) {
            onUpdate(context, manager, manager.getAppWidgetIds(
                new android.content.ComponentName(context, DeviceStatusWidgetProvider.class)));
        }
    }

    private static RemoteViews buildViews(Context context) {
        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_device);
        JSONObject s = DeviceMetrics.snapshot(context);

        int battery = s.optInt("batteryPercent", -1);
        views.setTextViewText(R.id.widget_device_battery,
            battery >= 0 ? battery + "%" : "--%");
        views.setTextViewText(R.id.widget_device_charge,
            s.optBoolean("batteryCharging", false) ? "Cargando" : "Batería");

        double cpu = s.optDouble("cpuPercent", -1);
        views.setTextViewText(R.id.widget_device_cpu,
            cpu >= 0 ? Math.round(cpu) + "%" : "—");
        views.setTextViewText(R.id.widget_device_ram,
            bytes(s.optLong("ramFreeBytes", -1)) + " libres");

        long storageFree = s.optLong("storageFreeBytes", -1);
        long storageTotal = s.optLong("storageTotalBytes", -1);
        views.setTextViewText(R.id.widget_device_storage,
            storageFree >= 0 && storageTotal > 0
                ? bytes(storageFree) + " / " + bytes(storageTotal)
                : "—");

        String network = s.optString("network", "none");
        views.setTextViewText(R.id.widget_device_network, networkLabel(network));
        views.setTextViewText(R.id.widget_device_uptime,
            uptime(s.optLong("uptimeSeconds", 0)));

        views.setOnClickPendingIntent(R.id.widget_device_root, openApp(context));
        return views;
    }

    private static PendingIntent openApp(Context context) {
        Intent intent = new Intent(context, MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT
            | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0);
        return PendingIntent.getActivity(context, 220, intent, flags);
    }

    private static String bytes(long value) {
        if (value < 0) return "—";
        double mb = value / 1024.0 / 1024.0;
        return mb >= 1024
            ? String.format(Locale.US, "%.1f GB", mb / 1024.0)
            : String.format(Locale.US, "%.0f MB", mb);
    }

    private static String networkLabel(String network) {
        if ("wifi".equals(network)) return "Wi-Fi";
        if ("mobile".equals(network)) return "Datos";
        if ("other".equals(network)) return "Otra";
        return "Sin conexión";
    }

    private static String uptime(long seconds) {
        if (seconds <= 0) return "Encendido —";
        long hours = seconds / 3600;
        long minutes = (seconds % 3600) / 60;
        return hours > 0
            ? "Encendido " + hours + " h " + minutes + " min"
            : "Encendido " + minutes + " min";
    }
}
