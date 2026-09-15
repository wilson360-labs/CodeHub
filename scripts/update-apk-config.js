#!/usr/bin/env node
/**
 * Auto-update de la config del APK: publica apkLatestVersion + apkUpdateNotes
 * en el backend (PATCH /api/admin/config) para que el diálogo de
 * "Actualización disponible" del APK se active tras publicar una versión nueva.
 *
 * Uso:
 *   node scripts/update-apk-config.js                 # lee build.gradle + changelog.json
 *   node scripts/update-apk-config.js --dry-run       # solo muestra, no publica
 *   node scripts/update-apk-config.js --notes "L1|L2"
 *   node scripts/update-apk-config.js --notes-file notas.txt
 *   node scripts/update-apk-config.js --version 1.4.1 --bump-code
 *   node scripts/update-apk-config.js --fallback "Mejoras y correcciones"
 *
 * Auth: requiere ADMIN_KEY (env o --key). Endpoint default: Render.
 */
const fs = require('fs');
const path = require('path');

const ROOT = process.cwd();
const BACKEND_DEFAULT = 'https://codehub-98s6.onrender.com';
const GRADLE_PATH = path.join(ROOT, 'apk', 'android', 'app', 'build.gradle');
const CHANGELOG_PATH = path.join(ROOT, 'changelog.json');

function readArg(name) {
  const i = process.argv.indexOf(name);
  return i >= 0 && i + 1 < process.argv.length ? process.argv[i + 1] : null;
}
function hasFlag(name) {
  return process.argv.includes(name);
}

function parseGradle() {
  const src = fs.readFileSync(GRADLE_PATH, 'utf8');
  const vm = src.match(/versionName\s+"([^"]+)"/);
  const cm = src.match(/versionCode\s+(\d+)/);
  if (!vm || !cm) throw new Error('No se pudo leer versionName/versionCode de build.gradle');
  return { versionName: vm[1], versionCode: parseInt(cm[1], 10), src };
}

function semverValid(v) {
  return /^\d+\.\d+(\.\d+)?$/.test(String(v).trim());
}

function findChangelogEntry(versionName) {
  if (!fs.existsSync(CHANGELOG_PATH)) return null;
  let entries;
  try { entries = JSON.parse(fs.readFileSync(CHANGELOG_PATH, 'utf8')); } catch (e) { return null; }
  const targets = [versionName, 'v' + versionName];
  for (const e of entries || []) {
    if (e && e.version && targets.includes(String(e.version).trim())) return e;
  }
  return null;
}

function linesToNotes(raw) {
  return String(raw || '')
    .split(/\r?\n/)
    .map((l) => l.replace(/^\s*[\-\*•]\s*/, '').trim())
    .filter(Boolean)
    .join('\n');
}

function normalizeNotesText(raw) {
  return String(raw || '')
    .replace(/\\n/g, '\n')
    .split(/\||\r?\n/)
    .map((l) => l.replace(/^\s*[\-\*•]\s*/, '').trim())
    .filter(Boolean)
    .join('\n');
}

async function main() {
  const dryRun = hasFlag('--dry-run');

  const gradle = parseGradle();
  const version = readArg('--version') || gradle.versionName;
  if (!semverValid(version)) {
    console.error(`✖ versionName "${version}" no es semver válido (ej: 1.4.1)`);
    process.exit(1);
  }

  let notes = null;
  const notesArg = readArg('--notes');
  const notesFile = readArg('--notes-file');
  if (notesArg) notes = normalizeNotesText(notesArg);
  else if (notesFile) {
    if (!fs.existsSync(notesFile)) { console.error(`✖ No existe --notes-file: ${notesFile}`); process.exit(1); }
    notes = linesToNotes(fs.readFileSync(notesFile, 'utf8'));
  } else {
    const entry = findChangelogEntry(version);
    if (entry && Array.isArray(entry.changes) && entry.changes.length) {
      notes = entry.changes.map((c) => String(c).replace(/^\s*[\-\*•]\s*/, '').trim()).filter(Boolean).join('\n');
    } else if (entry && entry.title) {
      notes = normalizeNotesText(entry.title);
    }
  }
  if (!notes) {
    const fallback = readArg('--fallback');
    if (fallback) notes = normalizeNotesText(fallback);
  }
  if (!notes) {
    console.error(
      '✖ No hay notas para el APK. Pasa --notes "Mejora X|Corrección Y", --notes-file, --fallback, o agrega en changelog.json una entrada con version "' + version + '".'
    );
    process.exit(1);
  }

  const bumpCode = hasFlag('--bump-code');
  if (bumpCode) {
    const next = gradle.versionCode + 1;
    const patched = gradle.src.replace(/versionCode\s+\d+/, 'versionCode ' + next);
    fs.writeFileSync(GRADLE_PATH, patched);
    console.log(`↗ versionCode ${gradle.versionCode} → ${next} (escrito en build.gradle, commitéalo)`);
  }

  console.log('PAYLOAD:');
  console.log('  apkLatestVersion: ' + version);
  console.log('  apkUpdateNotes:');
  notes.split('\n').forEach((l) => console.log('    • ' + l));

  if (dryRun) {
    console.log('\n(dry-run — no se publicó nada)');
    process.exit(0);
  }

  const backend = (readArg('--backend') || BACKEND_DEFAULT).replace(/\/+$/, '');
  const key = readArg('--key') || process.env.ADMIN_KEY;
  if (!key) {
    console.error('\n✖ Falta ADMIN_KEY: usa --key <ADMIN_KEY> o la variable de entorno ADMIN_KEY');
    process.exit(1);
  }

  const res = await fetch(backend + '/api/admin/config', {
    method: 'PATCH',
    headers: {
      'Content-Type': 'application/json',
      'x-admin-key': key,
    },
    body: JSON.stringify({ config: { apkLatestVersion: version, apkUpdateNotes: notes } }),
  });
  const text = await res.text();
  let data = null;
  try { data = JSON.parse(text); } catch (e) {}
  if (!res.ok || !data || data.ok !== true) {
    console.error(`✖ PATCH falló (HTTP ${res.status}): ${text.slice(0, 300)}`);
    process.exit(1);
  }
  console.log(`\n✔ Config remota publicada (version config ${data.version}). El diálogo del APK se activará con "${version}".`);
}

main().catch((e) => { console.error('✖', e.message); process.exit(1); });