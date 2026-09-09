#!/usr/bin/env node
/**
 * Usado por: .github/workflows/quality.yml
 * Valida la sintaxis de todos los <script> inline de los HTML del repo.
 * Omite scripts con type="application/ld+json|application/json|text/template".
 * Exit 0 = todo bien, 1 = hay errores.
 */
const fs = require('fs');
const path = require('path');

const ROOT = process.cwd();
const SKIP = /^(node_modules|\.git|apk|dist)/;

function walk(dir) {
  const out = [];
  for (const ent of fs.readdirSync(dir, { withFileTypes: true })) {
    if (ent.name === '.') continue;
    const full = path.join(dir, ent.name);
    if (SKIP.test(ent.name)) continue;
    if (ent.isDirectory()) out.push(...walk(full));
    else if (/\.html?$/.test(ent.name)) out.push(full);
  }
  return out;
}

const re = /<script(?![^>]*\bsrc=)[^>]*>([\s\S]*?)<\/script>/gi;
const SKIP_TYPE = /type\s*=\s*"(?:application\/ld\+json|application\/json|text\/template)"/i;

let files = 0, blocks = 0, skipped = 0, bad = 0;
for (const file of walk(ROOT)) {
  const html = fs.readFileSync(file, 'utf8');
  let m;
  while ((m = re.exec(html)) !== null) {
    const attrs = m[0];
    if (SKIP_TYPE.test(attrs)) { skipped++; continue; }
    blocks++;
    try {
      new Function(m[1]); // no se ejecuta, solo parsea
    } catch (e) {
      bad++;
      console.error(`✗ ${path.relative(ROOT, file)}: ${e.message}`);
    }
  }
  files++;
}
console.log(`check-inline: ${files} archivos, ${blocks} bloques inline, ${skipped} omitidos (JSON/template), ${bad} errores.`);
process.exit(bad ? 1 : 0);