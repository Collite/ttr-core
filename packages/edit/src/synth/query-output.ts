// SPDX-License-Identifier: Apache-2.0
import type { DataType, EntityDef, QueryDef, TableDef } from '@tatrman/parser';
import { isTtrId } from '@tatrman/parser/layout';
import { lexSql } from '@tatrman/sql/lexers';
import { Refusal } from './refusals.js';
import { type IndexedNode, ModelIndex } from './model-index.js';

export interface OutputColumn { name: string; type: DataType | string; optional?: boolean; isKey?: boolean }
const unquote = (s: string) => s.startsWith('[') ? s.slice(1, -1).replaceAll(']]', ']') : s.startsWith('"') ? s.slice(1, -1).replaceAll('""', '"') : s;
const isName = (s: string) => isTtrId(unquote(s));

/**
 * Browser-safe projection analysis over the existing dialect lexer. The TS TTR parser has no LQ
 * output schema API. Accept only projections whose names AND types follow from loaded entities,
 * tables, literals, or simple aggregates. No guessed text types for unresolved expressions.
 */
export function queryOutput(index: ModelIndex, node: IndexedNode): OutputColumn[] {
  const query = node.def as QueryDef;
  const fail = (detail: string): never => { throw new Refusal('source-unknown', `Cannot establish outputs of ${node.symbol.qname}: ${detail}`); };
  const block = query.sourceText;
  if (!block || (query.language && query.language !== 'SQL')) return fail('SQL sourceText is required.');
  const sql = block.value;
  const dialect = block.kind === 'taggedBlock' ? block.dialect ?? 'postgres' : 'postgres';
  const lexed = lexSql(sql, dialect);
  if (lexed.errors.length || !lexed.tokens.length) return fail('the SQL dialect could not be lexed.');
  const chars = [...sql];
  const tokens = lexed.tokens.filter(t => !/COMMENT|SPACE|^WS$|^NEWLINE$/i.test(t.typeName ?? ''))
    .map(t => chars.slice(t.span.offset, t.span.offset + t.span.length).join('')).filter(t => t.trim());
  if (tokens.indexOf(';') >= 0 && tokens.indexOf(';') !== tokens.length - 1) return fail('exactly one SQL statement is required.');
  let depth = 0;
  const levels = tokens.map(t => { const before = depth; if (t === '(') depth++; if (t === ')') depth--; return before; });
  if (depth !== 0 || tokens[0]?.toUpperCase() !== 'SELECT') return fail('only a top-level SELECT with known projections is supported.');
  if (tokens.some((t, i) => levels[i] === 0 && /^(UNION|INTERSECT|EXCEPT)$/i.test(t))) return fail('set-operation output typing is not available.');
  const from = tokens.findIndex((t, i) => levels[i] === 0 && t.toUpperCase() === 'FROM');
  const end = from < 0 ? tokens.findIndex(t => t === ';') : from;
  const projectionEnd = end < 0 ? tokens.length : end;
  const sources = new Map<string, OutputColumn[]>();
  if (from >= 0) {
    const stop = tokens.findIndex((t, i) => i > from && levels[i] === 0 && /^(WHERE|GROUP|ORDER|HAVING|LIMIT|OFFSET|FETCH|;)$/i.test(t));
    const until = stop < 0 ? tokens.length : stop;
    const keywords = /^(ON|JOIN|LEFT|RIGHT|FULL|INNER|OUTER|CROSS|WHERE|GROUP|ORDER|HAVING|LIMIT|OFFSET|FETCH|;)$/i;
    for (let i = from; i < until; i++) {
      if (levels[i] !== 0 || !/^(FROM|JOIN|,)$/i.test(tokens[i])) continue;
      const parts: string[] = [];
      let j = i + 1;
      if (!isName(tokens[j] ?? '')) return fail('derived tables require a supplied output schema.');
      parts.push(unquote(tokens[j++]));
      while (tokens[j] === '.' && isName(tokens[j + 1] ?? '')) { parts.push(unquote(tokens[j + 1])); j += 2; }
      const source = index.lookup(parts.join('.'), ['entity', 'table', 'view'], node.doc);
      if (!source) return fail(`source ${parts.join('.')} is not a loaded entity/table/view.`);
      const def = source.def;
      const columns = def.kind === 'entity' ? (def as EntityDef).attributes : (def as TableDef).columns;
      if (!columns?.length || columns.some(c => !c.type)) return fail(`source ${parts.join('.')} has no complete typed columns.`);
      if (tokens[j]?.toUpperCase() === 'AS') j++;
      const alias = tokens[j] && !keywords.test(tokens[j]) && isName(tokens[j]) ? unquote(tokens[j]) : parts.at(-1)!;
      if (sources.has(alias)) return fail(`duplicate source alias ${alias}.`);
      const join = tokens.slice(Math.max(0, i - 2), i).map(t => t.toUpperCase());
      if (join.includes('RIGHT') || join.includes('FULL')) for (const [key, cols] of sources) sources.set(key, cols.map(c => ({ ...c, optional: true })));
      const optional = join.includes('LEFT') || join.includes('FULL');
      sources.set(alias, columns.map(c => ({ name: c.name, type: c.type!, ...(c.optional || optional ? { optional: true } : {}) })));
    }
  }
  const column = (path: string[]): OutputColumn => {
    const matches = path.length === 1 ? [...sources.values()].flat().filter(c => c.name === path[0])
      : (sources.get(path.slice(0, -1).join('.')) ?? sources.get(path.at(-2)!) ?? []).filter(c => c.name === path.at(-1));
    if (matches.length !== 1) return fail(`column ${path.join('.')} is unknown or ambiguous.`);
    return matches[0];
  };
  const pathOf = (ts: string[]): string[] | undefined => ts.length && ts.every((t, i) => i % 2 === 0 ? isName(t) : t === '.') ? ts.filter((_, i) => i % 2 === 0).map(unquote) : undefined;
  // Typed arithmetic for the demo's quantity × unit_price, with normal operator precedence.
  const numeric = (ts: string[]): OutputColumn | undefined => {
    const path = pathOf(ts);
    if (path) return column(path);
    if (ts.length === 1 && /^\d+(?:\.\d+)?$/.test(ts[0])) return { name: '', type: ts[0].includes('.') ? 'decimal' : 'int' };
    if (ts[0] === '(' && ts.at(-1) === ')') {
      let d = 0;
      const wraps = ts.every((t, i) => { if (t === '(') d++; if (t === ')') d--; return d > 0 || i === ts.length - 1; });
      if (wraps) return numeric(ts.slice(1, -1));
    }
    for (const operators of [['+', '-'], ['*', '/']]) {
      let d = 0;
      for (let i = ts.length - 1; i > 0; i--) {
        if (ts[i] === ')') d++;
        if (ts[i] === '(') d--;
        if (d || !operators.includes(ts[i])) continue;
        const a = numeric(ts.slice(0, i)), b = numeric(ts.slice(i + 1));
        if (!a || !b) return undefined;
        const name = (t: DataType | string) => typeof t === 'string' ? t : t.kind === 'simple' ? t.name : t.typeName;
        const types = [name(a.type), name(b.type)];
        if (types.some(t => !['int', 'bigint', 'decimal', 'float'].includes(t))) return undefined;
        const type = types.includes('float') ? 'float' : types.includes('decimal') ? 'decimal' : types.includes('bigint') ? 'bigint' : 'int';
        return { name: '', type, ...(a.optional || b.optional ? { optional: true } : {}) };
      }
    }
    return undefined;
  };
  const groups: string[][] = [[]];
  for (let i = tokens[1]?.toUpperCase() === 'DISTINCT' ? 2 : 1; i < projectionEnd; i++) {
    if (tokens[i] === ',' && levels[i] === 0) groups.push([]);
    else groups.at(-1)!.push(tokens[i]);
  }
  const output: OutputColumn[] = [];
  for (let ts of groups) {
    const aliasAt = ts.map(t => t.toUpperCase()).lastIndexOf('AS');
    let alias: string | undefined;
    if (aliasAt >= 0 && aliasAt === ts.length - 2 && isName(ts.at(-1)!)) { alias = unquote(ts.at(-1)!); ts = ts.slice(0, aliasAt); }
    else if (ts.length > 1 && isName(ts.at(-1)!) && ts.at(-2) !== '.') { alias = unquote(ts.pop()!); }
    if (ts.at(-1) === '*') {
      const cols = ts.length === 1 ? [...sources.values()].flat() : ts.length === 3 && ts[1] === '.' ? sources.get(unquote(ts[0])) : undefined;
      if (alias || !cols?.length) return fail('wildcard projection cannot be expanded.');
      output.push(...cols); continue;
    }
    const path = pathOf(ts);
    let value: OutputColumn;
    if (path) value = column(path);
    else if (/^(COUNT|SUM|AVG|MIN|MAX)$/i.test(ts[0]) && ts[1] === '(' && ts.at(-1) === ')') {
      const fn = ts[0].toUpperCase();
      const body = ts.slice(2, -1).filter(t => t.toUpperCase() !== 'DISTINCT');
      const arg = pathOf(body);
      if (fn === 'COUNT' && (arg || body.join('') === '*')) { if (arg) column(arg); value = { name: alias ?? '', type: 'bigint' }; }
      else if (arg) { value = column(arg); if (fn === 'AVG') value = { ...value, type: 'decimal' }; }
      else return fail('aggregate argument must be a loaded column.');
      if (!alias) return fail('aggregate outputs require an explicit alias.');
    } else if (ts.length === 1 && /^(?:\d+(?:\.\d+)?|'(?:[^']|'')*'|true|false)$/i.test(ts[0])) {
      if (!alias) return fail('literal outputs require an explicit alias.');
      value = { name: alias, type: ts[0].startsWith("'") ? 'text' : /^(true|false)$/i.test(ts[0]) ? 'bool' : ts[0].includes('.') ? 'decimal' : 'int' };
    } else {
      const arithmetic = numeric(ts);
      if (!arithmetic || !alias) return fail('expression typing is not available; use typed arithmetic with an alias.');
      value = arithmetic;
    }
    output.push({ ...value, name: alias ?? value.name });
  }
  if (!output.length || output.some(c => !isTtrId(c.name)) || new Set(output.map(c => c.name)).size !== output.length) return fail('outputs must have distinct TTR identifiers.');
  return output;
}
