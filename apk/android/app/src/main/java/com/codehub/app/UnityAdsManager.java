package com.codehub.app;

import android.app.Activity;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import com.unity3d.ads.IUnityAdsInitializationListener;
import com.unity3d.ads.IUnityAdsLoadListener;
import com.unity3d.ads.IUnityAdsShowListener;
import com.unity3d.ads.UnityAds;
import com.unity3d.ads.UnityAdsShowOptions;
import com.unity3d.services.banners.BannerErrorInfo;
import com.unity3d.services.banners.BannerView;
import com.unity3d.services.banners.UnityBannerSize;

/**
 * Unity Ads (SDK directo): banners, interstitials y rewarded.
 *
 * El contrato JS `cb(earned, amount, type)` de CodeHubBridge se mantiene, por
 * lo que la web no necesita cambios.
 *
 * Proyecto Unity: GAME_ID 800397583.
 * Placements: BP_Rewarded_Android / BP_Interstitial_Android / BP_Banner_Android.
 *
 * La inicializacion esta condicionada al Consent Mode (UMP): MainActivity solo
 * llama a init() cuando ConsentManager lo autoriza. Nunca se muestran anuncios
 * antes de eso.
 */
public final class UnityAdsManager {

    private static final String TAG = "CodeHubUnityAds";

    private static final String GAME_ID = "800397583";

    private static final String PLACEMENT_REWARDED = "BP_Rewarded_Android";
    private static final String PLACEMENT_INTERSTITIAL = "BP_Interstitial_Android";
    private static final String PLACEMENT_BANNER = "BP_Banner_Android";

    /** false = anuncios reales; true = servidor de pruebas de Unity. */
    private static final boolean TEST_MODE = false;

    /** Prefs donde se guarda el modo test conmutable en runtime (ch_ads/test_mode). */
    private static final String PREFS = "ch_ads";
    private static final String KEY_TEST = "test_mode";

    private static boolean initialized;

    /** Modo test efectivo (TEST_MODE de compilación, sobre-escribible por prefs). */
    private static boolean testMode = TEST_MODE;

    // ── Diagnóstico (lo lee el WebView vía CodeHubBridge.adsStatus) ──
    private static String lastInitError;
    private static String lastRewardedError;
    private static String lastInterstitialError;
    private static String lastBannerError;

    /** Handler principal para reintentos de carga (fill intermitente). */
    private static final android.os.Handler MAIN = new android.os.Handler(android.os.Looper.getMainLooper());
    private static final long RETRY_MS = 30_000L;
    private static final int MAX_RETRIES = 8;
    private static int rewardedRetries;
    private static int interstitialRetries;
    private static int bannerRetries;

    private static ViewGroup bannerSlot;
    private static BannerView bannerView;

    private static RewardedCallback rewardedCallback;
    private static boolean rewardedReady;

    private static InterstitialCallback interstitialCallback;
    private static boolean interstitialReady;

    /** Contrato JS `cb(earned, amount, type)` de CodeHubBridge. */
    interface RewardedCallback {
        void onResult(boolean earned, int amount, String type);
    }

    /** Contrato JS `cb(shown)` de CodeHubBridge. */
    interface InterstitialCallback {
        void onResult(boolean shown);
    }

    private UnityAdsManager() {}

    // ------------------------------------------------------------------
    //  INIT
    // ------------------------------------------------------------------
    public static void init(final Activity activity, final ViewGroup reservedBannerSlot) {
        if (initialized || activity == null) return;
        bannerSlot = reservedBannerSlot;
        // El modo test puede sobre-escribirse en runtime (prefs ch_ads/test_mode).
        // UnityAds.initialize corre una sola vez por proceso, asi que cambiarlo
        // exige reiniciar la app.
        try {
            testMode = activity.getSharedPreferences(PREFS, Activity.MODE_PRIVATE)
                    .getBoolean(KEY_TEST, TEST_MODE);
        } catch (Throwable t) { testMode = TEST_MODE; }
        lastInitError = null;
        try {
            UnityAds.setDebugMode(testMode);
            Log.i(TAG, "init GAME_ID=" + GAME_ID + " testMode=" + testMode);
            UnityAds.initialize(activity, GAME_ID, testMode, new IUnityAdsInitializationListener() {
                @Override
                public void onInitializationComplete() {
                    initialized = true;
                    lastInitError = null;
                    Log.i(TAG, "Unity Ads inicializado (" + GAME_ID + ")");
                    try { setupBanner(activity); } catch (Throwable t) { Log.w(TAG, "banner setup", t); }
                    loadRewarded();
                    loadInterstitial();
                }

                @Override
                public void onInitializationFailed(UnityAds.UnityAdsInitializationError error, String message) {
                    lastInitError = "[" + error + "] " + message;
                    Log.w(TAG, "init fallo: " + lastInitError);
                }
            });
        } catch (Throwable t) {
            lastInitError = String.valueOf(t);
            Log.w(TAG, "error en init", t);
        }
    }

    public static boolean isInitialized() {
        return initialized;
    }

    // ------------------------------------------------------------------
    //  REWARDED
    // ------------------------------------------------------------------
    public static void loadRewarded() {
        if (!initialized) return;
        try {
            UnityAds.load(PLACEMENT_REWARDED, new IUnityAdsLoadListener() {
                @Override
                public void onUnityAdsAdLoaded(String placementId) {
                    rewardedReady = true;
                    rewardedRetries = 0;
                    lastRewardedError = null;
                }

                @Override
                public void onUnityAdsFailedToLoad(String placementId, UnityAds.UnityAdsLoadError error, String message) {
                    rewardedReady = false;
                    lastRewardedError = "load: [" + error + "] " + message;
                    Log.w(TAG, "rewarded no cargo: " + lastRewardedError);
                    if (initialized && rewardedRetries++ < MAX_RETRIES) {
                        MAIN.postDelayed(UnityAdsManager::loadRewarded, RETRY_MS);
                    }
                }
            });
        } catch (Throwable t) { Log.w(TAG, "loadRewarded", t); }
    }

    public static boolean isRewardedReady() {
        return initialized && rewardedReady;
    }

    public static void showRewarded(Activity activity, RewardedCallback cb) {
        if (!initialized || activity == null || !rewardedReady) {
            if (cb != null) cb.onResult(false, 0, "");
            return;
        }
        rewardedReady = false;
        synchronized (UnityAdsManager.class) { rewardedCallback = cb; }
        try {
            UnityAds.show(activity, PLACEMENT_REWARDED, new UnityAdsShowOptions(), new IUnityAdsShowListener() {
                @Override
                public void onUnityAdsShowFailure(String placementId, UnityAds.UnityAdsShowError error, String message) {
                    lastRewardedError = "show: [" + error + "] " + message;
                    Log.w(TAG, "rewarded show fallo: " + lastRewardedError);
                    deliverRewarded(false);
                    loadRewarded();
                }

                @Override
                public void onUnityAdsShowStart(String placementId) {}

                @Override
                public void onUnityAdsShowClick(String placementId) {}

                @Override
                public void onUnityAdsShowComplete(String placementId, UnityAds.UnityAdsShowCompletionState state) {
                    boolean completed = state == UnityAds.UnityAdsShowCompletionState.COMPLETED;
                    deliverRewarded(completed);
                    loadRewarded();
                }
            });
        } catch (Throwable t) {
            Log.w(TAG, "showRewarded", t);
            deliverRewarded(false);
        }
    }

    private static void deliverRewarded(boolean earned) {
        RewardedCallback cb;
        synchronized (UnityAdsManager.class) {
            cb = rewardedCallback;
            rewardedCallback = null;
        }
        if (cb != null) cb.onResult(earned, 0, "");
    }

    // ------------------------------------------------------------------
    //  INTERSTITIAL
    // ------------------------------------------------------------------
    public static void loadInterstitial() {
        if (!initialized) return;
        try {
            UnityAds.load(PLACEMENT_INTERSTITIAL, new IUnityAdsLoadListener() {
                @Override
                public void onUnityAdsAdLoaded(String placementId) {
                    interstitialReady = true;
                    interstitialRetries = 0;
                    lastInterstitialError = null;
                }

                @Override
                public void onUnityAdsFailedToLoad(String placementId, UnityAds.UnityAdsLoadError error, String message) {
                    interstitialReady = false;
                    lastInterstitialError = "load: [" + error + "] " + message;
                    Log.w(TAG, "interstitial no cargo: " + lastInterstitialError);
                    if (initialized && interstitialRetries++ < MAX_RETRIES) {
                        MAIN.postDelayed(UnityAdsManager::loadInterstitial, RETRY_MS);
                    }
                }
            });
        } catch (Throwable t) { Log.w(TAG, "loadInterstitial", t); }
    }

    public static boolean isInterstitialReady() {
        return initialized && interstitialReady;
    }

    public static void showInterstitial(Activity activity, InterstitialCallback cb) {
        if (!initialized || activity == null || !interstitialReady) {
            if (cb != null) cb.onResult(false);
            return;
        }
        interstitialReady = false;
        synchronized (UnityAdsManager.class) { interstitialCallback = cb; }
        try {
            UnityAds.show(activity, PLACEMENT_INTERSTITIAL, new UnityAdsShowOptions(), new IUnityAdsShowListener() {
                @Override
                public void onUnityAdsShowFailure(String placementId, UnityAds.UnityAdsShowError error, String message) {
                    lastInterstitialError = "show: [" + error + "] " + message;
                    Log.w(TAG, "interstitial show fallo: " + lastInterstitialError);
                    deliverInterstitial(false);
                    loadInterstitial();
                }

                @Override
                public void onUnityAdsShowStart(String placementId) {}

                @Override
                public void onUnityAdsShowClick(String placementId) {}

                @Override
                public void onUnityAdsShowComplete(String placementId, UnityAds.UnityAdsShowCompletionState state) {
                    deliverInterstitial(true);
                    loadInterstitial();
                }
            });
        } catch (Throwable t) {
            Log.w(TAG, "showInterstitial", t);
            deliverInterstitial(false);
        }
    }

    private static void deliverInterstitial(boolean shown) {
        InterstitialCallback cb;
        synchronized (UnityAdsManager.class) {
            cb = interstitialCallback;
            interstitialCallback = null;
        }
        if (cb != null) cb.onResult(shown);
    }

    // ------------------------------------------------------------------
    //  BANNER (franja reservada, nunca superpuesta)
    // ------------------------------------------------------------------
    private static void setupBanner(Activity activity) {
        if (bannerSlot == null) return;
        try {
            BannerView view = new BannerView(activity, PLACEMENT_BANNER, new UnityBannerSize(320, 50));
            view.setListener(new BannerView.IListener() {
                @Override
                public void onBannerLoaded(BannerView bannerAdView) {
                    bannerRetries = 0;
                    lastBannerError = null;
                    Log.i(TAG, "banner cargado");
                }

                @Override
                public void onBannerShown(BannerView bannerAdView) {
                    Log.i(TAG, "banner mostrado");
                }

                @Override
                public void onBannerFailedToLoad(BannerView bannerAdView, BannerErrorInfo errorInfo) {
                    lastBannerError = errorInfo != null
                            ? (errorInfo.errorCode + " " + errorInfo.errorMessage)
                            : "desconocido";
                    Log.w(TAG, "banner no cargo: " + lastBannerError);
                    if (initialized && bannerRetries++ < MAX_RETRIES) {
                        MAIN.postDelayed(() -> { if (bannerView != null) bannerView.load(); }, RETRY_MS);
                    }
                }

                @Override
                public void onBannerClick(BannerView bannerAdView) {
                    Log.i(TAG, "banner clickeado");
                }

                @Override
                public void onBannerLeftApplication(BannerView bannerAdView) {
                    Log.i(TAG, "banner abrio app externa");
                }
            });
            bannerSlot.removeAllViews();
            bannerSlot.addView(view, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            bannerSlot.setVisibility(View.VISIBLE);
            bannerView = view;
            view.load();
        } catch (Throwable t) {
            Log.w(TAG, "setup banner", t);
        }
    }

    public static void showBanner() {
        if (bannerView != null) bannerView.setVisibility(View.VISIBLE);
        if (bannerSlot != null) bannerSlot.setVisibility(View.VISIBLE);
    }

    public static void hideBanner() {
        if (bannerView != null) bannerView.setVisibility(View.GONE);
        if (bannerSlot != null) bannerSlot.setVisibility(View.GONE);
    }

    // ------------------------------------------------------------------
    //  DIAGNÓSTICO (lo lee el WebView vía CodeHubBridge.adsStatus)
    // ------------------------------------------------------------------
    /** Activa/desactiva el modo test para la próxima inicialización (requiere reinicio). */
    public static void setTestModePref(Activity activity, boolean enabled) {
        testMode = enabled;
        try {
            activity.getSharedPreferences(PREFS, Activity.MODE_PRIVATE)
                    .edit().putBoolean(KEY_TEST, enabled).apply();
        } catch (Throwable t) { Log.w(TAG, "setTestModePref", t); }
    }

    /** Escapa una cadena para incrustarla tal cual en JSON (null -> null). */
    private static String q(String s) {
        if (s == null) return "null";
        StringBuilder b = new StringBuilder(s.length() + 2).append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':  b.append("\\\""); break;
                case '\\': b.append("\\\\"); break;
                case '\n': b.append("\\n");  break;
                case '\r': b.append("\\r");  break;
                case '\t': b.append("\\t");  break;
                default:
                    if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
                    else b.append(c);
            }
        }
        return b.append('"').toString();
    }

    /**
     * Estado completo del SDK como JSON, para diagnosticar "no se ven anuncios"
     * sin logcat: init, modo test, si hay anuncio cargado y el último error de
     * cada formato (incluye el GAME_ID y los placements en uso).
     */
    public static String statusJson() {
        return "{"
                + "\"gameId\":" + q(GAME_ID)
                + ",\"initialized\":" + initialized
                + ",\"testMode\":" + testMode
                + ",\"rewardedReady\":" + rewardedReady
                + ",\"interstitialReady\":" + interstitialReady
                + ",\"bannerAttached\":" + (bannerView != null)
                + ",\"retries\":{\"rewarded\":" + rewardedRetries
                + ",\"interstitial\":" + interstitialRetries
                + ",\"banner\":" + bannerRetries + "}"
                + ",\"placements\":{\"rewarded\":" + q(PLACEMENT_REWARDED)
                + ",\"interstitial\":" + q(PLACEMENT_INTERSTITIAL)
                + ",\"banner\":" + q(PLACEMENT_BANNER) + "}"
                + ",\"lastErrors\":{\"init\":" + q(lastInitError)
                + ",\"rewarded\":" + q(lastRewardedError)
                + ",\"interstitial\":" + q(lastInterstitialError)
                + ",\"banner\":" + q(lastBannerError) + "}"
                + "}";
    }
}
