// SPDX-License-Identifier: Apache-2.0
import { describe, expect, it } from 'vitest';
import { parseString } from '../index.js';

const SOURCE = `model db
  def query raw { skipSecurity: true, parameters: [
    { name: term, type: text, defaultValue: "", binds: [er.Customer.name, er.Customer.code] }
  ], sourceText: "select 1" }
  def query checked { skipSecurity: false }
  def query ordinary { }
`;

describe('canonical metadata carriers', () => {
  it('preserves execution policy, empty default and ordered binding references', () => {
    const r = parseString(SOURCE, 'carriers.ttrm');
    expect(r.errors.filter((e) => e.severity === 'error')).toEqual([]);
    expect(r.ast?.definitions[0]).toMatchObject({
      kind: 'query', skipSecurity: true,
      parameters: [{ name: 'term', defaultValue: '', binds: [{ path: 'er.Customer.name' }, { path: 'er.Customer.code' }] }],
    });
    expect(r.ast?.definitions[1]).toMatchObject({ skipSecurity: false });
    expect(r.ast?.definitions[2]).not.toHaveProperty('skipSecurity', expect.anything());
  });

  it('keeps lexical index policy and regex/question pairs in the same entry', () => {
    const r = parseString(`model lexicon locale en
      def pattern paired { for: db.query.raw, match: "name (.*)", text: "Name Alice?", indexSecurity: applied }
      def pattern alternate { for: db.query.raw, match: "lookup (.*)", text: "Lookup Bob?", indexSecurity: none }
    `);
    expect(r.errors.filter((e) => e.severity === 'error')).toEqual([]);
    expect(r.ast?.definitions).toMatchObject([
      { name: 'paired', match: 'name (.*)', text: 'Name Alice?', indexSecurity: 'applied' },
      { name: 'alternate', match: 'lookup (.*)', text: 'Lookup Bob?', indexSecurity: 'none' },
    ]);
  });

  it('new property keywords remain valid identifiers and reference fragments', () => {
    const r = parseString(`model er
      def entity skipSecurity { attributes: [def attribute defaultValue { type: text }, def attribute binds { type: text }] }
      def entity indexSecurity { }
    `);
    expect(r.errors.filter((e) => e.severity === 'error')).toEqual([]);
    expect(r.ast?.definitions.map((d) => d.name)).toEqual(['skipSecurity', 'indexSecurity']);
  });

  it('retains default and binding metadata on the shared procedure parameter body', () => {
    const r = parseString('model db def procedure call { parameters: [{name: p, type: text, direction: IN, defaultValue: "seed", binds: [er.Customer.name]}] }');
    expect(r.errors.filter((e) => e.severity === 'error')).toEqual([]);
    expect(r.ast?.definitions[0]).toMatchObject({kind: 'procedure', parameters: [{defaultValue: 'seed', binds: [{path: 'er.Customer.name'}]}]});
  });

  it.each([
    'def query q { skipSecurity: "true" }',
    'def query q { parameters: [{ name: p, defaultValue: true }] }',
    'def query q { parameters: [{ name: p, defaultValue: null }] }',
    'def query q { parameters: [{ name: p, binds: ["er.Customer.name"] }] }',
    'def term t { indexSecurity: true }',
  ])('rejects wrong typed input: %s', (source) => {
    expect(parseString(source).errors.some((e) => e.severity === 'error')).toBe(true);
  });
});

it.each([
  ['skipSecurity: tr', 'skipSecurity'],
  ['skipSecurity: "true"', 'skipSecurity'],
  ['skipSecurity: 1', 'skipSecurity'],
  ['parameters: [{name: p, defaultValue }]', 'defaultValue'],
  ['parameters: [{name: p, binds }]', 'binds'],
  ['parameters: [{name: p, label }]', 'label'],
])('keeps sibling definitions while editing %s', (body, field) => {
  const result = parseString(`model db def query q { ${body} } def query r {}`);
  expect(result.ast).toBeDefined();
  expect(result.errors.some((e) => e.severity === 'error')).toBe(true);
  expect(result.ast?.definitions.map((d) => d.name)).toEqual(['q', 'r']);
  const q = result.ast!.definitions[0];
  expect(q.kind).toBe('query');
  if (q.kind !== 'query') throw new Error('expected query');
  expect(field === 'skipSecurity' ? q.skipSecurity : q.parameters?.[0]?.[field as 'defaultValue' | 'binds' | 'label']).toBeUndefined();
});

it('keeps the new soft keywords as ordered cn free-form keys', () => {
  const r = parseString('model cn def kind Item { binds: x, defaultValue: y, skipSecurity: z, indexSecurity: w }');
  expect(r.errors).toEqual([]);
  expect(r.ast?.definitions[0]).toMatchObject({properties: [
    {kind: 'entry', key: 'binds'}, {kind: 'entry', key: 'defaultValue'},
    {kind: 'entry', key: 'skipSecurity'}, {kind: 'entry', key: 'indexSecurity'},
  ]});
});
