/**
 * CodeHub — Configuración de Google AdSense
 * Publisher: ca-pub-3780093322926832
 *
 * ⚠️ MODALIDAD: AUTO ADS (desde Agosto 2026)
 * Google decide automáticamente dónde colocar los anuncios. Solo se carga
 * el script `adsbygoogle.js` en el <head> de cada página; NO se usan
 * unidades manuales <ins> ni data-ad-slot.
 *
 * Páginas con el script: index.html, pages/tools.html, pages/opensource.html,
 * privacy.html, terms.html.
 *
 * Para activar Auto ads desde el panel de AdSense:
 *   AdSense → Anuncios → Configuración de anuncios → Anuncios automáticos → activar.
 *
 * (Este archivo se mantiene como referencia del publisher id; ya no hace
 *  falta rellenar slot IDs porque no se usan unidades manuales.)
 */

const AD_SLOTS = {
  publisher: 'ca-pub-3780093322926832',
  mode: 'auto-ads',
  units: {},
};

// Para uso en Node.js
if (typeof module !== 'undefined') module.exports = AD_SLOTS;
