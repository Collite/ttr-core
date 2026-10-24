// SPDX-License-Identifier: Apache-2.0
import { execFileSync } from 'node:child_process';
import { readFileSync, mkdirSync, writeFileSync } from 'node:fs';
import { resolve, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { createHash } from 'node:crypto';
import assert from 'node:assert/strict';
const artifacts = resolve(process.argv[2] ?? 'build/designer-family');
const output = resolve(process.argv[3] ?? 'build/designer-consumer');
const receipt = JSON.parse(readFileSync(join(artifacts, 'manifest.json'), 'utf8'));
for (const a of receipt.artifacts)
  assert.equal(
    createHash('sha256')
      .update(readFileSync(join(artifacts, a.file)))
      .digest('hex'),
    a.sha256,
    a.file
  );
mkdirSync(output, { recursive: true });
const reports = {};
for (const manager of ['npm', 'pnpm']) {
  const out = join(output, manager);
  execFileSync(
    process.execPath,
    [
      fileURLToPath(new URL('./designer-consumer-leg.mjs', import.meta.url)),
      artifacts,
      out,
      ...(process.argv[4] ? [resolve(process.argv[4])] : []),
    ],
    { stdio: 'inherit', env: { ...process.env, DESIGNER_PACKAGE_MANAGER: manager } }
  );
  reports[manager] = JSON.parse(readFileSync(join(out, 'report.json'), 'utf8'));
}
writeFileSync(join(output, 'report.json'), JSON.stringify(reports, null, 2) + '\n');
