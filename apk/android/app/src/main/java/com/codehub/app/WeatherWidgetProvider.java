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
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
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

/**
 * Widget de clima de CodeHub para la pantalla de inicio (rediseñado).
 *
 * Reutiliza la misma ubicación (lat/lon) que guarda CodeHubSyncService
 * en SharedPreferences("codehub") cada ~15 min, y consulta Open-Meteo
 * directamente (mismo proveedor que usa el backend). Muestra:
 * temperatura, condición, sensación térmica, humedad, viento, mini-
 * forecast de 3 días y hora de actualización, sobre un fondo con
 * gradiente dinámico según la condición y el momento del día.
 *
 * Pinta primero con el último dato guardado en caché (instantáneo) y
 * dispara en background una consulta fresca que actualiza el
 * RemoteViews cuando llega. El botón de refrescar muestra un spinner
 * (animación) mientras se actualiza en segundo plano.
 */
public class WeatherWidgetProvider extends AppWidgetProvider {

    private static final String PREFS = "codehub";
    private static final String ACTION_REFRESH = "com.codehub.app.WIDGET_REFRESH";
    private static final String ACTION_CLOCK_TICK = "com.codehub.app.WIDGET_CLOCK_TICK";
    private static final long ALARM_INTERVAL_MS = 30 * 60 * 1000; // 30 min
    private static final long CLOCK_TICK_MS = 60 * 1000; // reloj en vivo, cada minuto

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
        return PendingIntent.getBroadcast(context, 1, intent, flags);
    }

    // Reloj en vivo: alarm cada 60s (RTC, hora real del dispositivo) que
    // solo repinta los labels de hora para que "corra" sin esperar al
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
        try {
            String time = new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date(System.currentTimeMillis()));
            if (hasAnyWidget(context)) {
                AppWidgetManager mgr = AppWidgetManager.getInstance(context);
                int[] ids = mgr.getAppWidgetIds(new ComponentName(context, WeatherWidgetProvider.class));
                RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_weather);
                views.setTextViewText(R.id.widget_updated, time);
                for (int id : ids) mgr.partiallyUpdateAppWidget(id, views);
                // Reprogramar para el siguiente minuto.
                scheduleClockTick(context);
            }
        } catch (Exception ignored) {}
    }

    // ── Pintado instantáneo con la última copia guardada ──
    private void paintFromCache(Context context, AppWidgetManager mgr, int widgetId) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        RemoteViews views = buildViews(context,
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
            prefs.getLong("widget_updated_at", 0));
        mgr.updateAppWidget(widgetId, views);
    }

    // Muestra/oculta el spinner de "refrescando" en todos los widgets,
    // para dar feedback visual de que el botón de refrescar funciona.
    private static void showLoading(final Context context, final boolean loading) {
        if (!hasAnyWidget(context)) return;
        try {
            AppWidgetManager mgr = AppWidgetManager.getInstance(context);
            int[] ids = mgr.getAppWidgetIds(new ComponentName(context, WeatherWidgetProvider.class));
            for (int id : ids) {
                RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_weather);
                views.setViewVisibility(R.id.widget_refresh, loading ? View.GONE : View.VISIBLE);
                views.setViewVisibility(R.id.widget_refresh_progress, loading ? View.VISIBLE : View.GONE);
                mgr.partiallyUpdateAppWidget(id, views);
            }
        } catch (Exception ignored) {}
    }

    private static void refreshAllInBackground(final Context context) {
        if (!hasAnyWidget(context)) return;
        // Feedback visual: mostrar spinner en el botón de refrescar.
        showLoading(context, true);
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
                    double lat = Double.longBitsToDouble(prefs.getLong("lat_bits", 0));
                    double lon = Double.longBitsToDouble(prefs.getLong("lon_bits", 0));
                    if (lat == 0 && lon == 0) return;

                    URL url = new URL("https://api.open-meteo.com/v1/forecast?latitude=" + lat +
                        "&longitude=" + lon +
                        "&current=temperature_2m,relative_humidity_2m,apparent_temperature,weather_code,wind_speed_10m,wind_gusts_10m,surface_pressure" +
                        "&hourly=precipitation_probability,uv_index" +
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
                    // "current" (mismo criterio que backend/clima/fetch.js): se
                    // toma el valor de la primera hora >= ahora en vez del
                    // máximo del día, para que suba/baje como el clima real.
                    int rainProb = -1;
                    float uv = Float.NaN;
                    JSONObject hourly = json.optJSONObject("hourly");
                    if (hourly != null) {
                        JSONArray hTimes = hourly.optJSONArray("time");
                        JSONArray hRain = hourly.optJSONArray("precipitation_probability");
                        JSONArray hUv = hourly.optJSONArray("uv_index");
                        if (hTimes != null) {
                            long now = System.currentTimeMillis();
                            int idx = -1;
                            SimpleDateFormat isoFmt = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm", Locale.US);
                            isoFmt.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
                            for (int i = 0; i < hTimes.length(); i++) {
                                try {
                                    Date t = isoFmt.parse(hTimes.optString(i, "") );
                                    if (t != null && t.getTime() >= now) { idx = i; break; }
                                } catch (Exception ignored) {}
                            }
                            if (idx < 0) idx = hTimes.length() - 1;
                            if (idx >= 0) {
                                if (hRain != null) rainProb = hRain.optInt(idx, -1);
                                if (hUv != null) uv = (float) hUv.optDouble(idx, Double.NaN);
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

                    // Mini-forecast 3 días (Moon/Mar/Mie… / emoji / 19° / 26°).
                    String fc1 = "", fc2 = "", fc3 = "";
                    if (daily != null) {
                        JSONArray dTempMax = daily.optJSONArray("temperature_2m_max");
                        JSONArray dTempMin = daily.optJSONArray("temperature_2m_min");
                        JSONArray dCode = daily.optJSONArray("weather_code");
                        if (dTempMax != null && dTempMin != null && dCode != null) {
                            fc1 = forecastCell(context, dTempMax, dTempMin, dCode, 0);
                            fc2 = forecastCell(context, dTempMax, dTempMin, dCode, 1);
                            fc3 = forecastCell(context, dTempMax, dTempMin, dCode, 2);
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
                        .putLong("widget_updated_at", now2)
                        .apply();

                    AppWidgetManager mgr = AppWidgetManager.getInstance(context);
                    int[] ids = mgr.getAppWidgetIds(new ComponentName(context, WeatherWidgetProvider.class));
                    RemoteViews views = new WeatherWidgetProvider().buildViews(
                        context, city, temp, wcode, feels, humidity, wind, gust, pressure,
                        rainProb, uv, sunrise, sunset, fc1, fc2, fc3, now2);
                    for (int id : ids) mgr.updateAppWidget(id, views);
                } catch (Exception ignored) {
                } finally {
                    // Quitar el spinner una vez terminada la actualización.
                    showLoading(context, false);
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
            int hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY);
            return hour >= 6 && hour < 19;
        }
        int now = minutesNow();
        return rise <= set && now >= rise && now < set
            || rise > set && (now >= rise || now < set);
    }

    private static int minutesNow() {
        java.util.Calendar c = java.util.Calendar.getInstance();
        return c.get(java.util.Calendar.HOUR_OF_DAY) * 60 + c.get(java.util.Calendar.MINUTE);
    }

    private static int parseHM(String s) {
        if (s == null || s.length() < 5) return -1;
        try {
            return Integer.parseInt(s.substring(0, 2)) * 60 + Integer.parseInt(s.substring(3, 5));
        } catch (Exception e) { return -1; }
    }

    // ── Fase lunar: mes sinódico (29.53058867 días) desde una Luna nueva
    //    de referencia (2000-01-06 18:14 UTC). Devuelve [emoji, nombre, %].
    private static final double SYNODIC_MS = 29.53058867 * 86400000.0;

    private static String[] moonPhaseInfo() {
        java.util.Calendar ref = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"));
        ref.set(2000, java.util.Calendar.JANUARY, 6, 18, 14, 0);
        ref.set(java.util.Calendar.MILLISECOND, 0);
        double cycles = ((System.currentTimeMillis() - ref.getTimeInMillis()) / SYNODIC_MS) % 1.0;
        if (cycles < 0) cycles += 1.0;
        int illum = (int) Math.round((1 - Math.cos(2 * Math.PI * cycles)) / 2 * 100);
        int idx = (int) Math.round(cycles * 8) % 8;
        String[] icons = {"🌑", "🌒", "🌓", "🌔", "🌕", "🌖", "🌗", "🌘"};
        String[] names = {
            "Luna nueva", "Creciente", "Cuarto creciente", "Gibosa creciente",
            "Luna llena", "Gibosa menguante", "Cuarto menguante", "Menguante"
        };
        return new String[]{icons[idx], names[idx], illum + "%"};
    }

    private static String forecastCell(Context context, JSONArray max, JSONArray min, JSONArray code, int idx) {
        try {
            StringBuilder sb = new StringBuilder();
            Calendar c = Calendar.getInstance();
            c.add(Calendar.DAY_OF_YEAR, idx);
            String day = new SimpleDateFormat("EEE", Locale.getDefault()).format(c.getTime());
            float hi = (float) max.getDouble(idx);
            float lo = (float) min.getDouble(idx);
            String emoji = emojiFor(code.optInt(idx, -1));
            sb.append(day).append('\n').append(emoji).append('\n')
              .append(Math.round(lo)).append('°').append('/').append(Math.round(hi)).append('°');
            return sb.toString();
        } catch (Exception e) { return "—"; }
    }

    private RemoteViews buildViews(Context context, String city, float temp, int wcode,
                                   float feels, int humidity, float wind, float gust, float pressure,
                                   int rainProb, float uv, String sunrise, String sunset,
                                   String fc1, String fc2, String fc3, long updatedAt) {
        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_weather);

        views.setTextViewText(R.id.widget_city, (city == null || city.isEmpty()) ? "CodeHub" : city);
        views.setTextViewText(R.id.widget_temp, Float.isNaN(temp) ? "--°" : Math.round(temp) + "°");
        views.setTextViewText(R.id.widget_icon, emojiFor(wcode));
        views.setTextViewText(R.id.widget_condition, labelFor(wcode));

        // Sensación térmica.
        if (!Float.isNaN(feels)) {
            views.setTextViewText(R.id.widget_feels, "Se siente como " + Math.round(feels) + "°");
        } else {
            views.setTextViewText(R.id.widget_feels, " ");
        }

        // Fila 1 de stats: humedad / viento / lluvia.
        views.setTextViewText(R.id.widget_humidity, humidity >= 0 ? "💧 " + humidity + "%" : "💧 --%");
        views.setTextViewText(R.id.widget_wind, !Float.isNaN(wind) ? "💨 " + Math.round(wind) + " km/h" : "💨 -- km/h");
        views.setTextViewText(R.id.widget_rain, rainProb >= 0 ? "🌧️ " + rainProb + "%" : "🌧️ --%");

        // Fila 2 de stats: ráfagas / presión / UV.
        views.setTextViewText(R.id.widget_gust, !Float.isNaN(gust) ? "🌬️ " + Math.round(gust) + " km/h" : "🌬️ -- km/h");
        views.setTextViewText(R.id.widget_pressure, !Float.isNaN(pressure) ? "📊 " + Math.round(pressure) + " hPa" : "📊 -- hPa");
        views.setTextViewText(R.id.widget_uv, !Float.isNaN(uv) ? "☀️ UV " + Math.round(uv) : "☀️ UV --");

        // Amanecer / atardecer + estado día/noche REAL (por salida/puesta,
        // no por hora fija) y fase lunar del mes sinódico.
        boolean hasSun = sunrise != null && !sunrise.isEmpty() && sunset != null && !sunset.isEmpty();
        boolean night = !isDaylightNow(hasSun ? sunrise : null, hasSun ? sunset : null);
        if (hasSun) {
            views.setTextViewText(R.id.widget_sun,
                (night ? "🌙  " : "☀️  ") + sunrise + "   " + sunset + "   ·   " + (night ? "Noche" : "Día"));
        } else {
            views.setTextViewText(R.id.widget_sun, night ? "🌙  Noche" : "☀️  Día");
        }
        views.setViewVisibility(R.id.widget_sun, View.VISIBLE);

        // Fase lunar: emoji + nombre + % iluminada (siempre presente).
        String[] moon = moonPhaseInfo();
        views.setTextViewText(R.id.widget_moon, moon[0] + " " + moon[1] + " · " + moon[2] + " iluminada");
        views.setViewVisibility(R.id.widget_moon, View.VISIBLE);

        // Mini-forecast 3 días.
        views.setTextViewText(R.id.widget_forecast_d1, fc1 == null || fc1.isEmpty() ? "—" : fc1);
        views.setTextViewText(R.id.widget_forecast_d2, fc2 == null || fc2.isEmpty() ? "—" : fc2);
        views.setTextViewText(R.id.widget_forecast_d3, fc3 == null || fc3.isEmpty() ? "—" : fc3);

        // Cronómetro EN VIVO del widget: "Actualizado hace mm:ss" desde
        // la última actualización. La cuenta la anima el Chronometer del
        // propio sistema (ticks cada segundo), sin alarmas de CodeHub.
        views.setChronometer(R.id.widget_updated2,
            SystemClock.elapsedRealtime() - Math.max(0, System.currentTimeMillis() - updatedAt),
            "Actualizado hace %s", updatedAt > 0);
        // Reloj del widget: hora local ACTUAL (la mantiene al minuto el
        // tick de AlarmManager — paintClockOnly). El header es el reloj,
        // el footer es cuándo se actualizó el clima.
        views.setTextViewText(R.id.widget_updated,
            new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date(System.currentTimeMillis())));

        // Fondo con gradiente dinámico según clima + momento del día
        // (la paleta noche se decide por las horas reales de salida/puesta).
        views.setImageViewBitmap(R.id.widget_bg_image, backgroundBitmap(wcode, night));

        // Tocar el widget abre la app en la sección de clima.
        Intent openIntent = new Intent(context, MainActivity.class);
        openIntent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        openIntent.putExtra("open_url", "https://wilson360-labs.vercel.app/index.html#weather-section");
        int openFlags = PendingIntent.FLAG_UPDATE_CURRENT |
            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0);
        PendingIntent openPending = PendingIntent.getActivity(context, 100, openIntent, openFlags);
        views.setOnClickPendingIntent(R.id.widget_root, openPending);

        // Botón de refrescar manual. Al presionarlo se dispara el refresh,
        // que internamente muestra el spinner (animación de carga) y lo
        // oculta al terminar.
        views.setOnClickPendingIntent(R.id.widget_refresh, refreshPendingIntent(context));

        // Emoji ANIMADO: el AdapterViewFlipper (wx_anim_emoji) rota entre
        // los frames que construye WeatherWidgetAnimService (flip del
        // sistema). widget_icon actúa como "empty view": si el launcher no
        // puede enlazar el servicio, se queda el emoji estático y el
        // widget sigue funcionando normal (nada se rompe).
        try {
            Intent animIntent = new Intent(context, WeatherWidgetAnimService.class);
            animIntent.setData(android.net.Uri.parse(
                "widget://weather/" + getClass().getSimpleName() + "/" + System.currentTimeMillis()));
            views.setRemoteAdapter(R.id.wx_anim_emoji, animIntent);
            views.setEmptyView(R.id.wx_anim_emoji, R.id.widget_icon);
        } catch (Exception ignored) {}

        return views;
    }

    // ── Fondo con gradiente según la condición meteorológica y el
    //    momento del día (día/noche cambia la paleta). ──
    private static Bitmap backgroundBitmap(int wcode, boolean night) {
        int w = 2, h = 2;
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        try {
            int[] colors = palette(wcode, night);
            GradientDrawable grad = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR, colors);
            grad.setCornerRadius(0);
            grad.setBounds(0, 0, w, h);
            grad.draw(new Canvas(bmp));
        } catch (Exception e) {
            bmp.eraseColor(Color.rgb(30, 50, 80));
        }
        return bmp;
    }

    private static int[] palette(int wcode, boolean night) {
        int clearDayDark = Color.rgb(21, 44, 91);
        int mid = Color.rgb(37, 99, 235);
        int accent = Color.rgb(14, 20, 32);

        boolean rain = wcode >= 51 && wcode <= 67 || wcode >= 80 && wcode <= 82;
        boolean storm = wcode >= 95;
        boolean snow = wcode >= 71 && wcode <= 77 || wcode >= 85 && wcode <= 86;
        boolean overcast = wcode == 3;
        boolean fog = wcode == 45 || wcode == 48;
        boolean clear = wcode == 0;
        boolean partly = wcode >= 1 && wcode <= 2;

        int top, bottom;
        if (night) {
            top = Color.rgb(10, 13, 28);
            bottom = rain ? Color.rgb(30, 41, 82)
                    : storm ? Color.rgb(30, 20, 40)
                    : Color.rgb(19, 27, 61);
        } else if (rain || storm) {
            top = Color.rgb(59, 91, 145);
            bottom = Color.rgb(30, 41, 82);
        } else if (snow) {
            top = Color.rgb(150, 185, 220);
            bottom = Color.rgb(90, 115, 150);
        } else if (clear) {
            top = Color.rgb(56, 130, 246);
            bottom = Color.rgb(135, 170, 255);
        } else if (partly) {
            top = Color.rgb(59, 130, 246);
            bottom = Color.rgb(148, 150, 175);
        } else if (overcast) {
            top = Color.rgb(96, 108, 130);
            bottom = Color.rgb(160, 166, 180);
        } else if (fog) {
            top = Color.rgb(110, 118, 132);
            bottom = Color.rgb(150, 155, 165);
        } else {
            top = Color.rgb(37, 99, 235);
            bottom = Color.rgb(14, 20, 32);
        }
        return new int[]{top, accent, bottom};
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
