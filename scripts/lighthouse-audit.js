#!/usr/bin/env node
/**
 * Auditoría Lighthouse (reporte no bloqueante) — Fase 0.2 de la roadmap.
 *
 * Levanta scripts/serve.js, corre Lighthouse en / una vez por modo
 * (mobile + desktop) usando Chromium de Playwright, compara los budgets
 * de rendimiento (LCP/CLS/TBT) y deja en lighthouse-out/:
 *   - mobile.html / desktop.html (reportes navegables)
 *   - summary.json + summary.md (métricas y veredicto por budget)
 *
 * Exit code: 0 siempre que se genere el reporte; 1 solo si la auditoría
 * falla técnica (sin Chrome/report). El workflow quality lo ejecuta con
 * continue-on-error: los budgets NO bloquean merge — son el reporte.
 *
 * Uso: node scripts/lighthouse-audit.js [--base http://localhost:4173] [--out dir]
 */
const fs = require('fs');
const path = require('path');
const { spawn } = require('child_process');
const chromeLauncher = require('chrome-launcher');
const lighthouseMod = require('lighthouse');
const lighthouse = typeof lighthouseMod === 'function' ? lighthouseMod : lighthouseMod.default;
const { chromium } = require('@playwright/test');

const ROOT = process.cwd();
const arg = (name, dflt) => {
  const i = process.argv.indexOf(name);
  return i > -1 && process.argv[i + 1] ? process.argv[i + 1] : dflt;
};
const BASE = arg('--base', process.env.BASE_URL || 'http://localhost:4173');
const OUT_DIR = arg('--out', process.env.OUT_DIR || path.join(ROOT, 'lighthouse-out'));
const CHROME = arg('--chrome', process.env.CHROME_PATH || '');
const PATH = arg('--path', '/');
const URL = BASE + PATH;

const BUDGETS = {
  firstContentfulPaint: { warn: 1800, error: 3000 },
  largestContentfulPaint: { warn: 2500, error: 4000 },
  totalBlockingTime: { warn: 200, error: 500 },
  cumulativeLayoutShift: { warn: 0.1, error: 0.25 },
  speedIndex: { warn: 3400, error: 5800 },
};

function lhConfig(mode, withBudgets) {
  const desktop = mode === 'desktop';
  return {
    extends: 'lighthouse:default',
    settings: {
      formFactor: desktop ? 'desktop' : 'mobile',
      screenEmulation: desktop
        ? { mobile: false, width: 1350, height: 940, deviceScaleFactor: 1 }
        : { mobile: true, width: 412, height: 823, deviceScaleFactor: 2.625 },
      throttling: desktop
        ? { rttMs: 40, throughputKbps: 10240, cpuSlowdownMultiplier: 1 }
        : { rttMs: 150, throughputKbps: 1638, cpuSlowdownMultiplier: 4 },
      maxWaitForLoad: 30_000,
      networkQuietThresholdMs: 2_000,
      cpuQuietThresholdMs: 2_000,
      // Bloques ads/trackers: jitter externo, no medimos latencia de ads.
      blockedUrlPatterns: [
        '*googletagmanager.com/*',
        '*googlesyndication.com/*',
        '*google-analytics.com/*',
        '*doubleclick.net/*',
        '*pagead2.google*',
        '*adservice.google*',
      ],
      disableFullPageScreenshot: true,
      budgets: withBudgets ? [
        { metric: 'firstContentfulPaint', budget: 1800 },
        { metric: 'largestContentfulPaint', budget: 2500 },
        { metric: 'totalBlockingTime', budget: 200 },
        { metric: 'cumulativeLayoutShift', budget: 0.1 },
        { metric: 'speedIndex', budget: 3400 },
      ] : undefined,
      onlyCategories: ['performance', 'accessibility', 'best-practices', 'seo'],
    },
  };
}

function verdict(numericValue, { warn, error }) {
  if (numericValue == null) return { status: 'N/A', value: '-' };
  if (error !== undefined && numericValue > error) return { status: 'ERROR', value: numericValue };
  if (warn !== undefined && numericValue > warn) return { status: 'WARN', value: numericValue };
  return { status: 'OK', value: numericValue };
}

const AUDIT_IDS = {
  firstContentfulPaint: 'first-contentful-paint',
  largestContentfulPaint: 'largest-contentful-paint',
  totalBlockingTime: 'total-blocking-time',
  cumulativeLayoutShift: 'cumulative-layout-shift',
  speedIndex: 'speed-index',
};

function startServer() {
  return new Promise((resolve, reject) => {
    const child = spawn(process.execPath, ['scripts/serve.js', '4173'], {
      cwd: ROOT,
      stdio: ['ignore', 'pipe', 'pipe'],
    });
    const timer = setTimeout(() => {
      child.kill();
      reject(new Error('serve.js no levanto en 10s'));
    }, 10_000);
    child.stdout.on('data', (d) => {
      if (/serving|listening|started|http/i.test(String(d))) {
        clearTimeout(timer);
        resolve(child);
      }
    });
    child.on('exit', () => reject(new Error('serve.js salio antes de levantar')));
  });
}

async function runLh(port, mode, withBudgets) {
  let lastErr;
  for (let attempt = 1; attempt <= 3; attempt++) {
    try {
      return await lighthouse(URL, {
        port,
        output: 'html',
        logLevel: 'error',
        outputPath: path.join(OUT_DIR, `${mode}.html`),
      }, lhConfig(mode, withBudgets));
    } catch (err) {
      lastErr = err;
      console.warn(`w ${mode}: intento ${attempt} fallo (${err.message})`);
      if (attempt < 3) await new Promise((r) => setTimeout(r, 1500));
    }
  }
  throw lastErr;
}

async function auditMode(port, mode) {
  let runner;
  try {
    runner = await runLh(port, mode, true);
  } catch (err) {
    console.warn(`w ${mode}: budgets fallaron (${err.message}) — reintento sin budgets`);
    runner = await runLh(port, mode, false);
  }
  const lhr = runner && runner.lhr;
  if (!lhr || !lhr.audits) throw new Error(`sin lhr para ${mode}`);
  if (lhr.runtimeError) throw new Error(`${mode}: ${lhr.runtimeError.message}`);

  const perf = (lhr.categories.performance.score || 0) * 100;
  const acc = (lhr.categories.accessibility && lhr.categories.accessibility.score || 0) * 100;
  const bp = (lhr.categories['best-practices'] && lhr.categories['best-practices'].score || 0) * 100;

  const metrics = {};
  for (const [name, cfg] of Object.entries(BUDGETS)) {
    const audio = lhr.audits[AUDIT_IDS[name]];
    metrics[name] = { value: audio ? audio.numericValue : null, ...verdict(audio ? audio.numericValue : null, cfg) };
  }
  return { mode, perf, acc, bp, metrics };
}

async function main() {
  const watchdog = setTimeout(() => {
    console.error('x Error: tiempo global agotado (780s) en lighthouse-audit');
    try {
      fs.writeFileSync(path.join(OUT_DIR, 'error.log'), 'tiempo global agotado (780s)\n');
    } catch (err) {}
    process.exit(2);
  }, 780_000);
  fs.mkdirSync(OUT_DIR, { recursive: true });

  let server = null;
  try {
    server = await startServer();
  } catch (e) {
    console.error('x ' + e.message);
    process.exit(1);
  }

  const chromePath = CHROME || chromium.executablePath();
  if (!fs.existsSync(chromePath)) {
    server.kill();
    console.error('x Chromium de Playwright no instalado — ejecuta: npx playwright install chromium');
    process.exit(1);
  }

  try {
    const results = [];
    for (const mode of ['mobile', 'desktop']) {
      let r = null;
      for (let attempt = 1; attempt <= 3 && !r; attempt++) {
        let ch;
        try {
          ch = await chromeLauncher.launch({
            chromePath,
            chromeFlags: ['--headless=new', '--no-sandbox', '--disable-gpu', '--disable-dev-shm-usage'],
          });
          r = await auditMode(ch.port, mode);
        } catch (e) {
          console.warn(`w ${mode} intento ${attempt}/3: ${e.message}`);
          await new Promise((res) => setTimeout(res, 2000));
        } finally {
          if (ch) { try { await ch.kill(); } catch (e) {} }
        }
      }
      if (!r) throw new Error(`${mode}: se agotaron 3 intentos`);
      results.push(r);
      console.log(`[${r.mode}] perf=${r.perf} a11y=${r.acc} b-p=${r.bp}`);
      for (const [name, m] of Object.entries(r.metrics)) {
        const fmt = typeof m.value === 'number' ? (name === 'cumulativeLayoutShift' ? m.value.toFixed(3) + ' ' : Math.round(m.value) + 'ms ') : m.value;
        console.log(`   ${name.padEnd(26)} ${m.status.padEnd(5)} ${fmt}`);
      }
    }

    const summary = { generatedAt: new Date().toISOString(), url: URL, results };
    fs.writeFileSync(path.join(OUT_DIR, 'summary.json'), JSON.stringify(summary, null, 2) + '\n');

    // summary.md legible en el artifact
    const lines = ['# Lighthouse — reporte CodeHub'];
    for (const r of results) {
      lines.push(`\n## ${r.mode}`);
      lines.push(`- performance: ${r.perf}`);
      lines.push(`- accessibility: ${r.acc}`);
      lines.push(`- best-practices: ${r.bp}`);
      for (const [n, m] of Object.entries(r.metrics)) lines.push(`- ${n}: ${m.status} (${m.value})`);
    }
    fs.writeFileSync(path.join(OUT_DIR, 'summary.md'), lines.join('\n') + '\n');

    console.log(`\nreporte en ${path.relative(ROOT, OUT_DIR)}/ (mobile.html, desktop.html, summary.json/md)`);
    clearTimeout(watchdog);
  } finally {
    if (server) server.kill();
  }
}

main().catch((e) => {
  const msg = (e && e.stack ? e.stack : String(e));
  console.error('x ' + msg);
  try {
    fs.writeFileSync(path.join(OUT_DIR, 'error.log'), msg + '\n');
  } catch (err) {}
  process.exit(1);
});

process.on('unhandledRejection', (e) => {
  console.warn('w unhandledRejection (se ignora; el reintento por modo lo cubre): ' + (e && e.message ? e.message : e));
});