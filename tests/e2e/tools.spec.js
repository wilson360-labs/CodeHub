const { test, expect } = require('@playwright/test');

test.describe('Tools — grid de herramientas', () => {
  test('tools carga y muestra el grid principal', async ({ page }) => {
    await page.goto('/pages/tools.html');
    await expect(page.locator('#grid')).toBeVisible();
    const cards = await page.locator('#grid > *').count();
    expect(cards).toBeGreaterThan(0);
  });

  test('el campo de búsqueda de tools filtra resultado', async ({ page }) => {
    await page.goto('/pages/tools.html');
    const search = page.locator('#search, #searchInput, .tools-search, input[type="search"]').first();
    await expect(search).toBeVisible();
    await search.fill('qr');
    await expect(page.locator('#grid > *').first()).toBeVisible();
  });
});