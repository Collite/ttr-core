#!/usr/bin/env node
// SPDX-License-Identifier: Apache-2.0
import { execFileSync } from 'node:child_process';
import { mkdirSync, readFileSync, writeFileSync, readdirSync } from 'node:fs';
import { resolve, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { createHash } from 'node:crypto';
import assert from 'node:assert/strict';
import { family } from './lsp-family.mjs';
const root = fileURLToPath(new URL('../', import.meta.url));
const output = resolve(process.argv[2] ?? 'build/lsp-family');
mkdirSync(output, { recursive: true });
const version = JSON.parse(
  readFileSync(join(root, 'packages/lsp/package.json'), 'utf8')
).version;
assert.match(version, /^\d+\.\d+\.\d+$/);
const artifacts = [];
for (const name of family) {
  execFileSync('pnpm', ['pack', '--pack-destination', output], {
    cwd: join(root, 'packages', name),
    stdio: 'inherit',
  });
  const source = JSON.parse(readFileSync(join(root, 'packages', name, 'package.json'), 'utf8'));
  const file = `${source.name.replace('@', '').replace('/', '-')}-${source.version}.tgz`;
  const path = join(output, file);
  const manifest = JSON.parse(
    execFileSync('tar', ['-xzOf', path, 'package/package.json'], { encoding: 'utf8' })
  );
  const files = execFileSync('tar', ['-tzf', path], { encoding: 'utf8' }).trim().split('\n');
  assert.equal(manifest.private, false);
  assert.equal(manifest.version, version);
  assert.ok(files.includes('package/LICENSE') && files.includes('package/NOTICE'));
  assert.ok(
    !files.some((f) => /(__tests__|\.test\.|\.spec\.|\.map$)/.test(f)),
    `${name}: test/source-map leak`
  );
  // The Studio loads this worker file from the tarball; the editor hosts spawn the stdio server.
  if (name === 'lsp')
    for (const entry of ['browser.worker.js', 'server-stdio.js'])
      assert.ok(files.includes(`package/dist/package/${entry}`), `lsp: ${entry} missing`);
  for (const [dep, version] of Object.entries(manifest.dependencies ?? {})) {
    assert.ok(!version.startsWith('workspace:'), `${name}: workspace dependency ${dep}`);
    if (dep.startsWith('@tatrman/'))
      assert.ok(
        family.some(
          (n) =>
            JSON.parse(readFileSync(join(root, 'packages', n, 'package.json'), 'utf8')).name === dep
        ),
        `${name}: missing ${dep}`
      );
  }
  artifacts.push({
    name: manifest.name,
    version: manifest.version,
    file,
    bytes: readFileSync(path).length,
    sha256: createHash('sha256').update(readFileSync(path)).digest('hex'),
    dependencies: manifest.dependencies ?? {},
    files,
  });
}
assert.equal(
  readdirSync(output).filter((f) => f.endsWith('.tgz')).length,
  family.length,
  'Use a clean artifact folder'
);
writeFileSync(
  join(output, 'manifest.json'),
  JSON.stringify(
    {
      family: 'lsp',
      version,
      commit: execFileSync('git', ['rev-parse', 'HEAD'], { cwd: root, encoding: 'utf8' }).trim(),
      artifacts,
    },
    null,
    2
  ) + '\n'
);
writeFileSync(
  join(output, 'SHA256SUMS'),
  artifacts.map((a) => `${a.sha256}  ${a.file}`).join('\n') + '\n'
);
console.log(`Verified ${artifacts.length} lsp family tarballs in ${output}`);
