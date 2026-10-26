// SPDX-License-Identifier: Apache-2.0
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
import { join, resolve } from 'node:path';
import { createRequire } from 'node:module';
import { pathToFileURL, fileURLToPath } from 'node:url';
import { checkEmbeddedPage } from './designer-page-browser.mjs';
const repo = fileURLToPath(new URL('../', import.meta.url));
const require = createRequire(join(repo, 'package.json'));
const { chromium } = require('playwright-core');
const artifacts = resolve(process.argv[2] ?? 'build/designer-family');
const out = resolve(process.argv[3] ?? 'build/designer-consumer');
mkdirSync(out, { recursive: true });
const receipt = JSON.parse(readFileSync(join(artifacts, 'manifest.json'), 'utf8'));
const scratch = realpathSync(
  mkdtempSync(join(process.env.TMPDIR ?? '/tmp', 'ttr-designer-consumer-'))
);
const checkout = process.argv[4] && resolve(process.argv[4]);
const paths = checkout
  ? execFileSync('git', ['ls-files', '-z', '--', 'model/', 'modeler.toml'], {
      cwd: checkout,
      encoding: 'utf8',
    })
      .split('\0')
      .filter((p) => /\.(ttrm|ttrg|ttrl|toml|mjs)$/.test(p))
  : [];
const files = checkout
  ? Object.fromEntries(paths.map((p) => [p, readFileSync(join(checkout, p), 'utf8')]))
  : {
      'modeler.toml': '[packages]\nlayout="off"',
      'model/er.ttrm':
        'package demo\nmodel er\ndef entity customer { attributes: [def attribute id { type: int }] }',
      'model/db.ttrm':
        'package demo\nmodel db\ndef table customer { columns: [def column id { type: int }] }',
      'model/md.ttrm':
        'package demo\nmodel md\ndef domain Code { type: string }\ndef dimension Customer { key: code, attributes: [def attribute code { domain: md.Code }] }\ndef measure revenue { domain: md.Code, aggregation: sum }\ndef cubelet sales { grain: [Customer.code], measures: [md.measure.revenue] }',
    };
let vite, browser, prod;
const errors = [];
const manager = process.env.DESIGNER_PACKAGE_MANAGER ?? 'npm';
try {
  writeFileSync(
    join(scratch, 'package.json'),
    JSON.stringify({ name: 'external-designer-smoke', type: 'module', private: true })
  );
  writeFileSync(join(scratch, 'empty.npmrc'), '');
  const hostDependencies = {
    react: '19.0.0',
    'react-dom': '19.0.0',
    '@types/react': '19',
    '@types/react-dom': '19',
    '@types/node': '22',
    vite: '6.4.2',
    typescript: '5.9.3',
  };
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
        name: 'external-designer-pnpm-smoke',
        type: 'module',
        private: true,
        packageManager: 'pnpm@11.1.3',
        dependencies: { ...hostDependencies, ...specs },
      })
    );
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
      env: { ...process.env, npm_config_userconfig: join(scratch, 'empty.npmrc') },
    });
  } else
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
      {
        cwd: scratch,
        stdio: 'inherit',
        env: { ...process.env, npm_config_userconfig: join(scratch, 'empty.npmrc') },
      }
    );
  const installed = createRequire(join(scratch, 'package.json'));
  const { createServer, build, preview } = await import(pathToFileURL(installed.resolve('vite')));
  writeFileSync(
    join(scratch, 'consume.mjs'),
    `import assert from 'node:assert/strict';
import { parseString } from '@tatrman/parser';import { renderDataType } from '@tatrman/lsp';
import { synthesize, resolveManifest } from '@tatrman/edit';
import * as canvas from '@tatrman/canvas-core';import * as tokens from '@tatrman/tokens';
import * as perspectives from '@tatrman/perspectives';import * as links from '@tatrman/deep-links';
assert.equal(parseString('model er\\ndef entity customer {}').errors.length,0);
assert.equal(typeof renderDataType,'function');assert.equal(typeof synthesize,'function');
for(const p of [canvas,tokens,perspectives,links])assert.ok(Object.keys(p).length>0);
resolveManifest(undefined,'');`
  );
  execFileSync(process.execPath, ['consume.mjs'], { cwd: scratch, stdio: 'inherit' });
  writeFileSync(
    join(scratch, 'stdio-probe.mjs'),
    readFileSync(join(repo, 'scripts/designer-stdio-probe.mjs'))
  );
  execFileSync(process.execPath, ['stdio-probe.mjs'], {
    cwd: scratch,
    stdio: 'inherit',
    timeout: 20_000,
  });
  writeFileSync(join(scratch, 'snapshot.json'), JSON.stringify({ sha: 'artifact-smoke', files }));
  writeFileSync(
    join(scratch, 'index.html'),
    `<!doctype html><html><head><meta charset="utf-8"><link rel="icon" href="data:,"><style>
body {margin:13px;background:#0c1828;color:#eee;font-family:Georgia} #navy-header{height:48px;background:#12263f;padding:8px;box-sizing:border-box} #host-button{font-family:Georgia;font-size:18px;border:3px solid coral;margin:7px;padding:5px} #mount{height:calc(100vh - 150px);min-height:500px} #outside{font-size:17px}
</style></head><body><header id="navy-header">External host · Hartland · DesignerPage</header><p id="outside">Host content keeps its own styles.</p><button id="host-button">Host button</button><div id="mount"></div><script type="module" src="/main.jsx"></script></body></html>`
  );
  writeFileSync(
    join(scratch, 'main.jsx'),
    `import React,{useState} from 'react';import{createRoot}from'react-dom/client';import{DesignerPage}from'@tatrman/designer-core';import'@tatrman/designer-core/designer.css';
window.locations=[];window.failures=[];window.installations=[];window.readiness=[];window.diagnosticSets=[];async function mount(){const snapshot=await fetch('/snapshot.json').then(r=>r.json());
function Model(){const[location,setLocation]=useState('/w/hartland/s/md');const[theme,setTheme]=useState('dark');window.setPageTheme=setTheme;return React.createElement(DesignerPage,{files:snapshot,workspace:'hartland',basePath:'/m/',lang:'en',theme,location,onReady:v=>window.readiness.push(v),onSnapshot:v=>window.installations.push(v),onDiagnostics:v=>window.diagnosticSets.push(v),onLocation:p=>{window.locations.push(p);setLocation(p)},onError:e=>window.failures.push(e)})}
createRoot(document.getElementById('mount')).render(React.createElement(Model));}mount();`
  );
  writeFileSync(
    join(scratch, 'type-probe.ts'),
    `import {DesignerPage,type DesignerPageProps,type ShellEditContext,type Host} from '@tatrman/designer-core';
import type {AuthoringExtension} from '@tatrman/designer-core/extensions';
const props:DesignerPageProps={files:async()=>({sha:'types',files:{}}),expectedSha:'types',sourceAuthoring:false,basePath:'/m/',lang:'en',onReady:v=>{const sha:string=v.sha;const required:boolean=v.manifest.bindings.required;void sha;void required;},onSnapshot:v=>{const sha:string=v.sha;const count:number=v.changed;void sha;void count;},onDiagnostics:values=>{for(const d of values){const message:string=d.message;void message;}},onError:e=>{const sha:string|undefined=e.sha;void sha;}};DesignerPage(props);
const host:Host={send:()=>{},dispose:()=>{}};host.send({type:'ttr.frame.ready'});
const extension:AuthoringExtension={manifest:{name:'probe',version:'1',license:'none',requires:[]},activate:async()=>null};void extension;
function context(c:ShellEditContext){return c.editable}void context;`
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
    { cwd: scratch, stdio: 'pipe' }
  );
  const config = {
    root: scratch,
    configFile: false,
    server: { host: '127.0.0.1', port: 0, fs: { allow: [scratch] } },
    resolve: { dedupe: ['react', 'react-dom'] },
    optimizeDeps: {
      exclude: ['@tatrman/designer-core'],
      include: [
        'react',
        'react/jsx-runtime',
        'react-dom',
        'react-dom/client',
        '@tatrman/designer-core > @tatrman/canvas-core > elkjs/lib/elk.bundled.js',
      ],
    },
    logLevel: 'warn',
  };
  console.log('Starting external Vite dev host');
  vite = await createServer(config);
  await vite.listen();
  console.log(vite.resolvedUrls);
  browser = await chromium.launch({
    executablePath:
      process.env.CHROME_BIN ??
      (process.platform === 'darwin'
        ? '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome'
        : chromium.executablePath()),
    headless: true,
  });
  const page = await browser.newPage({ viewport: { width: 1440, height: 1050 } });
  page.on('pageerror', (e) => errors.push(String(e)));
  page.on('console', (m) => {
    if (m.type() === 'error') errors.push(m.text());
  });
  const inspect = async (url, kind) => {
    console.log('Inspect', kind, url);
    await page.goto(url);
    console.log('Page loaded', kind);
    await page.getByTestId('catalog-spine').waitFor();
    await page.getByTestId('star-cube').first().waitFor();
    await page.waitForFunction(() => document.querySelectorAll('.react-flow__node').length > 0);
    await page.waitForFunction(() => window.installations.length === 1 && window.readiness.length === 1 && window.diagnosticSets.length > 0);
    const lifecycle = await page.evaluate(() => ({ snapshot: window.installations[0], ready: window.readiness[0], diagnostics: window.diagnosticSets.at(-1) }));
    assert.equal(lifecycle.snapshot.sha, kind === 'host-dev' ? 'artifact-smoke' : 'prod');
    assert.equal(lifecycle.ready.sha, lifecycle.snapshot.sha);
    assert.equal(lifecycle.snapshot.changed, 0);
    assert.equal(typeof lifecycle.ready.manifest.bindings.required, 'boolean');
    const state = await page.evaluate(() => {
      const root = document.querySelector('.ttr-designer'),
        mount = document.getElementById('mount'),
        button = document.getElementById('host-button');
      const bad = [];
      function selectors(text) {
        let start = 0,
          depth = 0,
          quote = null;
        const out = [];
        for (let i = 0; i < text.length; i++) {
          const c = text[i];
          if (quote) {
            if (c === quote && text[i - 1] !== '\\') quote = null;
            continue;
          }
          if (c === '"' || c === "'") {
            quote = c;
            continue;
          }
          if (c === '(' || c === '[') depth++;
          if (c === ')' || c === ']') depth--;
          if (c === ',' && depth === 0) {
            out.push(text.slice(start, i));
            start = i + 1;
          }
        }
        out.push(text.slice(start));
        return out;
      }
      function inspectRules(rules) {
        for (const rule of rules) {
          if (
            rule.selectorText &&
            !selectors(rule.selectorText).every((s) => s.trim().startsWith('.ttr-designer'))
          )
            bad.push(rule.selectorText);
          if (rule.cssRules && rule.type !== CSSRule.KEYFRAMES_RULE) inspectRules(rule.cssRules);
        }
      }
      for (const sheet of document.styleSheets) {
        if (
          sheet.ownerNode?.tagName === 'STYLE' &&
          sheet.ownerNode.textContent.includes('#navy-header')
        )
          continue;
        inspectRules(sheet.cssRules);
      }
      return {
        catalog: [...document.querySelectorAll('[data-testid="catalog-item"]')].map(
          (e) => e.textContent
        ),
        nodes: document.querySelectorAll('.react-flow__node').length,
        cubes: document.querySelectorAll('[data-testid="star-cube"]').length,
        dimensions: document.querySelectorAll('[data-testid="star-dim"]').length,
        scheme: getComputedStyle(root).colorScheme,
        rootHeight: root.getBoundingClientRect().height,
        mountHeight: mount.getBoundingClientRect().height,
        studioHeight: document.querySelector('.studio-root').getBoundingClientRect().height,
        bodyMargin: getComputedStyle(document.body).margin,
        buttonFont: getComputedStyle(button).fontFamily,
        buttonBorder: getComputedStyle(button).borderTopWidth,
        headerHeight: document.getElementById('navy-header').getBoundingClientRect().height,
        background: getComputedStyle(document.querySelector('[data-testid="canvas-kernel"]'))
          .backgroundColor,
        bad,
        failures: window.failures,
        url: location.pathname,
      };
    });
    assert.equal(state.scheme, 'dark');
    assert.equal(state.bodyMargin, '13px');
    assert.equal(state.buttonBorder, '3px');
    assert.equal(state.headerHeight, 48);
    assert.equal(state.studioHeight, state.mountHeight);
    assert.equal(state.background, 'rgb(22, 40, 63)');
    assert.deepEqual(state.bad, []);
    assert.deepEqual(state.failures, []);
    assert.ok(state.nodes > 0 && state.cubes > 0 && state.dimensions > 0);
    assert.ok(['er', 'db', 'md'].every((x) => state.catalog.includes(x)));
    assert.equal(state.url, '/');
    await page.screenshot({ path: join(out, kind + '.png'), fullPage: true });
    state.lifecycle = lifecycle;
    state.interactions = await checkEmbeddedPage(page);
    await page.locator('[data-testid="catalog-item"][data-ref="er"]').click();
    await page.waitForFunction(() => window.locations.includes('/w/hartland/s/er'));
    return state;
  };
  const dev = await inspect(vite.resolvedUrls.local[0], 'host-dev');
  await page.goto('about:blank');
  await vite.close();
  vite = null;
  await build({ ...config, build: { outDir: join(scratch, 'dist') } });
  prod = await preview({ ...config, preview: { host: '127.0.0.1', port: 0 } });
  // The model snapshot is a host input; copy it into the built static site.
  writeFileSync(join(scratch, 'dist/snapshot.json'), JSON.stringify({ sha: 'prod', files }));
  const production = await inspect(prod.resolvedUrls.local[0], 'host-production');
  assert.deepEqual(errors, []);
  const result = {
    manager,
    checkout: checkout ?? 'portable fixture',
    artifacts: receipt,
    files: Object.keys(files).length,
    dev,
    production,
    browserErrors: errors,
    externalTypes: 'Strict NodeNext consumer passed against installed package declarations',
    packages: `Eight packed tarballs installed with ${manager} outside the workspace. No workspace links, aliases or worker plugin.`,
  };
  writeFileSync(join(out, 'report.json'), JSON.stringify(result, null, 2) + '\n');
  console.log(JSON.stringify(result, null, 2));
} catch (error) {
  console.error('BROWSER ERRORS', errors);
  throw error;
} finally {
  await browser?.close();
  await vite?.close();
  await prod?.httpServer.close();
  rmSync(scratch, { recursive: true, force: true });
}
