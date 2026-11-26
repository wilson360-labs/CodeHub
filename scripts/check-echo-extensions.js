'use strict';

// Read-only monitor of Echo extension metadata. It reports catalog changes and
// release notes; it never downloads, installs, or publishes plugin packages.
const fs = require('node:fs/promises');
const os = require('node:os');
const path = require('node:path');

const INDEX_URL = process.env.ECHO_EXTENSIONS_INDEX_URL ||
  'https://raw.githubusercontent.com/itsmechinmoy/echo-extensions/main/echo_extensions.json';
const LOCAL_CATALOG = path.join(__dirname, '..', 'backend', 'data', 'codebeat-extensions.json');
const OUTPUT = process.env.ECHO_REPORT_PATH || path.join(os.tmpdir(), 'echo-extensions-report.md');

async function getJson(url) {
  const headers = { 'User-Agent': 'CodeHub-Echo-Extension-Monitor/1.0', Accept: 'application/json' };
  if (process.env.GITHUB_TOKEN && new URL(url).hostname === 'api.github.com') headers.Authorization = 'Bearer ' + process.env.GITHUB_TOKEN;
  const response = await fetch(url, { headers, signal: AbortSignal.timeout(15000) });
  if (!response.ok) throw new Error('HTTP ' + response.status);
  return response.json();
}

function safe(value, max) {
  return String(value || '').replace(/\r/g, '')
    .replace(/\[(.*?)\]\(https?:\/\/[^)]+\)/g, '$1')
    .replace(/https?:\/\/\S+/gi, '[enlace omitido]')
    .replace(/\s+/g, ' ')
    .replace(/[*_>#]/g, '').trim().slice(0, max || 1000);
}

function releaseUrl(entry) {
  const value = String((entry && entry.updateUrl) || '');
  const allowed = /^https:\/\/api\.github\.com\/repos\/[\w.-]+\/[\w.-]+\/releases\/latest\/?$/i.test(value) ||
    /^https:\/\/gitlab\.com\/api\/v4\/projects\/.+\/releases\/permalink\/latest\/?$/i.test(value);
  return allowed ? value : null;
}

async function mapLimit(items, mapper) {
  const result = new Array(items.length);
  let next = 0;
  await Promise.all(Array.from({ length: Math.min(4, items.length) }, async function () {
    while (next < items.length) {
      const index = next++;
      result[index] = await mapper(items[index]);
    }
  }));
  return result;
}

async function inspectRelease(entry) {
  const url = releaseUrl(entry);
  if (!url) return { entry: entry, error: 'endpoint no compatible' };
  try {
    const release = await getJson(url);
    const notes = safe(release.body || release.description || 'Sin notas publicadas.');
    return {
      entry: entry,
      title: safe(release.name || release.tag_name || release.tagName || 'Release sin nombre', 120),
      date: release.published_at || release.released_at || release.created_at || '',
      notes: notes,
      fixes: /\b(fix|fixed|bug|crash|repair|correg|error|issue|stabiliz)/i.test(notes),
    };
  } catch (error) {
    return { entry: entry, error: error.message };
  }
}

async function main() {
  const pair = await Promise.all([
    getJson(INDEX_URL),
    fs.readFile(LOCAL_CATALOG, 'utf8').then(JSON.parse),
  ]);
  const upstream = pair[0];
  const local = pair[1];
  if (!Array.isArray(upstream) || !Array.isArray(local)) {
    throw new Error('Ambos catálogos deben ser listas JSON.');
  }

  const upstreamById = new Map(upstream.filter(function (item) { return item && item.id; })
    .map(function (item) { return [String(item.id), item]; }));
  const selected = local.filter(function (item) { return item && item.id; });
  const comparison = selected.map(function (item) {
    return { local: item, upstream: upstreamById.get(String(item.id)) };
  });
  const checked = await mapLimit(
    comparison.filter(function (item) { return item.upstream; })
      .map(function (item) { return item.upstream; }),
    inspectRelease
  );
  const releases = new Map(checked.map(function (item) {
    return [String(item.entry.id), item];
  }));
  const selectedIds = new Set(selected.map(function (item) { return String(item.id); }));
  const additions = upstream.filter(function (item) {
    return item && item.id && !selectedIds.has(String(item.id));
  });

  const lines = [
    '# Informe de extensiones Echo para CodeHub',
    '',
    'Generado: ' + new Date().toISOString(),
    '',
    'Índice comunitario: ' + upstream.length + ' entradas; catálogo CodeHub: ' + selected.length + '.',
    'Solo lectura: no descarga, instala ni publica extensiones.',
    '',
    '## Catálogo actual',
    '',
  ];

  for (const row of comparison) {
    const localItem = row.local;
    const upstreamItem = row.upstream;
    const name = safe(localItem.name || localItem.id, 100);
    if (!upstreamItem) {
      lines.push('### ' + name, '',
        '- No aparece en el índice externo (ID: ' + safe(localItem.id, 100) + ').', '');
      continue;
    }
    const release = releases.get(String(localItem.id));
    lines.push('### ' + name, '',
      '- Está en ambos índices (ID: ' + safe(localItem.id, 100) + ').');
    if (!release) {
      lines.push('- Release: no consultada.', '');
      continue;
    }
    if (release.error) {
      lines.push('- Release no consultable: ' + release.error + '.');
    } else {
      lines.push('- Release: ' + release.title);
      if (release.date) lines.push('- Fecha: ' + release.date);
      if (release.fixes) lines.push('- Las notas mencionan correcciones o estabilidad.');
      if (release.notes) lines.push('- Notas: ' + release.notes);
      if (String(localItem.updateUrl || '') !== String(upstreamItem.updateUrl || '')) {
        lines.push('- CodeHub usa otra fuente; esta release es solo informativa.');
      }
    }
    lines.push('');
  }

  lines.push('## Extensiones del índice fuera del catálogo CodeHub', '');
  if (!additions.length) lines.push('No hay entradas nuevas.');
  else additions.forEach(function (item) {
    lines.push('- ' + safe(item.name || item.id, 100) + ' (' + safe(item.type || 'sin tipo', 40) + ').');
  });
  lines.push('', 'Las releases detectadas son señales para revisión; no implican adopción automática.', '');

  const report = lines.join('\n');
  await fs.mkdir(path.dirname(OUTPUT), { recursive: true });
  await fs.writeFile(OUTPUT, report, 'utf8');
  if (process.env.GITHUB_STEP_SUMMARY) {
    await fs.appendFile(process.env.GITHUB_STEP_SUMMARY, report, 'utf8');
  }
  console.log('Informe generado: ' + OUTPUT);
}

main().catch(function (error) {
  console.error('No se pudo generar el informe: ' + error.message);
  process.exitCode = 1;
});