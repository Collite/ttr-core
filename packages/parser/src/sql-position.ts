// SPDX-License-Identifier: Apache-2.0
import { mappedPoint } from './coordinates.js';
import type { TaggedBlockValue } from './ast.js';

/**
 * Maps a position inside a tagged block's extracted `value` (e.g. an ANTLR SQL
 * token) back to the enclosing `.ttrm` file (embedded-sql DESIGN §8).
 *
 * The block's body always starts on its own line (the grammar requires a
 * newline after the tag) and `dedent` strips one uniform `indentWidth` from
 * every line, so the file column is simply the indent re-added — a single
 * additive shift, not a ragged map. This is why DESIGN §8's line-0
 * `valueSource.startColumn` term is 0 for tagged blocks and is omitted here
 * (folding it in would double-count, since `valueSource.column` already equals
 * `indentWidth`). If a future un-formatted file ever has ragged indent, this is
 * the single place to swap in a per-line `indentWidth[]`.
 *
 * Coordinates follow the ANTLR/`SourceLocation` convention: `line` 1-indexed,
 * `column` 0-indexed. ANTLR tokens use the same (`tok.line` 1-indexed,
 * `tok.column` 0-indexed), so they pass through directly.
 */
export interface SqlTokenPos {
  /** 1-indexed line within the SQL `value` (ANTLR `token.line`). */
  line: number;
  /** 0-indexed column within the SQL `value` (ANTLR `token.column`). */
  column: number;
}

export function sqlPosToFile(
  tok: SqlTokenPos,
  block: Pick<TaggedBlockValue, 'valueSource' | 'indentWidth'> & Partial<Pick<TaggedBlockValue, 'value' | 'textMapping'>>,
): { line: number; column: number } {
  if (block.textMapping) {
    const offset = (block.value ?? '').split('\n').slice(0, tok.line - 1).reduce((n, line) => n + [...line].length + 1, 0) + tok.column;
    const { line, column } = mappedPoint(block.textMapping, offset);
    return { line, column };
  }
  const sqlLine = tok.line - 1; // ANTLR line is 1-indexed; DESIGN §8 sqlLine is 0-indexed
  return {
    line: block.valueSource.line + sqlLine,
    column: block.indentWidth + tok.column,
  };
}

/**
 * Inverse of {@link sqlPosToFile}: map a `.ttrm` file position into the char
 * offset within a tagged block's extracted `value`, or `undefined` if the
 * position lies outside the block body (before it, or left of the indent). Used
 * by SQL IDE features (hover/definition) to hit-test a cursor against the
 * `SqlRefModel` spans.
 *
 * `fileLine` is 1-indexed and `fileColumn` 0-indexed (LSP positions are
 * 0-indexed lines, so callers pass `position.line + 1`).
 */
export function fileToSqlOffset(
  block: Pick<TaggedBlockValue, 'value' | 'valueSource' | 'indentWidth' | 'textMapping'>,
  fileLine: number,
  fileColumn: number,
): number | undefined {
  if (block.textMapping) {
    const { offsets, lineStarts, startLine } = block.textMapping;
    const start = lineStarts[fileLine - startLine];
    if (start === undefined) return undefined;
    const index = offsets.indexOf(start + fileColumn);
    if (index < 0) return undefined;
    return [...block.value].slice(0, index).join('').length;
  }
  const sqlLine0 = fileLine - block.valueSource.line;
  if (sqlLine0 < 0) return undefined;
  const sqlColumn = fileColumn - block.indentWidth;
  if (sqlColumn < 0) return undefined;

  const value = block.value;
  let line = 0;
  let col = 0;
  for (let i = 0; i <= value.length; i++) {
    if (line === sqlLine0 && col === sqlColumn) return i;
    const ch = value[i];
    if (ch === undefined) break;
    if (ch === '\n') {
      line++;
      col = 0;
      if (line > sqlLine0) return undefined; // past the target line (column out of range)
    } else {
      col++;
    }
  }
  return undefined;
}
