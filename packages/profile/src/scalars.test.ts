// SPDX-License-Identifier: Apache-2.0
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';
import { parseYamlDocument } from '@tatrman/parser';
import { ForeignTree, NumericScalar } from './input.js';
import { ProfileReader } from './loading.js';
import { ProfileEngine } from './engine.js';
import { canonicalWrite, normalizeCanonical } from './vocabulary.js';
import { decimalText, jsonText } from './scalars.js';

const values = [
  '9007199254740993',
  '1.50',
  '1e3',
  '-0.0',
  '0x1F',
  '123456789012345678901234567890',
];
const resolved = [
  '9007199254740993',
  '1.50',
  '1E+3',
  '0.0',
  '31',
  '123456789012345678901234567890',
];
const source = `items: {Numbers: {data: [${values.join(', ')}]}}`;
const apply = (operations: string, input = source) =>
  ProfileEngine.apply(
    [ForeignTree.read(input, 'numbers.yaml')],
    [
      ProfileReader.read(
        `profile: numbers/1\ngrammar: '>=0.22 <1.0'\noperations:\n  - {op: package, mapping: {}, default: parity}\n  - {op: kinds, at: /items, mapping: {program: program}}\n${operations}`,
        'profile.yaml'
      ),
    ]
  ).documents[0];

function numericNodes(
  value: unknown
): Array<{ value: number; text?: string; source: { line: number; column: number } }> {
  if (!value || typeof value !== 'object') return [];
  if ('kind' in value && value.kind === 'number')
    return [value as ReturnType<typeof numericNodes>[number]];
  return Object.values(value).flatMap(numericNodes);
}

describe('lossless scalars and output maps', () => {
  it('carries resolved numeric text independently of approximate arithmetic values', () => {
    const node = ForeignTree.read(source).root.entries()[0].entries()[0].entries()[0];
    const scalars = node.entries().map((n) => n.value as NumericScalar);
    expect(scalars.map((n) => n.text)).toEqual(resolved);
    expect(scalars[0].value).toBe(9007199254740992);
    expect(scalars.every((n) => n instanceof NumericScalar)).toBe(true);
  });
  it('canonical reader adds text while preserving its authored decimal spelling and spans', () => {
    const parsed = parseYamlDocument(
      `ttr: '0.22'\nmodel: automation\ndefs: [{kind: program, id: Numbers, properties: {custom: [${values.join(', ')}]}}]`,
      'canonical.yaml'
    );
    expect(parsed.diagnostics).toEqual([]);
    const nodes = numericNodes(parsed.ast);
    expect(nodes.map((n) => n.text)).toEqual([
      '9007199254740993',
      '1.50',
      '1e3',
      '-0.0',
      '31',
      '123456789012345678901234567890',
    ]);
    expect(nodes.every((n) => n.source.line === 3)).toBe(true);
  });
  it.each(['rename', 'shape', 'each'])(
    '%s keeps text through copying and typed AST relocation',
    (op) => {
      const operand =
        op === 'shape' ? ', template: {input: ""}' : op === 'each' ? ', shape: {input: $item}' : '';
      const document = apply(
        `  - {op: ${op}, in: program, at: /data, to: {kind: program, property: custom}${operand}}`
      );
      expect(numericNodes(document.ast).map((n) => n.text)).toEqual(resolved);
      expect(numericNodes(document.ast).every((n) => n.source.line === 1)).toBe(true);
      for (const number of resolved) expect(document.write()).toContain(`- ${number}\n`);
    }
  );
  it('profile template/default numbers keep their own spelling rather than the selected value', () => {
    const document = apply(
      '  - {op: values, in: program, at: /data, to: {kind: program, property: custom}, mapping: {x: 9007199254740993}, default: 1.50}',
      'items: {Numbers: {data: x}}'
    );
    expect(document.write()).toContain('custom: 9007199254740993\n');
    expect(numericNodes(document.ast)[0].text).toBe('9007199254740993');
    expect(
      apply(
        '  - {op: values, in: program, at: /data, to: {kind: program, property: custom}, mapping: {}, default: 1.50}',
        'items: {Numbers: {}}'
      ).write()
    ).toContain('custom: 1.50\n');
  });
  it('uses Kotlin lexical ordering for integer keys through normalization and re-emission', () => {
    const file = fileURLToPath(
      new URL(
        '../../../tests/conformance/dialect/integer-keys/canonical/integer-keys.yaml',
        import.meta.url
      )
    );
    const raw = {
      ttr: '0.22',
      package: 'parity',
      model: 'er',
      defs: [
        {
          kind: 'attribute',
          id: 'IntegerKeys',
          properties: {
            valueLabels: {
              '10': { en: 'ten' },
              '2': { en: 'two' },
              b: { en: 'bee' },
              '1': { en: 'one' },
            },
          },
        },
      ],
    };
    const normalized = normalizeCanonical(raw);
    const definition = (
      normalized.defs as Array<{ properties: { valueLabels: Map<string, unknown> } }>
    )[0];
    expect(definition.properties.valueLabels).toBeInstanceOf(Map);
    expect([...definition.properties.valueLabels.keys()]).toEqual(['1', '10', '2', 'b']);
    expect(canonicalWrite(normalized)).toBe(readFileSync(file, 'utf8'));
    expect(jsonText(normalized)).toContain(
      '"valueLabels":{"1":{"en":"one"},"10":{"en":"ten"},"2":{"en":"two"},"b":{"en":"bee"}}'
    );
  });
  it.each([
    ['+001.500', '1.500'],
    ['1e-6', '0.000001'],
    ['1e-7', '1E-7'],
    ['0e3', '0E+3'],
    ['-0.00', '0.00'],
    ['123e2', '1.23E+4'],
    ['0o77777777777777777777', '1152921504606846975'],
  ])('resolves %s as %s', (input, expected) => expect(decimalText(input)).toBe(expected));
});
