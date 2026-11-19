// SPDX-License-Identifier: Apache-2.0
import type { SourceLocation, ParseError } from '../ast.js';
import {
  expressionTagDiagnostic,
  unknownTagDiagnostic,
  queryTagDiagnostics,
} from '../tag-diagnostics.js';
import { resolveTag, resolveExpressionTag } from '../tag-registry.js';
import { record, pointer, type Json, type YamlInput } from './input.js';
import type { Piece, Projection } from './bridge.js';
import { yamlContent } from './content.js';
import { Projections } from './projections.js';

/** All projection locations are discarded, including embedded source maps. */
export class YamlLocations {
  private readonly projections: Projections;
  constructor(
    readonly input: YamlInput,
    readonly bridge: Piece,
    readonly diagnostics: ParseError[]
  ) {
    this.projections = new Projections(bridge);
  }
  projection(source: SourceLocation, literal = false): Projection | undefined {
    return this.projections.find(source, literal);
  }
  source(source: SourceLocation): SourceLocation {
    return this.input.source(this.projection(source)?.path ?? '');
  }
  private value(path: string): Json {
    let value: Json = this.input.value;
    let at = '';
    for (const part of path.split('/').slice(1)) {
      value = Array.isArray(value)
        ? value[Number(part)]
        : record(value, at)[part.replace(/~1/g, '/').replace(/~0/g, '~')];
      at += '/' + part;
    }
    return value;
  }
  copy<T>(value: T): T {
    if (!value || typeof value !== 'object') return value;
    if (isLocation(value)) return this.source(value) as T;
    if (Array.isArray(value)) return value.map((v) => this.copy(v)) as T;
    const original = value as Record<string, unknown>;
    const src = isLocation(original.source) ? original.source : undefined;
    const p = src ? this.projection(src) : undefined;
    const literal = src ? this.projection(src, true) : undefined;
    const result: Record<string, unknown> = {};
    for (const [key, child] of Object.entries(original)) {
      if (src && ['leadingTrivia', 'trailingTrivia'].includes(key)) continue;
      if (src && key === 'entries' && child && !Array.isArray(child)) result[key] = child;
      else result[key] = this.copy(child);
    }
    if (src && literal) {
      const syntax = record(this.value(literal.path), literal.path);
      if (literal.literal === 'triple' && original.kind === 'string') {
        result.kind = 'tripleString';
        result.value = syntax.triple;
      }
      if (literal.literal === 'block') {
        const tag = typeof syntax.tag === 'string' ? syntax.tag : undefined;
        const text = String(syntax.block);
        const content = yamlContent(this.input, literal.path, text);
        if (original.kind === 'embeddedBlock') {
          const expressionTag = tag && resolveExpressionTag(tag);
          const plain = tag && !expressionTag;
          result.text = plain ? `${tag}\n${text}\n` : text;
          result.form = expressionTag ? 'tagged' : (syntax.form ?? 'tripleString');
          result.language = (tag && resolveExpressionTag(tag)) || 'pl';
          result.textSource = content.source;
          result.lineOffsets = content.lines;
          result.indentWidth = content.indent;
          result.escapes = [];
          result.textMapping = plain
            ? { ...content.mapping, offsets: [content.source.offsetStart] }
            : content.mapping;
          if (tag) {
            const key = literal.path.split('/').at(-1)!;
            const diagnostic = expressionTagDiagnostic(
              tag,
              key,
              this.input.source(pointer(literal.path, 'tag'))
            );
            if (diagnostic) this.diagnostics.push(diagnostic);
          }
        } else if (original.kind === 'string' && syntax.form !== 'string') {
          const entry = tag ? resolveTag(tag) : undefined;
          if (entry && tag) {
            result.kind = 'taggedBlock';
            result.tag = tag;
            result.language = entry.language;
            result.dialect = entry.dialect;
            result.value = text;
            result.tagSource = this.input.source(pointer(literal.path, 'tag'));
            result.valueSource = content.source;
            result.indentWidth = content.indent;
            result.textMapping = content.mapping;
          } else {
            result.kind = 'tripleString';
            result.value = text;
            if (tag)
              this.diagnostics.push(
                unknownTagDiagnostic(tag, this.input.source(pointer(literal.path, 'tag')))
              );
          }
        }
      }
    }
    if (src && p && original.kind === 'number') {
      result.text = this.input.number(p.path, Number(original.value));
      if ('raw' in original) result.raw = result.text;
    }
    // World schema fields name a key, rather than the type value.
    if (
      src &&
      p &&
      original.name !== undefined &&
      original.type !== undefined &&
      /^\/defs\/\d+\/properties\/members\/\d+\/properties\/schemas\/\d+\/fields\/[^/]+$/.test(
        p.path
      )
    )
      result.source = this.input.source(p.path, true);
    // The native walker checks this after constructing tagged query values.
    if (result.kind === 'query' && result.sourceText && typeof result.sourceText === 'object') {
      const block = result.sourceText as Record<string, unknown>;
      if (block.kind === 'taggedBlock' && result.language !== undefined) {
        this.diagnostics.push(
          ...queryTagDiagnostics(
            String(result.language),
            block as unknown as import('../ast.js').TaggedBlockValue
          )
        );
      }
    }
    return result as T;
  }
}

function isLocation(value: unknown): value is SourceLocation {
  if (!value || typeof value !== 'object') return false;
  const v = value as Record<string, unknown>;
  return (
    typeof v.file === 'string' &&
    ['line', 'column', 'endLine', 'endColumn', 'offsetStart', 'offsetEnd'].every(
      (key) => typeof v[key] === 'number'
    )
  );
}
