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
const lighthouse = require('lighthouse');
const { chromium } = require('@playwright/test');

const ROOT = process.cwd();
const arg = (name, dflt) => {
  const i = process.argv.indexOf(name);
  return i > -1 && process.argv[i + 1] ? process.argv[i + 1] : dflt;
};
const BASE = arg('--base', process.env.BASE_URL || 'http://localhost:4173');
const OUT_DIR = arg('--out', process.env.OUT_DIR || path.join(ROOT, 'lighthouse-out'));
const URL = BASE + '/';

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

async function auditMode(port, mode) {
  let runner;
  try {
    runner = await lighthouse(URL, {
      port,
      output: 'html',
      logLevel: 'error',
      outputPath: path.join(OUT_DIR, `${mode}.html`),
    }, lhConfig(mode, true));
  } catch (err) {
    console.warn(`w ${mode}: budgets fallaron (${err.message}) — reintento sin budgets`);
    try {
      runner = await lighthouse(URL, {
        port,
        output: 'html',
        logLevel: 'error',
        outputPath: path.join(OUT_DIR, `${mode}.html`),
      }, lhConfig(mode, false));
    } catch (err2) {
      throw new Error(`${mode}: ${err2.message}`);
    }
  }
  const lhr = runner && runner.lhr;
  if (!lhr || !lhr.audits) throw new Error(`sin lhr para ${mode}`);
  if (lhr.runtimeError) throw new Error(`${mode}: ${lhr.runtimeError.message}`);

  const perf = (lhr.categories.performance.score || 0) * 100;
  const acc = (lhr.categories.accessibility && lhr.categories.accessibility.score || 0) * 100;
  const bp = (lhr.categories['best-practices'] && lhr.categories['best-practices'].score || 0) * 100;

  const metrics = {};
  for (const [name, cfg] of Object.entries(BUDGETS)) {
    const audio = lhr.audits[name];
    metrics[name] = { value: audio ? audio.numericValue : null, ...verdict(audio ? audio.numericValue : null, cfg) };
  }
  return { mode, perf, acc, bp, metrics };
}

async function main() {
  fs.mkdirSync(OUT_DIR, { recursive: true });

  let server = null;
  try {
    server = await startServer();
  } catch (e) {
    console.error('x ' + e.message);
    process.exit(1);
  }

  const chromePath = chromium.executablePath();
  if (!fs.existsSync(chromePath)) {
    server.kill();
    console.error('x Chromium de Playwright no instalado — ejecuta: npx playwright install chromium');
    process.exit(1);
  }

  let launcher;
  try {
    launcher = await chromeLauncher.launch({
      chromePath,
      chromeFlags: ['--headless=new', '--no-sandbox', '--disable-gpu', '--disable-dev-shm-usage'],
    });

    const results = [];
    for (const mode of ['mobile', 'desktop']) {
      const r = await auditMode(launcher.port, mode);
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
  } finally {
    if (launcher) await launcher.kill().catch(() => {});
    if (server) server.kill();
  }
}

main().catch((e) => { console.error('x ' + (e && e.stack ? e.stack : e)); process.exit(1); });