import { describe, it, expect, beforeAll } from 'vitest';
import fs from 'fs';
import path from 'path';
import { fileURLToPath } from 'url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const ROOT = path.resolve(__dirname, '..');

let home;
beforeAll(() => { home = fs.readFileSync(path.join(ROOT, 'index.html'), 'utf8'); });

describe('Página principal — CodeHub (estático)', () => {

  it('tiene título con la marca Wilson.E', () => {
    expect(home).toMatch(/<title>[^<]*Wilson\.E/);
  });

  it('meta description con más de 50 caracteres', () => {
    const m = home.match(/name="description"\s+content="([^"]+)"/);
    expect(m).toBeTruthy();
    expect(m[1].length).toBeGreaterThan(50);
  });

  it('og:title presente', () => {
    expect(home).toMatch(/property="og:title"\s+content="[^"]+"/);
  });

  it('enlaza manifest.json y lo referencia en el PWA manifest', () => {
    expect(home).toContain('manifest.json');
    const manifest = JSON.parse(fs.readFileSync(path.join(ROOT, 'manifest.json'), 'utf8'));
    expect(manifest.name).toContain('CodeHub');
  });

  it('registra el Service Worker (sw.js)', () => {
    expect(home).toContain('sw.js');
    const sw = fs.readFileSync(path.join(ROOT, 'sw.js'), 'utf8');
    expect(sw.length).toBeGreaterThan(500);
  });

  it('nav incluye links a tools, opensource y servicios', () => {
    expect(home).toContain('tools.html');
    expect(home).toContain('opensource.html');
    expect(home).toContain('servicios.html');
  });

  it('incluye el chat con IA', () => {
    expect(home).toMatch(/id="chat|chat-trigger|openChat|chatToggle/i);
  });

  it('los assets locales referenciados existen', () => {
    const refs = [...home.matchAll(/(?:src|href)="((?:css|js)\/[^"]+)"/g)].map(m => m[1]);
    expect(refs.length).toBeGreaterThan(0);
    for (const ref of new Set(refs)) {
      expect(fs.existsSync(path.join(ROOT, ref)), `falta ${ref}`).toBe(true);
    }
  });

  it('no apunta al backend de Railway caído', () => {
    expect(home).not.toContain('codehub-production-729d');
  });

});
