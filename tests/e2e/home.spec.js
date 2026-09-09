const { test, expect } = require('@playwright/test');

test.describe('Home — shell PWA', () => {
  test('carga con título correcto y dock visible', async ({ page }) => {
    await page.goto('/');
    await expect(page).toHaveTitle(/CodeHub/);
    await expect(page.locator('#hub-dock')).toBeVisible();
    await expect(page.locator('header')).toBeVisible();
  });

  test('el tema alterna claro/oscuro via CodeHubTheme', async ({ page }) => {
    await page.goto('/');
    await page.evaluate(() => {
      if (!window.CodeHubTheme) throw new Error('CodeHubTheme no expuesto por theme-switcher.js');
      window.CodeHubTheme.toggle();
    });
    const dataTheme = await page.evaluate(() => document.documentElement.getAttribute('data-theme'));
    expect(['light', 'dark']).toContain(dataTheme);
  });

  test('manifest PWA servido correctamente', async ({ page }) => {
    await page.goto('/manifest.json');
    const text = await page.evaluate(() => document.body.innerText || '');
    expect(text).toContain('"name"');
  });
});