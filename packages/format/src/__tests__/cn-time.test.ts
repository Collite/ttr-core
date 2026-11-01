// SPDX-License-Identifier: Apache-2.0
import { readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';
import { parseString, type ConceptDef } from '@tatrman/parser';
import { DEFAULT_FORMAT_CONFIG, format, formatDocument } from '../index.js';

describe('CN-P8 formatting', () => {
  it('preserves every shape and reaches a fixed point', () => {
    const source = readFileSync(new URL('../../../../tests/conformance/fixtures/115-cn-things-in-time.ttrm', import.meta.url), 'utf8');
    const output = format(source, 'time.ttrm');
    expect(format(output, 'time.ttrm')).toBe(output);
    const shape = (s: string) => JSON.parse(JSON.stringify(parseString(s).ast!.definitions, (key, value) => {
      if (key === 'source' || key.endsWith('Source') || key.endsWith('Trivia') || key === 'lineOffsets') return undefined;
      if (key === 'properties' && Array.isArray(value)) return [...value].sort((a, b) => a.key.localeCompare(b.key));
      return value;
    }));
    expect(shape(output)).toEqual(shape(source));
  });

  it('orders bounds and projection references canonically', () => {
    const output = format('model cn\ndef phase OpenOrder { until: Delivered, from: Placed, of: Order }\ndef quality price { from: ListPrice.price, per: Channel, of: Item, valued_state: true }', 'time.ttrm');
    expect(output.indexOf('from: Placed')).toBeLessThan(output.indexOf('until: Delivered'));
    expect(output.indexOf('from: ListPrice.price')).toBeLessThan(output.indexOf('per: Channel'));
  });
});

describe('CN-P8 S8.6 variant (CN-D32)', () => {
  it("keeps each author's word and reaches a fixed point", () => {
    const source = readFileSync(new URL('../../../../tests/conformance/fixtures/122-cn-variant.ttrm', import.meta.url), 'utf8');
    const output = format(source, 'variant.ttrm');
    expect(format(output, 'variant.ttrm')).toBe(output);
    expect(output).toMatch(/^def variant Invoice /m);
    expect(output).toMatch(/^def subkind StoredSale /m);
    expect(output).toMatch(/^def quality variant /m);
  });

  it('writes `variant` for a subkind def that records no word (a synthesized def)', () => {
    const source = 'model cn\ndef subkind WebSale { of: Sale }';
    const ast = parseString(source).ast!;
    delete (ast.definitions[0] as ConceptDef).spelling;
    expect(formatDocument(ast, source, DEFAULT_FORMAT_CONFIG)).toMatch(/^def variant WebSale /m);
  });
});
