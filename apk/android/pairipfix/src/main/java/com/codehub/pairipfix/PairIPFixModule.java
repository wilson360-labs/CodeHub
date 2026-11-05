package com.codehub.pairipfix;

import java.util.LinkedHashMap;
import java.util.Map;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Módulo LSPosed: deja instalar como APK apps protegidas con PairIP.
 *
 * PairIP (Google) comprueba que la app se instaló desde Google Play, valida
 * la firma del APK y detecta depuradores/Frida. Se apoya en `libpairpcore.so`
 * y en clases `com.pairip.*`.
 *
 * Este módulo no parchea el APK: intercepta en runtime las comprobaciones de
 * PairIP para que la app no exija venir de la Play Store.
 *
 * La idea (qué clases hay que hookear) es la del módulo público de
 * ahmedmani/pairipfix; la implementación de aquí es propia.
 */
public class PairIPFixModule implements IXposedHookLoadPackage {

    /** Solo existe en apps con PairIP: sirve de detector. */
    private static final String MARKER = "com.pairip.VMRunner";

    private static final String CLIENT_V2 = "com.pairip.licensecheck.LicenseClient";
    private static final String STATE_V2 = "com.pairip.licensecheck.LicenseClient$LicenseCheckState";

    /** Clase -> métodos a neutralizar. */
    private static final Map<String, String[]> TARGETS = new LinkedHashMap<>();

    static {
        TARGETS.put("com.pairip.SignatureCheck",
                new String[]{"verifyIntegrity", "verifySignatureMatches"});
        TARGETS.put(CLIENT_V2,
                new String[]{"initializeLicenseCheck", "performLocalInstallerCheck"});
        TARGETS.put("com.pairip.licensecheck3.LicenseClientV3",
                new String[]{"initializeLicenseCheck", "processResponse"});
        TARGETS.put("com.pairip.licensecheck.LicenseActivity",
                new String[]{"closeApp", "exitApp", "showErrorDialog", "showPaywallAndCloseApp", "logAndShowErrorDialog"});
        TARGETS.put("com.pairip.licensecheck.LicenseResponseHelper",
                new String[]{"validateResponse", "getRepeatedCheckMetadata", "verifySignature"});
        TARGETS.put("com.pairip.licensecheck.ResponseValidator",
                new String[]{"validateResponse", "verifySignature"});
        TARGETS.put("com.pairip.licensecheck3.ResponseValidator",
                new String[]{"validateResponse", "verifySignature"});
    }

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        ClassLoader loader = lpparam.classLoader;
        if (XposedHelpers.findClassIfExists(MARKER, loader) == null) {
            // La app no usa PairIP: no se toca nada.
            return;
        }

        int applied = 0;
        for (Map.Entry<String, String[]> target : TARGETS.entrySet()) {
            for (String method : target.getValue()) {
                if (PairIPHooks.neutralize(loader, target.getKey(), method)) applied++;
            }
        }
        if (markLicenseAsFullCheck(loader)) applied++;

        XposedBridge.log("[PairIPFix] " + lpparam.packageName + ": " + applied + " hooks aplicados");
    }

    /**
     * Deja el estado de licencia en FULL_CHECK_OK para que la app no espere
     * la respuesta del servidor de PairIP.
     */
    private boolean markLicenseAsFullCheck(ClassLoader loader) {
        Class<?> state = XposedHelpers.findClassIfExists(STATE_V2, loader);
        if (state == null) return false;
        try {
            Object fullCheckOk = XposedHelpers.getStaticObjectField(state, "FULL_CHECK_OK");
            if (fullCheckOk == null) return false;
            return PairIPHooks.setStaticField(loader, CLIENT_V2, "licenseCheckState", fullCheckOk);
        } catch (Throwable error) {
            XposedBridge.log("[PairIPFix] licenseCheckState: " + error);
            return false;
        }
    }
}
