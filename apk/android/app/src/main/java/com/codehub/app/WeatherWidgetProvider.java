package com.codehub.app;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.SystemClock;
import android.widget.RemoteViews;

import org.json.JSONObject;

import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Widget de clima de CodeHub para la pantalla de inicio.
 *
 * Reutiliza la misma ubicación (lat/lon) que ya guarda CodeHubSyncService
 * en SharedPreferences("codehub") cada ~15 min, y consulta Open-Meteo
 * directamente (mismo proveedor que usa el backend en
 * backend/clima/fetch.js) — así el widget funciona aunque la app/WebView
 * no esté abierta.
 *
 * Pinta primero con el último dato guardado en caché (instantáneo, sin
 * parpadeo en blanco) y dispara en background una consulta fresca que
 * actualiza el RemoteViews cuando llega.
 */
public class WeatherWidgetProvider extends AppWidgetProvider {

    private static final String PREFS = "codehub";
    private static final String ACTION_REFRESH = "com.codehub.app.WIDGET_REFRESH";
    private static final long ALARM_INTERVAL_MS = 30 * 60 * 1000; // 30 min

    @Override
    public void onUpdate(Context context, AppWidgetManager mgr, int[] appWidgetIds) {
        for (int id : appWidgetIds) {
            paintFromCache(context, mgr, id);
        }
        scheduleAlarm(context);
        refreshAllInBackground(context);
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        super.onReceive(context, intent);
        if (ACTION_REFRESH.equals(intent.getAction())) {
            refreshAllInBackground(context);
        }
    }

    @Override
    public void onEnabled(Context context) {
        scheduleAlarm(context);
    }

    @Override
    public void onDisabled(Context context) {
        try {
            AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
            if (am != null) am.cancel(refreshPendingIntent(context));
        } catch (Exception ignored) {}
    }

    // ── Utilidad pública: llamado desde CodeHubBridge/CodeHubSyncService
    //    cuando hay ubicación o clima nuevos, para no esperar al alarm. ──
    public static void requestRefresh(Context context) {
        if (!hasAnyWidget(context)) return;
        refreshAllInBackground(context);
    }

    private static boolean hasAnyWidget(Context context) {
        try {
            AppWidgetManager mgr = AppWidgetManager.getInstance(context);
            int[] ids = mgr.getAppWidgetIds(new ComponentName(context, WeatherWidgetProvider.class));
            return ids != null && ids.length > 0;
        } catch (Exception e) { return false; }
    }

    private static void scheduleAlarm(Context context) {
        try {
            AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
            if (am == null) return;
            long triggerAt = SystemClock.elapsedRealtime() + ALARM_INTERVAL_MS;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, refreshPendingIntent(context));
            } else {
                am.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, refreshPendingIntent(context));
            }
        } catch (Exception ignored) {}
    }

    private static PendingIntent refreshPendingIntent(Context context) {
        Intent intent = new Intent(context, WeatherWidgetProvider.class);
        intent.setAction(ACTION_REFRESH);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT |
            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0);
        return PendingIntent.getBroadcast(context, 0, intent, flags);
    }

    // ── Pintado instantáneo con la última copia guardada ──
    private void paintFromCache(Context context, AppWidgetManager mgr, int widgetId) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        RemoteViews views = buildViews(context,
            prefs.getString("widget_city", "CodeHub"),
            prefs.getFloat("widget_temp", Float.NaN),
            prefs.getInt("widget_wcode", -1),
            prefs.getLong("widget_updated_at", 0));
        mgr.updateAppWidget(widgetId, views);
    }

    private static void refreshAllInBackground(final Context context) {
        if (!hasAnyWidget(context)) return;
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
                    double lat = Double.longBitsToDouble(prefs.getLong("lat_bits", 0));
                    double lon = Double.longBitsToDouble(prefs.getLong("lon_bits", 0));
                    if (lat == 0 && lon == 0) return; // sin ubicación aún

                    URL url = new URL("https://api.open-meteo.com/v1/forecast?latitude=" + lat +
                        "&longitude=" + lon +
                        "&current=temperature_2m,weather_code&timezone=auto");
                    HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                    conn.setConnectTimeout(10000);
                    conn.setReadTimeout(10000);
                    int code = conn.getResponseCode();
                    if (code != 200) { conn.disconnect(); return; }

                    java.io.InputStream is = conn.getInputStream();
                    java.util.Scanner sc = new java.util.Scanner(is, "UTF-8").useDelimiter("\\A");
                    String body = sc.hasNext() ? sc.next() : "{}";
                    sc.close();
                    conn.disconnect();

                    JSONObject json = new JSONObject(body);
                    JSONObject current = json.optJSONObject("current");
                    if (current == null) return;
                    float temp = (float) current.optDouble("temperature_2m", Double.NaN);
                    int wcode = current.optInt("weather_code", -1);
                    String city = prefs.getString("widget_city", "");
                    // El nombre de ciudad lo pone el bridge (saveLocation con
                    // ciudad) cuando el usuario la elige en el mapa — el
                    // widget no vuelve a geocodificar para no duplicar
                    // llamadas a Nominatim en segundo plano.

                    prefs.edit()
                        .putFloat("widget_temp", temp)
                        .putInt("widget_wcode", wcode)
                        .putLong("widget_updated_at", System.currentTimeMillis())
                        .apply();

                    AppWidgetManager mgr = AppWidgetManager.getInstance(context);
                    int[] ids = mgr.getAppWidgetIds(new ComponentName(context, WeatherWidgetProvider.class));
                    RemoteViews views = new WeatherWidgetProvider().buildViews(context, city, temp, wcode, System.currentTimeMillis());
                    for (int id : ids) mgr.updateAppWidget(id, views);
                } catch (Exception ignored) {}
            }
        }).start();
    }

    private RemoteViews buildViews(Context context, String city, float temp, int wcode, long updatedAt) {
        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_weather);

        views.setTextViewText(R.id.widget_city, (city == null || city.isEmpty()) ? "CodeHub" : city);
        views.setTextViewText(R.id.widget_temp, Float.isNaN(temp) ? "--°" : Math.round(temp) + "°");
        views.setTextViewText(R.id.widget_icon, emojiFor(wcode));
        views.setTextViewText(R.id.widget_condition, labelFor(wcode));

        if (updatedAt > 0) {
            String time = new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date(updatedAt));
            views.setTextViewText(R.id.widget_condition, labelFor(wcode) + " · " + time);
        }

        // Tocar el widget abre la app en la sección de clima — reutiliza
        // el mismo extra "open_url" que ya procesa MainActivity.handleIntent()
        // para las notificaciones push de clima (sw.js usa la misma ruta).
        Intent openIntent = new Intent(context, MainActivity.class);
        openIntent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        openIntent.putExtra("open_url", "https://wilson360-labs.vercel.app/index.html#weather-section");
        int openFlags = PendingIntent.FLAG_UPDATE_CURRENT |
            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0);
        PendingIntent openPending = PendingIntent.getActivity(context, 100, openIntent, openFlags);
        views.setOnClickPendingIntent(R.id.widget_root, openPending);

        // Botón de refrescar manual.
        views.setOnClickPendingIntent(R.id.widget_refresh, refreshPendingIntent(context));

        return views;
    }

    // Códigos WMO de Open-Meteo → emoji + etiqueta corta en español.
    private static String emojiFor(int wcode) {
        if (wcode < 0) return "🌤️";
        if (wcode == 0) return "☀️";
        if (wcode <= 2) return "🌤️";
        if (wcode == 3) return "☁️";
        if (wcode == 45 || wcode == 48) return "🌫️";
        if (wcode >= 51 && wcode <= 57) return "🌦️";
        if (wcode >= 61 && wcode <= 67) return "🌧️";
        if (wcode >= 71 && wcode <= 77) return "🌨️";
        if (wcode >= 80 && wcode <= 82) return "🌧️";
        if (wcode >= 85 && wcode <= 86) return "🌨️";
        if (wcode >= 95) return "⛈️";
        return "🌤️";
    }

    private static String labelFor(int wcode) {
        if (wcode < 0) return "Cargando…";
        if (wcode == 0) return "Despejado";
        if (wcode <= 2) return "Parcialmente nublado";
        if (wcode == 3) return "Nublado";
        if (wcode == 45 || wcode == 48) return "Neblina";
        if (wcode >= 51 && wcode <= 57) return "Llovizna";
        if (wcode >= 61 && wcode <= 67) return "Lluvia";
        if (wcode >= 71 && wcode <= 77) return "Nieve";
        if (wcode >= 80 && wcode <= 82) return "Chubascos";
        if (wcode >= 85 && wcode <= 86) return "Chubascos de nieve";
        if (wcode >= 95) return "Tormenta";
        return "—";
    }
}
