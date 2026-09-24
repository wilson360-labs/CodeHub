# Cumplimiento publicitario CodeHub — Reglas de Google (web + app)

Código que decide cómo codificar la publicidad en CodeHub. **Siempre se hace
"a la manera de Google"**, con sus políticas oficiales como referencia, nunca
con reglas propias. Cambiar algo de esto sin revisar primero este doc es un
riesgo de suspensión de AdSense/AdMob.

Referencias oficiales (revisar periódicamente, cambian):
- AdSense Program policies → https://www.google.com/adsense/policies
- Ad placement policies → https://support.google.com/adsense/answer/1346295
- AdMob & AdSense Program Policies → https://support.google.com/admob/answer/48182
- WebView API for Ads (Android) → https://developers.google.com/admob/android/browser

---

## 1) WEB (AdSense)

### Qué ADMITIMOS
- **Auto Ads**, servido por el propio algoritmo de Google (él decide posición y
  formato; así no existe riesgo de "placement engañoso" hecho por nosotros).
- Carga vía **snippet único gtag**: `gtag('config', 'ca-pub-3780093322926832')`
  (NO el loader clásico `adsbygoogle.js?client=…`).
- **Consent Mode v2** (GDPR/CCPA): default en `denied` con `wait_for_update:500`
  ANTES de cargar gtag; el banner (`js/consent-banner.js`, key `ch_consent`)
  hace `gtag('consent','update', …)` al aceptar/rechazar. Si rechaza → anuncios
  NO personalizados (permitido) y analytics denegado.
- Disclosure de publicidad en `privacy.html` (ID de editor, cookies de
  DoubleClick, derechos GDPR) y el banner menciona "anuncios de Google AdSense".

### Qué PROHIBIMOS (políticas de colocación)
- **Sin adsbygoogle.js clásico** (`adsbygoogle.js?client=…`): queda fuera del repo.
- **Sin flechas, símbolos, animaciones ni imágenes que apunten/llamen** la atención
  hacia los anuncios ("unnatural attention to ads").
- **Sin encabezados engañosos** sobre el área de anuncios ("Recursos", "Enlaces
  útiles"); si algún día se ponen unidades manuales, solo etiquetar "Anuncios"
  o "Enlaces patrocinados".
- **Sin clics/impresiones propias** ni pedir que otros clickeen (validation day).
- **Sin más anuncios que contenido** por página.
- **Sin colocar ads en pop-ups, pop-unders, emails ni software** (ver §3).

### Guard anti-WebView (obligatorio, NO quitar)
Cada página con publicidad arranca así y **debe mantenerse**:
```html
if (typeof window.CodeHubNative !== 'undefined' || window.__apkNative) return;
```
Evita que el WebView del APK sirva AdSense (Google no permite AdSense dentro de
apps salvo integraciones aprobadas; la app monetiza con AdMob, ver §2).

## 2) APP (Appodeal mediation → AdMob + AppLovin + Unity Ads + Vungle/Liftoff + BidMachine)

### Qué ADMITIMOS
- **Mediación con Appodeal SDK 4.4.0 (Mediation Only)** dentro de la app:
  banner (franja inferior), rewarded e interstitial. `AppodealManager.java`
  inicializa `Appodeal.initialize` (APP_KEY del panel de Appodeal); los
  formatos y cascada se configuran en el dashboard de Appodeal, y AdMob entra
  como red vía el adaptador `admob` (`play-services-ads` transitivo — exige
  compileSdk 36). El meta-data del AdMob App ID
  (`com.google.android.gms.ads.APPLICATION_ID`) se conserva en el manifest.
- **UMP gate**: `ConsentManager.java` (User Messaging Platform) resuelve el
  consentimiento ANTES de inicializar Appodeal (`MainActivity.initAdMob`). El
  adaptador `iab` lee los strings IABTCF que escribe UMP para respetar la
  decisión del usuario en el WebView.
- **`network_security_config.xml`** con base-config `cleartextTrafficPermitted`
  en `true` (requisito de Appodeal para servir la red de mediación; decisión
  deliberada de monetización, documentada en el propio XML).
- **app-ads.txt** en la raíz del sitio (verificación de redes, obligatorio desde
  Ene 2025): añadir también el seller de **Appodeal**.
- WebView configurado según guía de Google: `setAcceptThirdPartyCookies(true)`,
  JS y DOM storage habilitados.

### Qué PROHIBIMOS
- **Cargar AdSense/Ads web dentro del WebView** (viola AdSense y AdMob). Los
  `ins`/gtag de AdSense solo se sirven en navegador real; en la app, si algún día
  se quiere monetizar el contenido del WebView, hay que hacerlo vía
  **WebView API for Ads** (registrar el WebView con el GMA SDK), nunca inyectando
  AdSense.
- **No reintroducir** `MobileAds.initialize` directo ni `play-services-ads`
  declarado a mano: lo aporta el adaptador `admob` de Appodeal.
- **No añadir adapters no aprobados** (Meta, Mintegral, Amazon, Bidon, etc.)
  sin revisar antes este doc y las políticas de Google.
- **Interstitial spam**: no mostrar intersticiales que bloqueen la UI sin interacción.
- Clics propios en la app (usar **test ads** durante desarrollo, no ads reales).

### Banner según guía de AdMob (implementado)
`MainActivity` usa estructura vertical: **WebView arriba** (`mainFrame`, weight 1) y
**franja inferior reservada** (`bannerSlot`) para el banner. `AppodealManager`
coloca un `BannerView` (smart banner) dentro de esa franja debajo del contenido;
`show/hide` (desde JS) ocultan todo el slot → **el banner nunca se superpone a la
UI** (patrón "recommended" de AdMob, no el "discouraged" que cubre contenido).

## 3) Cruzado (aplica en ambos)
- Membresía y conflictos: no distributions de ads vía software (toolbars/ext) ni
  "software applications" fuera de web/WebView aprobado.
- No framing de contenido de terceros para monetizar sin permiso.
- Mantener `ads.txt` (web) y `app-ads.txt` (app) subidos y servidos por el host real.