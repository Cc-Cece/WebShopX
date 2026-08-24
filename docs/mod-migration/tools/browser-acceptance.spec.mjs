import { test, expect } from '@playwright/test';
import { createHash } from 'node:crypto';
import { mkdir, writeFile } from 'node:fs/promises';
import path from 'node:path';

const base = process.env.WEBSHOPX_BROWSER_BASE ?? 'http://127.0.0.1:8123';
const output = process.env.WEBSHOPX_BROWSER_EVIDENCE ?? 'build/reports/mod-migration/browser';

async function capture(page, id, expectedText) {
  const consoleErrors = [];
  page.on('console', message => {
    if (message.type() === 'error' || message.type() === 'warning') {
      consoleErrors.push(`${message.type()}: ${message.text()}`);
    }
  });
  page.on('pageerror', error => consoleErrors.push(`pageerror: ${error.message}`));
  const response = await page.goto(`${base}${id === 'landing' ? '/' : '/health'}`, {
    waitUntil: 'networkidle'
  });
  expect(response?.status()).toBe(200);
  const visibleText = await page.locator('body').innerText();
  expect(visibleText).toContain(expectedText);
  expect(consoleErrors).toEqual([]);
  const screenshot = await page.screenshot({ fullPage: true });
  const screenshotFile = path.join(output, `${id}.png`);
  await writeFile(screenshotFile, screenshot);
  const evidence = {
    schemaVersion: 1,
    id,
    url: page.url(),
    title: await page.title(),
    status: response?.status(),
    visibleText,
    consoleErrors,
    screenshot: path.basename(screenshotFile),
    screenshotSha256: createHash('sha256').update(screenshot).digest('hex')
  };
  await writeFile(path.join(output, `${id}.json`), `${JSON.stringify(evidence, null, 2)}\n`);
}

function recordConsoleFailures(page) {
  const failures = [];
  page.on('console', message => {
    if (message.type() === 'error' || message.type() === 'warning') {
      failures.push(`${message.type()}: ${message.text()}`);
    }
  });
  page.on('pageerror', error => failures.push(`pageerror: ${error.message}`));
  return failures;
}

async function saveJourney(page, id, consoleErrors, checkpoints) {
  const screenshot = await page.screenshot({ fullPage: true });
  const screenshotFile = path.join(output, `${id}.png`);
  await writeFile(screenshotFile, screenshot);
  const evidence = {
    schemaVersion: 1,
    id,
    url: page.url(),
    title: await page.title(),
    visibleText: await page.locator('body').innerText(),
    checkpoints,
    consoleErrors,
    screenshot: path.basename(screenshotFile),
    screenshotSha256: createHash('sha256').update(screenshot).digest('hex')
  };
  await writeFile(path.join(output, `${id}.json`), `${JSON.stringify(evidence, null, 2)}\n`);
}

async function playerLogin(page, username, password) {
  await page.goto(`${base}/account`, { waitUntil: 'networkidle' });
  await page.getByTestId('account-username').locator('input').fill(username);
  await page.getByTestId('account-password').locator('input').fill(password);
  await page.getByTestId('account-sign-in').click();
}

async function adminLogin(page, username, password) {
  await page.getByTestId('admin-username').locator('input').fill(username);
  await page.getByTestId('admin-password').locator('input').fill(password);
  await page.getByTestId('admin-sign-in').click();
}

test.beforeAll(async () => mkdir(output, { recursive: true }));

test('landing is visibly ready without console errors', async ({ page }) => {
  await capture(page, 'landing', 'WebShopX');
});

test('health is visibly UP without console errors', async ({ page }) => {
  await capture(page, 'health', '"status":"UP"');
});

test('authenticated shop, cart and admin journey is visibly functional', async ({ page }) => {
  const consoleErrors = recordConsoleFailures(page);
  const checkpoints = [];

  await playerLogin(page, 'BrowserAdmin', 'browser-secret');
  await expect(page.getByTestId('account-authenticated')).toContainText('BrowserAdmin');
  checkpoints.push('player-login');

  await page.goto(`${base}/shop`, { waitUntil: 'networkidle' });
  await expect(page.getByTestId('shop-product-BROWSER_STONE')).toContainText(
    'Browser Acceptance Stone');
  await page.getByTestId('shop-buy-BROWSER_STONE').click();
  await expect(page.getByTestId('shop-add-to-cart')).toBeVisible();
  const cartAdded = page.waitForResponse(response =>
    response.url().includes('/api/cart/lines/add') && response.request().method() === 'POST');
  await page.getByTestId('shop-add-to-cart').click();
  expect((await cartAdded).status()).toBe(200);
  await expect(page.getByTestId('shop-add-to-cart')).toBeHidden();
  checkpoints.push('official-product-added-to-cart');

  await page.goto(`${base}/cart`, { waitUntil: 'networkidle' });
  await expect(page.getByText('Browser Acceptance Stone', { exact: false }).first()).toBeVisible();
  checkpoints.push('cart-restored-from-backend');

  await page.goto(`${base}/admin/market`, { waitUntil: 'networkidle' });
  if (await page.getByTestId('admin-sign-in').count()) {
    await adminLogin(page, 'BrowserAdmin', 'browser-secret');
  }
  await expect(page.getByTestId('admin-unknown-supply')).toBeVisible();
  await expect(page.getByTestId('admin-unknown-supply')).toContainText(/Unknown supply|未知/);
  await expect(page.locator('body')).not.toContainText('did not return a valid list');
  checkpoints.push('admin-market-and-unknown-supply-control');

  expect(consoleErrors).toEqual([]);
  await saveJourney(page, 'critical-journeys', consoleErrors, checkpoints);
});

test('non-admin receives a visible authorization rejection', async ({ page }) => {
  const consoleErrors = recordConsoleFailures(page);
  await playerLogin(page, 'BrowserViewer', 'viewer-secret');
  await expect(page.getByTestId('account-authenticated')).toContainText('BrowserViewer');
  await page.goto(`${base}/admin/market`, { waitUntil: 'networkidle' });
  await adminLogin(page, 'BrowserViewer', 'viewer-secret');
  await expect(page.getByTestId('admin-sign-in')).toBeVisible();
  await expect(page.locator('body')).toContainText(/permission|权限|管理员/);
  expect(consoleErrors.some(message => message.includes('403 (Forbidden)'))).toBe(true);
  expect(consoleErrors.filter(message => !message.includes('403 (Forbidden)'))).toEqual([]);
  await saveJourney(
    page,
    'admin-permission-denied',
    consoleErrors,
    ['player-login', 'admin-permission-denied']);
});
