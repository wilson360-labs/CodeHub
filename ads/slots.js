/**
 * CodeHub — Configuración central de unidades AdSense
 * Publisher: ca-pub-3780093322926832
 *
 * INSTRUCCIONES:
 * 1. Cuando Google apruebe tu cuenta, entra a AdSense → Anuncios → Por unidad de anuncio
 * 2. Crea cada unidad y copia el data-ad-slot (número de 10 dígitos)
 * 3. Reemplaza null por el slot ID en cada entrada
 * 4. Ejecuta: node ads/apply-slots.js  (o hazlo manualmente en el HTML)
 */

const AD_SLOTS = {
  publisher: 'ca-pub-3780093322926832',

  units: {
    // index.html — debajo del hero
    'index-hero': {
      slot: null,          // ← reemplazar con el ID real, ej: '1234567890'
      file: 'index.html',
      position: 'Debajo del hero, antes de Open to Work',
      format: 'auto',
    },

    // index.html — pre-footer
    'index-prefooter': {
      slot: null,
      file: 'index.html',
      position: 'Antes del footer',
      format: 'auto',
    },

    // novedades.html — ad-gate (pre-descarga de apps)
    'novedades-adgate': {
      slot: null,
      file: 'pages/novedades.html',
      position: 'Intersticial pre-descarga (adGate)',
      format: 'auto',
      note: 'Este es el más importante — se muestra obligatoriamente antes de cada descarga',
    },
  },
};

// Para uso en Node.js
if (typeof module !== 'undefined') module.exports = AD_SLOTS;
