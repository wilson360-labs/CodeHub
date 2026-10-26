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
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
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
 * - COMPACTA: ícono + "13° Nublado" + RESUMEN INTELIGENTE + ciudad.
 * - EXPANDIDA: ciudad real, temperatura, condición, sensación/máx/mín,
 *   resumen inteligente ("Lluvia probable a las 17h · lleva paraguas"),
 *   chips (humedad, viento, lluvia, próximo sol), pronóstico
 *   "Ahora + 3 horas" con % de lluvia por hora y fila de sismos (siempre
 *   visible: con sismo, o "sin actividad relevante").
 * - El fondo cambia según condición/hora (día, noche, nublado, lluvia,
 *   tormenta) y la pastilla de resumen según severidad.
 * - Ciudad: la elegida por el usuario; si no hay, geocodificación inversa
 *   (Nominatim → BigDataCloud) cacheada por celda de ~1 km.
 * - Datos: Open-Meteo (current + hourly + daily) y USGS para sismos.
 * - Sin ubicación real: no se publica nada (nunca datos falsos).
 * - ALARMA de sismos: M ≥ 4.5 a ≤1200 km no vista antes → heads-up aparte.
 *   Se evalúa PRIMERO (antes del clima) para no retrasarla.
 *
 * CADENCIA: al abrir la app, en cada ciclo del sync nativo (~15 min),
 * tras reiniciar y cada vez que cambia la ubicación guardada.
 */
public class WeatherNotificationProvider {

    private static final String TAG = "CodeHubWxNotif";

    private static final String PREFS = "codehub";
    private static final String PREF_CACHE = "wx_notif_cache";
    private static final String PREF_QUAKE_LAST = "quake_last_alarm_id";
    private static final String PREF_GEO_KEY = "notif_geo_key";
    private static final String PREF_GEO_CITY = "notif_geo_city";

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

    private static final int HOUR_SLOTS = 4;        // "Ahora" + 3 horas
    private static final int HOURS_KEEP = 12;       // horas guardadas para el resumen
    private static final double CACHE_MAX_KM = 15;  // caché de otra ciudad → se descarta
    private static final long STALE_MS = 20L * 60 * 1000;

    private static final AtomicBoolean sRefreshing = new AtomicBoolean(false);
    private static final AtomicBoolean sRerun = new AtomicBoolean(false);

    private WeatherNotificationProvider() {}

    /* ════════════════════════ API PÚBLICA ════════════════════════ */

    /**
     * Refresca en segundo plano (hilo corto con single-flight). Si llega
     * una petición mientras hay otra en curso (p. ej. cambió la ubicación)
     * se encola UNA repetición en vez de descartarla.
     */
    public static void refresh(final Context context) {
        final Context app = context.getApplicationContext();
        if (!sRefreshing.compareAndSet(false, true)) { sRerun.set(true); return; }
        new Thread(new Runnable() {
            @Override public void run() {
                try { update(app); }
                catch (Throwable t) { Log.e(TAG, "refresh falló", t); }
                finally {
                    sRefreshing.set(false);
                    if (sRerun.getAndSet(false)) refresh(app);
                }
            }
        }).start();
    }

    /* ════════════════════════ MOTOR ════════════════════════ */

    private static void update(Context context) {
        if (!canPostNotifications(context)) return;   // sin permiso: silencio
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        double lat = Double.longBitsToDouble(prefs.getLong("lat_bits", 0));
        double lon = Double.longBitsToDouble(prefs.getLong("lon_bits", 0));

        if (lat == 0 && lon == 0) {
            // Sin ubicación real guardada: nada se publica (política "en
            // cero" de CodeHub — jamás una ciudad inventada).
            cancel(context, NOTIF_ID);
            return;
        }

        String city = cityFromPrefs(prefs, lat, lon);

        // 1) Pintar YA con la caché (instantáneo al arrancar; luego red).
        //    Una caché de OTRA zona (>15 km) se descarta: nunca mostrar el
        //    clima de la ciudad anterior con la ubicación nueva.
        String cache = prefs.getString(PREF_CACHE, null);
        JSONObject cacheObj = null;
        if (cache != null) {
            try {
                cacheObj = new JSONObject(cache);
                if (cacheObj.has("lat") && cacheObj.has("lon")
                    && haversineKm(lat, lon, cacheObj.optDouble("lat"), cacheObj.optDouble("lon")) > CACHE_MAX_KM) {
                    cacheObj = null;
                }
            } catch (Throwable t) { cacheObj = null; }
        }
        if (cacheObj != null && cacheObj.has("code")) {
            paintFromPayload(context, cacheObj, lat, lon, city, true);
        }

        // 2) Ciudad real (si el usuario no eligió una): geocodificación inversa.
        if (city.isEmpty()) {
            String g = reverseCity(prefs, lat, lon);
            if (!g.isEmpty()) {
                city = g;
                if (cacheObj != null && cacheObj.has("code")) {
                    paintFromPayload(context, cacheObj, lat, lon, city, true);
                }
            }
        }

        // 3) Sismos PRIMERO: la alarma no debe esperar al clima (ni
        //    perderse por un 500 de Open-Meteo).
        JSONObject quake = fetchQuake(lat, lon);
        if (quake != null) evalQuakeAlarm(context, quake, lat, lon);

        // 4) Clima en vivo (Open-Meteo; misma fuente que la tarjeta web).
        JSONObject payload = fetchWeather(lat, lon);
        boolean live = payload != null;
        JSONObject base = live ? payload : cacheObj;
        if (base == null || !base.has("code")) return;      // sin datos de clima: no pintar

        try {
            if (quake != null) {
                base.put("quake", quake);
            } else if (live && cacheObj != null && cacheObj.has("quake") && !base.has("quake")) {
                base.put("quake", cacheObj.optJSONObject("quake")); // no "destella" a vacío
            }
            prefs.edit().putString(PREF_CACHE, base.toString()).apply();
        } catch (Throwable ignored) {}
        paintFromPayload(context, base, lat, lon, city, !live);
    }

    /* ════════════════════════ CIUDAD ════════════════════════ */

    private static boolean genericCity(String c) {
        if (c == null) return true;
        String s = c.trim();
        return s.isEmpty() || s.equalsIgnoreCase("GPS") || s.equalsIgnoreCase("CodeHub")
            || s.equalsIgnoreCase("Tu ubicación") || s.equalsIgnoreCase("Mi ubicación");
    }

    private static String geoKey(double lat, double lon) {
        return String.format(Locale.US, "%.2f,%.2f", lat, lon); // celda ~1 km
    }

    /** Ciudad sin red: la elegida por el usuario o la geocodificada en caché. */
    private static String cityFromPrefs(SharedPreferences prefs, double lat, double lon) {
        String chosen = prefs.getString("widget_city", "");
        if (!genericCity(chosen)) return chosen.trim();
        if (geoKey(lat, lon).equals(prefs.getString(PREF_GEO_KEY, ""))) {
            String c = prefs.getString(PREF_GEO_CITY, "");
            if (!genericCity(c)) return c;
        }
        return "";
    }

    /** Geocodificación inversa (con red): Nominatim, y BigDataCloud de respaldo. */
    private static String reverseCity(SharedPreferences prefs, double lat, double lon) {
        String name = "";
        try {
            JSONObject j = getJson("https://nominatim.openstreetmap.org/reverse?format=jsonv2&zoom=12"
                + "&accept-language=es&lat=" + lat + "&lon=" + lon);
            JSONObject a = j == null ? null : j.optJSONObject("address");
            if (a != null) {
                String[] keys = {"city", "town", "village", "municipality", "suburb", "city_district", "county", "state"};
                for (String k : keys) {
                    String v = a.optString(k, "");
                    if (!v.isEmpty()) { name = v; break; }
                }
            }
        } catch (Throwable ignored) {}
        if (name.isEmpty()) {
            try {
                JSONObject j = getJson("https://api.bigdatacloud.net/data/reverse-geocode-client?latitude="
                    + lat + "&longitude=" + lon + "&localityLanguage=es");
                if (j != null) {
                    name = j.optString("city", "");
                    if (name.isEmpty()) name = j.optString("locality", "");
                    if (name.isEmpty()) name = j.optString("principalSubdivision", "");
                }
            } catch (Throwable ignored) {}
        }
        if (!genericCity(name)) {
            prefs.edit().putString(PREF_GEO_KEY, geoKey(lat, lon)).putString(PREF_GEO_CITY, name).apply();
            return name;
        }
        return "";
    }

    private static JSONObject getJson(String u) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(u).openConnection();
            conn.setConnectTimeout(7000);
            conn.setReadTimeout(7000);
            conn.setRequestProperty("User-Agent", "CodeHub-Android/1.0");
            conn.setRequestProperty("Accept", "application/json");
            if (conn.getResponseCode() != 200) return null;
            InputStream is = conn.getInputStream();
            Scanner sc = new Scanner(is, "UTF-8").useDelimiter("\\A");
            String body = sc.hasNext() ? sc.next() : "{}";
            sc.close();
            return new JSONObject(body);
        } catch (Throwable t) {
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /* ════════════════════════ PINTAR ════════════════════════ */

    private static void paintFromPayload(Context context, JSONObject payload,
                                         double lat, double lon,
                                         String city, boolean cached) {
        try {
            long now = System.currentTimeMillis();
            int code = payload.optInt("code", -1);
            boolean day = payload.optBoolean("day", true);
            float temp  = (float) payload.optDouble("temp", Double.NaN);
            float feels = (float) payload.optDouble("feels", Double.NaN);
            int humidity = payload.optInt("humidity", -1);
            float wind = (float) payload.optDouble("wind", Double.NaN);
            int rain = payload.optInt("rain", -1);
            float tmax = (float) payload.optDouble("tmax", Double.NaN);
            float tmin = (float) payload.optDouble("tmin", Double.NaN);
            long at = payload.optLong("at", now);

            JSONObject q = payload.optJSONObject("quake");
            JSONArray future = futureHours(payload.optJSONArray("hours"), now);

            Bitmap astro = paintAstro(code, day);
            SimpleDateFormat fmt = new SimpleDateFormat("HH:mm", Locale.getDefault());
            String cityStr = (city != null && !city.isEmpty()) ? city : "Tu ubicación";

            long age = Math.max(0, now - at);
            boolean stale = age >= STALE_MS;
            String timeStr = stale ? ago(age) : fmt.format(new Date(at));

            String tempStr = Float.isNaN(temp) ? "--°" : Math.round(temp) + "°";
            String condStr = describe(code, day);

            StringBuilder sub = new StringBuilder();
            if (!Float.isNaN(feels)) sub.append("Sens. ").append(Math.round(feels)).append('°');
            if (!Float.isNaN(tmax)) {
                if (sub.length() > 0) sub.append("  ·  ");
                sub.append("Máx ").append(Math.round(tmax)).append('°');
            }
            if (!Float.isNaN(tmin)) {
                if (sub.length() > 0) sub.append("  ·  ");
                sub.append("Mín ").append(Math.round(tmin)).append('°');
            }

            int[] sev = new int[1];
            String smart = smartLine(code, temp, feels, future, now, sev);

            int bg = backgroundFor(code, day);

            /* ── COMPACTA ── */
            RemoteViews compact = new RemoteViews(context.getPackageName(),
                R.layout.notification_weather_compact);
            if (astro != null) compact.setImageViewBitmap(R.id.n_c_icon, astro);
            compact.setInt(R.id.n_c_root, "setBackgroundResource", bg);
            compact.setTextViewText(R.id.n_c_main, condStr.isEmpty() ? tempStr : tempStr + "  " + condStr);
            compact.setTextViewText(R.id.n_c_smart, smart);
            compact.setTextViewText(R.id.n_c_side, stale ? cityStr + " · " + timeStr : cityStr);

            /* ── EXPANDIDA ── */
            RemoteViews big = new RemoteViews(context.getPackageName(),
                R.layout.notification_weather);
            if (astro != null) big.setImageViewBitmap(R.id.n_w_icon, astro);
            big.setInt(R.id.n_w_root, "setBackgroundResource", bg);
            big.setTextViewText(R.id.n_w_city, cityStr);
            big.setTextViewText(R.id.n_w_time, timeStr);
            big.setTextColor(R.id.n_w_time, stale ? 0xFFFFC46B : 0xFFB4BED0);
            big.setTextViewText(R.id.n_w_temp, tempStr);
            big.setTextViewText(R.id.n_w_desc, condStr.isEmpty() ? cityStr : condStr);
            big.setTextViewText(R.id.n_w_sub, sub.toString());

            big.setTextViewText(R.id.n_w_smart, smart);
            big.setInt(R.id.n_w_smart, "setBackgroundResource",
                sev[0] >= 2 ? R.drawable.bg_pill_alert
                    : sev[0] == 1 ? R.drawable.bg_pill_warn : R.drawable.bg_pill_info);

            big.setTextViewText(R.id.n_w_hum, humidity >= 0 ? humidity + "%" : "--");
            big.setTextViewText(R.id.n_w_wind, Float.isNaN(wind) ? "--" : Math.round(wind) + " km/h");
            big.setTextViewText(R.id.n_w_rain, rain >= 0 ? rain + "%" : "--");
            big.setTextViewText(R.id.n_w_sun, sunText(payload, now));

            fillHourSlots(big, temp, code, day, rain, future);

            /* Fila de sismos: SIEMPRE visible (con sismo o "sin actividad"). */
            boolean showQuake = q != null && q.optDouble("mag", -1) > 0
                && (now - q.optLong("time", 0)) <= ALERT_WINDOW_MS;
            if (showQuake) {
                float mag = (float) q.optDouble("mag", 0);
                double dist = haversineKm(lat, lon, q.optDouble("lat", 0), q.optDouble("lon", 0));
                String dir = bearingTo8(lat, lon, q.optDouble("lat", 0), q.optDouble("lon", 0));
                String row = "Sismo M" + String.format(Locale.US, "%.1f", mag)
                    + " · " + Math.round(dist) + " km " + dir
                    + " · " + localTime(q.optLong("time", 0))
                    + " · prof. " + Math.round(q.optDouble("depth", 0)) + " km";
                big.setTextViewText(R.id.n_w_quake, row);
                big.setTextColor(R.id.n_w_quake,
                    mag >= 4.5f ? 0xFFFF9B8A : 0xFFFFD27A);
            } else {
                big.setTextViewText(R.id.n_w_quake, "Sismos: sin actividad relevante (M3.5+ · 24 h)");
                big.setTextColor(R.id.n_w_quake, 0xFF8FD3A8);
            }

            notifyWidget(context, buildWidgetNotification(context, compact, big));
        } catch (Throwable t) {
            Log.e(TAG, "paintFromPayload falló", t);
        }
    }

    private static String ago(long ageMs) {
        long min = ageMs / 60000L;
        if (min < 90) return "hace " + Math.max(1, min) + " min";
        return "hace " + (min / 60) + " h";
    }

    /** Fondo según condición y hora (día/noche/nublado/lluvia/tormenta). */
    private static int backgroundFor(int code, boolean day) {
        switch (bucket(code)) {
            case 5: return R.drawable.bg_notif_storm;
            case 4: case 6: return R.drawable.bg_notif_rain;
            case 2: case 3: return day ? R.drawable.bg_notif_cloud : R.drawable.bg_notif_night;
            default: return day ? R.drawable.bg_notif_clear_day : R.drawable.bg_notif_night;
        }
    }

    /** Horas futuras guardadas (ts > ahora), en orden. */
    private static JSONArray futureHours(JSONArray hours, long now) {
        JSONArray out = new JSONArray();
        if (hours == null) return out;
        for (int i = 0; i < hours.length(); i++) {
            JSONObject h = hours.optJSONObject(i);
            if (h == null) continue;
            if (h.optLong("ts", 0) > now) out.put(h);
        }
        return out;
    }

    /** "Ahora" + las 3 próximas horas, con temperatura, ícono y % de lluvia. */
    private static void fillHourSlots(RemoteViews big, float tNow, int codeNow, boolean dayNow,
                                      int rainNow, JSONArray future) {
        int[][] ids = {
            {R.id.n_w_h1_icon, R.id.n_w_h1_temp, R.id.n_w_h1_hour, R.id.n_w_h1_rain},
            {R.id.n_w_h2_icon, R.id.n_w_h2_temp, R.id.n_w_h2_hour, R.id.n_w_h2_rain},
            {R.id.n_w_h3_icon, R.id.n_w_h3_temp, R.id.n_w_h3_hour, R.id.n_w_h3_rain},
            {R.id.n_w_h4_icon, R.id.n_w_h4_temp, R.id.n_w_h4_hour, R.id.n_w_h4_rain},
        };
        boolean show = future.length() >= 1;
        big.setViewVisibility(R.id.n_w_hours, show ? View.VISIBLE : View.GONE);
        if (!show) return;
        for (int i = 0; i < HOUR_SLOTS; i++) {
            int icoCode; boolean icoDay; float t; String label; int rain;
            if (i == 0) {
                icoCode = codeNow; icoDay = dayNow; t = tNow; label = "Ahora"; rain = rainNow;
            } else {
                JSONObject h = future.optJSONObject(i - 1);
                if (h == null) {
                    big.setViewVisibility(ids[i][0], View.INVISIBLE);
                    big.setTextViewText(ids[i][1], "");
                    big.setTextViewText(ids[i][2], "");
                    big.setTextViewText(ids[i][3], "");
                    continue;
                }
                icoCode = h.optInt("code", -1); icoDay = h.optBoolean("day", true);
                t = (float) h.optDouble("temp", Double.NaN);
                label = h.optString("h", ""); rain = h.optInt("rain", -1);
            }
            Bitmap mini = paintAstro(icoCode, icoDay);
            if (mini != null) {
                big.setImageViewBitmap(ids[i][0], mini);
                big.setViewVisibility(ids[i][0], View.VISIBLE);
            }
            big.setTextViewText(ids[i][1], Float.isNaN(t) ? "—" : Math.round(t) + "°");
            big.setTextViewText(ids[i][2], label);
            big.setTextViewText(ids[i][3], rain >= 20 ? rain + "%" : "");
        }
    }

    /** "Atardece 17:32" / "Amanece 05:48" según lo próximo que ocurra. */
    private static String sunText(JSONObject p, long now) {
        try {
            JSONArray sr = p.optJSONArray("sr"), ss = p.optJSONArray("ss");
            JSONArray srl = p.optJSONArray("srl"), ssl = p.optJSONArray("ssl");
            if (sr == null || ss == null || srl == null || ssl == null) return "";
            long sr0 = sr.optLong(0, 0), ss0 = ss.optLong(0, 0), sr1 = sr.optLong(1, 0);
            if (sr0 > 0 && now < sr0) return "Amanece " + srl.optString(0, "");
            if (ss0 > 0 && now < ss0) return "Atardece " + ssl.optString(0, "");
            if (sr1 > 0) return "Amanece " + srl.optString(1, "");
        } catch (Throwable ignored) {}
        return "";
    }

    /* ═══════════════════ RESUMEN INTELIGENTE ═══════════════════ */

    private static boolean wet(int b) { return b == 4 || b == 5 || b == 6; }

    /**
     * Una frase útil a partir de lo que viene en las próximas horas.
     * sev[0]: 0 = informativo, 1 = precaución (ámbar), 2 = alerta (rojo).
     */
    private static String smartLine(int codeNow, float temp, float feels,
                                    JSONArray fut, long now, int[] sev) {
        sev[0] = 0;
        int bNow = bucket(codeNow);
        int n = Math.min(fut.length(), 8);

        // Tormenta ahora o en ≤3 h
        if (bNow == 5) { sev[0] = 2; return "Tormenta eléctrica ahora · evita zonas abiertas"; }
        for (int i = 0; i < n && i < 3; i++) {
            JSONObject h = fut.optJSONObject(i);
            if (h != null && bucket(h.optInt("code", -1)) == 5) {
                sev[0] = 2;
                return "Tormenta eléctrica " + inHours(h, now) + " · evita zonas abiertas";
            }
        }

        // Lloviendo / nevando ahora: ¿cuándo mejora?
        if (wet(bNow)) {
            sev[0] = 1;
            String what = bNow == 6 ? "Nieve" : "Lluvia";
            for (int i = 0; i < n; i++) {
                JSONObject h = fut.optJSONObject(i);
                if (h == null) continue;
                if (!wet(bucket(h.optInt("code", -1))) && h.optInt("rain", 0) < 40) {
                    return what + " ahora · mejora hacia las " + h.optString("h", "");
                }
            }
            return what + " ahora · sin mejora en las próximas horas";
        }

        // Lluvia próxima (código mojado o probabilidad alta)
        for (int i = 0; i < n; i++) {
            JSONObject h = fut.optJSONObject(i);
            if (h == null) continue;
            int r = h.optInt("rain", 0);
            if (wet(bucket(h.optInt("code", -1))) || r >= 55) {
                sev[0] = 1;
                int hrs = hoursAhead(h, now);
                String pct = r > 0 ? " (" + r + "%)" : "";
                return hrs <= 1
                    ? "Lluvia inminente" + pct + " · lleva paraguas"
                    : "Lluvia probable a las " + h.optString("h", "") + pct + " · lleva paraguas";
            }
        }

        // Cambios de cielo
        if (bNow == 2 || bNow == 3) {
            for (int i = 0; i < n && i < 6; i++) {
                JSONObject h = fut.optJSONObject(i);
                if (h != null && bucket(h.optInt("code", -1)) <= 1 && h.optBoolean("day", true)) {
                    return "Se despeja hacia las " + h.optString("h", "");
                }
            }
        }

        // Tendencia de temperatura (próximas 6 h)
        if (!Float.isNaN(temp)) {
            double lo = temp, hi = temp; String loH = "", hiH = "";
            for (int i = 0; i < n && i < 6; i++) {
                JSONObject h = fut.optJSONObject(i);
                if (h == null) continue;
                double t = h.optDouble("temp", Double.NaN);
                if (Double.isNaN(t)) continue;
                if (t < lo) { lo = t; loH = h.optString("h", ""); }
                if (t > hi) { hi = t; hiH = h.optString("h", ""); }
            }
            if (temp - lo >= 4) return "Baja a " + Math.round(lo) + "° hacia las " + loH;
            if (hi - temp >= 4) return "Sube a " + Math.round(hi) + "° hacia las " + hiH;
        }

        // Confort
        float ref = !Float.isNaN(feels) ? Math.max(feels, Float.isNaN(temp) ? feels : temp) : temp;
        if (!Float.isNaN(ref)) {
            if (ref >= 33) { sev[0] = 1; return "Calor intenso · hidrátate y evita el sol directo"; }
            if (ref >= 28) return "Cálido · usa protector solar";
            if (ref <= 8)  { sev[0] = 1; return "Mucho frío · abrígate bien"; }
            if (ref <= 14) return "Fresco · lleva una chaqueta";
        }
        return "Sin cambios importantes en las próximas horas";
    }

    private static int hoursAhead(JSONObject h, long now) {
        return (int) Math.max(1, Math.round((h.optLong("ts", now) - now) / 3600000.0));
    }

    private static String inHours(JSONObject h, long now) {
        int n = hoursAhead(h, now);
        return n <= 1 ? "en ~1 h" : "en ~" + n + " h";
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
            .setShowWhen(false)
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

            // Pantalla completa para sismos grandes (M≥ umbral configurable, default 5.0)
            float threshold = 5.0f;
            try {
                threshold = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                        .getFloat("quake_fullscreen_mag", 5.0f);
            } catch (Exception ignored) {}
            if (mag >= threshold) {
                try {
                    EarthquakeAlertActivity.launch(context, mag, dist, dir, place, time, false);
                } catch (Exception ignored) {}
            }

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
     * Payload del widget: "current" + próximas horas (Open-Meteo: temp,
     * código, prob. de lluvia), máx/mín del día y sol de hoy y mañana.
     */
    private static JSONObject fetchWeather(double lat, double lon) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL("https://api.open-meteo.com/v1/forecast?latitude=" + lat
                + "&longitude=" + lon
                + "&current=temperature_2m,apparent_temperature,relative_humidity_2m,weather_code,wind_speed_10m,is_day"
                + "&hourly=temperature_2m,weather_code,precipitation_probability&forecast_hours=" + (HOURS_KEEP + 2)
                + "&daily=sunrise,sunset,temperature_2m_max,temperature_2m_min&forecast_days=2"
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
            out.put("lat", lat);
            out.put("lon", lon);

            JSONObject daily = json.optJSONObject("daily");
            if (daily != null) {
                JSONArray mx = daily.optJSONArray("temperature_2m_max");
                JSONArray mn = daily.optJSONArray("temperature_2m_min");
                if (mx != null) putNaNable(out, "tmax", mx.optDouble(0, Double.NaN));
                if (mn != null) putNaNable(out, "tmin", mn.optDouble(0, Double.NaN));
                putSun(out, json, daily);
            }

            JSONArray hours = buildHours(json);
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

    private static SimpleDateFormat isoUtc() {
        SimpleDateFormat iso = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm", Locale.US);
        iso.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
        return iso;
    }

    /** Epoch real de un "yyyy-MM-ddTHH:mm" local de la ubicación. */
    private static long localToEpoch(SimpleDateFormat iso, String t, double offsetMs) {
        try {
            Date d = iso.parse(t);
            return d == null ? 0 : Math.round(d.getTime() - offsetMs);
        } catch (Throwable e) { return 0; }
    }

    /** sunrise/sunset de hoy y mañana: epoch + etiqueta HH:mm local. */
    private static void putSun(JSONObject out, JSONObject json, JSONObject daily) {
        try {
            double offsetMs = json.optDouble("utc_offset_seconds", 0) * 1000.0;
            SimpleDateFormat iso = isoUtc();
            JSONArray rise = daily.optJSONArray("sunrise"), set = daily.optJSONArray("sunset");
            if (rise == null || set == null) return;
            JSONArray sr = new JSONArray(), ss = new JSONArray(), srl = new JSONArray(), ssl = new JSONArray();
            for (int i = 0; i < 2; i++) {
                String a = rise.optString(i, ""), b = set.optString(i, "");
                sr.put(a.length() >= 16 ? localToEpoch(iso, a, offsetMs) : 0);
                ss.put(b.length() >= 16 ? localToEpoch(iso, b, offsetMs) : 0);
                srl.put(a.length() >= 16 ? a.substring(11, 16) : "");
                ssl.put(b.length() >= 16 ? b.substring(11, 16) : "");
            }
            out.put("sr", sr); out.put("ss", ss); out.put("srl", srl); out.put("ssl", ssl);
        } catch (Throwable ignored) {}
    }

    /**
     * Próximas horas [{ts, h:"21h", code, temp, day, rain}]. Los times de
     * Open-Meteo (timezone=auto) son HORA LOCAL de la ubicación: se pasan a
     * epoch restando utc_offset_seconds. "day" usa el sunrise/sunset DEL DÍA
     * de cada hora (no solo el de hoy).
     */
    private static JSONArray buildHours(JSONObject json) {
        try {
            JSONObject hourly = json.optJSONObject("hourly");
            if (hourly == null) return null;
            JSONArray times = hourly.optJSONArray("time");
            JSONArray temps = hourly.optJSONArray("temperature_2m");
            JSONArray codes = hourly.optJSONArray("weather_code");
            JSONArray rains = hourly.optJSONArray("precipitation_probability");
            if (times == null || temps == null || codes == null) return null;

            double offsetMs = json.optDouble("utc_offset_seconds", 0) * 1000.0;
            long now = System.currentTimeMillis();
            SimpleDateFormat iso = isoUtc();

            JSONObject daily = json.optJSONObject("daily");
            JSONArray dTimes = daily == null ? null : daily.optJSONArray("time");
            JSONArray rise = daily == null ? null : daily.optJSONArray("sunrise");
            JSONArray set = daily == null ? null : daily.optJSONArray("sunset");

            JSONArray out = new JSONArray();
            for (int i = 0; i < times.length() && out.length() < HOURS_KEEP; i++) {
                String t = times.optString(i, "");
                if (t.length() < 13) continue;
                long ts = localToEpoch(iso, t, offsetMs);
                if (ts <= now) continue;                       // pasada: saltar
                JSONObject slot = new JSONObject();
                slot.put("ts", ts);
                slot.put("h", t.substring(11, 13) + "h");
                double ht = temps.optDouble(i, Double.NaN);
                slot.put("temp", Double.isNaN(ht) ? JSONObject.NULL : ht);
                slot.put("code", codes.optInt(i, -1));
                slot.put("rain", rains == null ? -1 : rains.optInt(i, -1));
                slot.put("day", isDayAt(t, dTimes, rise, set));
                out.put(slot);
            }
            return out.length() > 0 ? out : null;
        } catch (Throwable ignored) {}
        return null;
    }

    /** ¿La hora local t ("yyyy-MM-ddTHH:mm") cae entre sunrise y sunset de su día? */
    private static boolean isDayAt(String t, JSONArray dTimes, JSONArray rise, JSONArray set) {
        try {
            double hd = Integer.parseInt(t.substring(11, 13))
                + (t.length() >= 16 ? Integer.parseInt(t.substring(14, 16)) : 0) / 60.0;
            double up = 6.0, down = 18.5;
            if (dTimes != null && rise != null && set != null) {
                String date = t.substring(0, 10);
                for (int i = 0; i < dTimes.length(); i++) {
                    if (date.equals(dTimes.optString(i, ""))) {
                        String a = rise.optString(i, ""), b = set.optString(i, "");
                        if (a.length() >= 16) up = Integer.parseInt(a.substring(11, 13)) + Integer.parseInt(a.substring(14, 16)) / 60.0;
                        if (b.length() >= 16) down = Integer.parseInt(b.substring(11, 13)) + Integer.parseInt(b.substring(14, 16)) / 60.0;
                        break;
                    }
                }
            }
            return hd >= up && hd <= down;
        } catch (Throwable e) { return true; }
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
            double offsetMs = json.optDouble("utc_offset_seconds", 0) * 1000.0;
            SimpleDateFormat iso = isoUtc();
            for (int i = 0; i < times.length(); i++) {
                long ms = localToEpoch(iso, times.optString(i, ""), offsetMs);
                if (ms >= now) return rain.optInt(i, -1);
            }
            // todas las horas son anteriores: usar la última disponible
            return rain.optInt(rain.length() - 1, -1);
        } catch (Throwable ignored) {}
        return -1;
    }

    /**
     * Sismo del día: el más fuerte en 24 h a ≤1200 km (USGS, público).
     * Devuelve {"none":true} si la consulta funcionó pero no hubo sismos
     * relevantes (así la fila muestra "sin actividad"), y null si falló la red.
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
            JSONObject none = new JSONObject();
            none.put("none", true);
            if (features == null || features.length() == 0) return none;

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
            if (best == null || bestMag < ROW_MAG_MIN) return none;

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
     * Íconos del clima dibujados en un lienzo lógico de 96×96 y escalados a
     * 192 px. TODA la geometría queda dentro de márgenes de ~6 u, así nada
     * se recorta (la nube anterior se salía por arriba y por la derecha y
     * quedaba pegada arriba). La nube es una sola silueta con degradado
     * vertical, y la nublada lleva una nube trasera para dar volumen.
     * Se reutiliza en los slots horarios.
     */
    static Bitmap paintAstro(int code, boolean day) {
        int b = bucket(code);
        final int S = 192;
        Bitmap bmp = Bitmap.createBitmap(S, S, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        c.scale(S / 96f, S / 96f);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);

        int front = day ? TONE_FRONT_DAY : TONE_FRONT_NIGHT;
        int back = day ? TONE_BACK_DAY : TONE_BACK_NIGHT;

        switch (b) {
            case 0:
                if (day) drawSun(c, p, 48, 48, 19, true);
                else     drawMoon(c, p, 44, 50, 23, true);
                break;
            case 1:
                if (day) drawSun(c, p, 35, 34, 14, false);
                else     drawMoon(c, p, 34, 33, 15, false);
                drawCloud(c, p, 22, 38, 68, front);          // x 22..90 · y 38..82
                break;
            case 2:
                drawCloud(c, p, 40, 14, 48, back);           // x 40..88 · y 14..45
                drawCloud(c, p, 8, 34, 74, front);           // x 8..82  · y 34..82
                break;
            case 3:
                drawCloud(c, p, 18, 10, 60, back);           // x 18..78 · y 10..49
                drawFog(c, p);
                break;
            case 4:
                drawCloud(c, p, 10, 8, 76, front);           // x 10..86 · y 8..57
                drawRain(c, p);
                break;
            case 5:
                drawCloud(c, p, 10, 6, 76, TONE_STORM);      // x 10..86 · y 6..55
                drawBolt(c, p);
                break;
            case 6:
                drawCloud(c, p, 10, 8, 76, front);
                drawSnow(c, p);
                break;
            default:
                drawCloud(c, p, 8, 24, 80, front);
                break;
        }
        return bmp;
    }

    // Tonos de nube: {color arriba, color abajo}
    private static final int TONE_FRONT_DAY   = 0;
    private static final int TONE_BACK_DAY    = 1;
    private static final int TONE_FRONT_NIGHT = 2;
    private static final int TONE_BACK_NIGHT  = 3;
    private static final int TONE_STORM       = 4;
    private static final int[][] TONES = {
        {0xFFFFFFFF, 0xFFC3CFE6},   // frontal día
        {0xFFA9B6D2, 0xFF8997B8},   // trasera día
        {0xFFC4CDE4, 0xFF8190B2},   // frontal noche
        {0xFF7482A6, 0xFF5D6A8F},   // trasera noche
        {0xFF8890AC, 0xFF4B536F},   // tormenta
    };

    /**
     * Nube: silueta única (3 círculos + base redondeada) dentro de un cuadro
     * de 90×58 u normalizado. (x, y) = esquina superior izquierda real y w =
     * ancho final; el alto es w·58/90. Nada sale de ese cuadro.
     */
    private static void drawCloud(Canvas c, Paint p, float x, float y, float w, int tone) {
        float k = w / 90f;
        float h = 58f * k;
        p.setShader(new LinearGradient(0, y, 0, y + h, TONES[tone][0], TONES[tone][1], Shader.TileMode.CLAMP));
        p.setStyle(Paint.Style.FILL);
        // coordenadas del molde → reales: px = x + (ux - 6)·k ; py = y + (uy - 2)·k
        c.drawCircle(x + (28 - 6) * k, y + (38 - 2) * k, 20 * k, p);
        c.drawCircle(x + (52 - 6) * k, y + (30 - 2) * k, 28 * k, p);
        c.drawCircle(x + (76 - 6) * k, y + (40 - 2) * k, 18 * k, p);
        c.drawRoundRect(new RectF(x, y + (36 - 2) * k, x + 90 * k, y + h), 12 * k, 12 * k, p);
        p.setShader(null);
    }

    private static void drawSun(Canvas c, Paint p, float cx, float cy, float r, boolean rays) {
        p.setStyle(Paint.Style.FILL);
        p.setShader(new RadialGradient(cx, cy, r * 2.2f,
            new int[]{0x66FFD75E, 0x00FFD75E}, new float[]{0.3f, 1f}, Shader.TileMode.CLAMP));
        c.drawCircle(cx, cy, r * 2.2f, p);
        if (rays) {
            p.setShader(null);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeWidth(r * 0.17f);
            p.setColor(0xFFFFD24D);
            for (int i = 0; i < 8; i++) {
                double a = Math.toRadians(i * 45);
                float cs = (float) Math.cos(a), sn = (float) Math.sin(a);
                c.drawLine(cx + cs * r * 1.38f, cy + sn * r * 1.38f,
                           cx + cs * r * 1.78f, cy + sn * r * 1.78f, p);
            }
            p.setStyle(Paint.Style.FILL);
        }
        p.setShader(new RadialGradient(cx - r * .25f, cy - r * .25f, r * 1.35f,
            new int[]{0xFFFFF4C2, 0xFFFFD24D, 0xFFFFB92E}, new float[]{0f, .6f, 1f},
            Shader.TileMode.CLAMP));
        c.drawCircle(cx, cy, r, p);
        p.setShader(null);
    }

    private static void drawMoon(Canvas c, Paint p, float cx, float cy, float r, boolean stars) {
        p.setStyle(Paint.Style.FILL);
        p.setShader(new RadialGradient(cx, cy, r * 1.9f,
            new int[]{0x40C8D5FF, 0x00C8D5FF}, new float[]{0.35f, 1f}, Shader.TileMode.CLAMP));
        c.drawCircle(cx, cy, r * 1.9f, p);
        p.setShader(null);

        Path outer = new Path(); outer.addCircle(cx, cy, r, Path.Direction.CW);
        Path cut = new Path();   cut.addCircle(cx + r * .50f, cy - r * .22f, r * .86f, Path.Direction.CW);
        Path crescent = new Path();
        boolean ok = false;
        try { ok = crescent.op(outer, cut, Path.Op.DIFFERENCE); } catch (Throwable ignored) {}
        p.setShader(new LinearGradient(cx - r, cy - r, cx + r, cy + r,
            0xFFFFF6D6, 0xFFE8D8A4, Shader.TileMode.CLAMP));
        c.drawPath(ok ? crescent : outer, p);
        p.setShader(null);

        if (stars) {
            p.setColor(0xCCFFFFFF);
            c.drawCircle(76, 24, 2.3f, p);
            c.drawCircle(82, 48, 1.7f, p);
            c.drawCircle(68, 74, 1.9f, p);
        }
    }

    private static void drawRain(Canvas c, Paint p) {
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeWidth(4.6f);
        p.setColor(0xFF6DB4FF);
        float[] xs = {27, 41, 55, 69};
        for (int i = 0; i < xs.length; i++) {
            float y0 = 65 + (i % 2) * 4;
            c.drawLine(xs[i], y0, xs[i] - 5, y0 + 15, p);
        }
        p.setStyle(Paint.Style.FILL);
    }

    private static void drawSnow(Canvas c, Paint p) {
        p.setStyle(Paint.Style.FILL);
        p.setColor(0xFFFFFFFF);
        float[] xs = {24, 37, 50, 63, 76};
        for (int i = 0; i < xs.length; i++) {
            c.drawCircle(xs[i], 68 + (i % 2) * 10, 3.4f, p);
        }
    }

    private static void drawFog(Canvas c, Paint p) {
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeWidth(5f);
        p.setColor(0xD9E4EBF6);
        c.drawLine(16, 60, 80, 60, p);
        c.drawLine(24, 72, 84, 72, p);
        c.drawLine(16, 84, 70, 84, p);
        p.setStyle(Paint.Style.FILL);
    }

    private static void drawBolt(Canvas c, Paint p) {
        Path path = new Path();
        path.moveTo(51, 48);
        path.lineTo(37, 69);
        path.lineTo(47, 69);
        path.lineTo(40, 90);
        path.lineTo(63, 62);
        path.lineTo(52, 62);
        path.lineTo(61, 48);
        path.close();
        p.setStyle(Paint.Style.FILL);
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
