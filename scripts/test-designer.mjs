// SPDX-License-Identifier: Apache-2.0
import { createRequire } from 'node:module';
import { dirname, join, resolve } from 'node:path';
import { spawnSync } from 'node:child_process';
// pnpm forwards `test -- pattern` literally; Vitest treats the separator as the end of filters.
const require = createRequire(resolve('package.json'));
const cli = join(dirname(require.resolve('vitest/package.json')), 'vitest.mjs');
const result = spawnSync(
  process.execPath,
  [cli, 'run', ...process.argv.slice(2).filter((a) => a !== '--')],
  { stdio: 'inherit' }
);
if (result.error) throw result.error;
process.exitCode = result.status ?? 1;
