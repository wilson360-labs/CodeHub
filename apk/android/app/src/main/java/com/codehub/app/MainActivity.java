package com.codehub.app;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.location.Location;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ProgressBar;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.webkit.CookieManager;
import android.webkit.GeolocationPermissions;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.google.android.gms.location.CurrentLocationRequest;
import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;
import com.google.android.gms.tasks.CancellationTokenSource;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;

public class MainActivity extends Activity {

    private static final String APP_URL = "https://wilson360-labs.vercel.app";
    private static final String CHANNEL_DEFAULT = "codehub_default";
    private static final int FILE_CHOOSER_REQUEST   = 100;
    private static final int PERMISSION_REQUEST_CODE = 200;

    private WebView webView;
    private SwipeRefreshLayout swipeRefreshLayout;
    private ProgressBar progressBar;
    private CodeHubBridge bridge;
    private ValueCallback<Uri[]> fileUploadCallback;
    private GeolocationPermissions.Callback geoCallback;
    private FusedLocationProviderClient fusedLocation;
    private boolean backPressedOnce = false;
    private final Handler backHandler = new Handler(Looper.getMainLooper());

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        webView = new WebView(this);

        swipeRefreshLayout = new SwipeRefreshLayout(this);
        swipeRefreshLayout.setColorSchemeColors(0xFF6366F1, 0xFF00E5FF, 0xFF38EF7D);
        swipeRefreshLayout.setProgressBackgroundColorSchemeColor(0xFF141424);
        swipeRefreshLayout.addView(webView, new ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        swipeRefreshLayout.setOnRefreshListener(() -> {
            if (webView != null) webView.reload();
        });

        swipeRefreshLayout.setOnChildScrollUpCallback((parent, child) -> {
            return webView != null && webView.getScrollY() > 0;
        });

        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setMax(100);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            progressBar.setProgressTintList(ColorStateList.valueOf(0xFF6366F1));
        }
        int barHeight = (int) (3 * getResources().getDisplayMetrics().density);
        FrameLayout.LayoutParams pbParams = new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, Math.max(barHeight, 6));
        progressBar.setLayoutParams(pbParams);
        progressBar.setVisibility(View.GONE);

        FrameLayout rootLayout = new FrameLayout(this);
        rootLayout.setBackgroundColor(0xFF080810);
        rootLayout.addView(swipeRefreshLayout, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        rootLayout.addView(progressBar);

        setContentView(rootLayout);

        try { setupStatusBar(); } catch (Throwable t) { crashLog("statusBar", t); }
        try { createNotificationChannels(); } catch (Throwable t) { crashLog("notifChannels", t); }
        try { requestAllPermissions(); } catch (Throwable t) { crashLog("permissions", t); }
        try { setupWebView(); } catch (Throwable t) { crashLog("webView", t); }
        try { RewardedAdManager.load(this); } catch (Throwable t) { crashLog("rewardedAd", t); }
        try { registerFCMToken(); } catch (Throwable t) { crashLog("fcm", t); }
        try { checkInternetAndLoad(); } catch (Throwable t) { crashLog("internet", t); }

        // (Chequeo de actualización vía GitHub Releases deshabilitado: el
        // repo es privado, la API pública no lo sirve y el diálogo de "release
        // notes" no debía exponer el repo. La app se actualiza vía backend.)

        // Iniciar Foreground Service — mantiene la app viva en 2do plano
        // con WakeLock + sync periódico cada 15 min.
        try { CodeHubSyncService.startIfNotRunning(this); } catch (Throwable t) { crashLog("syncService", t); }

        handleIntent(getIntent());
    }

    private void crashLog(String tag, Throwable t) {
        String msg = tag + ": " + t.getClass().getSimpleName() + " — " + t.getMessage();
        android.util.Log.e("CodeHub", msg, t);
        CrashReporter.reportCaught(getApplicationContext(), tag, t);
        try {
            File f = new File(getFilesDir(), "crash.log");
            PrintWriter pw = new PrintWriter(new FileWriter(f, true));
            pw.println("[" + tag + "] " + System.currentTimeMillis());
            t.printStackTrace(pw);
            pw.println("---");
            pw.close();
        } catch (Exception ignored) {}
    }

    // ── STATUS BAR ──────────────────────────────────────────────
    private void setupStatusBar() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            getWindow().setStatusBarColor(0xFF080810);
            getWindow().setNavigationBarColor(0xFF080810);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        }
    }

    // ── NOTIFICATION CHANNELS ───────────────────────────────────
    private void createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm == null) return;

            NotificationChannel def = new NotificationChannel(CHANNEL_DEFAULT, "CodeHub", NotificationManager.IMPORTANCE_DEFAULT);
            def.setDescription("Notificaciones generales de CodeHub");

            NotificationChannel updates = new NotificationChannel("codehub_updates", "Actualizaciones", NotificationManager.IMPORTANCE_HIGH);
            updates.setDescription("Nuevas apps y actualizaciones del catálogo");

            NotificationChannel weather = new NotificationChannel("codehub_weather", "Clima", NotificationManager.IMPORTANCE_HIGH);
            weather.setDescription("Alertas de cambio de clima");

            nm.createNotificationChannel(def);
            nm.createNotificationChannel(updates);
            nm.createNotificationChannel(weather);
        }
    }

    // ── PERMISSIONS ─────────────────────────────────────────────
    private void requestAllPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestPermissions(new String[]{
                Manifest.permission.POST_NOTIFICATIONS,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.CAMERA,
                Manifest.permission.RECORD_AUDIO
            }, PERMISSION_REQUEST_CODE);
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            requestPermissions(new String[]{
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.CAMERA,
                Manifest.permission.RECORD_AUDIO
            }, PERMISSION_REQUEST_CODE);
        }
        requestIgnoreBattery();
    }

    private void requestIgnoreBattery() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
                if (pm != null && !pm.isIgnoringBatteryOptimizations(getPackageName())) {
                    Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                    intent.setData(Uri.parse("package:" + getPackageName()));
                    startActivity(intent);
                }
            } catch (Exception ignored) {}
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CODE) {
            boolean locGranted = false;
            for (int i = 0; i < permissions.length; i++) {
                if ((permissions[i].equals(Manifest.permission.ACCESS_FINE_LOCATION) ||
                     permissions[i].equals(Manifest.permission.ACCESS_COARSE_LOCATION)) &&
                    grantResults[i] == PackageManager.PERMISSION_GRANTED) {
                    locGranted = true;
                }
            }
            if (locGranted) startLocationUpdates();
            injectNativeFlags(webView);
        }
        // Location permission from CodeHubBridge.requestLocationPermission()
        if (requestCode == 400) {
            boolean granted = false;
            for (int i = 0; i < permissions.length; i++) {
                if ((permissions[i].equals(Manifest.permission.ACCESS_FINE_LOCATION) ||
                     permissions[i].equals(Manifest.permission.ACCESS_COARSE_LOCATION)) &&
                    grantResults[i] == PackageManager.PERMISSION_GRANTED) {
                    granted = true;
                }
            }
            try {
                if (bridge != null) bridge.onLocationPermissionResult(granted);
            } catch (Exception ignored) {}
        }
    }

    // ── INTERNET CHECK ──────────────────────────────────────────
    private void checkInternetAndLoad() {
        if (isOnline()) {
            webView.loadUrl(APP_URL);
        } else {
            new AlertDialog.Builder(this)
                .setTitle("Sin conexión a internet")
                .setMessage("CodeHub necesita internet para sincronizar datos en tiempo real.\n\n¿Deseas conectar ahora?")
                .setPositiveButton("Conectar", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) { d.dismiss(); checkInternetAndLoad(); }
                })
                .setNegativeButton("Cancelar", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) { d.dismiss(); finish(); }
                })
                .setCancelable(false)
                .show();
        }
    }

    private boolean isOnline() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        if (cm == null) return false;
        NetworkInfo ni = cm.getActiveNetworkInfo();
        return ni != null && ni.isConnected();
    }

    // ── FCM TOKEN ──────────────────────────────────────────────
    private void registerFCMToken() {
        try {
            com.google.firebase.messaging.FirebaseMessaging.getInstance().getToken()
                .addOnCompleteListener(new com.google.android.gms.tasks.OnCompleteListener<String>() {
                    @Override
                    public void onComplete(com.google.android.gms.tasks.Task<String> task) {
                        try {
                            if (!task.isSuccessful() || task.getResult() == null) return;
                            final String token = task.getResult();
                            getSharedPreferences("codehub", MODE_PRIVATE).edit()
                                .putString("fcm_token", token).apply();
                            new Thread(new Runnable() {
                                @Override
                                public void run() {
                                    FcmHelper.registerToken(getApplicationContext(), token);
                                }
                            }).start();
                        } catch (Exception ignored) {}
                    }
                });
        } catch (Throwable ignored) {}
    }

    // ── LOCATION ────────────────────────────────────────────────
    // FusedLocationProviderClient (GPS + WiFi + red vía Google) en vez
    // de LocationManager crudo — misma fuente de precisión que usa
    // CodeHubBridge, para que no compitan entre sí con fixes distintos.
    private FusedLocationProviderClient getFused() {
        if (fusedLocation == null) fusedLocation = LocationServices.getFusedLocationProviderClient(this);
        return fusedLocation;
    }

    @SuppressLint("MissingPermission")
    private void startLocationUpdates() {
        boolean fine   = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        boolean coarse = checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        if (!fine && !coarse) return;
        try {
            getFused().getLastLocation()
                .addOnSuccessListener(this, loc -> { if (loc != null) saveLocation(loc); });

            CurrentLocationRequest request = new CurrentLocationRequest.Builder()
                    .setPriority(Priority.PRIORITY_HIGH_ACCURACY)
                    .setMaxUpdateAgeMillis(0)
                    .build();
            CancellationTokenSource cts = new CancellationTokenSource();
            getFused().getCurrentLocation(request, cts.getToken())
                .addOnSuccessListener(this, loc -> { if (loc != null) saveLocation(loc); });
            new Handler(Looper.getMainLooper()).postDelayed(cts::cancel, 15000);
        } catch (Exception ignored) {}
    }

    private void saveLocation(Location loc) {
        final double lat = loc.getLatitude();
        final double lon = loc.getLongitude();
        SharedPreferences prefs = getSharedPreferences("codehub", MODE_PRIVATE);
        prefs.edit()
            .putLong("lat_bits", Double.doubleToRawLongBits(lat))
            .putLong("lon_bits", Double.doubleToRawLongBits(lon))
            .apply();
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (webView == null) return;
                webView.loadUrl("javascript:(function(){try{" +
                    "localStorage.setItem('ch_user_lat','" + lat + "');" +
                    "localStorage.setItem('ch_user_lon','" + lon + "');" +
                    "}catch(e){}" +
                    "try{if(typeof chSavePushLocation==='function')chSavePushLocation(" + lat + "," + lon + ",'GPS','');}catch(e){}" +
                    "try{if(typeof chNotifWeatherCheck==='function')chNotifWeatherCheck(" + lat + "," + lon + ");}catch(e){}" +
                    "})()");
            }
        });
    }

    // ── WEBVIEW SETUP ───────────────────────────────────────────
    @SuppressLint("SetJavaScriptEnabled")
    private void setupWebView() {
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        s.setDatabaseEnabled(true);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);
        s.setSupportZoom(false);
        s.setSupportMultipleWindows(false);
        s.setJavaScriptCanOpenWindowsAutomatically(true);
        s.setGeolocationEnabled(true);

        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true);

        bridge = new CodeHubBridge(this, webView);
        webView.addJavascriptInterface(bridge, "CodeHubNative");

        webView.setDownloadListener((url, userAgent, contentDisposition, mimeType, contentLength) -> {
            if (url == null || url.isEmpty()) return;
            DownloadManager dm = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
            if (dm == null) return;
            try {
                DownloadManager.Request req = new DownloadManager.Request(Uri.parse(url));
                req.setMimeType(mimeType);
                String fileName = "download";
                if (contentDisposition != null) {
                    int idx = contentDisposition.indexOf("filename=");
                    if (idx > -1) fileName = contentDisposition.substring(idx + 9).replace("\"", "").trim();
                }
                final String safeFileName = fileName;
                req.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, safeFileName);
                req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                req.setTitle(safeFileName);
                req.setDescription("Descargando desde CodeHub");
                dm.enqueue(req);
                runOnUiThread(() -> Toast.makeText(this, "Descargando " + safeFileName, Toast.LENGTH_SHORT).show());
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this, "Error al descargar: " + e.getMessage(), Toast.LENGTH_SHORT).show());
            }
        });

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String url = request.getUrl().toString();
                if (url.contains("wilson360-labs.vercel.app") || url.contains("codehub-98s6.onrender.com")) {
                    return false;
                }
                try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); } catch (Exception ignored) {}
                return true;
            }

            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                super.onPageStarted(view, url, favicon);
                if (progressBar != null) {
                    progressBar.setProgress(15);
                    progressBar.setVisibility(View.VISIBLE);
                }
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                if (swipeRefreshLayout != null) swipeRefreshLayout.setRefreshing(false);
                if (progressBar != null) progressBar.setVisibility(View.GONE);
                injectNativeFlags(view);
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                super.onReceivedError(view, request, error);
                if (swipeRefreshLayout != null) swipeRefreshLayout.setRefreshing(false);
                if (progressBar != null) progressBar.setVisibility(View.GONE);
                if (request != null && request.isForMainFrame()) {
                    showOfflineFallback(view);
                }
            }

            @SuppressWarnings("deprecation")
            @Override
            public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
                super.onReceivedError(view, errorCode, description, failingUrl);
                if (swipeRefreshLayout != null) swipeRefreshLayout.setRefreshing(false);
                if (progressBar != null) progressBar.setVisibility(View.GONE);
                // BUG CORREGIDO: este overload se dispara para CADA request
                // de subrecurso que falla (tiles de mapa, JS, imágenes…), no
                // solo para la página principal. Antes, si un tile del mapa
                // fallaba (p.ej. MapTiler 403 o tile bloqueado por red), la
                // app mostraba la pantalla "Sin conexión" y DESTRUÍA todo el
                // contenido WebView — por eso las capas del mapa "no se
                // veían". Ahora solo actuamos si el fallo es del frame
                // principal; los errores de subrecursos se ignoran.
                if (failingUrl != null
                        && !failingUrl.equals(view.getUrl())
                        && !failingUrl.equals(view.getOriginalUrl())
                        && !failingUrl.equals("about:blank")
                        && !failingUrl.startsWith("data:")) {
                    return; // error de subrecurso → no tocar la interfaz
                }
                if (failingUrl != null && (failingUrl.startsWith("http://") || failingUrl.startsWith("https://"))) {
                    showOfflineFallback(view);
                }
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                super.onProgressChanged(view, newProgress);
                if (progressBar != null) {
                    progressBar.setProgress(newProgress);
                    if (newProgress >= 100) {
                        progressBar.setVisibility(View.GONE);
                        if (swipeRefreshLayout != null) swipeRefreshLayout.setRefreshing(false);
                    } else {
                        progressBar.setVisibility(View.VISIBLE);
                    }
                }
            }

            @Override
            public void onPermissionRequest(final android.webkit.PermissionRequest request) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() { request.grant(request.getResources()); }
                });
            }

            @Override
            public void onGeolocationPermissionsShowPrompt(String origin, GeolocationPermissions.Callback callback) {
                geoCallback = callback;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                        callback.invoke(origin, true, false);
                        geoCallback = null;
                    } else {
                        requestPermissions(new String[]{
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION
                        }, PERMISSION_REQUEST_CODE);
                    }
                } else {
                    callback.invoke(origin, true, false);
                }
            }

            @Override
            public boolean onShowFileChooser(WebView webView, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (fileUploadCallback != null) fileUploadCallback.onReceiveValue(null);
                fileUploadCallback = callback;
                try { startActivityForResult(params.createIntent(), FILE_CHOOSER_REQUEST); }
                catch (Exception e) { fileUploadCallback = null; return false; }
                return true;
            }
        });

        webView.setOnKeyListener(new View.OnKeyListener() {
            @Override
            public boolean onKey(View v, int keyCode, KeyEvent event) {
                if (keyCode == KeyEvent.KEYCODE_BACK && webView.canGoBack()) {
                    webView.goBack();
                    return true;
                }
                return false;
            }
        });
    }

    // ── INJECT NATIVE FLAGS ─────────────────────────────────────
    private void injectNativeFlags(WebView view) {
        if (view == null) return;
        String online = isOnline() ? "true" : "false";
        String versionName = "1.2.0";
        try { versionName = getPackageManager().getPackageInfo(getPackageName(), 0).versionName; } catch (Exception ignored) {}
        String fcmToken = getSharedPreferences("codehub", MODE_PRIVATE).getString("fcm_token", "");
        String js = "javascript:" +
            "window.__apkNative=true;" +
            "window.__apkOnline=" + online + ";" +
            "window.__apkVersion='" + versionName + "';" +
            "window.__apkFCMToken='" + fcmToken + "';" +
            "window.__apkPermissions={notifications:true,location:true,backgroundSync:true,periodicSync:true,internet:true,offline:true};" +
            "try{localStorage.setItem('pwa_installed','1');}catch(e){}" +
            // Reporta errores JS no atrapados y promesas rechazadas sin catch
            // al bridge nativo, que los reenvía a /api/crash-report (Telegram).
            // Se instala solo una vez por carga de página (flag __apkCrashHooked).
            "if(!window.__apkCrashHooked){window.__apkCrashHooked=true;" +
            "window.onerror=function(m,s,l,c,er){try{if(window.CodeHubNative&&CodeHubNative.reportJsError){" +
            "CodeHubNative.reportJsError(String(m),String(s||location.href),String(l||0),String(c||0),(er&&er.stack)?String(er.stack):'');" +
            "}}catch(e){}return false;};" +
            "window.addEventListener('unhandledrejection',function(ev){try{var r=ev.reason;" +
            "var msg=(r&&r.message)?r.message:String(r);var st=(r&&r.stack)?String(r.stack):'';" +
            "if(window.CodeHubNative&&CodeHubNative.reportJsError){" +
            "CodeHubNative.reportJsError('UnhandledRejection: '+msg,location.href,'0','0',st);" +
            "}}catch(e){}});}";
        view.loadUrl(js);
    }

    // ── INTENT FROM NOTIFICATION ────────────────────────────────
    private void handleIntent(Intent intent) {
        if (intent == null) return;
        String url = intent.getStringExtra("open_url");
        if (url != null && !url.isEmpty()) {
            final String loadUrl = url;
            webView.postDelayed(new Runnable() {
                @Override
                public void run() { webView.loadUrl(loadUrl); }
            }, 1500);
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleIntent(intent);
    }

    // ── DOUBLE BACK EXIT ────────────────────────────────────────
    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
        } else if (backPressedOnce) {
            finish();
        } else {
            backPressedOnce = true;
            Toast.makeText(this, "Presiona atrás otra vez para salir", Toast.LENGTH_SHORT).show();
            backHandler.postDelayed(new Runnable() {
                @Override
                public void run() { backPressedOnce = false; }
            }, 2000);
        }
    }

    // ── LIFECYCLE ───────────────────────────────────────────────
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == FILE_CHOOSER_REQUEST && fileUploadCallback != null) {
            Uri[] results = null;
            if (resultCode == RESULT_OK && data != null && data.getDataString() != null) {
                results = new Uri[]{Uri.parse(data.getDataString())};
            }
            fileUploadCallback.onReceiveValue(results);
            fileUploadCallback = null;
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (webView != null) webView.onResume();
        injectNativeFlags(webView);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (webView != null) webView.onPause();
    }

    @Override
    protected void onDestroy() {
        if (webView != null) webView.destroy();
        super.onDestroy();
    }

    // ── OFFLINE FALLBACK ─────────────────────────────────────────
    private void showOfflineFallback(WebView view) {
        if (view == null) return;
        String offlineHtml = "<!DOCTYPE html><html lang='es'><head><meta charset='utf-8'>" +
            "<meta name='viewport' content='width=device-width,initial-scale=1,maximum-scale=1,user-scalable=no'>" +
            "<title>Sin conexión — CodeHub</title>" +
            "<style>" +
            "* { box-sizing: border-box; margin: 0; padding: 0; }" +
            "body { background: #080810; color: #f0f0f5; font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif; display: flex; flex-direction: column; align-items: center; justify-content: center; min-height: 100vh; padding: 2rem; text-align: center; }" +
            ".card { background: #12121e; border: 1px solid rgba(255,255,255,0.08); border-radius: 20px; padding: 2.2rem 1.8rem; max-width: 360px; width: 100%; box-shadow: 0 16px 40px rgba(0,0,0,0.6); }" +
            ".icon { font-size: 3.2rem; margin-bottom: 1rem; }" +
            "h1 { font-size: 1.35rem; font-weight: 700; margin-bottom: 0.6rem; color: #fff; }" +
            "p { font-size: 0.88rem; color: #9a9ab2; line-height: 1.55; margin-bottom: 1.6rem; }" +
            "button { width: 100%; background: linear-gradient(135deg, #6366f1, #4f46e5); color: #fff; border: none; padding: 0.85rem 1.4rem; border-radius: 12px; font-size: 0.95rem; font-weight: 600; cursor: pointer; box-shadow: 0 4px 14px rgba(99,102,241,0.4); }" +
            "button:active { transform: scale(0.98); opacity: 0.9; }" +
            "</style></head>" +
            "<body><div class='card'>" +
            "<div class='icon'>📡</div>" +
            "<h1>Sin conexión a internet</h1>" +
            "<p>No se pudo cargar CodeHub. Comprueba tu conexión Wi-Fi o datos móviles e inténtalo de nuevo.</p>" +
            "<button onclick=\"location.href='" + APP_URL + "'\">🔄 Reintentar conexión</button>" +
            "</div></body></html>";
        view.loadDataWithBaseURL(APP_URL, offlineHtml, "text/html", "UTF-8", null);
    }
}
