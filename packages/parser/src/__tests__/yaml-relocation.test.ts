// SPDX-License-Identifier: Apache-2.0
import { describe, expect, it, vi } from 'vitest';
import { readFileSync } from 'node:fs';
import {
  conceptProperty,
  embeddedBlockLocation,
  parseString,
  parseYamlDocument,
} from '../index.js';
import type { ConceptDef, EmbeddedBlock, SourceLocation } from '../ast.js';
import { YamlInput, YamlFailure } from '../yaml/input.js';
import { YamlBridge, type Piece, type Projection } from '../yaml/bridge.js';
import { YamlLocations } from '../yaml/locations.js';

const cn = (scalar: string) =>
  `ttr: "0.22"\nmodel: cn\ndefs:\n- kind: kind\n  id: P\n  properties:\n    definition:\n      block: ${scalar}\n`;
function block(text: string): EmbeddedBlock {
  const result = parseYamlDocument(text, 'block.yaml');
  expect(result.diagnostics).toEqual([]);
  return conceptProperty(result.ast!.definitions[0] as ConceptDef, 'definition')!.block;
}
const slice = (text: string, source: SourceLocation) =>
  [...text].slice(source.offsetStart, source.offsetEnd).join('');
function at(text: string, source: SourceLocation): void {
  for (const [offset, line, column] of [
    [source.offsetStart, source.line, source.column],
    [source.offsetEnd, source.endLine, source.endColumn],
  ]) {
    const lines = [...text].slice(0, offset).join('').split('\n');
    expect([line, column]).toEqual([lines.length, [...lines.at(-1)!].length]);
  }
}

describe('YAML authored relocation (review-334)', () => {
  it('R1 relocates location arrays, including area packages and entities', () => {
    const text = readFileSync(
      new URL('../../../../tests/conformance/fixtures/56-area.yaml', import.meta.url),
      'utf8'
    );
    const result = parseYamlDocument(text, 'area.yaml');
    const area = result.ast!.definitions[0];
    if (area.kind !== 'area') throw new Error('area expected');
    expect(area.packageSources!.map((s) => slice(text, s))).toEqual([
      '"shop.sales"',
      '"shop.catalog"',
    ]);
    expect(area.entitySources!.map((s) => slice(text, s))).toEqual([
      '"shop.sales.er.entity.order"',
    ]);
    for (const source of [...area.packageSources!, ...area.entitySources!]) at(text, source);
  });

  it('R2 matches the old exhaustive projection oracle, including equal spans and astral text', () => {
    const texts = [
      cn('"😀\\ntext"'),
      ...['56-area', '76-cn-bindings'].map((name) =>
        readFileSync(
          new URL(`../../../../tests/conformance/fixtures/${name}.yaml`, import.meta.url),
          'utf8'
        )
      ),
    ];
    for (const text of texts) {
      const input = new YamlInput(text, 'oracle.yaml');
      const bridge = new YamlBridge(input).write();
      const native = parseString(bridge.text, input.uri).ast!;
      // Retain the previous filter/sort implementation only as a test oracle.
      const units = [0];
      for (const ch of bridge.text) units.push(units.at(-1)! + ch.length);
      const oracle = (source: SourceLocation, literal = false): Projection | undefined =>
        bridge.spans
          .filter(
            (p) =>
              (!literal || p.literal) &&
              p.start <= units[source.offsetStart] &&
              p.end >= units[source.offsetEnd]
          )
          .sort((a, b) => a.end - a.start - (b.end - b.start))[0];
      const indexed = new YamlLocations(input, bridge, []);
      const exhaustive = new YamlLocations(input, bridge, []);
      exhaustive.projection = oracle;
      expect(indexed.copy(native)).toEqual(exhaustive.copy(native));
      // Exercise intervals beyond AST nodes, both literal and non-literal queries.
      for (let start = 0; start < Math.min(units.length, 100); start++)
        for (const width of [0, 1, 4]) {
          const source = {
            offsetStart: start,
            offsetEnd: Math.min(start + width, units.length - 1),
          } as SourceLocation;
          for (const literal of [false, true])
            expect(indexed.projection(source, literal)?.path).toBe(oracle(source, literal)?.path);
        }
    }
  });

  it('R3 uses code points after emoji and CRLF, including syntax/schema diagnostics', () => {
    const text =
      'ttr: "0.22"\r\nmodel: er\r\ndefs: [{kind: entity, id: E, properties: {description: "😀😀", attributes: [{id: name, properties: {type: text}}]}}]';
    const result = parseYamlDocument(text);
    const entity = result.ast!.definitions[0];
    if (entity.kind !== 'entity') throw new Error('entity expected');
    expect(slice(text, entity.attributes![0].source)).toBe('{id: name, properties: {type: text}}');
    at(text, entity.attributes![0].source);
    for (const suffix of ['unknown: 1', 'broken: [']) {
      const invalid = 'ttr: "0.22"\ndefs: []\n# 😀😀\n' + suffix;
      const errors = parseYamlDocument(invalid).diagnostics;
      expect(errors.length).toBeGreaterThan(0);
      expect(errors[0].code).toBe(suffix.startsWith('unknown') ? 'ys/schema' : 'ys/yaml-syntax');
      errors.forEach((d) => at(invalid, d.source));
    }
    const embedded = cn('|\n        😀 and wrong');
    const location = embeddedBlockLocation(block(embedded), 0, 6, 5);
    expect(slice(embedded, location)).toBe('wrong');
    at(embedded, location);
  });

  it.each([
    '"warehouse = 1\\nand qty > 0"',
    '"warehouse = 1"',
    '"\\U0001F600 \\u0061nd\\nqty"',
    '"warehouse = 1\n        and qty > 0"',
  ])('R4 maps quoted content inside its scalar: %s', (scalar) => {
    const text = cn(scalar),
      b = block(text);
    expect(b.form).toBe('tripleString');
    const logical = b.text.split('\n');
    logical.forEach((line, index) => {
      for (let column = 0; column < [...line].length; column++) {
        const loc = embeddedBlockLocation(b, index, column);
        at(text, loc);
        expect(loc.offsetStart).toBeGreaterThan(b.source.offsetStart);
        expect(loc.offsetEnd).toBeLessThanOrEqual(b.source.offsetEnd);
      }
    });
    if (b.text.startsWith('warehouse'))
      expect(slice(text, embeddedBlockLocation(b, 0, 0, 9))).toBe('warehouse');
    if (logical.length > 1 && logical[1].startsWith('and'))
      expect(slice(text, embeddedBlockLocation(b, 1, 0, 3))).toBe('and');
  });
  it.each([
    '>\n        warehouse = 1\n\n        and qty > 0',
    "'warehouse = 1\n\n        and qty > 0'",
    'warehouse = 1\n\n        and qty > 0',
  ])('R4 clamps non-bijective content to its scalar: %s', (scalar) => {
    const text = cn(scalar),
      b = block(text);
    b.text.split('\n').forEach((_line, l) => {
      const loc = embeddedBlockLocation(b, l, 2, 8);
      at(text, loc);
      expect(loc.offsetStart).toBeGreaterThanOrEqual(b.source.offsetStart);
      expect(loc.offsetEnd).toBeLessThanOrEqual(b.source.offsetEnd);
    });
  });
  it('R5 preserves blank leading and middle physical lines of a literal block', () => {
    const text = cn('|\n\n        warehouse = 1\n\n        and qty > 0');
    const b = block(text);
    expect(b.textSource.line).toBe(9);
    for (const [l, word] of [
      [1, 'warehouse'],
      [3, 'and'],
    ] as const) {
      const loc = embeddedBlockLocation(b, l, 0, word.length);
      expect(slice(text, loc)).toBe(word);
      at(text, loc);
    }
    at(text, embeddedBlockLocation(b, 2, 0, 0));
  });

  it('R6 shares all tag diagnostics with the native reader', () => {
    const compact = (errors: ReturnType<typeof parseString>['errors']) =>
      errors.map(({ code, severity, message }) => ({ code, severity, message }));
    for (const tag of ['postgres', 'nope']) {
      const yaml = JSON.stringify({
        ttr: '0.22',
        model: 'db',
        defs: [
          {
            kind: 'query',
            id: 'P',
            properties: { language: 'TRANSFORMATION_DSL', sourceText: { tag, block: 'x' } },
          },
        ],
      });
      expect(compact(parseYamlDocument(yaml).diagnostics)).toEqual(
        compact(
          parseString(
            `model db def query P {language: TRANSFORMATION_DSL, sourceText: """${tag}\nx\n"""}`
          ).errors
        )
      );
    }
    for (const tag of ['sql', 'nope']) {
      const yaml = cn('"x"').replace('block: "x"', `block: "x"\n      tag: ${tag}`);
      expect(compact(parseYamlDocument(yaml).diagnostics)).toEqual(
        compact(parseString(`model cn def kind P {definition: """${tag}\nx\n"""}`).errors)
      );
    }
  });
  it('R6 relocates a map named fields outside a world schema to its value', () => {
    const text = JSON.stringify({
      ttr: '0.22',
      model: 'world',
      defs: [
        {
          kind: 'world',
          id: 'W',
          properties: {
            members: [{ kind: 'engine', id: 'E', properties: { fields: { example: 5 } } }],
          },
        },
      ],
    });
    const result = parseYamlDocument(text);
    expect(result.diagnostics).toEqual([]);
    const definition = result.ast!.definitions[0];
    if (definition.kind !== 'world') throw new Error('world expected');
    const fields = definition.engines[0].manifest.fields;
    expect(slice(text, fields.source)).toBe('{"example":5}');
    const input = new YamlInput('ttr: "0.22"\n', 'fields.yaml');
    const source = input.source('');
    const locations = new YamlLocations(input, { text: '', spans: [] }, []);
    locations.projection = (_source, literal) =>
      literal ? undefined : { start: 0, end: 1, path: '/defs/0/properties/fields/example' };
    const sourceSpy = vi.spyOn(input, 'source');
    locations.copy({ name: 'example', type: 'text', source });
    expect(sourceSpy.mock.calls.some((args) => args[1] === true)).toBe(false);
  });

  it.each(['alternative', 'typed fallthrough'])(
    'R7 surfaces programming errors in %s',
    (position) => {
      const original = YamlBridge.prototype.render;
      const bug = new TypeError('renderer bug');
      const stub = vi.spyOn(YamlBridge.prototype, 'render').mockImplementation(function (
        this: YamlBridge,
        name,
        value,
        path
      ) {
        if (name === (position === 'alternative' ? 'stringLiteralForm' : 'id')) throw bug;
        return original.call(this, name, value, path);
      });
      try {
        const text =
          position === 'alternative'
            ? JSON.stringify({
                ttr: '0.22',
                defs: [{ kind: 'entity', id: 'P', properties: { description: { en: 'text' } } }],
              })
            : JSON.stringify({
                ttr: '0.22',
                model: 'world',
                defs: [
                  {
                    kind: 'world',
                    id: 'W',
                    properties: {
                      members: [{ kind: 'storage', id: 'S', properties: { via: 'engine' } }],
                    },
                  },
                ],
              });
        expect(() => parseYamlDocument(text)).toThrow(bug);
      } finally {
        stub.mockRestore();
      }
    }
  );
  it('R7 rolls back embedded recognition when an alternative fails', () => {
    const original = YamlBridge.prototype.render;
    const render = vi.spyOn(YamlBridge.prototype, 'render').mockImplementation(function (
      this: YamlBridge,
      name,
      value,
      path
    ) {
      const piece = original.call(this, name, value, path);
      if (name === 'embeddedBlock') throw new YamlFailure(path, 'failed alternative');
      return piece;
    });
    const write = vi.spyOn(YamlBridge.prototype, 'write').mockImplementation(function (
      this: YamlBridge
    ) {
      const bridge = this as unknown as { expr(e: object, value: object, path: string): Piece };
      return bridge.expr(
        { alt: [{ symbol: 'embeddedBlock' }, { symbol: 'value' }] },
        { block: 'x', tag: 'pl' },
        '/defs/0/properties/definition'
      );
    });
    try {
      const result = parseYamlDocument(
        cn('!pl x').replace('definition:\n      block: !pl x', 'definition: !pl x')
      );
      expect(result.ast).toBeUndefined();
      expect(result.diagnostics[0].message).toContain('!pl is allowed only');
    } finally {
      write.mockRestore();
      render.mockRestore();
    }
  });
});
