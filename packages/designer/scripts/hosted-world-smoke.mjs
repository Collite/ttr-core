// SPDX-License-Identifier: Apache-2.0
// Production bundle + real worker inside a same-origin fake parent. No running BFF required.
// node packages/designer/scripts/hosted-world-smoke.mjs <hartland-checkout> [preview-url]
import { chromium } from 'playwright-core';
import { existsSync, readdirSync, readFileSync, mkdirSync } from 'node:fs';
import { join } from 'node:path';
import { homedir } from 'node:os';
import { snapshotFromCheckout } from './make-host-smoke-files.mjs';

if (!process.argv[2]) throw new Error('Usage: hosted-world-smoke.mjs <checkout> [preview-url]');
const snapshot = snapshotFromCheckout(process.argv[2]);
const base = process.argv[3] ?? 'http://127.0.0.1:5187/';
const out = process.env.OUT_DIR ?? '/tmp/so-hosted-smoke';
mkdirSync(out, { recursive: true });
const cache = join(homedir(), 'Library/Caches/ms-playwright');
const candidates = existsSync(cache)
  ? readdirSync(cache)
      .filter((p) => p.startsWith('chromium_headless_shell-'))
      .sort()
      .reverse()
      .flatMap((p) =>
        ['mac-arm64', 'mac-x64', 'linux64'].map((arch) =>
          join(cache, p, `chrome-headless-shell-${arch}`, 'chrome-headless-shell')
        )
      )
  : [];
const executablePath =
  process.env.PW_EXECUTABLE ??
  [...candidates, '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome'].find(existsSync);
if (!executablePath) throw new Error('Set PW_EXECUTABLE to a Chromium executable.');
const browser = await chromium.launch({ executablePath, headless: true });
try {
  const page = await browser.newPage({ viewport: { width: 1400, height: 900 } });
  const errors = [];
  const requests = [];
  let served = snapshot;
  if (process.env.TRACE_RPC)
    await page.addInitScript(() => {
      window.rpcTrace = [];
      const NativeWorker = window.Worker;
      window.Worker = class extends NativeWorker {
        constructor(...args) {
          super(...args);
          this.addEventListener('message', (e) => {
            if ('id' in e.data) rpcTrace.push({ response: e.data.id, at: performance.now() });
          });
        }
        postMessage(message) {
          rpcTrace.push({ method: message.method, id: message.id, at: performance.now() });
          super.postMessage(message);
        }
      };
    });
  page.on('pageerror', (e) => errors.push(String(e)));
  await page.route('**/host-smoke.html', (r) =>
    r.fulfill({
      contentType: 'text/html',
      body: readFileSync(new URL('./host-smoke.html', import.meta.url), 'utf8'),
    })
  );
  await page.route('**/api/model/**', (r) => {
    requests.push({
      path: new URL(r.request().url()).pathname,
      authorization: r.request().headers().authorization,
    });
    return r.fulfill({ contentType: 'application/json', body: JSON.stringify(served) });
  });
  await page.goto(new URL('host-smoke.html', base).href);
  await page.waitForFunction(() => Number.isFinite(window.readyMs), undefined, { timeout: 30000 });
  const readyMs = await page.evaluate(() => window.readyMs);
  const frame = page.frames().find((f) => f.url().includes('backend=host'));
  const labels = await frame.getByTestId('catalog-item').allTextContents();
  const initialLocation = await page.evaluate(
    () => messages.find((m) => m.type === 'ttr.frame.location')?.location
  );
  await page.evaluate(() => hostSend({ type: 'ttr.host.navigate', location: '/w/foreign/s/md' }));
  await frame.waitForFunction(() => document.querySelectorAll('.react-flow__node').length === 14);
  await frame.waitForTimeout(800);
  const md = {
    nodes: await frame.locator('.react-flow__node').count(),
    edges: await frame.locator('.react-flow__edge').count(),
  };
  const mdLink = await frame
    .getByTestId('copy-federation-link')
    .getAttribute('data-federation-url');
  await page.screenshot({ path: join(out, 'md.png') });
  await page.evaluate(() => hostSend({ type: 'ttr.host.navigate', location: '/w/hartland/s/er' }));
  await frame.waitForFunction(() => document.querySelectorAll('.react-flow__node').length === 36);
  const er = {
    nodes: await frame.locator('.react-flow__node').count(),
    edges: await frame.locator('.react-flow__edge').count(),
  };
  await frame
    .getByTestId('catalog-item')
    .filter({ hasText: /^er · sales$/ })
    .click();
  await frame.waitForFunction(() => document.querySelectorAll('.react-flow__node').length === 12);
  await frame.waitForTimeout(800);
  await page.screenshot({ path: join(out, 'sales.png') });
  const sales = {
    nodes: await frame.locator('.react-flow__node').count(),
    edges: await frame.locator('.react-flow__edge').count(),
  };
  await page.evaluate(() => {
    hostSend({ type: 'ttr.host.token', token: 'refreshed-smoke-only' });
    hostSend({ type: 'ttr.host.refresh' });
  });
  await page.waitForFunction(() =>
    messages.some((m) => m.type === 'ttr.frame.location' && m.location.includes('layout%3Asales'))
  );
  await page.waitForTimeout(200);
  const previousHeader = await frame.getByTestId('studio-header').elementHandle();
  served = { ...snapshot, sha: `${snapshot.sha}-refreshed` };
  await page.evaluate(
    (sha) => hostSend({ type: 'ttr.host.navigate', location: '/w/foreign/s/md', sha }),
    served.sha
  );
  await frame.waitForFunction((old) => !old.isConnected, previousHeader);
  await frame.waitForFunction(() => document.querySelectorAll('.react-flow__node').length === 14);
  const state = await frame.evaluate(() => ({
    path: location.pathname,
    hash: location.hash,
    height: getComputedStyle(document.getElementById('root')).height,
    scheme: getComputedStyle(document.getElementById('root')).colorScheme,
  }));
  const messages = await page.evaluate(() => window.messages);
  if (process.env.TRACE_RPC)
    console.log(JSON.stringify(await frame.evaluate(() => rpcTrace), null, 2));
  console.log(
    JSON.stringify(
      {
        sha: snapshot.sha,
        readyMs: Math.round(readyMs),
        labels,
        md,
        er,
        sales,
        initialLocation,
        mdLink,
        refreshedMdNodes: await frame.locator('.react-flow__node').count(),
        state,
        hostPath: new URL(page.url()).pathname,
        requests,
        messages,
        errors,
      },
      null,
      2
    )
  );
  if (
    readyMs > 3000 ||
    initialLocation !== '/w/hartland' ||
    mdLink !== `${new URL(base).origin}/m/hartland/s/md` ||
    requests.length !== 3 ||
    errors.length ||
    messages.some((m) => m.type === 'ttr.frame.error') ||
    requests.at(-1)?.authorization !== 'Bearer refreshed-smoke-only' ||
    md.edges !== 31 ||
    sales.edges !== 33
  )
    process.exitCode = 1;
} finally {
  await browser.close();
}
