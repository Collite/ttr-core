// SPDX-License-Identifier: Apache-2.0
import { createRequire } from 'node:module';
import { readFileSync, readdirSync, writeFileSync, realpathSync, existsSync } from 'node:fs';
import { dirname, resolve, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { family } from './designer-family.mjs';
const root = fileURLToPath(new URL('../', import.meta.url));
const seen = new Set(),
  notices = [];
function visit(path) {
  path = realpathSync(path);
  if (seen.has(path)) return;
  seen.add(path);
  const p = JSON.parse(readFileSync(path, 'utf8')),
    dir = dirname(path),
    require = createRequire(path);
  if (!p.name.startsWith('@tatrman/')) {
    const files = readdirSync(dir).filter((f) => /^(licen[cs]e|copying|notice)(\.|$)/i.test(f));
    if (!files.length) throw new Error(`Missing third-party license: ${p.name}@${p.version}`);
    notices.push(
      `Dependency: ${p.name}@${p.version} (${p.license ?? 'see text'})\n` +
        files.map((f) => `--- ${f} ---\n${readFileSync(join(dir, f), 'utf8')}`).join('\n')
    );
  }
  for (const name of Object.keys({ ...p.dependencies, ...p.optionalDependencies })) {
    let next;
    try {
      next = require.resolve(`${name}/package.json`);
    } catch {
      // Package exports may hide package.json; find its directory through pnpm's installed link.
      const link = join(dir, 'node_modules', name, 'package.json');
      if (existsSync(link)) next = link;
      else {
        for (const base of require.resolve.paths(name) ?? [])
          if (existsSync(join(base, name, 'package.json'))) {
            next = join(base, name, 'package.json');
            break;
          }
      }
      if (!next && p.optionalDependencies?.[name]) continue;
      if (!next) throw new Error(`Cannot inspect ${p.name} -> ${name}`);
    }
    visit(next);
  }
}
for (const name of family) visit(resolve(root, 'packages', name, 'package.json'));
// esbuild inputs also contain generated code from vendored grammars, not npm packages.
for (const path of [
  'tsql/TSqlLexer.g4',
  'tsql/TSqlParser.g4',
  'postgresql/PostgreSQLLexer.g4',
  'postgresql/PostgreSQLParser.g4',
  'postgresql/PostgreSQLLexerBase.ts',
  'postgresql/PostgreSQLParserBase.ts',
]) {
  const source = readFileSync(resolve(root, 'packages/sql/vendor', path), 'utf8');
  const headers = [...source.matchAll(/\/\*[\s\S]*?\*\//g)]
    .map((m) => m[0])
    .filter((h) => /Copyright|MIT [Ll]icense/.test(h));
  if (!headers.length) throw new Error(`Missing vendored license: ${path}`);
  notices.push(
    `Vendored antlr/grammars-v4: ${path}\nPinned commit: 923a1a90f3b9e28c8ac08e170e582ceb15d6f0a9\n${headers.join('\n')}`
  );
}
notices.sort();
const notice =
  `Tatrman — Table Transformation Manager\nCopyright 2026 Collite\n\nLicensed under the Apache License, Version 2.0; see LICENSE.\n\nThe Designer family distributes bundled worker/compiler code and uses the runtime\ndependencies below. This cumulative notice includes their license texts; some\ndependencies are installed separately rather than embedded in every family member.\n\n` +
  notices.join('\n\n========================================\n\n') +
  '\n';
for (const name of family.filter((n) => n !== 'edit')) {
  writeFileSync(resolve(root, 'packages', name, 'LICENSE'), readFileSync(resolve(root, 'LICENSE')));
  writeFileSync(resolve(root, 'packages', name, 'NOTICE'), notice.replace(/\r\n?/g, '\n'));
}
console.log(`Prepared family licenses: ${notices.length} third-party package versions.`);
