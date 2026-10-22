// SPDX-License-Identifier: Apache-2.0
import { describe, it, expect } from 'vitest';
import { parseString } from '@tatrman/parser';
import { formatDocument, DEFAULT_FORMAT_CONFIG } from '../index.js';

function fmt(src: string): string {
  const parsed = parseString(src, 'binding.ttrm');
  expect(parsed.errors).toEqual([]);
  return formatDocument(parsed.ast!, src, DEFAULT_FORMAT_CONFIG);
}

describe('HP-P1 formatter', () => {
  for (const src of [
    'model binding\ndef md2er_dimension c { attributes: { code: sk, state: address.state }, entity: er.entity.customer, dimension: md.Customer }',
    'model binding\ndef md2er_cubelet c { measures: { revenue: amount }, attributes: { Customer.code: id }, target: er.entity.sales, cubelet: md.sales }',
    'model binding\ndef md2er_dimension c { dimension: md.Customer, /* retain this */ entity: er.entity.customer, attributes: { code: sk } }',
  ]) it('preserves the parsed fields and is idempotent', () => {
    const out = fmt(src);
    expect(fmt(out)).toBe(out);
    const fields = (s: string) => JSON.parse(JSON.stringify(parseString(s, 'binding.ttrm').ast!.definitions, (k, v) => k === 'source' || k.endsWith('Trivia') ? undefined : v));
    expect(fields(out)).toEqual(fields(src));
  });
});
