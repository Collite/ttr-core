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
 */
const CEILING_BYTES = 640 * 1024;

const here = path.dirname(fileURLToPath(import.meta.url));
const bundle = path.resolve(here, '../../dist/server-browser.js');

describe('browser Worker bundle size (S0.3 / E11)', () => {
  it('server-browser.js gzips under the 640 KB ceiling (no parser leak)', () => {
    if (!existsSync(bundle)) {
      throw new Error(
        `dist/server-browser.js missing — run \`pnpm --filter @tatrman/lsp build\` before this test`,
      );
    }
    const gz = gzipSync(readFileSync(bundle)).length;
    expect(
      gz,
      `browser bundle is ${(gz / 1024).toFixed(0)} KB gz (ceiling ${CEILING_BYTES / 1024} KB) — ` +
        `did a SQL parser get imported into server.ts? Use @tatrman/sql/lexers, not @tatrman/sql.`,
    ).toBeLessThan(CEILING_BYTES);
  });
});
