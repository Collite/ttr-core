// SPDX-License-Identifier: Apache-2.0
// Real Hartland input through the built stdio entry and an actual Chromium Worker.
// Build LSP/dependencies first; node packages/lsp/scripts/studio-edit-smoke.mjs ../hartland
// PW_EXECUTABLE overrides the installed Chrome executable. No checkout files are changed.
import assert from 'node:assert/strict';
import { spawn, execFileSync } from 'node:child_process';
import { readFileSync } from 'node:fs';
import { resolve, join } from 'node:path';
import { pathToFileURL } from 'node:url';
import { createServer } from 'node:http';
import * as lsp from 'vscode-languageserver/node.js';
import { chromium } from 'playwright-core';

const checkout = resolve(process.argv[2] ?? '../hartland');
const codeRoot = resolve(import.meta.dirname, '../../..');
const vector = join(codeRoot, 'packages/edit/__tests__/synth/vectors/insert-entity/on-query');
const command = JSON.parse(readFileSync(join(vector, 'op.json'), 'utf8'));
const query = readFileSync(join(vector, 'world/model/er/returns-review.ttrm'), 'utf8');
const manifest = readFileSync(join(checkout, 'modeler.toml'), 'utf8');
const paths = execFileSync('git', ['ls-files', '-z', '--', 'model/'], { cwd: checkout, encoding: 'utf8' }).split('\0')
  .filter(p => /\.ttr[mg]$/.test(p) && !p.startsWith('model/layouts/') || /^model\/tests\/.+\.mjs$/.test(p));
const files = Object.fromEntries(paths.map(p => [p, readFileSync(join(checkout, p), 'utf8')]));
files['model/er/returns-review.ttrm'] = query;

// This function is also serialized into Chromium: use only JSON-RPC + plain JS.
async function exercise(request, notify, root, manifest, files, command, node) {
  const check = (ok, detail) => { if (!ok) throw new Error(detail); };
  await request('initialize', { processId: null, rootUri: node ? root : null, capabilities: {} });
  if (!node) await request('modeler/setProjectRoot', { projectRoot: root, manifest });
  const inputs = node ? { 'model/er/returns-review.ttrm': files['model/er/returns-review.ttrm'] } : files;
  for (const [path, text] of Object.entries(inputs)) await notify('textDocument/didOpen', {
    textDocument: { uri: `${root}/${path}`, languageId: path.endsWith('.mjs') ? 'javascript' : 'ttr', version: 1, text },
  });
  await request('modeler/setProjectRoot', { projectRoot: root, manifest });
  const before = await request('modeler/listSymbols', { kind: 'entity', limit: 5000 });
  const dry = await request('modeler/edit', { ...command, dryRun: true });
  check(dry.ok, JSON.stringify(dry));
  check(JSON.stringify(await request('modeler/listSymbols', { kind: 'entity', limit: 5000 })) === JSON.stringify(before), 'dry run mutated symbols');
  const result = await request('modeler/edit', command);
  check(JSON.stringify(result) === JSON.stringify(dry), 'dry/real result differs');
  check(result.touched.length === 4, 'expected entity, binding and two lexicons');
  check(!result.diagnostics.some(d => d.severity === 1), JSON.stringify(result.diagnostics));
  const after = await request('modeler/listSymbols', { kind: 'entity', limit: 5000 });
  check(after.symbols.some(s => s.qname === 'hartland.er.entity.returns_review'), 'new entity missing');
  const detail = await request('modeler/getSymbolDetail', { qname: 'hartland.er.entity.returns_review' });
  check(detail !== null, 'new entity source is not readable');
  const duplicate = await request('modeler/edit', command);
  check(!duplicate.ok && duplicate.code === 'entity-exists', 'cache did not retain edit');
  // Hartland's original JS rosters must be present without opening them in Node.
  const removal = await request('modeler/edit', { op: 'remove-definition', args: { qname: 'hartland.er.entity.return_request' } });
  check(!removal.ok && removal.code === 'would-orphan' && removal.refs.some(r => r.kind === 'roster'), 'missing roster guard');
  check(after.total === before.total + 1, 'expected exactly one new entity');
  return { edit: result.edit, entitiesBefore: before.total, entitiesAfter: after.total, touched: result.touched, diagnostics: result.diagnostics, rosterRefs: removal.refs.filter(r => r.kind === 'roster').length };
}

const child = spawn(process.execPath, [join(codeRoot, 'packages/lsp/dist/server-stdio.js')], { stdio: ['pipe', 'pipe', 'pipe'] });
let stderr = ''; child.stderr.on('data', d => { stderr += d; });
const client = lsp.createConnection(new lsp.StreamMessageReader(child.stdout), new lsp.StreamMessageWriter(child.stdin));
client.listen();
const stdioTimer = setTimeout(() => child.kill(), 60000);
let nodeResult;
try {
  nodeResult = await exercise((m, p) => client.sendRequest(m, p), (m, p) => client.sendNotification(m, p), pathToFileURL(checkout).href, manifest, files, command, true);
} catch (error) { throw new Error(`${error.message}\n${stderr}`); }
finally { clearTimeout(stdioTimer); client.dispose(); child.kill(); }

const server = createServer((req, res) => {
  res.setHeader('Content-Type', req.url === '/worker.js' ? 'text/javascript' : 'text/html');
  res.end(req.url === '/worker.js' ? readFileSync(join(codeRoot, 'packages/lsp/dist/server-browser.js')) : '<!doctype html><title>Studio worker edit smoke</title>');
});
await new Promise(r => server.listen(0, '127.0.0.1', r));
let browser, browserResult;
try {
  browser = await chromium.launch({ headless: true, executablePath: process.env.PW_EXECUTABLE ?? '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome' });
  const page = await browser.newPage();
  await page.goto(`http://127.0.0.1:${server.address().port}/`);
  browserResult = await page.evaluate(async ({ body, manifest, files, command }) => {
    const worker = new Worker('/worker.js', { type: 'module' });
    const pending = new Map(); let id = 0;
    worker.onmessage = ({ data }) => {
      if (!pending.has(data.id)) return;
      const { resolve, reject, timer } = pending.get(data.id); pending.delete(data.id); clearTimeout(timer);
      if (data.error) reject(new Error(JSON.stringify(data.error))); else resolve(data.result);
    };
    worker.onerror = event => { for (const p of pending.values()) p.reject(new Error(event.message)); };
    const request = (method, params) => new Promise((resolve, reject) => {
      const key = ++id;
      const timer = setTimeout(() => reject(new Error(`Worker request timed out: ${method}`)), 30000);
      pending.set(key, { resolve, reject, timer }); worker.postMessage({ jsonrpc: '2.0', id: key, method, params });
    });
    const notify = (method, params) => worker.postMessage({ jsonrpc: '2.0', method, params });
    try { return await (0, eval)(`(${body})`)(request, notify, 'file:///studio-smoke', manifest, files, command, false); }
    finally { worker.terminate(); }
  }, { body: exercise.toString(), manifest, files, command });
} finally { await browser?.close(); await new Promise(r => server.close(r)); }
// Desktop discovers all project TTR (including manual examples); the hosted contract
// supplies model/**. Check the authored bytes agree despite those existing load scopes.
assert.equal(JSON.stringify(nodeResult.edit).split(pathToFileURL(checkout).href).join('<root>'),
  JSON.stringify(browserResult.edit).split('file:///studio-smoke').join('<root>'));
delete nodeResult.edit; delete browserResult.edit;
assert.equal(nodeResult.rosterRefs, browserResult.rosterRefs);
console.log(JSON.stringify({ checkout, sha: execFileSync('git', ['rev-parse', 'HEAD'], { cwd: checkout, encoding: 'utf8' }).trim(), node: nodeResult, browser: browserResult }, null, 2));
