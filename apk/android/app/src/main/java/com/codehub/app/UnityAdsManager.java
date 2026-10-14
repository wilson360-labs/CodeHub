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
import com.unity3d.services.banners.BannerErrorCode;
import com.unity3d.services.banners.UnityBannerSize;

/**
 * Unity Ads (SDK directo) â€” banners, interstitials y rewarded.
 *
 * Reemplaza a AppodealManager como motor de anuncios conservando EL MISMO
 * contrato JS de CodeHubBridge; por eso CodeHubBridge y js/rewarded-ad.js
 * no cambian. Unity Ads no usa "APP_KEY" de mediaciÃ³n: se inicializa con
 * el Game ID y se carga/muestra con un Placement ID por formato.
 *
 *   Â· Game ID      800397583
 *   Â· Rewarded     BP_Rewarded_Android
 *   Â· Interstitial BP_Interstitial_Android
 *   Â· Banner       BP_Banner_Android
 *
 * El consentimiento sigue siendo el de UMP (ConsentManager): solo se llama
 * a init() cuando canRequestAds() es true. Nada de anuncios antes de eso.
 */
public final class UnityAdsManager {

    private static final String TAG = "CodeHubUnityAds";

    // Dashboard Unity â†’ proyecto "CodeHub".
    private static final String GAME_ID = "800397583";

    private static final String PLACEMENT_REWARDED = "BP_Rewarded_Android";
    private static final String PLACEMENT_INTERSTITIAL = "BP_Interstitial_Android";
    private static final String PLACEMENT_BANNER = "BP_Banner_Android";

    /** false = anuncios reales; true = servidor de pruebas de Unity. */
    private static final boolean TEST_MODE = false;

    private static boolean initialized;

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

    // â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
    //  INIT
    // â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
    public static void init(final Activity activity, final ViewGroup reservedBannerSlot) {
        if (initialized || activity == null) return;
        bannerSlot = reservedBannerSlot;
        try {
            UnityAds.initialize(activity, GAME_ID, TEST_MODE, new IUnityAdsInitializationListener() {
                @Override
                public void onInitializationComplete() {
                    initialized = true;
                    Log.i(TAG, "Unity Ads inicializado (" + GAME_ID + ")");
                    try { setupBanner(activity); } catch (Throwable t) { Log.w(TAG, "banner setup", t); }
                    loadRewarded();
                    loadInterstitial();
                }

                @Override
                public void onInitializationFailed(UnityAds.UnityAdsInitializationError error, String message) {
                    Log.w(TAG, "init fallÃ³: [" + error + "] " + message);
                }
            });
        } catch (Throwable t) {
            Log.w(TAG, "error en init", t);
        }
    }

    public static boolean isInitialized() {
        return initialized;
    }

    // â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
    //  REWARDED
    // â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
    public static void loadRewarded() {
        if (!initialized) return;
        try {
            UnityAds.load(PLACEMENT_REWARDED, new IUnityAdsLoadListener() {
                @Override
                public void onUnityAdsAdLoaded(String placementId) {
                    rewardedReady = true;
                }

                @Override
                public void onUnityAdsFailedToLoad(String placementId, UnityAds.UnityAdsLoadError error, String message) {
                    rewardedReady = false;
                    Log.w(TAG, "rewarded no cargÃ³: [" + error + "] " + message);
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
                    Log.w(TAG, "rewarded show fallÃ³: [" + error + "] " + message);
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

    // â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
    //  INTERSTITIAL
    // â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
    public static void loadInterstitial() {
        if (!initialized) return;
        try {
            UnityAds.load(PLACEMENT_INTERSTITIAL, new IUnityAdsLoadListener() {
                @Override
                public void onUnityAdsAdLoaded(String placementId) {
                    interstitialReady = true;
                }

                @Override
                public void onUnityAdsFailedToLoad(String placementId, UnityAds.UnityAdsLoadError error, String message) {
                    interstitialReady = false;
                    Log.w(TAG, "interstitial no cargÃ³: [" + error + "] " + message);
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
                    Log.w(TAG, "interstitial show fallÃ³: [" + error + "] " + message);
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

    // â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
    //  BANNER (franja reservada, nunca superpuesta)
    // â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
    private static void setupBanner(Activity activity) {
        if (bannerSlot == null) return;
        try {
            BannerView view = new BannerView(activity, PLACEMENT_BANNER, new UnityBannerSize(320, 50));
            view.setListener(new BannerView.IListener() {
                @Override
                public void onBannerLoaded(BannerView bannerAdView) {
                    Log.i(TAG, "banner cargado");
                }

                @Override
                public void onBannerFailedToLoad(BannerView bannerAdView, BannerErrorInfo errorInfo) {
                    Log.w(TAG, "banner no cargÃ³: " + errorInfo.getErrorCode() + " " + errorInfo.getErrorMessage());
                }

                @Override
                public void onBannerClick(BannerView bannerAdView) {
                    Log.i(TAG, "banner clickeado");
                }

                @Override
                public void onBannerLeftApplication(BannerView bannerAdView) {
                    Log.i(TAG, "banner abriÃ³ app externa");
                }
            });
            ((ViewGroup) bannerSlot).removeAllViews();
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
}
