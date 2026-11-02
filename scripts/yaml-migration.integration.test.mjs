// SPDX-License-Identifier: Apache-2.0
// Run after :packages:kotlin:ttr-yaml:installDist and pnpm --filter '@tatrman/migrate...' build.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { mkdtempSync, readFileSync, readdirSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { fileURLToPath } from 'node:url';
import { join, resolve } from 'node:path';

const root = fileURLToPath(new URL('../', import.meta.url));
const bridge = resolve(
  root,
  'packages/kotlin/ttr-yaml/build/install/ttr-yaml-migrate/bin/ttr-yaml-migrate'
);
const cli = resolve(root, 'packages/migrate/dist/cli.js');
const input = JSON.stringify({
  ttr: '0.22',
  model: 'er',
  defs: [{ kind: 'entity', id: 'Item', properties: { description: 'Příliš žluťoučký 🐈' } }],
});
const request = (source, from = '0.22', to = '0.22') =>
  JSON.stringify({ operation: 'migrate', from, to, source });
const invoke = (source) => spawnSync(bridge, [], { input: source, encoding: 'utf8' });
const runCli = (files, extra = []) =>
  spawnSync(
    process.execPath,
    [cli, 'migrate-yaml', ...files, '--from', '0.22', '--to', '0.22', '--bridge', bridge, ...extra],
    { encoding: 'utf8' }
  );
function withFiles(action) {
  const dir = mkdtempSync(join(tmpdir(), 'yaml migration real '));
  try {
    action(dir);
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
}

test('packaged UTF-8 bridge normalizes and then reaches an unchanged fixed point', () => {
  const result = invoke(request(input));
  assert.equal(result.status, 0, result.stderr);
  const value = JSON.parse(result.stdout);
  assert.deepEqual(Object.keys(value).sort(), ['changed', 'source']);
  assert.equal(value.changed, true);
  assert.match(value.source, /Příliš žluťoučký 🐈/);
  const again = invoke(request(value.source));
  assert.equal(again.status, 0, again.stderr);
  assert.deepEqual(JSON.parse(again.stdout), { source: value.source, changed: false });
});

test('packaged bridge reports located YAML errors only on stderr', () => {
  const result = invoke(
    request(
      'ttr: "0.22"\nmodel: er\ndefs:\n - kind: entity\n   id: E\n   properties:\n     unknown: 1\n'
    )
  );
  assert.equal(result.status, 2);
  assert.equal(result.stdout, '');
  assert.match(result.stderr, /<yaml>:7:15:/);
});

test('packaged bridge refuses unregistered versions and malformed requests', () => {
  for (const text of [
    request(input, '0.20'),
    '{}',
    '[]',
    '{',
    '{} {}',
    request(input).replace('"operation":"migrate"', '"operation":"migrate","operation":"migrate"'),
  ]) {
    const result = invoke(text);
    assert.equal(result.status, 2, result.stderr);
    assert.equal(result.stdout, '');
    assert.match(result.stderr, /<request>:1:1:/);
  }
});

test('real TS CLI dry-run, write and idempotent no-op', () =>
  withFiles((dir) => {
    const path = join(dir, 'model with spaces.yaml');
    writeFileSync(path, input);
    const dry = runCli([path], ['--dry-run']);
    assert.equal(dry.status, 0, dry.stderr);
    assert.match(dry.stdout, /Would change 1 file/);
    assert.equal(readFileSync(path, 'utf8'), input);
    const write = runCli([path]);
    assert.equal(write.status, 0, write.stderr);
    const normalized = readFileSync(path, 'utf8');
    assert.equal(JSON.parse(invoke(request(input)).stdout).source, normalized);
    const again = runCli([path]);
    assert.equal(again.status, 0, again.stderr);
    assert.match(again.stdout, /Changed 0 file/);
    assert.equal(readFileSync(path, 'utf8'), normalized);
    assert.deepEqual(readdirSync(dir), ['model with spaces.yaml']);
  }));

test('real TS CLI refuses an undeclared dialect before any batch write', () =>
  withFiles((dir) => {
    const first = join(dir, 'first.yaml');
    const foreign = join(dir, 'foreign.yaml');
    writeFileSync(first, input);
    writeFileSync(foreign, 'entities: []\n');
    const result = runCli([first, foreign]);
    assert.equal(result.status, 2);
    assert.equal(result.stderr, `${foreign}: <yaml>:1:1: : required property 'ttr' not found\n`);
    assert.equal(readFileSync(first, 'utf8'), input);
    assert.equal(readFileSync(foreign, 'utf8'), 'entities: []\n');
    assert.deepEqual(readdirSync(dir).sort(), ['first.yaml', 'foreign.yaml']);
  }));

test('packaged bridge migrates a 0.21 document by its marker, then reads it as 0.22', () => {
  const old = input.replace('"ttr":"0.22"', '"ttr":"0.21"');
  assert.notEqual(old, input);
  const result = invoke(request(old, '0.21', '0.22'));
  assert.equal(result.status, 0, result.stderr);
  const value = JSON.parse(result.stdout);
  assert.equal(value.changed, true);
  assert.equal(value.source, JSON.parse(invoke(request(input)).stdout).source);
  const refused = invoke(request(input, '0.21', '0.22'));
  assert.equal(refused.status, 2);
  assert.match(refused.stderr, /Expected ttr: "0.21"/);
});

test('real TS CLI refuses unsupported target versions without writing', () =>
  withFiles((dir) => {
    const path = join(dir, 'model.yaml');
    writeFileSync(path, input);
    const result = runCli([path], ['--to', '0.23']);
    assert.equal(result.status, 2);
    assert.match(result.stderr, /No registered canonical YAML migration/);
    assert.equal(readFileSync(path, 'utf8'), input);
  }));

test('real launcher refuses depth 1,000 at the guard node and without a stack trace', () => {
  const source =
    "ttr: '0.22'\nmodel: automation\ndefs:\n  - kind: program\n    id: D\n    properties:\n      a: " +
    '['.repeat(1000) +
    '1' +
    ']'.repeat(1000) +
    '\n';
  const result = invoke(request(source));
  assert.equal(result.status, 2);
  assert.equal(result.stdout, '');
  assert.equal(result.stderr, '<yaml>:7:70: nesting too deep (limit 64)\n');
});

test('real launcher locates 100,000 nested retained calc brackets at the argument scalar', () => {
  const source =
    "ttr: '0.22'\nmodel: md\ndefs:\n  - kind: map\n    id: Convert\n    properties:\n" +
    "      calc:\n        name: convert\n        args:\n          input: '" +
    '['.repeat(100000) +
    '1' +
    ']'.repeat(100000) +
    "'\n";
  const result = invoke(request(source));
  assert.equal(result.status, 2);
  assert.equal(result.stdout, '');
  assert.equal(result.stderr, '<yaml>:10:18: nesting too deep (limit 64)\n');
});
