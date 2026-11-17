// SPDX-License-Identifier: Apache-2.0
import { describe, expect, it } from 'vitest';
import { parseDocument, visit, isNode } from 'yaml';
import { parseYamlDocument, parseString } from '../index.js';

const envelope = 'ttr: "0.22"\nmodel: er\n';
const source = `${envelope}defs:\n  - kind: entity\n    id: Customer\n    properties:\n      description: {cs: Zákazník, en: Customer}\n      attributes:\n        - id: name\n          properties: {type: text}\n`;
const portable = (ast: unknown) =>
  JSON.parse(
    JSON.stringify(ast, (key, value) =>
      [
        'source',
        'leadingTrivia',
        'trailingTrivia',
        'tagSource',
        'valueSource',
        'textSource',
        'lineOffsets',
        'indentWidth',
        'escapes',
        'textMapping',
      ].includes(key)
        ? undefined
        : value
    )
  );

describe('canonical YAML reader contract', () => {
  it('builds the native typed AST, including ordered nested definitions and localized text', () => {
    const result = parseYamlDocument(source, 'customer.yaml');
    expect(result.diagnostics).toEqual([]);
    expect(portable(result.ast)).toEqual(
      portable(
        parseString(
          'model er def entity Customer { description: {cs: "Zákazník", en: "Customer"}, attributes: [def attribute name {type: text}] }'
        ).ast
      )
    );
  });

  it('locates every AST node on an authored YAML node, with exact code-point line/column offsets', () => {
    const result = parseYamlDocument(source, 'customer.yaml');
    expect(result.ast).toBeDefined();
    const spans = new Set<string>();
    visit(parseDocument(source), {
      Node(_key, node) {
        if (isNode(node) && node.range)
          spans.add(
            `${[...source.slice(0, node.range[0])].length}:${[...source.slice(0, node.range[1])].length}`
          );
      },
    });
    const inspect = (value: unknown): void => {
      if (!value || typeof value !== 'object') return;
      for (const child of Object.values(value)) {
        if (child && typeof child === 'object' && 'offsetStart' in child && 'file' in child) {
          expect(child.file).toBe('customer.yaml');
          expect(spans.has(`${child.offsetStart}:${child.offsetEnd}`)).toBe(true);
          const prefix = [...source].slice(0, child.offsetStart).join('').split('\n');
          expect([child.line, child.column]).toEqual([prefix.length, [...prefix.at(-1)!].length]);
        } else inspect(child);
      }
    };
    inspect(result.ast);
    expect(result.ast?.definitions[0].source.offsetStart).toBe(source.indexOf('kind: entity'));
  });

  it.each([
    'ttr: "0.22"\nunknown: true\n',
    `${envelope}defs: [{kind: entity, id: E, properties: {isKey: wrong}}]`,
    'ttr: "0.22"\nttr: "0.22"',
    'ttr: "0.22"\n---\nttr: "0.22"',
    'ttr: "0.22"\npackage: !evil foo',
  ])('returns a located ys/* diagnostic and no AST for invalid input: %s', (text) => {
    const result = parseYamlDocument(text, 'bad.yaml');
    expect(result.ast).toBeUndefined();
    expect(result.diagnostics.length).toBeGreaterThan(0);
    for (const error of result.diagnostics) {
      expect(error.code).toMatch(/^ys\//);
      expect(error.source.file).toBe('bad.yaml');
      expect(error.source.line).toBeGreaterThanOrEqual(1);
      expect(error.source.offsetStart).toBeGreaterThanOrEqual(0);
      expect(error.source.offsetEnd).toBeLessThanOrEqual(text.length);
    }
  });

  it('honours the reserved range/storage slots, typed fallthrough and ordered field/member maps', () => {
    const doc = (model: string, kind: string, properties: unknown) =>
      JSON.stringify({ ttr: '0.22', model, defs: [{ kind, id: 'P', properties }] });
    for (const text of [
      doc('md', 'domain', { restrict: { members: { range: { en: 'bad' } } } }),
      doc('world', 'world', {
        members: [{ kind: 'storage', id: 'S', properties: { schemas: 'free' } }],
      }),
    ]) {
      expect(parseYamlDocument(text).ast).toBeUndefined();
    }
    const text = doc('world', 'world', {
      members: [
        {
          kind: 'storage',
          id: 'S',
          properties: { via: 'erp_pg', schemas: [{ id: 'T', fields: { z: 'text', a: 'int' } }] },
        },
      ],
    });
    const result = parseYamlDocument(text);
    expect(result.diagnostics).toEqual([]);
    expect(portable(result.ast)).toEqual(
      portable(
        parseString(
          'model world def world P {def storage S {via: erp_pg, def schema T {z: text, a: int}}}'
        ).ast
      )
    );
    const ordered = parseYamlDocument(
      doc('md', 'domain', { restrict: { members: { z: { en: 'Last' }, a: { en: 'First' } } } })
    );
    expect(ordered.diagnostics).toEqual([]);
    expect(portable(ordered.ast)).toEqual(
      portable(
        parseString(
          'model md def domain P {restrict: {members: {"z": {en: "Last"}, "a": {en: "First"}}}}'
        ).ast
      )
    );
  });

  it('reads !pl scalar sugar as the explicit tagged block at an embedded position', () => {
    const a = parseYamlDocument(
      'ttr: "0.22"\nmodel: cn\ndefs:\n- kind: kind\n  id: Invoice\n  properties:\n    definition: !pl |\n      select x\n'
    );
    const b = parseYamlDocument(
      'ttr: "0.22"\nmodel: cn\ndefs:\n- kind: kind\n  id: Invoice\n  properties:\n    definition: {block: "select x\\n", tag: pl}\n'
    );
    expect(a.diagnostics).toEqual([]);
    expect(portable(a.ast)).toEqual(portable(b.ast));
  });
});
