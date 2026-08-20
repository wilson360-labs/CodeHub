package com.codehub.app;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.KeyEvent;
import android.view.View;
import android.view.Window;
import android.webkit.CookieManager;
import android.webkit.GeolocationPermissions;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

public class MainActivity extends Activity implements LocationListener {

    private static final String APP_URL = "https://wilson360-labs.vercel.app";
    private static final int FILE_CHOOSER_REQUEST = 100;
    private static final int PERMISSION_REQUEST_CODE = 200;

    private WebView webView;
    private android.webkit.ValueCallback<Uri[]> fileUploadCallback;
    private GeolocationPermissions.Callback geoCallback;
    private String geoOrigin;
    private LocationManager locationManager;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        webView = new WebView(this);
        setContentView(webView);

        setupStatusBar();
        createNotificationChannel();
        requestAllPermissions();
        setupWebView();
        checkInternetAndLoad();
    }

    private void setupStatusBar() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            getWindow().setStatusBarColor(0xFF080810);
            getWindow().setNavigationBarColor(0xFF080810);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel("codehub_default", "CodeHub", NotificationManager.IMPORTANCE_HIGH);
            ch.setDescription("Notificaciones de CodeHub");
            ch.enableVibration(true);
            NotificationManager m = getSystemService(NotificationManager.class);
            if (m != null) m.createNotificationChannel(ch);
        }
    }

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
        // Request to ignore battery optimizations for background sync
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
        }
    }

    private void checkInternetAndLoad() {
        if (isOnline()) {
            webView.loadUrl(APP_URL);
        } else {
            new AlertDialog.Builder(this)
                .setTitle("Sin conexión a internet")
                .setMessage("CodeHub necesita internet para sincronizar datos en tiempo real.\n\n¿Deseas conectar ahora?")
                .setPositiveButton("Conectar", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        d.dismiss();
                        checkInternetAndLoad();
                    }
                })
                .setNegativeButton("Cancelar", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        d.dismiss();
                        finish();
                    }
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

    @SuppressLint("MissingPermission")
    private void startLocationUpdates() {
        locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);
        if (locationManager == null) return;
        boolean fine = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
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
    public void onLocationChanged(Location loc) {
        if (loc != null) saveLocation(loc);
    }

    @Override
    public void onStatusChanged(String p, int s, Bundle e) {}
    @Override
    public void onProviderEnabled(String p) {}
    @Override
    public void onProviderDisabled(String p) {}

    private void saveLocation(Location loc) {
        final double lat = loc.getLatitude();
        final double lon = loc.getLongitude();
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                webView.loadUrl("javascript:(function(){" +
                    "localStorage.setItem('ch_user_lat','" + lat + "');" +
                    "localStorage.setItem('ch_user_lon','" + lon + "');" +
                    "try{if(typeof chSavePushLocation==='function')chSavePushLocation(" + lat + "," + lon + ",'GPS','');}catch(e){}" +
                    "try{if(typeof chNotifWeatherCheck==='function')chNotifWeatherCheck(" + lat + "," + lon + ");}catch(e){}" +
                    "})()");
            }
        });
    }

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
                geoOrigin = origin;
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
            public boolean onShowFileChooser(WebView webView, android.webkit.ValueCallback<Uri[]> callback, FileChooserParams params) {
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

    private void injectNativeFlags(WebView view) {
        String online = isOnline() ? "true" : "false";
        String js = "javascript:" +
            "window.__apkNative=true;" +
            "window.__apkOnline=" + online + ";" +
            "window.__apkPermissions={notifications:true,location:true,backgroundSync:true,periodicSync:true,internet:true,storage:true,offline:true};" +
            "try{localStorage.setItem('pwa_installed','1');}catch(e){}";
        view.loadUrl(js);
    }

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
        // Re-inject flags when coming back
        if (webView != null) injectNativeFlags(webView);
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
