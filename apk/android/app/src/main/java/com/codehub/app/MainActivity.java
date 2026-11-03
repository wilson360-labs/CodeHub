package com.codehub.app;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.ComponentCallbacks2;
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
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ProgressBar;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
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
    static final int BACKUP_IMPORT_REQUEST  = 101;
    private static final int NATIVE_STORE_REQUEST = 102;
    private static final int NATIVE_MY_APPS_REQUEST = 103;
    private static final int NATIVE_COPILOT_REQUEST = 104;
    private static final int PERMISSION_REQUEST_CODE = 200;
    private static final int WEB_PERMISSION_REQUEST_CODE = 201;
    static final int MICROPHONE_PERMISSION_REQUEST_CODE = 401;

    private WebView webView;
    private String pendingWebUrl;
    private boolean webViewInitScheduled;
    private NativeSplashView nativeSplash;
    private NativeHomeView nativeHome;
    private SwipeRefreshLayout swipeRefreshLayout;
    private ProgressBar progressBar;
    private CodeHubBridge bridge;
    private String pendingSharedText;
    private ValueCallback<Uri[]> fileUploadCallback;
    private GeolocationPermissions.Callback geoCallback;
    private String pendingGeoOrigin;
    private android.webkit.PermissionRequest pendingWebPermissionRequest;
    private FusedLocationProviderClient fusedLocation;
    /** Franja inferior reservada para el banner (nunca superpuesto al contenido). */
    private FrameLayout bannerSlot;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        swipeRefreshLayout = new SwipeRefreshLayout(this);
        swipeRefreshLayout.setColorSchemeColors(0xFF6366F1, 0xFF00E5FF, 0xFF6366F1);
        swipeRefreshLayout.setProgressBackgroundColorSchemeColor(0xFF141424);
        swipeRefreshLayout.setVisibility(View.GONE);
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

        // La portada Kotlin es la pantalla inicial; el WebView queda oculto
        // hasta que el usuario abre una sección web. Banners siguen abajo.
        LinearLayout rootLayout = new LinearLayout(this);
        rootLayout.setOrientation(LinearLayout.VERTICAL);
        rootLayout.setBackgroundColor(0xFF080B11);

        FrameLayout mainFrame = new FrameLayout(this);
        mainFrame.addView(swipeRefreshLayout, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        mainFrame.addView(progressBar);
        nativeHome = new NativeHomeView(this, new NativeHomeView.Listener() {
            @Override public void onOpenStore() { openNativeStore(); }
            @Override public void onOpenMyApps() { openNativeMyApps(); }
            @Override public void onOpenCodeBeat() { openNativeCodeBeat(); }
            @Override public void onOpenCopilot() { openNativeCopilot(); }
            @Override public void onOpenWebsite(String path) {
                String suffix = path == null || path.isEmpty() ? "/" : path;
                openWebUrl(APP_URL + (suffix.startsWith("/") ? suffix : "/" + suffix));
            }
        });
        mainFrame.addView(nativeHome, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        nativeSplash = new NativeSplashView(this);
        mainFrame.addView(nativeSplash, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        rootLayout.addView(mainFrame, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        bannerSlot = new FrameLayout(this);
        bannerSlot.setVisibility(View.GONE);
        rootLayout.addView(bannerSlot, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        setContentView(rootLayout);
        SystemBars.applyInsets(this);
        nativeSplash.setLoadProgress(100);
        nativeSplash.dismiss();

        try { setupStatusBar(); } catch (Throwable t) { crashLog("statusBar", t); }
        try { createNotificationChannels(); } catch (Throwable t) { crashLog("notifChannels", t); }
        try { WeatherNotificationProvider.refresh(this); } catch (Throwable t) { crashLog("wxNotif", t); }
        // Las funciones solicitan permisos al usarse; no bloquear el primer inicio con varios diálogos.
        try { initAdMob(); } catch (Throwable t) { crashLog("adMob", t); }
        try { registerFCMToken(); } catch (Throwable t) { crashLog("fcm", t); }
        // Chequeo de actualizaciones SOLO nativo: la app consulta GitHub
        // Releases (release con el asset app-release-signed.apk) y usa su
        // DIÁLOGO PROPIO (res/layout/dialog_update.xml) dentro del APK.
        // La web/PWA ya no muestra diálogos de actualización: el APK se
        // actualiza desde el APK.
        try { AppUpdateChecker.checkIfDue(this); } catch (Throwable t) { crashLog("updateCheck", t); }

        // Sync en 2do plano vía WorkManager (token FCM + ubicación cada 15 min).
        // Ya NO se usa Foreground Service tipo dataSync: en Android 15/16 el
        // sistema lo mata por límite de tiempo y lanzaba excepciones que
        // tumbaban el proceso.
        try {
            CodeHubSyncScheduler.schedule(this);
            CodeHubSyncScheduler.syncNow(this);
        } catch (Throwable t) { crashLog("syncScheduler", t); }

        handleIntent(getIntent());
    }

    // ── Anuncios: consentimiento UMP + Unity Ads ──────────────────────
    // Nada de anuncios se pide antes de que UMP resuelva el consentimiento
    // (EEE/UK/Canadá). Fuera de esas regiones lookup UMP resuelve inmediato
    // con canRequestAds=true y no se bloquea ningún anuncio.
    private void initAdMob() {
        ConsentManager.init(this, () -> {
            runOnUiThread(() -> {
                if (!ConsentManager.canRequestAds()) return;
                UnityAdsManager.init(MainActivity.this, bannerSlot);
            });
        });
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

    /**
     * Pantalla nativa de recuperación cuando el proveedor de WebView del
     * dispositivo falla al inicializarse (p. ej. "failed to redirect
     * ResourcesImpl" en Android 16 con Android System WebView dañado).
     * Evita que la Activity muera y ofrece reintentar sin salir de la app.
     */
    private void showWebViewUnavailable() {
        if (nativeHome != null) {
            final String retryUrl = pendingWebUrl;
            pendingWebUrl = null;
            nativeHome.setVisibility(View.VISIBLE);
            if (swipeRefreshLayout != null) swipeRefreshLayout.setVisibility(View.GONE);
            if (progressBar != null) progressBar.setVisibility(View.GONE);
            new AlertDialog.Builder(this)
                .setTitle("El visor web no está disponible")
                .setMessage("Actualiza Android System WebView y Chrome. El inicio nativo y la tienda siguen disponibles.")
                .setPositiveButton("Reintentar", (dialog, which) -> {
                    if (retryUrl != null) {
                        pendingWebUrl = retryUrl;
                        nativeHome.setVisibility(View.GONE);
                        if (swipeRefreshLayout != null) swipeRefreshLayout.setVisibility(View.VISIBLE);
                        initializeWebViewOnDemand();
                    }
                })
                .setNegativeButton("Seguir en inicio", null)
                .show();
            return;
        }
        float density = getResources().getDisplayMetrics().density;
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        box.setBackgroundColor(0xFF080B11);
        int pad = (int) (24 * density);
        box.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("No se pudo iniciar el visor web");
        title.setTextColor(0xFFFFFFFF);
        title.setTextSize(20);
        title.setGravity(Gravity.CENTER);

        TextView msg = new TextView(this);
        msg.setText("Actualiza \"Android System WebView\" y \"Chrome\" desde Play Store, "
            + "luego vuelve a abrir CodeHub.");
        msg.setTextColor(0xFFB0B0C0);
        msg.setTextSize(14);
        msg.setGravity(Gravity.CENTER);
        int gap = (int) (16 * density);
        msg.setPadding(0, gap, 0, gap);

        Button retry = new Button(this);
        retry.setText("Reintentar");
        retry.setAllCaps(false);
        retry.setOnClickListener(v -> recreate());

        Button close = new Button(this);
        close.setText("Cerrar");
        close.setAllCaps(false);
        close.setOnClickListener(v -> finish());

        box.addView(title);
        box.addView(msg);
        box.addView(retry);
        box.addView(close);
        setContentView(box);
    }

    // ── STATUS BAR ──────────────────────────────────────────────
    private void setupStatusBar() {
        SystemBars.fit(this);
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
    private void startQuakeDetector() {
        try {
            SharedPreferences p = getSharedPreferences("codehub", MODE_PRIVATE);
            if (!p.getBoolean("quake_detector_enabled", true)) return;
            Intent i = new Intent(this, QuakeDetectorService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(i);
            } else {
                startService(i);
            }
        } catch (Exception ignored) {}
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
            if (geoCallback != null) {
                geoCallback.invoke(pendingGeoOrigin, locGranted, false);
                geoCallback = null;
                pendingGeoOrigin = null;
            }
            injectNativeFlags(webView);
        }
        if (requestCode == WEB_PERMISSION_REQUEST_CODE) {
            android.webkit.PermissionRequest pending = pendingWebPermissionRequest;
            pendingWebPermissionRequest = null;
            if (pending != null) {
                boolean cameraGranted = checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
                boolean audioGranted = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
                boolean approved = true;
                for (String resource : pending.getResources()) {
                    if (android.webkit.PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(resource) && !cameraGranted) approved = false;
                    if (android.webkit.PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(resource) && !audioGranted) approved = false;
                }
                try {
                    if (approved) pending.grant(pending.getResources()); else pending.deny();
                } catch (IllegalStateException ignored) {}
            }
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
        if (requestCode == MICROPHONE_PERMISSION_REQUEST_CODE) {
            boolean granted = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
            try {
                if (bridge != null) bridge.onMicrophonePermissionResult(granted);
            } catch (Exception ignored) {}
        }
        injectNativeFlags(webView);
    }

    // ── NAVEGACIÓN A SECCIONES WEB ──────────────────────────────
    /** Opens a web-only section while keeping the Kotlin home available via Back. */
    private void openWebUrl(String url) {
        if (swipeRefreshLayout == null) return;
        if (!isOnline()) {
            new AlertDialog.Builder(this)
                .setTitle("Sin conexión a internet")
                .setMessage("Esta sección necesita internet. La pantalla principal de CodeHub sigue disponible.")
                .setPositiveButton("Reintentar", (dialog, which) -> openWebUrl(url))
                .setNegativeButton("Volver", (dialog, which) -> pendingSharedText = null)
                .show();
            return;
        }
        if (nativeHome != null) nativeHome.setVisibility(View.GONE);
        swipeRefreshLayout.setVisibility(View.VISIBLE);
        if (webView == null) {
            pendingWebUrl = url;
            if (progressBar != null) progressBar.setVisibility(View.VISIBLE);
            initializeWebViewOnDemand();
            return;
        }
        webView.loadUrl(url);
    }

    /** Build WebView only when a web-only section is requested so native Inicio renders immediately. */
    private void initializeWebViewOnDemand() {
        if (webView != null || webViewInitScheduled || swipeRefreshLayout == null) return;
        webViewInitScheduled = true;
        swipeRefreshLayout.postDelayed(() -> {
            webViewInitScheduled = false;
            if (isFinishing() || isDestroyed() || webView != null) return;
            try {
                webView = new WebView(this);
                swipeRefreshLayout.addView(webView, new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                setupWebView();
                injectNativeFlags(webView);
                String url = pendingWebUrl;
                pendingWebUrl = null;
                if (url != null) webView.loadUrl(url);
            } catch (Throwable t) {
                if (webView != null) {
                    if (webView.getParent() instanceof ViewGroup) {
                        ((ViewGroup) webView.getParent()).removeView(webView);
                    }
                    try { webView.destroy(); } catch (Throwable ignored) {}
                    webView = null;
                }
                bridge = null;
                crashLog("webViewCreate", t);
                showWebViewUnavailable();
            }
        }, 80L);
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
        try { WeatherNotificationProvider.refresh(this); } catch (Throwable ignored) {}
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
        // Permitir TODOS los recursos externos (tiles de mapa, APIs de clima,
        // anuncios/rastreadores). Chrome Safe Browsing bloquea por defecto
        // hosts que considera "maliciosos" o de rastreo, lo que en CodeHub
        // hacía que no cargasen tiles de mapa/capas ni algunas APIs.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            s.setSafeBrowsingEnabled(false);
        }
        s.setBlockNetworkImage(false);
        s.setBlockNetworkLoads(false);
        s.setLoadsImagesAutomatically(true);

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
                Uri uri = request.getUrl();
                String path = uri.getPath();
                if (path != null && path.length() > 1 && path.endsWith("/")) {
                    path = path.substring(0, path.length() - 1);
                }
                if ("wilson360-labs.vercel.app".equalsIgnoreCase(uri.getHost())
                        && ("/mis-apps".equals(path) || "/tienda".equals(path) || "/opensource".equals(path)
                            || "/pages/tienda.html".equals(path) || "/pages/opensource.html".equals(path))) {
                    if ("/mis-apps".equals(path)) openNativeMyApps();
                    else openNativeStore();
                    return true;
                }
                // Validación por HOST EXACTO (no contains): "wilson360-labs.
                // vercel.app.evil.com" no debe quedarse dentro del WebView.
                // about/data/blob: se dejan pasar (recursos internos).
                if (url.startsWith("about:") || url.startsWith("data:") || url.startsWith("blob:")) {
                    return false;
                }
                if (isAppHost(url)) return false;
                try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); } catch (Exception ignored) {}
                return true;
            }

            /** true si el host es uno de los dominios de la app (comparación
             *  exacta de host, sin subdominios falsos). */
            private boolean isAppHost(String url) {
                try {
                    String host = Uri.parse(url).getHost();
                    if (host == null) return false;
                    return "wilson360-labs.vercel.app".equalsIgnoreCase(host)
                        || "codehub-98s6.onrender.com".equalsIgnoreCase(host);
                } catch (Exception e) { return false; }
            }

            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                super.onPageStarted(view, url, favicon);
                if (nativeSplash != null) nativeSplash.setLoadProgress(8);
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
                if (nativeSplash != null) {
                    nativeSplash.setLoadProgress(100);
                    nativeSplash.dismiss();
                }
                injectNativeFlags(view);
                if (pendingSharedText != null && bridge != null) {
                    String sharedText = pendingSharedText;
                    pendingSharedText = null;
                    view.postDelayed(() -> bridge.handleSharedText(sharedText), 250);
                }
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                super.onReceivedError(view, request, error);
                if (swipeRefreshLayout != null) swipeRefreshLayout.setRefreshing(false);
                if (progressBar != null) progressBar.setVisibility(View.GONE);
                if (request != null && request.isForMainFrame()) {
                    if (nativeSplash != null) nativeSplash.dismiss();
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
                    if (nativeSplash != null) nativeSplash.dismiss();
                    showOfflineFallback(view);
                }
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                super.onProgressChanged(view, newProgress);
                if (nativeSplash != null) nativeSplash.setLoadProgress(newProgress);
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
                    public void run() {
                        if (request == null || isFinishing() || isDestroyed()) return;
                        java.util.ArrayList<String> missing = new java.util.ArrayList<>();
                        boolean needsCamera = false;
                        boolean needsAudio = false;
                        boolean unsupportedResource = false;
                        for (String resource : request.getResources()) {
                            if (android.webkit.PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(resource)) needsCamera = true;
                            else if (android.webkit.PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(resource)) needsAudio = true;
                            else unsupportedResource = true;
                        }
                        if ((!needsCamera && !needsAudio) || unsupportedResource) {
                            request.deny();
                            return;
                        }
                        if (needsCamera && checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                            missing.add(Manifest.permission.CAMERA);
                        }
                        if (needsAudio && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                            missing.add(Manifest.permission.RECORD_AUDIO);
                        }
                        if (missing.isEmpty()) {
                            request.grant(request.getResources());
                            return;
                        }
                        if (pendingWebPermissionRequest != null) {
                            try { pendingWebPermissionRequest.deny(); } catch (IllegalStateException ignored) {}
                        }
                        pendingWebPermissionRequest = request;
                        requestPermissions(missing.toArray(new String[0]), WEB_PERMISSION_REQUEST_CODE);
                    }
                });
            }

            @Override
            public void onGeolocationPermissionsShowPrompt(String origin, GeolocationPermissions.Callback callback) {
                geoCallback = callback;
                pendingGeoOrigin = origin;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    boolean hasLocation = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                        || checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
                    if (hasLocation) {
                        callback.invoke(origin, true, false);
                        geoCallback = null;
                        pendingGeoOrigin = null;
                    } else {
                        requestPermissions(new String[]{
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION
                        }, PERMISSION_REQUEST_CODE);
                    }
                } else {
                    callback.invoke(origin, true, false);
                    geoCallback = null;
                    pendingGeoOrigin = null;
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
        NotificationManager notificationManager = getSystemService(NotificationManager.class);
        boolean notificationsGranted = (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
            || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)
            && notificationManager != null && notificationManager.areNotificationsEnabled();
        boolean locationGranted = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
            || checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        boolean cameraGranted = checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
        boolean microphoneGranted = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
        String js = "javascript:" +
            "window.__apkNative=true;" +
            "window.__apkOnline=" + online + ";" +
            "window.__apkVersion='" + versionName + "';" +
            "window.__apkFCMToken='" + fcmToken + "';" +
            "window.__apkPermissions={notifications:" + notificationsGranted + ",location:" + locationGranted +
                ",camera:" + cameraGranted + ",microphone:" + microphoneGranted +
                ",backgroundSync:true,periodicSync:true,internet:" + online + ",offline:true};" +
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

    // ── INTENT FROM NOTIFICATION / DEEP LINK / SHARE ────────
    private void handleIntent(Intent intent) {
        if (intent == null) return;

        // Share target nativo (ACTION_SEND, text/plain): si lo que compartió
        // otra app contiene una URL de CodeHub, se abre aquí; si es texto
        // suelto, se reenvía a la web para que la procese (búsqueda/chat).
        if (Intent.ACTION_SEND.equals(intent.getAction()) && "text/plain".equals(intent.getType())) {
            String shared = intent.getStringExtra(Intent.EXTRA_TEXT);
            if (shared != null && !shared.isEmpty()) {
                final String trimmed = shared.trim();
                String url = extractCodeHubUrl(trimmed);
                if (url != null && !url.isEmpty()) {
                    openWebUrl(url);
                    return;
                }
                // Texto (no URL) → se deja en la web vía bridge para search/chat.
                pendingSharedText = trimmed;
                openWebUrl(APP_URL);
                return;
            }
            return;
        }

        String url = intent.getStringExtra("open_url");
        // Deep link: la app se abrió/levantó con un enlace del dominio
        // (ACTION_VIEW + data https/http de wilson360-labs.vercel.app).
        if (url == null && intent.getData() != null && Intent.ACTION_VIEW.equals(intent.getAction())) {
            url = intent.getData().toString();
        }
        if (url != null && !url.isEmpty() && isAllowedUrl(url)) {
            openWebUrl(url);
        }
    }

    /** Busca la primera URL http(s) del texto compartido que pertenezca a
     *  CodeHub (host exacto). Devuelve null si no hay ninguna permitida. */
    private String extractCodeHubUrl(String text) {
        try {
            String[] parts = text.split("\\s+");
            for (String p : parts) {
                String clean = p.trim().replaceAll("[.,;:!?)]+$", "");
                if (clean.startsWith("http://") || clean.startsWith("https://")) {
                    if (isAllowedUrl(clean)) return clean;
                    return null; // hay URL, pero no es de CodeHub → no abrir
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    /** Solo se permiten URLs del dominio propio (http/https). Todo lo demás
     *  se ignora: evita cargar páginas arbitrarias viajadas por el intent. */
    private boolean isAllowedUrl(String url) {
        if (url == null) return false;
        try {
            Uri uri = Uri.parse(url.trim());
            String scheme = uri.getScheme();
            if (!"https".equalsIgnoreCase(scheme) && !"http".equalsIgnoreCase(scheme)) return false;
            String host = uri.getHost();
            if (host == null) return false;
            // Frontera exacta de host: "wilson360-labs.vercel.app.evil.com" no pasa
            return "wilson360-labs.vercel.app".equalsIgnoreCase(host);
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleIntent(intent);
    }

    // ── BACK NAVIGATION ─────────────────────────────────────────
    // Back follows the visible screen; leaving from home requires explicit confirmation.
    @Override
    public void onBackPressed() {
        if (swipeRefreshLayout != null && swipeRefreshLayout.getVisibility() == View.VISIBLE
                && webView != null && webView.getVisibility() == View.VISIBLE && webView.canGoBack()) {
            webView.goBack();
            return;
        }

        if (nativeHome != null && nativeHome.getVisibility() != View.VISIBLE) {
            nativeHome.setVisibility(View.VISIBLE);
            if (webView != null) webView.setVisibility(View.GONE);
            if (swipeRefreshLayout != null) swipeRefreshLayout.setVisibility(View.GONE);
            return;
        }

        new AlertDialog.Builder(this)
            .setTitle("¿Salir de CodeHub?")
            .setMessage("Puedes seguir explorando la tienda, tus apps y el resto de CodeHub.")
            .setNegativeButton("Seguir aquí", null)
            .setPositiveButton("Salir", (dialog, which) -> finish())
            .show();
    }
    // ── LIFECYCLE ───────────────────────────────────────────────
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if ((requestCode == NATIVE_STORE_REQUEST || requestCode == NATIVE_MY_APPS_REQUEST)
                && resultCode == RESULT_OK && data != null) {
            String apkUrl = data.getStringExtra(NativeStoreActivity.EXTRA_APK_URL);
            String appId = data.getStringExtra(NativeStoreActivity.EXTRA_APP_ID);
            if (bridge != null && apkUrl != null && appId != null) {
                String packageName = data.getStringExtra(NativeStoreActivity.EXTRA_PACKAGE_NAME);
                boolean preferSilent = data.getBooleanExtra(NativeMyAppsActivity.EXTRA_PREFER_SILENT, false);
                bridge.downloadAndInstallStoreApk(apkUrl, appId, packageName, preferSilent, "CodeHubNativeStoreInstallResult");
                Toast.makeText(this, "Descargando " + appId + "…", Toast.LENGTH_SHORT).show();
            }
            return;
        }
        // Respaldo 1-tap — archivo JSON de configuración elegido con
        // ACTION_OPEN_DOCUMENT (CodeHubBridge.pickBackupFile).
        if (requestCode == BACKUP_IMPORT_REQUEST) {
            Uri picked = (resultCode == RESULT_OK && data != null) ? data.getData() : null;
            try {
                if (bridge != null) bridge.onBackupFilePicked(picked);
            } catch (Exception ignored) {}
            return;
        }
        if (requestCode == FILE_CHOOSER_REQUEST && fileUploadCallback != null) {
            Uri[] results = null;
            if (resultCode == RESULT_OK && data != null && data.getDataString() != null) {
                results = new Uri[]{Uri.parse(data.getDataString())};
            }
            fileUploadCallback.onReceiveValue(results);
            fileUploadCallback = null;
        }
    }

    private void openNativeStore() {
        try {
            startActivityForResult(new Intent(this, NativeStoreActivity.class), NATIVE_STORE_REQUEST);
        } catch (Exception e) {
            openWebUrl(APP_URL + "/pages/tienda.html");
        }
    }

    private void openNativeMyApps() {
        try {
            startActivityForResult(new Intent(this, NativeMyAppsActivity.class), NATIVE_MY_APPS_REQUEST);
        } catch (Exception e) {
            openWebUrl(APP_URL + "/opensource#my-apps-section");
        }
    }

    private void openNativeCodeBeat() {
        try {
            MusicSessionBridge.onPlayerOpened();
        } catch (Throwable ignored) {}
        try {
            dev.brahmkshatriya.echo.EchoBoot.start(this);
        } catch (Throwable t) {
            android.util.Log.e("CodeHub", "Echo no arrancó desde inicio nativo", t);
        }
        if (dev.brahmkshatriya.echo.EchoBoot.isReady()) {
            try {
                startActivity(new Intent(this, dev.brahmkshatriya.echo.EchoBoot.mainActivity(this)));
                return;
            } catch (Exception ignored) {}
        }
        startActivity(new Intent(this, com.codehub.app.music.ui.MusicPlayerActivity.class));
    }

    /** Open the Kotlin WIL.E screen and reuse the current WebView auth session when present. */
    private void openNativeCopilot() {
        Runnable launchGuest = () -> startActivityForResult(
                new Intent(this, NativeAiChatActivity.class), NATIVE_COPILOT_REQUEST);
        if (webView == null || webView.getUrl() == null) {
            launchGuest.run();
            return;
        }
        String host = Uri.parse(webView.getUrl()).getHost();
        if (host == null || !("wilson360-labs.vercel.app".equalsIgnoreCase(host)
                || "www.wilson360-labs.vercel.app".equalsIgnoreCase(host))) {
            launchGuest.run();
            return;
        }
        webView.evaluateJavascript("(function(){try{var s=JSON.parse(sessionStorage.getItem('ch_auth_session')||localStorage.getItem('ch_auth_session')||'null');return s&&s.token?s.token:''}catch(e){return ''}})()", value -> {
            String token = "";
            try {
                Object parsed = new org.json.JSONTokener(value == null ? "\"\"" : value).nextValue();
                if (parsed instanceof String) token = (String) parsed;
            } catch (Exception ignored) {}
            Intent intent = new Intent(this, NativeAiChatActivity.class);
            if (!token.isEmpty()) intent.putExtra(NativeAiChatActivity.EXTRA_AUTH_TOKEN, token);
            startActivityForResult(intent, NATIVE_COPILOT_REQUEST);
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (webView != null) webView.onResume();
        injectNativeFlags(webView);
        // Re-verificar Shizuku al volver a la app: si el usuario acaba de
        // conceder el permiso (diálogo in-app o app Shizuku), el panel web
        // recibe el estado en vivo sin recargar la página.
        try { if (bridge != null) bridge.pushShizukuState(); } catch (Throwable ignored) {}
        // Control de música flotante: escuchar la sesión de medios solo
        // mientras la WebView está en pantalla.
        try { if (bridge != null) bridge.musicAttach(); } catch (Throwable ignored) {}
        // Chequeo de actualización también al volver del segundo plano (el
        // cold-start de.onCreate no ocurre si la app sigue viva en recientes):
        // AppUpdateChecker tiene throttle interno y decide si toca chequear.
        try { AppUpdateChecker.checkIfDue(this); } catch (Throwable t) { crashLog("updateCheckResume", t); }
    }

    @Override
    protected void onPostResume() {
        super.onPostResume();
        // La Activity ya esta visible; iniciar aqui evita hacerlo desde
        // onCreate o desde un receiver de arranque en segundo plano.
        try { startQuakeDetector(); } catch (Throwable t) { crashLog("quakeDetector", t); }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (webView != null) webView.onPause();
        // Sin WebView en primer plano no se empuja estado de reproducción.
        try { if (bridge != null) bridge.musicDetach(); } catch (Throwable ignored) {}
    }

    @Override
    protected void onDestroy() {
        if (pendingWebPermissionRequest != null) {
            try { pendingWebPermissionRequest.deny(); } catch (IllegalStateException ignored) {}
            pendingWebPermissionRequest = null;
        }
        if (webView != null) webView.destroy();
        super.onDestroy();
    }

    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        // Bajo presion de memoria libera la cache de disco del WebView; el
        // service worker y los recursos de la pagina se revalidan solos, asi
        // que el cambio es transparente para el usuario.
        if (level >= ComponentCallbacks2.TRIM_MEMORY_MODERATE && webView != null) {
            webView.clearCache(false);
        }
    }

    // ── OFFLINE FALLBACK ─────────────────────────────────────────
    private void showOfflineFallback(WebView view) {
        if (view == null) return;
        if (nativeSplash != null) nativeSplash.dismiss();
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
