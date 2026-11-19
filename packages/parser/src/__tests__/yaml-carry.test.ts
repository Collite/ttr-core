// SPDX-License-Identifier: Apache-2.0
import { describe, expect, it } from 'vitest';
import { readFileSync } from 'node:fs';
import { parseYamlDocument, parseString, conceptProperty } from '../index.js';
import { sqlPosToFile, fileToSqlOffset } from '../sql-position.js';
import { YamlInput, YamlFailure } from '../yaml/input.js';
import { YamlBridge } from '../yaml/bridge.js';
import type { QueryDef, ConceptDef, SourceLocation } from '../ast.js';

const portable = (v: unknown) =>
  JSON.parse(
    JSON.stringify(v, (key, value) =>
      [
        'source',
        'textSource',
        'textMapping',
        'lineOffsets',
        'indentWidth',
        'escapes',
        'leadingTrivia',
        'trailingTrivia',
      ].includes(key)
        ? undefined
        : value
    )
  );
const slice = (text: string, loc: SourceLocation) =>
  [...text].slice(loc.offsetStart, loc.offsetEnd).join('');

describe('review-334 S7.3 carry', () => {
  it.each([
    '"SELECT 1\\nFROM t"',
    "'SELECT 1\n\n        FROM t'",
    '>\n        SELECT 1\n\n        FROM t',
    '|\n        SELECT 1\n        FROM t',
  ])('N2 keeps SQL positions within the authored scalar and round trips: %s', (scalar) => {
    const text = `ttr: "0.22"\nmodel: db\ndefs:\n- kind: query\n  id: Q\n  properties:\n    sourceText:\n      block: ${scalar}\n      tag: sql\n`;
    const result = parseYamlDocument(text);
    expect(result.diagnostics).toEqual([]);
    const block = (result.ast!.definitions[0] as QueryDef).sourceText!;
    if (block.kind !== 'taggedBlock') throw new Error('expected SQL block');
    const input = new YamlInput(text, '<yaml>');
    const scalarSource = input.source('/defs/0/properties/sourceText/block');
    for (const [i, line] of block.value.split('\n').entries()) {
      for (let col = 0; col < line.length; col++) {
        const pos = sqlPosToFile({ line: i + 1, column: col }, block);
        const physical = text.split('\n')[pos.line - 1];
        const absolute =
          text
            .split('\n')
            .slice(0, pos.line - 1)
            .reduce((n, s) => n + [...s].length + 1, 0) + pos.column;
        expect(absolute).toBeGreaterThanOrEqual(scalarSource.offsetStart);
        expect(absolute).toBeLessThan(scalarSource.offsetEnd);
        const back = fileToSqlOffset(block, pos.line, pos.column);
        if (scalar.startsWith('"') || scalar.startsWith('|')) {
          expect(physical[pos.column]).toBe(line[col]);
          expect(back).toBe(
            block.value
              .split('\n')
              .slice(0, i)
              .reduce((n, s) => n + s.length + 1, 0) + col
          );
        } else expect(back).toBe(0); // conservative non-bijective mapping clamps to scalar start
      }
    }
  });
  it('N3 maps call entries to distinct real entries and accepts triple values', () => {
    const text = readFileSync(
      new URL('../../../../tests/conformance/fixtures/71-automation.yaml', import.meta.url),
      'utf8'
    );
    for (const variant of [text, text.replace('ref: "order_number"', 'triple: "x"')]) {
      const result = parseYamlDocument(variant);
      expect(result.diagnostics.filter((d) => d.severity === 'error')).toEqual([]);
      const calls: { entries: { source: SourceLocation }[] }[] = [];
      const walk = (v: unknown): void => {
        if (!v || typeof v !== 'object') return;
        if ('kind' in v && v.kind === 'call') calls.push(v as unknown as (typeof calls)[number]);
        for (const child of Object.values(v)) walk(child);
      };
      walk(result.ast);
      expect(calls.length).toBeGreaterThan(0);
      for (const call of calls) {
        const spans = call.entries.map((e) => slice(variant, e.source));
        expect(new Set(spans).size).toBe(spans.length);
        expect(spans.every((s) => !s.startsWith('call:'))).toBe(true);
      }
    }
  });
  it.each([
    [{ imports: [5] }, '/imports/0'],
    [{ security: [[{ verb: 'own', object: 5, owner: 'P' }]] }, '/security/0/0/object'],
    [{ defs: [{ kind: 'entity', id: 'E', properties: 5 }] }, '/defs/0/properties'],
  ])('N4 bridge failures retain their precise path: %j', (body, path) => {
    const input = new YamlInput(JSON.stringify({ ttr: '0.22', ...body }), 'bad.yaml');
    try {
      new YamlBridge(input).write();
      throw new Error('expected refusal');
    } catch (e) {
      expect(e).toBeInstanceOf(YamlFailure);
      expect((e as YamlFailure).path).toBe(path);
    }
  });
  it.each(['nope', 'sql'])('N5 expression tag %s has the native twin AST and diagnostic', (tag) => {
    const text = JSON.stringify({
      ttr: '0.22',
      model: 'cn',
      defs: [{ kind: 'kind', id: 'P', properties: { definition: { block: 'x', tag } } }],
    });
    const yaml = parseYamlDocument(text);
    const native = parseString(`model cn def kind P {definition: """${tag}\nx\n"""}`);
    const compact = (ds: typeof yaml.diagnostics) =>
      ds.map(({ code, severity, message }) => ({ code, severity, message }));
    expect(compact(yaml.diagnostics)).toEqual(compact(native.errors));
    expect(portable(conceptProperty(yaml.ast!.definitions[0] as ConceptDef, 'definition'))).toEqual(
      portable(conceptProperty(native.ast!.definitions[0] as ConceptDef, 'definition'))
    );
  });
});
