/**
 * enrich-app-logos.js — Busca y aplica el logo REAL a las apps Open Source
 * Módulo: Catálogo Open Source · CodeHub v3
 * ─────────────────────────────────────────────────────────────────
 * Recorre las apps con `source_repo` cuyo `imagen` apunta a la
 * portada social del repo (opengraph.githubassets.com/... en vez del
 * logo de la app), intenta encontrar el ícono real dentro del repo
 * (fastlane, mipmap) y, si no hay, usa el avatar de la organización.
 * Sube el logo a img/opensource/{appId}.png en GitHub y actualiza el
 * campo `imagen` en MongoDB a la ruta local.
 *
 * Uso:
 *   MONGODB_URI=... GITHUB_TOKEN=... node backend/scripts/enrich-app-logos.js
 *
 * GITHUB_TOKEN: Personal Access Token con permiso de contenido en el repo
 * (para subir los PNG). El token automático de GitHub Actions (secrets.GITHUB_TOKEN)
 * funciona si el workflow declara `permissions: contents: write`.
 *
 * DRY_RUN=1: solo descubre y reporta, no sube ni modifica la DB.
 */

'use strict';

const mongoose = require('mongoose');

const MONGODB_URI  = process.env.MONGODB_URI;
const GITHUB_TOKEN = process.env.GITHUB_TOKEN || null;
const DRY_RUN      = process.env.DRY_RUN === '1';

if (!MONGODB_URI) {
  console.error('❌ Falta MONGODB_URI en el entorno.');
  process.exit(1);
}

const GITHUB_OWNER  = process.env.GITHUB_OWNER  || 'wilson360-labs';
const GITHUB_REPO   = process.env.GITHUB_REPO   || 'CodeHub';
const GITHUB_BRANCH = process.env.GITHUB_BRANCH || 'main';

const App = mongoose.models.App || mongoose.model('App', new mongoose.Schema({
  appId: String, nombre: String, descripcion: String, version: String,
  tag: String, changelog: String, imagen: String, categoria: String,
  verified: Boolean, enlace: String, plugin_enlace: String,
  tutorial_url: String, source_repo: String,
  updatedAt: Date, createdAt: Date,
}, { strict: false }));

function ghHeaders(extra = {}) {
  return { Accept: 'application/vnd.github+json', ...(GITHUB_TOKEN ? { Authorization: `Bearer ${GITHUB_TOKEN}` } : {}), ...extra };
}

// Ícono real dentro del repo: fastlane y mipmap (PNG), en varias ramas.
async function findRepoIcon(owner, repo) {
  const branches = ['main', 'master', 'dev'];
  const paths = [
    'fastlane/metadata/android/en-US/images/icon.png',
    'metadata/android/en-US/images/icon.png',
    'app/src/main/res/mipmap-xxxhdpi/ic_launcher.png',
    'app/src/main/res/mipmap-xxhdpi/ic_launcher.png',
    'app/src/main/res/mipmap-xhdpi/ic_launcher.png',
    'app/src/main/res/mipmap-anydpi-v26/ic_launcher.png',
  ];
  for (const branch of branches) {
    for (const path of paths) {
      try {
        const r = await fetch(`https://api.github.com/repos/${owner}/${repo}/contents/${path}?ref=${branch}`, { headers: ghHeaders({ Accept: 'application/vnd.github.raw' }) });
        if (r.ok) {
          const buf = Buffer.from(await r.arrayBuffer());
          if (buf.length > 0) return { buffer: buf, ext: '.png', source: path };
        }
      } catch { /* siguiente combinación */ }
    }
  }
  return null;
}

// Avatar de la organización/usuario del repo (https://github.com/{owner}.png).
// Suena a fallback para apps cuyo repo no expone fastlane/mipmap.
async function fetchOwnerAvatar(owner) {
  try {
    const r = await fetch(`https://github.com/${encodeURIComponent(owner)}.png`, {
      headers: { 'User-Agent': 'CodeHub-IconBot' },
      redirect: 'follow',
    });
    if (r.ok) {
      const buf = Buffer.from(await r.arrayBuffer());
      if (buf.length > 0) return { buffer: buf, ext: '.png', source: `avatar:${owner}` };
    }
  } catch {}
  return null;
}

async function ghUploadFile(filePath, content) {
  let sha;
  try {
    const g = await fetch(`https://api.github.com/repos/${GITHUB_OWNER}/${GITHUB_REPO}/contents/${filePath}?ref=${GITHUB_BRANCH}`, { headers: ghHeaders() });
    if (g.ok) sha = (await g.json()).sha;
  } catch { /* archivo nuevo */ }

  const body = JSON.stringify({
    message: `img: logo ${filePath}`,
    content: Buffer.from(content).toString('base64'),
    branch: GITHUB_BRANCH,
    ...(sha ? { sha } : {}),
  });
  const r = await fetch(`https://api.github.com/repos/${GITHUB_OWNER}/${GITHUB_REPO}/contents/${filePath}`, {
    method: 'PUT',
    headers: ghHeaders({ 'Content-Type': 'application/json' }),
    body,
  });
  if (!r.ok) {
    const d = await r.json().catch(() => ({}));
    throw new Error(`GitHub PUT ${filePath} → ${r.status} ${d.message || ''}`);
  }
  return true;
}

const OPENGGRAPH_RE = /opengraph\.githubassets\.com/i;

(async () => {
  await mongoose.connect(MONGODB_URI, { serverSelectionTimeoutMS: 8000 });
  console.log('✅ Conectado a MongoDB');

  const apps = await App.find({
    source_repo: { $ne: null },
    imagen: OPENGGRAPH_RE,
  }).lean();
  console.log(`🔎 ${apps.length} apps muestran la portada del repo (opengraph) — buscando logo real...`);

  let aplicadas = 0, fallidas = 0;

  for (const app of apps) {
    const [owner, repo] = (app.source_repo || '').split('/');
    const appId = app.appId;
    try {
      if (!owner || !repo) throw new Error('source_repo inválido');

      let icon = await findRepoIcon(owner, repo);
      if (!icon) icon = await fetchOwnerAvatar(owner);
      if (!icon) throw new Error(`sin ícono encontrado en ${app.source_repo}`);

      const targetPath = `img/opensource/${appId}${icon.ext}`;
      const localImagen = `/${targetPath}`;

      if (DRY_RUN) {
        console.log(`⏭️  [dry-run] ${app.nombre}: logo en ${icon.source} → ${localImagen}`);
        continue;
      }

      await ghUploadFile(targetPath, icon.buffer);
      await App.updateOne({ appId }, { $set: { imagen: localImagen, updatedAt: new Date() } });
      aplicadas++;
      console.log(`✅ ${app.nombre}: ${icon.source} → ${localImagen}`);
    } catch (err) {
      fallidas++;
      console.error(`❌ ${app.nombre} (${app.source_repo}):`, err.message);
    }
    await new Promise(r => setTimeout(r, 600));
  }

  console.log(`\n✅ Listo — ${aplicadas} logo(s) aplicado(s), ${fallidas} fallido(s) de ${apps.length}.`);
  await mongoose.disconnect();
})().catch(err => { console.error('❌ Error fatal:', err); process.exit(1); });
