#!/usr/bin/env node
// FO-P0.S3.T1 — grammar-consumer smoke: proves a clean consumer can install and
// use @tatrman/grammar, WITHOUT publishing it anywhere.
//
// Per FO ⚑2 (Bora 2026-07-18: external publish only after the pipeline is set up
// and VALIDATED), this is the validation half: it `pnpm pack`s the grammar, does a
// real `npm install <tarball>` in a throwaway scratch project (exactly what an
// external consumer does after `npm i @tatrman/grammar`, minus the registry hop),
// and asserts the consumable surface:
//   - the raw grammar resolves via `@tatrman/grammar/grammar` (→ TTR.g4), so a
//     consumer can regenerate its own parser from the canonical source;
//   - the built entry exports TTR_GRAMMAR_VERSION + PROPERTY_MAP.
// The publish workflow (publish-ts.yml) runs the same pack + tarball guard; this
// adds the install-and-consume leg. Full parse-a-fixture validation rides
// @tatrman/parser (a separate package / publish), not the grammar artifact.
//
// Usage: `node scripts/grammar-consumer-smoke.mjs`  (add --skip-build to reuse dist).
import { execFileSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import {
  mkdtempSync,
  writeFileSync,
  rmSync,
  readFileSync,
  mkdirSync,
  copyFileSync,
  existsSync,
} from 'node:fs';
import { join, dirname } from 'node:path';
import { tmpdir } from 'node:os';
import { fileURLToPath } from 'node:url';

const repoRoot = join(dirname(fileURLToPath(import.meta.url)), '..');
const grammarDir = join(repoRoot, 'packages', 'grammar');
const run = (cmd, args, cwd) =>
  execFileSync(cmd, args, { cwd, stdio: ['ignore', 'pipe', 'inherit'], encoding: 'utf8' });

if (!process.argv.includes('--skip-build')) {
  // Designer builds a TS-only context. The grammar extractor must not assume
  // that another workspace package (such as Kotlin metadata) exists beside it.
  const leaf = mkdtempSync(join(tmpdir(), 'ttr-grammar-leaf-'));
  try {
    const pkg = join(leaf, 'packages', 'grammar');
    mkdirSync(join(pkg, 'scripts'), { recursive: true });
    mkdirSync(join(pkg, 'src'), { recursive: true });
    writeFileSync(join(leaf, 'package.json'), '{"type":"module"}');
    copyFileSync(join(grammarDir, 'src', 'TTR.g4'), join(pkg, 'src', 'TTR.g4'));
    for (const name of [
      'extract-property-map.ts',
      'canonical-metadata.mjs',
      'canonical-schemas.mjs',
      'generate-canonical-schemas.mjs',
    ])
      copyFileSync(join(grammarDir, 'scripts', name), join(pkg, 'scripts', name));
    run('pnpm', ['exec', 'tsx', join(pkg, 'scripts', 'extract-property-map.ts')], grammarDir);
    if (!existsSync(join(pkg, 'src', 'generated', 'property-map.ts')))
      throw new Error('Isolated grammar extraction did not emit the property map');
    for (const name of ['model-yaml.schema.json', 'profile.schema.json', 'canonical-metadata.json'])
      if (
        readFileSync(join(pkg, 'schemas', name), 'utf8') !==
        readFileSync(join(grammarDir, 'schemas', name), 'utf8')
      )
        throw new Error('Leaf schema generation differs: ' + name);
    if (existsSync(join(leaf, 'packages', 'kotlin')))
      throw new Error('Leaf grammar extraction wrote into a sibling Kotlin workspace');
    console.log('[grammar-smoke] TS-only source extraction OK');
  } finally {
    rmSync(leaf, { recursive: true, force: true });
  }

  console.log('[grammar-smoke] building @tatrman/grammar …');
  run('pnpm', ['--filter', '@tatrman/grammar', 'run', 'build'], repoRoot);
}

console.log('[grammar-smoke] packing …');
const packDirectory = mkdtempSync(join(tmpdir(), 'ttr-grammar-pack-'));
const packOut = run('pnpm', ['pack', '--pack-destination', packDirectory], grammarDir).trim();
const tarball = packOut.split('\n').pop().trim();
console.log(`[grammar-smoke] tarball: ${tarball}`);

const resourceHashes = Object.fromEntries(
  ['model-yaml.schema.json', 'profile.schema.json', 'canonical-metadata.json'].map((name) => [
    name,
    createHash('sha256')
      .update(readFileSync(join(grammarDir, 'schemas', name)))
      .digest('hex'),
  ])
);
const scratch = mkdtempSync(join(tmpdir(), 'ttr-grammar-consumer-'));
try {
  writeFileSync(
    join(scratch, 'package.json'),
    JSON.stringify(
      { name: 'grammar-consumer-smoke', version: '0.0.0', type: 'module', private: true },
      null,
      2
    )
  );
  console.log('[grammar-smoke] installing the tarball into a scratch consumer …');
  // npm (not pnpm) so this mirrors a plain external consumer and resolves the
  // tarball's own deps (antlr4ng) from the registry, as a real install would.
  run('npm', ['install', '--no-audit', '--no-fund', tarball], scratch);

  const consumer = join(scratch, 'consume.mjs');
  writeFileSync(
    consumer,
    [
      "import { createHash } from 'node:crypto';",
      "import { createRequire } from 'node:module';",
      "import { readFileSync } from 'node:fs';",
      'const require = createRequire(import.meta.url);',
      "const mod = await import('@tatrman/grammar');",
      "if (typeof mod.TTR_GRAMMAR_VERSION !== 'string' || mod.TTR_GRAMMAR_VERSION.length === 0)",
      "  throw new Error('TTR_GRAMMAR_VERSION missing/empty');",
      "if (!mod.PROPERTY_MAP || typeof mod.PROPERTY_MAP !== 'object')",
      "  throw new Error('PROPERTY_MAP missing');",
      "const g4Path = require.resolve('@tatrman/grammar/grammar');",
      "const g4 = readFileSync(g4Path, 'utf8');",
      "if (!/grammar\\s+TTR\\s*;/.test(g4)) throw new Error('TTR.g4 is not the canonical grammar');",
      'const expectedHashes = ' + JSON.stringify(resourceHashes) + ';',
      "for (const name of ['model-yaml.schema.json', 'profile.schema.json', 'canonical-metadata.json']) {",
      "  const bytes = readFileSync(require.resolve('@tatrman/grammar/schemas/' + name));",
      "  if (createHash('sha256').update(bytes).digest('hex') !== expectedHashes[name]) throw new Error('Packed schema byte mismatch: ' + name);",
      "  const resource = JSON.parse(bytes.toString('utf8'));",
      "  if ((resource['x-ttr-version'] ?? resource.version) !== mod.TTR_GRAMMAR_VERSION) throw new Error('Schema version mismatch: ' + name);",
      '}',
      "const metadata = JSON.parse(readFileSync(require.resolve('@tatrman/grammar/schemas/canonical-metadata.json'), 'utf8'));",
      "const schema = JSON.parse(readFileSync(require.resolve('@tatrman/grammar/schemas/model-yaml.schema.json'), 'utf8'));",
      "for (const kind of metadata.kinds) if (!schema.$defs['def_' + kind.kind]) throw new Error('Missing packed kind: ' + kind.kind);",
      "console.log('[grammar-smoke] consumer OK — version ' + mod.TTR_GRAMMAR_VERSION + ', ' + Object.keys(mod.PROPERTY_MAP).length + ' property entries, raw grammar resolvable');",
    ].join('\n')
  );
  run('node', ['consume.mjs'], scratch);
  console.log('[grammar-consumer-smoke] OK — @tatrman/grammar installs and consumes cleanly.');
} finally {
  rmSync(scratch, { recursive: true, force: true });
  rmSync(packDirectory, { recursive: true, force: true });
}
