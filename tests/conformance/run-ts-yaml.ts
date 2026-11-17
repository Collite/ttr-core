// SPDX-License-Identifier: Apache-2.0
// Compare reviewed twins, never regenerate them. Keep working dumps in build/.
import { mkdirSync, writeFileSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { twinDumps, yamlTwins } from './yaml-fixtures.js';

const out = fileURLToPath(new URL('../../build/conformance/ts-yaml/', import.meta.url));
const twins = yamlTwins();
let failed = 0;
for (const twin of twins) {
  try {
    const dumps = twinDumps(twin);
    const dest = path.join(out, twin.name.replace(/\.ttr[mg]$/, '.json'));
    mkdirSync(path.dirname(dest), { recursive: true });
    writeFileSync(dest, dumps.yaml + '\n');
    if (dumps.native !== dumps.yaml) throw new Error('TS YAML/native dump mismatch');
  } catch (error) {
    console.error(`${twin.name}: ${error instanceof Error ? error.message : String(error)}`);
    failed++;
  }
}
console.log(
  `TS canonical YAML: ${twins.length} twins, ${twins.length - failed} equal, ${failed} failed`
);
if (failed) process.exitCode = 1;
