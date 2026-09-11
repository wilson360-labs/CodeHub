package com.codehub.app;

import android.app.Activity;
import android.util.Log;

import com.google.android.ump.ConsentInformation;
import com.google.android.ump.ConsentRequestParameters;
import com.google.android.ump.UserMessagingPlatform;

/**
 * UMP — Gestión de consentimiento GDPR/CCPA (User Messaging Platform) de Google.
 *
 * Debe iniciarse ANTES de pedir cualquier anuncio de AdMob. En regiones donde la
 * ley lo exige (EEE/UK/Canadá) muestra el formulario nativo de Google; fuera de
 * esas regiones canRequestAds() queda en true sin interrumpir nada.
 *
 * Uso (MainActivity.initAdMob):
 *   ConsentManager.init(activity, () -> { …cargar banner/rewarded/interstitial… });
 */
final class ConsentManager {

    private static final String TAG = "CodeHubConsent";

    private static volatile boolean canRequestAds = false;

    private ConsentManager() {}

    /** true cuando ya se puede pedir anuncios de AdMob sin violar UMP. */
    static boolean canRequestAds() {
        return canRequestAds;
    }

    /**
     * Consulta el estado de consentimiento y, si hace falta, muestra el
     * formulario nativo. onResolved se invoca (siempre) al terminar, con
     * canRequestAds() ya actualizado.
     */
    static void init(final Activity activity, final Runnable onResolved) {
        try {
            ConsentRequestParameters params = new ConsentRequestParameters.Builder().build();
            final ConsentInformation info = ConsentInformation.getInstance(activity);
            info.requestConsentInfoUpdate(activity, params,
                    () -> {
                        try {
                            if (info.isConsentFormAvailable()) {
                                UserMessagingPlatform.loadAndShowConsentFormIfRequired(
                                        activity,
                                        form -> {
                                            canRequestAds = info.canRequestAds();
                                            if (onResolved != null) onResolved.run();
                                        },
                                        error -> {
                                            Log.w(TAG, "form error code=" + error.getErrorCode());
                                            canRequestAds = info.canRequestAds();
                                            if (onResolved != null) onResolved.run();
                                        });
                            } else {
                                canRequestAds = info.canRequestAds();
                                if (onResolved != null) onResolved.run();
                            }
                        } catch (Throwable t) {
                            canRequestAds = info.canRequestAds();
                            if (onResolved != null) onResolved.run();
                        }
                    },
                    error -> {
                        // Sin red o región no auditada: no bloquear la app.
                        Log.w(TAG, "consent info update error code=" + error.getErrorCode() + " — se asume consent");
                        canRequestAds = true;
                        if (onResolved != null) onResolved.run();
                    });
        } catch (Throwable t) {
            Log.w(TAG, "UMP init falló — se asume consent", t);
            canRequestAds = true;
            if (onResolved != null) onResolved.run();
        }
    }
}