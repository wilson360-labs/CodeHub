package com.codehub.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.os.Build;
import android.util.Log;
import android.view.View;
import android.widget.RemoteViews;

import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Scanner;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Widget de clima DENTRO del panel de notificaciones (Android).
 *
 * La notificación persistente elegante de CodeHub — dos vistas:
 * - COMPACTA (colapsada): astro pintado + "17° Nublado · Sens. 19°" + ciudad.
 * - EXPANDIDA (grande): header astro+ciudad+hora, temperatura 34sp,
 *   condición + sensación, PRONÓSTICO POR HORAS (4 slots con astritos
 *   pintados vía canvas) y fila de SISMOS con magnitud, distancia y
 *   rumbo (haversine) desde la ubicación guardada.
 * - Fuente de datos: Open-Meteo (current + hourly + daily sunrise/sunset);
 *   sismos: USGS (público, sin key).
 * - La ubicación viene de las prefs "codehub" (lat_bits/lon_bits — lo que
 *   escribe chSavePushLocation → CodeHubNative.saveLocation y el sync).
 *   Sin ubicación real: no se publica nada (nunca datos falsos).
 * - ALARMA de sismos: M ≥ 4.5 a ≤1200 km no vista antes → heads-up aparte.
 *
 * CADENCIA de refresco: al abrir la app (MainActivity.onCreate), en cada
 * ciclo del sync nativo (CodeHubSyncWorker — WorkManager, ~15 min) y tras
 * reiniciar (BootReceiver). El HH:mm mostrado es la marca "actualizado":
 * Android limita los ciclos periódicos a un mínimo de 15 minutos.
 */
public class WeatherNotificationProvider {

    private static final String TAG = "CodeHubWxNotif";

    private static final String PREFS = "codehub";
    private static final String PREF_CACHE = "wx_notif_cache";
    private static final String PREF_QUAKE_LAST = "quake_last_alarm_id";

    private static final String CH_WIDGET = "codehub_weather_widget";
    private static final String CH_QUAKES = "codehub_quakes_v2";
    /** Canal v1 (7.44–7.46): sonaba con el tono de notificación genérico. */
    private static final String CH_QUAKES_LEGACY = "codehub_quakes";

    private static final int NOTIF_ID = 4242;       // widget de clima
    private static final int NOTIF_ID_QUAKE = 4243; // alarma de sismo

    private static final int ALERT_MAG_X10 = 45;    // 4.5 magnitud
    private static final int ALERT_RADIUS_KM = 1200;
    private static final long ALERT_WINDOW_MS = 24L * 60 * 60 * 1000;
    private static final double ROW_MAG_MIN = 3.5;  // fila informativa

    private static final int HOUR_SLOTS = 4;

    private static final AtomicBoolean sRefreshing = new AtomicBoolean(false);

    private WeatherNotificationProvider() {}

    /* ════════════════════════ API PÚBLICA ════════════════════════ */

    /** Refresca en segundo plano (hilo corto con single-flight). */
    public static void refresh(final Context context) {
        final Context app = context.getApplicationContext();
        if (!sRefreshing.compareAndSet(false, true)) return; // descarta ticks coincidentes
        new Thread(new Runnable() {
            @Override public void run() {
                try { update(app); }
                catch (Throwable t) { Log.e(TAG, "refresh falló", t); }
                finally { sRefreshing.set(false); }
            }
        }).start();
    }

    /* ════════════════════════ MOTOR ════════════════════════ */

    private static void update(Context context) {
        if (!canPostNotifications(context)) return;   // sin permiso: silencio
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        double lat = Double.longBitsToDouble(prefs.getLong("lat_bits", 0));
        double lon = Double.longBitsToDouble(prefs.getLong("lon_bits", 0));
        String city = prefs.getString("widget_city", "");

        if (lat == 0 && lon == 0) {
            // Sin ubicación real guardada: nada se publica (política "en
            // cero" de CodeHub — jamás una ciudad inventada).
            cancel(context, NOTIF_ID);
            return;
        }

        // 1) Pintar YA con la caché (instantáneo al arrancar; luego red).
        String cache = prefs.getString(PREF_CACHE, null);
        if (cache != null) {
            try { paintFromPayload(context, new JSONObject(cache), lat, lon, city, true); }
            catch (Throwable ignored) {}
        }

        // 2) Clima en vivo (Open-Meteo; misma fuente que la tarjeta web).
        JSONObject payload = fetchWeather(lat, lon);
        if (payload != null) {
            // Conservar el sismo visto en la caché previa hasta que llegue
            // uno nuevo del refresh (la fila no "destella" a vacío).
            if (cache != null) {
                try {
                    JSONObject oldQ = new JSONObject(cache).optJSONObject("quake");
                    if (oldQ != null && !payload.has("quake")) payload.put("quake", oldQ);
                } catch (Throwable ignored) {}
            }
            prefs.edit().putString(PREF_CACHE, payload.toString()).apply();
            paintFromPayload(context, payload, lat, lon, city, false);
        }

        // 3) Sismos: la fila del widget Y la alarma se evalúan aunque el
        //    clima haya fallado (no se pierde un sismo grande por un 500).
        JSONObject quake = fetchQuake(lat, lon);
        if (quake != null) {
            boolean painted = false;
            if (payload == null && cache != null) {
                try {
                    JSONObject cached = new JSONObject(cache);
                    cached.put("quake", quake);
                    prefs.edit().putString(PREF_CACHE, cached.toString()).apply();
                    paintFromPayload(context, cached, lat, lon, city, true);
                    painted = true;
                } catch (Throwable ignored) {}
            }
            if (!painted && payload == null) {
                try {
                    JSONObject merged = (cache != null && new JSONObject(cache).has("code"))
                        ? new JSONObject(cache) : new JSONObject();
                    merged.put("at", System.currentTimeMillis());
                    merged.put("quake", quake);
                    prefs.edit().putString(PREF_CACHE, merged.toString()).apply();
                    paintFromPayload(context, merged, lat, lon, city, true);
                } catch (Throwable ignored) {}
            }
            evalQuakeAlarm(context, quake, lat, lon);
        }
    }

    /* ════════════════════════ PINTAR ════════════════════════ */

    private static void paintFromPayload(Context context, JSONObject payload,
                                         double lat, double lon,
                                         String city, boolean cached) {
        try {
            int code = payload.optInt("code", -1);
            boolean day = payload.optBoolean("day", true);
            float temp  = (float) payload.optDouble("temp", Double.NaN);
            float feels = (float) payload.optDouble("feels", Double.NaN);
            int humidity = payload.optInt("humidity", -1);
            float wind = (float) payload.optDouble("wind", Double.NaN);
            int rain = payload.optInt("rain", -1);
            long at = payload.optLong("at", System.currentTimeMillis());

            JSONObject q = payload.optJSONObject("quake");
            JSONArray hours = payload.optJSONArray("hours");

            Bitmap astro = paintAstro(code, day);
            SimpleDateFormat fmt = new SimpleDateFormat("HH:mm", Locale.getDefault());
            String timeStr = fmt.format(new Date(at));
            String cityStr = (city != null && !city.isEmpty()) ? city : "Mi ubicación";

            String tempStr = Float.isNaN(temp) ? "--°" : Math.round(temp) + "°";
            String condStr = describe(code, day);
            String feelsStr = Float.isNaN(feels) ? "" : " · Sens. " + Math.round(feels) + "°";

            // Chips: humedad · viento · lluvia %
            StringBuilder data = new StringBuilder();
            if (humidity >= 0) data.append("💧 ").append(humidity).append('%');
            if (!Float.isNaN(wind)) {
                if (data.length() > 0) data.append("  ·  ");
                data.append("🌬 ").append(Math.round(wind)).append(" km/h");
            }
            if (rain >= 0) {
                if (data.length() > 0) data.append("  ·  ");
                data.append("☔ ").append(rain).append('%');
            }

            /* ── COMPACTA ── */
            RemoteViews compact = new RemoteViews(context.getPackageName(),
                R.layout.notification_weather_compact);
            if (astro != null) compact.setImageViewBitmap(R.id.n_c_icon, astro);
            compact.setTextViewText(R.id.n_c_main, condStr.isEmpty()
                ? tempStr + " · " + cityStr
                : tempStr + " " + condStr + feelsStr);
            compact.setTextViewText(R.id.n_c_side, cityStr + " · " + timeStr);

            /* ── EXPANDIDA ── */
            RemoteViews big = new RemoteViews(context.getPackageName(),
                R.layout.notification_weather);
            if (astro != null) big.setImageViewBitmap(R.id.n_w_icon, astro);
            big.setTextViewText(R.id.n_w_city, cityStr);
            big.setTextViewText(R.id.n_w_time, cached ? timeStr + " · caché" : timeStr);
            big.setTextViewText(R.id.n_w_temp, tempStr);
            big.setTextViewText(R.id.n_w_desc,
                condStr + (condStr.isEmpty() ? "" : feelsStr));
            big.setTextViewText(R.id.n_w_data, data.toString());

            fillHourSlots(big, hours);

            if (q != null && q.optDouble("mag", -1) > 0) {
                float mag = (float) q.optDouble("mag", 0);
                double dist = haversineKm(lat, lon, q.optDouble("lat", 0), q.optDouble("lon", 0));
                String dir = bearingTo8(lat, lon, q.optDouble("lat", 0), q.optDouble("lon", 0));
                String when = localTime(q.optLong("time", 0));
                String row = "⚠ Sismo M" + String.format(Locale.US, "%.1f", mag)
                    + " · " + Math.round(dist) + " km " + dir
                    + " · " + when
                    + " · prof. " + Math.round(q.optDouble("depth", 0)) + " km";
                big.setTextViewText(R.id.n_w_quake, row);
                big.setViewVisibility(R.id.n_w_quake,
                    dist <= ALERT_RADIUS_KM ? View.VISIBLE : View.GONE);
            } else {
                big.setViewVisibility(R.id.n_w_quake, View.GONE);
            }

            notifyWidget(context, buildWidgetNotification(context, compact, big));
        } catch (Throwable t) {
            Log.e(TAG, "paintFromPayload falló", t);
        }
    }

    /** Llena los 4 slots horarios; esconde la fila si no hay datos. */
    private static void fillHourSlots(RemoteViews big, JSONArray hours) {
        boolean show = hours != null && hours.length() >= 2;
        big.setViewVisibility(R.id.n_w_hours, show ? View.VISIBLE : View.GONE);
        if (!show) return;
        int[][] ids = {
            {R.id.n_w_h1_icon, R.id.n_w_h1_temp, R.id.n_w_h1_hour},
            {R.id.n_w_h2_icon, R.id.n_w_h2_temp, R.id.n_w_h2_hour},
            {R.id.n_w_h3_icon, R.id.n_w_h3_temp, R.id.n_w_h3_hour},
            {R.id.n_w_h4_icon, R.id.n_w_h4_temp, R.id.n_w_h4_hour},
        };
        for (int i = 0; i < HOUR_SLOTS; i++) {
            JSONObject h = hours.optJSONObject(i);
            if (h == null) {
                big.setViewVisibility(ids[i][0], View.INVISIBLE);
                big.setTextViewText(ids[i][1], "");
                big.setTextViewText(ids[i][2], "");
                continue;
            }
            Bitmap mini = paintAstro(h.optInt("code", -1), h.optBoolean("day", true));
            if (mini != null) {
                big.setImageViewBitmap(ids[i][0], mini);
                big.setViewVisibility(ids[i][0], View.VISIBLE);
            } else {
                big.setViewVisibility(ids[i][0], View.INVISIBLE);
            }
            float t = (float) h.optDouble("temp", Double.NaN);
            big.setTextViewText(ids[i][1], Float.isNaN(t) ? "—" : Math.round(t) + "°");
            big.setTextViewText(ids[i][2], h.optString("h", ""));
        }
    }

    private static Notification buildWidgetNotification(Context context,
                                                        RemoteViews compact,
                                                        RemoteViews big) {
        Intent open = new Intent(context, MainActivity.class);
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(context, 2001, open,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        return new NotificationCompat.Builder(context, CH_WIDGET)
            .setSmallIcon(R.drawable.ic_stat_weather)
            .setContentTitle("Clima · CodeHub")
            .setContentText("Widget del clima en el panel de notificaciones")
            .setCustomContentView(compact)
            .setCustomBigContentView(big)
            .setStyle(new NotificationCompat.DecoratedCustomViewStyle())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(pi)
            .build();
    }

    /* ════════════════════════ ALARMA DE SISMOS ════════════════════════ */

    private static void evalQuakeAlarm(Context context, JSONObject quake, double lat, double lon) {
        try {
            SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            String id = quake.optString("id", "");
            float mag = (float) quake.optDouble("mag", 0);
            long time = quake.optLong("time", 0);

            boolean worth = Math.round(mag * 10) >= ALERT_MAG_X10
                && (System.currentTimeMillis() - time) <= ALERT_WINDOW_MS;
            if (!worth) return;
            if (id.equals(prefs.getString(PREF_QUAKE_LAST, ""))) return; // ya sonó

            double dist = haversineKm(lat, lon, quake.optDouble("lat", 0), quake.optDouble("lon", 0));
            if (dist > ALERT_RADIUS_KM) return;
            prefs.edit().putString(PREF_QUAKE_LAST, id).apply();

            String dir = bearingTo8(lat, lon, quake.optDouble("lat", 0), quake.optDouble("lon", 0));
            String place = quake.optString("place", "");
            long agoMin = Math.max(1, (System.currentTimeMillis() - time) / 60000L);
            String title = "⚠ Sismo M" + String.format(Locale.US, "%.1f", mag)
                + " a " + Math.round(dist) + " km " + dir + " de tu ubicación";
            String text = (place.isEmpty() ? "Sismo reciente" : place)
                + " · hace " + (agoMin < 90 ? agoMin + " min" : localTime(time))
                + " · profundidad " + Math.round(quake.optDouble("depth", 0)) + " km";

            Intent open = new Intent(context, MainActivity.class);
            open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            PendingIntent pi = PendingIntent.getActivity(context, 3002, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

            notifyQuake(context,
                new NotificationCompat.Builder(context, CH_QUAKES)
                    .setSmallIcon(R.drawable.ic_stat_quake)
                    .setContentTitle(title)
                    .setContentText(text)
                    .setStyle(new NotificationCompat.BigTextStyle().bigText(title + "\n" + text))
                    .setAutoCancel(true)
                    .setOnlyAlertOnce(true)
                    .setContentIntent(pi)
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setCategory(NotificationCompat.CATEGORY_ALARM)
                    .build());
        } catch (Throwable t) {
            Log.e(TAG, "evalQuakeAlarm falló", t);
        }
    }

    /* ════════════════════════ DATOS ════════════════════════ */

    /**
     * Payload del widget: "current" + próximas horas (Open-Meteo) para el
     * pronóstico, sunrise/sunset del día para saber si cada hora es de día.
     */
    private static JSONObject fetchWeather(double lat, double lon) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL("https://api.open-meteo.com/v1/forecast?latitude=" + lat
                + "&longitude=" + lon
                + "&current=temperature_2m,apparent_temperature,relative_humidity_2m,weather_code,wind_speed_10m,is_day"
                + "&hourly=temperature_2m,weather_code,precipitation_probability&forecast_hours=6"
                + "&daily=sunrise,sunset"
                + "&timezone=auto");
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(9000);
            conn.setReadTimeout(9000);
            if (conn.getResponseCode() != 200) return null;
            InputStream is = conn.getInputStream();
            Scanner sc = new Scanner(is, "UTF-8").useDelimiter("\\A");
            String body = sc.hasNext() ? sc.next() : "{}";
            sc.close();

            JSONObject json = new JSONObject(body);
            JSONObject cur = json.optJSONObject("current");
            if (cur == null) return null;

            JSONObject out = new JSONObject();
            out.put("code", cur.optInt("weather_code", -1));
            out.put("day", cur.optInt("is_day", 1) == 1);
            putNaNable(out, "temp", cur.optDouble("temperature_2m", Double.NaN));
            putNaNable(out, "feels", cur.optDouble("apparent_temperature", Double.NaN));
            putNaNable(out, "wind", cur.optDouble("wind_speed_10m", Double.NaN));
            out.put("humidity", cur.optInt("relative_humidity_2m", -1));
            out.put("rain", nextRainProb(json));
            out.put("at", System.currentTimeMillis());

            JSONArray hours = buildHourSlots(json);
            if (hours != null) out.put("hours", hours);
            return out;
        } catch (Throwable t) {
            Log.e(TAG, "fetchWeather falló", t);
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /** org.json rechaza NaN: se guarda JSONObject.NULL y optDouble trae el default. */
    private static void putNaNable(JSONObject o, String key, double v) throws Exception {
        o.put(key, Double.isNaN(v) ? JSONObject.NULL : v);
    }

    /**
     * Las próximas HORAS_SLOTS horas [{h: "21h", code, temp, day}].
     * Los times de Open-Meteo (timezone=auto) son HORA LOCAL de la
     * ubicación: se convierten a epoch restando utc_offset_seconds para
     * compararlas con "ahora", y el label se saca del propio string.
     */
    private static JSONArray buildHourSlots(JSONObject json) {
        try {
            JSONObject hourly = json.optJSONObject("hourly");
            if (hourly == null) return null;
            JSONArray times = hourly.optJSONArray("time");
            JSONArray temps = hourly.optJSONArray("temperature_2m");
            JSONArray codes = hourly.optJSONArray("weather_code");
            if (times == null || temps == null || codes == null) return null;

            double offsetMs = json.optDouble("utc_offset_seconds", 0) * 1000.0;
            long now = System.currentTimeMillis();
            SimpleDateFormat iso = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm", Locale.US);
            iso.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));

            double sunUp = sunHour(json, "sunrise", 6.0);
            double sunDown = sunHour(json, "sunset", 18.5);

            JSONArray out = new JSONArray();
            for (int i = 0; i < times.length() && out.length() < HOUR_SLOTS; i++) {
                String t = times.optString(i, "");
                if (t.length() < 13) continue;
                try {
                    Date d = iso.parse(t);
                    if (d == null) continue;
                    if (d.getTime() - offsetMs < now) continue;   // pasada: saltar
                    JSONObject slot = new JSONObject();
                    slot.put("h", t.substring(11, 13) + "h");
                    double ht = temps.optDouble(i, Double.NaN);
                    slot.put("temp", Double.isNaN(ht) ? JSONObject.NULL : ht);
                    slot.put("code", codes.optInt(i, -1));
                    double hd = Integer.parseInt(t.substring(11, 13))
                        + Integer.parseInt(t.length() >= 16 ? t.substring(14, 16) : "0") / 60.0;
                    slot.put("day", hd >= sunUp && hd <= sunDown);
                    out.put(slot);
                } catch (Exception ignored) {}
            }
            return out.length() > 0 ? out : null;
        } catch (Throwable ignored) {}
        return null;
    }

    /** Hora decimal (0-24) del sunrise/sunset del día 1 en hora local. */
    private static double sunHour(JSONObject json, String which, double fallback) {
        try {
            JSONObject daily = json.optJSONObject("daily");
            if (daily == null) return fallback;
            JSONArray arr = daily.optJSONArray(which);
            if (arr == null) return fallback;
            String t = arr.optString(0, "");
            if (t.length() < 16) return fallback;
            return Integer.parseInt(t.substring(11, 13))
                + Integer.parseInt(t.substring(14, 16)) / 60.0;
        } catch (Throwable ignored) {}
        return fallback;
    }

    /** Lluvia % de la próxima hora (igual criterio que el widget launcher). */
    private static int nextRainProb(JSONObject json) {
        try {
            JSONObject hourly = json.optJSONObject("hourly");
            if (hourly == null) return -1;
            JSONArray times = hourly.optJSONArray("time");
            JSONArray rain = hourly.optJSONArray("precipitation_probability");
            if (times == null || rain == null) return -1;
            long now = System.currentTimeMillis();
            double offsetSec = json.optDouble("utc_offset_seconds", 0);
            SimpleDateFormat iso = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm", Locale.US);
            iso.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
            for (int i = 0; i < times.length(); i++) {
                try {
                    Date t = iso.parse(times.optString(i, ""));
                    if (t == null) continue;
                    long ms = t.getTime() - Math.round(offsetSec * 1000.0);
                    if (ms >= now) return rain.optInt(i, -1);
                } catch (Exception ignored) {}
            }
        } catch (Throwable ignored) {}
        return -1;
    }

    /**
     * Sismo del día: el más fuerte en 24 h a ≤1200 km (USGS, público).
     * Decide la fila del widget y la alarma.
     */
    private static JSONObject fetchQuake(double lat, double lon) {
        HttpURLConnection conn = null;
        try {
            SimpleDateFormat dayF = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
            dayF.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
            String since = dayF.format(new Date(System.currentTimeMillis() - ALERT_WINDOW_MS));
            URL url = new URL("https://earthquake.usgs.gov/fdsnws/event/1/query?format=geojson"
                + "&latitude=" + lat + "&longitude=" + lon + "&maxradiuskm=" + ALERT_RADIUS_KM
                + "&starttime=" + since + "&minmagnitude=3.5&orderby=time");
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(9000);
            conn.setReadTimeout(9000);
            if (conn.getResponseCode() != 200) return null;
            InputStream is = conn.getInputStream();
            Scanner sc = new Scanner(is, "UTF-8").useDelimiter("\\A");
            String body = sc.hasNext() ? sc.next() : "{}";
            sc.close();

            JSONObject json = new JSONObject(body);
            JSONArray features = json.optJSONArray("features");
            if (features == null || features.length() == 0) return null;

            JSONObject best = null;
            double bestMag = -1;
            for (int i = 0; i < features.length(); i++) {
                JSONObject f = features.optJSONObject(i);
                if (f == null) continue;
                JSONObject p = f.optJSONObject("properties");
                if (p == null) continue;
                double mag = p.optDouble("mag", -1);
                if (mag > bestMag) { bestMag = mag; best = f; }
            }
            if (best == null || bestMag < ROW_MAG_MIN) return null;

            JSONObject p = best.optJSONObject("properties");
            JSONObject g = best.optJSONObject("geometry");
            JSONArray coords = g == null ? null : g.optJSONArray("coordinates");

            JSONObject out = new JSONObject();
            out.put("id", best.optString("id", ""));
            out.put("mag", p == null ? 0 : p.optDouble("mag", 0));
            out.put("place", p == null ? "" : p.optString("place", ""));
            out.put("time", p == null ? 0 : p.optLong("time", 0));
            out.put("lon", coords == null ? 0 : coords.optDouble(0, 0));
            out.put("lat", coords == null ? 0 : coords.optDouble(1, 0));
            out.put("depth", coords == null ? 0 : coords.optDouble(2, 0));
            return out;
        } catch (Throwable t) {
            Log.e(TAG, "fetchQuake falló", t);
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /* ════════════════════════ UTILIDADES ════════════════════════ */

    private static boolean canPostNotifications(Context ctx) {
        if (Build.VERSION.SDK_INT >= 33) {
            return ContextCompat.checkSelfPermission(ctx,
                android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
        }
        return true;
    }

    private static double haversineKm(double lat1, double lon1, double lat2, double lon2) {
        double R = 6371.0;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
            + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
            * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 2 * R * Math.asin(Math.min(1, Math.sqrt(a)));
    }

    private static String bearingTo8(double lat1, double lon1, double lat2, double lon2) {
        double y = Math.sin(Math.toRadians(lon2 - lon1)) * Math.cos(Math.toRadians(lat2));
        double x = Math.cos(Math.toRadians(lat1)) * Math.sin(Math.toRadians(lat2))
            - Math.sin(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
            * Math.cos(Math.toRadians(lon2 - lon1));
        double brng = (Math.toDegrees(Math.atan2(y, x)) + 360) % 360;
        String[] dirs = {"N", "NE", "E", "SE", "S", "SO", "O", "NO"};
        return dirs[(int) Math.round(brng / 45) % 8];
    }

    /** Hora local del dispositivo (los sismos llegan en epoch UTC). */
    private static String localTime(long epochMs) {
        SimpleDateFormat fmt = new SimpleDateFormat("HH:mm", Locale.US);
        fmt.setTimeZone(java.util.TimeZone.getDefault());
        return fmt.format(new Date(epochMs));
    }

    private static String describe(int code, boolean day) {
        switch (bucket(code)) {
            case 0: return day ? "Despejado" : "Cielo limpio";
            case 1: return "Parcial";
            case 2: return "Nublado";
            case 3: return "Niebla";
            case 4: return "Lluvia";
            case 5: return "Tormenta";
            case 6: return "Nieve";
            default: return "";
        }
    }

    /** Mismos rangos que wxConditionBucket() del sitio (js). */
    private static int bucket(int code) {
        if (code <= 1) return 0;
        if (code == 2) return 1;
        if (code == 3) return 2;
        if (code == 45 || code == 48) return 3;
        if ((code >= 51 && code <= 67) || code == 80 || code == 81 || code == 82) return 4;
        if (code >= 95) return 5;
        if ((code >= 71 && code <= 77) || code == 85 || code == 86) return 6;
        return 2;
    }

    /* ═══════════════════ ASTRO PINTADO (canvas, sin emojis) ═══════════════════ */

    /**
     * Icono del widget: sol con halo / luna con terminador / nube con
     * volumen / lluvia / tormenta con rayo / nieve / niebla — la misma
     * familia de color del widget del launcher. Se reusa en los slots
     * horarios (el ImageView los escala a 22dp).
     */
    static Bitmap paintAstro(int code, boolean day) {
        int b = bucket(code);
        int S = 96;
        Bitmap bmp = Bitmap.createBitmap(S, S, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        if (b == 0 || b == 1) {
            if (day) drawSun(c, p, S * .38f, S * .30f, S * .16f);
            else     drawMoon(c, p, S * .40f, S * .30f, S * .15f);
        }
        switch (b) {
            case 1: drawCloud(c, p, S * .34f, S * .62f, S * .46f, day); break;
            case 2: drawCloud(c, p, S * .52f, S * .50f, S * .90f, day); break;
            case 3: drawFog(c, p, S); break;
            case 4: drawCloud(c, p, S * .50f, S * .40f, S * .92f, day);
                    drawRain(c, p, S, day); break;
            case 5: drawCloud(c, p, S * .50f, S * .38f, S * .92f, false);
                    drawBolt(c, p, S); break;
            case 6: drawCloud(c, p, S * .50f, S * .40f, S * .88f, day);
                    drawSnow(c, p, S); break;
            default: break;
        }
        return bmp;
    }

    private static void drawSun(Canvas c, Paint p, float cx, float cy, float r) {
        p.setShader(new RadialGradient(cx, cy, r * 2.4f,
            new int[]{0x66FFD75E, 0x00FFD75E}, new float[]{0.25f, 1f},
            Shader.TileMode.CLAMP));
        c.drawCircle(cx, cy, r * 2.4f, p);
        p.setShader(null);
        p.setColor(0xFFFFE07A); c.drawCircle(cx, cy, r, p);
        p.setColor(0xFFFFF3C4); c.drawCircle(cx - r * .18f, cy - r * .18f, r * .55f, p);
    }

    private static void drawMoon(Canvas c, Paint p, float cx, float cy, float r) {
        p.setColor(0x26C8D5FF); c.drawCircle(cx, cy, r * 1.9f, p);
        p.setColor(0xFFEDF0FA); c.drawCircle(cx, cy, r, p);
        p.setColor(0xFFC7CFE2); c.drawCircle(cx + r * .32f, cy + r * .18f, r * .92f, p);
        p.setColor(0x335A6591);
        c.drawCircle(cx - r * .24f, cy - r * .12f, r * .22f, p);
        c.drawCircle(cx + r * .18f, cy - r * .34f, r * .14f, p);
    }

    private static void drawCloud(Canvas c, Paint p, float cx, float cy, float w, boolean day) {
        p.setColor(day ? 0xFFD9E1F0 : 0xFF9AA6C2);
        float h = w * .52f;
        c.drawCircle(cx - w * .24f, cy - h * .12f, h * .58f, p);
        c.drawCircle(cx + w * .10f, cy - h * .30f, h * .72f, p);
        c.drawCircle(cx + w * .34f, cy - h * .08f, h * .50f, p);
        c.drawRoundRect(cx - w * .46f, cy - h * .30f, cx + w * .46f, cy + h * .28f,
            h * .30f, h * .30f, p);
        p.setColor(day ? 0x24000000 : 0x2A000000);
        c.drawRoundRect(cx - w * .44f, cy + h * .06f, cx + w * .44f, cy + h * .28f,
            h * .24f, h * .24f, p);
    }

    private static void drawRain(Canvas c, Paint p, int S, boolean day) {
        p.setColor(day ? 0xA9BDC8FF : 0x80A9C4FF);
        p.setStrokeWidth(S * .024f);
        float y0 = S * .62f;
        for (int i = 0; i < 4; i++) {
            float x = S * (.30f + i * .13f);
            c.drawLine(x, y0, x - S * .05f, y0 + S * .14f, p);
        }
    }

    private static void drawSnow(Canvas c, Paint p, int S) {
        p.setColor(0xF2FFFFFF);
        for (int i = 0; i < 5; i++) {
            float x = S * (.26f + i * .12f);
            float y = S * (.64f + (i % 2) * .08f);
            c.drawCircle(x, y, S * (.020f + (i % 3) * .006f), p);
        }
    }

    private static void drawFog(Canvas c, Paint p, int S) {
        p.setColor(0x82E4E9F2);
        p.setStrokeWidth(S * .045f);
        for (int i = 0; i < 3; i++) {
            float y = S * (.52f + i * .13f);
            c.drawLine(S * .22f, y, S * .78f - i * S * .06f, y, p);
        }
    }

    private static void drawBolt(Canvas c, Paint p, int S) {
        Path path = new Path();
        path.moveTo(S * .46f, S * .52f);
        path.lineTo(S * .34f, S * .70f);
        path.lineTo(S * .44f, S * .70f);
        path.lineTo(S * .34f, S * .88f);
        path.lineTo(S * .58f, S * .64f);
        path.lineTo(S * .46f, S * .64f);
        path.lineTo(S * .56f, S * .52f);
        path.close();
        p.setColor(0xFFFFE16D);
        c.drawPath(path, p);
    }

    /* ═══════════════════ NOTIFICACIONES/CANALES ═══════════════════ */

    private static void ensureChannels(Context ctx) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        if (nm == null) return;
        // Canal v1 (7.44–7.46): sus settings son inmutables una vez creado,
        // así que no admite el sonido de alarma — se borra para que todos
        // los usuarios queden en el v2 con la configuración completa.
        try {
            if (nm.getNotificationChannel(CH_QUAKES_LEGACY) != null) {
                nm.deleteNotificationChannel(CH_QUAKES_LEGACY);
            }
        } catch (Throwable ignored) {}
        if (nm.getNotificationChannel(CH_WIDGET) == null) {
            NotificationChannel ch = new NotificationChannel(CH_WIDGET,
                "Clima · widget", NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("Clima y sismos en el panel de notificaciones");
            ch.setShowBadge(false);
            ch.setSound(null, null);
            ch.enableVibration(false);
            nm.createNotificationChannel(ch);
        }
        if (nm.getNotificationChannel(CH_QUAKES) == null) {
            // v2: suena en el stream de ALARMA (se escucha aunque el volumen
            // de notificaciones esté bajo o el teléfono en silencio parcial),
            // vibración fuerte y pedido de bypass a "No molestar" (el usuario
            // manda en DND; los sistemas que permiten alarmas lo respetan).
            NotificationChannel ch = new NotificationChannel(CH_QUAKES,
                "Sismos (alarma)", NotificationManager.IMPORTANCE_HIGH);
            ch.setDescription("Alertas de sismos cercanos a tu ubicación");
            ch.enableVibration(true);
            ch.setVibrationPattern(new long[]{0, 500, 250, 500, 250, 900});
            ch.setBypassDnd(true);
            AudioAttributes alarm = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build();
            ch.setSound(android.provider.Settings.System.DEFAULT_ALARM_ALERT_URI, alarm);
            nm.createNotificationChannel(ch);
        }
    }

    private static void notifyWidget(Context ctx, Notification n) {
        ensureChannels(ctx);
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        if (nm != null) nm.notify(NOTIF_ID, n);
    }

    private static void notifyQuake(Context ctx, Notification n) {
        ensureChannels(ctx);
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        if (nm != null) nm.notify(NOTIF_ID_QUAKE, n);
    }

    private static void cancel(Context ctx, int id) {
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        if (nm != null) nm.cancel(id);
    }
}
