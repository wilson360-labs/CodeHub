#!/usr/bin/env node
/*
 * Resuelve el packageName real (applicationId) de cada app del catÃ¡logo
 * Open Source leyendo el build.gradle(.kts) de su repo en GitHub â€” NO
 * adivina IDs, los lee del cÃ³digo fuente real. Necesario para poder
 * detectar en el dispositivo si una app del catÃ¡logo estÃ¡ instalada
 * (PackageManager.getPackageInfo requiere el packageName exacto).
 *
 * Uso:
 *   GITHUB_TOKEN=ghp_xxx node resolve-package-names.js
 * Sin GITHUB_TOKEN funciona igual pero con el lÃ­mite de 60 req/hora de
 * GitHub (sin auth), asÃ­ que con ~50 repos puede no alcanzar para
 * terminar en una sola corrida â€” volvÃ© a correrlo, ya resueltos se
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

async function rawText(sourceRepo, branch, filePath) {
  const response = await fetch(`https://raw.githubusercontent.com/${sourceRepo}/${branch}/${filePath}`, {
    headers: HEADERS, signal: AbortSignal.timeout(15000),
  });
  return response.ok ? response.text() : null;
}

async function resolveGradleExpression(expression, sourceRepo, branch, tree, moduleDir, moduleText) {
  const property = expression.match(/(?:project\.)?property\(\s*["']([A-Za-z0-9_.-]+)["']\s*\)/);
  if (property) {
    const propertyFiles = [...new Set(['gradle.properties', `${moduleDir}/gradle.properties`])];
    for (const filePath of propertyFiles) {
      if (!tree.some(entry => entry.type === 'blob' && entry.path === filePath)) continue;
      const contents = await rawText(sourceRepo, branch, filePath);
      const value = contents?.match(new RegExp(`^\\s*${property[1]}\\s*=\\s*["']?([A-Za-z0-9_.]+)`, 'm'));
      if (value) return value[1];
    }
  }

  const variable = expression.match(/(?:\$\{)?([A-Za-z_][A-Za-z0-9_]*)(?:\})?\s*$/);
  if (!variable) return null;
  const escaped = variable[1].replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
  const configFiles = tree
    .filter(entry => entry.type === 'blob' && (
      /(^|\/)gradle\.properties$/.test(entry.path) ||
      /(^|\/)buildSrc\/.*(?:ProjectConfig|projectconfig).*(?:\.kt|\.java|\.gradle|\.groovy)$/.test(entry.path)
    ))
    .map(entry => entry.path);
  const contents = [moduleText];
  for (const filePath of configFiles) {
    const text = await rawText(sourceRepo, branch, filePath);
    if (text) contents.push(text);
  }
  const declaration = new RegExp(`(?:const\\s+val|val|var|def)?\\s*${escaped}\\s*=\\s*["']([A-Za-z0-9_.]+)["']`);
  for (const text of contents) {
    const value = text.match(declaration);
    if (value) return value[1];
  }
  return null;
}

// Busca el archivo build.gradle/build.gradle.kts de la app (no el de
// librerÃ­as) recorriendo el Ã¡rbol del repo, y extrae applicationId.
async function findPackageName(sourceRepo) {
  const repoInfo = await gh('https://api.github.com/repos/' + sourceRepo);
  if (!repoInfo) return null;
  const branch = repoInfo.default_branch || 'main';
  const treeRes = await gh('https://api.github.com/repos/' + sourceRepo + '/git/trees/' + branch + '?recursive=1');
  if (!treeRes || !Array.isArray(treeRes.tree)) return null;
  const tree = treeRes.tree;
  const candidates = tree.filter(f => f.type === 'blob' && /(^|\/)build\.gradle(\.kts)?$/.test(f.path))
    .sort((a, b) => {
      const score = p => /(^|\/)app\/build\.gradle/.test(p) ? 0
        : /(^|\/)mobile\/build\.gradle/.test(p) ? 1
          : /(^|\/)android\/app\/build\.gradle/.test(p) ? 2 : 3;
      return score(a.path) - score(b.path);
    });
  const applicationModules = [];
  for (const file of candidates) {
    const text = await rawText(sourceRepo, branch, file.path);
    if (!text) continue;
    const moduleDir = file.path.replace(/\/build\.gradle(?:\.kts)?$/, '');
    const appId = text.match(/^\s*applicationId\s*(?:=\s*)?(.+)$/m)?.[1]
      ?.split('//')[0].trim().replace(/[,;]+$/, '');
    if (appId) {
      const literal = appId.match(/^["']([A-Za-z0-9_.]+)["']$/)?.[1];
      const resolved = literal || await resolveGradleExpression(appId, sourceRepo, branch, tree, moduleDir, text);
      if (resolved && /^[A-Za-z0-9_.]+$/.test(resolved)) return resolved;
    }
    const hasAppPlugin = /com\.android\.application/.test(text) ||
      (/alias\s*\(\s*libs\.plugins\./.test(text) && /(^|\/)(app|mobile|androidApp)\/build\.gradle/.test(file.path));
    if (hasAppPlugin) applicationModules.push({ moduleDir, text });
  }
  for (const { moduleDir, text } of applicationModules) {
    const value = text.match(/^\s*namespace\s*(?:=\s*)?(.+)$/m)?.[1]
      ?.split('//')[0].trim().replace(/[,;]+$/, '');
    if (value) {
      const literal = value.match(/^["']([A-Za-z0-9_.]+)["']$/)?.[1];
      const resolved = literal || await resolveGradleExpression(value, sourceRepo, branch, tree, moduleDir, text);
      if (resolved && /^[A-Za-z0-9_.]+$/.test(resolved)) return resolved;
    }
    const manifestPath = moduleDir + '/src/main/AndroidManifest.xml';
    if (!tree.some(entry => entry.type === 'blob' && entry.path === manifestPath)) continue;
    const xml = (await rawText(sourceRepo, branch, manifestPath) || '').replace(/<!--[\s\S]*?-->/g, '');
    const pkg = xml.match(/<manifest\b[^>]*\bpackage\s*=\s*["']([A-Za-z0-9_.]+)["']/);
    if (pkg) return pkg[1];
  }
  const manifests = tree.filter(file => file.type === 'blob' &&
    (file.path === 'AndroidManifest.xml' || /(^|\/)app\/src\/main\/AndroidManifest\.xml$/.test(file.path)));
  for (const file of manifests) {
    const xml = (await rawText(sourceRepo, branch, file.path) || '').replace(/<!--[\s\S]*?-->/g, '');
    const pkg = xml.match(/<manifest\b[^>]*\bpackage\s*=\s*["']([A-Za-z0-9_.]+)["']/);
    if (pkg) return pkg[1];
  }
  return null;
}
async function main() {
  const documents = [];
  for (const seedPath of SEED_FILES) {
    if (!fs.existsSync(seedPath)) { console.warn('âš ï¸  no existe:', seedPath); continue; }
    const raw = JSON.parse(fs.readFileSync(seedPath, 'utf8'));
    const apps = Array.isArray(raw) ? raw : raw.apps;
    if (Array.isArray(apps)) documents.push({ seedPath, raw, apps, changed: false });
  }

  // Resolver una sola vez por repositorio aunque el mismo catÃ¡logo estÃ©
  // duplicado en opensource_seed.json y foss-catalog-seed.json.
  const byRepo = new Map();
  const forceResolve = process.env.FORCE_PACKAGE_RESOLVE === 'true';
  const knownByRepo = new Map();
  if (!forceResolve) for (const doc of documents) for (const app of doc.apps) {
    if (app.source_repo && app.packageName) knownByRepo.set(app.source_repo, app.packageName);
  }
  for (const doc of documents) for (const app of doc.apps) {
    if (forceResolve && app.source_repo) {
      app.packageName = null;
      doc.changed = true;
      if (!byRepo.has(app.source_repo)) byRepo.set(app.source_repo, []);
      byRepo.get(app.source_repo).push({ doc, app });
    } else if (app.source_repo && !app.packageName && knownByRepo.has(app.source_repo)) {
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
    console.log('âœ… Guardado:', doc.seedPath);
  }
}

main().catch(e => { console.error(e); process.exit(1); });

