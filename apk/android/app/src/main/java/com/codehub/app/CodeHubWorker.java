package com.codehub.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

public class CodeHubWorker extends Worker {

    private static final String BACKEND = "https://codehub-98s6.onrender.com";
    private static final String CHANNEL_UPDATES = "codehub_updates";
    private static final String CHANNEL_WEATHER  = "codehub_weather";
    private static final String WORK_NAME = "codehub_polling";
    private static final int NOTIF_ID_UPDATES = 3001;
    private static final int NOTIF_ID_WEATHER  = 3002;

    public CodeHubWorker(@NonNull Context ctx, @NonNull WorkerParameters params) {
        super(ctx, params);
    }

    public static void schedule(Context ctx) {
        PeriodicWorkRequest work = new PeriodicWorkRequest.Builder(
            CodeHubWorker.class, 30, TimeUnit.MINUTES)
            .build();
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(
            WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, work);
    }

    @NonNull
    @Override
    public Result doWork() {
        if (!isOnline()) return Result.retry();
        try { pollNewApps(); }   catch (Exception ignored) {}
        try { pollWeather(); }   catch (Exception ignored) {}
        return Result.success();
    }

    private boolean isOnline() {
        ConnectivityManager cm = (ConnectivityManager) getApplicationContext().getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return false;
        NetworkInfo ni = cm.getActiveNetworkInfo();
        return ni != null && ni.isConnected();
    }

    // ── NEW APPS POLLING ────────────────────────────────────────
    private void pollNewApps() throws Exception {
        SharedPreferences prefs = getApplicationContext().getSharedPreferences("codehub", Context.MODE_PRIVATE);
        long lastCheck = prefs.getLong("last_apps_check", 0);
        String lastKnownHash = prefs.getString("last_apps_hash", "");

        String json = httpGet(BACKEND + "/api/opensource-apps?limit=5&sort=newest");
        if (json == null) return;

        JSONArray arr = new JSONObject(json).optJSONArray("apps");
        if (arr == null || arr.length() == 0) return;

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject app = arr.getJSONObject(i);
            sb.append(app.optString("id", "")).append(app.optString("updatedAt", ""));
        }
        String currentHash = sb.toString();

        if (lastCheck > 0 && !currentHash.equals(lastKnownHash)) {
            String newestName = arr.getJSONObject(0).optString("name", "una app");
            int count = arr.length();
            showNotification(CHANNEL_UPDATES, NOTIF_ID_UPDATES,
                count > 1 ? count + " apps nuevas en CodeHub" : "Nueva app: " + newestName,
                count > 1 ? "Se agregaron " + count + " apps al catálogo" : newestName + " ya está disponible",
                "https://wilson360-labs.vercel.app/#apps");
        }

        prefs.edit()
            .putLong("last_apps_check", System.currentTimeMillis())
            .putString("last_apps_hash", currentHash)
            .apply();
    }

    // ── WEATHER POLLING ─────────────────────────────────────────
    private void pollWeather() throws Exception {
        SharedPreferences prefs = getApplicationContext().getSharedPreferences("codehub", Context.MODE_PRIVATE);
        double lat = Double.longBitsToDouble(prefs.getLong("lat_bits", Double.doubleToLongBits(0)));
        double lon = Double.longBitsToDouble(prefs.getLong("lon_bits", Double.doubleToLongBits(0)));
        if (lat == 0 && lon == 0) return;

        long lastWeather = prefs.getLong("last_weather_check", 0);
        if (System.currentTimeMillis() - lastWeather < 3600000) return; // 1h min

        String json = httpGet(BACKEND + "/api/push/weather/check?lat=" + lat + "&lon=" + lon);
        if (json == null) return;

        JSONObject obj = new JSONObject(json);
        boolean shouldAlert = obj.optBoolean("shouldNotify", false);
        if (!shouldAlert) return;

        String condition = obj.optString("condition", "Cambio de clima");
        String summary   = obj.optString("summary", "Revisa el pronóstico en CodeHub");

        showNotification(CHANNEL_WEATHER, NOTIF_ID_WEATHER,
            condition, summary,
            "https://wilson360-labs.vercel.app");

        prefs.edit().putLong("last_weather_check", System.currentTimeMillis()).apply();
    }

    // ── NOTIFICATIONS ───────────────────────────────────────────
    private void showNotification(String channelId, int notifId, String title, String body, String url) {
        Context ctx = getApplicationContext();
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            String name = channelId.equals(CHANNEL_WEATHER) ? "Clima" : "Actualizaciones";
            NotificationChannel ch = new NotificationChannel(channelId, name, NotificationManager.IMPORTANCE_DEFAULT);
            ch.setDescription("Notificaciones de " + name.toLowerCase());
            nm.createNotificationChannel(ch);
        }

        Intent intent = new Intent(ctx, MainActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        intent.putExtra("open_url", url);
        PendingIntent pi = PendingIntent.getActivity(ctx, notifId, intent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification notif = new NotificationCompat.Builder(ctx, channelId)
            .setSmallIcon(R.drawable.ic_launcher_real)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(new NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build();

        nm.notify(notifId, notif);
    }

    // ── HTTP GET ────────────────────────────────────────────────
    private String httpGet(String spec) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(spec).openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(15000);
        conn.setRequestProperty("Accept", "application/json");
        if (conn.getResponseCode() != 200) return null;
        BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream()));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = br.readLine()) != null) sb.append(line);
        br.close();
        conn.disconnect();
        return sb.toString();
    }
}
