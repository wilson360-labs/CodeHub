/**
 * check-app-updates.js — Monitor de actualizaciones vía GitHub Releases
 * Módulo: Catálogo Open Source · CodeHub v3
 * ─────────────────────────────────────────────────────────────────
 * Recorre todas las apps del catálogo que tengan `source_repo`
 * ("owner/repo") y consulta la API pública de GitHub para saber si
 * hay una versión más nueva publicada. Si la hay, actualiza el
 * documento en MongoDB (version, enlace de descarga directa del
 * .apk si el release trae uno adjunto, changelog y tag).
 *
 * Uso:
 *   MONGODB_URI=... GITHUB_TOKEN=... node backend/scripts/check-app-updates.js
 *
 * GITHUB_TOKEN es opcional pero muy recomendado: sin él, la API de
 * GitHub limita a 60 solicitudes/hora por IP; con un Personal Access
 * Token (solo necesita permisos de lectura pública) el límite sube
 * a 5,000/hora — más que suficiente para revisar el catálogo entero
 * varias veces al día.
 *
 * Pensado para ejecutarse vía GitHub Actions con un cron (ver
 * .github/workflows/check-app-updates.yml).
 */

'use strict';

const mongoose = require('mongoose');

const MONGODB_URI  = process.env.MONGODB_URI;
const GITHUB_TOKEN = process.env.GITHUB_TOKEN || null;

if (!MONGODB_URI) {
  console.error('❌ Falta MONGODB_URI en el entorno.');
  process.exit(1);
}

// Mismo esquema que server.js — se define de forma local para que este
// script pueda correr de forma independiente (por ejemplo en GitHub Actions)
// sin necesitar importar todo server.js.
const App = mongoose.models.App || mongoose.model('App', new mongoose.Schema({
  appId: String, nombre: String, descripcion: String, version: String,
  tag: String, changelog: String, imagen: String, categoria: String,
  verified: Boolean, enlace: String, plugin_enlace: String,
  tutorial_url: String, source_repo: String,
  updatedAt: Date, createdAt: Date,
}, { strict: false }));

async function fetchLatestRelease(ownerRepo) {
  const url = `https://api.github.com/repos/${ownerRepo}/releases/latest`;
  const headers = {
    'Accept': 'application/vnd.github+json',
    'User-Agent': 'CodeHub-App-Update-Monitor',
  };
  if (GITHUB_TOKEN) headers['Authorization'] = `Bearer ${GITHUB_TOKEN}`;

  const res = await fetch(url, { headers });
  if (res.status === 404) return null; // repo sin releases publicados
  if (!res.ok) throw new Error(`GitHub API ${res.status} para ${ownerRepo}`);
  return res.json();
}

function pickApkAsset(release) {
  if (!Array.isArray(release.assets)) return null;
  const apk = release.assets.find(a => a.name && a.name.toLowerCase().endsWith('.apk'));
  return apk ? apk.browser_download_url : null;
}

function truncate(text, max = 400) {
  if (!text) return '';
  const clean = text.replace(/\r\n/g, '\n').trim();
  return clean.length > max ? clean.slice(0, max).trim() + '…' : clean;
}

(async () => {
  await mongoose.connect(MONGODB_URI, { serverSelectionTimeoutMS: 8000 });
  console.log('✅ Conectado a MongoDB');

  const apps = await App.find({ source_repo: { $ne: null } }).lean();
  console.log(`🔎 Revisando ${apps.length} apps con source_repo configurado...`);

  let actualizadas = 0;
  for (const app of apps) {
    try {
      const release = await fetchLatestRelease(app.source_repo);
      if (!release) { console.log(`⏭️  ${app.nombre}: sin releases en ${app.source_repo}`); continue; }

      const nuevaVersion = release.tag_name || release.name || null;
      if (!nuevaVersion) continue;

      const cambioVersion = nuevaVersion !== app.version;
      if (!cambioVersion) { console.log(`✔️  ${app.nombre}: ya está al día (${app.version})`); continue; }

      const apkUrl = pickApkAsset(release);
      const update = {
        version: nuevaVersion,
        changelog: truncate(release.body),
        tag: '🔄 Actualizada',
        updatedAt: new Date(),
      };
      if (apkUrl) update.enlace = apkUrl; // solo se sobreescribe si el release trae un .apk adjunto

      await App.updateOne({ appId: app.appId }, { $set: update });
      actualizadas++;
      console.log(`⬆️  ${app.nombre}: ${app.version || '(sin versión previa)'} → ${nuevaVersion}`);
    } catch (err) {
      console.error(`❌ ${app.nombre} (${app.source_repo}):`, err.message);
    }
    // Pequeña pausa para no saturar la API de GitHub
    await new Promise(r => setTimeout(r, 500));
  }

  console.log(`\n✅ Listo — ${actualizadas} app(s) actualizada(s) de ${apps.length} revisadas.`);
  await mongoose.disconnect();
})().catch(err => { console.error('❌ Error fatal:', err); process.exit(1); });
