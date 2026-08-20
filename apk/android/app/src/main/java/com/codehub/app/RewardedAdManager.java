package com.codehub.app;

import android.app.Activity;
import android.util.Log;

import androidx.annotation.NonNull;

import com.google.android.gms.ads.AdError;
import com.google.android.gms.ads.AdRequest;
import com.google.android.gms.ads.FullScreenContentCallback;
import com.google.android.gms.ads.LoadAdError;
import com.google.android.gms.ads.rewarded.RewardedAd;
import com.google.android.gms.ads.rewarded.RewardedAdLoadCallback;

/**
 * Maneja la carga y presentación del anuncio recompensado de CodeHub.
 * Unidad: ca-app-pub-3780093322926832/4285173985
 *
 * Uso desde JS (ver CodeHubBridge):
 *   CodeHubNative.loadRewardedAd()          → precarga (opcional, se auto-precarga)
 *   CodeHubNative.isRewardedAdReady()       → boolean
 *   CodeHubNative.showRewardedAd('miCallback') → muestra el anuncio
 *
 * El callback JS se invoca como: miCallback(earned, amount, type)
 *   earned: boolean — true solo si el usuario vio el anuncio completo
 */
final class RewardedAdManager {

    private static final String AD_UNIT_ID = "ca-app-pub-3780093322926832/4285173985";
    private static RewardedAd rewardedAd;
    private static boolean loading = false;

    private RewardedAdManager() {}

    static void load(final Activity activity) {
        if (rewardedAd != null || loading) return;
        loading = true;
        AdRequest request = new AdRequest.Builder().build();
        RewardedAd.load(activity, AD_UNIT_ID, request, new RewardedAdLoadCallback() {
            @Override
            public void onAdLoaded(@NonNull RewardedAd ad) {
                rewardedAd = ad;
                loading = false;
            }

            @Override
            public void onAdFailedToLoad(@NonNull LoadAdError adError) {
                Log.w("CodeHub", "Rewarded ad failed to load: " + adError.getMessage());
                rewardedAd = null;
                loading = false;
            }
        });
    }

    static boolean isReady() {
        return rewardedAd != null;
    }

    interface ResultCallback {
        void onResult(boolean earned, int amount, String type);
    }

    static void show(final Activity activity, final ResultCallback callback) {
        if (rewardedAd == null) {
            callback.onResult(false, 0, "");
            // Intentar precargar para la próxima vez.
            load(activity);
            return;
        }

        final boolean[] earned = {false};
        final int[] amount = {0};
        final String[] type = {""};

        rewardedAd.setFullScreenContentCallback(new FullScreenContentCallback() {
            @Override
            public void onAdDismissedFullScreenContent() {
                rewardedAd = null;
                callback.onResult(earned[0], amount[0], type[0]);
                // Precargar el siguiente para que esté listo de inmediato.
                load(activity);
            }

            @Override
            public void onAdFailedToShowFullScreenContent(@NonNull AdError adError) {
                rewardedAd = null;
                callback.onResult(false, 0, "");
                load(activity);
            }
        });

        rewardedAd.show(activity, rewardItem -> {
            earned[0] = true;
            amount[0] = rewardItem.getAmount();
            type[0] = rewardItem.getType();
        });
    }
}
