import { describe, it, expect, beforeAll } from 'vitest';
import fs from 'fs';
import path from 'path';
import { fileURLToPath } from 'url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const ROOT = path.resolve(__dirname, '..');

let tools;
beforeAll(() => { tools = fs.readFileSync(path.join(ROOT, 'pages/tools.html'), 'utf8'); });

describe('Tools — herramientas web (estático)', () => {

  it('título correcto', () => {
    expect(tools).toMatch(/<title>[^<]*Herramientas/);
  });

  it('grid con más de 20 tarjetas de herramientas', () => {
    const cards = (tools.match(/class="card"/g) || []).length;
    expect(cards).toBeGreaterThan(20);
  });

  it('buscador de herramientas presente', () => {
    expect(tools).toMatch(/placeholder="[^"]*Buscar herramienta/);
  });

  it('chips de filtro por categoría presentes', () => {
    expect(tools).toMatch(/filterTag\('seg'/);
    expect(tools).toMatch(/filterTag\('util'/);
  });

  it('generador de contraseñas tiene botón y salida', () => {
    expect(tools).toContain('id="pv"');
    expect(tools).toMatch(/onclick="genPass\(\)"/);
  });

  it('generador de QR tiene salida', () => {
    expect(tools).toContain('id="qr-out"');
  });

  it('calculadora científica tiene contenedor de botones', () => {
    expect(tools).toContain('id="calc-btns"');
  });

  it('clima acepta ciudad', () => {
    expect(tools).toContain('id="weather-city"');
  });

  it('los assets locales referenciados existen', () => {
    const refs = [...tools.matchAll(/(?:src|href)="(\.\.\/(?:css|js)\/[^"]+)"/g)].map(m => m[1].replace(/^\.\.\//, ''));
    expect(refs.length).toBeGreaterThan(0);
    for (const ref of new Set(refs)) {
      expect(fs.existsSync(path.join(ROOT, ref)), `falta ${ref}`).toBe(true);
    }
  });

});
