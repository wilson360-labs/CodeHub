package com.codehub.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
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
 * Es la notificación persistente elegante de CodeHub:
 * - Estilo propio (RemoteViews con layout notification_weather.xml):
 *   astro pintado en canvas (sol/luna/nube/lluvia/ tormenta/nieve/niebla —
 *   la misma familia del widget del launcher), ciudad, temperatura, marcante
 *   condición + sensación, chips (humedad · viento · lluvia) y fila de
 *   SISMOS con magnitud, distancia y rumbo DESDE tu ubicación (haversine).
 * - La ubicación se toma EXACTAMENTE de donde el app guarda la real
 *   (prefs "codehub": lat_bits / lon_bits / widget_city — lo mismo que
 *   escribe el puente saveLocation y CodeHubSyncWorker cada 15 min).
 *   Sin ubicación: no se publica nada (nunca datos falsos).
 * - ALARMA de sismos: si aparece un M≥4.5 a ≤1200 km que no sonó antes,
 *   dispara una notificación aparte (canal heads-up) con la distancia.
 * - Vive en canal IMPORTANCE_LOW: sin sonido, sin badge, ongoing.
 *
 * Se refresca: en cada arranque (MainActivity), en cada ciclo del sync
 * (CodeHubSyncWorker) y tras reiniciar el equipo (BootReceiver).
 */
public class WeatherNotificationProvider {

    private static final String TAG = "CodeHubWxNotif";

    private static final String PREFS = "codehub";
    private static final String PREF_CACHE = "wx_notif_cache";
    private static final String PREF_QUAKE_LAST = "quake_last_alarm_id";

    private static final String CH_WIDGET = "codehub_weather_widget";
    private static final String CH_QUAKES = "codehub_quakes";

    private static final int NOTIF_ID = 4242;       // widget de clima
    private static final int NOTIF_ID_QUAKE = 4243; // alarma de sismo

    private static final int ALERT_MAG_X10 = 45;    // 4.5 magnitud
    private static final int ALERT_RADIUS_KM = 1200;
    private static final long ALERT_WINDOW_MS = 24L * 60 * 60 * 1000;
    private static final double ROW_MAG_MIN = 3.5;  // fila informativa

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
            prefs.edit().putString(PREF_CACHE, payload.toString()).apply();
            paintFromPayload(context, payload, lat, lon, city, false);
            if (payload.has("quake")) { /* fila ya integrada si viene */ }
        }

        // 3) Sismos: la fila del widget Y la alarma se evalúan aunque el
        //    clima haya fallado (no se pierde un sismo grande por un 500).
        JSONObject quake = fetchQuake(lat, lon);
        if (quake != null) {
            if (payload == null && cache != null) {
                // Repintar la fila de sismos sobre la caché del clima.
                try {
                    JSONObject cached = new JSONObject(cache);
                    cached.put("quake", quake);
                    prefs.edit().putString(PREF_CACHE, cached.toString()).apply();
                    paintFromPayload(context, cached, lat, lon, city, true);
                } catch (Throwable ignored) {}
            } else if (payload == null) {
                try {
                    JSONObject solo = new JSONObject();
                    solo.put("at", System.currentTimeMillis());
                    solo.put("quake", quake);
                    paintFromPayload(context, solo, lat, lon, city, true);
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

            RemoteViews views = new RemoteViews(context.getPackageName(),
                R.layout.notification_weather);

            Bitmap astro = paintAstro(code, day);
            if (astro != null) views.setImageViewBitmap(R.id.n_w_icon, astro);

            views.setTextViewText(R.id.n_w_city,
                (city != null && !city.isEmpty()) ? city : "Mi ubicación");
            SimpleDateFormat fmt = new SimpleDateFormat("HH:mm", Locale.getDefault());
            views.setTextViewText(R.id.n_w_time, fmt.format(new Date(at)));
            views.setTextViewText(R.id.n_w_temp, Float.isNaN(temp) ? "--°" : Math.round(temp) + "°");
            views.setTextViewText(R.id.n_w_desc, describe(code, day)
                + (Float.isNaN(feels) ? "" : " · " + Math.round(feels) + "°"));

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
            views.setTextViewText(R.id.n_w_data, data.toString());

            if (q != null && q.optDouble("mag", -1) > 0) {
                float mag = (float) q.optDouble("mag", 0);
                double dist = haversineKm(lat, lon, q.optDouble("lat", 0), q.optDouble("lon", 0));
                String dir = bearingTo8(lat, lon, q.optDouble("lat", 0), q.optDouble("lon", 0));
                String when = hourOf(q.optLong("time", 0), 0);
                String row = "⚠ Sismo M" + String.format(Locale.US, "%.1f", mag)
                    + " · " + Math.round(dist) + " km " + dir
                    + " · " + when
                    + " · prof. " + Math.round(q.optDouble("depth", 0)) + " km";
                views.setTextViewText(R.id.n_w_quake, row);
                views.setViewVisibility(R.id.n_w_quake,
                    dist <= ALERT_RADIUS_KM ? View.VISIBLE : View.GONE);
            } else {
                views.setViewVisibility(R.id.n_w_quake, View.GONE);
            }

            notifyWidget(context, buildWidgetNotification(context, views));
        } catch (Throwable t) {
            Log.e(TAG, "paintFromPayload falló", t);
        }
    }

    private static Notification buildWidgetNotification(Context context, RemoteViews views) {
        Intent open = new Intent(context, MainActivity.class);
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(context, 2001, open,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        return new NotificationCompat.Builder(context, CH_WIDGET)
            .setSmallIcon(R.drawable.ic_stat_weather)
            .setContentTitle("Clima · CodeHub")
            .setContentText("Widget del clima en el panel de notificaciones")
            .setCustomContentView(views)
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
                + " · hace " + (agoMin < 90 ? agoMin + " min" : hourOf(time, 0))
                + " · profundidad " + Math.round(quake.optDouble("depth", 0)) + " km";

            Intent open = new Intent(context, MainActivity.class);
            open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            PendingIntent pi = PendingIntent.getActivity(context, 3002, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

            NotificationManagerHelper.notifyQuake(context,
                new NotificationCompat.Builder(context, CH_QUAKES)
                    .setSmallIcon(R.drawable.ic_stat_quake)
                    .setContentTitle(title)
                    .setContentText(text)
                    .setStyle(new NotificationCompat.BigTextStyle().bigText(title + "\n" + text))
                    .setAutoCancel(true)
                    .setOnlyAlertOnce(true)
                    .setContentIntent(pi)
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .build());
        } catch (Throwable t) {
            Log.e(TAG, "evalQuakeAlarm falló", t);
        }
    }

    /* ════════════════════════ DATOS ════════════════════════ */

    /** Payload del widget: "current" de Open-Meteo (con is_day). */
    private static JSONObject fetchWeather(double lat, double lon) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL("https://api.open-meteo.com/v1/forecast?latitude=" + lat
                + "&longitude=" + lon
                + "&current=temperature_2m,apparent_temperature,relative_humidity_2m,weather_code,wind_speed_10m,is_day"
                + "&hourly=precipitation_probability"
                + "&forecast_hours=6&timezone=auto");
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
     * Sismo del día: el más STRONG en 24 h a ≤1200 km (USGS, público).
     * Decide la fila del widget y la alarma.
     */
    private static JSONObject fetchQuake(double lat, double lon) {
        HttpURLConnection conn = null;
        try {
            SimpleDateFormat day = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
            day.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
            String since = day.format(new Date(System.currentTimeMillis() - ALERT_WINDOW_MS));
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

            org.json.JSONObject json = new org.json.JSONObject(body);
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

    private static String hourOf(long epochMs, long offsetSec) {
        SimpleDateFormat fmt = new SimpleDateFormat("HH:mm", Locale.US);
        fmt.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
        return fmt.format(new Date(epochMs - offsetSec * 1000));
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
            default: return "—";
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
     * familia de color del widget del launcher.
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
            NotificationChannel ch = new NotificationChannel(CH_QUAKES,
                "Sismos (alarma)", NotificationManager.IMPORTANCE_HIGH);
            ch.setDescription("Alertas de sismos cercanos a tu ubicación");
            ch.enableVibration(true);
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
