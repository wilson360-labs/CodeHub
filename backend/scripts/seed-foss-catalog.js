/**
 * seed-foss-catalog.js â€” Carga inicial del catÃ¡logo Open Source
 * MÃ³dulo: CatÃ¡logo Open Source Â· CodeHub v3
 * â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
 * Inserta (o actualiza si ya existe el appId) las apps definidas en
 * foss-catalog-seed.json dentro de la colecciÃ³n `App` de MongoDB.
 * Es seguro correrlo varias veces â€” usa upsert por appId, asÃ­ que
 * no duplica registros.
 *
 * Uso:
 *   MONGODB_URI=... node backend/scripts/seed-foss-catalog.js
 */

'use strict';

const mongoose = require('mongoose');
const seed = require('./foss-catalog-seed.json');

const MONGODB_URI = process.env.MONGODB_URI;
if (!MONGODB_URI) {
  console.error('âŒ Falta MONGODB_URI en el entorno.');
  process.exit(1);
}

const App = mongoose.models.App || mongoose.model('App', new mongoose.Schema({
  appId: String, nombre: String, descripcion: String, version: String,
  tag: String, changelog: String, imagen: String, categoria: String,
  verified: Boolean, enlace: String, plugin_enlace: String,
  tutorial_url: String, source_repo: String,
  updatedAt: Date, createdAt: Date,
}, { strict: false }));

(async () => {
  await mongoose.connect(MONGODB_URI, { serverSelectionTimeoutMS: 8000 });
  console.log('âœ… Conectado a MongoDB');

  let creadas = 0, existentes = 0;
  for (const app of seed.apps) {
    // Guarda anti-revert: si el seed NO propone un logo real (viene vacÃ­o o
    // es la portada social del repo opengraph.githubassets.com) y la DB ya
    // tiene un logo local (/img/... â€” aplicado por enrich-app-logos o de
    // forma manual), se conserva el logo local en vez de pisarlo. Antes solo
    // se protegÃ­a el caso opengraph, asÃ­ que sembrar pisaba los logos de los
    // apps sin imagen en el seed (ej. Magisk, Termux) dejÃ¡ndolas sin logo.
    const prev = await App.findOne({ appId: app.appId }).select('imagen').lean();
    const seedSinLogoOficial = !(app.imagen || '').trim() || /opengraph\.githubassets\.com/i.test(app.imagen || '');
    const prevIsLocal   = !!prev?.imagen && /^\/img\//.test(prev.imagen);
    const imagenFinal   = (seedSinLogoOficial && prevIsLocal) ? prev.imagen : (app.imagen || '');

    const fields = {
      nombre: app.nombre,
      descripcion: app.descripcion,
      categoria: app.categoria,
      source_repo: app.source_repo,
      imagen: imagenFinal,
      tag: 'ðŸ†•',
      verified: true,
      enlace: '#', // el monitor de actualizaciones lo llenarÃ¡ con el .apk del release mÃ¡s reciente
      updatedAt: new Date(),
    };
    // Resolver PackageManager.getPackageInfo requiere el applicationId exacto.
    // El resolver se ejecuta antes del seed. Escribe tambiÃ©n null cuando no
    // encontrÃ³ un ID verificable para eliminar valores viejos de mÃ³dulos library.
    if (app.source_repo) {
      fields.packageName = /^[A-Za-z0-9_.]+$/.test(app.packageName || '') ? app.packageName : null;
    }

    const res = await App.updateOne(
      { appId: app.appId },
      {
        $setOnInsert: {
          appId: app.appId,
          createdAt: new Date(),
        },
        $set: fields,
      },
      { upsert: true }
    );
    if (res.upsertedCount > 0) { creadas++; console.log(`âž• Creada: ${app.nombre}`); }
    else { existentes++; console.log(`âœ”ï¸  Ya existÃ­a (actualizada): ${app.nombre}`); }
  }

  console.log(`\nâœ… Listo â€” ${creadas} app(s) nueva(s), ${existentes} ya existÃ­an y fueron actualizadas.`);
  console.log('ðŸ’¡ Ejecuta ahora check-app-updates.js para llenar versiÃ³n y enlace de descarga desde GitHub Releases.');
  await mongoose.disconnect();
})().catch(err => { console.error('âŒ Error fatal:', err); process.exit(1); });

