package com.codehub.app;

import android.app.Activity;
import android.app.DownloadManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;

import com.google.firebase.messaging.FirebaseMessaging;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

public class CodeHubBridge {

    private final Activity activity;
    private final WebView webView;

    public CodeHubBridge(Activity activity, WebView webView) {
        this.activity = activity;
        this.webView  = webView;
    }

    @JavascriptInterface
    public String getVersionName() {
        try {
            return activity.getPackageManager().getPackageInfo(activity.getPackageName(), 0).versionName;
        } catch (Exception e) { return "1.0.0"; }
    }

    @JavascriptInterface
    public int getVersionCode() {
        try {
            return activity.getPackageManager().getPackageInfo(activity.getPackageName(), 0).versionCode;
        } catch (Exception e) { return 1; }
    }

    @JavascriptInterface
    public String checkForUpdate() {
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(
                "https://api.github.com/repos/wilson360-labs/CodeHub/releases/latest"
            ).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);
            conn.setRequestProperty("Accept", "application/vnd.github.v3+json");
            if (conn.getResponseCode() != 200) return null;

            BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream()));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
            br.close();
            conn.disconnect();

            JSONObject release = new JSONObject(sb.toString());
            String tagName = release.optString("tag_name", "");
            String body    = release.optString("body", "");
            String htmlUrl = release.optString("html_url", "");

            JSONObject result = new JSONObject();
            result.put("tag", tagName);
            result.put("body", body);
            result.put("html_url", htmlUrl);

            JSONArray assets = release.optJSONArray("assets");
            if (assets != null) {
                for (int i = 0; i < assets.length(); i++) {
                    JSONObject asset = assets.getJSONObject(i);
                    String name = asset.optString("name", "");
                    if (name.endsWith(".apk")) {
                        result.put("apk_url", asset.optString("browser_download_url", ""));
                        result.put("apk_size", asset.optLong("size", 0));
                        break;
                    }
                }
            }

            return result.toString();
        } catch (Exception e) { return null; }
    }

    @JavascriptInterface
    public void downloadUpdate(String apkUrl) {
        if (apkUrl == null || apkUrl.isEmpty()) return;
        DownloadManager dm = (DownloadManager) activity.getSystemService(Context.DOWNLOAD_SERVICE);
        if (dm == null) return;
        DownloadManager.Request req = new DownloadManager.Request(Uri.parse(apkUrl));
        req.setTitle("CodeHub - Descargando actualización");
        req.setDescription("Se instalará automáticamente al completar");
        req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
        req.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "CodeHub-update.apk");
        req.setMimeType("application/vnd.android.package-archive");
        dm.enqueue(req);
    }

    @JavascriptInterface
    public void saveLocation(double lat, double lon) {
        SharedPreferences prefs = activity.getSharedPreferences("codehub", Context.MODE_PRIVATE);
        prefs.edit()
            .putLong("lat_bits", Double.doubleToRawLongBits(lat))
            .putLong("lon_bits", Double.doubleToRawLongBits(lon))
            .apply();
    }

    @JavascriptInterface
    public boolean isOnline() {
        ConnectivityManager cm = (ConnectivityManager) activity.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return false;
        NetworkInfo ni = cm.getActiveNetworkInfo();
        return ni != null && ni.isConnected();
    }

    @JavascriptInterface
    public void openExternal(String url) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            activity.startActivity(intent);
        } catch (Exception ignored) {}
    }

    @JavascriptInterface
    public void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            activity.runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    activity.requestPermissions(new String[]{
                        android.Manifest.permission.POST_NOTIFICATIONS
                    }, 300);
                }
            });
        }
    }

    @JavascriptInterface
    public void openAppSettings() {
        Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
        intent.setData(Uri.parse("package:" + activity.getPackageName()));
        activity.startActivity(intent);
    }

    @JavascriptInterface
    public void getFCMToken(final String callbackName) {
        FirebaseMessaging.getInstance().getToken().addOnCompleteListener(task -> {
            if (!task.isSuccessful() || task.getResult() == null) return;
            final String token = task.getResult();
            // Save locally
            activity.getSharedPreferences("codehub", Context.MODE_PRIVATE)
                .edit().putString("fcm_token", token).apply();
            // Register with backend
            new Thread(() -> FcmHelper.registerToken(activity.getApplicationContext(), token)).start();
            // Callback to JS
            activity.runOnUiThread(() -> webView.loadUrl("javascript:" + callbackName + "('" + token + "')"));
        });
    }

    @JavascriptInterface
    public String getStoredFCMToken() {
        return activity.getSharedPreferences("codehub", Context.MODE_PRIVATE)
            .getString("fcm_token", "");
    }
}
