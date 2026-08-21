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
  ...(TOKEN ? { Authorization: `token ${TOKEN}` } : {}),
};

const SEED_FILES = [
  path.join(__dirname, '..', '..', 'opensource_seed.json'),
  path.join(__dirname, 'foss-catalog-seed.json'),
];

async function gh(url) {
  const res = await fetch(url, { headers: HEADERS });
  if (!res.ok) return null;
  return res.json();
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
      const score = p => /(^|\/)app\/build\.gradle/.test(p) ? 0 : /(^|\/)mobile\/build\.gradle/.test(p) ? 1 : 2;
      return score(a.path) - score(b.path);
    });

  for (const file of candidates) {
    const raw = await fetch(`https://raw.githubusercontent.com/${sourceRepo}/${branch}/${file.path}`, { headers: HEADERS });
    if (!raw.ok) continue;
    const text = await raw.text();
    const m = text.match(/applicationId\s*[=(]?\s*["']([a-zA-Z0-9_.]+)["']/);
    if (m) return m[1];
  }
  return null;
}

async function main() {
  for (const seedPath of SEED_FILES) {
    if (!fs.existsSync(seedPath)) { console.warn('⚠️  no existe:', seedPath); continue; }
    const raw = JSON.parse(fs.readFileSync(seedPath, 'utf8'));
    const apps = Array.isArray(raw) ? raw : raw.apps;
    if (!Array.isArray(apps)) continue;

    let changed = false;
    for (const app of apps) {
      if (app.packageName || !app.source_repo) continue;
      process.stdout.write(`Resolviendo ${app.appId} (${app.source_repo})... `);
      try {
        const pkg = await findPackageName(app.source_repo);
        app.packageName = pkg || null;
        changed = true;
        console.log(pkg || '(no encontrado)');
      } catch (e) {
        console.log('error: ' + e.message);
      }
    }
    if (changed) {
      fs.writeFileSync(seedPath, JSON.stringify(raw, null, 2) + '\n');
      console.log('✅ Guardado:', seedPath);
    }
  }
}

main().catch(e => { console.error(e); process.exit(1); });
