// SPDX-License-Identifier: Apache-2.0
import { readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';
import { parse } from 'yaml';
import { canonicalWrite, normalizeCanonical } from './vocabulary.js';

const oracle = new URL(
  '../../kotlin/ttr-profile/src/test/resources/demo-cs/expected/',
  import.meta.url
);
describe('canonical profile codec', () => {
  for (const fixture of [
    '01-sequence/er.yaml',
    '03-mixed/er.yaml',
    '03-mixed/binding.yaml',
    '06-table/db.yaml',
    '08-values/er.yaml',
  ]) {
    it(`preserves committed Kotlin bytes: ${fixture}`, () => {
      const bytes = readFileSync(new URL(fixture, oracle), 'utf8');
      expect(canonicalWrite(parse(bytes))).toBe(bytes);
    });
  }
  it('drops empty imports and orders grammar properties before UTF-8 free keys', () => {
    const value = normalizeCanonical({
      ttr: '0.22',
      package: 'demo',
      imports: [],
      model: 'er',
      defs: [
        {
          kind: 'entity',
          id: 'A',
          properties: { tags: ['a'], description: 'x', label: { cs: 'A' } },
        },
      ],
    });
    expect(value.imports).toBeUndefined();
    expect(Object.keys((value.defs as { properties: object }[])[0].properties)).toEqual([
      'description',
      'tags',
      'label',
    ]);
  });
  it('quotes ambiguous keys and preserves multiline indentation and trailing newlines', () => {
    const raw = {
      ttr: '0.22',
      package: 'demo',
      model: 'cn',
      defs: [{ kind: 'kind', id: 'A', properties: { true: 'yes', description: '  content\n\n' } }],
    };
    const bytes = canonicalWrite(raw);
    expect(parse(bytes)).toEqual(normalizeCanonical(raw));
    expect(bytes).toContain('|2+');
    expect(bytes).toContain('"true": "yes"');
  });
});
