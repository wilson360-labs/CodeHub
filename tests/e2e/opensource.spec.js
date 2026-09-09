const { test, expect } = require('@playwright/test');

test.describe('OpenSource — catálogo', () => {
  test('opensource carga con buscador y contador de catálogo', async ({ page }) => {
    await page.goto('/pages/opensource.html');
    await expect(page.locator('#os-tools')).toBeVisible();
    await expect(page.locator('#os-search-input')).toBeVisible();
    await expect(page.locator('body')).toContainText('Open Source');
  });
});