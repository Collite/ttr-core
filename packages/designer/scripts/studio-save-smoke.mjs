#!/usr/bin/env node
// SPDX-License-Identifier: Apache-2.0
// Actual App + React Flow + Worker, static parent, test-only extension slot. No checkout writes.
import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { readFileSync, writeFileSync, mkdirSync } from 'node:fs';
import { resolve, join } from 'node:path';
import { createServer } from 'vite';
import { chromium } from 'playwright-core';
import { synthesize, resolveManifest, parseManifest, applyWorkspaceEditToText } from '../../edit/dist/index.js';
const repo = resolve(import.meta.dirname, '../../..');
const checkout = resolve(process.argv[2] ?? '../hartland');
const out = resolve(process.argv[3] ?? '/tmp/so-save-smoke');
mkdirSync(out, { recursive: true });
const paths = execFileSync('git', ['ls-files', '-z', '--', 'model/', 'modeler.toml'], { cwd: checkout, encoding: 'utf8' }).split('\0')
  .filter(p => /\.(ttrm|ttrg|ttrl|toml|mjs)$/.test(p));
const files = Object.fromEntries(paths.map(p => [p, readFileSync(join(checkout, p), 'utf8')]));
const queryPath = 'model/er/returns-review.ttrm';
// A canvas-visible draft solely to reach the existing drawer. The user replaces this
// whole definition with the query; the production build gets no source-opening test hook.
files[queryPath] = 'package hartland\nmodel er\ndef entity returns_review { attributes: [ def attribute draft { type: text } ] }\n';
const vector = join(repo, 'packages/edit/__tests__/synth/vectors/insert-entity/on-query');
const query = readFileSync(join(vector, 'world', queryPath), 'utf8').split('def query')[1];
const queryText = 'def query' + query;
const insert = JSON.parse(readFileSync(join(vector, 'op.json'), 'utf8'));
let currentFiles = { ...files };
let sha = 'smoke-one';
const source = 'hartland.er.entity.returns_review';
const commands = [
  { op: 'set-source', args: { nodeRef: source, text: queryText } },
  { op: insert.op, args: { ...insert.args, terms: undefined } },
  ...['en', 'cs'].map(lang => ({ op: 'add-term', args: { lang, term: insert.args.terms[lang], target: source, file: 'returns.ttrm' } })),
];
const expected = { ...files };
const manifest = resolveManifest(parseManifest(files['modeler.toml']), '');
for (const cmd of commands) {
  const result = synthesize(cmd.op, cmd.args, { files: expected, rootUri: '', manifest });
  assert.equal(result.ok, true, JSON.stringify(result));
  for (const path of result.touched) expected[path] = applyWorkspaceEditToText(expected[path] ?? '', result.edit, path);
}
const init = { type: 'ttr.host.init', workspace: 'hartland', basePath: '/m/', api: '/api/model', token: 'smoke-only',
  scope: { kind: 'change', id: 'smoke' }, lang: 'en', theme: 'light' };
let vite;
const plugin = {
  name: 'studio-save-smoke-only',
  resolveId(id) { if (id === 'virtual:studio-save-smoke' || id === '/virtual:studio-save-smoke') return '\0studio-save-smoke'; },
  load(id) {
    if (id !== '\0studio-save-smoke') return;
    return `import React from 'react';
import ReactDOM from 'react-dom/client';
import { flushSync } from 'react-dom';
import App from '/src/App.tsx';
import { defaultRuntimeConfig, setRuntimeConfig } from '/src/runtime-config.ts';
import '/src/index.css';
import { registerAuthoringLoader } from '/src/ext/authoring-registry.ts';
import { editModel } from '/src/extensions.ts';
registerAuthoringLoader(async ({dataSource}) => {
  window.__studio = dataSource;
  return { editable: true, saveNode: async()=>({ok:true}), renderNodeMenu:()=>null, renderMissingObjects:()=>null,
    renderToolbarActions:()=>React.createElement('button', { 'data-testid':'smoke-insert', onClick: async()=> {
      window.__slotResults = [];
      for (const command of ${JSON.stringify(commands.slice(1))}) window.__slotResults.push(await editModel(dataSource, command));
    }}, 'Author returns review (test slot)') };
});
setRuntimeConfig(defaultRuntimeConfig());
flushSync(()=>ReactDOM.createRoot(document.getElementById('root')).render(React.createElement(React.StrictMode,null,React.createElement(App))));`;
  },
  configureServer(server) {
    server.middlewares.use(async (req, res, next) => {
      const path = req.url.split('?')[0];
      if (path === '/favicon.ico') { res.statusCode=204; res.end(); return; }
      if (path === '/api/model/changes/smoke/files') { res.setHeader('Content-Type', 'application/json'); res.end(JSON.stringify({sha, files:currentFiles})); return; }
      if (path === '/__host') {
        res.setHeader('Content-Type', 'text/html');
        res.end(`<!doctype html><style>body{margin:0}iframe{width:100vw;height:100vh;border:0}</style><iframe></iframe><script>
window.messages=[];window.addEventListener('message',e=>{if(e.source===document.querySelector('iframe').contentWindow)messages.push(e.data)});
document.querySelector('iframe').onload=()=>document.querySelector('iframe').contentWindow.postMessage(${JSON.stringify(init)},location.origin);
document.querySelector('iframe').src='/__smoke.html?backend=host#/w/hartland/s/er';
</script>`); return;
      }
      if (path === '/__smoke.html') {
        res.setHeader('Content-Type', 'text/html');
        res.end(await server.transformIndexHtml(path, '<!doctype html><html><head><meta charset="utf-8"></head><body><div id="root"></div><script type="module" src="/virtual:studio-save-smoke"></script></body></html>')); return;
      }
      next();
    });
  },
};
let browser;
try {
  process.chdir(join(repo, 'packages/designer'));
  vite = await createServer({ root: join(repo, 'packages/designer'), plugins: [plugin], server: { host:'127.0.0.1', port:0, open:false }, logLevel:'error' });
  await vite.listen();
  browser = await chromium.launch({ headless:true, executablePath:process.env.PW_EXECUTABLE ?? '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome' });
  const page = await browser.newPage({ viewport: {width:1600,height:1000} });
  const errors = []; page.on('pageerror', e => { errors.push(e.message); console.error('Browser error:', e.message); });
  page.on('response', r => { if(r.status()>=400) console.error('HTTP',r.status(),r.url()); });
  page.on('requestfailed', r => console.error('Request failed:',r.url(),r.failure()?.errorText));
  page.on('console', m => { if(m.type()==='error') console.error('Console:',m.text()); });
  await page.goto(`http://127.0.0.1:${vite.httpServer.address().port}/__host`);
  try { await page.waitForFunction(()=>window.messages.some(m=>m.type==='ttr.frame.ready'),null,{timeout:30000}); }
  catch(error) {
    console.error('Host messages:', await page.evaluate(()=>window.messages));
    for(const f of page.frames()) console.error('Frame:',f.url(),await f.locator('body').innerText());
    throw error;
  }
  const frame = page.frames().find(f=>f.url().includes('/__smoke.html'));
  const card = frame.locator('.react-flow__node').filter({hasText:'returns_review'}).first();
  await card.waitFor({timeout:30000});
  await card.click();
  await frame.getByTestId('peek-escalate').click();
  await frame.getByTestId('drawer-edit').click();
  await frame.getByTestId('drawer-editor').fill(queryText);
  await frame.getByTestId('drawer-save').click();
  await frame.getByTestId('drawer-editor').waitFor({state:'detached'});
  await frame.getByTestId('smoke-insert').click();
  await frame.waitForFunction(()=>window.__slotResults?.length===3,{},{timeout:60000});
  const results = await frame.evaluate(()=>window.__slotResults);
  assert.ok(results.every(r=>r.ok),JSON.stringify(results));
  assert.ok(!results.flatMap(r=>r.diagnostics??[]).some(d=>d.severity===1),JSON.stringify(results));
  await page.waitForFunction(()=>window.messages.some(m=>m.type==='ttr.frame.dirty'&&m.changed===4));
  await page.evaluate(()=>document.querySelector('iframe').contentWindow.postMessage({type:'ttr.host.collect'},location.origin));
  await page.waitForFunction(()=>window.messages.some(m=>m.type==='ttr.frame.changedFiles'));
  const collected = await page.evaluate(()=>window.messages.filter(m=>m.type==='ttr.frame.changedFiles').at(-1).files);
  const expectedPaths = Object.keys(expected).filter(p=>expected[p]!==files[p]).sort();
  assert.deepEqual(collected.map(f=>f.path),expectedPaths);
  for(const file of collected) assert.equal(file.text,expected[file.path],file.path);
  await card.waitFor({timeout:30000});
  await frame.getByTestId('peek-source').filter({hasText:'def entity returns_review'}).waitFor();
  await frame.getByTestId('display-mode').selectOption('with-types');
  await card.getByText('request_id', {exact:true}).waitFor();
  await page.screenshot({path:join(out,'authored.png')});
  for(const file of collected) { const p=join(out,'files',file.path); mkdirSync(resolve(p,'..'),{recursive:true}); writeFileSync(p,file.text); }
  for (const file of collected) {
    const p=join(out,'before',file.path); mkdirSync(resolve(p,'..'),{recursive:true}); writeFileSync(p,files[file.path] ?? '');
  }
  let patch;
  try { patch=execFileSync('git',['diff','--no-index','--','before','files'],{cwd:out,encoding:'utf8'}); }
  catch(error) { if(error.status!==1) throw error; patch=error.stdout; }
  writeFileSync(join(out,'collected.diff'),patch);
  // Record whole before/after bytes portably; git diff exits 1 for a real change.
  writeFileSync(join(out,'collected.json'),JSON.stringify(collected,null,2)+'\n');
  currentFiles = { ...expected }; sha = 'smoke-two';
  await page.evaluate(init=>document.querySelector('iframe').contentWindow.postMessage(init,location.origin),{...init,sha});
  await frame.waitForFunction(()=>window.__studio.sha==='smoke-two');
  assert.deepEqual(await frame.evaluate(()=>window.__studio.changedFiles()),[]);
  await page.waitForFunction(()=>window.messages.filter(m=>m.type==='ttr.frame.dirty').at(-1)?.changed===0);
  assert.deepEqual(errors,[]);
  const report = { checkout, sha:execFileSync('git',['rev-parse','HEAD'],{cwd:checkout,encoding:'utf8'}).trim(),
    paths:expectedPaths, byteEqualToPureVectors:true, afterSaveDirty:0, browserErrors:errors,
    diagnostics:results.flatMap(r=>r.diagnostics??[]), seed:'In-memory canvas draft replaced with query through the real drawer; test-only extension slot authors entity and two terms.' };
  writeFileSync(join(out,'report.json'),JSON.stringify(report,null,2)+'\n');
  console.log(JSON.stringify(report,null,2));
} finally { await browser?.close(); await vite?.close(); }
