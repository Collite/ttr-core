// SPDX-License-Identifier: Apache-2.0
// Build a local-only BFF snapshot from tracked model files, without vendoring a demo world.
import { execFileSync } from 'node:child_process';
import { readFileSync, writeFileSync } from 'node:fs';
import { join, resolve } from 'node:path';
import { pathToFileURL } from 'node:url';

export function snapshotFromCheckout(checkout) {
  const root = resolve(checkout);
  const paths = execFileSync('git', ['ls-files', '-z', '--', 'model/', 'modeler.toml'], {
    cwd: root,
    encoding: 'utf8',
  })
    .split('\0')
    .filter(Boolean);
  const files = Object.fromEntries(paths.map((p) => [p, readFileSync(join(root, p), 'utf8')]));
  const sha = execFileSync('git', ['rev-parse', 'HEAD'], { cwd: root, encoding: 'utf8' }).trim();
  return { sha, files };
}
if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  if (!process.argv[2] || !process.argv[3])
    throw new Error('Usage: make-host-smoke-files.mjs <checkout> <output.json>');
  writeFileSync(process.argv[3], JSON.stringify(snapshotFromCheckout(process.argv[2])));
}
