// SPDX-License-Identifier: Apache-2.0
import { describe, it, expect } from 'vitest';
import { readFileSync, readdirSync } from 'fs';
import path from 'path';
import { fileURLToPath } from 'url';
import { parseString } from '@tatrman/parser';
import { loadStockVocabularies } from '@tatrman/semantics/node-only';
import { dump } from './dump.js';
import { dumpSem, dumpSemDocs, renderSem, type SemDocInput } from './dump-sem.js';

// CN-P8 S8.6 (CN-D32): `variant` is the house spelling of `subkind`. One stereotype below the parser, so a
// file reads the same in either word — the AST dump differs only by `spelling`, the semantics dump not at all.
const here = path.dirname(fileURLToPath(import.meta.url));
const fixtures = path.join(here, 'fixtures');
const asSubkind = (src: string) => src.replace(/^def variant /gm, 'def subkind ');

type Def = { kind: string; name: string; spelling?: string };
const withoutSpelling = (json: string) =>
  JSON.stringify(JSON.parse(json), (k, v) => (k === 'spelling' ? undefined : v));

describe('variant = subkind (S8.6)', () => {
  const variant = readFileSync(path.join(fixtures, '122-cn-variant.ttrm'), 'utf8');

  it('records the word on each subkind def and nothing else', () => {
    const defs = (JSON.parse(dump(parseString(variant, '122.ttrm'))).definitions as Def[]).filter((d) => d.kind === 'subkind');
    expect(defs.map((d) => [d.name, d.spelling])).toEqual([
      ['Invoice', 'variant'],
      ['WebSale', 'variant'],
      ['CodeRange', 'variant'],
      ['StoredSale', undefined],
      ['Bundle', 'variant'],
      ['MissingBearer', 'variant'],
    ]);
  });

  it('dumps the same tree as its subkind spelling, but for `spelling`', () => {
    const a = parseString(variant, '122.ttrm');
    const b = parseString(asSubkind(variant), '122.ttrm');
    expect(a.errors).toEqual([]);
    expect(withoutSpelling(dump(a))).toBe(JSON.stringify(JSON.parse(dump(b))));
  });

  // review-200 R2: both words are cn free-form keys (`subkind` joined `cnKey` at 0.21.2).
  it('reads `variant:` and `subkind:` as free-form keys', () => {
    const parsed = parseString(variant, '122.ttrm');
    expect(parsed.errors).toEqual([]);
    const defs = JSON.parse(dump(parsed)).definitions as (Def & { properties: Record<string, { path?: string }> })[];
    const kit = defs.find((d) => d.name === 'Kit');
    expect(kit?.properties.variant?.path).toBe('boxed');
    expect(kit?.properties.subkind?.path).toBe('kitted');
  });

  it('reads the same semantics in either word', async () => {
    const stock = await loadStockVocabularies(['cnc-roles']);
    const sem = (src: string) => renderSem(dumpSem(parseString(src, '122.ttrm').ast, '122.ttrm', stock));
    expect(sem(variant)).toBe(sem(asSubkind(variant)));
    expect(JSON.parse(sem(variant)).diagnostics).toContain('ttr/required-property-missing');
  });

  it('fires every AM-45 rule on `variant` as on `subkind` (123 = 117 respelled)', async () => {
    const stock = await loadStockVocabularies(['cnc-roles']);
    const semDir = (dir: string) => {
      const docs: SemDocInput[] = readdirSync(path.join(fixtures, dir))
        .filter((f) => f.endsWith('.ttrm'))
        .sort()
        .map((f) => ({ ast: parseString(readFileSync(path.join(fixtures, dir, f), 'utf8'), f).ast!, uri: `${dir}/${f}` }));
      return renderSem(dumpSemDocs(docs, stock));
    };
    const respelled = semDir('123-cn-variant-rules');
    expect(respelled).toBe(semDir('117-cn-things-in-time'));
    for (const slug of ['cn/subkind-temporal-definition', 'cn/subkind-bearer', 'cn/partition-overlap', 'cn/partition-incomplete']) {
      expect(JSON.parse(respelled).diagnostics).toContain(slug);
    }
  });
});
