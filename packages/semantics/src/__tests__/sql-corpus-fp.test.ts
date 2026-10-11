// SPDX-License-Identifier: Apache-2.0
import { describe, it, expect } from 'vitest';
import { fileURLToPath } from 'node:url';
import path from 'node:path';
import fs from 'node:fs';
import { parseFile, parseString, type QueryDef } from '@tatrman/parser';
import { parseSql, extract } from '@tatrman/sql';
import { resolveDialect, maskPlaceholders } from '@tatrman/sql/lexers';
import { ProjectSymbolTable } from '../project-symbols.js';
import { parseSqlConfig } from '../sql-config.js';
import { resolveSqlReferences } from '../sql/resolve.js';

/**
 * 3.4.6 — false-positive pass over the sample corpus. The resolver is run over
 * every embedded-SQL query in `samples/v1-metadata` against a representative
 * `[sql]` config + the sample `db` model. The guard that matters: a masked
 * `{param}` placeholder must NEVER be reported as a column (the one true
 * resolver-level FP we fixed). Genuine model↔SQL mismatches in the sample (e.g.
 * SQL reading TPC-DS's denormalised `item.i_brand`, which the model normalises out
 * into `item_brand`) are expected and documented in
 * `packages/sql/LAZY-PATCH.md`; the remaining residual (SELECT-alias-in-ORDER-BY)
 * is tracked there too.
 */
const here = path.dirname(fileURLToPath(import.meta.url));
const repoRoot = path.resolve(here, '../../../..');
const sampleDir = path.join(repoRoot, 'samples/v1-metadata');

// (WH, dbo) ⇄ db.ttrm's namespace `dbo`; tsql defaults so bare `FROM QFOO` maps.
const config = parseSqlConfig({
  sql: {
    'default-dialect': 'tsql',
    'namespace-map': [{ namespace: 'dbo', database: 'WH', schema: 'dbo' }],
    defaults: { tsql: { database: 'WH', schema: 'dbo' } },
  },
}).config;

describe('SQL resolver false-positive corpus pass (3.4.6)', () => {
  it('never reports a masked {param} placeholder as a column', async () => {
    const symbols = new ProjectSymbolTable();
    const dbText = fs.readFileSync(path.join(sampleDir, 'db.ttrm'), 'utf8');
    symbols.upsertDocument('file:///s/db.ttrm', parseString(dbText).ast!, 'db', 'dbo', '');

    const q = await parseFile(path.join(sampleDir, 'query.ttrm'));
    const queries = q.ast!.definitions.filter((d): d is QueryDef => d.kind === 'query');

    let analyzed = 0;
    let totalDiagnostics = 0;
    for (const def of queries) {
      const st = def.sourceText;
      if (st?.kind !== 'taggedBlock' || st.language !== 'SQL') continue;
      analyzed++;
      const dialect = resolveDialect(st, config);
      const { tree } = parseSql(st.value, dialect);
      if (!tree) continue;
      const model = extract(tree, dialect);
      const placeholders = maskPlaceholders(st.value).placeholders;
      const diags = resolveSqlReferences(model, { dialect, config, symbols, placeholders });
      totalDiagnostics += diags.length;

      // The FP guard: no diagnostic span may fall inside a {param} placeholder.
      for (const d of diags) {
        const inParam = placeholders.some(
          (p) => d.span.offset >= p.offset && d.span.offset < p.offset + p.length,
        );
        expect(inParam, `${def.name}: diagnostic on a {param} placeholder: ${d.message}`).toBe(false);
      }
    }

    // Sanity: resolution actually ran over a real corpus (not silently skipped).
    expect(analyzed).toBeGreaterThan(20);
    expect(totalDiagnostics).toBeGreaterThan(0);
    // Full-grammar ANTLR SQL parse over 20+ embedded blocks runs ~1.7s locally
    // but ~7.4s on CI runners — past the 5s default. Give generous headroom.
  }, 30_000);
});
