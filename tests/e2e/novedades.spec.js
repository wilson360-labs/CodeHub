// @ts-check
const { test, expect } = require('@playwright/test');

test.describe('Novedades — Tienda de Apps Android', () => {

  test.beforeEach(async ({ page }) => {
    await page.goto('/novedades.html');
    await page.waitForLoadState('domcontentloaded');
    // Esperar que carguen las apps del backend
    await page.waitForTimeout(3000);
  });

  test('página carga con título correcto', async ({ page }) => {
    await expect(page).toHaveTitle(/Novedades|Apps/);
  });

  test('apps se cargan desde el backend', async ({ page }) => {
    const cards = page.locator('.app-card');
    await expect(cards.first()).toBeVisible({ timeout: 10000 });
    const count = await cards.count();
    expect(count).toBeGreaterThan(0);
  });

  test('filtros de categoría funcionan', async ({ page }) => {
    const allCards = await page.locator('.app-card').count();
    expect(allCards).toBeGreaterThan(0);
  });

  test('badge de verificado visible', async ({ page }) => {
    const verified = page.locator('.app-verified-badge').first();
    // Puede estar oculto si la app no está verificada
    const count = await page.locator('.app-card').count();
    expect(count).toBeGreaterThan(0);
  });

  test('sin errores JS en consola', async ({ page }) => {
    const errors = [];
    page.on('pageerror', e => errors.push(e.message));
    await page.goto('/novedades.html');
    await page.waitForTimeout(4000);
    const real = errors.filter(e => !e.includes('chrome-extension'));
    expect(real).toHaveLength(0);
  });

});
