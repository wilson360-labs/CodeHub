#!/usr/bin/env node

const fs = require('fs');
const path = require('path');

const ROOT = process.cwd();
const args = new Set(process.argv.slice(2));

const requiredFiles = [
  'index.html',
  'manifest.json',
  'sw.js',
  'pages/opensource.html',
  'pages/tools.html',
  'backend/server.js',
  'backend/package.json',
  'css/index.css',
  'css/opensource.css',
  'js/opensource.js'
];

function fail(message) {
  console.error(`❌ ${message}`);
  return false;
}

function ok(message) {
  console.log(`✅ ${message}`);
  return true;
}

function readJson(filePath) {
  try {
    return JSON.parse(fs.readFileSync(path.join(ROOT, filePath), 'utf8'));
  } catch (error) {
    throw new Error(`JSON inválido en ${filePath}: ${error.message}`);
  }
}

async function checkHealthEndpoint() {
  const url = 'https://codehub-98s6.onrender.com/api/health';
  try {
    const res = await fetch(url, { cache: 'no-store' });
    if (!res.ok) {
      throw new Error(`HTTP ${res.status}`);
    }
    const data = await res.json();
    ok(`Health endpoint responde bien: ${url}`);
    if (data && data.status) {
      ok(`Backend status: ${data.status}`);
    }
    return true;
  } catch (error) {
    return fail(`No se pudo validar el health endpoint: ${error.message}`);
  }
}

async function runAudit() {
  let checks = 0;
  let errors = 0;

  for (const file of requiredFiles) {
    const full = path.join(ROOT, file);
    checks += 1;
    if (!fs.existsSync(full)) {
      errors += 1;
      fail(`Archivo requerido faltante: ${file}`);
      continue;
    }
    ok(`Archivo presente: ${file}`);
  }

  try {
    const manifest = readJson('manifest.json');
    checks += 1;
    if (manifest.display !== 'standalone') {
      errors += 1;
      fail('manifest.json no usa display standalone');
    } else {
      ok('manifest.json en modo standalone');
    }

    if (!manifest.start_url) {
      errors += 1;
      fail('manifest.json sin start_url');
    } else {
      ok('manifest.json tiene start_url');
    }
  } catch (error) {
    errors += 1;
    fail(error.message);
  }

  const indexHtml = fs.readFileSync(path.join(ROOT, 'index.html'), 'utf8');
  checks += 1;
  if (!indexHtml.includes('rel="manifest"') || !indexHtml.includes('beforeinstallprompt')) {
    errors += 1;
    fail('index.html no incluye manifiesto PWA o prompt de instalación');
  } else {
    ok('index.html tiene soporte de instalación PWA');
  }

  const swJs = fs.readFileSync(path.join(ROOT, 'sw.js'), 'utf8');
  checks += 1;
  if (!swJs.includes('navigator.serviceWorker') && !swJs.includes('self.addEventListener(\'fetch\'')) {
    errors += 1;
    fail('sw.js no parece estar configurado para PWA/offline');
  } else {
    ok('sw.js tiene estrategia de caché y offline');
  }

  const opensource = fs.readFileSync(path.join(ROOT, 'pages/opensource.html'), 'utf8');
  checks += 1;
  if (!opensource.includes('to-top-btn') || !opensource.includes('Open Source')) {
    errors += 1;
    fail('pages/opensource.html no incluye CTA de navegación / estructura del catálogo');
  } else {
    ok('pages/opensource.html tiene CTA base y estructura del catálogo');
  }

  const backendPkg = readJson('backend/package.json');
  checks += 1;
  if (!backendPkg.dependencies || !backendPkg.dependencies.express) {
    errors += 1;
    fail('backend/package.json no tiene Express configurado');
  } else {
    ok('backend/package.json válido para API Node');
  }

  console.log(`\nResumen: ${checks} comprobaciones, ${errors} errores.`);
  if (errors > 0) {
    process.exitCode = 1;
  }
}

(async () => {
  if (args.has('--health')) {
    await checkHealthEndpoint();
    return;
  }

  if (args.has('--install')) {
    try {
      const manifest = readJson('manifest.json');
      const installReady = !!(manifest.display === 'standalone' && manifest.start_url && manifest.icons && manifest.icons.length);
      if (installReady) {
        ok('Instalación PWA lista para usuarios: manifest + standalone + iconos');
      } else {
        fail('PWA no está lista para instalar');
        process.exitCode = 1;
      }
      return;
    } catch (error) {
      fail(error.message);
      process.exitCode = 1;
      return;
    }
  }

  await runAudit();
})();
