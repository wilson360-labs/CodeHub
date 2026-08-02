/**
 * dedupe-catalog.js — Limpieza de apps duplicadas en el catálogo
 * Módulo: Catálogo Open Source · CodeHub v3
 * ─────────────────────────────────────────────────────────────────
 * Causa del bug: opensource_seed.json y backend/scripts/foss-catalog-seed.json
 * usaban appId distintos para la misma app en 24 casos (ej. NewPipe era
 * "os-newpipe" en uno y "newpipe" en el otro). Como el seed hace upsert
 * POR appId, sembrar con ambos métodos creó dos documentos separados en
 * MongoDB para esas 24 apps — eso es lo que se ve repetido en /opensource.
 * (Ya corregido en ambos JSON para que no vuelva a pasar — mismo appId,
 * derivado de source_repo — pero los duplicados ya sembrados siguen en la
 * base de datos y este script los limpia.)
 *
 * Qué hace:
 *   1. Agrupa todas las apps con `source_repo` (las de Open Source) por
 *      ese campo — dos apps nunca deberían compartir el mismo repo de
 *      GitHub, así que cualquier grupo con más de un documento es un
 *      duplicado real.
 *   2. De cada grupo duplicado, conserva UN documento (prioriza el que
 *      tenga `enlace` distinto de "#", y si hay empate el más reciente
 *      por updatedAt) y borra el resto.
 *   3. Con --dry-run solo imprime qué borraría, sin tocar la base de datos.
 *
 * Uso:
 *   MONGODB_URI=... node backend/scripts/dedupe-catalog.js --dry-run
 *   MONGODB_URI=... node backend/scripts/dedupe-catalog.js
 */

'use strict';

const mongoose = require('mongoose');

const MONGODB_URI = process.env.MONGODB_URI;
if (!MONGODB_URI) {
  console.error('❌ Falta MONGODB_URI en el entorno.');
  process.exit(1);
}

const DRY_RUN = process.argv.includes('--dry-run');

const App = mongoose.models.App || mongoose.model('App', new mongoose.Schema({
  appId: String, nombre: String, descripcion: String, version: String,
  tag: String, changelog: String, imagen: String, categoria: String,
  verified: Boolean, enlace: String, plugin_enlace: String,
  tutorial_url: String, source_repo: String,
  updatedAt: Date, createdAt: Date,
}, { strict: false }));

function pickSurvivor(docs) {
  // 1º criterio: que tenga un enlace de descarga real (no "#" / vacío).
  const conEnlace = docs.filter(d => d.enlace && d.enlace !== '#');
  const pool = conEnlace.length ? conEnlace : docs;
  // 2º criterio: el más reciente por updatedAt.
  return pool.sort((a, b) => new Date(b.updatedAt || 0) - new Date(a.updatedAt || 0))[0];
}

(async () => {
  await mongoose.connect(MONGODB_URI, { serverSelectionTimeoutMS: 8000 });
  console.log(`✅ Conectado a MongoDB${DRY_RUN ? ' (modo --dry-run, no se borra nada)' : ''}`);

  const apps = await App.find({ source_repo: { $exists: true, $ne: null } }).lean();
  console.log(`📦 ${apps.length} apps con source_repo encontradas`);

  const byRepo = {};
  for (const a of apps) {
    (byRepo[a.source_repo] = byRepo[a.source_repo] || []).push(a);
  }

  const dupGroups = Object.entries(byRepo).filter(([, docs]) => docs.length > 1);
  console.log(`🔎 ${dupGroups.length} repo(s) con documentos duplicados\n`);

  let toDelete = [];
  for (const [repo, docs] of dupGroups) {
    const survivor = pickSurvivor(docs);
    const losers = docs.filter(d => d._id.toString() !== survivor._id.toString());
    console.log(`— ${repo} (${docs.length} copias)`);
    console.log(`   ✔️  conserva: appId="${survivor.appId}" (${survivor._id})`);
    losers.forEach(l => console.log(`   🗑️  borra:    appId="${l.appId}" (${l._id})`));
    toDelete.push(...losers.map(l => l._id));
  }

  if (!dupGroups.length) {
    console.log('🎉 No se encontraron duplicados. El catálogo está limpio.');
  } else if (DRY_RUN) {
    console.log(`\n💡 Se borrarían ${toDelete.length} documento(s). Corré sin --dry-run para aplicar.`);
  } else {
    const res = await App.deleteMany({ _id: { $in: toDelete } });
    console.log(`\n✅ Listo — ${res.deletedCount} documento(s) duplicado(s) eliminado(s).`);
  }

  await mongoose.disconnect();
})().catch(err => { console.error('❌ Error fatal:', err); process.exit(1); });
