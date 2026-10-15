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

## 2) APP (Unity Ads directo)

### Qué ADMITIMOS
- **Unity Ads SDK directo** (`com.unity3d.ads:unity-ads`) dentro de la app:
  banner (franja inferior), interstitial y rewarded. `UnityAdsManager.java`
  inicializa `UnityAds.initialize` con el **Game ID** y sirve cada formato con
  su placement (`BP_Banner_Android`, `BP_Interstitial_Android`,
  `BP_Rewarded_Android`). Sin mediación ni APP_KEY → menos superficie de fallo.
- **UMP gate**: `ConsentManager.java` (User Messaging Platform) resuelve el
  consentimiento ANTES de inicializar Unity Ads (`MainActivity.initAdMob`).
  `UnityAdsManager.init` no se llama si `canRequestAds()` es false.
- **`network_security_config.xml`** con base-config `cleartextTrafficPermitted`
  en true (decisión de monetización documentada en el XML).
- **app-ads.txt** en la raíz del sitio (verificación de redes): añadir el seller
  de **Unity Ads** (`# Unity Ads`).
- WebView configurado según guía de Google: `setAcceptThirdPartyCookies(true)`,
  JS y DOM storage habilitados.

### Qué PROHIBIMOS
- **Cargar AdSense/Ads web dentro del WebView** (viola AdSense). El gtag de
  AdSense solo se sirve en navegador real; en la app, si algún día se quiere
  monetizar contenido del WebView, hacerlo vía **WebView API for Ads**
  (registrar el WebView con el GMA SDK), nunca inyectando AdSense.
- **Inicializar anuncios antes del consentimiento** (UMP). Nada de anuncios sin
  `canRequestAds()`.
- **No añadir redes/SDKs no revisados** (Meta, Mintegral, Amazon, Bidon, …) sin
  revisar antes este doc y las políticas de Google.
- **Interstitial spam**: no mostrar intersticiales que bloqueen la UI sin
  interacción del usuario.
- Clics propios en la app (usar **modo test** durante desarrollo, no ads reales).

### Banner (franja inferior, nunca superpuesto)
`MainActivity` usa layout vertical: **WebView arriba** (`mainFrame`, weight 1) y
**franja inferior reservada** (`bannerSlot`) para el banner. `UnityAdsManager`
coloca un `BannerView` dentro de esa franja debajo del contenido; `show/hide`
desde el WebView (`showNativeBanner`/`hideNativeBanner`) ocultan todo el slot →
**el banner nunca tapa contenido** (patrón "reservado", no el que cubre).

### Colocación (reglas de `js/unity-ads.js`)
El WebView pide el banner/interstitial al nativo, pero **cuándo** se muestra vive
en `js/unity-ads.js` y cumple las políticas:
- **Banner**: se muestra solo en páginas de contenido, con un retardo (no
  compite con la primera pintura) y se oculta en páginas no aptas
  (`data-no-ads` en `<html>`/`<body>`: admin, analytics, juegos).
- **Interstitial**: **solo** tras una acción del usuario y en una pausa natural
  (abrir la ficha de una app en Open Source), nunca sobre el botón de descarga
  ni al cargar la página. Lleva **cooldown** (3 min) + **tope por sesión** (4) y
  *single-flight* (`isInterstitialAdReady()` antes de mostrar). Si no está
  listo, se precarga y se sale (no bloquea).
- **Rewarded**: sin cambios; sigue ligado a la descarga con verificación de
  recompensa en servidor.

### Recarga ante fallo de fill (nunca en bucle)
`UnityAdsManager` reintenta la carga de banner/interstitial/rewarded con
`RETRY_MS = 30 s` y `MAX_RETRIES = 8` (los contadores se resetean al cargar
bien). Evita pantallas vacías por *no fill* intermitente sin spamear la red.

### Diagnóstico "no se ven anuncios" (sin logcat)
`GAME_ID` (`800397583`) es el **ID de proyecto**; los *placements* son
`BP_Rewarded_Android` / `BP_Interstitial_Android` / `BP_Banner_Android` y
deben existir **exactos** en el dashboard. Para ver por qué un formato no
aparece, el WebView expone el estado real del SDK:
- `window.chAdsStatus(console.log)` → JSON puntual (`initialized`, `testMode`,
  `rewardedReady`, `interstitialReady`, `bannerAttached`, `lastErrors`).
- `window.chAdsDebug(true)` o abrir la URL con `?ads_debug=1` → badge fijo.
- `?ads_test=1|0` escribe la preferencia de **modo test** (`ch_ads/test_mode`);
  se aplica al **reiniciar** la app (`UnityAds.initialize` corre una vez por
  proceso). En modo test Unity sirve anuncios de prueba: úsalo para confirmar
  que el código de colocación funciona y descarta *no fill*.

## 3) Cruzado (aplica en ambos)
- Membresía y conflictos: no distributions de ads vía software (toolbars/ext) ni
  "software applications" fuera de web/WebView aprobado.
- No framing de contenido de terceros para monetizar sin permiso.
- Mantener `ads.txt` (web) y `app-ads.txt` (app) subidos y servidos por el host real.