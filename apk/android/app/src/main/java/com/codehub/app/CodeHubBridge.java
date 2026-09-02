package com.codehub.app;

import android.app.Activity;
import android.app.DownloadManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.location.Location;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.Manifest;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import com.google.firebase.messaging.FirebaseMessaging;
import com.google.android.gms.location.CurrentLocationRequest;
import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;
import com.google.android.gms.tasks.CancellationTokenSource;

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

    // ── VOZ NATIVA (TTS + STT) ───────────────────────────────────
    private TextToSpeech tts;
    private SpeechRecognizer sr;
    private String sttCallback = "";

    private void ensureTts() {
        if (tts != null) return;
        tts = new TextToSpeech(activity, status -> {
            if (status != TextToSpeech.SUCCESS || tts == null) {
                if (tts != null) tts.setLanguage(Locale.getDefault());
            }
        });
        if (tts != null) {
            int setLang = tts.setLanguage(new Locale("spa", "GT"));
            if (setLang == TextToSpeech.LANG_MISSING_DATA || setLang == TextToSpeech.LANG_NOT_SUPPORTED) {
                tts.setLanguage(Locale.getDefault());
            }
            tts.setSpeechRate(0.95f);
            tts.setPitch(0.75f);
        }
    }

    // WIL.E hable en voz alta (TTS nativo de Android) en español.
    @JavascriptInterface
    public void ttsSpeak(final String text) {
        activity.runOnUiThread(() -> {
            try {
                if (text == null || text.trim().isEmpty()) return;
                ensureTts();
                if (tts == null) return;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "wile-utt");
                } else {
                    @SuppressWarnings("deprecation")
                    HashMap<String, String> params = new HashMap<>();
                    params.put(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, "wile-utt");
                    tts.speak(text, TextToSpeech.QUEUE_FLUSH, params);
                }
            } catch (Exception ignored) {}
        });
    }

    @JavascriptInterface
    public void ttsStop() {
        activity.runOnUiThread(() -> {
            try { if (tts != null) tts.stop(); } catch (Exception ignored) {}
        });
    }

    @JavascriptInterface
    public boolean ttsIsAvailable() {
        try {
            android.content.Intent checkIntent = new android.content.Intent();
            checkIntent.setAction(TextToSpeech.Engine.ACTION_CHECK_TTS_DATA);
            android.content.pm.PackageManager pm = activity.getPackageManager();
            return !pm.queryIntentActivities(checkIntent, 0).isEmpty();
        } catch (Exception e) { return false; }
    }

    // Dicta lo que diga el usuario (STT nativo de Android) y lo envía al JS.
    @JavascriptInterface
    public void sttStart(final String callbackName) {
        activity.runOnUiThread(() -> {
            try {
                sttCallback = callbackName == null ? "" : callbackName;
                int granted = activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO);
                if (granted != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    notifySttError("Permiso de micrófono requerido para dictar.");
                    return;
                }
                if (sr != null) { sr.destroy(); }
                sr = SpeechRecognizer.createSpeechRecognizer(activity);
                if (sr == null) { notifySttError("Reconocimiento de voz no disponible."); return; }
                sr.setRecognitionListener(new RecognitionListener() {
                    public void onReadyForSpeech(android.os.Bundle b) { notifySttEvent("ready"); }
                    public void onBeginningOfSpeech() {}
                    public void onRmsChanged(float v) {}
                    public void onBufferReceived(byte[] b) {}
                    public void onEndOfSpeech() {}
                    public void onError(int error) { notifySttError("error_nativo_" + error); }
                    public void onResults(android.os.Bundle results) {
                        ArrayList<String> matches = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                        if (matches != null && !matches.isEmpty()) {
                            notifySttResult(matches.get(0));
                        } else {
                            notifySttError("No se escuchó nada.");
                        }
                    }
                    public void onPartialResults(android.os.Bundle partial) {}
                    public void onEvent(int e, android.os.Bundle b) {}
                });
                Intent in = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
                in.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
                in.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "es-GT");
                in.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "es");
                in.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
                in.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
                sr.startListening(in);
            } catch (Exception ex) {
                notifySttError("Error iniciando reconocimiento.");
            }
        });
    }

    @JavascriptInterface
    public void sttStop() {
        activity.runOnUiThread(() -> {
            try { if (sr != null) { sr.stopListening(); sr.destroy(); sr = null; } } catch (Exception ignored) {}
        });
    }

    private void notifySttEvent(String type) {
        if (webView == null) return;
        String js = "javascript:(function(){ var cb=window." + sttCallback + "; if(cb&&cb.onStart)cb.onStart('" + type + "'); })()";
        activity.runOnUiThread(() -> { try { webView.loadUrl(js); } catch (Exception ignored) {} });
    }

    private void notifySttResult(String transcript) {
        if (webView == null) return;
        String safe = transcript.replace("\\", "\\\\").replace("'", "\\'").replace("\"", "\\\"").replace("\n", " ");
        String js = "javascript:(function(){ var cb=window." + sttCallback + "; if(cb){ cb.onResult && cb.onResult('" + safe + "'); } })()";
        activity.runOnUiThread(() -> { try { webView.loadUrl(js); } catch (Exception ignored) {} });
    }

    private void notifySttError(String msg) {
        if (webView == null) return;
        String safe = msg.replace("\\", "\\\\").replace("'", "\\'").replace("\"", "\\\"");
        String js = "javascript:(function(){ var cb=window." + sttCallback + "; if(cb){ cb.onError && cb.onError('" + safe + "'); } })()";
        activity.runOnUiThread(() -> { try { webView.loadUrl(js); } catch (Exception ignored) {} });
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
        // Deshabilitado: el repo es privado, la API pública de GitHub no
        // sirve sus releases y no debe exponerse el repo. La actualización
        // se maneja vía backend (/api/changelog + descarga del APK).
        return null;
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

    // ── LOCATION (permission + GPS) ────────────────────────────
    private static final int LOC_PERMISSION_REQUEST = 400;
    private String pendingLocCallback = null;

    /**
     * Called from MainActivity.onRequestPermissionsResult when request
     * code == LOC_PERMISSION_REQUEST.
     */
    public void onLocationPermissionResult(boolean granted) {
        String cb = pendingLocCallback;
        pendingLocCallback = null;
        if (cb == null) return;
        if (granted) {
            // Permission granted — now get the actual location
            doGetLocation(cb);
        } else {
            callbackNull(cb);
        }
    }

    /**
     * requestLocationPermission(callbackName) — Checks if location
     * permission is granted. If yes, gets location immediately. If no,
     * requests it from the user, then gets location after grant.
     * Callback: callbackName(lat, lon, accuracy) or callbackName(null)
     */
    @JavascriptInterface
    public void requestLocationPermission(final String callbackName) {
        boolean hasFine   = activity.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        boolean hasCoarse = activity.checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;

        if (hasFine || hasCoarse) {
            doGetLocation(callbackName);
            return;
        }

        // Need to request permission from user
        pendingLocCallback = callbackName;
        activity.runOnUiThread(() -> {
            activity.requestPermissions(new String[]{
                android.Manifest.permission.ACCESS_FINE_LOCATION,
                android.Manifest.permission.ACCESS_COARSE_LOCATION
            }, LOC_PERMISSION_REQUEST);
        });
    }

    /**
     * doGetLocation(callbackName) — Uses Google Play Services'
     * FusedLocationProviderClient, which fuses GPS + WiFi + cell signals
     * via Google's positioning backend. This is materially more accurate
     * than reading raw LocationManager.NETWORK_PROVIDER fixes directly
     * (that provider's self-reported accuracy is frequently optimistic
     * and can be off by kilometers — the previous cause of "wrong city"
     * reports on the APK). Falls back to getLastLocation() if a fresh
     * fix can't be obtained in time.
     */
    private void doGetLocation(final String callbackName) {
        try {
            FusedLocationProviderClient fused = LocationServices.getFusedLocationProviderClient(activity);
            CurrentLocationRequest request = new CurrentLocationRequest.Builder()
                    .setPriority(Priority.PRIORITY_HIGH_ACCURACY)
                    .setMaxUpdateAgeMillis(0) // exigir un fix fresco, no uno viejo cacheado por el SO
                    .build();
            CancellationTokenSource cts = new CancellationTokenSource();

            fused.getCurrentLocation(request, cts.getToken())
                .addOnSuccessListener(activity, loc -> {
                    if (loc != null) {
                        returnLocation(callbackName, loc);
                    } else {
                        // Sin fix fresco disponible (p. ej. GPS apagado) — usar el último conocido
                        fused.getLastLocation()
                            .addOnSuccessListener(activity, last -> {
                                if (last != null) returnLocation(callbackName, last);
                                else callbackNull(callbackName);
                            })
                            .addOnFailureListener(activity, e -> callbackNull(callbackName));
                    }
                })
                .addOnFailureListener(activity, e -> {
                    // getCurrentLocation falló (permisos, Play Services, etc.) — intentar el último conocido
                    fused.getLastLocation()
                        .addOnSuccessListener(activity, last -> {
                            if (last != null) returnLocation(callbackName, last);
                            else callbackNull(callbackName);
                        })
                        .addOnFailureListener(activity, e2 -> callbackNull(callbackName));
                });

            // 15s hard timeout — si Fused nunca responde, no dejar el JS colgado
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                cts.cancel();
            }, 15000);

        } catch (SecurityException se) {
            callbackNull(callbackName);
        } catch (Exception e) {
            callbackNull(callbackName);
        }
    }

    private void returnLocation(String callbackName, Location loc) {
        saveLocation(loc.getLatitude(), loc.getLongitude());
        final double lat = loc.getLatitude();
        final double lon = loc.getLongitude();
        final float acc = loc.getAccuracy();
        activity.runOnUiThread(() -> webView.loadUrl(
            "javascript:try{if(window." + callbackName + ")window." + callbackName +
            "(" + lat + "," + lon + "," + acc + ");}catch(e){}"));
    }

    private void callbackNull(String callbackName) {
        activity.runOnUiThread(() -> webView.loadUrl(
            "javascript:try{if(window." + callbackName + ")window." + callbackName + "(null);}catch(e){}"));
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

    // ── COMPARTIR NATIVO ─────────────────────────────────────────
    @JavascriptInterface
    public void shareText(final String title, final String text, final String url) {
        activity.runOnUiThread(() -> {
            try {
                Intent intent = new Intent(Intent.ACTION_SEND);
                intent.setType("text/plain");
                StringBuilder sb = new StringBuilder();
                if (text != null && !text.isEmpty()) sb.append(text);
                if (url != null && !url.isEmpty()) {
                    if (sb.length() > 0) sb.append("\n");
                    sb.append(url);
                }
                String content = sb.toString().trim();
                if (title != null && !title.isEmpty()) {
                    intent.putExtra(Intent.EXTRA_SUBJECT, title);
                    intent.putExtra(Intent.EXTRA_TITLE, title);
                }
                intent.putExtra(Intent.EXTRA_TEXT, content);
                activity.startActivity(Intent.createChooser(intent, title != null && !title.isEmpty() ? title : "Compartir con"));
            } catch (Exception ignored) {}
        });
    }

    // ── VIBRACIÓN HÁPTICA ────────────────────────────────────────
    @JavascriptInterface
    public void vibrate(long ms) {
        try {
            long duration = ms > 0 ? Math.min(ms, 1000) : 40;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                android.os.VibratorManager vm = (android.os.VibratorManager) activity.getSystemService(Context.VIBRATOR_MANAGER_SERVICE);
                if (vm != null) {
                    vm.getDefaultVibrator().vibrate(android.os.VibrationEffect.createOneShot(duration, android.os.VibrationEffect.DEFAULT_AMPLITUDE));
                }
            } else {
                @SuppressWarnings("deprecation")
                android.os.Vibrator v = (android.os.Vibrator) activity.getSystemService(Context.VIBRATOR_SERVICE);
                if (v != null) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        v.vibrate(android.os.VibrationEffect.createOneShot(duration, android.os.VibrationEffect.DEFAULT_AMPLITUDE));
                    } else {
                        v.vibrate(duration);
                    }
                }
            }
        } catch (Exception ignored) {}
    }

    // ── PORTAPAPELES NATIVO ──────────────────────────────────────
    @JavascriptInterface
    public void copyToClipboard(final String text) {
        if (text == null) return;
        activity.runOnUiThread(() -> {
            try {
                android.content.ClipboardManager cb = (android.content.ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
                if (cb != null) {
                    android.content.ClipData clip = android.content.ClipData.newPlainText("CodeHub", text);
                    cb.setPrimaryClip(clip);
                    android.widget.Toast.makeText(activity, "Copiado al portapapeles", android.widget.Toast.LENGTH_SHORT).show();
                }
            } catch (Exception ignored) {}
        });
    }

    // ── LIMPIEZA DE CACHÉ ────────────────────────────────────────
    @JavascriptInterface
    public void clearAppCache() {
        activity.runOnUiThread(() -> {
            try {
                if (webView != null) {
                    webView.clearCache(true);
                    webView.clearFormData();
                    webView.clearHistory();
                }
                android.webkit.WebStorage.getInstance().deleteAllData();
                android.widget.Toast.makeText(activity, "Caché de la app limpiada", android.widget.Toast.LENGTH_SHORT).show();
            } catch (Exception ignored) {}
        });
    }

    // ── MODO OSCURO DEL SISTEMA ─────────────────────────────────
    @JavascriptInterface
    public boolean isSystemDarkMode() {
        try {
            int nightModeFlags = activity.getResources().getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK;
            return nightModeFlags == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        } catch (Exception e) {
            return false;
        }
    }
}
