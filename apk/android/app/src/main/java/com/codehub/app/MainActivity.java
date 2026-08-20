package com.codehub.app;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.KeyEvent;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.GeolocationPermissions;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;

public class MainActivity extends Activity implements LocationListener {

    private static final String APP_URL = "https://wilson360-labs.vercel.app";
    private static final String CHANNEL_DEFAULT = "codehub_default";
    private static final int FILE_CHOOSER_REQUEST   = 100;
    private static final int PERMISSION_REQUEST_CODE = 200;

    private WebView webView;
    private ValueCallback<Uri[]> fileUploadCallback;
    private GeolocationPermissions.Callback geoCallback;
    private LocationManager locationManager;
    private boolean backPressedOnce = false;
    private final Handler backHandler = new Handler(Looper.getMainLooper());

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        webView = new WebView(this);
        setContentView(webView);

        try { setupStatusBar(); } catch (Throwable t) { crashLog("statusBar", t); }
        try { createNotificationChannels(); } catch (Throwable t) { crashLog("notifChannels", t); }
        // Los permisos ya NO se piden aquí. Se disparan desde JS
        // (CodeHubNative.requestRuntimePermissions, ver permissions-setup.js)
        // una vez que el splash terminó y el usuario vio el diálogo
        // explicando para qué se necesita cada permiso.
        try { setupWebView(); } catch (Throwable t) { crashLog("webView", t); }
        try { registerFCMToken(); } catch (Throwable t) { crashLog("fcm", t); }
        try { checkInternetAndLoad(); } catch (Throwable t) { crashLog("internet", t); }

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
    // Público: invocado desde CodeHubBridge.requestRuntimePermissions()
    // cuando JS (permissions-setup.js) dispara el flujo tras el splash.
    public void requestAllPermissions() {
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
    @SuppressLint("MissingPermission")
    private void startLocationUpdates() {
        locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);
        if (locationManager == null) return;
        boolean fine   = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        boolean coarse = checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        if (!fine && !coarse) return;
        try {
            Location last = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            if (last == null) last = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
            if (last != null) saveLocation(last);
            String provider = fine ? LocationManager.GPS_PROVIDER : LocationManager.NETWORK_PROVIDER;
            locationManager.requestSingleUpdate(provider, this, Looper.getMainLooper());
        } catch (Exception ignored) {}
    }

    @Override
    public void onLocationChanged(Location loc) { if (loc != null) saveLocation(loc); }
    @Override public void onStatusChanged(String p, int s, Bundle e) {}
    @Override public void onProviderEnabled(String p) {}
    @Override public void onProviderDisabled(String p) {}

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
                webView.loadUrl("javascript:(function(){" +
                    "localStorage.setItem('ch_user_lat','" + lat + "');" +
                    "localStorage.setItem('ch_user_lon','" + lon + "');" +
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

        webView.addJavascriptInterface(new CodeHubBridge(this, webView), "CodeHubNative");

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
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                injectNativeFlags(view);
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
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
        boolean notifGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
        boolean locGranted = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        boolean camGranted = checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
        boolean micGranted = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
        String js = "javascript:" +
            "window.__apkNative=true;" +
            "window.__apkOnline=" + online + ";" +
            "window.__apkVersion='" + versionName + "';" +
            "window.__apkFCMToken='" + fcmToken + "';" +
            // Refleja el estado REAL otorgado por el usuario (no valores fijos).
            "window.__apkPermissions={notifications:" + notifGranted + ",location:" + locGranted +
            ",camera:" + camGranted + ",microphone:" + micGranted +
            ",backgroundSync:true,periodicSync:true,internet:true,storage:true,offline:true};" +
            // Avisa a permissions-setup.js si está esperando el resultado del diálogo nativo.
            "if(window.__onApkPermsUpdated){try{window.__onApkPermsUpdated();}catch(e){}}" +
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
}
