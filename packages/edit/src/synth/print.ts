// SPDX-License-Identifier: Apache-2.0
import { parseString } from '@tatrman/parser';
import { Refusal } from './refusals.js';
import type { DataType, Definition, Document, ObjectValue, PropertyValue, Reference, SourceLocation } from '@tatrman/parser';
import { DEFAULT_FORMAT_CONFIG, formatDocument } from '@tatrman/format';

export const location: SourceLocation = { file: '', line: 1, column: 0, endLine: 1, endColumn: 0, offsetStart: 0, offsetEnd: 0 };

/** Value lexemes back the formatter's source slices. Definition/layout syntax belongs exclusively to the formatter. */
export class Fragment {
  source: string;
  constructor(source = '') { this.source = source; }
  span(text: string): SourceLocation {
    const offsetStart = [...this.source].length;
    this.source += text;
    return { ...location, offsetStart, offsetEnd: [...this.source].length };
  }
  ref(path: string): Reference { return { path, parts: path.split('.'), source: this.span(path) }; }
  id(path: string): PropertyValue { return { kind: 'id', path, parts: path.split('.'), source: this.span(path) }; }
  object(entries: Record<string, string>, quoted = false): ObjectValue {
    const text = '{ ' + Object.entries(entries).map(([k, v]) => `${k}: ${quoted ? JSON.stringify(v) : v}`).join(', ') + ' }';
    return { kind: 'object', entries: [], source: this.span(text) };
  }
  type(value: string | DataType): DataType {
    if (typeof value === 'string') {
      const type = { kind: 'simple' as const, name: value, source: this.span(value) };
      const printed = this.print([{ kind: 'attribute', name: '_type', type, source: location }]);
      const attribute = parseString(printed, '').ast!.definitions[0];
      if (attribute.kind !== 'attribute' || attribute.type?.kind !== 'simple' || attribute.type.name !== value) throw new Refusal('invalid-args', 'type must be one TTR data type.');
      return type;
    }
    const text = value.kind === 'simple' ? value.name : '{ type: ' + value.typeName +
      (value.length === undefined ? '' : `, length: ${value.length}`) +
      (value.precision === undefined ? '' : `, precision: ${value.precision}`) + ' }';
    return { ...value, source: this.span(text) };
  }
  print(definitions: Definition[]): string {
    const ast: Document = { imports: [], definitions, securityBlocks: [], source: location };
    const text = formatDocument(ast, this.source, { ...DEFAULT_FORMAT_CONFIG, separator: 'newline' });
    const checked = parseString(text, '');
    if (!checked.ast || checked.errors.some(e => e.severity === 'error') || checked.ast.definitions.length !== definitions.length || checked.ast.definitions.some((d, i) => d.kind !== definitions[i].kind || d.name !== definitions[i].name)) throw new Refusal('invalid-args', 'The supplied values do not form the requested definitions.');
    return text;
  }
}

/** Headers use the same canonical document printer as definitions. */
export function printHeader(pkg: string, model: string, locale?: string): string {
  const ast: Document = {
    imports: [], definitions: [], securityBlocks: [], source: location,
    ...(pkg ? { packageDecl: { kind: 'packageDecl', name: pkg, parts: pkg.split('.'), source: location } } : {}),
    modelDirective: { modelCode: model, ...(locale ? { locale } : {}), source: location },
  };
  return formatDocument(ast, '');
}

/** Parser offsets count code points; LSP positions count UTF-16 units. */
export function utf16Offset(text: string, offset: number): number { return [...text].slice(0, offset).join('').length; }
export function position(text: string, offset: number): { line: number; character: number } {
  const before = text.slice(0, offset);
  const last = before.lastIndexOf('\n');
  return { line: before.split('\n').length - 1, character: before.length - last - 1 };
}
