// SPDX-License-Identifier: Apache-2.0
import { readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';
import { parseString } from '../index.js';
import { conceptProperty } from '../concept.js';
import type { ConceptDef } from '../ast.js';

const fixture = readFileSync(new URL('../../../../tests/conformance/fixtures/115-cn-things-in-time.ttrm', import.meta.url), 'utf8');

describe('CN-P8 things in time syntax', () => {
  it('keeps all four temporal shapes and ordered typed references with exact spans', () => {
    const result = parseString(fixture, 'time.ttrm');
    expect(result.errors).toEqual([]);
    const defs = result.ast!.definitions as ConceptDef[];
    expect(defs.filter((d) => d.stereotype === 'subkind').map((d) => d.name)).toEqual(['Invoice', 'WebSale', 'CodeRange', 'StoredSale']);
    const phase = defs.find((d) => d.name === 'OpenOrder')!;
    const quality = defs.find((d) => d.name === 'list_price')!;
    expect(phase.properties.map((p) => [p.kind, p.key])).toEqual([['entry', 'of'], ['from', 'from'], ['until', 'until']]);
    expect(conceptProperty(quality, 'per')!.ref.path).toBe('Channel');
    expect(conceptProperty(quality, 'from')!.ref.parts).toEqual(['ListPrice', 'price']);
    for (const p of [...phase.properties, ...quality.properties]) {
      expect(fixture.slice(p.source.offsetStart, p.source.offsetEnd)).toMatch(new RegExp(`^${p.key}:`));
      if (p.kind === 'from' || p.kind === 'until' || p.kind === 'per') {
        expect(fixture.slice(p.ref.source.offsetStart, p.ref.source.offsetEnd)).toBe(p.ref.path);
      }
    }
    expect(conceptProperty(defs.find((d) => d.name === 'Supplier')!, 'involves')!.participants).toHaveLength(2);
  });

  it('leaves per-kind validity to S8.2 and rejects malformed typed references', () => {
    for (const name of ['01-subkind-no-bearer', '02-phase-no-until', '03-per-without-valued-state']) {
      const source = readFileSync(new URL(`../../../kotlin/ttr-parser/src/test/resources/cn-negative/time/${name}.ttrm`, import.meta.url), 'utf8');
      expect(parseString(source).errors, name).toEqual([]);
    }
    expect(parseString('model cn\ndef phase P { of: Order, from: Started }').errors).toEqual([]);
    expect(parseString('model cn\ndef quality q { of: Item, per: Channel }').errors).toEqual([]);
    for (const body of ['from: [Started]', 'until: true', 'per: {}']) {
      expect(parseString(`model cn\ndef phase P { of: Order, ${body} }`).errors.length).toBeGreaterThan(0);
    }
  });

  it.each(['cnc', 'automation'])('keeps lifetime role properties out of model %s', (model) => {
    for (const property of ['involves: [Item]', 'period: { start: since, end: until }']) {
      expect(parseString(`model ${model}\ndef role Reader { ${property} }`).errors.some((e) => e.code === 'ttr/unknown-property')).toBe(true);
    }
  });
});
