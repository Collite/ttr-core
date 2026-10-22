// SPDX-License-Identifier: Apache-2.0
// Manual browser smoke against the Hartland checkout. Build the Designer and
// start vite preview, then run: node packages/designer/scripts/worker-world-smoke.mjs ../hartland http://127.0.0.1:5187/
// PW_EXECUTABLE overrides Chromium; OUT_DIR selects screenshot output.
import { chromium } from 'playwright-core';
import { existsSync, readdirSync, readFileSync, mkdirSync } from 'node:fs';
import { join, relative, resolve } from 'node:path';
import { homedir } from 'node:os';
if (!process.argv[2])
  throw new Error('Usage: worker-world-smoke.mjs <hartland-checkout> [viewer-url]');
const checkout = resolve(process.argv[2]);
const base = process.argv[3] ?? 'http://127.0.0.1:5187/';
const out = process.env.OUT_DIR ?? '/tmp/so-worker-smoke';
mkdirSync(out, { recursive: true });
function resolveExecutable() {
  if (process.env.PW_EXECUTABLE) return process.env.PW_EXECUTABLE;
  const cache = join(homedir(), 'Library/Caches/ms-playwright');
  const shells = existsSync(cache)
    ? readdirSync(cache)
        .filter((p) => p.startsWith('chromium_headless_shell-'))
        .sort((a, b) => Number(b.split('-')[1]) - Number(a.split('-')[1]))
    : [];
  for (const shell of shells) {
    for (const arch of ['mac-arm64', 'mac-x64', 'linux64']) {
      const path = join(cache, shell, `chrome-headless-shell-${arch}`, 'chrome-headless-shell');
      if (existsSync(path)) return path;
    }
  }
  const chrome = '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome';
  if (existsSync(chrome)) return chrome;
  throw new Error('Chromium unavailable; set PW_EXECUTABLE.');
}
const executablePath = resolveExecutable();
const browser = await chromium.launch({ executablePath, headless: true });
try {
  const page = await browser.newPage({ viewport: { width: 1400, height: 900 } });
  const errors = [];
  page.on('pageerror', (e) => errors.push(String(e)));
  const files = {};
  const walk = (dir) =>
    readdirSync(dir, { withFileTypes: true }).forEach((e) => {
      const p = join(dir, e.name);
      if (e.isDirectory()) walk(p);
      else if (/\.(ttrm|ttrg|ttrl)$/.test(p))
        files[relative(checkout, p)] = readFileSync(p, 'utf8');
    });
  walk(join(checkout, 'model'));
  files['modeler.toml'] = readFileSync(join(checkout, 'modeler.toml'), 'utf8');
  await page.route('**/samples/so-worker-smoke/**', (route) => {
    const p = decodeURIComponent(
      new URL(route.request().url()).pathname.split('/samples/so-worker-smoke/')[1]
    );
    if (p !== 'index.json' && !(p in files))
      return route.fulfill({ status: 404, body: 'Missing project file' });
    return route.fulfill({
      contentType: p === 'index.json' ? 'application/json' : 'text/plain',
      body: p === 'index.json' ? JSON.stringify(Object.keys(files)) : files[p],
    });
  });
  await page.goto(new URL('?demo=so-worker-smoke', base).href);
  await page.getByTestId('shell-frame').waitFor({ timeout: 60000 });
  const labels = await page.getByTestId('catalog-item').allTextContents();
  const checks = [];
  for (const label of ['er', 'md', 'er · sales', 'db · agent_hartland_db', 'er · agent_hartland']) {
    const item = page
      .getByTestId('catalog-item')
      .filter({ hasText: new RegExp('^' + label + '$') });
    await item.click();
    await page.waitForFunction(() => document.querySelectorAll('.react-flow__node').length > 0);
    await page.waitForTimeout(1500);
    checks.push({
      label,
      nodes: await page.locator('.react-flow__node').count(),
      edges: await page.locator('.react-flow__edge').count(),
      count: await page.getByTestId('objects-count').textContent(),
    });
    if (label === 'er · sales') await page.screenshot({ path: join(out, 'sales.png') });
  }
  // Verify the schema route also restores directly, without a catalog click.
  await page.goto(new URL('w/so-worker-smoke/s/md?demo=so-worker-smoke', base).href);
  await page.waitForFunction(
    () => document.querySelectorAll('.react-flow__node').length === 14,
    undefined,
    { timeout: 60000 }
  );
  console.log(
    JSON.stringify(
      { labels, checks, deepLinkNodes: await page.locator('.react-flow__node').count(), errors },
      null,
      2
    )
  );
  if (errors.length || checks.some((c) => c.nodes === 0 || c.edges === 0)) process.exitCode = 1;
} finally {
  await browser.close();
}
