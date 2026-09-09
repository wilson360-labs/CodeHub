const { test, expect } = require('@playwright/test');

test.describe('Home — shell PWA', () => {
  test('carga con título correcto y dock visible', async ({ page }) => {
    await page.goto('/');
    await expect(page).toHaveTitle(/CodeHub/);
    await expect(page.locator('#hub-dock')).toBeVisible();
    await expect(page.locator('header')).toBeVisible();
  });

  test('cabecera permite alternar tema claro/oscuro', async ({ page }) => {
    await page.goto('/');
    const toggle = page.locator('#theme-toggle');
    await expect(toggle).toBeVisible();
    const before = await page.evaluate(() => document.documentElement.getAttribute('data-theme') || document.body.dataset.theme || '');
    await toggle.click();
    await page.waitForTimeout(250);
    const after = await page.evaluate(() => document.documentElement.getAttribute('data-theme') || document.body.dataset.theme || '');
    expect(after).not.toBe(before);
  });

  test('manifest PWA servido correctamente', async ({ page }) => {
    await page.goto('/manifest.json');
    const manifest = await page.evaluate(() => ({ name: document.querySelector('pre, body') && document.body.innerText }));
    expect((manifest.name || '').length).toBeGreaterThan(0);
  });
});