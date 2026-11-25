// SPDX-License-Identifier: Apache-2.0
import { describe, it, expect } from 'vitest';
import { gzipSync } from 'node:zlib';
import { readFileSync, existsSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import path from 'node:path';

/**
 * Browser-Worker bundle-size guard (embedded-sql 2.5.3, SPIKE S0.3 / E11).
 *
 * The Designer runs the LSP as a Web Worker; only the SQL **lexers** may ride
 * along (`@tatrman/sql/lexers`), never the parsers (~+839 KB gz). This locks in
 * that decision: an accidental `@tatrman/sql` (`.`) import into `server.ts`
 * would pull the parsers and blow past the ceiling.
 *
 * Ceiling: 600 KB gz. Current bundle ≈ 498 KB gz (LSP base + both SQL lexers);
 * ~100 KB headroom for incremental growth, while a single parser import (≈+400–
 * 839 KB) trips it immediately. Adjust with the owner if the base legitimately
 * grows.
 *
 * Grammar 0.16 (AU-core-P0, 2026-10-19): the base reached ≈ 601 KB gz — the generated TTR parser grew by
 * the automation model's rules, no SQL parser involved. Ceiling 640 KB: still far below a parser leak.
 *
 * Grammar 0.17 (CN-P2, 2026-10-21): the base reached ≈ 641 KB gz — the cn model's parser rules, semantics
 * (desugar, groundings, validation, the hygiene report) and LSP features (hover, rename); still no SQL
 * parser. Ceiling 680 KB: a parser leak (≈ +400 KB) still trips it.
 *
 * Grammar 0.20 (CN-P8 review-175, 2026-10-28): the base reached ≈ 681 KB gz — the things-in-time parser rules and
 * the semantic layer's P8 lints (the IDENT word rule, `per:` coverage, same-row participant paths, dotted `from:`
 * references); still no SQL parser. Ceiling 720 KB: a parser leak (≈ +400 KB) still trips it.
 *
 * Profile and canonical YAML support (P7 S7.4b) adds standalone validators.
 * The minified bundle is about 1049 KiB gzipped. Check SQL parser exclusion
 * directly against esbuild's emitted inputs and keep 51 KiB of size headroom.
 */
const CEILING_BYTES = 1100 * 1024;

const here = path.dirname(fileURLToPath(import.meta.url));
const bundle = path.resolve(here, '../../dist/server-browser.js');
const metafile = path.resolve(here, '../../dist/browser-worker-meta.json');

interface WorkerMetafile {
  outputs: Record<string, { inputs: Record<string, { bytesInOutput: number }> }>;
}

describe('browser Worker bundle size (S0.3 / E11)', () => {
  it('keeps the generated SQL parsers and parser service out of the Worker', () => {
    const meta: WorkerMetafile = JSON.parse(readFileSync(metafile, 'utf8'));
    const inputs = meta.outputs['dist/server-browser.js'].inputs;
    const parsers = Object.entries(inputs)
      .filter(
        ([file, { bytesInOutput }]) =>
          bytesInOutput > 0 &&
          /(?:^|\/)sql\/dist\/(?:generated\/.*Parser[^/]*|parser-service)\.js$/.test(file)
      )
      .map(([file]) => file);
    expect(parsers).toEqual([]);
  });

  it('keeps the gzipped Worker within its size budget', () => {
    if (!existsSync(bundle)) {
      throw new Error(
        `dist/server-browser.js missing — run \`pnpm --filter @tatrman/lsp build\` before this test`
      );
    }
    const gz = gzipSync(readFileSync(bundle)).length;
    expect(
      gz,
      `browser bundle is ${(gz / 1024).toFixed(0)} KiB gz (ceiling ${CEILING_BYTES / 1024} KiB)`
    ).toBeLessThan(CEILING_BYTES);
  });
});
