/**
 * CodeHub — Configuración de Google AdSense
 * Publisher: ca-pub-3780093322926832
 *
 * MODALIDAD: AUTO ADS
 * El script adsbygoogle.js se carga solo en páginas con contenido de editor
 * y nunca dentro de la APK WebView (ver gate CodeHubNative / __apkNative).
 *
 * Con Auto Ads: index, tools, guias, opensource, servicios, cv.
 * Sin Auto Ads: privacy, terms, juegos, flexbox-labs (iframe de terceros),
 * admin, analytics, codehub-ultra.
 *
 * AdSense → Anuncios → Configuración de anuncios → Anuncios automáticos.
 */

const AD_SLOTS = {
  publisher: 'ca-pub-3780093322926832',
  mode: 'auto-ads',
  units: {},
};

if (typeof module !== 'undefined') module.exports = AD_SLOTS;
