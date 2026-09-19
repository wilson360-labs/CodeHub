package com.codehub.app;

import android.app.Activity;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import com.google.android.gms.ads.AdRequest;
import com.google.android.gms.ads.AdSize;
import com.google.android.gms.ads.AdView;

/**
 * Banner nativo de AdMob, en una franja PROPIA debajo de la WebView
 * (recomendación de AdMob: el banner nunca se superpone al contenido).
 * Se coloca dentro del contenedor inferior (bannerSlot) de MainActivity.
 *
 * Control desde JS (CodeHubBridge):
 *   CodeHubNative.showNativeBanner() / hideNativeBanner()
 *
 * ⚠️ REQUIERE una unidad de BANNER creada en AdMob. Un formato NO puede
 * usar la unidad de otro (rewarded/intersticial). Mientras AD_UNIT_ID_BANNER
 * tenga el placeholder (0000000000), el banner no se muestra.
 */
final class BannerAdManager {

    private static final String TAG = "CodeHubBanner";

    private static final String AD_UNIT_ID_BANNER = "ca-app-pub-3780093322926832/1968391001";

    private static AdView adView;
    private static View slot;

    private BannerAdManager() {}

    static boolean hasUnitId() {
        return !AD_UNIT_ID_BANNER.contains("0000000000");
    }

    /** Crea el banner (una sola vez) dentro de la franja inferior reservada. */
    static void setup(final Activity activity, final View bottomSlot) {
        if (adView != null || bottomSlot == null || !hasUnitId() || !ConsentManager.canRequestAds()) return;
        try {
            slot = bottomSlot;
            adView = new AdView(activity);
            adView.setAdUnitId(AD_UNIT_ID_BANNER);
            adView.setAdSize(AdSize.SMART_BANNER);
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.gravity = android.view.Gravity.CENTER_HORIZONTAL;
            ((FrameLayout) bottomSlot).addView(adView, lp);
            adView.loadAd(new AdRequest.Builder().build());
            bottomSlot.setVisibility(View.VISIBLE);
            Log.i(TAG, "banner configurado en APP_ID=" + adView.getAdUnitId());
        } catch (Throwable t) {
            Log.w(TAG, "banner setup error", t);
        }
    }

    static void show() {
        if (adView != null) adView.setVisibility(View.VISIBLE);
        if (slot != null) slot.setVisibility(View.VISIBLE);
    }

    static void hide() {
        if (adView != null) adView.setVisibility(View.GONE);
        if (slot != null) slot.setVisibility(View.GONE);
    }
}