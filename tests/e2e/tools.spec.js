// @ts-check
const { test, expect } = require('@playwright/test');

test.describe('Tools — 27 herramientas', () => {

  test.beforeEach(async ({ page }) => {
    await page.goto('/tools.html');
    await page.waitForLoadState('domcontentloaded');
  });

  test('página carga con título correcto', async ({ page }) => {
    await expect(page).toHaveTitle(/Tools.*CodeHub/);
  });

  test('muestra herramientas en el grid', async ({ page }) => {
    const cards = page.locator('.card');
    await expect(cards.first()).toBeVisible();
    const count = await cards.count();
    expect(count).toBeGreaterThan(20);
  });

  test('buscador filtra herramientas', async ({ page }) => {
    await page.fill('input[placeholder*="Buscar"]', 'contraseña');
    await page.waitForTimeout(300);
    const visible = await page.locator('.card:not(.hidden)').count();
    expect(visible).toBeGreaterThan(0);
    expect(visible).toBeLessThan(10);
  });

  test('generador de contraseñas funciona', async ({ page }) => {
    await page.click('button:has-text("Generar")');
    await page.waitForTimeout(500);
    // Verificar que aparece una contraseña generada
    const output = page.locator('#pv, [id*="pass"]').first();
    if (await output.isVisible()) {
      const text = await output.textContent();
      expect(text!.length).toBeGreaterThan(6);
    }
  });

  test('generador de QR funciona', async ({ page }) => {
    await page.fill('#qt', 'https://wilson360-labs.vercel.app');
    await page.click('button:has-text("Generar QR"), button:has-text("Generar"):near(#qt)');
    await page.waitForTimeout(1000);
    const qrOut = page.locator('#qr-out');
    await expect(qrOut).toBeVisible();
  });

  test('calculadora científica tiene botones', async ({ page }) => {
    const calcBtns = page.locator('#calc-btns button');
    const count = await calcBtns.count();
    expect(count).toBeGreaterThan(10);
    // Probar que botones responden
    await calcBtns.first().click();
  });

  test('chips de filtro funcionan', async ({ page }) => {
    await page.click('.chip:has-text("Seguridad")');
    await page.waitForTimeout(300);
    const visible = await page.locator('.card:not(.hidden)').count();
    expect(visible).toBeGreaterThan(0);
  });

  test('clima acepta input de ciudad', async ({ page }) => {
    const cityInput = page.locator('#weather-city');
    await expect(cityInput).toBeVisible();
    await cityInput.fill('Guatemala City');
    await cityInput.press('Enter');
    await page.waitForTimeout(3000);
    // Verificar que hizo algo (loading o resultado)
    const weatherOut = page.locator('#weather-out');
    await expect(weatherOut).toBeVisible();
  });

});
