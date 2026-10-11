// SPDX-License-Identifier: Apache-2.0
import { describe, it, expect } from 'vitest';
import { fileURLToPath } from 'node:url';
import path from 'node:path';
import { parseFile } from '@tatrman/parser';
import type { QueryDef, TaggedBlockValue } from '@tatrman/parser';
import { lexSql, resolveDialect } from '../lexer-service.js';

/**
 * Lex-coverage regression (ports the SPIKE S0.2 harness): every embedded
 * SQL block in the sample corpus lexes with **zero** error tokens — tsql via the
 * `{param}` mask, postgres natively. A regression here means the mask or dialect
 * wiring broke the "restores 100% lex" guarantee.
 */
const here = path.dirname(fileURLToPath(import.meta.url));
const repoRoot = path.resolve(here, '../../../..');
const sampleFiles = [
  'samples/v1-metadata/query.ttrm',
  'samples/v1.1-metadata/billing/query.ttrm',
].map((p) => path.join(repoRoot, p));

describe('embedded-SQL lex coverage (S0.2)', () => {
  it('every tagged SQL block in the sample queries lexes with zero errors', async () => {
    let blocks = 0;
    for (const file of sampleFiles) {
      const result = await parseFile(file);
      expect(result.errors.filter((e) => e.severity === 'error')).toHaveLength(0);
      const queries = result.ast!.definitions.filter((d) => d.kind === 'query') as QueryDef[];
      for (const q of queries) {
        const st = q.sourceText;
        if (st?.kind !== 'taggedBlock') continue;
        const block = st as TaggedBlockValue;
        if (block.language !== 'SQL') continue;
        blocks++;
        const { errors } = lexSql(block.value, resolveDialect(block));
        expect(errors, `${path.basename(file)} → query ${q.name}`).toEqual([]);
      }
    }
    // Guard: the corpus actually exercised some blocks (not a vacuous pass).
    expect(blocks).toBeGreaterThan(0);
  });
});
