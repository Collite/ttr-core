// SPDX-License-Identifier: Apache-2.0
import { mkdirSync, readdirSync, readFileSync, writeFileSync, rmSync } from 'node:fs';
import { join, dirname } from 'node:path';
// Run in a pure-TS family member; keep workspace entry points and staging separate.
rmSync('dist/package', { recursive: true, force: true });
function visit(dir) {
  for (const entry of readdirSync(dir, { withFileTypes: true })) {
    if (
      entry.name === 'package' ||
      entry.name === '__tests__' ||
      /\.(test|spec)\./.test(entry.name)
    )
      continue;
    const path = join(dir, entry.name);
    if (entry.isDirectory()) visit(path);
    else if (path.endsWith('.js') || path.endsWith('.d.ts')) {
      const target = join('dist/package', path.slice(5));
      mkdirSync(dirname(target), { recursive: true });
      writeFileSync(
        target,
        readFileSync(path, 'utf8').replace(/^\/\/# sourceMappingURL=.*$/gm, '')
      );
    }
  }
}
visit('dist');
