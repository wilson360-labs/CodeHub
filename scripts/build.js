#!/usr/bin/env node
/**
 * Fase 0 (build aislado — preparado, NO conectado al index.html todavía).
 *
 * Minifica cada JS "hoja" declarado en ENTRY_FILES con esbuild,
 * le calcula un hash de contenido y lo escribe en dist/assets/ con nombre
 * versionado. Genera dist/build-manifest.json con la correspondencia
 * origen → destino para cablear el HTML en la Fase 0.1 definitiva.
 *
 * Diseñado para NO cambiar semántica: cada archivo se procesa por separado
 * (sin bundling), así el orden de carga y el alcance global quedan iguales.
 *
 * Uso: node scripts/build.js          (minifica todo /js + /widgets declarado)
 *      node scripts/build.js clean    (borra dist/)
 */
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');
const { transform } = require('esbuild');

const ROOT = process.cwd();
const OUT_DIR = path.join(ROOT, 'dist', 'assets');
const MANIFEST_PATH = path.join(ROOT, 'dist', 'build-manifest.json');

function listEntryFiles(dir) {
  const out = [];
  const rec = (d) => {
    for (const ent of fs.readdirSync(d, { withFileTypes: true })) {
      if (ent.name === '.') continue;
      const full = path.join(d, ent.name);
      if (ent.isDirectory()) rec(full);
      else if (ent.isFile() && /\.(?:js|mjs)$/.test(ent.name) && !/\.min\./.test(ent.name)) out.push(full);
    }
  };
  rec(path.join(ROOT, 'js'));
  rec(path.join(ROOT, 'widgets'));
  return out.filter((f) => !/vendor|emailjs/.test(path.relative(ROOT, f)));
}

async function main() {
  if (process.argv.includes('clean')) {
    fs.rmSync(path.join(ROOT, 'dist'), { recursive: true, force: true });
    console.log('dist/ limpiado.');
    return;
  }

  const files = listEntryFiles(ROOT);
  const manifest = { generatedAt: new Date().toISOString(), bundles: {} };

  let bytesIn = 0, bytesOut = 0;
  for (const file of files) {
    const code = fs.readFileSync(file, 'utf8');
    const result = await transform(code, {
      minify: true,
      target: 'es2020',
      legalComments: 'none',
      sourcemap: false,
    });
    if (result.errors && result.errors.length) {
      console.error(`✗ build error: ${file}`);
      result.errors.forEach((e) => console.error('  ', e.text));
      process.exitCode = 1;
      continue;
    }
    const hash = crypto.createHash('sha256').update(result.code).digest('hex').slice(0, 10);
    const rel = path.relative(ROOT, file).split(path.sep).join('/');
    const name = path.basename(file).replace(/\.(?:js|mjs)$/, '');
    const outRel = `assets/${rel.replace(/\.(?:js|mjs)$/, `.${hash}.min.js`)}`;
    const outFull = path.join(OUT_DIR, rel.replace(/\.(?:js|mjs)$/, `.${hash}.min.js`));
    fs.mkdirSync(path.dirname(outFull), { recursive: true });
    fs.writeFileSync(outFull, result.code);
    bytesIn += Buffer.byteLength(code);
    bytesOut += Buffer.byteLength(result.code);
    manifest.bundles[rel] = outRel;
  }

  fs.mkdirSync(path.dirname(MANIFEST_PATH), { recursive: true });
  fs.writeFileSync(MANIFEST_PATH, JSON.stringify(manifest, null, 2));
  const n = Object.keys(manifest.bundles).length;
  console.log(`build: ${n} archivos minificados`);
  console.log(`  ${(bytesIn / 1024).toFixed(0)} KB → ${(bytesOut / 1024).toFixed(0)} KB (${Math.round((1 - bytesOut / bytesIn) * 100)}% menos)`);
  console.log(`  manifest: dist/build-manifest.json`);
}

main();