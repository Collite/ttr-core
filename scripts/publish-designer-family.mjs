// SPDX-License-Identifier: Apache-2.0
import { execFileSync } from 'node:child_process';
import { readFileSync } from 'node:fs';
import { resolve, join } from 'node:path';
import assert from 'node:assert/strict';
// Never called for PRs or bare (internal) designer tags.
assert.match(process.env.GITHUB_REF_NAME ?? '', /^designer\/v\d+\.\d+\.\d+-RELEASE$/);
const folder = resolve(process.argv[2]);
const { artifacts } = JSON.parse(readFileSync(join(folder, 'manifest.json'), 'utf8'));
for (const a of artifacts) {
  // edit/v0.1.0 has already published these unchanged synthesizer bytes (P1).
  if (a.name === '@tatrman/edit' && a.version === '0.1.0') continue;
  execFileSync('npm', ['publish', join(folder, a.file), '--access', 'public'], {
    stdio: 'inherit',
  });
}
