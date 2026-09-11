package com.codehub.app;

import android.app.Activity;
import android.util.Log;

import androidx.annotation.NonNull;

import com.google.android.gms.ads.AdError;
import com.google.android.gms.ads.AdRequest;
import com.google.android.gms.ads.FullScreenContentCallback;
import com.google.android.gms.ads.LoadAdError;
import com.google.android.gms.ads.interstitial.InterstitialAd;
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback;

/**
 * Anuncio intersticial (pantalla completa) de AdMob.
 *
 * Control desde JS (CodeHubBridge):
 *   CodeHubNative.loadInterstitialAd()
 *   CodeHubNative.isInterstitialAdReady()
 *   CodeHubNative.showInterstitialAd('miCallback') → miCallback(shown)
 *
 * ⚠️ REQUIERE una unidad de INTERSTICIAL creada en AdMob (no reutilizar la
 * de rewarded ni banner). Mientras AD_UNIT_ID_INTERSTITIAL tenga el
 * placeholder (0000000000), no se carga ni se muestra nada.
 */
final class InterstitialAdManager {

    private static final String TAG = "CodeHubInterstitial";

    private static final String AD_UNIT_ID_INTERSTITIAL = "ca-app-pub-3780093322926832/6519180963";

    private static InterstitialAd interstitialAd;
    private static boolean loading = false;

    private InterstitialAdManager() {}

    static boolean hasUnitId() {
        return !AD_UNIT_ID_INTERSTITIAL.contains("0000000000");
    }

    static void load(final Activity activity) {
        if (interstitialAd != null || loading || !hasUnitId() || !ConsentManager.canRequestAds()) return;
        loading = true;
        InterstitialAd.load(activity, AD_UNIT_ID_INTERSTITIAL, new AdRequest.Builder().build(),
                new InterstitialAdLoadCallback() {
                    @Override
                    public void onAdLoaded(@NonNull InterstitialAd ad) {
                        interstitialAd = ad;
                        loading = false;
                    }

                    @Override
                    public void onAdFailedToLoad(@NonNull LoadAdError e) {
                        Log.w(TAG, "load failed: " + e.getMessage());
                        loading = false;
                    }
                });
    }

    static boolean isReady() {
        return interstitialAd != null;
    }

    interface ResultCallback {
        void onResult(boolean shown);
    }

    static void show(final Activity activity, final ResultCallback callback) {
        if (interstitialAd == null) {
            if (callback != null) callback.onResult(false);
            load(activity);
            return;
        }

        final boolean[] shown = {false};
        interstitialAd.setFullScreenContentCallback(new FullScreenContentCallback() {
            @Override
            public void onAdDismissedFullScreenContent() {
                interstitialAd = null;
                shown[0] = true;
                if (callback != null) callback.onResult(true);
                load(activity);
            }

            @Override
            public void onAdFailedToShowFullScreenContent(@NonNull AdError adError) {
                interstitialAd = null;
                if (callback != null) callback.onResult(false);
                load(activity);
            }
        });

        interstitialAd.show(activity);
    }
}