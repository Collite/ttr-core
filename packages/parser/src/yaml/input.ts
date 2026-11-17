// SPDX-License-Identifier: Apache-2.0
import { isAlias, isMap, isNode, isScalar, isSeq, LineCounter, parseAllDocuments, type Node } from 'yaml';
import Ajv2020Module from 'ajv/dist/2020.js';
import type { ErrorObject, ValidateFunction } from 'ajv';
import type { SourceLocation } from '../ast.js';
import { canonicalSchema } from '../generated/canonical-yaml.js';
import { DiagnosticCode } from '../diagnostics.js';

export type Json = null | boolean | number | string | Json[] | { [key: string]: Json };
export type RecordValue = { [key: string]: Json };
export const pointer = (parent: string, key: string | number): string => `${parent}/${String(key).replace(/~/g, '~0').replace(/\//g, '~1')}`;
export const record = (v: Json): RecordValue => {
  if (!v || typeof v !== 'object' || Array.isArray(v)) throw new Error('Expected a mapping');
  return v;
};
export const list = (v: Json): Json[] => {
  if (!Array.isArray(v)) throw new Error('Expected a sequence');
  return v;
};
export class YamlFailure extends Error {
  constructor(readonly path: string, message: string, readonly code = DiagnosticCode.YamlValue, readonly span?: [number, number]) { super(message); }
}
type AjvConstructor = new (options: object) => { compile(schema: object): ValidateFunction };
const Ajv = ((Ajv2020Module as unknown as {default?: AjvConstructor}).default ?? Ajv2020Module) as AjvConstructor;
let validator: ValidateFunction | undefined;

/** One representation tree owns values, lexical numbers and authored coordinates. No alias expansion. */
export class YamlInput {
  readonly nodes = new Map<string, Node>();
  readonly keys = new Map<string, Node>();
  readonly pl = new Set<string>();
  readonly lines = new LineCounter();
  readonly value: RecordValue;
  constructor(readonly text: string, readonly uri: string) {
    if (text.length > 12_000_000 || [...text].length > 6_000_000) throw new YamlFailure('', 'YAML exceeds 6000000 code points', DiagnosticCode.YamlSyntax);
    const docs = parseAllDocuments(text, {
      version: '1.2', schema: 'core', uniqueKeys: true, lineCounter: this.lines,
      customTags: [{tag: '!pl', resolve: (value: string) => value}],
      // Keeping CST text allows numbers to retain their authored spelling.
      keepSourceTokens: true,
    });
    if (docs.length !== 1) throw new YamlFailure('', 'Expected exactly one YAML document', DiagnosticCode.YamlSyntax, [docs[1]?.range?.[0] ?? 0, docs[1]?.range?.[1] ?? text.length]);
    const doc = docs[0];
    const error = doc.errors[0] ?? doc.warnings[0];
    if (error) throw new YamlFailure('', error.message, DiagnosticCode.YamlSyntax, error.pos);
    if (doc.directives?.yaml.version !== '1.2') throw new YamlFailure('', 'Only YAML 1.2 is supported', DiagnosticCode.YamlSyntax);
    try {
      const value = this.convert(doc.contents, '', 0);
      if (!value || typeof value !== 'object' || Array.isArray(value)) throw new YamlFailure('', 'Expected a canonical document mapping');
      this.value = value;
    } catch (error) {
      if (!(error instanceof YamlFailure) || error.span) throw error;
      const range = this.node(error.path)?.range;
      throw new YamlFailure(error.path, error.message, error.code, range ? [range[0], range[1]] : undefined);
    }
  }

  private convert(node: unknown, path: string, depth: number): Json {
    if (!isNode(node)) throw new YamlFailure(path, 'Expected a YAML value');
    this.nodes.set(path, node);
    if (isAlias(node) || ('anchor' in node && node.anchor)) throw new YamlFailure(path, 'Anchors and aliases are not supported', DiagnosticCode.YamlSyntax);
    if (node.tag && node.tag !== '!pl' && !node.tag.startsWith('tag:yaml.org,2002:')) throw new YamlFailure(path, `Unsupported YAML tag ${node.tag}`, DiagnosticCode.YamlSyntax);
    if (isMap(node) || isSeq(node)) {
      if (depth >= 64) throw new YamlFailure(path, 'YAML nesting too deep (limit 64)', DiagnosticCode.YamlSyntax);
      if (node.tag === '!pl') throw new YamlFailure(path, '!pl requires a scalar');
    }
    if (isMap(node)) {
      const out: RecordValue = Object.create(null);
      for (const pair of node.items) {
        if (!isScalar(pair.key) || typeof pair.key.value !== 'string') throw new YamlFailure(path, 'Mapping keys must be strings');
        const key = pair.key.value;
        const child = pointer(path, key);
        this.keys.set(child, pair.key);
        if (key === '<<') throw new YamlFailure(child, 'Merge keys are not supported', DiagnosticCode.YamlSyntax, pair.key.range ? [pair.key.range[0], pair.key.range[1]] : undefined);
        out[key] = this.convert(pair.value, child, depth + 1);
      }
      return out;
    }
    if (isSeq(node)) return node.items.map((item, i) => this.convert(item, pointer(path, i), depth + 1));
    if (!isScalar(node)) throw new YamlFailure(path, 'Unsupported YAML value');
    const value: unknown = node.value;
    if (node.tag === '!pl') {
      if (typeof value !== 'string') throw new YamlFailure(path, '!pl requires a string');
      this.pl.add(path);
      this.nodes.set(pointer(path, 'block'), node);
      this.nodes.set(pointer(path, 'tag'), node);
      return {block: value, tag: 'pl'};
    }
    if (value === null || typeof value === 'string' || typeof value === 'boolean') return value;
    if (typeof value === 'number' && Number.isFinite(value)) {
      if ((node.source?.length ?? 0) > 1000) throw new YamlFailure(path, 'Number literal too long (maximum 1000 characters)');
      return value;
    }
    throw new YamlFailure(path, 'Expected a finite scalar');
  }

  validate(): void {
    validator ??= new Ajv({allErrors: true, strict: false, ownProperties: true}).compile(canonicalSchema as object);
    if (validator(this.value)) return;
    // Prefer the most specific vocabulary error over the enclosing union failures.
    const errors = validator.errors ?? [];
    const error = [...errors].sort((a,b) => this.errorPath(b).length - this.errorPath(a).length)[0];
    const path = error ? this.errorPath(error) : '';
    const range = error?.keyword === 'additionalProperties' ? this.keys.get(path)?.range : undefined;
    throw new YamlFailure(path, error?.message ?? 'Invalid canonical document', DiagnosticCode.YamlSchema, range ? [range[0],range[1]] : undefined);
  }
  private errorPath(error: ErrorObject): string {
    return error.keyword === 'additionalProperties' ? pointer(error.instancePath, String(error.params.additionalProperty)) : error.instancePath;
  }
  node(path: string, key = false): Node | undefined {
    if (key && this.keys.has(path)) return this.keys.get(path);
    while (!this.nodes.has(path) && path) path = path.slice(0, path.lastIndexOf('/'));
    return this.nodes.get(path);
  }
  source(path: string, key = false): SourceLocation {
    const range = this.node(path, key)?.range;
    return this.span(range ? [range[0], range[1]] : [0, Math.min(1, this.text.length)]);
  }
  span([start, end]: [number, number]): SourceLocation {
    start = Math.max(0, Math.min(start, this.text.length));
    end = Math.max(start, Math.min(end, this.text.length));
    const a = this.lines.linePos(start);
    const b = this.lines.linePos(end);
    return {file: this.uri, line: a.line, column: a.col - 1, endLine: b.line, endColumn: b.col - 1, offsetStart: start, offsetEnd: end};
  }
  number(path: string, value: number): string {
    const node = this.node(path);
    const raw = isScalar(node) ? node.source : undefined;
    return raw && /^-?[0-9]+(?:\.[0-9]+)?(?:[eE][+-]?[0-9]+)?$/.test(raw) ? raw : String(value);
  }
}
