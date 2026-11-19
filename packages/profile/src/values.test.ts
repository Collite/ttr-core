// SPDX-License-Identifier: Apache-2.0
import { describe, it, expect, vi } from 'vitest';
import { ForeignTree, JsonPointer, NumericScalar, type Json } from './input.js';
import { Record, PlannedOp, type RecordPlan, type CanonicalBuilder } from './engine.js';
import { ValuesOp, ShapeOp, EachOp, JoinPairsOp, LinkOp, fillTemplate, Missing } from './values.js';
import { LinkIndex } from './links.js';

function harness(yaml: string, operation: { [key: string]: Json }) {
  const tree = ForeignTree.read(yaml, 'dialect.yaml');
  const record = new Record(tree.root, 'entity', 'R');
  const op = new PlannedOp({
    value: { in: 'entity', at: '/v', to: { property: 'label' }, ...operation },
    location: tree.root.span(),
    locations: new Map(),
  });
  const consumption = { open: vi.fn(), consume: vi.fn() };
  const warnings: RecordPlan['warnings'] = [];
  const plan = { records: [record], consumption, warnings } as unknown as RecordPlan;
  const builder = { write: vi.fn(), import: vi.fn() } as unknown as CanonicalBuilder;
  const output = () => vi.mocked(builder.write).mock.calls[0]?.[3];
  return { tree, record, op, plan, builder, output, consumption, warnings };
}
describe('values parity', () => {
  it.each(['v: no\n', 'v: null\n', 'other: 1\n'])(
    'defaults for unmapped, null and missing: %s',
    (yaml) => {
      const h = harness(yaml, { op: 'values', mapping: { yes: 1 }, default: null });
      new ValuesOp(h.op).apply(h.plan, h.builder);
      expect(h.output()).toBe(null);
      expect(vi.mocked(h.builder.write).mock.calls[0][4].path).toBe(
        yaml.startsWith('other') ? '' : '/v'
      );
    }
  );
  it('maps numeric and boolean scalars and handles prototype-like foreign names', () => {
    for (const [value, mapping] of [
      ['true', { true: 'ok' }],
      ['42', { '42': 'ok' }],
      ['constructor', { constructor: 'ok' }],
    ] as [string, { [key: string]: Json }][]) {
      const h = harness(`v: ${value}`, { op: 'values', mapping });
      new ValuesOp(h.op).apply(h.plan, h.builder);
      expect(h.output()).toBe('ok');
    }
    const h = harness('v: toString', { op: 'values', mapping: {} });
    expect(() => new ValuesOp(h.op).apply(h.plan, h.builder)).toThrow(
      expect.objectContaining({ code: 'ys/value-unmapped' })
    );
  });
  it('does not let default mask structured input and locates the key', () => {
    const h = harness('pad: 😀\nv: {x: 1}\n', { op: 'values', mapping: {}, default: 'x' });
    expect(() => new ValuesOp(h.op).apply(h.plan, h.builder)).toThrow(
      expect.objectContaining({
        code: 'ys/value-unmapped',
        location: expect.objectContaining({ file: 'dialect.yaml', line: 2, column: 1, path: '/v' }),
      })
    );
  });
  it.each([
    [' ; ', ' a ; b;; c ', ['a', 'b', 'c']],
    ['  ', ' a\tb  c ', ['a', 'b', 'c']],
    ['.', 'a.b..', ['a', 'b']],
  ])('splits literally and trims %s', (split, text, expected) => {
    const h = harness(`v: ${JSON.stringify(text)}`, { op: 'values', split });
    new ValuesOp(h.op).apply(h.plan, h.builder);
    expect(h.output()).toEqual(expected);
  });
  it('split ignores null/missing and refuses nonstrings', () => {
    for (const yaml of ['v: null', 'x: 1']) {
      const h = harness(yaml, { op: 'values', split: ',' });
      new ValuesOp(h.op).apply(h.plan, h.builder);
      expect(h.builder.write).not.toHaveBeenCalled();
    }
    const h = harness('v: 42', { op: 'values', split: ',' });
    expect(() => new ValuesOp(h.op).apply(h.plan, h.builder)).toThrow(
      expect.objectContaining({
        code: 'ys/split-not-a-string',
        location: expect.objectContaining({ path: '/v', column: 1 }),
      })
    );
  });
});
describe('shape recursive missing inputs', () => {
  it('drops containers whose only input is missing despite literals', () => {
    const result = fillTemplate(
      { outer: [{ literal: 1, missing: { input: '/absent' } }], literal: 2 },
      () => undefined,
      vi.fn()
    );
    expect(result).toEqual({ value: Missing, inputs: true });
  });
  it('keeps literal empty containers and populated siblings', () => {
    const tree = ForeignTree.read('x: null\ny: yes');
    const consume = vi.fn();
    const result = fillTemplate(
      { empty: {}, list: [], missing: { input: '/x' }, yes: { input: '/y' } },
      (s) => new JsonPointer(s).resolve(tree.root, true),
      consume
    );
    expect(result.value).toEqual({ empty: {}, list: [], yes: 'yes' });
    expect(consume).toHaveBeenCalledTimes(2);
  });
  it('shape inputs are relative to at; opens rather than consuming container', () => {
    const h = harness('v: {x: ok, spare: ignored}', {
      op: 'shape',
      template: { nested: { input: '/x' }, absent: { input: '/missing' } },
    });
    new ShapeOp(h.op).apply(h.plan, h.builder);
    expect(h.output()).toEqual({ nested: 'ok' });
    expect(h.consumption.open.mock.calls[0][0].path).toBe('/v');
    expect(h.consumption.consume.mock.calls.map(([n]) => n.path)).toEqual(['/v/x']);
  });
  it('literal template consumes at but null/missing base writes nothing', () => {
    for (const yaml of ['v: null', 'x: 1', 'v: {}']) {
      const h = harness(yaml, { op: 'shape', template: {} });
      new ShapeOp(h.op).apply(h.plan, h.builder);
      expect(vi.mocked(h.builder.write).mock.calls.length).toBe(yaml === 'v: {}' ? 1 : 0);
    }
  });
});
describe('each amended key behaviour', () => {
  it('reads item and record inputs, drops missing shapes, consumes the list whole', () => {
    const h = harness('name: Record\nv:\n  - x: first\n  - spare: unused\n  - x: last\n', {
      op: 'each',
      shape: { item: { input: '$item/x' } },
    });
    new EachOp(h.op).apply(h.plan, h.builder);
    expect(h.output()).toEqual([{ item: 'first' }, { item: 'last' }]);
    expect(h.consumption.consume.mock.calls[0][0].path).toBe('/v');
    const k = harness('name: Record\nv: [one, two]', {
      op: 'each',
      shape: { name: { input: '/name' }, item: { input: '$item' } },
    });
    new EachOp(k.op).apply(k.plan, k.builder);
    expect(k.output()).toEqual([
      { name: 'Record', item: 'one' },
      { name: 'Record', item: 'two' },
    ]);
  });
  it('keeps bytewise-first duplicate and warns once per message at list key', () => {
    const h = harness(
      'v:\n  - {key: en, text: 😀}\n  - {key: en, text: \ue000}\n  - {key: en, text: z}\n  - {text: zz}\n  - {text: a}\n',
      { op: 'each', key: '/key', missing: 'en', shape: { input: '$item/text' } }
    );
    new EachOp(h.op).apply(h.plan, h.builder);
    expect(h.output()).toEqual({ en: 'a' });
    expect(h.warnings.map((w) => w.message)).toEqual([
      'each key repeated (en)',
      'each key missing → en',
    ]);
    for (const w of h.warnings)
      expect(w).toEqual(
        expect.objectContaining({
          record: 'R',
          location: expect.objectContaining({ path: '/v', line: 1, column: 1 }),
        })
      );
  });
  it('checks keys after dropping missing templates; accepts empty and prototype keys', () => {
    const h = harness(
      'v: [{spare: 1}, {text: skip}, {key: "", text: empty}, {key: __proto__, text: safe}]',
      { op: 'each', key: '/key', shape: { input: '$item/text' } }
    );
    new EachOp(h.op).apply(h.plan, h.builder);
    expect(h.output()).toEqual(JSON.parse('{"":"empty","__proto__":"safe"}'));
    expect(h.warnings.map((w) => w.message)).toEqual(['each key missing → skipped']);
  });
  it.each([undefined, '/key'])('writes empty list/object with key %s', (key) => {
    const h = harness('v: []', { op: 'each', shape: { input: '$item' }, ...(key ? { key } : {}) });
    new EachOp(h.op).apply(h.plan, h.builder);
    expect(h.output()).toEqual(key ? {} : []);
  });
  it('compares nonstrings by JSON text', () => {
    const h = harness('v: [{k: en, x: 2}, {k: en, x: 10}]', {
      op: 'each',
      key: '/k',
      shape: { input: '$item/x' },
    });
    new EachOp(h.op).apply(h.plan, h.builder);
    expect(h.output()).toEqual({ en: new NumericScalar(10, '10') });
  });
  it('refuses scalar at the authored key', () => {
    const h = harness('v: no', { op: 'each', shape: {} });
    expect(() => new EachOp(h.op).apply(h.plan, h.builder)).toThrow(
      expect.objectContaining({
        code: 'ys/each-not-a-list',
        location: expect.objectContaining({ path: '/v', column: 1 }),
      })
    );
  });
});
describe('join-pairs and link position parity', () => {
  it('preserves ordered Unicode two-part equality pairs', () => {
    const h = harness('v: "Číslo.x = b.y aNd c.z=d.w"', { op: 'join-pairs' });
    new JoinPairsOp(h.op).apply(h.plan, h.builder);
    expect(h.output()).toEqual([
      { from: { ref: 'er.entity.Číslo.x' }, to: { ref: 'er.entity.b.y' } },
      { from: { ref: 'er.entity.c.z' }, to: { ref: 'er.entity.d.w' } },
    ]);
  });
  it.each([
    '',
    'a.x=b.y AND',
    'a.x=b.y OR c.z=d.w',
    'a.b.c = d.e',
    'a.x != b.y',
    'a.x=b.y AND AND c.z=d.w',
  ])('refuses malformed join %s at value', (text) => {
    const h = harness(`v: ${JSON.stringify(text)}`, { op: 'join-pairs' });
    expect(() => new JoinPairsOp(h.op).apply(h.plan, h.builder)).toThrow(
      expect.objectContaining({
        code: 'xm/join-pair-shape',
        location: expect.objectContaining({ path: '/v', line: 1, column: 4 }),
      })
    );
  });
  it('links arrays with referent-kind filtering and adds foreign imports', () => {
    const h = harness('v: [A, B]', {
      op: 'link',
      by: 'sourceName',
      to: { path: ['target', 'table'] },
    });
    const index = new LinkIndex([
      { qname: 'one.db.dbo.A', kind: 'table', pkg: 'one', sourceName: 'A' },
      { qname: 'one.er.entity.A', kind: 'entity', pkg: 'one', sourceName: 'A' },
      { qname: 'two.db.dbo.B', kind: 'table', pkg: 'two', sourceName: 'B' },
    ]);
    new LinkOp(h.op, index, 'one').apply(h.plan, h.builder);
    expect(h.output()).toEqual(['one.db.dbo.A', 'two.db.dbo.B']);
    expect(h.builder.import).toHaveBeenCalledWith(h.plan, h.record, 'two');
  });
  it('distinguishes explicit kind missing from unresolved and locates list elements', () => {
    const h = harness('v:\n  - A', { op: 'link', by: 'sourceName', kind: 'table' });
    const index = new LinkIndex([
      { qname: 'p.er.entity.A', kind: 'entity', pkg: 'p', sourceName: 'A' },
    ]);
    expect(() => new LinkOp(h.op, index, 'p').apply(h.plan, h.builder)).toThrow(
      expect.objectContaining({
        code: 'xm/link-kind-missing',
        location: expect.objectContaining({ path: '/v/0', line: 2, column: 5 }),
      })
    );
  });
});
