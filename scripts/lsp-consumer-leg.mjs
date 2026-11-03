// SPDX-License-Identifier: Apache-2.0
// One leg of the lsp family consumer smoke. The four packed tarballs are installed with npm or pnpm
// (LSP_PACKAGE_MANAGER) in a fresh folder outside the workspace, then used the way their hosts use
// them: runtime imports, strict declarations, the stdio server (editor hosts) and the browser worker
// in a real Chromium Worker (the Studio). No workspace links, aliases or worker plugin.
import assert from 'node:assert/strict';
import {
  mkdtempSync,
  mkdirSync,
  writeFileSync,
  readFileSync,
  rmSync,
  realpathSync,
  copyFileSync,
} from 'node:fs';
import { execFileSync } from 'node:child_process';
import { createServer } from 'node:http';
import { join, resolve } from 'node:path';
import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';
const repo = fileURLToPath(new URL('../', import.meta.url));
const require = createRequire(join(repo, 'package.json'));
const { chromium } = require('playwright-core');
const artifacts = resolve(process.argv[2] ?? 'build/lsp-family');
const out = resolve(process.argv[3] ?? 'build/lsp-consumer');
mkdirSync(out, { recursive: true });
const receipt = JSON.parse(readFileSync(join(artifacts, 'manifest.json'), 'utf8'));
const scratch = realpathSync(mkdtempSync(join(process.env.TMPDIR ?? '/tmp', 'ttr-lsp-consumer-')));
const manager = process.env.LSP_PACKAGE_MANAGER ?? 'npm';
const hostDependencies = { '@types/node': '22', typescript: '5.9.3' };
const npmEnv = { ...process.env, npm_config_userconfig: join(scratch, 'empty.npmrc') };
let browser, server;
try {
  writeFileSync(join(scratch, 'empty.npmrc'), '');
  if (manager === 'pnpm') {
    mkdirSync(join(scratch, 'vendor'));
    const specs = Object.fromEntries(
      receipt.artifacts.map((a) => {
        copyFileSync(join(artifacts, a.file), join(scratch, 'vendor', a.file));
        return [a.name, 'file:vendor/' + a.file];
      })
    );
    writeFileSync(
      join(scratch, 'package.json'),
      JSON.stringify({
        name: 'external-lsp-pnpm-smoke',
        type: 'module',
        private: true,
        packageManager: 'pnpm@11.1.3',
        dependencies: { ...hostDependencies, ...specs },
      })
    );
    // pnpm 11 ignores package.json `pnpm.overrides`; the family's internal edges take the same tarballs.
    writeFileSync(
      join(scratch, 'pnpm-workspace.yaml'),
      'overrides:\n' +
        Object.entries(specs)
          .map(([name, spec]) => '  ' + JSON.stringify(name) + ': ' + JSON.stringify(spec))
          .join('\n') +
        '\n'
    );
    execFileSync('pnpm', ['install', '--ignore-scripts', '--registry=https://registry.npmjs.org'], {
      cwd: scratch,
      stdio: 'inherit',
      env: npmEnv,
    });
  } else {
    writeFileSync(
      join(scratch, 'package.json'),
      JSON.stringify({ name: 'external-lsp-smoke', type: 'module', private: true })
    );
    execFileSync(
      'npm',
      [
        'install',
        '--ignore-scripts',
        '--no-audit',
        '--no-fund',
        '--registry=https://registry.npmjs.org',
        ...receipt.artifacts.map((a) => join(artifacts, a.file)),
        ...Object.entries(hostDependencies).map(([name, version]) => name + '@' + version),
      ],
      { cwd: scratch, stdio: 'inherit', env: npmEnv }
    );
  }
  const installed = createRequire(join(scratch, 'package.json'));

  writeFileSync(
    join(scratch, 'consume.mjs'),
    `import assert from 'node:assert/strict';
import { parseString } from '@tatrman/parser';import * as layout from '@tatrman/parser/layout';
import { renderDataType } from '@tatrman/lsp';import { synthesize, resolveManifest } from '@tatrman/edit';
import * as tokens from '@tatrman/tokens';
assert.equal(parseString('model er\\ndef entity customer {}').errors.length,0);
assert.equal(typeof renderDataType,'function');assert.equal(typeof synthesize,'function');
for(const p of [layout,tokens])assert.ok(Object.keys(p).length>0);
resolveManifest(undefined,'');`
  );
  execFileSync(process.execPath, ['consume.mjs'], { cwd: scratch, stdio: 'inherit' });

  writeFileSync(
    join(scratch, 'stdio-probe.mjs'),
    readFileSync(join(repo, 'scripts/lsp-stdio-probe.mjs'))
  );
  execFileSync(process.execPath, ['stdio-probe.mjs'], {
    cwd: scratch,
    stdio: 'inherit',
    timeout: 20_000,
  });

  // Every subpath an installed tarball declares types for gets a type-only import, so a new or
  // broken one is checked without editing this list (`./browser.worker.js` declares none).
  const typedSubpaths = receipt.artifacts.flatMap((a) => {
    // Read from node_modules: the packages do not export ./package.json.
    const { exports = {} } = JSON.parse(
      readFileSync(join(scratch, 'node_modules', a.name, 'package.json'), 'utf8')
    );
    return Object.entries(exports)
      .filter(([, target]) => typeof target === 'object' && target?.types)
      .map(([subpath]) => a.name + subpath.slice(1));
  });
  assert.ok(typedSubpaths.includes('@tatrman/parser/layout'), JSON.stringify(typedSubpaths));
  assert.ok(typedSubpaths.includes('@tatrman/lsp/browser'), JSON.stringify(typedSubpaths));
  writeFileSync(
    join(scratch, 'type-probe.ts'),
    `import { parseString } from '@tatrman/parser';
import { renderDataType } from '@tatrman/lsp';import { createServerConnection } from '@tatrman/lsp/server';
import { synthesize } from '@tatrman/edit';import * as tokens from '@tatrman/tokens';
import * as layout from '@tatrman/parser/layout';import type * as browserEntry from '@tatrman/lsp/browser';
const errors: number = parseString('model er').errors.length;
const doc: layout.LayoutDocument | undefined = undefined;
const write: typeof layout.writeTtrg = layout.writeTtrg;
const entry: typeof browserEntry | undefined = undefined;
void errors;void renderDataType;void createServerConnection;void synthesize;void tokens;void doc;void write;void entry;
` +
      typedSubpaths
        .map((s, i) => `import type * as t${i} from '${s}';\nexport type T${i} = typeof t${i};`)
        .join('\n') +
      '\n'
  );
  execFileSync(
    process.execPath,
    [
      installed.resolve('typescript/bin/tsc'),
      '--noEmit',
      '--strict',
      '--module',
      'NodeNext',
      '--moduleResolution',
      'NodeNext',
      '--target',
      'ES2022',
      join(scratch, 'type-probe.ts'),
    ],
    { cwd: scratch, stdio: 'inherit' }
  );

  // The Studio loads the worker file the tarball ships; serve exactly that file to a module Worker.
  const workerFile = installed.resolve('@tatrman/lsp/browser.worker.js');
  server = createServer((req, res) => {
    const worker = req.url === '/worker.js';
    res.setHeader('Content-Type', worker ? 'text/javascript' : 'text/html');
    res.end(worker ? readFileSync(workerFile) : '<!doctype html><title>lsp worker smoke</title>');
  });
  await new Promise((r) => server.listen(0, '127.0.0.1', r));
  browser = await chromium.launch({
    headless: true,
    executablePath:
      process.env.CHROME_BIN ??
      (process.platform === 'darwin'
        ? '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome'
        : undefined),
  });
  const page = await browser.newPage();
  const errors = [];
  page.on('pageerror', (e) => errors.push(String(e)));
  // A dedicated worker's console reaches the page's console event (ConsoleMessage.worker()).
  page.on('console', (m) => {
    if (m.type() === 'error') errors.push(`console.error: ${m.text()}`);
  });
  await page.goto(`http://127.0.0.1:${server.address().port}/`);
  const worker = await page.evaluate(async () => {
    const worker = new Worker('/worker.js', { type: 'module' });
    const pending = new Map();
    // Anything that went wrong without failing a request: worker errors (also after the last reply)
    // and Error-level window/logMessage or window/showMessage (how the server reports a failing
    // notification handler). The server's other messages are listed in the report.
    const problems = [];
    const serverMessages = [];
    let id = 0;
    worker.onmessage = ({ data }) => {
      if (data.method !== undefined) {
        serverMessages.push(data.method);
        if (
          (data.method === 'window/logMessage' || data.method === 'window/showMessage') &&
          data.params?.type === 1
        )
          problems.push(`${data.method}: ${data.params.message}`);
        // A server request must not settle a client request; answer it so the server is not left waiting.
        if (data.id !== undefined)
          worker.postMessage({
            jsonrpc: '2.0',
            id: data.id,
            error: { code: -32601, message: `smoke client does not handle ${data.method}` },
          });
        return;
      }
      if (!pending.has(data.id)) return;
      const { resolve, reject, timer } = pending.get(data.id);
      pending.delete(data.id);
      clearTimeout(timer);
      if (data.error) reject(new Error(JSON.stringify(data.error)));
      else resolve(data.result);
    };
    worker.onerror = (event) => {
      problems.push(`worker error: ${event.message}`);
      for (const p of pending.values()) p.reject(new Error(event.message));
    };
    const request = (method, params) =>
      new Promise((resolve, reject) => {
        const key = ++id;
        const timer = setTimeout(
          () => reject(new Error(`Worker request timed out: ${method}`)),
          20000
        );
        pending.set(key, { resolve, reject, timer });
        worker.postMessage({ jsonrpc: '2.0', id: key, method, params });
      });
    const notify = (method, params) => worker.postMessage({ jsonrpc: '2.0', method, params });
    try {
      const init = await request('initialize', {
        processId: null,
        rootUri: null,
        capabilities: {},
      });
      const root = 'file:///lsp-smoke';
      await request('modeler/setProjectRoot', { projectRoot: root, manifest: '' });
      notify('textDocument/didOpen', {
        textDocument: {
          uri: `${root}/model/customer.ttrm`,
          languageId: 'ttr',
          version: 1,
          text: 'package demo\nmodel er\ndef entity customer { attributes: [def attribute id { type: int }] }',
        },
      });
      const symbols = await request('modeler/listSymbols', { kind: 'entity', limit: 50 });
      // A late error (a timer, a deferred handler) surfaces within this window, before terminate.
      await new Promise((r) => setTimeout(r, 500));
      return {
        capabilities: Object.keys(init.capabilities ?? {}).length,
        entities: symbols.symbols.map((s) => s.qname),
        serverMessages,
        problems,
      };
    } finally {
      worker.terminate();
    }
  });
  assert.ok(worker.capabilities > 0, JSON.stringify(worker));
  assert.ok(
    worker.entities.some((q) => q.endsWith('customer')),
    JSON.stringify(worker)
  );
  assert.deepEqual(worker.problems, [], JSON.stringify(worker));
  assert.deepEqual(errors, []);
  const result = {
    manager,
    artifacts: receipt,
    worker,
    externalTypes: 'Strict NodeNext consumer passed against installed package declarations',
    typedSubpaths,
    packages: `${receipt.artifacts.length} packed tarballs installed with ${manager} outside the workspace. No workspace links, aliases or worker plugin.`,
  };
  writeFileSync(join(out, 'report.json'), JSON.stringify(result, null, 2) + '\n');
  console.log(JSON.stringify({ manager, worker }, null, 2));
} finally {
  await browser?.close();
  await new Promise((r) => (server ? server.close(r) : r()));
  rmSync(scratch, { recursive: true, force: true });
}
