package com.codehub.app;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import android.widget.RemoteViews;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Widget de clima de CodeHub para el launcher — lenguaje AMOLED de la app.
 *
 * Reutiliza la ubicación (lat/lon) que guarda CodeHubSyncWorker en
 * SharedPreferences("codehub") y consulta Open-Meteo directamente.
 *
 * Composición:
 *  - Escena vectorial dibujada en Canvas (lago del Inicio, sol/luna y
 *    señales de lluvia/nieve/niebla) adaptada al tamaño real del widget.
 *  - Ciudad + hora, temperatura grande, condición y sensación.
 *  - Franja de 4 horas y pronóstico de 3 días con glifos vectoriales.
 *  - Detalles (humedad · viento · lluvia) y "actualizado hace…".
 *  - Negro puro, texto hueso #F4ECE1, acentos terracota/salvia. Sin
 *    degradados decorativos, glows ni emoji.
 *
 * El widget se adapta a su altura: COMPACTO (ciudad + hero + footer),
 * MEDIO (+ franja horaria y detalles) o COMPLETO (+ 3 días). Pinta
 * primero con la caché y luego refresca en segundo plano.
 */
public class WeatherWidgetProvider extends AppWidgetProvider {

    private static final String PREFS = "codehub";
    private static final String ACTION_REFRESH = "com.codehub.app.WIDGET_REFRESH";
    private static final String ACTION_CLOCK_TICK = "com.codehub.app.WIDGET_CLOCK_TICK";
    private static final long ALARM_INTERVAL_MS = 30 * 60 * 1000; // 30 min
    private static final long CLOCK_TICK_MS = 60 * 1000; // reloj en vivo, cada minuto

    /** Single-flight: evita solapar threads de refresh (onUpdate + requestRefresh
     *  + alarma pueden dispararse casi a la vez y lanzar varias consultas HTTP). */
    private static final AtomicBoolean sRefreshing = new AtomicBoolean(false);

    private static final int MODE_COMPACT = 0;
    private static final int MODE_MEDIUM = 1;
    private static final int MODE_FULL = 2;

    private static final int[] H_TIME = {
        R.id.widget_h1_time, R.id.widget_h2_time, R.id.widget_h3_time, R.id.widget_h4_time
    };
    private static final int[] H_ICON = {
        R.id.widget_h1_icon, R.id.widget_h2_icon, R.id.widget_h3_icon, R.id.widget_h4_icon
    };
    private static final int[] H_TEMP = {
        R.id.widget_h1_temp, R.id.widget_h2_temp, R.id.widget_h3_temp, R.id.widget_h4_temp
    };
    private static final int[] D_DAY = {
        R.id.widget_d1_day, R.id.widget_d2_day, R.id.widget_d3_day
    };
    private static final int[] D_ICON = {
        R.id.widget_d1_icon, R.id.widget_d2_icon, R.id.widget_d3_icon
    };
    private static final int[] D_TEMP = {
        R.id.widget_d1_temp, R.id.widget_d2_temp, R.id.widget_d3_temp
    };

    @Override
    public void onUpdate(Context context, AppWidgetManager mgr, int[] appWidgetIds) {
        for (int id : appWidgetIds) {
            paintFromCache(context, mgr, id);
        }
        scheduleAlarm(context);
        scheduleClockTick(context);
        refreshAllInBackground(context);
    }

    @Override
    public void onAppWidgetOptionsChanged(Context context, AppWidgetManager mgr,
                                          int appWidgetId, Bundle newOptions) {
        // Al redimensionar cambia el modo (compacto/medio/completo).
        paintFromCache(context, mgr, appWidgetId);
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        super.onReceive(context, intent);
        String action = intent == null ? "" : intent.getAction();
        if (ACTION_REFRESH.equals(action)) {
            refreshAllInBackground(context);
        } else if (ACTION_CLOCK_TICK.equals(action)) {
            // Reloj en vivo: solo repinta la hora, sin tocar los datos del clima.
            paintClockOnly(context);
        }
    }

    @Override
    public void onEnabled(Context context) {
        scheduleAlarm(context);
        scheduleClockTick(context);
    }

    @Override
    public void onDisabled(Context context) {
        try {
            AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
            if (am != null) {
                am.cancel(refreshPendingIntent(context));
                am.cancel(clockTickPendingIntent(context));
            }
        } catch (Exception ignored) {}
    }

    // ── Utilidad pública: llamado desde CodeHubBridge/CodeHubSyncWorker
    //    cuando hay ubicación o clima nuevos, para no esperar al alarm. ──
    public static void requestRefresh(Context context) {
        if (!hasAnyWidget(context)) return;
        refreshAllInBackground(context);
    }

    /** Re-arms widget alarms after Android force-stops and later restarts CodeHub. */
    public static void resumeUpdates(Context context) {
        if (!hasAnyWidget(context)) return;
        scheduleAlarm(context);
        scheduleClockTick(context);
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
        return PendingIntent.getBroadcast(context, 1, intent, flags);
    }

    // Reloj en vivo: alarm cada 60s (RTC, hora real del dispositivo) que
    // solo repinta el label de hora para que "corra" sin esperar al
    // refresh del clima (que es cada 30 min).
    private static void scheduleClockTick(Context context) {
        try {
            AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
            if (am == null) return;
            long triggerAt = System.currentTimeMillis() + CLOCK_TICK_MS;
            PendingIntent pi = clockTickPendingIntent(context);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                am.setAndAllowWhileIdle(AlarmManager.RTC, triggerAt, pi);
            } else {
                am.setRepeating(AlarmManager.RTC, triggerAt, CLOCK_TICK_MS, pi);
            }
            // Con setAndAllowWhileIdle solo se dispara una vez; se reprograma
            // en cada tick para seguir corriendo (más amable con batería que
            // setRepeating + menos preciso).
        } catch (Exception ignored) {}
    }

    private static PendingIntent clockTickPendingIntent(Context context) {
        Intent intent = new Intent(context, WeatherWidgetProvider.class);
        intent.setAction(ACTION_CLOCK_TICK);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT |
            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0);
        return PendingIntent.getBroadcast(context, 2, intent, flags);
    }

    private static void paintClockOnly(Context context) {
        // partiallyUpdateAppWidget solo existe desde API 31; en APIs viejas
        // un RemoteViews parcial borraría el widget. Allí el reloj se
        // actualiza con el refresco normal (cada 30 min).
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return;
        try {
            String time = new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date(System.currentTimeMillis()));
            if (hasAnyWidget(context)) {
                AppWidgetManager mgr = AppWidgetManager.getInstance(context);
                int[] ids = mgr.getAppWidgetIds(new ComponentName(context, WeatherWidgetProvider.class));
                RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_weather);
                views.setTextViewText(R.id.widget_updated, time);
                for (int id : ids) safePartialUpdate(context, mgr, id, views);
                // Reprogramar para el siguiente minuto.
                scheduleClockTick(context);
            }
        } catch (Exception ignored) {}
    }

    // ── Pintado instantáneo con la última copia guardada ──
    private static void paintFromCache(Context context, AppWidgetManager mgr, int widgetId) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        RemoteViews views = buildViews(context, widgetId, modeFor(context, widgetId),
            prefs.getString("widget_city", "CodeHub"),
            prefs.getFloat("widget_temp", Float.NaN),
            prefs.getInt("widget_wcode", -1),
            prefs.getFloat("widget_feels", Float.NaN),
            prefs.getInt("widget_humidity", -1),
            prefs.getFloat("widget_wind", Float.NaN),
            prefs.getFloat("widget_gust", Float.NaN),
            prefs.getFloat("widget_pressure", Float.NaN),
            prefs.getInt("widget_rain", -1),
            prefs.getFloat("widget_uv", Float.NaN),
            prefs.getString("widget_sunrise", ""),
            prefs.getString("widget_sunset", ""),
            prefs.getString("widget_fc1", ""),
            prefs.getString("widget_fc2", ""),
            prefs.getString("widget_fc3", ""),
            prefs.getString("widget_hourly", ""),
            prefs.getLong("widget_updated_at", 0));
        mgr.updateAppWidget(widgetId, views);
    }

    // Muestra/oculta el spinner de "refrescando" en todos los widgets,
    // para dar feedback visual de que el refresco está en curso.
    private static void showLoading(final Context context, final boolean loading) {
        if (!hasAnyWidget(context)) return;
        // En APIs < 31 no hay partial update: un RemoteViews con solo el
        // spinner borraría el resto del widget. Se omite el feedback.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return;
        try {
            AppWidgetManager mgr = AppWidgetManager.getInstance(context);
            int[] ids = mgr.getAppWidgetIds(new ComponentName(context, WeatherWidgetProvider.class));
            for (int id : ids) {
                RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_weather);
                views.setViewVisibility(R.id.widget_refresh, loading ? View.GONE : View.VISIBLE);
                views.setViewVisibility(R.id.widget_refresh_progress, loading ? View.VISIBLE : View.GONE);
                safePartialUpdate(context, mgr, id, views);
            }
        } catch (Exception ignored) {}
    }

    // OJO: partiallyUpdateAppWidget SOLO existe desde API 31. En minSdk=24 una
    // llamada directa lanza NoSuchMethodError (no Exception), no se captura y
    // crashea el onReceive en Android 7-11. En APIs viejas se repinta completo.
    private static void safePartialUpdate(Context context, AppWidgetManager mgr, int id, RemoteViews views) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try { mgr.partiallyUpdateAppWidget(id, views); } catch (Exception ignored) { mgr.updateAppWidget(id, views); }
        } else {
            mgr.updateAppWidget(id, views);
        }
    }

    // Sin ubicación guardada: pinta un estado informativo en lugar de quedarse
    // con "--°" y la carga eterna. El botón de refrescar vuelve a aparecer.
    private static void paintNoLocation(Context context) {
        if (!hasAnyWidget(context)) return;
        try {
            AppWidgetManager mgr = AppWidgetManager.getInstance(context);
            int[] ids = mgr.getAppWidgetIds(new ComponentName(context, WeatherWidgetProvider.class));
            for (int id : ids) {
                RemoteViews views = buildViews(context, id, modeFor(context, id),
                    "Configura tu ciudad", Float.NaN, -1,
                    Float.NaN, -1, Float.NaN, Float.NaN, Float.NaN,
                    -1, Float.NaN, "", "", "", "", "", "", 0);
                views.setTextViewText(R.id.widget_condition, "Abre CodeHub y añade tu ubicación");
                mgr.updateAppWidget(id, views);
            }
        } catch (Exception ignored) {}
    }

    private static void refreshAllInBackground(final Context context) {
        if (!hasAnyWidget(context)) return;
        // Single-flight: si ya hay un refresh en curso, este se descarta. Evita
        // solapar peticiones HTTP cuando el onUpdate, el botón manual, la alarma
        // o requestRefresh() coinciden en el tiempo.
        if (!sRefreshing.compareAndSet(false, true)) return;
        // Feedback visual: mostrar spinner en el botón de refrescar.
        showLoading(context, true);
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
                    double lat = Double.longBitsToDouble(prefs.getLong("lat_bits", 0));
                    double lon = Double.longBitsToDouble(prefs.getLong("lon_bits", 0));
                    if (lat == 0 && lon == 0) {
                        // Sin ubicación guardada: en vez de quedarse mudo con
                        // "--°" y la carga eterna, se pinta un aviso de que hay
                        // que abrir la app y configurar la ciudad.
                        paintNoLocation(context);
                        return;
                    }

                    URL url = new URL("https://api.open-meteo.com/v1/forecast?latitude=" + lat +
                        "&longitude=" + lon +
                        "&current=temperature_2m,relative_humidity_2m,apparent_temperature,weather_code,wind_speed_10m,wind_gusts_10m,surface_pressure" +
                        "&hourly=temperature_2m,weather_code,precipitation_probability,uv_index" +
                        "&daily=weather_code,temperature_2m_max,temperature_2m_min,sunrise,sunset" +
                        "&forecast_days=3&timezone=auto");
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

                    float temp  = (float) current.optDouble("temperature_2m", Double.NaN);
                    float feels = (float) current.optDouble("apparent_temperature", Double.NaN);
                    int humidity = current.optInt("relative_humidity_2m", -1);
                    float wind  = (float) current.optDouble("wind_speed_10m", Double.NaN);
                    float gust  = (float) current.optDouble("wind_gusts_10m", Double.NaN);
                    float pressure = (float) current.optDouble("surface_pressure", Double.NaN);
                    int wcode = current.optInt("weather_code", -1);
                    String city = prefs.getString("widget_city", "");

                    // Lluvia (%) y UV — Open-Meteo solo los da por hora, no en
                    // "current": se toma la primera hora >= ahora (no el máximo
                    // del día) para que suba/baje como el clima real. Del mismo
                    // índice salen las 4 horas de la franja horaria.
                    int rainProb = -1;
                    float uv = Float.NaN;
                    String hourlyStr = "";
                    JSONObject hourly = json.optJSONObject("hourly");
                    if (hourly != null) {
                        JSONArray hTimes = hourly.optJSONArray("time");
                        JSONArray hTemps = hourly.optJSONArray("temperature_2m");
                        JSONArray hCodes = hourly.optJSONArray("weather_code");
                        JSONArray hRain = hourly.optJSONArray("precipitation_probability");
                        JSONArray hUv = hourly.optJSONArray("uv_index");
                        if (hTimes != null) {
                            long now = System.currentTimeMillis();
                            int idx = -1;
                            SimpleDateFormat isoFmt = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm", Locale.US);
                            // Los tiempos por hora de Open-Meteo vienen en la hora
                            // LOCAL de la ubicación (timezone=auto). Parsearlos como
                            // UTC y compararlos con "ahora" desplaza el índice en
                            // zonas != UTC (lluvia/UV la hora equivocada). Se usa el
                            // offset que devuelve la API; si no viene, se parsea con
                            // la zona local del dispositivo como aproximación.
                            double utcOffset = json.optDouble("utc_offset_seconds", Double.NaN);
                            boolean hasOffset = !Double.isNaN(utcOffset);
                            if (hasOffset) {
                                isoFmt.setTimeZone(TimeZone.getTimeZone("UTC"));
                            } else {
                                isoFmt.setTimeZone(TimeZone.getDefault());
                            }
                            for (int i = 0; i < hTimes.length(); i++) {
                                try {
                                    Date t = isoFmt.parse(hTimes.optString(i, ""));
                                    if (t == null) continue;
                                    long tMs = t.getTime() - (long) Math.round(hasOffset ? utcOffset * 1000.0 : 0.0);
                                    if (tMs >= now) { idx = i; break; }
                                } catch (Exception ignored) {}
                            }
                            if (idx < 0) idx = hTimes.length() - 1;
                            if (idx >= 0) {
                                if (hRain != null) rainProb = hRain.optInt(idx, -1);
                                if (hUv != null) uv = (float) hUv.optDouble(idx, Double.NaN);
                                if (hTemps != null && hCodes != null) {
                                    hourlyStr = buildHourly(hTimes, hTemps, hCodes, idx);
                                }
                            }
                        }
                    }

                    // Amanecer/atardecer — ya vienen en hora local de la
                    // ubicación consultada (timezone=auto), así que se toma
                    // directo el trozo "HH:mm" del ISO sin convertir zona.
                    String sunrise = "", sunset = "";
                    JSONObject daily = json.optJSONObject("daily");
                    if (daily != null) {
                        JSONArray dSunrise = daily.optJSONArray("sunrise");
                        JSONArray dSunset = daily.optJSONArray("sunset");
                        sunrise = isoTime(dSunrise != null ? dSunrise.optString(0, "") : "");
                        sunset  = isoTime(dSunset != null ? dSunset.optString(0, "") : "");
                    }

                    // Pronóstico de 3 días (Hoy/Mar/Mié… / emoji / 24° / 15°).
                    String fc1 = "", fc2 = "", fc3 = "";
                    if (daily != null) {
                        JSONArray dTempMax = daily.optJSONArray("temperature_2m_max");
                        JSONArray dTempMin = daily.optJSONArray("temperature_2m_min");
                        JSONArray dCode = daily.optJSONArray("weather_code");
                        if (dTempMax != null && dTempMin != null && dCode != null) {
                            fc1 = forecastCell(dTempMax, dTempMin, dCode, 0);
                            fc2 = forecastCell(dTempMax, dTempMin, dCode, 1);
                            fc3 = forecastCell(dTempMax, dTempMin, dCode, 2);
                        }
                    }

                    long now2 = System.currentTimeMillis();
                    prefs.edit()
                        .putFloat("widget_temp", temp)
                        .putInt("widget_wcode", wcode)
                        .putFloat("widget_feels", feels)
                        .putInt("widget_humidity", humidity)
                        .putFloat("widget_wind", wind)
                        .putFloat("widget_gust", gust)
                        .putFloat("widget_pressure", pressure)
                        .putInt("widget_rain", rainProb)
                        .putFloat("widget_uv", uv)
                        .putString("widget_sunrise", sunrise)
                        .putString("widget_sunset", sunset)
                        .putString("widget_fc1", fc1)
                        .putString("widget_fc2", fc2)
                        .putString("widget_fc3", fc3)
                        .putString("widget_hourly", hourlyStr)
                        .putLong("widget_updated_at", now2)
                        .apply();

                    AppWidgetManager mgr = AppWidgetManager.getInstance(context);
                    int[] ids = mgr.getAppWidgetIds(new ComponentName(context, WeatherWidgetProvider.class));
                    for (int id : ids) {
                        RemoteViews views = buildViews(context, id, modeFor(context, id),
                            city, temp, wcode, feels, humidity, wind, gust, pressure,
                            rainProb, uv, sunrise, sunset, fc1, fc2, fc3, hourlyStr, now2);
                        mgr.updateAppWidget(id, views);
                    }
                } catch (Exception ignored) {
                } finally {
                    // Liberar single-flight + quitar el spinner al terminar.
                    sRefreshing.set(false);
                    showLoading(context, false);
                    // Rearmar la alarma: setAndAllowWhileIdle es de un solo
                    // disparo, así que se reprograma tras cada refresco.
                    scheduleAlarm(context);
                }
            }
        }).start();
    }

    // Extrae "HH:mm" de un timestamp ISO de Open-Meteo (p.ej.
    // "2026-09-03T06:02"). Ya viene en hora local de la ubicación
    // consultada (timezone=auto), así que es un simple recorte de
    // texto — no hay que convertir zona horaria.
    private static String isoTime(String iso) {
        if (iso == null || iso.length() < 16) return "";
        return iso.substring(11, 16);
    }

    // ── Día/noche REAL: usa las horas de salida/puesta del sol de la
    //    ubicación (SS "HH:mm"). Sin datos → estimación por hora (6–19h).
    private static boolean isDaylightNow(String sunrise, String sunset) {
        int rise = parseHM(sunrise), set = parseHM(sunset);
        if (rise < 0 || set < 0) {
            int hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
            return hour >= 6 && hour < 19;
        }
        int now = minutesNow();
        return rise <= set && now >= rise && now < set
            || rise > set && (now >= rise || now < set);
    }

    private static int minutesNow() {
        Calendar c = Calendar.getInstance();
        return c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE);
    }

    private static int parseHM(String s) {
        if (s == null || s.length() < 5) return -1;
        try {
            return Integer.parseInt(s.substring(0, 2)) * 60 + Integer.parseInt(s.substring(3, 5));
        } catch (Exception e) { return -1; }
    }

    // ── Franja horaria: "Ahora|24|1;17:00|23|2;…" (hora|temp|wcode). ──
    private static String buildHourly(JSONArray times, JSONArray temps, JSONArray codes, int idx) {
        StringBuilder sb = new StringBuilder();
        for (int k = 0; k < 4; k++) {
            int i = idx + k;
            if (i < 0 || i >= times.length()) break;
            String t = times.optString(i, "");
            if (t.length() >= 16) t = t.substring(11, 16);
            if (k == 0) t = "Ahora";
            double tv = temps.optDouble(i, Double.NaN);
            String temp = Double.isNaN(tv) ? "--" : String.valueOf(Math.round(tv));
            int code = codes.optInt(i, -1);
            if (sb.length() > 0) sb.append(';');
            sb.append(t).append('|').append(temp).append('|').append(code);
        }
        return sb.toString();
    }

    // ── Pronóstico de 3 días: "Hoy|code|26° / 15°". ──
    private static String forecastCell(JSONArray max, JSONArray min, JSONArray code, int idx) {
        try {
            Calendar c = Calendar.getInstance();
            c.add(Calendar.DAY_OF_YEAR, idx);
            String day = idx == 0
                ? "Hoy"
                : capitalize(new SimpleDateFormat("EEE", Locale.getDefault()).format(c.getTime()));
            float hi = (float) max.getDouble(idx);
            float lo = (float) min.getDouble(idx);
            return day + "|" + code.optInt(idx, -1) + "|" + Math.round(hi) + "° / " + Math.round(lo) + "°";
        } catch (Exception e) { return "—"; }
    }

    private static String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    // ── Construcción del RemoteViews (una por widget, según su tamaño). ──
    private static RemoteViews buildViews(Context context, int widgetId, int mode,
                                          String city, float temp, int wcode,
                                          float feels, int humidity, float wind, float gust, float pressure,
                                          int rainProb, float uv, String sunrise, String sunset,
                                          String fc1, String fc2, String fc3, String hourly,
                                          long updatedAt) {
        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_weather);

        boolean hasData = !Float.isNaN(temp) && wcode >= 0;
        boolean hasSun = sunrise != null && !sunrise.isEmpty() && sunset != null && !sunset.isEmpty();
        boolean night = !isDaylightNow(hasSun ? sunrise : null, hasSun ? sunset : null);

        views.setTextViewText(R.id.widget_city, (city == null || city.isEmpty()) ? "CodeHub" : city);
        views.setTextViewText(R.id.widget_temp, Float.isNaN(temp) ? "--°" : Math.round(temp) + "°");
        views.setTextViewText(R.id.widget_condition, labelFor(wcode));
        views.setTextViewText(R.id.widget_feels,
            Float.isNaN(feels) ? " " : "Sensación " + Math.round(feels) + "°");

        fillHourly(views, hourly, night);
        fillDaily(views, fc1, fc2, fc3, night);

        // Detalles: humedad · viento · lluvia (lo más útil, sin saturar).
        StringBuilder det = new StringBuilder();
        if (humidity >= 0) det.append("Humedad ").append(humidity).append('%');
        if (!Float.isNaN(wind)) {
            if (det.length() > 0) det.append("  ·  ");
            det.append("Viento ").append(Math.round(wind)).append(" km/h");
        }
        if (rainProb >= 0) {
            if (det.length() > 0) det.append("  ·  ");
            det.append("Lluvia ").append(rainProb).append('%');
        }
        views.setTextViewText(R.id.widget_details, det.length() == 0 ? " " : det.toString());

        // Footer: cronómetro vivo desde la última actualización.
        views.setChronometer(R.id.widget_updated2,
            SystemClock.elapsedRealtime() - Math.max(0, System.currentTimeMillis() - updatedAt),
            "Actualizado hace %s", updatedAt > 0);
        views.setViewVisibility(R.id.widget_updated2, updatedAt > 0 ? View.VISIBLE : View.GONE);
        // Header: hora local actual (la mantiene el tick de AlarmManager).
        views.setTextViewText(R.id.widget_updated,
            new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date(System.currentTimeMillis())));

        applyTextColors(views, night);

        // Modos: en compacto solo caben header + hero + footer.
        boolean showHourly = hasData && mode >= MODE_MEDIUM;
        boolean showDaily = hasData && mode >= MODE_FULL;
        boolean showDetails = hasData && mode >= MODE_MEDIUM;
        views.setViewVisibility(R.id.widget_hourly, showHourly ? View.VISIBLE : View.GONE);
        views.setViewVisibility(R.id.widget_daily, showDaily ? View.VISIBLE : View.GONE);
        views.setViewVisibility(R.id.widget_details, showDetails ? View.VISIBLE : View.GONE);

        // Escena de fondo adaptada al tamaño real del widget.
        views.setImageViewBitmap(R.id.widget_bg_image, backgroundBitmap(context, widgetId, wcode, night));

        // Tocar el widget abre la app en la sección de clima.
        Intent openIntent = new Intent(context, MainActivity.class);
        openIntent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        openIntent.putExtra("open_weather_native", true);
        int openFlags = PendingIntent.FLAG_UPDATE_CURRENT |
            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0);
        PendingIntent openPending = PendingIntent.getActivity(context, 100, openIntent, openFlags);
        views.setOnClickPendingIntent(R.id.widget_root, openPending);

        // Botón de refrescar manual (glifo ↻ en el footer).
        views.setOnClickPendingIntent(R.id.widget_refresh, refreshPendingIntent(context));

        return views;
    }

    private static void applyTextColors(RemoteViews views, boolean night) {
        int primary = 0xFFF4ECE1;
        int secondary = 0xFFC2B6A8;
        int accent = 0xFFD98A67;
        views.setTextColor(R.id.widget_city, primary);
        views.setTextColor(R.id.widget_updated, secondary);
        views.setTextColor(R.id.widget_temp, primary);
        views.setTextColor(R.id.widget_condition, primary);
        views.setTextColor(R.id.widget_feels, secondary);
        views.setTextColor(R.id.widget_details, secondary);
        views.setTextColor(R.id.widget_updated2, secondary);
        views.setInt(R.id.widget_refresh, "setColorFilter", accent);
        for (int id : H_TIME) views.setTextColor(id, secondary);
        for (int id : H_TEMP) views.setTextColor(id, primary);
        for (int id : D_DAY) views.setTextColor(id, secondary);
        for (int id : D_TEMP) views.setTextColor(id, primary);
    }

    // ── Franja horaria: glifo vectorial teñido, sin emoji. ──
    private static void fillHourly(RemoteViews views, String hourly, boolean night) {
        String[] slots = (hourly == null || hourly.isEmpty()) ? new String[0] : hourly.split(";");
        for (int k = 0; k < 4; k++) {
            String t = "--:--", temp = "--", code = "-1";
            if (k < slots.length) {
                String[] p = slots[k].split("\\|");
                if (p.length >= 3) { t = p[0]; temp = p[1]; code = p[2]; }
            }
            int codeInt = parseCode(code);
            views.setTextViewText(H_TIME[k], t);
            views.setImageViewResource(H_ICON[k], iconFor(codeInt, night));
            views.setInt(H_ICON[k], "setColorFilter", tintFor(codeInt));
            views.setTextViewText(H_TEMP[k], "--".equals(temp) ? "--" : temp + "°");
        }
    }

    // ── Pronóstico: "Hoy|code|26° / 15°". El código viaja en vez del emoji. ──
    private static void fillDaily(RemoteViews views, String fc1, String fc2, String fc3, boolean night) {
        String[] cells = {fc1, fc2, fc3};
        for (int k = 0; k < 3; k++) {
            String day = "--", code = "-1", temp = "--° / --°";
            String cell = cells[k] == null ? "" : cells[k];
            if (cell.contains("\n")) {
                // Entradas guardadas por versiones anteriores: "Hoy\n🌤️\n26° / 15°".
                String[] old = cell.split("\\n");
                if (old.length > 0) day = old[0];
                if (old.length > 2) temp = old[2];
            } else {
                String[] parts = cell.split("\\|");
                if (parts.length > 0 && !parts[0].isEmpty()) day = parts[0];
                if (parts.length > 1 && !parts[1].isEmpty()) code = parts[1];
                if (parts.length > 2 && !parts[2].isEmpty()) temp = parts[2];
            }
            int codeInt = parseCode(code);
            views.setTextViewText(D_DAY[k], day);
            views.setImageViewResource(D_ICON[k], iconFor(codeInt, night));
            views.setInt(D_ICON[k], "setColorFilter", tintFor(codeInt));
            views.setTextViewText(D_TEMP[k], temp);
        }
    }

    private static int parseCode(String value) {
        try { return Integer.parseInt(value); } catch (Exception e) { return -1; }
    }

    // ── Modo según la altura real del widget (dp). ──
    private static int modeFor(Context context, int widgetId) {
        try {
            Bundle o = AppWidgetManager.getInstance(context).getAppWidgetOptions(widgetId);
            int h = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0);
            if (h <= 0) h = 250;
            if (h >= 238) return MODE_FULL;
            if (h >= 192) return MODE_MEDIUM;
            return MODE_COMPACT;
        } catch (Exception e) {
            return MODE_FULL;
        }
    }

    // ── Escena de fondo: lago AMOLED + sol/luna + lluvia/nieve/niebla.
    //    Se dibuja al tamaño real del widget (en px), con tope para no
    //    rebasar el límite de transacción Binder (~1 MB): 512 px de lado
    //    y 262 144 px totales en RGB_565 (≤ 524 KB). ──
    private static Bitmap backgroundBitmap(Context context, int widgetId, int wcode, boolean night) {
        int wDp = 0, hDp = 0;
        try {
            Bundle o = AppWidgetManager.getInstance(context).getAppWidgetOptions(widgetId);
            wDp = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0);
            hDp = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0);
        } catch (Exception ignored) {}
        if (wDp <= 0 || hDp <= 0) { wDp = 250; hDp = 250; }

        float density = context.getResources().getDisplayMetrics().density;
        int w = Math.max(96, Math.round(wDp * density));
        int h = Math.max(96, Math.round(hDp * density));
        double scale = Math.min(1.0, Math.min(512.0 / Math.max(w, h),
            Math.sqrt(262144.0 / ((double) w * h))));
        w = Math.max(64, (int) Math.round(w * scale));
        h = Math.max(64, (int) Math.round(h * scale));

        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565);
        try {
            drawScene(new Canvas(bmp), w, h, wcode, night);
        } catch (Exception e) {
            bmp.eraseColor(palette(wcode, night)[1]);
        }
        return bmp;
    }

    private static void drawScene(Canvas c, int w, int h, int wcode, boolean night) {
        int[] colors = palette(wcode, night);
        Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeCap(Paint.Cap.ROUND);

        // Cielo y agua: planos. El lago del Inicio se reduce a dos franjas
        // para no competir con la lectura de la temperatura.
        float horizon = h * 0.58f;
        fill.setColor(colors[0]);
        c.drawRect(0, 0, w, horizon, fill);
        fill.setColor(colors[1]);
        c.drawRect(0, horizon, w, h, fill);

        boolean rain = (wcode >= 51 && wcode <= 67) || (wcode >= 80 && wcode <= 82);
        boolean snow = (wcode >= 71 && wcode <= 77) || (wcode >= 85 && wcode <= 86);
        boolean fog = wcode == 45 || wcode == 48;
        boolean clear = wcode < 0 || wcode == 0 || (wcode >= 1 && wcode <= 2);

        // Sol/luna sin halo: terracota de día, hueso de noche.
        if (clear) {
            float cx = w * 0.78f;
            float cy = horizon - h * 0.13f;
            float r = Math.max(3f, Math.min(w, h) * 0.075f);
            fill.setColor(night ? 0xFFF4ECE1 : 0xFFD98A67);
            c.drawCircle(cx, cy, r, fill);

            stroke.setColor(0x669DB99E);
            stroke.setStrokeWidth(Math.max(1f, h * 0.005f));
            for (int i = 0; i < 5; i++) {
                float y = horizon + (h - horizon) * (0.14f + i * 0.17f);
                float half = r * (1.1f + i * 0.35f);
                c.drawLine(cx - half, y, cx + half, y, stroke);
            }
        }

        // Señales funcionales de condición, discretas.
        if (rain || wcode >= 95) {
            stroke.setColor(0x809DB99E);
            stroke.setStrokeWidth(Math.max(1f, w * 0.004f));
            for (int i = 0; i < 10; i++) {
                float x = w * (0.60f + (i % 5) * 0.07f);
                float y = h * (0.20f + (i / 5) * 0.11f + (i % 5) * 0.012f);
                c.drawLine(x, y, x - w * 0.015f, y + h * 0.035f, stroke);
            }
        }
        if (snow) {
            fill.setColor(0xCCF4ECE1);
            for (int i = 0; i < 12; i++) {
                float x = w * (0.58f + ((i * 37) % 40) / 100f);
                float y = h * (0.18f + ((i * 53) % 34) / 100f);
                c.drawCircle(x, y, Math.max(1f, w * 0.005f), fill);
            }
        }
        if (fog) {
            stroke.setColor(0x66C2B6A8);
            stroke.setStrokeWidth(Math.max(1f, h * 0.008f));
            for (int i = 0; i < 3; i++) {
                float y = h * (0.28f + i * 0.09f);
                c.drawLine(w * 0.10f, y, w * 0.90f, y, stroke);
            }
        }

        // Juncos: cierran el lago sin textura ni movimiento.
        stroke.setColor(0x809DB99E);
        stroke.setStrokeWidth(Math.max(1f, w * 0.003f));
        for (int i = 0; i < 4; i++) {
            float x = w * (0.05f + i * 0.02f);
            c.drawLine(x, h, x + w * 0.01f, h - h * (0.08f + (i % 2) * 0.04f), stroke);
            float xr = w * (0.95f - i * 0.02f);
            c.drawLine(xr, h, xr - w * 0.01f, h - h * (0.07f + (i % 2) * 0.04f), stroke);
        }
    }

    // Lago AMOLED: cielo negro puro y agua casi negra. La condición solo
    // matiza el agua; no hay degradados decorativos ni glows.
    private static int[] palette(int wcode, boolean night) {
        boolean rain = (wcode >= 51 && wcode <= 67) || (wcode >= 80 && wcode <= 82);
        boolean storm = wcode >= 95;
        boolean snow = (wcode >= 71 && wcode <= 77) || (wcode >= 85 && wcode <= 86);
        boolean fog = wcode == 45 || wcode == 48;

        int water;
        if (storm) water = 0xFF0B0A0E;
        else if (rain) water = 0xFF080D12;
        else if (snow) water = 0xFF0B1012;
        else if (fog) water = 0xFF0A0C0D;
        else water = 0xFF080A0A;
        return new int[]{0xFF000000, water};
    }

    // Códigos WMO de Open-Meteo → drawable vectorial. El color se aplica
    // con setColorFilter desde RemoteViews, sin depender de tintes del launcher.
    private static int iconFor(int wcode, boolean night) {
        if (wcode < 0) return R.drawable.ic_wx_partly;
        if (wcode == 0) return night ? R.drawable.ic_wx_night : R.drawable.ic_wx_clear;
        if (wcode <= 2) return night ? R.drawable.ic_wx_night : R.drawable.ic_wx_partly;
        if (wcode == 3) return R.drawable.ic_wx_cloud;
        if (wcode == 45 || wcode == 48) return R.drawable.ic_wx_fog;
        if (wcode >= 95) return R.drawable.ic_wx_storm;
        if ((wcode >= 71 && wcode <= 77) || (wcode >= 85 && wcode <= 86)) return R.drawable.ic_wx_snow;
        if ((wcode >= 51 && wcode <= 57) || (wcode >= 61 && wcode <= 67)
            || (wcode >= 80 && wcode <= 82)) return R.drawable.ic_wx_rain;
        return R.drawable.ic_wx_partly;
    }

    private static int tintFor(int wcode) {
        if (wcode < 0) return 0xFFC2B6A8;
        if (wcode == 0 || wcode == 1 || wcode == 2) return 0xFFD98A67;
        if ((wcode >= 51 && wcode <= 67) || (wcode >= 80 && wcode <= 82) || wcode >= 95) {
            return 0xFF9DB99E;
        }
        return 0xFFF4ECE1;
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
