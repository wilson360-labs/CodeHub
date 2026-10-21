package com.codehub.app;

import android.app.Dialog;
import android.app.DownloadManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Actualización de CodeHub, 100% NATIVA (dentro del APK).
 *
 * - La web/PWA NO muestra diálogos de actualización; este chequeo vive
 *   solo en la app y mueestra SU PROPIO diálogo (res/layout/
 *   dialog_update.xml), elegante, sin toques del sistema aburridos.
 * - Habla directo con GitHub Releases del repo (tag apk-codehub-vX.Y).
 * - SOLO se acepta la release que trae el asset "app-release-signed.apk"
 *   (igualdad exacta). Con el criterio viejo (cualquier asset *.apk),
 *   una release de extensión (youtube-music-codebeat.eapk) terminaba en
 *   ".apk" y se hacía pasar por actualización de CodeHub — esto elimina
 *   ese bug de raíz.
 * - Las notas se limpian antes de mostrarse: jamás se muestran enlaces
 *   al repo (Full Changelog / compare) ni sintaxis Markdown.
 * - "Más tarde" memoriza la versión y no vuelve a molestar por ella;
 *   cuando sale una versión más nueva, pregunta de nuevo.
 * - Al confirmar, descarga vía DownloadManager y InstallReceiver lanza
 *   el instalador del sistema (mismo flujo del catálogo Open Source).
 *
 * Uso: AppUpdateChecker.checkIfDue(activity);  // en MainActivity.onCreate Y onResume
 */
public class AppUpdateChecker {

    private static final String TAG = "CodeHubUpdater";
    private static final String GITHUB_OWNER = "wilson360-labs";
    private static final String GITHUB_REPO  = "CodeHub";
    private static final String RELEASES_LATEST_URL =
        "https://api.github.com/repos/" + GITHUB_OWNER + "/" + GITHUB_REPO + "/releases/latest";

    private static final String APK_ASSET_NAME = "app-release-signed.apk";

    private static final String PREFS = "codehub";
    private static final String PREF_LAST_CHECK = "update_last_check_ts";
    private static final String PREF_LAST_ATTEMPT = "update_last_attempt_ts";
    private static final String PREF_SKIP = "update_skip_version";
    private static final long CHECK_INTERVAL_MS = 24L * 60 * 60 * 1000; // éxito: 1 vez al día
    // Fallo de red: reintentar en 30 min. Antes la marca de último chequeo se
    // guardaba ANTES de consultar, así que una consulta fallida (GitHub
    // inalcanzable) dejaba el diálogo bloqueado 24 horas — ese era el bug
    // por el que "el update no llega".
    private static final long CHECK_RETRY_MS = 30L * 60 * 1000;

    /**
     * Chequeo con gating por RESULTADO: se llama en onCreate y onResume.
     * - Chequeo OK   → espera CHECK_INTERVAL_MS antes del siguiente.
     * - Chequeo FALLIDO (red/servidor) → reintenta en CHECK_RETRY_MS.
     * La marca del INTENTO se guarda antes de consultar; la del ÉXITO solo
     * cuando GitHub contestó de verdad (checkNow).
     */
    public static void checkIfDue(final MainActivity activity) {
        SharedPreferences prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        long now = System.currentTimeMillis();
        long lastSuccess = prefs.getLong(PREF_LAST_CHECK, 0);
        long lastAttempt = prefs.getLong(PREF_LAST_ATTEMPT, 0);
        if (now - lastSuccess < CHECK_INTERVAL_MS) return; // chequeado OK hace <24h
        if (now - lastAttempt < CHECK_RETRY_MS) return;    // falló hace <30min: esperar
        prefs.edit().putLong(PREF_LAST_ATTEMPT, now).apply();
        checkNow(activity);
    }

    /** Chequeo inmediato (queda disponible para futuros usos en ajustes). */
    public static void checkNow(final MainActivity activity) {
        new Thread(() -> {
            try {
                JSONObject release = fetchLatestRelease();
                if (release == null) {
                    Log.i(TAG, "GitHub no contestó; reintento automático en " + (CHECK_RETRY_MS / 60000) + " min");
                    return;
                }
                // GitHub contestó: recién aquí arranca la ventana de 24h.
                SharedPreferences sp = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
                sp.edit().putLong(PREF_LAST_CHECK, System.currentTimeMillis()).apply();

                String tag = release.optString("tag_name", "");
                String remoteVersion = normalizeVersion(tag);
                if (remoteVersion.isEmpty()) return;
                if (!isCodeHubUpdateRelease(release)) {
                    Log.i(TAG, "release " + tag + " descartada: no trae " + APK_ASSET_NAME);
                    return;
                }

                String localVersion = "0";
                try {
                    localVersion = activity.getPackageManager()
                        .getPackageInfo(activity.getPackageName(), 0).versionName;
                } catch (Exception ignored) {}

                if (!isNewer(remoteVersion, localVersion)) {
                    Log.i(TAG, "sin actualización (local " + localVersion + ", remota " + remoteVersion + ")");
                    return;
                }

                String apkUrl = findApkAssetUrl(release);
                if (apkUrl == null) return;

                List<String> notes = cleanNotes(release.optString("body", ""));
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
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(15000);

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

    /**
     * Devuelve true SOLO si la release trae el APK firmado de CodeHub.
     * Igualdad exacta de nombre: cualquier otra release (extensiones
     * ytm-*, etc.) ni siquiera es candidata.
     */
    private static boolean isCodeHubUpdateRelease(JSONObject release) {
        JSONArray assets = release.optJSONArray("assets");
        if (assets == null) return false;
        for (int i = 0; i < assets.length(); i++) {
            JSONObject asset = assets.optJSONObject(i);
            if (asset == null) continue;
            if (APK_ASSET_NAME.equalsIgnoreCase(asset.optString("name", ""))) return true;
        }
        return false;
    }

    private static String findApkAssetUrl(JSONObject release) {
        JSONArray assets = release.optJSONArray("assets");
        if (assets == null) return null;
        for (int i = 0; i < assets.length(); i++) {
            JSONObject asset = assets.optJSONObject(i);
            if (asset == null) continue;
            if (APK_ASSET_NAME.equalsIgnoreCase(asset.optString("name", ""))) {
                return asset.optString("browser_download_url", null);
            }
        }
        return null;
    }

    /**
     * Normaliza el tag a una versión comparable con versionName.
     * Workflow publica "apk-codehub-v7.43" → "7.43" (último bloque numérico).
     */
    private static String normalizeVersion(String tag) {
        if (tag == null || tag.isEmpty()) return "";
        java.util.regex.Matcher m = java.util.regex.Pattern
            .compile("(\\d+(?:\\.\\d+)*)")
            .matcher(tag);
        String found = null;
        while (m.find()) found = m.group(1);
        return found != null ? found : "";
    }

    /** Compara versiones "1.3.0" vs "1.2.0" numéricamente por segmento. */
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

    /**
     * Notas limpias: sin enlaces (repo nunca se muestra), sin Markdown,
     * sin líneas de guía de instalación vacías, máximo 6 puntos.
     */
    private static List<String> cleanNotes(String body) {
        List<String> out = new ArrayList<>();
        if (body == null || body.isEmpty()) return out;
        for (String raw : body.split("\\r?\\n")) {
            String l = raw == null ? "" : raw.trim();
            if (l.isEmpty()) continue;
            String low = l.toLowerCase();
            if (low.contains("http://") || low.contains("https://") || low.contains("github.com")) continue;
            l = l.replaceFirst("^#{1,6}\\s*", "")
                 .replace("**", "")
                 .replaceFirst("^[•\\-*]\\s*", "")
                 .trim();
            if (l.length() < 3) continue;
            out.add(l);
            if (out.size() >= 6) break;
        }
        return out;
    }

    /**
     * Diálogo de actualización NATIVO con el estilo propio de la app
     * (res/layout/dialog_update.xml + drawables bg_update_*). Es la
     * única puerta de actualización del APK: la web no participa.
     */
    private static void promptUpdate(final MainActivity activity, final String version,
                                     final String apkUrl, final List<String> notes) {
        new Handler(Looper.getMainLooper()).post(() -> {
            if (activity.isFinishing()) return;

            SharedPreferences prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            if (version.equals(prefs.getString(PREF_SKIP, null))) {
                Log.i(TAG, "v" + version + " no se muestra: el usuario eligió 'Más tarde' para ella");
                return;
            }

            try {
                final Dialog dialog = new Dialog(activity);
                dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
                dialog.setContentView(R.layout.dialog_update);

                Window w = dialog.getWindow();
                if (w != null) {
                    w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
                    w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.WRAP_CONTENT);
                }

                TextView ver = dialog.findViewById(R.id.dialog_update_ver);
                if (ver != null) ver.setText("v" + version);

                boolean hasNotes = notes != null && !notes.isEmpty();
                LinearLayout notesWrap = dialog.findViewById(R.id.dialog_update_notes_wrap);
                TextView notesEl = dialog.findViewById(R.id.dialog_update_notes);
                if (notesWrap != null && notesEl != null) {
                    if (hasNotes) {
                        StringBuilder sb = new StringBuilder();
                        for (String n : notes) {
                            if (sb.length() > 0) sb.append('\n');
                            sb.append("• ").append(n);
                        }
                        notesEl.setText(sb.toString());
                        notesWrap.setVisibility(View.VISIBLE);
                    } else {
                        notesWrap.setVisibility(View.GONE);
                    }
                }

                View apply = dialog.findViewById(R.id.dialog_update_apply);
                if (apply != null) {
                    apply.setOnClickListener(v -> {
                        try { dialog.dismiss(); } catch (Exception ignored) {}
                        startDownload(activity, apkUrl, version);
                    });
                }

                View later = dialog.findViewById(R.id.dialog_update_later);
                if (later != null) {
                    later.setOnClickListener(v -> {
                        prefs.edit().putString(PREF_SKIP, version).apply();
                        try { dialog.dismiss(); } catch (Exception ignored) {}
                    });
                }

                dialog.setCancelable(true);
                if (w != null) w.setWindowAnimations(-1);
                dialog.show();
            } catch (Throwable t) {
                Log.e(TAG, "promptUpdate falló", t);
            }
        });
    }

    private static void startDownload(MainActivity activity, String apkUrl, String version) {
        // Android 8+: permiso para instalar de fuentes desconocidas desde
        // ESTA app antes de que InstallReceiver dispare el instalador.
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
            // Al completarse, InstallReceiver (ACTION_DOWNLOAD_COMPLETE) lanza
            // el instalador del sistema automáticamente.
        } catch (Exception e) {
            Log.e(TAG, "startDownload falló", e);
        }
    }
}
