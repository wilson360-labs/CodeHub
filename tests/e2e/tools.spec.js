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
    const search = page.locator('input[placeholder^="Buscar"], input[type="search"]').first();
    await expect(search).toBeVisible();
    const all = page.locator('#grid .card');
    const total = await all.count();
    expect(total).toBeGreaterThan(5);
    await search.fill('qr');
    const visible = page.locator('#grid .card:not(.hidden)');
    await expect(visible.first()).toBeVisible();
    const shown = await visible.count();
    expect(shown).toBeGreaterThan(0);
    expect(shown).toBeLessThan(total);
  });
});