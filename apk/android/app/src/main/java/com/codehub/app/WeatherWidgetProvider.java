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
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
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
 * Widget de clima de CodeHub para el launcher — diseño One UI 8.5.
 *
 * Reutiliza la ubicación (lat/lon) que guarda CodeHubSyncWorker en
 * SharedPreferences("codehub") y consulta Open-Meteo directamente.
 *
 * Composición:
 *  - Escena dibujada en Canvas (degradado + sol/luna + nubes + lluvia/
 *    nieve/niebla/estrellas) adaptada al tamaño real del widget.
 *  - Ciudad + hora, temperatura grande, condición y sensación.
 *  - Franja de 4 horas y pronóstico de 3 días.
 *  - Detalles (humedad · viento · lluvia) y "actualizado hace…".
 *  - Texto tinta oscura de día y blanco de noche (contraste AA).
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

    // ── Pronóstico de 3 días: "Hoy\n🌤️\n26° / 15°". ──
    private static String forecastCell(JSONArray max, JSONArray min, JSONArray code, int idx) {
        try {
            Calendar c = Calendar.getInstance();
            c.add(Calendar.DAY_OF_YEAR, idx);
            String day = idx == 0
                ? "Hoy"
                : capitalize(new SimpleDateFormat("EEE", Locale.getDefault()).format(c.getTime()));
            float hi = (float) max.getDouble(idx);
            float lo = (float) min.getDouble(idx);
            String emoji = emojiFor(code.optInt(idx, -1));
            return day + "\n" + emoji + "\n" + Math.round(hi) + "° / " + Math.round(lo) + "°";
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

        fillHourly(views, hourly);
        fillDaily(views, fc1, fc2, fc3);

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
        openIntent.putExtra("open_url", "https://wilson360-labs.vercel.app/index.html#weather-section");
        int openFlags = PendingIntent.FLAG_UPDATE_CURRENT |
            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0);
        PendingIntent openPending = PendingIntent.getActivity(context, 100, openIntent, openFlags);
        views.setOnClickPendingIntent(R.id.widget_root, openPending);

        // Botón de refrescar manual (glifo ↻ en el footer).
        views.setOnClickPendingIntent(R.id.widget_refresh, refreshPendingIntent(context));

        return views;
    }

    private static void applyTextColors(RemoteViews views, boolean night) {
        int primary = night ? 0xFFFFFFFF : 0xFF0B2545;
        int secondary = night ? 0xFFC7D6EA : 0xFF16324F;
        int accent = night ? 0xFFC9E8FF : 0xFF1D4ED8;
        views.setTextColor(R.id.widget_city, primary);
        views.setTextColor(R.id.widget_updated, secondary);
        views.setTextColor(R.id.widget_temp, primary);
        views.setTextColor(R.id.widget_condition, primary);
        views.setTextColor(R.id.widget_feels, secondary);
        views.setTextColor(R.id.widget_details, secondary);
        views.setTextColor(R.id.widget_updated2, secondary);
        views.setTextColor(R.id.widget_refresh, accent);
        for (int id : H_TIME) views.setTextColor(id, secondary);
        for (int id : H_TEMP) views.setTextColor(id, primary);
        for (int id : D_DAY) views.setTextColor(id, secondary);
        for (int id : D_TEMP) views.setTextColor(id, primary);
    }

    private static void fillHourly(RemoteViews views, String hourly) {
        String[] slots = (hourly == null || hourly.isEmpty()) ? new String[0] : hourly.split(";");
        for (int k = 0; k < 4; k++) {
            String t = "--:--", temp = "--", code = "-1";
            if (k < slots.length) {
                String[] p = slots[k].split("\\|");
                if (p.length >= 3) { t = p[0]; temp = p[1]; code = p[2]; }
            }
            int codeInt = -1;
            try { codeInt = Integer.parseInt(code); } catch (Exception ignored) {}
            views.setTextViewText(H_TIME[k], t);
            views.setTextViewText(H_ICON[k], emojiFor(codeInt));
            views.setTextViewText(H_TEMP[k], "--".equals(temp) ? "--" : temp + "°");
        }
    }

    private static void fillDaily(RemoteViews views, String fc1, String fc2, String fc3) {
        String[] cells = {fc1, fc2, fc3};
        for (int k = 0; k < 3; k++) {
            String day = "--", icon = "—", temp = "--° / --°";
            String cell = cells[k] == null ? "" : cells[k];
            String[] lines = cell.split("\\n");
            if (lines.length > 0 && !lines[0].isEmpty()) day = lines[0];
            if (lines.length > 1 && !lines[1].isEmpty()) icon = lines[1];
            if (lines.length > 2 && !lines[2].isEmpty()) temp = lines[2];
            views.setTextViewText(D_DAY[k], day);
            views.setTextViewText(D_ICON[k], icon);
            views.setTextViewText(D_TEMP[k], temp);
        }
    }

    // ── Modo según la altura real del widget (dp). ──
    private static int modeFor(Context context, int widgetId) {
        try {
            Bundle o = AppWidgetManager.getInstance(context).getAppWidgetOptions(widgetId);
            int h = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0);
            if (h <= 0) h = 250;
            if (h >= 237) return MODE_FULL;
            if (h >= 189) return MODE_MEDIUM;
            return MODE_COMPACT;
        } catch (Exception e) {
            return MODE_FULL;
        }
    }

    // ── Escena de fondo: degradado + sol/luna + nubes + lluvia/nieve/niebla.
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
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setShader(new LinearGradient(0, 0, 0, h, colors, null, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, p);
        p.setShader(null);

        boolean storm = wcode >= 95;
        boolean rain = (wcode >= 51 && wcode <= 67) || (wcode >= 80 && wcode <= 82);
        boolean snow = (wcode >= 71 && wcode <= 77) || (wcode >= 85 && wcode <= 86);
        boolean fog = wcode == 45 || wcode == 48;
        boolean overcast = wcode == 3;
        boolean partly = wcode >= 1 && wcode <= 2;
        boolean clear = !storm && !rain && !snow && !fog && !overcast && !partly;

        // Sol / luna con halo (solo despejado/parcial: con nubes densas,
        // lluvia, nieve o niebla no se asoma un sol que no toca).
        if (clear || partly) {
            float cx = w * 0.82f;
            float cy = h * 0.22f;
            float r = Math.min(w, h) * 0.09f;

            Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
            glow.setShader(new RadialGradient(cx, cy, r * 2.6f,
                new int[]{night ? 0x66C9D8FF : 0x66FFF1B8, 0x00FFFFFF}, null, Shader.TileMode.CLAMP));
            c.drawCircle(cx, cy, r * 2.6f, glow);
            glow.setShader(null);

            Paint disc = new Paint(Paint.ANTI_ALIAS_FLAG);
            disc.setColor(night ? 0xFFF3F0DF : 0xFFFFE08A);
            c.drawCircle(cx, cy, r, disc);

            if (!night) {
                Paint halo = new Paint(Paint.ANTI_ALIAS_FLAG);
                halo.setColor(0x33FFFFFF);
                c.drawCircle(cx, cy, r * 1.28f, halo);
            } else {
                Paint crater = new Paint(Paint.ANTI_ALIAS_FLAG);
                crater.setColor(0x22000000);
                c.drawCircle(cx - r * 0.26f, cy - r * 0.16f, r * 0.18f, crater);
                c.drawCircle(cx + r * 0.22f, cy + r * 0.2f, r * 0.12f, crater);
            }
        }

        // Nubes (translúcidas, más marcadas de día).
        if (!clear) {
            int cloudAlpha = night ? 46 : 150;
            drawCloud(c, w * 0.64f, h * 0.34f, w * 0.15f, cloudAlpha, night);
            drawCloud(c, w * 0.31f, h * 0.54f, w * 0.12f, Math.max(30, cloudAlpha - 30), night);
            if (overcast || rain || storm || snow) {
                drawCloud(c, w * 0.83f, h * 0.50f, w * 0.13f, Math.max(30, cloudAlpha - 20), night);
            }
        }

        // Lluvia.
        if (rain) {
            Paint rainP = new Paint(Paint.ANTI_ALIAS_FLAG);
            rainP.setColor(night ? 0x66BFD8FF : 0x7760A5FF);
            rainP.setStrokeWidth(Math.max(1.5f, w * 0.004f));
            for (int i = 0; i < 14; i++) {
                float x = w * (0.56f + (i % 7) * 0.055f);
                float y = h * (0.40f + (i / 7) * 0.12f + (i % 7) * 0.018f);
                c.drawLine(x, y, x - w * 0.02f, y + h * 0.06f, rainP);
            }
        }

        // Nieve.
        if (snow) {
            Paint snowP = new Paint(Paint.ANTI_ALIAS_FLAG);
            snowP.setColor(0xCCFFFFFF);
            for (int i = 0; i < 18; i++) {
                float x = w * (0.54f + ((i * 37) % 42) / 100f);
                float y = h * (0.30f + ((i * 53) % 46) / 100f);
                c.drawCircle(x, y, Math.max(1.5f, w * 0.006f), snowP);
            }
        }

        // Estrellas (noche despejada / parcial), solo mitad derecha.
        if (night && (clear || partly)) {
            Paint star = new Paint(Paint.ANTI_ALIAS_FLAG);
            star.setColor(0xCCFFFFFF);
            for (int i = 0; i < 12; i++) {
                float x = w * (0.50f + ((i * 29) % 44) / 100f);
                float y = h * (0.06f + ((i * 47) % 36) / 100f);
                c.drawCircle(x, y, Math.max(1f, w * 0.0035f), star);
            }
        }

        // Niebla.
        if (fog) {
            Paint fogP = new Paint(Paint.ANTI_ALIAS_FLAG);
            fogP.setColor(night ? 0x1FFFFFFF : 0x4DFFFFFF);
            for (int i = 0; i < 3; i++) {
                float y = h * (0.36f + i * 0.11f);
                c.drawRect(0, y, w, y + h * 0.05f, fogP);
            }
        }

        // Bruma superior sutil (profundidad, no contraste).
        Paint haze = new Paint(Paint.ANTI_ALIAS_FLAG);
        haze.setShader(new LinearGradient(0, 0, 0, h * 0.42f,
            night ? 0x14000000 : 0x1FFFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h * 0.42f, haze);
    }

    private static void drawCloud(Canvas c, float cx, float cy, float r, int alpha, boolean night) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        int a = Math.max(0, Math.min(255, alpha));
        p.setColor(night ? Color.argb(a, 210, 224, 244) : Color.argb(a, 255, 255, 255));
        c.drawCircle(cx, cy, r, p);
        c.drawCircle(cx - r * 0.78f, cy + r * 0.16f, r * 0.7f, p);
        c.drawCircle(cx + r * 0.76f, cy + r * 0.2f, r * 0.62f, p);
        c.drawRoundRect(new RectF(cx - r * 1.38f, cy + r * 0.1f, cx + r * 1.38f, cy + r * 0.86f),
            r * 0.5f, r * 0.5f, p);
    }

    // Paletas One UI 8.5. Día: cielos claros y luminosos (texto tinta
    // oscura, contraste AA). Noche: azules profundos (texto blanco).
    private static int[] palette(int wcode, boolean night) {
        boolean rain = (wcode >= 51 && wcode <= 67) || (wcode >= 80 && wcode <= 82);
        boolean storm = wcode >= 95;
        boolean snow = (wcode >= 71 && wcode <= 77) || (wcode >= 85 && wcode <= 86);
        boolean overcast = wcode == 3;
        boolean fog = wcode == 45 || wcode == 48;
        boolean partly = wcode >= 1 && wcode <= 2;

        int top, mid, bottom;
        if (night) {
            if (storm)         { top = 0xFF0A0A1A; mid = 0xFF1C1736; bottom = 0xFF33284F; }
            else if (rain)     { top = 0xFF0C1222; mid = 0xFF1C2A44; bottom = 0xFF2C4060; }
            else if (snow)     { top = 0xFF141C33; mid = 0xFF2B3C60; bottom = 0xFF596F94; }
            else if (fog)      { top = 0xFF141A26; mid = 0xFF2A3342; bottom = 0xFF475262; }
            else if (overcast) { top = 0xFF111729; mid = 0xFF222C42; bottom = 0xFF3A4557; }
            else if (partly)   { top = 0xFF0B1128; mid = 0xFF1A2850; bottom = 0xFF34466F; }
            else               { top = 0xFF070B1F; mid = 0xFF14214F; bottom = 0xFF2A3F7E; }
        } else {
            if (storm)         { top = 0xFF8A8FB8; mid = 0xFFA9ADD0; bottom = 0xFFD9DBEC; }
            else if (rain)     { top = 0xFF7E97B5; mid = 0xFF9DB4CC; bottom = 0xFFD6E1EC; }
            else if (snow)     { top = 0xFFC2D5E8; mid = 0xFFDDEAF7; bottom = 0xFFF6FAFF; }
            else if (fog)      { top = 0xFFC1CBD4; mid = 0xFFD8E0E7; bottom = 0xFFF0F4F7; }
            else if (overcast) { top = 0xFFA6B4C4; mid = 0xFFC3CEDB; bottom = 0xFFE8EEF4; }
            else if (partly)   { top = 0xFF7FB8F5; mid = 0xFFA9D2FA; bottom = 0xFFE6F3FF; }
            else               { top = 0xFF6FB0FF; mid = 0xFFA5D4FF; bottom = 0xFFE9F7FF; }
        }
        return new int[]{top, mid, bottom};
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
