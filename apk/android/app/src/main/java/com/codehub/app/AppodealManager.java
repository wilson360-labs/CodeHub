package com.codehub.app;

import android.app.Activity;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import com.appodeal.ads.Appodeal;
import com.appodeal.ads.BannerCallbacks;
import com.appodeal.ads.BannerView;
import com.appodeal.ads.InterstitialCallbacks;
import com.appodeal.ads.RewardedVideoCallbacks;
import com.appodeal.ads.initializing.ApdInitializationCallback;
import com.appodeal.ads.initializing.ApdInitializationError;

import java.util.List;

/**
 * Mediación Appodeal 4.4.0 (Mediation Only) con la cascada del dashboard:
 * AdMob + AppLovin + Unity Ads + Liftoff/Vungle + BidMachine.
 *
 * Reemplaza las llamadas directas a AdMob (BannerAdManager,
 * RewardedAdManager e InterstitialAdManager) manteniendo el MISMO contrato
 * JS de CodeHubBridge y el gate de consentimiento UMP (ConsentManager).
 *
 *   AppodealManager.init(activity, bannerSlot)  → tras ConsentManager.canRequestAds()
 *   loadRewardedAd() / isRewardedAdReady() / showRewardedAd(cb)  ← cb(earned, amount, type)
 *   loadInterstitialAd() / isInterstitialAdReady() / showInterstitialAd(cb) ← cb(shown)
 *   showBanner() / hideBanner()                 → franja inferior (BannerView, nunca superpuesto)
 *
 * ⚠️ APPODEAL_APP_KEY es un placeholder: mientras no se pegue la APP_KEY
 * real del panel de Appodeal, hasAppKey() devuelve false y la app
 * simplemente se ejecuta SIN anuncios (igual que el guard "0000000000" de
 * los managers AdMob previos).
 */
final class AppodealManager {

    private static final String TAG = "CodeHubAppodeal";

    /** APP_KEY de https://appodeal.com → Apps → CodeHub. */
    private static final String APPODEAL_APP_KEY = "YOUR_APPODEAL_APP_KEY";

    private static final String PLACEMENT = "default";

    private static boolean initialized;

    private static View bannerSlot;
    private static BannerView bannerView;

    private static RewardedCallback rewardedCallback;
    private static boolean rewardedFinished;
    private static double rewardedAmount;
    private static String rewardedCurrency;

    private static InterstitialCallback interstitialCallback;
    private static boolean interstitialShown;

    /** Contrato JS `cb(earned, amount, type)` de CodeHubBridge. */
    interface RewardedCallback {
        void onResult(boolean earned, int amount, String type);
    }

    /** Contrato JS `cb(shown)` de CodeHubBridge. */
    interface InterstitialCallback {
        void onResult(boolean shown);
    }

    private AppodealManager() {}

    static boolean hasAppKey() {
        return APPODEAL_APP_KEY != null
            && !APPODEAL_APP_KEY.startsWith("YOUR_")
            && APPODEAL_APP_KEY.length() > 8;
    }

    /**
     * Inicializa Appodeal (una sola vez), tras el consentimiento resuelto
     * por UMP. Se llama desde MainActivity.initAdMob dentro de la callback
     * de ConsentManager, en el hilo principal.
     */
    static void init(final Activity activity, final View bottomSlot) {
        if (initialized || !hasAppKey() || !ConsentManager.canRequestAds()) return;
        try {
            initialized = true;
            bannerSlot = bottomSlot;

            int adTypes = Appodeal.BANNER | Appodeal.REWARDED_VIDEO | Appodeal.INTERSTITIAL;
            Appodeal.initialize(activity, APPODEAL_APP_KEY, adTypes, new ApdInitializationCallback() {
                @Override
                public void onInitializationFinished(List<ApdInitializationError> errors) {
                    if (errors != null && !errors.isEmpty()) {
                        Log.w(TAG, "Appodeal inicializado con " + errors.size() + " error(es)");
                    } else {
                        Log.i(TAG, "Appodeal inicializado");
                    }
                    setupBanner(activity);
                }
            });

            Appodeal.setBannerCallbacks(new BannerCallbacks() {
                @Override
                public void onBannerLoaded(int height, boolean isPrecache) {
                    Log.i(TAG, "banner cargado (height=" + height + ", precache=" + isPrecache + ")");
                }

                @Override
                public void onBannerFailedToLoad() {
                    Log.w(TAG, "banner no se pudo cargar");
                }

                @Override
                public void onBannerShown() {
                    Log.i(TAG, "banner mostrado");
                }

                @Override
                public void onBannerShowFailed() {
                    Log.w(TAG, "banner no se pudo mostrar");
                }

                @Override
                public void onBannerClicked() {
                    Log.i(TAG, "banner clickeado");
                }

                @Override
                public void onBannerExpired() {
                    Log.w(TAG, "banner expirado (se recargará solo por autocache)");
                }
            });

            Appodeal.setRewardedVideoCallbacks(new RewardedVideoCallbacks() {
                @Override
                public void onRewardedVideoLoaded(boolean isPrecache) {
                    Log.i(TAG, "rewarded cargado (precache=" + isPrecache + ")");
                }

                @Override
                public void onRewardedVideoFailedToLoad() {
                    Log.w(TAG, "rewarded no se pudo cargar");
                }

                @Override
                public void onRewardedVideoShown() {
                    Log.i(TAG, "rewarded mostrado");
                }

                @Override
                public void onRewardedVideoShowFailed() {
                    Log.w(TAG, "rewarded no se pudo mostrar");
                    deliverRewarded(false, 0, "");
                }

                @Override
                public void onRewardedVideoClicked() {
                    Log.i(TAG, "rewarded clickeado");
                }

                @Override
                public void onRewardedVideoFinished(double amount, String currency) {
                    rewardedAmount = amount;
                    rewardedCurrency = currency == null ? "" : currency;
                    rewardedFinished = true;
                }

                @Override
                public void onRewardedVideoClosed(boolean finished) {
                    if (finished && rewardedFinished) {
                        deliverRewarded(true, (int) Math.round(rewardedAmount), rewardedCurrency);
                    } else {
                        deliverRewarded(false, 0, "");
                    }
                }

                @Override
                public void onRewardedVideoExpired() {
                    Log.w(TAG, "rewarded expirado");
                }
            });

            Appodeal.setInterstitialCallbacks(new InterstitialCallbacks() {
                @Override
                public void onInterstitialLoaded(boolean isPrecache) {
                    Log.i(TAG, "interstitial cargado (precache=" + isPrecache + ")");
                }

                @Override
                public void onInterstitialFailedToLoad() {
                    Log.w(TAG, "interstitial no se pudo cargar");
                }

                @Override
                public void onInterstitialShown() {
                    interstitialShown = true;
                }

                @Override
                public void onInterstitialShowFailed() {
                    deliverInterstitial(false);
                }

                @Override
                public void onInterstitialClicked() {
                    Log.i(TAG, "interstitial clickeado");
                }

                @Override
                public void onInterstitialClosed() {
                    deliverInterstitial(interstitialShown);
                }

                @Override
                public void onInterstitialExpired() {
                    Log.w(TAG, "interstitial expirado");
                }
            });
        } catch (Throwable t) {
            initialized = false;
            Log.w(TAG, "error en init", t);
        }
    }

    /**
     * Banner en la franja inferior reservada de MainActivity (BannerView,
     * nunca superpuesto al contenido). Se crea al terminar la inicialización
     * del SDK, igual que los managers AdMob previos (visible por defecto;
     * JS puede ocultarlo con hideNativeBanner).
     */
    private static void setupBanner(final Activity activity) {
        if (bannerView != null || bannerSlot == null) return;
        try {
            bannerView = Appodeal.getBannerView(activity);
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.gravity = Gravity.CENTER_HORIZONTAL;
            ((FrameLayout) bannerSlot).addView(bannerView, lp);
            bannerSlot.setVisibility(View.VISIBLE);
            Appodeal.show(activity, Appodeal.BANNER_VIEW, PLACEMENT);
            Log.i(TAG, "banner (BannerView) configurado en la franja inferior");
        } catch (Throwable t) {
            Log.w(TAG, "setup del banner falló", t);
        }
    }

    static void showBanner() {
        if (bannerView != null) bannerView.setVisibility(View.VISIBLE);
        if (bannerSlot != null) bannerSlot.setVisibility(View.VISIBLE);
    }

    static void hideBanner() {
        if (bannerView != null) bannerView.setVisibility(View.GONE);
        if (bannerSlot != null) bannerSlot.setVisibility(View.GONE);
    }

    // ── REWARDED ───────────────────────────────────────────────────

    static void loadRewarded(Activity activity) {
        if (!initialized) return;
        try { Appodeal.cache(activity, Appodeal.REWARDED_VIDEO); } catch (Throwable t) { Log.w(TAG, "cache rewarded", t); }
    }

    static boolean isRewardedReady() {
        return initialized && Appodeal.canShow(Appodeal.REWARDED_VIDEO, PLACEMENT);
    }

    static void showRewarded(final Activity activity, RewardedCallback cb) {
        if (!initialized || cb == null) { if (cb != null) cb.onResult(false, 0, ""); return; }
        if (!Appodeal.canShow(Appodeal.REWARDED_VIDEO, PLACEMENT)) {
            cb.onResult(false, 0, "");
            return;
        }
        synchronized (AppodealManager.class) {
            rewardedCallback = cb;
            rewardedFinished = false;
            rewardedAmount = 0;
            rewardedCurrency = "";
        }
        boolean ok = Appodeal.show(activity, Appodeal.REWARDED_VIDEO, PLACEMENT);
        if (!ok) deliverRewarded(false, 0, "");
    }

    private static void deliverRewarded(final boolean earned, final int amount, final String currency) {
        RewardedCallback cb;
        synchronized (AppodealManager.class) {
            cb = rewardedCallback;
            rewardedCallback = null;
        }
        if (cb != null) cb.onResult(earned, amount, currency);
    }

    // ── INTERSTITIAL ──────────────────────────────────────────────

    static void loadInterstitial(Activity activity) {
        if (!initialized) return;
        try { Appodeal.cache(activity, Appodeal.INTERSTITIAL); } catch (Throwable t) { Log.w(TAG, "cache interstitial", t); }
    }

    static boolean isInterstitialReady() {
        return initialized && Appodeal.canShow(Appodeal.INTERSTITIAL, PLACEMENT);
    }

    static void showInterstitial(final Activity activity, InterstitialCallback cb) {
        if (!initialized || cb == null) { if (cb != null) cb.onResult(false); return; }
        if (!Appodeal.canShow(Appodeal.INTERSTITIAL, PLACEMENT)) {
            cb.onResult(false);
            return;
        }
        synchronized (AppodealManager.class) {
            interstitialCallback = cb;
            interstitialShown = false;
        }
        boolean ok = Appodeal.show(activity, Appodeal.INTERSTITIAL, PLACEMENT);
        if (!ok) deliverInterstitial(false);
    }

    private static void deliverInterstitial(final boolean shown) {
        InterstitialCallback cb;
        synchronized (AppodealManager.class) {
            cb = interstitialCallback;
            interstitialCallback = null;
        }
        if (cb != null) cb.onResult(shown);
    }
}