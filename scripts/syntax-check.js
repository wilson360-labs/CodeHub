#!/usr/bin/env node
/**
 * Usado por: .github/workflows/quality.yml
 * Valida la sintaxis de todos los JS del repo con `node --check` real.
 * Archivos con `import/export` top-level se validan como ESM.
 * Exit 0 = todo bien, 1 = hay errores.
 */
const { execFileSync } = require('child_process');
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
    else if (ent.isFile() && /\.(?:js|mjs|cjs)$/.test(ent.name) && !/\.min\./.test(ent.name)) out.push(full);
  }
  return out;
}

let errors = 0, checked = 0;
const nodeBin = process.env.NODE || process.execPath;
for (const file of walk(ROOT)) {
  const code = fs.readFileSync(file, 'utf8');
  const isEsm = /\.mjs$/.test(file) || /^\s*(import|export)\s/m.test(code);
  try {
    if (isEsm) {
      execFileSync(nodeBin, ['--check', '--input-type=module'], { stdio: 'pipe', input: code });
    } else {
      execFileSync(nodeBin, ['--check', file], { stdio: 'pipe' });
    }
    checked++;
  } catch (e) {
    errors++;
    const msg = (e.stderr || e.message || '').toString().split('\n').slice(0, 3).join(' | ');
    console.error(`✗ ${path.relative(ROOT, file)}: ${msg}`);
  }
}
console.log(`syntax-check: ${checked} OK de ${checked + errors} archivos, ${errors} errores.`);
process.exit(errors ? 1 : 0);