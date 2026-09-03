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
        String action = intent == null ? "" : intent.getAction();
        if (ACTION_REFRESH.equals(action)) {
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
            if (am != null) {
                am.cancel(refreshPendingIntent(context));
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
                        "&current=temperature_2m,relative_humidity_2m,apparent_temperature,weather_code,wind_speed_10m" +
                        "&daily=weather_code,temperature_2m_max,temperature_2m_min" +
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
                    int wcode = current.optInt("weather_code", -1);
                    String city = prefs.getString("widget_city", "");

                    // Mini-forecast 3 días (Moon/Mar/Mie… / emoji / 19° / 26°).
                    String fc1 = "", fc2 = "", fc3 = "";
                    JSONObject daily = json.optJSONObject("daily");
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

                    long now = System.currentTimeMillis();
                    prefs.edit()
                        .putFloat("widget_temp", temp)
                        .putInt("widget_wcode", wcode)
                        .putFloat("widget_feels", feels)
                        .putInt("widget_humidity", humidity)
                        .putFloat("widget_wind", wind)
                        .putString("widget_fc1", fc1)
                        .putString("widget_fc2", fc2)
                        .putString("widget_fc3", fc3)
                        .putLong("widget_updated_at", now)
                        .apply();

                    AppWidgetManager mgr = AppWidgetManager.getInstance(context);
                    int[] ids = mgr.getAppWidgetIds(new ComponentName(context, WeatherWidgetProvider.class));
                    RemoteViews views = new WeatherWidgetProvider().buildViews(
                        context, city, temp, wcode, feels, humidity, wind, fc1, fc2, fc3, now);
                    for (int id : ids) mgr.updateAppWidget(id, views);
                } catch (Exception ignored) {
                finally {
                    // Quitar el spinner una vez terminada la actualización.
                    showLoading(context, false);
                }
            }
        }).start();
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
                                   float feels, int humidity, float wind,
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

        // Humedad y viento.
        views.setTextViewText(R.id.widget_humidity, humidity >= 0 ? "💧 " + humidity + "%" : "💧 --%");
        views.setTextViewText(R.id.widget_wind, !Float.isNaN(wind) ? "💨 " + Math.round(wind) + " km/h" : "💨 -- km/h");

        // Mini-forecast 3 días.
        views.setTextViewText(R.id.widget_forecast_d1, fc1 == null || fc1.isEmpty() ? "—" : fc1);
        views.setTextViewText(R.id.widget_forecast_d2, fc2 == null || fc2.isEmpty() ? "—" : fc2);
        views.setTextViewText(R.id.widget_forecast_d3, fc3 == null || fc3.isEmpty() ? "—" : fc3);

        // Hora de última actualización.
        if (updatedAt > 0) {
            String time = new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date(updatedAt));
            views.setTextViewText(R.id.widget_updated, time);
            views.setTextViewText(R.id.widget_updated2, "Actualizado " + time);
        } else {
            views.setTextViewText(R.id.widget_updated, "");
            views.setTextViewText(R.id.widget_updated2, "");
        }

        // Fondo con gradiente dinámico según clima + momento del día.
        views.setImageViewBitmap(R.id.widget_bg_image, backgroundBitmap(wcode));

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

        return views;
    }

    // ── Fondo con gradiente según la condición meteorológica y el
    //    momento del día (día/noche cambia la paleta). ──
    private static Bitmap backgroundBitmap(int wcode) {
        int w = 2, h = 2;
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        try {
            java.util.Calendar cal = java.util.Calendar.getInstance();
            int hour = cal.get(java.util.Calendar.HOUR_OF_DAY);
            boolean night = hour < 6 || hour >= 19;

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
