package com.codehub.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.location.Location;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.google.android.gms.location.CurrentLocationRequest;
import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;
import com.google.android.gms.tasks.CancellationTokenSource;
import com.google.android.gms.tasks.Tasks;

import org.json.JSONObject;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * Sincronización periódica de CodeHub (token FCM + última ubicación) usando
 * WorkManager.
 *
 * Reemplaza al antiguo {@code CodeHubSyncService} (Foreground Service de tipo
 * {@code dataSync}), que en Android 15/16 provocaba
 * {@code ForegroundServiceDidNotStopInTimeException} y
 * {@code ForegroundServiceStartNotAllowedException}: el sistema limita a 6 h
 * por día ese tipo de servicio y, al agotarse, ni siquiera deja reiniciarlo.
 *
 * WorkManager no depende de servicios en primer plano, respeta Doze y reintenta
 * solo; el sistema decide el momento exacto, por lo que ya no hay timeout que
 * pueda tumbar la app.
 */
public class CodeHubSyncWorker extends Worker {

    /** Nombre único del trabajo periódico (evita duplicados). */
    public static final String NAME = "codehub_sync_worker";
    private static final String BASE_URL = "https://codehub-98s6.onrender.com";

    public CodeHubSyncWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        Context ctx = getApplicationContext();
        try {
            SharedPreferences prefs = ctx.getSharedPreferences("codehub", Context.MODE_PRIVATE);
            String token = prefs.getString("fcm_token", "");
            double lat = Double.longBitsToDouble(prefs.getLong("lat_bits", 0));
            double lon = Double.longBitsToDouble(prefs.getLong("lon_bits", 0));

            double[] fresh = fetchLocation(ctx);
            if (fresh != null) {
                lat = fresh[0];
                lon = fresh[1];
                prefs.edit()
                    .putLong("lat_bits", Double.doubleToRawLongBits(lat))
                    .putLong("lon_bits", Double.doubleToRawLongBits(lon))
                    .apply();
                WeatherWidgetProvider.requestRefresh(ctx);
            }

            if (!token.isEmpty()) {
                postSubscription(ctx, token, lat, lon);
            }
            return Result.success();
        } catch (Throwable t) {
            // Error transitorio de red/GPS: WorkManager reintentará con backoff.
            return Result.retry();
        }
    }

    private static boolean hasLocationPermission(Context ctx) {
        return ctx.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED
            || ctx.checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    /** Ubicación fresca con timeout duro: el worker nunca se queda colgado. */
    private static double[] fetchLocation(Context ctx) {
        if (!hasLocationPermission(ctx)) return null;
        try {
            FusedLocationProviderClient fused = LocationServices.getFusedLocationProviderClient(ctx);
            CurrentLocationRequest request = new CurrentLocationRequest.Builder()
                .setPriority(Priority.PRIORITY_BALANCED_POWER_ACCURACY)
                .setMaxUpdateAgeMillis(5 * 60 * 1000L)
                .build();
            CancellationTokenSource cts = new CancellationTokenSource();
            Location loc = Tasks.await(
                fused.getCurrentLocation(request, cts.getToken()),
                20, TimeUnit.SECONDS);
            if (loc != null) return new double[]{loc.getLatitude(), loc.getLongitude()};
        } catch (Throwable ignored) {}
        return null;
    }

    private static void postSubscription(Context ctx, String token, double lat, double lon) {
        String versionName = "1.2.0";
        try {
            versionName = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0).versionName;
        } catch (Exception ignored) {}

        try {
            JSONObject body = new JSONObject();
            body.put("token", token);
            body.put("lat", lat);
            body.put("lon", lon);
            body.put("appName", "CodeHub");
            body.put("appVersion", versionName);
            body.put("platform", "android");
            body.put("userAgent", Build.MANUFACTURER + " " + Build.MODEL
                + " (Android " + Build.VERSION.RELEASE + ")");
            postJson(BASE_URL + "/api/push/fcm-subscribe", body);

            if (lat != 0 || lon != 0) {
                JSONObject locBody = new JSONObject();
                locBody.put("token", token);
                locBody.put("lat", lat);
                locBody.put("lon", lon);
                postJson(BASE_URL + "/api/push/fcm-location", locBody);
            }
        } catch (Throwable ignored) {}
    }

    private static void postJson(String endpoint, JSONObject body) {
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(endpoint).openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setDoOutput(true);
            OutputStream os = conn.getOutputStream();
            os.write(body.toString().getBytes(StandardCharsets.UTF_8));
            os.close();
            conn.getResponseCode();
            conn.disconnect();
        } catch (Throwable ignored) {}
    }
}
