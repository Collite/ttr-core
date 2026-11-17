// SPDX-License-Identifier: Apache-2.0
import type { SourceLocation, ParseError } from '../ast.js';
import { DiagnosticCode } from '../diagnostics.js';
import { resolveTag, resolveExpressionTag } from '../tag-registry.js';
import { record, pointer, type Json, type YamlInput } from './input.js';
import type { Piece, Projection } from './bridge.js';

/** All projection locations are discarded, including embedded source maps. */
export class YamlLocations {
  private readonly offsets: number[] = [0];
  constructor(readonly input: YamlInput, readonly bridge: Piece, readonly diagnostics: ParseError[]) {
    let offset = 0;
    for (const point of bridge.text) { offset += point.length; this.offsets.push(offset); }
  }
  private contains(span: Projection, source: SourceLocation): boolean {
    return span.start <= (this.offsets[source.offsetStart] ?? this.bridge.text.length) && span.end >= (this.offsets[source.offsetEnd] ?? this.bridge.text.length);
  }
  projection(source: SourceLocation, literal = false): Projection | undefined {
    return this.bridge.spans.filter((p) => (!literal || p.literal) && this.contains(p,source))
      .sort((a,b) => (a.end - a.start) - (b.end - b.start))[0];
  }
  source(source: SourceLocation): SourceLocation {
    return this.input.source(this.projection(source)?.path ?? '');
  }
  private value(path: string): Json {
    let value: Json = this.input.value;
    for (const part of path.split('/').slice(1)) value = Array.isArray(value) ? value[Number(part)] : record(value)[part.replace(/~1/g,'/').replace(/~0/g,'~')];
    return value;
  }
  private content(path: string, text: string): {source: SourceLocation; lines: number[]; indent: number} {
    const source = this.input.source(path);
    const node = this.input.node(path);
    // Literal scalars have a physical line for every decoded line. Quoted and
    // folded text use the scalar span: never expose coordinates in the projection.
    if (node && 'type' in node && node.type === 'BLOCK_LITERAL') {
      const starts: number[] = [];
      for (let i = source.offsetStart; i < source.offsetEnd; i++) if (this.input.text[i] === '\n') starts.push(i + 1);
      const logical = text.split('\n');
      const first = logical.findIndex((line) => line.trim());
      const physicalStart = starts[Math.max(0,first)] ?? source.offsetEnd;
      const physical = this.input.text.slice(physicalStart).split('\n')[0];
      const indent = Math.max(0, (physical.match(/^ */)?.[0].length ?? 0) - (logical[Math.max(0,first)]?.match(/^ */)?.[0].length ?? 0));
      const lines = logical.map((_line,i) => Math.min(source.offsetEnd,(starts[i] ?? source.offsetEnd) + indent));
      return {source: this.input.span([lines[0],source.offsetEnd]), lines, indent};
    }
    return {source, lines: text.split('\n').map(() => source.offsetStart), indent: 0};
  }
  copy<T>(value: T): T {
    if (!value || typeof value !== 'object') return value;
    if (Array.isArray(value)) return value.map((v) => this.copy(v)) as T;
    const original = value as Record<string,unknown>;
    const src = original.source as SourceLocation | undefined;
    const p = src ? this.projection(src) : undefined;
    const literal = src ? this.projection(src,true) : undefined;
    const result: Record<string,unknown> = {};
    for (const [key,child] of Object.entries(original)) {
      if (['leadingTrivia','trailingTrivia'].includes(key)) continue;
      if (child && ['source','tagSource','valueSource','textSource'].includes(key)) result[key] = this.source(child as SourceLocation);
      else result[key] = this.copy(child);
    }
    if (src && literal) {
      const syntax = record(this.value(literal.path));
      if (literal.literal === 'triple' && original.kind === 'string') {
        result.kind = 'tripleString'; result.value = syntax.triple;
      }
      if (literal.literal === 'block') {
        const tag = typeof syntax.tag === 'string' ? syntax.tag : undefined;
        const text = String(syntax.block);
        const content = this.content(pointer(literal.path,'block'),text);
        if (original.kind === 'embeddedBlock') {
          result.text = text;
          result.form = tag ? 'tagged' : syntax.form ?? 'tripleString';
          result.language = tag && resolveExpressionTag(tag) || 'pl';
          result.textSource = content.source;
          result.lineOffsets = content.lines;
          result.indentWidth = content.indent;
          result.escapes = [];
          if (tag && !resolveExpressionTag(tag)) this.diagnostics.push({code: resolveTag(tag) ? DiagnosticCode.LanguageTagMismatch : DiagnosticCode.UnknownLanguageTag, severity: resolveTag(tag) ? 'error' : 'warning', message: `Unknown expression-language tag '${tag}'`, source: this.input.source(pointer(literal.path,'tag'))});
        } else if (original.kind === 'string' && syntax.form !== 'string') {
          const entry = tag ? resolveTag(tag) : undefined;
          if (entry && tag) {
            result.kind = 'taggedBlock'; result.tag = tag;
            result.language = entry.language; result.dialect = entry.dialect;
            result.value = text;
            result.tagSource = this.input.source(pointer(literal.path,'tag'));
            result.valueSource = content.source; result.indentWidth = content.indent;
          } else {
            result.kind = 'tripleString'; result.value = text;
            if (tag) this.diagnostics.push({code: DiagnosticCode.UnknownLanguageTag, severity:'warning', message: `Unknown embedded-language tag '${tag}'`, source: this.input.source(pointer(literal.path,'tag'))});
          }
        }
      }
    }
    if (src && p && original.kind === 'number' && 'raw' in original) result.raw = this.input.number(p.path,Number(original.value));
    // World schema fields name a key, rather than the type value.
    if (src && p && original.name !== undefined && original.type !== undefined && p.path.includes('/fields/')) result.source = this.input.source(p.path,true);
    // The native walker checks this after constructing tagged query values.
    if (result.kind === 'query' && result.sourceText && typeof result.sourceText === 'object') {
      const block = result.sourceText as Record<string,unknown>;
      if (block.kind === 'taggedBlock' && result.language !== undefined) {
        const source = block.tagSource as SourceLocation;
        if (block.language !== result.language) this.diagnostics.push({code: DiagnosticCode.LanguageTagMismatch, severity:'error', message: `language disagrees with block tag '${block.tag}'`, source});
        this.diagnostics.push({code: DiagnosticCode.DeprecatedLanguageProperty, severity:'warning', message:'language is inferred from the block tag', source});
      }
    }
    return result as T;
  }
}
