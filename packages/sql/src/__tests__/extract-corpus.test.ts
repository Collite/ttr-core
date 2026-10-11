// SPDX-License-Identifier: Apache-2.0
import { describe, it, expect } from 'vitest';
import { fileURLToPath } from 'node:url';
import path from 'node:path';
import { parseFile } from '@tatrman/parser';
import type { QueryDef, TaggedBlockValue } from '@tatrman/parser';
import { parseSql } from '../parser-service.js';
import { extract } from '../adapters/index.js';
import { resolveDialect } from '../lexer-service.js';

/**
 * Extraction corpus regression (3.2.6): `parseSql` → `extract` over every
 * embedded SQL block in the sample corpus must never throw, and the parse must
 * be clean (the spike parsed 100%). Any new failing construct goes in
 * `packages/sql/LAZY-PATCH.md` (DESIGN §12.7) — which starts empty.
 */
const here = path.dirname(fileURLToPath(import.meta.url));
const repoRoot = path.resolve(here, '../../../..');
const sampleFiles = [
  'samples/v1-metadata/query.ttrm',
  'samples/v1.1-metadata/billing/query.ttrm',
].map((p) => path.join(repoRoot, p));

describe('SQL extraction corpus regression (3.2.6)', () => {
  it('parses + extracts every tagged SQL block with no throw and a clean parse', async () => {
    let blocks = 0;
    for (const file of sampleFiles) {
      const result = await parseFile(file);
      const queries = result.ast!.definitions.filter((d) => d.kind === 'query') as QueryDef[];
      for (const q of queries) {
        const st = q.sourceText;
        if (st?.kind !== 'taggedBlock') continue;
        const block = st as TaggedBlockValue;
        if (block.language !== 'SQL') continue;
        blocks++;
        const dialect = resolveDialect(block);
        const { tree, parseErrors } = parseSql(block.value, dialect);
        expect(tree, `${path.basename(file)} → ${q.name} produced no tree`).not.toBeNull();
        // never throws:
        const model = extract(tree!, dialect);
        expect(model.tables.length, `${q.name}: at least one table`).toBeGreaterThan(0);
        expect(parseErrors, `${path.basename(file)} → ${q.name} parse errors`).toEqual([]);
      }
    }
    expect(blocks).toBeGreaterThan(0);
    // Cold-JIT full-grammar SQL parse (T-SQL/PostgreSQL ANTLR) runs ~2.3s locally
    // but ~5.6s on CI runners — past the 5s default. Give generous headroom.
  }, 30_000);
});
