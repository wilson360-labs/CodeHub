#!/usr/bin/env node
/**
 * Usado por: .github/workflows/quality.yml
 * Audita la coherencia entre las fuentes de versión de CodeHub:
 *
 *   1. sw.js VERSION ......... version vigente del sitio/PWA (auto-bump +0.01
 *                              por wire-build.js cuando cambia el precache).
 *                              build-apk.yml publica el APK como apk-<VERSION>.
 *   2. changelog.json ........ changelog curado (/api/changelog, y notas que
 *                              lee scripts/update-apk-config.js).
 *   3. apk-download.json ..... config legacy del enlace de descarga del APK.
 *   4. apk/android/app/build.gradle  versionName/versionCode de la app Android.
 *      (AppUpdateChecker.java compara el tag de GitHub contra versionName con
 *       parseIntSafe, que borra lo no numérico → una serie distinta a la del
 *       sitio provoca avisos de actualización incorrectos.)
 *
 * Modo por defecto: reporte ADVISORY (el drift NO rompe el CI, solo se lista).
 *   node scripts/audit-versions.js            → exit 0 (salvo errores graves)
 *   node scripts/audit-versions.js --strict   → exit 1 si hay cualquier drift
 */
const fs = require('fs');
const path = require('path');

const ROOT = process.cwd();
const STRICT = process.argv.includes('--strict');

const SW_PATH = path.join(ROOT, 'sw.js');
const CHANGELOG_PATH = path.join(ROOT, 'changelog.json');
const APK_DL_PATH = path.join(ROOT, 'apk-download.json');
const GRADLE_PATH = path.join(ROOT, 'apk', 'android', 'app', 'build.gradle');

const errors = [];
const warnings = [];

function readIfExists(p) {
  try { return fs.readFileSync(p, 'utf8'); } catch { return null; }
}

/** 'codehub-v7.14' | 'v7.14' | '1.4.0' → 7.14 (número). null si no hay match. */
function versionNumber(v) {
  const m = String(v || '').match(/(\d+)\.(\d+)/);
  return m ? Number(m[1] + '.' + m[2]) : null;
}

// ── 1. sw.js VERSION ────────────────────────────────────────────
let siteVersion = null;   // 'v7.14'
let siteNumber = null;    // 7.14
const sw = readIfExists(SW_PATH);
if (!sw) {
  errors.push('No existe sw.js');
} else {
  const m = sw.match(/const VERSION\s*=\s*'([^']+)'/);
  if (!m) {
    errors.push("sw.js: no encontré `const VERSION = '...'`");
  } else {
    siteNumber = versionNumber(m[1]);
    siteVersion = siteNumber != null ? 'v' + siteNumber.toFixed(2) : m[1];
    if (siteNumber == null) errors.push(`sw.js: VERSION con formato inesperado: ${m[1]}`);
  }
}

// ── 2. changelog.json ───────────────────────────────────────────
let changelog = [];
const clRaw = readIfExists(CHANGELOG_PATH);
if (clRaw == null) {
  warnings.push('No existe changelog.json');
} else {
  try {
    changelog = JSON.parse(clRaw);
    if (!Array.isArray(changelog)) {
      errors.push('changelog.json: la raíz no es un array');
      changelog = [];
    }
  } catch (e) {
    errors.push('changelog.json: JSON inválido — ' + e.message);
    changelog = [];
  }
}
const clVersions = changelog
  .map((e) => ({ v: e && e.version, n: versionNumber(e && e.version) }))
  .filter((e) => e.v);
const newestClNumber = clVersions.reduce(
  (acc, e) => (e.n != null && (acc == null || e.n > acc) ? e.n : acc),
  null
);

// ── 2b. config legacy del APK ───────────────────────────────────
let apkDlVersion = null;
const apkDlRaw = readIfExists(APK_DL_PATH);
if (apkDlRaw == null) {
  warnings.push('No existe apk-download.json');
} else {
  try {
    const j = JSON.parse(apkDlRaw);
    apkDlVersion = j && j.version ? String(j.version) : null;
  } catch (e) {
    warnings.push('apk-download.json: JSON inválido — ' + e.message);
  }
}

// ── 3. build.gradle (Android) ───────────────────────────────────
let gradleVersionName = null;
let gradleVersionCode = null;
const gradle = readIfExists(GRADLE_PATH);
if (gradle) {
  const vm = gradle.match(/versionName\s+"([^"]+)"/);
  const cm = gradle.match(/versionCode\s+(\d+)/);
  gradleVersionName = vm ? vm[1] : null;
  gradleVersionCode = cm ? cm[1] : null;
  if (!vm) warnings.push('build.gradle: no encontré versionName');
  if (!cm) warnings.push('build.gradle: no encontré versionCode');
}

// ── Evaluación de drift ─────────────────────────────────────────
if (siteNumber != null && newestClNumber != null && siteNumber - newestClNumber >= 0.05) {
  warnings.push(
    `changelog desfasado: la entrada más nueva es v${newestClNumber.toFixed(2)} ` +
    `pero el sitio va en ${siteVersion} (delta ${(siteNumber - newestClNumber).toFixed(2)})`
  );
}

if (siteNumber != null && apkDlVersion != null) {
  const n = versionNumber(apkDlVersion);
  if (n == null) warnings.push(`apk-download.json: version con formato inesperado (${apkDlVersion})`);
  else if (n !== siteNumber) {
    warnings.push(`apk-download.json apunta a v${n.toFixed(2)} pero el sitio va en ${siteVersion}`);
  }
}

if (siteNumber != null && gradleVersionName != null) {
  const g = versionNumber(gradleVersionName);
  if (g == null) warnings.push(`build.gradle: versionName no numérico (${gradleVersionName})`);
  else if (Math.floor(g) !== Math.floor(siteNumber)) {
    warnings.push(
      `build.gradle versionName "${gradleVersionName}" (serie ${Math.floor(g)}.x) no coincide con la serie ` +
      `del sitio ${siteVersion} (serie ${Math.floor(siteNumber)}.x) — AppUpdateChecker.java compara el tag ` +
      `apk-${siteVersion} contra este versionName y puede avisar de actualizaciones falsas`
    );
  }
}

// ── Reporte ─────────────────────────────────────────────────────
const line = '─'.repeat(58);
console.log(line);
console.log('  AUDITORÍA DE VERSIONES — CodeHub');
console.log(line);
console.log(`  sw.js VERSION ......... ${siteVersion || '(desconocida)'}`);
console.log(`  changelog.json ........ ${newestClNumber != null ? 'v' + newestClNumber.toFixed(2) : '(vacío)'} (${changelog.length} entradas)`);
console.log(`  apk-download.json ..... ${apkDlVersion || '(sin version)'}`);
console.log(`  build.gradle .......... versionName ${gradleVersionName || '?'} · versionCode ${gradleVersionCode || '?'}`);
if (siteVersion) console.log(`  tag de release APK .... apk-${siteVersion}`);
console.log(line);

for (const w of warnings) console.log('  ⚠  ' + w);
for (const e of errors) console.log('  ✖  ' + e);
if (!warnings.length && !errors.length) console.log('  ✔ Todo coherente.');

if (STRICT && warnings.length) {
  console.error(`\n✖ ${warnings.length} advertencia(s) en modo --strict.`);
  process.exit(1);
}
if (errors.length) {
  console.error(`\n✖ ${errors.length} error(es) grave(s).`);
  process.exit(1);
}
console.log('\n(auditoría advisory — usa --strict para fallar ante cualquier drift)');
process.exit(0);
