#!/usr/bin/env node
/*
 * Resuelve el packageName real (applicationId) de cada app del catálogo
 * Open Source leyendo el build.gradle(.kts) de su repo en GitHub — NO
 * adivina IDs, los lee del código fuente real. Necesario para poder
 * detectar en el dispositivo si una app del catálogo está instalada
 * (PackageManager.getPackageInfo requiere el packageName exacto).
 *
 * Uso:
 *   GITHUB_TOKEN=ghp_xxx node resolve-package-names.js
 * Sin GITHUB_TOKEN funciona igual pero con el límite de 60 req/hora de
 * GitHub (sin auth), así que con ~50 repos puede no alcanzar para
 * terminar en una sola corrida — volvé a correrlo, ya resueltos se
 * saltan.
 *
 * Actualiza in-place:
 *   - ../../opensource_seed.json
 *   - foss-catalog-seed.json
 */
const fs = require('fs');
const path = require('path');

const TOKEN = process.env.GITHUB_TOKEN || '';
const HEADERS = {
  'User-Agent': 'CodeHub-PackageResolver',
  'Accept': 'application/vnd.github.v3+json',
  ...(TOKEN ? { Authorization: `Bearer ${TOKEN}` } : {}),
};

const SEED_FILES = [
  path.join(__dirname, '..', '..', 'opensource_seed.json'),
  path.join(__dirname, 'foss-catalog-seed.json'),
];

async function gh(url) {
  for (let attempt = 0; attempt < 3; attempt++) {
    const res = await fetch(url, { headers: HEADERS, signal: AbortSignal.timeout(15000) });
    if (res.ok) return res.json();
    if (res.status === 403 && res.headers.get('x-ratelimit-remaining') === '0') return null;
    if (![403, 429, 500, 502, 503, 504].includes(res.status) || attempt === 2) return null;
    const retryAfter = Number(res.headers.get('retry-after'));
    const resetAt = Number(res.headers.get('x-ratelimit-reset'));
    const delay = retryAfter > 0 ? retryAfter * 1000 : resetAt > 0
      ? Math.max(1000, Math.min(10000, resetAt * 1000 - Date.now()))
      : 1000 * (attempt + 1);
    await new Promise(resolve => setTimeout(resolve, delay));
  }
  return null;
}

// Busca el archivo build.gradle/build.gradle.kts de la app (no el de
// librerías) recorriendo el árbol del repo, y extrae applicationId.
async function findPackageName(sourceRepo) {
  const repoInfo = await gh(`https://api.github.com/repos/${sourceRepo}`);
  if (!repoInfo) return null;
  const branch = repoInfo.default_branch || 'main';

  const treeRes = await gh(`https://api.github.com/repos/${sourceRepo}/git/trees/${branch}?recursive=1`);
  if (!treeRes || !Array.isArray(treeRes.tree)) return null;

  const candidates = treeRes.tree
    .filter(f => f.type === 'blob' && /(^|\/)build\.gradle(\.kts)?$/.test(f.path))
    // Preferir app/build.gradle o mobile/build.gradle sobre build.gradle raíz
    .sort((a, b) => {
      const score = p => /(^|\/)app\/build\.gradle/.test(p) ? 0
        : /(^|\/)mobile\/build\.gradle/.test(p) ? 1
          : /(^|\/)android\/app\/build\.gradle/.test(p) ? 2 : 3;
      return score(a.path) - score(b.path);
    });

  for (const file of candidates) {
    const raw = await fetch(`https://raw.githubusercontent.com/${sourceRepo}/${branch}/${file.path}`, {
      headers: HEADERS, signal: AbortSignal.timeout(15000),
    });
    if (!raw.ok) continue;
    const text = await raw.text();
    const appId = text.match(/applicationId\s*[=(]?\s*["']([a-zA-Z0-9_.]+)["']/);
    if (appId) return appId[1];
    // En AGP, applicationId toma namespace como valor predeterminado cuando
    // el módulo no declara un ID distinto.
    const namespace = text.match(/namespace\s*[=(]?\s*["']([a-zA-Z0-9_.]+)["']/);
    if (namespace) return namespace[1];

    const moduleDir = file.path.replace(/\/build\.gradle(?:\.kts)?$/, '');
    const manifestPath = `${moduleDir}/src/main/AndroidManifest.xml`;
    if (treeRes.tree.some(entry => entry.type === 'blob' && entry.path === manifestPath)) {
      const manifest = await fetch(`https://raw.githubusercontent.com/${sourceRepo}/${branch}/${manifestPath}`, {
        headers: HEADERS, signal: AbortSignal.timeout(15000),
      });
      if (manifest.ok) {
        const xml = await manifest.text();
        const pkg = xml.match(/<manifest\b[^>]*\bpackage\s*=\s*["']([a-zA-Z0-9_.]+)["']/);
        if (pkg) return pkg[1];
      }
    }
  }
  return null;
}

async function main() {
  const documents = [];
  for (const seedPath of SEED_FILES) {
    if (!fs.existsSync(seedPath)) { console.warn('⚠️  no existe:', seedPath); continue; }
    const raw = JSON.parse(fs.readFileSync(seedPath, 'utf8'));
    const apps = Array.isArray(raw) ? raw : raw.apps;
    if (Array.isArray(apps)) documents.push({ seedPath, raw, apps, changed: false });
  }

  // Resolver una sola vez por repositorio aunque el mismo catálogo esté
  // duplicado en opensource_seed.json y foss-catalog-seed.json.
  const byRepo = new Map();
  const knownByRepo = new Map();
  for (const doc of documents) for (const app of doc.apps) {
    if (app.source_repo && app.packageName) knownByRepo.set(app.source_repo, app.packageName);
  }
  for (const doc of documents) for (const app of doc.apps) {
    if (app.source_repo && !app.packageName && knownByRepo.has(app.source_repo)) {
      app.packageName = knownByRepo.get(app.source_repo);
      doc.changed = true;
    } else if (app.source_repo && !app.packageName) {
      if (!byRepo.has(app.source_repo)) byRepo.set(app.source_repo, []);
      byRepo.get(app.source_repo).push({ doc, app });
    }
  }
  const entries = [...byRepo.entries()];
  let cursor = 0;
  const worker = async () => {
    while (cursor < entries.length) {
      const [sourceRepo, targets] = entries[cursor++];
      process.stdout.write(`Resolviendo ${targets.map(x => x.app.appId).join(', ')} (${sourceRepo})... `);
      try {
        const pkg = await findPackageName(sourceRepo);
        for (const { doc, app } of targets) {
          app.packageName = pkg || null;
          doc.changed = true;
        }
        console.log(pkg || '(Android applicationId no encontrado)');
      } catch (e) {
        console.log('error: ' + e.message);
      }
    }
  };
  await Promise.all(Array.from({ length: Math.min(4, entries.length) }, worker));

  for (const doc of documents) if (doc.changed) {
    fs.writeFileSync(doc.seedPath, JSON.stringify(doc.raw, null, 2) + '\n');
    console.log('✅ Guardado:', doc.seedPath);
  }
}

main().catch(e => { console.error(e); process.exit(1); });
