#!/usr/bin/env node
/**
 * Fase 0.1 — cablea index.html a los bundles hasheados de esbuild.
 *
 * Lee dist/build-manifest.json (generado por scripts/build.js) y reescribe
 * en index.html el src de cada <script> local a su versión minificada con
 * hash:  js/script.js?v=...  ->  /dist/assets/js/script.<hash>.min.js
 *
 * Reglas:
 *  - Solo toca scripts locales (js/, widgets/, /js/, /widgets/).
 *  - Los .min (vendor/anime.min.js, emailjs.min.js) y CDN NO se tocan.
 *  - Preserva atributos (defer, module, async) — solo cambia el src.
 *  - Idempotente: una segunda corrida no vuelve a reescribir ya-cableados.
 *
 * Además sincroniza sw.js de forma determinista y CRLF-safe: regenera la
 * sección JS del PRECACHE = (entradas js/widgets originales, menos las de
 * morphicons que ya viven embebidas en el bundle de morphicons-init.js) +
 * (bundles hasheados del manifest). Bumpea VERSION solo si algo cambió.
 *
 * Uso: node scripts/wire-build.js
 */
const fs = require('fs');
const path = require('path');

const ROOT = process.cwd();
const MANIFEST_PATH = path.join(ROOT, 'dist', 'build-manifest.json');
const INDEX_PATH = path.join(ROOT, 'index.html');
const SW_PATH = path.join(ROOT, 'sw.js');

const SRC_RE = /(<script\b[^>]*\bsrc=")([^"]*)("[^>]*>)/g;
const LOCAL_PREFIX = /^(?:\/)?(js|widgets)\//;

function clean(src) {
  return src.replace(/\?.*$/, '');
}

function splitLines(text) {
  const eol = text.includes('\r\n') ? '\r\n' : '\n';
  return { eol, lines: text.split(/\r?\n/) };
}

function syncSw(swText, distRefs) {
  const { eol, lines } = splitLines(swText);
  const startIdx = lines.findIndex((l) => l.includes("'/js/script.js',"));
  const endIdx = lines.findIndex((l) => l.includes("'/data/roadmap.json',"));
  if (startIdx < 0 || endIdx < 0) {
    console.error('sw.js: no encontre el bloque JS del PRECACHE (marcadores js/script.js y data/roadmap.json)');
    process.exit(1);
  }

  const jsLines = lines.slice(startIdx, endIdx);
  const keptJs = jsLines.filter((l) => {
    const t = l.trim();
    return t !== "'/js/vendor/morphicons/controller-CXZuwJ_M.js',"
      && t !== "'/js/vendor/morphicons/dom.js',"
      && t !== "'/js/vendor/morphicons/index.js',"
      && t !== "'/js/vendor/morphicons/normalize-CYnN3Npw.js',"
      && t !== "'/js/vendor/morphicons/spring-CFHloqPP.js',"
      && t !== "'/js/vendor/morphicons/element.js',"
      && t !== "'/js/morphicons-init.js',"
      && !t.startsWith("'/dist/")
      && !t.startsWith('// dist/');
  });

  const allDist = Array.from(new Set(distRefs)).sort();
  const newJs = [
    ...keptJs,
    '  // dist/ (esbuild Fase 0.1 - bundles hasheados que index.html carga)',
    ...allDist.map((u) => `  '${u}',`),
  ];
  const newLines = [...lines.slice(0, startIdx), ...newJs, ...lines.slice(endIdx)];
  let doc = newLines.join(eol);

  const original = swText.replace(/\r/g, '').replace(/\n/g, '\n');
  const changed = doc.replace(/\r/g, '').replace(/\n/g, '\n') !== original;

  let next = null;
  if (changed) {
    const vM = doc.match(/const VERSION = 'codehub-v(\d+\.\d+)';/);
    if (!vM) { console.error('sw.js: no encontre VERSION'); process.exit(1); }
    next = 'codehub-v' + (Number(vM[1]) + 0.01).toFixed(2);
    doc = doc.replace(vM[0], `const VERSION = '${next}';`);
  } else {
    next = null;
  }

  return { text: doc, next, dist: allDist.length };
}

function main() {
  if (!fs.existsSync(MANIFEST_PATH)) {
    console.error('Falta dist/build-manifest.json — ejecuta primero: npm run build');
    process.exit(1);
  }
  const manifest = JSON.parse(fs.readFileSync(MANIFEST_PATH, 'utf8'));
  const bundles = manifest.bundles || {};

  let html = fs.readFileSync(INDEX_PATH, 'utf8');
  const distRefs = new Set();
  let count = 0;

  html = html.replace(SRC_RE, (whole, pre, src, post) => {
    if (!LOCAL_PREFIX.test(src)) return whole;
    if (/\.min\.[^?]*/.test(src)) return whole;
    const rel = clean(src).replace(/^\//, '');
    if (!bundles[rel]) return whole;
    const target = '/dist/' + bundles[rel];
    if (src === target) return whole;
    distRefs.add(target);
    count++;
    return pre + target + post;
  });

  fs.writeFileSync(INDEX_PATH, html);

  // Ref de dist para el PRECACHE: TODOS los bundles del manifest
  // (index.html es la unica pagina cableada en Fase 0.1; el resto del
  // js original queda en precache porque otras paginas lo siguen usando).
  const allDist = Object.values(bundles).map((u) => '/dist/' + u);

  const sw = fs.readFileSync(SW_PATH, 'utf8');
  const res = syncSw(sw, allDist);
  fs.writeFileSync(SW_PATH, res.text);

  console.log(`index.html: ${count} scripts cableados a /dist/assets/`);
  if (res.next) {
    console.log(`sw.js: VERSION -> ${res.next}, PRECACHE con ${res.dist} bundles dist`);
  } else {
    console.log(`sw.js: sin cambios (ya sincronizado, PRECACHE con ${res.dist} bundles dist)`);
  }
}

main();