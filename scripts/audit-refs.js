#!/usr/bin/env node
/**
 * Usado por: .github/workflows/quality.yml
 * Audita que toda referencia local (src/href) en los HTML exista,
 * y que los assets precacheados (sw.js PRECACHE) y el manifest aún existan.
 * Exit 0 = todo bien, 1 = hay referencias rotas.
 */
const fs = require('fs');
const path = require('path');

const ROOT = process.cwd();
const SKIP = /^(node_modules|\.git|apk|dist)/;
const EXT_SUFFIX = /\.(?:js|css|png|jpe?g|webp|svg|gif|ico|json|woff2?|ttf|html?|pdf|mp3|mp4|txt|webmanifest)$/i;

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

function exists(ref) {
  try { fs.accessSync(ref); return true; } catch { return false; }
}

const missing = new Set();
let checked = 0;

function checkRef(fromFile, ref) {
  if (/^(?:https?:)?\/\//.test(ref) || /^(?:data:|mailto:|tel:|#)/.test(ref)) return;
  const clean = ref.split(/[?#]/)[0].trim();
  if (!clean || clean.endsWith('.php')) return;
  let full;
  if (clean.startsWith('/')) full = path.join(ROOT, clean.replace(/^\//, '').split('/').join(path.sep));
  else full = path.resolve(path.dirname(fromFile), clean.split('/').join(path.sep));
  checked++;
  if (!exists(full)) missing.add(`${path.relative(ROOT, fromFile)}  =>  ${ref}`);
}

for (const file of walk(ROOT)) {
  const html = fs.readFileSync(file, 'utf8');
  const re = /(?:src|href)=["']([^"'\s?#]+)/g;
  let m;
  while ((m = re.exec(html))) {
    const ref = m[1];
    if (EXT_SUFFIX.test(ref)) checkRef(file, ref);
  }
  const cssUrl = /url\(['"]?([^'")\s]+)/g;
  while ((m = cssUrl.exec(html))) {
    const ref = m[1];
    if (EXT_SUFFIX.test(ref)) checkRef(file, ref);
  }
}

// sw.js PRECACHE
const swPath = path.join(ROOT, 'sw.js');
if (fs.existsSync(swPath)) {
  const sw = fs.readFileSync(swPath, 'utf8');
  const precacheBlock = (sw.match(/const PRECACHE\s*=\s*\[([\s\S]*?)\]/) || [])[1];
  if (precacheBlock) {
    for (const m of precacheBlock.matchAll(/['"](\/[^'"]+)['"]/g)) {
      const p = m[1];
      let full;
      if (/\.(?:html|css|js|json|woff2?|ttf|png|svg|webp|webmanifest)$/.test(p)) {
        full = path.join(ROOT, p.replace(/^\//, '').split('/').join(path.sep));
        checked++;
        if (!exists(full)) missing.add(`sw.js PRECACHE  =>  ${p}`);
      }
    }
  }
}

// manifest.json
const manPath = path.join(ROOT, 'manifest.json');
if (fs.existsSync(manPath)) {
  try {
    const man = JSON.parse(fs.readFileSync(manPath, 'utf8'));
    const icons = man.icons || [];
    for (const ic of icons) { if (ic.src) checkRef('manifest.json', ic.src); }
    for (const key of ['start_url', 'scope']) {
      const v = man[key];
      if (typeof v === 'string' && v.startsWith('/')) checkRef('manifest.json', v);
    }
  } catch { /* manifest audit no es bloqueante */ }
}

if (missing.size === 0) {
  console.log(`audit-refs: ${checked} referencias locales OK — ALL LOCAL REFS OK`);
  process.exit(0);
} else {
  console.error(`audit-refs: ${checked} referencias, ${missing.size} ROTAS:`);
  for (const m of [...missing].sort()) console.error(`  ✗ ${m}`);
  process.exit(1);
}