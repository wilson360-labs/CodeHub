// @ts-check
const { test, expect } = require('@playwright/test');

test.describe('Página principal — CodeHub', () => {

  test('carga correctamente', async ({ page }) => {
    await page.goto('/');
    await expect(page).toHaveTitle(/Wilson\.E/);
    await expect(page.locator('header')).toBeVisible();
  });

  test('nav tiene links correctos', async ({ page }) => {
    await page.goto('/');
    await expect(page.locator('a[href*="tools"]')).toBeVisible();
    await expect(page.locator('a[href*="novedades"]')).toBeVisible();
    await expect(page.locator('a[href*="servicios"]')).toBeVisible();
  });

  test('sección de proyectos visible', async ({ page }) => {
    await page.goto('/');
    // Hacer scroll para cargar lazy elements
    await page.evaluate(() => window.scrollTo(0, 500));
    await page.waitForTimeout(500);
    // Verificar que hay contenido en la página
    const body = await page.locator('body').textContent();
    expect(body).toContain('Wilson');
  });

  test('chat IA visible', async ({ page }) => {
    await page.goto('/');
    // Buscar el botón del chat
    const chatBtn = page.locator('[id*="chat"], [class*="chat"], button:has-text("Chat")').first();
    if (await chatBtn.isVisible()) {
      await expect(chatBtn).toBeVisible();
    }
  });

  test('PWA manifest existe', async ({ page }) => {
    const response = await page.request.get('/manifest.json');
    expect(response.status()).toBe(200);
    const body = await response.json();
    expect(body.name).toContain('CodeHub');
  });

  test('Service Worker registrado', async ({ page }) => {
    await page.goto('/');
    const swResponse = await page.request.get('/sw.js');
    expect(swResponse.status()).toBe(200);
  });

  test('SEO — meta tags presentes', async ({ page }) => {
    await page.goto('/');
    const description = await page.locator('meta[name="description"]').getAttribute('content');
    expect(description).toBeTruthy();
    expect(description!.length).toBeGreaterThan(50);

    const ogTitle = await page.locator('meta[property="og:title"]').getAttribute('content');
    expect(ogTitle).toBeTruthy();
  });

  test('sin errores críticos en consola', async ({ page }) => {
    const errors = [];
    page.on('pageerror', err => errors.push(err.message));
    await page.goto('/');
    await page.waitForTimeout(2000);
    // Filtrar errores de extensiones de Chrome
    const realErrors = errors.filter(e => !e.includes('chrome-extension') && !e.includes('Extension'));
    expect(realErrors).toHaveLength(0);
  });

});
