/**
 * enrich-app-logos.js — Busca y aplica el logo REAL a las apps Open Source
 * Módulo: Catálogo Open Source · CodeHub v3
 * ─────────────────────────────────────────────────────────────────
 * DISPARO UNIVERSAL: no es solo una corrección puntual. Recorre TODAS las
 * apps con `source_repo` que NO tienen un logo oficial (imagen vacía o la
 * portada social del repo opengraph.githubassets.com) — sean las que ya
 * existen o las que se agreguen a futuro. Las que ya tienen un logo local
 * (/img/...) o una URL externa elegida a mano se dejan intactas.
 *
 * Para cada candidata intenta en orden:
 *   1) fastlane/metadata/android/.../icon.png   (ícono real de la app)
 *   2) mipmap-*/ic_launcher.png                 (ícono de Android)
 *   3) avatar de la ORGANIZACIÓN (solo si el dueño es una org, cuyo avatar
 *      normalmente ES el logo de la app). Los repos de usuarios personales
 *      NO usan avatar para no ponerle una foto de desarrollador de logo.
 *
 * El logo encontrado se sube a img/opensource/{appId}.png en GitHub y se
 * actualiza `imagen` en MongoDB a la ruta local. Es idempotente: si ya se
 * aplicó, la app ya tiene /img/ y se saltea.
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

// Logo oficial local o URL externa elegida a mano → no se toca.
function hasOfficialImage(img) {
  if (!img) return false;
  const v = String(img).trim();
  return /^\/img\//.test(v) || /^https?:\/\//i.test(v);
}

function ghHeaders(extra = {}) {
  return { Accept: 'application/vnd.github+json', ...(GITHUB_TOKEN ? { Authorization: `Bearer ${GITHUB_TOKEN}` } : {}), ...extra };
}

// Info del repo: rama default y tipo de dueño (org vs usuario).
async function fetchRepoInfo(owner, repo) {
  try {
    const r = await fetch(`https://api.github.com/repos/${owner}/${repo}`, { headers: ghHeaders() });
    if (!r.ok) return null;
    return await r.json();
  } catch { return null; }
}

// Ícono real dentro del repo: fastlane y mipmap (PNG), en la rama default.
async function findRepoIcon(owner, repo, defaultBranch) {
  const branches = [defaultBranch, 'main', 'master'].filter(Boolean);
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

// Avatar de la ORGANIZACIÓN del repo. Solo aplica cuando el dueño es una
// org (su avatar suele ser el logo oficial de la app). Los repos de
// usuarios personales se saltan para no poner una foto de perfil de logo.
async function fetchOwnerAvatar(owner) {
  try {
    const r = await fetch(`https://github.com/${encodeURIComponent(owner)}.png`, {
      headers: { 'User-Agent': 'CodeHub-IconBot' },
      redirect: 'follow',
    });
    if (r.ok) {
      const buf = Buffer.from(await r.arrayBuffer());
      if (buf.length > 0) return { buffer: buf, ext: '.png', source: `avatar-org:${owner}` };
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

// Candidatas: apps open source cuyo logo NO es oficial — vacío, '#' o la
// portada del repo (opengraph). Cualquier app nueva sembrada con source_repo
// e imagen opengraph/ vacía entra sola la próxima vez que se dispare.
const NO_LOGO_RE = /opengraph\.githubassets\.com/i;

(async () => {
  await mongoose.connect(MONGODB_URI, { serverSelectionTimeoutMS: 8000 });
  console.log('✅ Conectado a MongoDB');

  const all = await App.find({ source_repo: { $ne: null } }).lean();
  const candidates = all.filter(a => !hasOfficialImage(a.imagen) || NO_LOGO_RE.test(a.imagen || ''));
  console.log(`🔎 ${candidates.length} app(s) sin logo oficial de ${all.length} open source — buscando logo real...`);

  let aplicadas = 0, fallidas = 0, saltadas = 0;

  for (const app of candidates) {
    const [owner, repo] = (app.source_repo || '').split('/');
    const appId = app.appId;
    try {
      if (!owner || !repo) throw new Error('source_repo inválido');

      const info = await fetchRepoInfo(owner, repo);
      const defaultBranch = info?.default_branch;
      const isOrg = info?.owner?.type === 'Organization';

      let icon = await findRepoIcon(owner, repo, defaultBranch);
      if (!icon && isOrg) icon = await fetchOwnerAvatar(owner);
      if (!icon) {
        const motivo = !info ? 'repo no accesible' : (isOrg ? 'sin ícono ni avatar disponible' : 'repo de usuario sin fastlane/mipmap (no se usa avatar de usuario)');
        console.log(`⏭️  ${app.nombre}: ${motivo}`);
        saltadas++;
        continue;
      }

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

  console.log(`\n✅ Listo — ${aplicadas} logo(s) aplicado(s), ${saltadas} sin logo disponible, ${fallidas} error(es) de ${candidates.length}.`);
  await mongoose.disconnect();
})().catch(err => { console.error('❌ Error fatal:', err); process.exit(1); });
