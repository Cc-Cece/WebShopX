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

test.beforeAll(async () => mkdir(output, { recursive: true }));

test('landing is visibly ready without console errors', async ({ page }) => {
  await capture(page, 'landing', 'WebShopX');
});

test('health is visibly UP without console errors', async ({ page }) => {
  await capture(page, 'health', '"status":"UP"');
});
