package com.codehub.app;

import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Chequeo de actualizaciones de la app CodeHub, INDEPENDIENTE del sitio web
 * (wilson360-labs.vercel.app) y del backend (Render/Supabase).
 *
 * Habla directo con la API pública de GitHub Releases del propio repo, que
 * el workflow .github/workflows/build-apk.yml ya publica en cada build
 * (tag "apk-<version>" con el .apk firmado adjunto). No requiere backend
 * ni fallback JSON: si GitHub está caído, simplemente no hay chequeo.
 *
 * Uso: AppUpdateChecker.checkIfDue(activity);
 */
public class AppUpdateChecker {

    private static final String TAG = "CodeHubUpdater";
    private static final String GITHUB_OWNER = "wilson360-labs";
    private static final String GITHUB_REPO  = "CodeHub";
    private static final String RELEASES_LATEST_URL =
        "https://api.github.com/repos/" + GITHUB_OWNER + "/" + GITHUB_REPO + "/releases/latest";

    private static final String PREFS = "codehub";
    private static final String PREF_LAST_CHECK = "update_last_check_ts";
    private static final long CHECK_INTERVAL_MS = 24L * 60 * 60 * 1000; // 1 vez al día

    /** Chequea solo si ya pasó CHECK_INTERVAL_MS desde el último chequeo. */
    public static void checkIfDue(final MainActivity activity) {
        SharedPreferences prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        long last = prefs.getLong(PREF_LAST_CHECK, 0);
        if (System.currentTimeMillis() - last < CHECK_INTERVAL_MS) return;
        prefs.edit().putLong(PREF_LAST_CHECK, System.currentTimeMillis()).apply();
        checkNow(activity);
    }

    /** Fuerza el chequeo ya mismo (p.ej. desde un botón "Buscar actualizaciones"). */
    public static void checkNow(final MainActivity activity) {
        new Thread(() -> {
            try {
                JSONObject release = fetchLatestRelease();
                if (release == null) return;

                String tag = release.optString("tag_name", "");
                String remoteVersion = tag.startsWith("apk-") ? tag.substring(4) : tag;
                String apkUrl = findApkAssetUrl(release);
                if (remoteVersion.isEmpty() || apkUrl == null) return;

                String localVersion = "0";
                try {
                    localVersion = activity.getPackageManager()
                        .getPackageInfo(activity.getPackageName(), 0).versionName;
                } catch (Exception ignored) {}

                if (!isNewer(remoteVersion, localVersion)) return;

                String notes = release.optString("body", "");
                promptUpdate(activity, remoteVersion, apkUrl, notes);
            } catch (Throwable t) {
                Log.e(TAG, "Error chequeando actualización", t);
            }
        }).start();
    }

    private static JSONObject fetchLatestRelease() {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(RELEASES_LATEST_URL);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Accept", "application/vnd.github+json");
            conn.setRequestProperty("User-Agent", "CodeHub-Android");
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(8000);

            int code = conn.getResponseCode();
            if (code != 200) return null;

            InputStream in = conn.getInputStream();
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) sb.append(line);
            reader.close();

            return new JSONObject(sb.toString());
        } catch (Exception e) {
            Log.e(TAG, "fetchLatestRelease falló", e);
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static String findApkAssetUrl(JSONObject release) {
        JSONArray assets = release.optJSONArray("assets");
        if (assets == null) return null;
        for (int i = 0; i < assets.length(); i++) {
            JSONObject asset = assets.optJSONObject(i);
            if (asset == null) continue;
            String name = asset.optString("name", "");
            if (name.toLowerCase().endsWith(".apk")) {
                return asset.optString("browser_download_url", null);
            }
        }
        return null;
    }

    /** Compara versiones tipo "1.3.0" vs "1.2.0" (numérica por segmento). */
    private static boolean isNewer(String remote, String local) {
        String[] r = remote.split("\\.");
        String[] l = local.split("\\.");
        int len = Math.max(r.length, l.length);
        for (int i = 0; i < len; i++) {
            int rv = i < r.length ? parseIntSafe(r[i]) : 0;
            int lv = i < l.length ? parseIntSafe(l[i]) : 0;
            if (rv != lv) return rv > lv;
        }
        return false;
    }

    private static int parseIntSafe(String s) {
        try { return Integer.parseInt(s.replaceAll("[^0-9]", "")); }
        catch (Exception e) { return 0; }
    }

    private static void promptUpdate(final MainActivity activity, final String version,
                                      final String apkUrl, String notes) {
        new Handler(Looper.getMainLooper()).post(() -> {
            if (activity.isFinishing()) return;
            String message = "Hay una nueva versión de CodeHub disponible: v" + version +
                (notes != null && !notes.isEmpty() ? "\n\n" + trim(notes, 240) : "");
            new AlertDialog.Builder(activity)
                .setTitle("🚀 Actualización disponible")
                .setMessage(message)
                .setCancelable(true)
                .setPositiveButton("Actualizar ahora", (d, w) -> startDownload(activity, apkUrl, version))
                .setNegativeButton("Luego", null)
                .show();
        });
    }

    private static String trim(String s, int max) {
        return s.length() > max ? s.substring(0, max) + "…" : s;
    }

    private static void startDownload(MainActivity activity, String apkUrl, String version) {
        // Android 8+: hay que tener permiso para instalar de fuentes desconocidas
        // desde ESTA app antes de intentar el ACTION_VIEW del InstallReceiver.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            PackageManager pm = activity.getPackageManager();
            if (!pm.canRequestPackageInstalls()) {
                Intent settingsIntent = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + activity.getPackageName()));
                try { activity.startActivity(settingsIntent); } catch (Exception ignored) {}
            }
        }

        try {
            DownloadManager.Request request = new DownloadManager.Request(Uri.parse(apkUrl));
            request.setTitle("CodeHub v" + version);
            request.setDescription("Descargando actualización…");
            request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            request.setDestinationInExternalFilesDir(activity, android.os.Environment.DIRECTORY_DOWNLOADS,
                "CodeHub-update.apk");
            request.setMimeType("application/vnd.android.package-archive");

            DownloadManager dm = (DownloadManager) activity.getSystemService(Context.DOWNLOAD_SERVICE);
            if (dm != null) dm.enqueue(request);
            // La descarga la recoge InstallReceiver (ACTION_DOWNLOAD_COMPLETE)
            // y dispara el instalador del sistema — mismo flujo que ya usa
            // la app para instalar APKs del catálogo Open Source.
        } catch (Exception e) {
            Log.e(TAG, "startDownload falló", e);
        }
    }
}
