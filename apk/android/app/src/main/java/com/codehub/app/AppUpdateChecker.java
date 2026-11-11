package com.codehub.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;
import com.google.firebase.appcheck.FirebaseAppCheck;
import com.google.android.gms.tasks.Tasks;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Actualización de CodeHub, 100% NATIVA (dentro del APK).
 *
 * - La web/PWA no participa: la disponibilidad aparece en la portada Kotlin.
 * - Habla directo con GitHub Releases del repo (tag con versión, secuencia y SHA).
 * - Se selecciona el APK de la ABI del dispositivo; se acepta el asset
 *   universal firmado como compatibilidad para equipos no ARM.
 *   Con el criterio viejo (cualquier asset *.apk),
 *   una release de extensión (youtube-music-codebeat.eapk) terminaba en
 *   ".apk" y se hacía pasar por actualización de CodeHub — esto elimina
 *   ese bug de raíz.
 * - Las notas se limpian antes de mostrarse: jamás se muestran enlaces
 *   al repo (Full Changelog / compare) ni sintaxis Markdown.
 * - La portada nativa recibe el release y su botón delega la descarga y
 *   validación al instalador común de la CodeHub Store.
 *
 * Uso: AppUpdateChecker.checkIfDue(activity);  // en MainActivity.onCreate Y onResume
 */
public class AppUpdateChecker {

    private static final String TAG = "CodeHubUpdater";
    private static final String GITHUB_OWNER = "wilson360-labs";
    private static final String GITHUB_REPO  = "CodeHub";
    private static final String RELEASES_LATEST_URL =
        "https://api.github.com/repos/" + GITHUB_OWNER + "/" + GITHUB_REPO + "/releases/latest";
    private static final String UPDATE_POLICY_URL =
        "https://codehub-98s6.onrender.com/api/mobile/policy";

    private static final String APK_ASSET_NAME = "app-release-signed.apk";
    private static final String APK_ARM64_ASSET_NAME = "app-release-arm64-v8a.apk";
    private static final String APK_ARMV7_ASSET_NAME = "app-release-armeabi-v7a.apk";
    private static final String APK_X86_64_ASSET_NAME = "app-release-x86_64.apk";
    private static final String APK_X86_ASSET_NAME = "app-release-x86.apk";

    public static final String SELF_UPDATE_ID = "codehub-self-update";
    public static final String PREFS = "codehub";
    public static final String PREF_UPDATE_PENDING = "self_update_pending";
    public static final String PREF_UPDATE_PENDING_AT = "self_update_pending_at";
    public static final String PREF_UPDATE_PENDING_CODE = "self_update_pending_code";
    public static final String PREF_UPDATE_PENDING_VERSION = "self_update_pending_version";
    public static final String PREF_UPDATE_PENDING_BUILD = "self_update_pending_build";
    private static final String PREF_AVAILABLE_VERSION = "app_update_available_version";
    private static final String PREF_AVAILABLE_BUILD = "app_update_available_build";
    private static final String PREF_AVAILABLE_URL = "app_update_available_url";
    private static final String PREF_AVAILABLE_CODE = "app_update_available_code";
    private static final String PREF_LAST_CHECK = "update_last_check_ts";
    private static final String PREF_LAST_ATTEMPT = "update_last_attempt_ts";
    private static final String PREF_LAST_POLICY_CHECK = "update_policy_last_check_ts";
    private static final long CHECK_INTERVAL_MS = 24L * 60 * 60 * 1000; // éxito: 1 vez al día
    private static final long POLICY_CHECK_INTERVAL_MS = 15L * 60 * 1000;
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
        checkPolicyIfDue(activity, prefs);
        long now = System.currentTimeMillis();
        long lastSuccess = prefs.getLong(PREF_LAST_CHECK, 0);
        long lastAttempt = prefs.getLong(PREF_LAST_ATTEMPT, 0);
        if (now - lastSuccess < CHECK_INTERVAL_MS || now - lastAttempt < CHECK_RETRY_MS) {
            showCachedBanner(activity);
            return;
        }
        prefs.edit().putLong(PREF_LAST_ATTEMPT, now).apply();
        checkNow(activity);
    }

    /**
     * Chequeo de diagnostico: devuelve un JSON con el estado (sin throttle,
     * sin dialogo). Usado por el boton "Verificar actualizacion".
     */
    public static String checkStatus(final Context context) {
        org.json.JSONObject out = new org.json.JSONObject();
        try {
            org.json.JSONObject release = fetchLatestRelease();
            if (release == null) {
                out.put("ok", false);
                out.put("reason", "network");
                out.put("message", "No se pudo conectar a GitHub (red o bloqueo)");
                return out.toString();
            }
            String tag = release.optString("tag_name", "");
            String remote = normalizeVersion(tag);
            if (remote.isEmpty()) {
                out.put("ok", false);
                out.put("reason", "parse");
                out.put("message", "No se pudo leer la version remota: " + tag);
                return out.toString();
            }
            if (!isCodeHubUpdateRelease(release)) {
                out.put("ok", false);
                out.put("reason", "no_asset");
                out.put("message", "La release " + tag + " no contiene un APK compatible con " + preferredAssetName());
                return out.toString();
            }
            String local = "0";
            try {
                local = context.getPackageManager()
                        .getPackageInfo(context.getPackageName(), 0).versionName;
            } catch (Exception ignored) {}
            long remoteCode = releaseVersionCode(release);
            long localCode = localVersionCode(context);
            boolean hasUpdate = isNewerBuild(remote, remoteCode, local, localCode);
            out.put("ok", true);
            out.put("remote", remote);
            out.put("local", local);
            out.put("remoteVersionCode", remoteCode);
            out.put("localVersionCode", localCode);
            out.put("build", releaseBuildLabel(release));
            out.put("abi", preferredAssetName());
            out.put("hasUpdate", hasUpdate);
            out.put("message", hasUpdate
                    ? ("Actualizacion disponible: v" + remote + " " + releaseBuildLabel(release))
                    : ("Ya tienes la ultima version (v" + local + ")"));
            return out.toString();
        } catch (Throwable t) {
            try { out.put("ok", false); out.put("reason", "error"); out.put("message", String.valueOf(t.getMessage())); } catch (Exception ignored) {}
            return out.toString();
        }
    }

    /** Chequeo inmediato (queda disponible para futuros usos en ajustes). */
    public static void checkNow(final MainActivity activity) {
        checkNow(activity, -1L);
    }

    private static void checkNow(final MainActivity activity, final long knownMinimumCode) {
        new Thread(() -> {
            try {
                long minimumSupportedCode = knownMinimumCode >= 0
                        ? knownMinimumCode : fetchMinimumSupportedCode(activity);
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

                long remoteCode = releaseVersionCode(release);
                long localCode = localVersionCode(activity);
                if (!isNewerBuild(remoteVersion, remoteCode, localVersion, localCode)) {
                    Log.i(TAG, "sin actualización (local v" + localVersion + " code " + localCode
                            + ", remota v" + remoteVersion + " code " + remoteCode + ")");
                    clearCachedUpdate(activity);
                    activity.runOnUiThread(activity::hideAppUpdateBanner);
                    return;
                }

                String apkUrl = findApkAssetUrl(release);
                if (apkUrl == null) return;

                String buildLabel = releaseBuildLabel(release);
                UpdateInfo info = new UpdateInfo(remoteVersion, buildLabel, apkUrl, remoteCode,
                        minimumSupportedCode > 0 && localCode < minimumSupportedCode);
                cacheUpdate(activity, info);
                activity.runOnUiThread(() -> activity.showAppUpdateBanner(info));
            } catch (Throwable t) {
                Log.e(TAG, "Error chequeando actualización", t);
            }
        }).start();
    }

    /** Refresca la fecha de corte aparte del chequeo diario de GitHub Releases. */
    private static void checkPolicyIfDue(MainActivity activity, SharedPreferences prefs) {
        long now = System.currentTimeMillis();
        if (now - prefs.getLong(PREF_LAST_POLICY_CHECK, 0L) < POLICY_CHECK_INTERVAL_MS) return;
        prefs.edit().putLong(PREF_LAST_POLICY_CHECK, now).apply();
        new Thread(() -> {
            long minimumCode = fetchMinimumSupportedCode(activity);
            if (minimumCode < 0) return; // error de red/App Check: conservar estado anterior
            long localCode = localVersionCode(activity);
            boolean required = minimumCode > 0 && localCode < minimumCode;
            SharedPreferences current = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            String version = current.getString(PREF_AVAILABLE_VERSION, "");
            String build = current.getString(PREF_AVAILABLE_BUILD, "");
            String url = current.getString(PREF_AVAILABLE_URL, "");
            long code = current.getLong(PREF_AVAILABLE_CODE, 0L);
            if (version.isEmpty() || url.isEmpty()) {
                if (required) checkNow(activity, minimumCode);
                return;
            }
            String localVersion = "0";
            try {
                localVersion = activity.getPackageManager()
                        .getPackageInfo(activity.getPackageName(), 0).versionName;
            } catch (Exception ignored) { }
            if (!required && !isNewerBuild(version, code, localVersion, localCode)) {
                clearCachedUpdate(activity);
                activity.runOnUiThread(activity::hideAppUpdateBanner);
                return;
            }
            UpdateInfo info = new UpdateInfo(version, build, url, code, required);
            cacheUpdate(activity, info);
            activity.runOnUiThread(() -> activity.showAppUpdateBanner(info));
        }).start();
    }

    /**
     * Lee la política remota con un token App Check. Si Render/Firebase no
     * responde, se falla abierto: una caída del servicio no debe inutilizar
     * CodeHub ni impedir que el updater directo de GitHub siga funcionando.
     */
    private static long fetchMinimumSupportedCode(Context context) {
        HttpURLConnection conn = null;
        try {
            String token = Tasks.await(
                    FirebaseAppCheck.getInstance().getAppCheckToken(false), 12,
                    java.util.concurrent.TimeUnit.SECONDS).getToken();
            if (token == null || token.isEmpty()) return -1L;
            conn = (HttpURLConnection) new URL(UPDATE_POLICY_URL).openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("X-Firebase-AppCheck", token);
            conn.setRequestProperty("X-CodeHub-Client", "android");
            conn.setRequestProperty("X-CodeHub-Version-Code", String.valueOf(localVersionCode(context)));
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);
            int status = conn.getResponseCode();
            if (status != HttpURLConnection.HTTP_OK && status != 426) return -1L;
            InputStream response = status == HttpURLConnection.HTTP_OK
                    ? conn.getInputStream() : conn.getErrorStream();
            if (response == null) return -1L;
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(response, StandardCharsets.UTF_8))) {
                StringBuilder body = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) body.append(line);
                JSONObject json = new JSONObject(body.toString());
                JSONObject policy = json.optJSONObject("policy");
                return policy == null ? -1L : Math.max(0L,
                        policy.optLong("minimumSupportedVersionCode", 0L));
            }
        } catch (Throwable t) {
            Log.i(TAG, "Política remota no disponible; se conserva modo compatible: " + t.getMessage());
            return -1L;
        } finally {
            if (conn != null) conn.disconnect();
        }
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

    /** Only accept the ABI-specific APK or the exact universal fallback asset. */
    private static boolean isCodeHubUpdateRelease(JSONObject release) {
        JSONArray assets = release.optJSONArray("assets");
        if (assets == null) return false;
        String preferred = preferredAssetName();
        for (int i = 0; i < assets.length(); i++) {
            JSONObject asset = assets.optJSONObject(i);
            if (asset == null) continue;
            String name = asset.optString("name", "");
            if (preferred.equalsIgnoreCase(name) || APK_ASSET_NAME.equalsIgnoreCase(name)) return true;
        }
        return false;
    }

    private static String findApkAssetUrl(JSONObject release) {
        JSONArray assets = release.optJSONArray("assets");
        if (assets == null) return null;
        String preferred = preferredAssetName();
        String universalUrl = null;
        for (int i = 0; i < assets.length(); i++) {
            JSONObject asset = assets.optJSONObject(i);
            if (asset == null) continue;
            String name = asset.optString("name", "");
            if (preferred.equalsIgnoreCase(name)) {
                return asset.optString("browser_download_url", null);
            }
            if (APK_ASSET_NAME.equalsIgnoreCase(name)) universalUrl = asset.optString("browser_download_url", null);
        }
        return universalUrl;
    }

    private static String preferredAssetName() {
        for (String abi : Build.SUPPORTED_ABIS) {
            if ("arm64-v8a".equals(abi)) return APK_ARM64_ASSET_NAME;
            if ("armeabi-v7a".equals(abi)) return APK_ARMV7_ASSET_NAME;
            if ("x86_64".equals(abi)) return APK_X86_64_ASSET_NAME;
            if ("x86".equals(abi)) return APK_X86_ASSET_NAME;
        }
        return APK_ASSET_NAME;
    }

    /** Normaliza tags antiguos y nuevos: apk-codehub-v7.59-build2-SHA → 7.59. */
    private static String normalizeVersion(String tag) {
        if (tag == null || tag.isEmpty()) return "";
        java.util.regex.Matcher m = java.util.regex.Pattern
            .compile("(?:^|-)v(\\d+(?:\\.\\d+)+)(?:-|$)")
            .matcher(tag);
        return m.find() ? m.group(1) : "";
    }

    /** Lee el versionCode publicado en el cuerpo de la release; 0 para releases antiguas. */
    private static long releaseVersionCode(JSONObject release) {
        String body = release == null ? "" : release.optString("body", "");
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(?im)^\\s*\\*\\*CodeHub-Version-Code:\\*\\*\\s*(\\d+)\\s*$")
                .matcher(body);
        if (!m.find()) return 0L;
        try { return Long.parseLong(m.group(1)); }
        catch (Exception ignored) { return 0L; }
    }

    /** Etiqueta visible y estable para builds que comparten versionName. */
    private static String releaseBuildLabel(JSONObject release) {
        String tag = release == null ? "" : release.optString("tag_name", "");
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(?i)-build(\\d+)-([0-9a-f]{7,40})$")
                .matcher(tag);
        if (m.find()) return "build " + m.group(1) + "/5 · " + m.group(2);
        return "";
    }

    public static final class UpdateInfo {
        public final String version;
        public final String buildLabel;
        public final String apkUrl;
        public final long versionCode;
        public final boolean required;

        UpdateInfo(String version, String buildLabel, String apkUrl, long versionCode) {
            this(version, buildLabel, apkUrl, versionCode, false);
        }

        UpdateInfo(String version, String buildLabel, String apkUrl, long versionCode, boolean required) {
            this.version = version;
            this.buildLabel = buildLabel;
            this.apkUrl = apkUrl;
            this.versionCode = versionCode;
            this.required = required;
        }
    }

    private static void cacheUpdate(Context context, UpdateInfo info) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(PREF_AVAILABLE_VERSION, info.version)
                .putString(PREF_AVAILABLE_BUILD, info.buildLabel)
                .putString(PREF_AVAILABLE_URL, info.apkUrl)
                .putLong(PREF_AVAILABLE_CODE, info.versionCode)
                .putBoolean("app_update_required", info.required)
                .apply();
    }

    private static void clearCachedUpdate(Context context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .remove(PREF_AVAILABLE_VERSION)
                .remove(PREF_AVAILABLE_BUILD)
                .remove(PREF_AVAILABLE_URL)
                .remove(PREF_AVAILABLE_CODE)
                .remove("app_update_required")
                .apply();
    }

    private static void showCachedBanner(MainActivity activity) {
        SharedPreferences prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String version = prefs.getString(PREF_AVAILABLE_VERSION, "");
        String build = prefs.getString(PREF_AVAILABLE_BUILD, "");
        String url = prefs.getString(PREF_AVAILABLE_URL, "");
        long code = prefs.getLong(PREF_AVAILABLE_CODE, 0L);
        boolean required = prefs.getBoolean("app_update_required", false);
        if (version.isEmpty() || url.isEmpty()) return;

        String localVersion = "0";
        try {
            localVersion = activity.getPackageManager()
                    .getPackageInfo(activity.getPackageName(), 0).versionName;
        } catch (Exception ignored) { }
        if (!required && !isNewerBuild(version, code, localVersion, localVersionCode(activity))) {
            clearCachedUpdate(activity);
            activity.runOnUiThread(activity::hideAppUpdateBanner);
            return;
        }
        UpdateInfo info = new UpdateInfo(version, build, url, code, required);
        activity.runOnUiThread(() -> activity.showAppUpdateBanner(info));
    }

    public static long localVersionCode(Context context) {
        try {
            android.content.pm.PackageInfo info = context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), 0);
            return Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                    ? info.getLongVersionCode() : info.versionCode;
        } catch (Exception ignored) { return 0L; }
    }

    /** Prefiere versionCode para ofrecer builds sucesivos con el mismo versionName. */
    private static boolean isNewerBuild(String remoteVersion, long remoteCode,
                                        String localVersion, long localCode) {
        if (remoteCode > 0 && localCode > 0) return remoteCode > localCode;
        return isNewer(remoteVersion, localVersion);
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

}
