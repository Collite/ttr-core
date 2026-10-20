// SPDX-License-Identifier: Apache-2.0
/**
 * 0.17 — read access to a cn body (`ConceptDef`, `ConceptSugar`, `ConceptBindingDef`). The body keeps
 * every property in authored order; a repeated key is a parse diagnostic and the FIRST one is the
 * property these accessors (and the conformance dump) read. Lookups only — what a property means is
 * the semantics layer's.
 */
import type {
  ConceptBody,
  ConceptEntry,
  ConceptProperty,
  ConceptRefKey,
  ConceptRefProperty,
  EmbeddedBlock,
  SourceLocation,
} from './ast.js';

/** The first property of `kind` (a typed property; free-form ones are read with {@link conceptEntry}). */
export function conceptProperty<K extends Exclude<ConceptProperty['kind'], 'ref' | 'entry'>>(
  body: ConceptBody,
  kind: K,
): Extract<ConceptProperty, { kind: K }> | undefined {
  return body.properties.find((p): p is Extract<ConceptProperty, { kind: K }> => p.kind === kind);
}

/** The first stereotype ref under `key` (`event: Order`, `kind: cn.Item`, …). */
export function conceptRef(body: ConceptBody, key: ConceptRefKey): ConceptRefProperty | undefined {
  return body.properties.find((p): p is ConceptRefProperty => p.kind === 'ref' && p.key === key);
}

/** The first free-form property under `key` (`of`, `is_a`, `qualities`, `participants`, …). */
export function conceptEntry(body: ConceptBody, key: string): ConceptEntry | undefined {
  return body.properties.find((p): p is ConceptEntry => p.kind === 'entry' && p.key === key);
}

/**
 * Where a position inside an expression block sits in the file — `line` and `column` are 0-based
 * within the block's `text` (the coordinates an expression parser reports), `length` the span's width
 * in characters on that line. Lets a diagnostic about the expression point into the `.ttrm`.
 */
export function embeddedBlockLocation(
  block: EmbeddedBlock,
  line: number,
  column: number,
  length = 1,
): SourceLocation {
  const l = Math.max(0, Math.min(line, block.lineOffsets.length - 1));
  // A string-form block's escapes each take one more character of the file than of `text`.
  const shift = (c: number) => (l === 0 ? (block.escapes ?? []).filter((e) => e < c).length : 0);
  const base = l === 0 ? block.textSource.column : block.indentWidth;
  const start = column + shift(column);
  const end = column + length + shift(column + length);
  return {
    file: block.source.file,
    line: block.textSource.line + l,
    column: base + start,
    endLine: block.textSource.line + l,
    endColumn: base + end,
    offsetStart: block.lineOffsets[l] + start,
    offsetEnd: block.lineOffsets[l] + end,
  };
}
