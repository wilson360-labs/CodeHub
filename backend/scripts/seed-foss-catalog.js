/**
 * seed-foss-catalog.js — Carga inicial del catálogo Open Source
 * Módulo: Catálogo Open Source · CodeHub v3
 * ─────────────────────────────────────────────────────────────────
 * Inserta (o actualiza si ya existe el appId) las apps definidas en
 * foss-catalog-seed.json dentro de la colección `App` de MongoDB.
 * Es seguro correrlo varias veces — usa upsert por appId, así que
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
  console.error('❌ Falta MONGODB_URI en el entorno.');
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
  console.log('✅ Conectado a MongoDB');

  let creadas = 0, existentes = 0;
  for (const app of seed.apps) {
    // Guarda anti-revert: si el seed trae la portada social del repo
    // (opengraph.githubassets.com) y la DB ya tiene un logo real local
    // (/img/...), se conserva el logo local en vez de pisarlo.
    const prev = await App.findOne({ appId: app.appId }).select('imagen').lean();
    const seedIsPortada = /opengraph\.githubassets\.com/i.test(app.imagen || '');
    const prevIsLocal   = !!prev?.imagen && /^\/img\//.test(prev.imagen);
    const imagenFinal   = (seedIsPortada && prevIsLocal) ? prev.imagen : (app.imagen || '');

    const res = await App.updateOne(
      { appId: app.appId },
      {
        $setOnInsert: {
          appId: app.appId,
          createdAt: new Date(),
        },
        $set: {
          nombre: app.nombre,
          descripcion: app.descripcion,
          categoria: app.categoria,
          source_repo: app.source_repo,
          imagen: imagenFinal,
          tag: '🆕',
          verified: true,
          enlace: '#', // el monitor de actualizaciones lo llenará con el .apk del release más reciente
          updatedAt: new Date(),
        },
      },
      { upsert: true }
    );
    if (res.upsertedCount > 0) { creadas++; console.log(`➕ Creada: ${app.nombre}`); }
    else { existentes++; console.log(`✔️  Ya existía (actualizada): ${app.nombre}`); }
  }

  console.log(`\n✅ Listo — ${creadas} app(s) nueva(s), ${existentes} ya existían y fueron actualizadas.`);
  console.log('💡 Ejecuta ahora check-app-updates.js para llenar versión y enlace de descarga desde GitHub Releases.');
  await mongoose.disconnect();
})().catch(err => { console.error('❌ Error fatal:', err); process.exit(1); });
