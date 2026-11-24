#!/usr/bin/env node
/**
 * Genera la entrada de changelog de un build APK desde los commits publicados
 * entre la última release y el commit actual. La entrada se escribe como JSON
 * (stdout o --output) y la publica scripts/update-apk-config.js junto con la
 * versión/versionCode del APK.
 *
 * Uso en CI:
 *   node scripts/build-changelog-entry.js \
 *     --version "$APP_VERSION" --build "$BUILD_SEQ" --sha "$SHORT_SHA" \
 *     --prev-tag "$PREV_RELEASE_TAG" --fallback "$MSG" \
 *     --output "$RUNNER_TEMP/changelog-entry.json"
 *
 * Uso local (sin GitHub):
 *   node scripts/build-changelog-entry.js --version 7.72 --build 5 \
 *     --sha abc1234 --subjects-file notas.txt
 *
 * Env: GITHUB_TOKEN/GH_TOKEN, GITHUB_REPOSITORY, GITHUB_RUN_ID.
 */
const fs = require('fs');

function readArg(name) {
  const i = process.argv.indexOf(name);
  return i >= 0 && i + 1 < process.argv.length ? process.argv[i + 1] : null;
}

const TYPES = {
  feat:     { es: 'Nuevo',         en: 'Feature' },
  fix:      { es: 'Corrección',    en: 'Fix' },
  style:    { es: 'Estilo',        en: 'Style' },
  docs:     { es: 'Documentación', en: 'Docs' },
  chore:    { es: 'Mantenimiento', en: 'Chore' },
  refactor: { es: 'Refactor',      en: 'Refactor' },
  perf:     { es: 'Rendimiento',   en: 'Performance' },
  build:    { es: 'Build',         en: 'Build' },
  ci:       { es: 'CI',            en: 'CI' },
  test:     { es: 'Pruebas',       en: 'Tests' },
};

function cleanSubject(raw) {
  return String(raw || '').split(/\r?\n/)[0].replace(/\s+/g, ' ').trim();
}

function toBullet(subject, lang) {
  const cleaned = cleanSubject(subject);
  const m = cleaned.match(/^([a-z]+)(?:\([^)]*\))?(!?):\s*(.*)$/i);
  const type = m ? m[1].toLowerCase() : '';
  const text = (m ? m[3] : cleaned).trim();
  const label = (TYPES[type] && TYPES[type][lang]) || (type || (lang === 'es' ? 'Cambio' : 'Change'));
  return text ? `${label}: ${text}` : label;
}

function readSubjectsFile(file) {
  if (!fs.existsSync(file)) throw new Error(`No existe --subjects-file: ${file}`);
  return fs.readFileSync(file, 'utf8')
    .split(/\r?\n/)
    .map(cleanSubject)
    .filter(Boolean);
}

async function githubJson(url, token) {
  const res = await fetch(url, {
    headers: {
      Accept: 'application/vnd.github+json',
      'User-Agent': 'CodeHub-Changelog-Builder',
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
    },
  });
  if (!res.ok) {
    const body = (await res.text()).slice(0, 200);
    throw new Error(`GitHub HTTP ${res.status}: ${body}`);
  }
  return res.json();
}

async function fetchSubjects({ owner, repo, sha, prevTag, token }) {
  const base = `https://api.github.com/repos/${encodeURIComponent(owner)}/${encodeURIComponent(repo)}`;
  const ref = encodeURIComponent(sha);

  if (prevTag) {
    try {
      const data = await githubJson(`${base}/compare/${encodeURIComponent(prevTag)}...${ref}`, token);
      const commits = Array.isArray(data.commits) ? data.commits : [];
      const subjects = commits.map((c) => cleanSubject(c && c.commit && c.commit.message)).filter(Boolean);
      if (subjects.length) return subjects;
    } catch (e) {
      // Tag de release eliminado o commit fuera del historial: se cae al
      // listado simple de commits para no bloquear el build.
      console.error(`Aviso: compare con ${prevTag} falló (${e.message}); usando commits recientes.`);
    }
  }

  const data = await githubJson(`${base}/commits?sha=${ref}&per_page=10`, token);
  const commits = Array.isArray(data) ? data : [];
  return commits.map((c) => cleanSubject(c && c.commit && c.commit.message)).filter(Boolean);
}

function makeEntry({ version, build, sha, subjects, repoUrl, runId }) {
  const seen = new Set();
  const changes = [];
  const changesEn = [];
  for (const subject of subjects) {
    const key = toBullet(subject, 'es').toLowerCase();
    if (seen.has(key)) continue;
    seen.add(key);
    changes.push(toBullet(subject, 'es'));
    changesEn.push(toBullet(subject, 'en'));
    if (changes.length >= 8) break;
  }

  return {
    version: `v${version}-build${build}`,
    date: new Date().toISOString(),
    type: 'build',
    title: `Build ${build} · ${sha}`,
    title_en: `Build ${build} · ${sha}`,
    build,
    sha,
    ...(repoUrl && runId ? { url: `${repoUrl}/actions/runs/${runId}` } : {}),
    changes,
    changes_en: changesEn,
  };
}

async function main() {
  const rawVersion = readArg('--version') || process.env.APP_VERSION || process.env.VERSION;
  const version = String(rawVersion || '').replace(/^v/, '').trim();
  const build = Number(readArg('--build') || process.env.BUILD_SEQ || 0);
  const rawSha = readArg('--sha') || process.env.SHORT_SHA || String(process.env.GITHUB_SHA || '').slice(0, 7);
  const sha = String(rawSha || '').trim();
  const prevTag = String(readArg('--prev-tag') || '').trim();
  const subjectsFile = readArg('--subjects-file');
  const fallback = String(readArg('--fallback') || '').trim();
  const output = readArg('--output');
  const repository = String(readArg('--repo') || process.env.GITHUB_REPOSITORY || 'wilson360-labs/CodeHub');
  const [owner, repo] = repository.split('/');
  const token = process.env.GITHUB_TOKEN || process.env.GH_TOKEN || '';

  if (!/^\d+\.\d+(?:\.\d+)?$/.test(version)) {
    throw new Error(`Versión inválida "${rawVersion}" (esperado 7.72 o 7.72.1)`);
  }
  if (!Number.isSafeInteger(build) || build < 1) {
    throw new Error(`Build inválido "${build}" (debe ser entero positivo)`);
  }
  if (!sha) throw new Error('Falta --sha');

  let subjects = subjectsFile ? readSubjectsFile(subjectsFile) : [];
  if (!subjects.length && !subjectsFile) {
    subjects = await fetchSubjects({ owner, repo, sha, prevTag, token });
  }
  if (!subjects.length && fallback) subjects = [fallback];
  if (!subjects.length) subjects = [`Actualización de CodeHub ${version}`];

  const entry = makeEntry({
    version,
    build,
    sha,
    subjects,
    repoUrl: `https://github.com/${owner}/${repo}`,
    runId: process.env.GITHUB_RUN_ID,
  });
  const json = JSON.stringify(entry, null, 2) + '\n';

  if (output) {
    fs.writeFileSync(output, json);
    console.error(`✔ Entrada de changelog escrita en ${output} (${entry.changes.length} cambios).`);
  } else {
    process.stdout.write(json);
  }
}

main().catch((e) => { console.error('✖', e.message); process.exit(1); });
