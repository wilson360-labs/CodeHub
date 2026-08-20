package com.codehub.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.location.Location;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

public class FcmHelper {

    private static final String BACKEND = "https://codehub-98s6.onrender.com";

    public static void registerToken(Context ctx, String token) {
        try {
            SharedPreferences prefs = ctx.getSharedPreferences("codehub", Context.MODE_PRIVATE);
            double lat = Double.longBitsToDouble(prefs.getLong("lat_bits", Double.doubleToLongBits(0)));
            double lon = Double.longBitsToDouble(prefs.getLong("lon_bits", Double.doubleToLongBits(0)));
            String versionName = "1.2.0";
            try { versionName = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0).versionName; } catch (Exception ignored) {}

            JSONObject body = new JSONObject();
            body.put("token", token);
            body.put("lat", lat);
            body.put("lon", lon);
            body.put("appName", "CodeHub");
            body.put("appVersion", versionName);
            body.put("platform", "android");
            body.put("userAgent", android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL + " (Android " + android.os.Build.VERSION.RELEASE + ")");

            // Save locally
            prefs.edit().putString("fcm_token", token).apply();

            // Send to backend
            HttpURLConnection conn = (HttpURLConnection) new URL(BACKEND + "/api/push/fcm-subscribe").openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(15000);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setDoOutput(true);
            OutputStream os = conn.getOutputStream();
            os.write(body.toString().getBytes(StandardCharsets.UTF_8));
            os.close();

            int code = conn.getResponseCode();
            conn.disconnect();

            // Also update location if we have it
            if (lat != 0 || lon != 0) {
                updateLocation(ctx, token, lat, lon);
            }
        } catch (Exception ignored) {}
    }

    public static void updateLocation(Context ctx, String token, double lat, double lon) {
        try {
            JSONObject body = new JSONObject();
            body.put("token", token);
            body.put("lat", lat);
            body.put("lon", lon);

            HttpURLConnection conn = (HttpURLConnection) new URL(BACKEND + "/api/push/fcm-location").openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setDoOutput(true);
            OutputStream os = conn.getOutputStream();
            os.write(body.toString().getBytes(StandardCharsets.UTF_8));
            os.close();
            conn.disconnect();
        } catch (Exception ignored) {}
    }
}
