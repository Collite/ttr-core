// SPDX-License-Identifier: Apache-2.0
import { Composer, Parser, isAlias, isMap, isNode, isScalar, isSeq, type Node } from 'yaml';
import { ProfileException, type ProfileSpan } from './loading.js';

export type Json = null | boolean | number | string | Json[] | { [key: string]: Json };
export type RecordValue = { [key: string]: Json };
export const escapePointer = (key: string): string => key.replace(/~/g, '~0').replace(/\//g, '~1');
export const pointer = (parent: string, key: string | number): string =>
  `${parent}/${escapePointer(String(key))}`;
export const isRecord = (value: unknown): value is RecordValue =>
  value !== null && typeof value === 'object' && !Array.isArray(value);
const propertyStart = (tokens?: readonly { type: string; offset: number }[]): number | undefined =>
  tokens?.find((token) => token.type === 'anchor' || token.type === 'tag')?.offset;

/** SourceLocation-compatible positions: one-based lines, zero-based code-point columns and offsets. */
export interface SourcePosition {
  file: string;
  line: number;
  column: number;
  endLine: number;
  endColumn: number;
  offsetStart: number;
  offsetEnd: number;
}

export class Coordinates {
  readonly points: Uint32Array;
  private readonly lines = [0];
  constructor(
    readonly text: string,
    readonly uri: string
  ) {
    text = this.text = text.replace(/\r(?!\n)/g, '\n');
    this.points = new Uint32Array(text.length + 1);
    let unit = 0,
      point = 0;
    for (const ch of text) {
      this.points[unit] = point;
      if (ch.length === 2) this.points[unit + 1] = point;
      unit += ch.length;
      this.points[unit] = ++point;
      if (ch === '\n') this.lines.push(unit);
    }
  }
  span([start, end]: [number, number]): SourcePosition {
    start = Math.max(0, Math.min(start, this.text.length));
    end = Math.max(start, Math.min(end, this.text.length));
    const position = (offset: number) => {
      let lo = 0,
        hi = this.lines.length;
      while (lo + 1 < hi) {
        const mid = (lo + hi) >>> 1;
        if (this.lines[mid] <= offset) lo = mid;
        else hi = mid;
      }
      return { line: lo + 1, column: this.points[offset] - this.points[this.lines[lo]] };
    };
    const a = position(start),
      b = position(end);
    return {
      file: this.uri,
      line: a.line,
      column: a.column,
      endLine: b.line,
      endColumn: b.column,
      offsetStart: this.points[start],
      offsetEnd: this.points[end],
    };
  }
}

/** Safe representation tree; values and key positions remain tied to authored pointers. */
export class ForeignData {
  readonly nodes = new Map<string, Node>();
  readonly keys = new Map<string, Node>();
  readonly keyOrder = new Map<string, string[]>();
  readonly coordinates: Coordinates;
  readonly value: Json;
  readonly sourceText: string;
  get paths(): Set<string> {
    return new Set(this.nodes.keys());
  }
  constructor(
    readonly text: string,
    readonly file: string,
    tagScope = '',
    allowNonMappingRoot = false
  ) {
    text = this.text = text.replace(/\r(?!\n)/g, '\n');
    // Refuse oversized inputs before allocating a coordinate table or invoking the parser.
    if (text.length > 12_000_000 || [...text].length > 6_000_000)
      throw new ProfileException(
        'ys/profile-invalid',
        { file, line: 1, column: 1, endLine: 1, endColumn: 1, path: '' },
        'YAML exceeds 6000000 code points'
      );
    this.sourceText = text;
    this.coordinates = new Coordinates(text, file);
    const control = /[\x00-\x08\x0b\x0c\x0e-\x1f\x7f-\x84\x86-\x9f\ufffe\uffff]/u.exec(text);
    if (control)
      this.fail('', 'Non-printable character in YAML input', [control.index, control.index + 1]);
    const tokens = new Parser().parse(text);
    let rootProperties: number | undefined;
    function* locatedTokens() {
      for (const token of tokens) {
        if (token.type === 'document') rootProperties = propertyStart(token.start);
        yield token;
      }
    }
    const docs = [
      ...new Composer({
        version: '1.2',
        schema: 'core',
        uniqueKeys: false,
        keepSourceTokens: true,
        customTags: [{ tag: '!pl', resolve: (value: string) => value }],
      }).compose(locatedTokens()),
    ];
    if (docs.length !== 1) this.fail('', 'Expected exactly one YAML document', docs[1]?.range);
    const doc = docs[0];
    if (doc.directives?.yaml.version !== '1.2') this.fail('', 'Only YAML 1.2 is supported');
    // Convert before reporting parser diagnostics so safety failures retain their full key path.
    this.value = this.convert(doc.contents, '', 0, tagScope, rootProperties);
    const error = doc.errors[0] ?? doc.warnings[0];
    if (error) {
      const offset = error.pos[0];
      let path = '',
        length = Infinity;
      for (const [candidate, node] of this.nodes) {
        if (
          node.range &&
          node.range[0] <= offset &&
          node.range[1] >= offset &&
          node.range[1] - node.range[0] <= length
        ) {
          path = candidate;
          length = node.range[1] - node.range[0];
        }
      }
      this.fail(path, error.message, error.pos);
    }
    if (!allowNonMappingRoot && !isRecord(this.value))
      throw new ProfileException(
        'ys/profile-invalid',
        {
          file,
          line: 1,
          column: 1,
          endLine: 1,
          endColumn: 1,
          path: '',
        },
        'Expected a canonical document mapping'
      );
  }
  private fail(path: string, detail: string, range?: readonly number[] | null): never {
    const source = range ? this.coordinates.span([range[0], range[1]]) : this.source(path, true);
    throw new ProfileException(
      'ys/profile-invalid',
      { ...source, column: source.column + 1, endColumn: source.endColumn + 1, path },
      detail
    );
  }
  private convert(
    node: unknown,
    path: string,
    depth: number,
    tagScope: string,
    properties?: number
  ): Json {
    if (!isNode(node)) {
      // Empty YAML values and empty documents are JSON null.
      return null;
    }
    this.nodes.set(path, node);
    const nodeRange = node.range ? [properties ?? node.range[0], node.range[1]] : undefined;
    if (isAlias(node) || ('anchor' in node && node.anchor))
      this.fail(path, 'Anchors and aliases are not supported', nodeRange);
    const inScope = !tagScope || path === tagScope || path.startsWith(`${tagScope}/`);
    const allowed = isMap(node)
      ? ['tag:yaml.org,2002:map']
      : isSeq(node)
        ? ['tag:yaml.org,2002:seq']
        : [
            'tag:yaml.org,2002:str',
            'tag:yaml.org,2002:null',
            'tag:yaml.org,2002:bool',
            'tag:yaml.org,2002:int',
            'tag:yaml.org,2002:float',
          ];
    if (node.tag && !(node.tag === '!pl' && !inScope) && !allowed.includes(node.tag))
      this.fail(path, `Unsupported YAML tag ${node.tag}`, nodeRange);
    if ((isMap(node) || isSeq(node)) && depth >= 64)
      this.fail(path, 'YAML nesting too deep (limit 64)');
    if (isMap(node)) {
      const out: RecordValue = Object.create(null);
      const order: string[] = [];
      this.keyOrder.set(path, order);
      for (const pair of node.items) {
        if (!isScalar(pair.key) || typeof pair.key.value !== 'string') {
          const keyPath = isScalar(pair.key) ? pointer(path, String(pair.key.value)) : path;
          this.fail(
            keyPath,
            'Mapping keys must be strings',
            isNode(pair.key) ? pair.key.range : undefined
          );
        }
        const key = pair.key.value as string,
          child = pointer(path, key);
        if (pair.key.anchor || (pair.key.tag && pair.key.tag !== 'tag:yaml.org,2002:str'))
          this.fail(
            child,
            'Anchors and non-string tags on keys are not supported',
            pair.key.range
              ? [propertyStart(pair.srcToken?.start) ?? pair.key.range[0], pair.key.range[1]]
              : undefined
          );
        this.keys.set(child, pair.key);
        order.push(key);
        if (key === '<<') this.fail(child, 'Merge keys are not supported', pair.key.range);
        if (Object.hasOwn(out, key))
          this.fail(child, `Duplicate mapping key ${key}`, pair.key.range);
        out[key] = this.convert(
          pair.value,
          child,
          depth + 1,
          tagScope,
          propertyStart(pair.srcToken?.sep)
        );
        // Null values have no node, but must still have an authored position.
        if (!this.nodes.has(child)) this.nodes.set(child, pair.key);
      }
      return out;
    }
    if (isSeq(node)) {
      const token = node.srcToken;
      const items = token && 'items' in token ? token.items : [];
      return node.items.map((item, i) => {
        const child = pointer(path, i),
          value = this.convert(item, child, depth + 1, tagScope, propertyStart(items[i]?.start));
        if (!this.nodes.has(child)) this.nodes.set(child, node);
        return value;
      });
    }
    if (!isScalar(node)) this.fail(path, 'Unsupported YAML value');
    const value: unknown = node.value;
    if (node.tag === '!pl' && !inScope) return { block: String(value), tag: 'pl' };
    if (value === null || typeof value === 'string' || typeof value === 'boolean') return value;
    if (typeof value === 'number' && Number.isFinite(value)) {
      if ((node.source?.length ?? 0) > 1000)
        this.fail(path, 'Number literal too long (maximum 1000 characters)');
      return value;
    }
    this.fail(path, 'Expected a finite scalar');
  }
  node(path: string, key = false): Node | undefined {
    if (key && this.keys.has(path)) return this.keys.get(path);
    while (path && !this.nodes.has(path)) path = path.slice(0, path.lastIndexOf('/'));
    return this.nodes.get(path);
  }
  source(path: string, key = false): SourcePosition {
    const range = this.node(path, key)?.range;
    return this.coordinates.span(range ? [range[0], range[1]] : [0, Math.min(1, this.text.length)]);
  }
  span(path: string, key = false): ProfileSpan {
    const span = this.source(path, key);
    return {
      file: span.file,
      line: span.line,
      column: span.column + 1,
      endLine: span.endLine,
      endColumn: span.endColumn + 1,
      path,
    };
  }
}

export class ForeignTree {
  private constructor(
    readonly data: ForeignData,
    readonly file: string,
    readonly label: string
  ) {}
  get root(): ForeignNode {
    return new ForeignNode(this, this.data.value, '');
  }
  get sourceText(): string {
    return this.data.text;
  }
  get coordinates(): Coordinates {
    return this.data.coordinates;
  }
  static read(source: string, file = '<yaml>', label = file): ForeignTree {
    return new ForeignTree(new ForeignData(source, label), file, label);
  }
}

export class ForeignNode {
  constructor(
    readonly tree: ForeignTree,
    readonly value: Json,
    readonly path: string,
    readonly mappingKey?: string,
    readonly keySelection = false
  ) {}
  span(key = false): ProfileSpan {
    return this.tree.data.span(this.path, key || this.keySelection);
  }
  child(key: string, value?: Json): ForeignNode {
    const selected =
      value === undefined
        ? isRecord(this.value)
          ? this.value[key]
          : Array.isArray(this.value)
            ? this.value[Number(key)]
            : null
        : value;
    return new ForeignNode(
      this.tree,
      selected ?? null,
      pointer(this.path, key),
      Array.isArray(this.value) ? undefined : key
    );
  }
  entries(): ForeignNode[] {
    if (Array.isArray(this.value))
      return this.value.map((value, i) => this.child(String(i), value));
    if (isRecord(this.value))
      return (this.tree.data.keyOrder.get(this.path) ?? Object.keys(this.value)).map((key) =>
        this.child(key, (this.value as RecordValue)[key])
      );
    return [];
  }
}

/** RFC 6901; /$key is a record's mapping key, with its key span. */
export class JsonPointer {
  private readonly parts: string[];
  constructor(readonly pointer: string) {
    if ((pointer !== '' && !pointer.startsWith('/')) || /~(?![01])/.test(pointer))
      throw new Error('Expected a valid JSON Pointer');
    this.parts =
      pointer === ''
        ? []
        : pointer
            .slice(1)
            .split('/')
            .map((x) => x.replace(/~1/g, '/').replace(/~0/g, '~'));
  }
  resolve(record: ForeignNode, includeNull = false): ForeignNode | null {
    if (this.parts.length === 1 && this.parts[0] === '$key')
      return record.mappingKey === undefined
        ? null
        : new ForeignNode(record.tree, record.mappingKey, record.path, record.mappingKey, true);
    let current = record;
    for (const part of this.parts) {
      const value = current.value;
      if (isRecord(value) && Object.hasOwn(value, part)) current = current.child(part, value[part]);
      else if (
        Array.isArray(value) &&
        /^(?:0|[1-9][0-9]*)$/.test(part) &&
        Number(part) < value.length
      )
        current = current.child(part, value[Number(part)]);
      else return null;
    }
    return !includeNull && current.value === null ? null : current;
  }
}

// Shared diagnostics are available from either loading entry point.
export { ProfileException, type ProfileSpan } from './loading.js';
