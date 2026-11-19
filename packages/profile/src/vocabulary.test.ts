// SPDX-License-Identifier: Apache-2.0
import { readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';
import { parse } from 'yaml';
import { NumericScalar, jsonText } from './scalars.js';
import { canonicalWrite, normalizeCanonical } from './vocabulary.js';

const oracle = new URL(
  '../../kotlin/ttr-profile/src/test/resources/demo-cs/expected/',
  import.meta.url
);
describe('canonical profile codec', () => {
  it.each(['unordered-entity', 'ordered-kind'])(
    'matches Kotlin %s free-key codec bytes (D-MW-107)',
    (name) => {
      const fixture = new URL(
        `../../../tests/conformance/dialect/integer-keys/codec/${name}`,
        import.meta.url
      );
      const raw = JSON.parse(readFileSync(new URL(fixture.href + '.json'), 'utf8'));
      const expected = readFileSync(new URL(fixture.href + '.yaml'), 'utf8');
      expect(canonicalWrite(raw)).toBe(expected);
      const normalized = normalizeCanonical(raw);
      expect(canonicalWrite(normalized)).toBe(expected);
      const properties = (
        normalized.defs as { properties: Map<string, unknown> | Record<string, unknown>[] }[]
      )[0].properties;
      const keys =
        properties instanceof Map ? [...properties.keys()] : properties.flatMap(Object.keys);
      expect(keys).toEqual(name === 'unordered-entity' ? ['10', '9', 'b'] : ['10', '2', 'b', '1']);
      if (name === 'unordered-entity') expect(properties).toBeInstanceOf(Map);
    }
  );

  it.each(['search', 'concept'])('keeps a numeric %s block value scalar', (property) => {
    const bytes = canonicalWrite({
      ttr: '0.22',
      model: 'er',
      defs: [{ kind: 'attribute', id: 'A', properties: { [property]: new NumericScalar(5, '5') } }],
    });
    expect(bytes).toContain(`${property}: 5\n`);
    expect(bytes).not.toContain('text:');
    expect(bytes).not.toContain('value:');
  });
  it('keeps non-mapping entries in an ordered body scalar', () => {
    const bytes = canonicalWrite({
      ttr: '0.22',
      model: 'er',
      defs: [{ kind: 'attribute', id: 'A', properties: { search: [new NumericScalar(5, '5')] } }],
    });
    expect(bytes).toContain('search:\n        - 5\n');
    expect(bytes).not.toContain('text:');
  });
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
    expect([
      ...(value.defs as { properties: Map<string, unknown> }[])[0].properties.keys(),
    ]).toEqual(['description', 'tags', 'label']);
  });
  it('quotes ambiguous keys and preserves multiline indentation and trailing newlines', () => {
    const raw = {
      ttr: '0.22',
      package: 'demo',
      model: 'cn',
      defs: [{ kind: 'kind', id: 'A', properties: { true: 'yes', description: '  content\n\n' } }],
    };
    const bytes = canonicalWrite(raw);
    expect(parse(bytes)).toEqual(JSON.parse(jsonText(normalizeCanonical(raw))));
    expect(bytes).toContain('|2+');
    expect(bytes).toContain('"true": "yes"');
  });
});
