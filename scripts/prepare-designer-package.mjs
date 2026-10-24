// SPDX-License-Identifier: Apache-2.0
import { cpSync, mkdirSync, readFileSync, writeFileSync, rmSync, readdirSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
// Run in packages/designer. Ship the public declaration closure, never test declarations.
const output = resolve('dist/package');
rmSync(output, { recursive: true, force: true });
cpSync('dist/library', output, { recursive: true, filter: (p) => !p.endsWith('.map') });
for (const name of readdirSync(output).filter((n) => n.endsWith('.js'))) {
  const path = resolve(output, name);
  writeFileSync(path, readFileSync(path, 'utf8').replace(/^\/\/# sourceMappingURL=.*$/gm, ''));
}
const seen = new Set();
function copy(path) {
  if (seen.has(path)) return;
  seen.add(path);
  const source = resolve('dist', path),
    text = readFileSync(source, 'utf8');
  const target = resolve(output, 'types', path);
  mkdirSync(dirname(target), { recursive: true });
  writeFileSync(target, text);
  for (const [, spec] of text.matchAll(/(?:from\s*|import\s*\(\s*|import\s*)['"](\.[^'"]+)['"]/g)) {
    const next = resolve(dirname(source), spec.replace(/\.js$/, '.d.ts'));
    if (!next.startsWith(resolve('dist') + '/')) throw new Error(`Declaration escaped: ${spec}`);
    copy(next.slice(resolve('dist').length + 1));
  }
}
copy('index.d.ts');
copy('extensions.d.ts');
