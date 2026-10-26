#!/usr/bin/env node
// SPDX-License-Identifier: Apache-2.0
import { execFileSync } from 'node:child_process';
import { mkdtempSync, writeFileSync, readFileSync, readdirSync, rmSync } from 'node:fs';
import { join, dirname, relative } from 'node:path';
import { tmpdir } from 'node:os';
import { fileURLToPath } from 'node:url';
import assert from 'node:assert/strict';
const root = join(dirname(fileURLToPath(import.meta.url)), '..');
const run = (cmd, args, cwd = root) => execFileSync(cmd, args, { cwd, stdio: ['ignore', 'pipe', 'inherit'], encoding: 'utf8' });
const scratch = mkdtempSync(join(tmpdir(), 'ttr-edit-consumer-'));
try {
  if (!process.argv.includes('--skip-build')) run('pnpm', ['--filter', '@tatrman/edit...', 'build']);
  const pkg = join(root, 'packages/edit');
  run('pnpm', ['publish', '--dry-run', '--no-git-checks', '--access', 'public'], pkg);
  run('pnpm', ['pack', '--pack-destination', scratch], pkg);
  const tarball = join(scratch, readdirSync(scratch).find(f => f.endsWith('.tgz')));
  const manifest = JSON.parse(run('tar', ['-xzOf', tarball, 'package/package.json']));
  const notices = run('tar', ['-xzOf', tarball, 'package/NOTICE']);
  assert.ok(notices.includes('The ANTLR Project') && notices.includes('Squirrel Chat'));
  assert.equal(manifest.private, false);
  assert.equal(manifest.version, JSON.parse(readFileSync(join(pkg, 'package.json'), 'utf8')).version);
  assert.ok(!Object.keys(manifest.dependencies ?? {}).some(d => d.startsWith('@tatrman/')));
  writeFileSync(join(scratch, 'package.json'), JSON.stringify({ type: 'module', private: true }));
  run('npm', ['install', '--ignore-scripts', '--no-audit', '--no-fund', tarball], scratch);
  const vector = join(root, 'packages/edit/__tests__/synth/vectors/insert-entity/on-query');
  const files = sub => Object.fromEntries(readdirSync(join(vector, sub), { recursive: true, withFileTypes: true })
    .filter(e => e.isFile()).map(e => { const p = join(e.parentPath, e.name); return [relative(join(vector, sub), p), readFileSync(p, 'utf8')]; }));
  writeFileSync(join(scratch, 'input.json'), JSON.stringify({ world: files('world'), expected: files('expected'),
    command: JSON.parse(readFileSync(join(vector, 'op.json'), 'utf8')) }));
  writeFileSync(join(scratch, 'consume.mjs'), `
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { synthesize, parseManifest, resolveManifest, applyWorkspaceEditToText } from '@tatrman/edit';
const {world, expected, command} = JSON.parse(readFileSync('input.json', 'utf8'));
const result = synthesize(command.op, command.args, {files: world, rootUri: '', manifest: resolveManifest(parseManifest(world['modeler.toml']), '')});
assert.equal(result.ok, true);
assert.equal(result.touched.length, 4);
for (const path of result.touched) assert.equal(applyWorkspaceEditToText(world[path] ?? '', result.edit, path), expected[path], path);
console.log('Standalone npm consumer: four canonical files match the portable vector.');
`);
  run('node', ['consume.mjs'], scratch);
  writeFileSync(join(scratch, 'consume.ts'), `import { synthesize, resolveManifest, type SynthContext } from '@tatrman/edit';
const ctx: SynthContext = { files: {}, rootUri: '', manifest: resolveManifest(undefined, '') };
synthesize('add-term', { lang: 'en', term: 'returns review', target: 'er.entity.returns_review' }, ctx);
`);
  // Compile from outside the workspace so private symlinks cannot hide missing type dependencies.
  run('node', [join(root, 'node_modules/typescript/bin/tsc'), '--noEmit', '--strict', '--target', 'es2022',
    '--module', 'nodenext', '--moduleResolution', 'nodenext', '--types', 'vscode-languageserver-types', 'consume.ts'], scratch);
  console.log('Standalone TypeScript consumer: declarations resolve without private workspace packages.');
} finally { rmSync(scratch, { recursive: true, force: true }); }
