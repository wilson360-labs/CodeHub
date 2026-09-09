#!/usr/bin/env node
/**
 * Fase 0.1 — build esbuild (preparado para cablear index.html).
 *
 * Minifica cada JS "hoja" de /js y /widgets con esbuild y escribe en
 * dist/assets/ un archivo con hash de contenido. Las entradas que hagan
 * imports relativos (módulos, p.ej. js/morphicons-init.js) se BUNDLEAN para
 * que sus dependencias queden inline y no se rompan al vivir en dist/assets/.
 *
 * Genera dist/build-manifest.json con la correspondencia origen -> destino
 * usada por scripts/wire-build.js para reescribir index.html.
 *
 * Uso: node scripts/build.js          (minifica todo /js + /widgets declarado)
 *      node scripts/build.js clean    (borra dist/)
 */
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');
const esbuild = require('esbuild');

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

const HAS_RELATIVE_IMPORT = /(?:^|\n)\s*import[^;]*from\s+['"]\.\.?\//;
const HAS_DYNAMIC_IMPORT = /import\(['"]\.\.?\//;

async function compileOne(code, file) {
  if (HAS_RELATIVE_IMPORT.test(code) || HAS_DYNAMIC_IMPORT.test(code)) {
    const result = await esbuild.build({
      entryPoints: [file],
      bundle: true,
      format: 'esm',
      target: 'es2020',
      minify: true,
      legalComments: 'none',
      write: false,
    });
    const out = result.outputFiles?.[0];
    if (!out) throw new Error('sin output en bundle');
    return out.text;
  }
  const result = await esbuild.transform(code, {
    minify: true,
    target: 'es2020',
    legalComments: 'none',
    sourcemap: false,
  });
  if (result.errors && result.errors.length) throw new Error(result.errors.map((e) => e.text).join('; '));
  return result.code;
}

async function main() {
  if (process.argv.includes('clean')) {
    fs.rmSync(path.join(ROOT, 'dist'), { recursive: true, force: true });
    console.log('dist/ limpiado.');
    return;
  }

  fs.rmSync(OUT_DIR, { recursive: true, force: true });
  fs.mkdirSync(OUT_DIR, { recursive: true });

  const files = listEntryFiles(ROOT);
  const manifest = { generatedAt: new Date().toISOString(), bundles: {} };

  let bytesIn = 0, bytesOut = 0;
  let failed = false;
  for (const file of files) {
    const code = fs.readFileSync(file, 'utf8');
    let outCode;
    try {
      outCode = await compileOne(code, file);
    } catch (err) {
      console.error(`x build error: ${path.relative(ROOT, file)}`);
      console.error('   ', err.message);
      failed = true;
      continue;
    }
    const hash = crypto.createHash('sha256').update(outCode).digest('hex').slice(0, 10);
    const rel = path.relative(ROOT, file).split(path.sep).join('/');
    const outRel = `assets/${rel.replace(/\.(?:js|mjs)$/, `.${hash}.min.js`)}`;
    const outFull = path.join(OUT_DIR, rel.replace(/\.(?:js|mjs)$/, `.${hash}.min.js`));
    fs.mkdirSync(path.dirname(outFull), { recursive: true });
    fs.writeFileSync(outFull, outCode);
    bytesIn += Buffer.byteLength(code);
    bytesOut += Buffer.byteLength(outCode);
    manifest.bundles[rel] = outRel;
  }

  fs.mkdirSync(path.dirname(MANIFEST_PATH), { recursive: true });
  fs.writeFileSync(MANIFEST_PATH, JSON.stringify(manifest, null, 2));
  const n = Object.keys(manifest.bundles).length;
  if (failed || !n) {
    console.error('build fallido: ' + (failed ? 'errores de transform/bundle' : 'sin bundles'));
    process.exit(1);
  }
  console.log(`build: ${n} archivos minificados`);
  console.log(`  ${(bytesIn / 1024).toFixed(0)} KB -> ${(bytesOut / 1024).toFixed(0)} KB (${Math.round((1 - bytesOut / bytesIn) * 100)}% menos)`);
  console.log(`  manifest: dist/build-manifest.json`);
}

main();