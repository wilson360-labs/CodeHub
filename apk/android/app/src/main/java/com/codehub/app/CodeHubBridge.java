package com.codehub.app;

import android.app.Activity;
import android.app.DownloadManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
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
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
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

    // ── ANUNCIO RECOMPENSADO (AdMob) ────────────────────────────
    // ca-app-pub-3780093322926832/4285173985
    @JavascriptInterface
    public void loadRewardedAd() {
        activity.runOnUiThread(() -> RewardedAdManager.load(activity));
    }

    @JavascriptInterface
    public boolean isRewardedAdReady() {
        return RewardedAdManager.isReady();
    }

    // callbackName: nombre de una función global en window, invocada como
    // callbackName(earned, amount, type) — earned=true solo si el usuario
    // vio el anuncio completo y ganó la recompensa.
    @JavascriptInterface
    public void showRewardedAd(final String callbackName) {
        activity.runOnUiThread(() -> RewardedAdManager.show(activity, (earned, amount, type) -> {
            String safeType = type == null ? "" : type.replace("'", "\\'");
            webView.loadUrl("javascript:try{if(window." + callbackName + ")window." + callbackName +
                "(" + earned + "," + amount + ",'" + safeType + "');}catch(e){}");
        }));
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

    // Llamado desde window.onerror / unhandledrejection (inyectado en
    // MainActivity.injectNativeFlags) para reportar errores JS del sitio
    // dentro del WebView al chat de Telegram del admin vía backend.
    @JavascriptInterface
    public void reportJsError(String message, String source, String line, String col, String stack) {
        CrashReporter.reportJs(activity.getApplicationContext(), message, source, line, col, stack);
    }

    // ── DETECCIÓN DE APPS INSTALADAS (catálogo Open Source) ──────
    // Requiere QUERY_ALL_PACKAGES en el manifest (declarado — CodeHub
    // es una "tienda de apps", uso permitido por Play Console).

    /**
     * Recibe un JSON array de packageNames (ej. ["org.schabi.newpipe", ...])
     * y devuelve un JSON object { packageName: versionName|null }.
     * null = no instalada. Se hace en batch para evitar N round-trips
     * JS↔Java, que son lentos en WebView.
     */
    @JavascriptInterface
    public String getInstalledVersions(String packageNamesJson) {
        JSONObject result = new JSONObject();
        try {
            JSONArray pkgs = new JSONArray(packageNamesJson);
            PackageManager pm = activity.getPackageManager();
            for (int i = 0; i < pkgs.length(); i++) {
                String pkg = pkgs.optString(i, null);
                if (pkg == null || pkg.isEmpty()) continue;
                try {
                    PackageInfo info = pm.getPackageInfo(pkg, 0);
                    result.put(pkg, info.versionName != null ? info.versionName : "");
                } catch (PackageManager.NameNotFoundException e) {
                    result.put(pkg, JSONObject.NULL);
                }
            }
        } catch (Exception ignored) {}
        return result.toString();
    }

    @JavascriptInterface
    public boolean isPackageInstalled(String packageName) {
        try {
            activity.getPackageManager().getPackageInfo(packageName, 0);
            return true;
        } catch (Exception e) { return false; }
    }

    // ── SHIZUKU — instalación silenciosa (opcional) ──────────────

    @JavascriptInterface
    public boolean isShizukuAvailable() {
        return ShizukuInstaller.isBinderAlive();
    }

    @JavascriptInterface
    public boolean hasShizukuPermission() {
        return ShizukuInstaller.hasPermission();
    }

    /** callbackName(granted: boolean) — se llama tras la respuesta del usuario al diálogo de Shizuku. */
    @JavascriptInterface
    public void requestShizukuPermission(final String callbackName) {
        activity.runOnUiThread(() -> ShizukuInstaller.requestPermission(granted ->
            webView.post(() -> webView.loadUrl("javascript:try{if(window." + callbackName + ")window." +
                callbackName + "(" + granted + ");}catch(e){}"))
        ));
    }

    /**
     * Descarga el APK de una app Open Source y la instala.
     * Si Shizuku está listo (preferSilent=true), instala sin diálogo.
     * Si no, cae al flujo normal (Intent.ACTION_VIEW, pide confirmación).
     * callbackName(status: 'installed'|'prompted'|'error', message: string)
     */
    @JavascriptInterface
    public void downloadAndInstallApk(final String apkUrl, final String appId, final boolean preferSilent, final String callbackName) {
        new Thread(() -> {
            File dest = null;
            try {
                dest = new File(activity.getExternalFilesDir(null), "os_" + appId + ".apk");
                downloadToFile(apkUrl, dest);

                boolean useSilent = preferSilent && ShizukuInstaller.isReady();
                if (useSilent) {
                    ShizukuInstaller.installSilently(dest.getAbsolutePath());
                    notifyInstallResult(callbackName, "installed", "");
                } else {
                    final File apkFile = dest;
                    activity.runOnUiThread(() -> installViaSystemDialog(apkFile));
                    notifyInstallResult(callbackName, "prompted", "");
                }
            } catch (Exception e) {
                notifyInstallResult(callbackName, "error", e.getMessage() != null ? e.getMessage() : "error desconocido");
            }
        }).start();
    }

    private void downloadToFile(String url, File dest) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setInstanceFollowRedirects(true);
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(30000);
        conn.connect();
        if (conn.getResponseCode() != 200 && conn.getResponseCode() != 302) {
            throw new Exception("HTTP " + conn.getResponseCode() + " descargando APK");
        }
        try (InputStream in = conn.getInputStream(); FileOutputStream out = new FileOutputStream(dest)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
        }
        conn.disconnect();
    }

    private void installViaSystemDialog(File apkFile) {
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
        Uri contentUri = androidx.core.content.FileProvider.getUriForFile(
            activity, activity.getPackageName() + ".fileprovider", apkFile);
        intent.setDataAndType(contentUri, "application/vnd.android.package-archive");
        activity.startActivity(intent);
    }

    private void notifyInstallResult(String callbackName, String status, String message) {
        String safeMsg = message.replace("\\", "\\\\").replace("'", "\\'").replace("\n", " ");
        activity.runOnUiThread(() -> webView.loadUrl(
            "javascript:try{if(window." + callbackName + ")window." + callbackName +
            "('" + status + "','" + safeMsg + "');}catch(e){}"));
    }
}
