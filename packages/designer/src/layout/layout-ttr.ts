// SPDX-License-Identifier: Apache-2.0
// The TTR serialization of a layout (VL contracts §3, V-10…V-13): write ONE `.ttrg` — the graph block
// with `model`, `objects` and, when there are positions, the in-file `layout: { nodes: {…} }` block
// (the v1.1 D4 shape the worker mode writes). Read a `.ttrg` with or without that block, optionally
// with its `.ttrl` sidecar (grammar v4.3) — and when both carry positions, the sidecar wins.
//
// The reader is a small tokenizer + scanner, not `@tatrman/parser`: the grammar's `layout:` object takes
// `id` keys only, so the quoted keys a reader must accept (V-12) are outside it, and the JVM reader
// (`TtrgGraphReader`, ttr-parser) scans text the same way — one approach, held together by the shared
// vectors. Unlike a naive scan it skips comments and strings, so neither can fake a block.
// Never read or written here: the viewport (C1-b) and `edges` (reserved).

import {
  canonicalize,
  stemWarning,
  validateDocument,
  type LayoutDocument,
  type LayoutIssue,
  type LayoutModel,
  type LayoutPoint,
  type ReadOptions,
} from './layout-document.js';

/**
 * The lexer keywords of `TTR.g4` that the `idPart` rule does NOT admit — an `id` part spelled like one
 * of these is not an `id`. Kept in sync with the grammar by a test that recomputes it from `TTR.g4`.
 */
export const TTR_NON_ID_WORDS: ReadonlySet<string> = new Set([
  'DATAFRAME_DSL', 'REL_NODE', 'SQL', 'TRANSFORMATION_DSL', 'attributes', 'bool', 'boolean', 'btree',
  'cardinality', 'char', 'codeAttribute', 'columns', 'constraints', 'date', 'datetime', 'decimal', 'def',
  'definitionSql', 'description', 'descriptions', 'displayLabel', 'double', 'extends', 'false', 'float',
  'fulltext', 'fuzzy', 'hosts', 'indexed', 'indices', 'int', 'integer', 'isKey', 'join', 'keywords',
  'labelPlural', 'language', 'length', 'list', 'nameAttribute', 'notNull', 'null', 'number', 'object',
  'optional', 'ordered', 'parameters', 'precision', 'primary', 'primaryKey', 'resultColumns', 'roles',
  'search', 'searchable', 'secondary', 'sourceText', 'staging', 'string', 'tags', 'target', 'text',
  'timestamp', 'true', 'type', 'unique', 'valueLabels', 'varchar', 'whereFilter',
]);

/** The grammar's IDENT token: ASCII + Latin-1/Latin Extended-A/B letters, digits, underscore. */
const IDENT_PART = /^[A-Za-z_\u00C0-\u024F][A-Za-z0-9_\u00C0-\u024F]*$/;

/** Whether `s` is a grammar `id` — `idPart ( '.' idPart )*` — and so may be written bare (V-12). */
export function isTtrId(s: string): boolean {
  return s.length > 0 && s.split('.').every((p) => IDENT_PART.test(p) && !TTR_NON_ID_WORDS.has(p));
}

/** One ref as the `.ttrg` writer spells it: bare when it is an `id`, else a string literal (V-12). */
export function ttrToken(s: string): string {
  return isTtrId(s) ? s : `"${s.replace(/\\/g, '\\\\').replace(/"/g, '\\"')}"`;
}

/** Serialize `doc` as one canonical `.ttrg` (V-10). `skin`/`collapsed` are not carried (GQ-1). */
export function writeTtrg(doc: LayoutDocument): string {
  const d = canonicalize(doc).doc;
  const keys = Object.keys(d.positions);
  const lines = [`graph ${d.name} {`, `  model: ${d.model},`];
  const objectsEnd = keys.length > 0 ? ',' : '';
  if (d.objects.length === 0) {
    lines.push(`  objects: []${objectsEnd}`);
  } else {
    lines.push('  objects: [');
    d.objects.forEach((o, i) => lines.push(`    ${ttrToken(o)}${i < d.objects.length - 1 ? ',' : ''}`));
    lines.push(`  ]${objectsEnd}`);
  }
  if (keys.length > 0) {
    lines.push('  layout: {', '    nodes: {');
    keys.forEach((k, i) => {
      const p = d.positions[k];
      lines.push(`      ${ttrToken(k)}: { x: ${p.x}, y: ${p.y} }${i < keys.length - 1 ? ',' : ''}`);
    });
    lines.push('    }', '  }');
  }
  lines.push('}');
  return lines.join('\n') + '\n';
}

// ── the scanner ──────────────────────────────────────────────────────────────

interface Tok {
  kind: 'word' | 'number' | 'string' | 'punct';
  /** the word / number / punctuation as written; for a string, its unescaped value */
  text: string;
  line: number;
}

class ScanError extends Error {
  constructor(readonly issue: LayoutIssue) {
    super(issue.message);
  }
}

const WORD_START = /[A-Za-z_\u00C0-\u024F]/;
const WORD_CHAR = /[A-Za-z0-9_.\u00C0-\u024F]/;
const NUMBER = /-?[0-9]+(?:\.[0-9]+)?(?:[eE][+-]?[0-9]+)?/y;

function tokenize(src: string): Tok[] {
  const toks: Tok[] = [];
  let line = 1;
  let i = 0;
  const lines = (s: string) => (s.match(/\n/g) ?? []).length;
  while (i < src.length) {
    const c = src[i];
    if (c === '\n') { line++; i++; continue; }
    if (c === ' ' || c === '\t' || c === '\r' || c === '\f' || c === '\uFEFF') { i++; continue; }
    if (c === '/' && src[i + 1] === '/') {
      while (i < src.length && src[i] !== '\n') i++;
      continue;
    }
    if (c === '/' && src[i + 1] === '*') {
      const end = src.indexOf('*/', i + 2);
      if (end < 0) throw new ScanError({ path: '', message: 'unterminated block comment', line });
      line += lines(src.slice(i, end));
      i = end + 2;
      continue;
    }
    if (src.startsWith('"""', i)) {
      const end = src.indexOf('"""', i + 3);
      if (end < 0) throw new ScanError({ path: '', message: 'unterminated triple-quoted string', line });
      toks.push({ kind: 'string', text: src.slice(i + 3, end), line });
      line += lines(src.slice(i, end));
      i = end + 3;
      continue;
    }
    if (c === '"') {
      let j = i + 1;
      let out = '';
      while (j < src.length && src[j] !== '"' && src[j] !== '\n') {
        if (src[j] === '\\' && j + 1 < src.length && src[j + 1] !== '\n') {
          out += src[j + 1];
          j += 2;
        } else {
          out += src[j++];
        }
      }
      if (src[j] !== '"') throw new ScanError({ path: '', message: 'unterminated string', line });
      toks.push({ kind: 'string', text: out, line });
      i = j + 1;
      continue;
    }
    if (c === '-' || (c >= '0' && c <= '9')) {
      NUMBER.lastIndex = i;
      const m = NUMBER.exec(src);
      if (m) {
        toks.push({ kind: 'number', text: m[0], line });
        i += m[0].length;
        continue;
      }
    }
    if (WORD_START.test(c)) {
      let j = i + 1;
      while (j < src.length && WORD_CHAR.test(src[j])) j++;
      toks.push({ kind: 'word', text: src.slice(i, j), line });
      i = j;
      continue;
    }
    toks.push({ kind: 'punct', text: c, line });
    i++;
  }
  return toks;
}

class Cursor {
  p = 0;
  constructor(private readonly toks: Tok[]) {}
  peek(): Tok | undefined {
    return this.toks[this.p];
  }
  next(): Tok | undefined {
    return this.toks[this.p++];
  }
  atEnd(): boolean {
    return this.p >= this.toks.length;
  }
  is(ch: string): boolean {
    const t = this.toks[this.p];
    return t?.kind === 'punct' && t.text === ch;
  }
  /** an optional `:` / `=` property separator (`propSep`) */
  sep(): void {
    if (this.is(':') || this.is('=')) this.p++;
  }
  expect(ch: string, issue: LayoutIssue): void {
    if (!this.is(ch)) throw new ScanError({ ...issue, line: issue.line ?? this.peek()?.line });
    this.p++;
  }
  /** skip one value: a scalar, a `word(…)` call, or a balanced `{…}` / `[…]` / `(…)` */
  skipValue(): void {
    const t = this.next();
    if (!t) return;
    if (t.kind === 'word' && this.is('(')) this.skipBalanced(this.next()!);
    else if (t.kind === 'punct' && '{[('.includes(t.text)) this.skipBalanced(t);
  }
  private skipBalanced(open: Tok): void {
    let depth = 1;
    while (depth > 0) {
      const t = this.next();
      if (!t) throw new ScanError({ path: '', message: `unbalanced \`${open.text}\``, line: open.line });
      if (t.kind === 'punct' && '{[('.includes(t.text)) depth++;
      else if (t.kind === 'punct' && '}])'.includes(t.text)) depth--;
    }
  }
}

/** `{ "<key>": { x: <n>, y: <n> } … }` — keys bare or quoted, commas and separators optional. */
function parseNodeMap(c: Cursor, nodes: Record<string, LayoutPoint>, nodeLines: Record<string, number>): void {
  c.expect('{', { path: 'nodes', message: 'nodes must be { <ref>: { x, y } … }' });
  for (;;) {
    if (c.is('}')) { c.next(); return; }
    if (c.is(',')) { c.next(); continue; }
    const key = c.next();
    if (!key) throw new ScanError({ path: 'nodes', message: 'unclosed nodes block' });
    if (key.kind !== 'word' && key.kind !== 'string') {
      throw new ScanError({ path: 'nodes', message: `expected a node key, got \`${key.text}\``, line: key.line });
    }
    const path = `positions.${key.text}`;
    c.sep();
    c.expect('{', { path, message: `${path} must be { x, y }`, line: key.line });
    const xy: Partial<LayoutPoint> = {};
    for (;;) {
      if (c.is('}')) { c.next(); break; }
      if (c.is(',')) { c.next(); continue; }
      const axis = c.next();
      if (!axis) throw new ScanError({ path, message: `unclosed ${path}`, line: key.line });
      c.sep();
      const v = c.next();
      if (axis.text !== 'x' && axis.text !== 'y') {
        throw new ScanError({ path: `${path}.${axis.text}`, message: `a node coordinate is x or y (got ${axis.text})`, line: axis.line });
      }
      if (v?.kind !== 'number') throw new ScanError({ path: `${path}.${axis.text}`, message: `${axis.text} must be a number`, line: axis.line });
      xy[axis.text] = Number(v.text);
    }
    for (const axis of ['x', 'y'] as const) {
      if (xy[axis] === undefined) throw new ScanError({ path: `${path}.${axis}`, message: `missing ${axis}`, line: key.line });
    }
    nodes[key.text] = { x: xy.x!, y: xy.y! };
    nodeLines[key.text] = key.line;
  }
}

/** The graph block of a `.ttrg`, as written — before any document rule is applied. */
export interface RawTtrGraph {
  name: string;
  model?: string;
  objects: string[];
  /** the in-file `layout.nodes` block, verbatim */
  nodes: Record<string, LayoutPoint>;
  lines: { name: number; model?: number; objects: number[]; nodes: Record<string, number> };
}

/** Scan a `.ttrg` for its graph block (header, `objects`, in-file `layout.nodes`). */
export function parseTtrg(text: string): { graph?: RawTtrGraph; errors: LayoutIssue[]; warnings: LayoutIssue[] } {
  const warnings: LayoutIssue[] = [];
  try {
    const toks = tokenize(text);
    const at = toks.findIndex(
      (t, k) => t.kind === 'word' && t.text === 'graph' && toks[k + 1]?.kind === 'word' && toks[k + 2]?.kind === 'punct' && toks[k + 2].text === '{',
    );
    if (at < 0) return { errors: [{ path: '', message: 'no `graph <name> { … }` block' }], warnings };
    const g: RawTtrGraph = { name: toks[at + 1].text, objects: [], nodes: {}, lines: { name: toks[at].line, objects: [], nodes: {} } };
    const c = new Cursor(toks);
    c.p = at + 3;
    for (;;) {
      if (c.atEnd()) throw new ScanError({ path: '', message: `unclosed graph ${g.name}`, line: g.lines.name });
      if (c.is('}')) break;
      if (c.is(',')) { c.next(); continue; }
      const key = c.next()!;
      if (key.kind !== 'word') throw new ScanError({ path: '', message: `expected a graph property, got \`${key.text}\``, line: key.line });
      c.sep();
      switch (key.text) {
        case 'model': {
          const v = c.next();
          if (v?.kind !== 'word') throw new ScanError({ path: 'model', message: 'model must be a model code (er, db, …)', line: key.line });
          g.model = v.text;
          g.lines.model = v.line;
          break;
        }
        case 'objects': {
          c.expect('[', { path: 'objects', message: 'objects must be a list', line: key.line });
          for (;;) {
            if (c.is(']')) { c.next(); break; }
            const item = c.next();
            if (!item) throw new ScanError({ path: 'objects', message: 'unclosed objects list', line: key.line });
            if (item.kind !== 'word' && item.kind !== 'string') {
              throw new ScanError({ path: 'objects', message: `unexpected \`${item.text}\` in objects`, line: item.line });
            }
            g.objects.push(item.text);
            g.lines.objects.push(item.line);
            if (c.is(',')) c.next();
            else if (!c.is(']')) throw new ScanError({ path: 'objects', message: 'expected `,` or `]` in objects', line: c.peek()?.line ?? item.line });
          }
          break;
        }
        case 'layout': {
          c.expect('{', { path: 'layout', message: 'layout must be { nodes: { … } }', line: key.line });
          for (;;) {
            if (c.is('}')) { c.next(); break; }
            if (c.is(',')) { c.next(); continue; }
            const prop = c.next();
            if (!prop) throw new ScanError({ path: 'layout', message: 'unclosed layout block', line: key.line });
            c.sep();
            if (prop.text === 'nodes') parseNodeMap(c, g.nodes, g.lines.nodes);
            else {
              c.skipValue(); // viewport (never read, C1-b) · edges (reserved)
              if (prop.text !== 'viewport' && prop.text !== 'edges') {
                warnings.push({ path: `layout.${prop.text}`, message: `unknown layout property \`${prop.text}\` — ignored`, line: prop.line });
              }
            }
          }
          break;
        }
        default:
          c.skipValue();
          warnings.push(
            key.text === 'description' || key.text === 'tags'
              ? { path: key.text, message: `\`${key.text}\` is graph metadata, not part of a layout — not kept`, line: key.line }
              : { path: key.text, message: `unknown graph property \`${key.text}\` — ignored`, line: key.line },
          );
      }
    }
    return { graph: g, errors: [], warnings };
  } catch (e) {
    if (e instanceof ScanError) return { errors: [e.issue], warnings };
    throw e;
  }
}

/** One `canvas` block of a `.ttrl` sidecar (grammar v4.3). */
export interface TtrlCanvasBlock {
  key: string;
  skin?: string;
  mode: 'auto' | 'manual';
  nodes: Record<string, LayoutPoint>;
  collapsed: string[];
}

export type TtrlReadResult =
  | { ok: true; version: number; canvases: TtrlCanvasBlock[]; warnings: LayoutIssue[] }
  | { ok: false; errors: LayoutIssue[]; warnings: LayoutIssue[] };

/** Read a `.ttrl` sidecar: `ttrl 1` then `canvas <key> { skin, mode, nodes, collapsed }` blocks. */
export function readTtrl(text: string): TtrlReadResult {
  const warnings: LayoutIssue[] = [];
  try {
    const c = new Cursor(tokenize(text));
    const head = c.next();
    const version = c.next();
    if (head?.kind !== 'word' || head.text !== 'ttrl' || version?.kind !== 'number') {
      throw new ScanError({ path: 'ttrl', message: 'a `.ttrl` sidecar starts with `ttrl 1`', line: head?.line });
    }
    if (version.text !== '1') {
      throw new ScanError({ path: 'ttrl', message: `\`.ttrl\` version ${version.text} is not supported (expected 1)`, line: version.line });
    }
    const canvases: TtrlCanvasBlock[] = [];
    while (!c.atEnd()) {
      const kw = c.next()!;
      const key = c.next();
      if (kw.kind !== 'word' || kw.text !== 'canvas' || key?.kind !== 'word') {
        throw new ScanError({ path: 'ttrl', message: 'expected `canvas <key> {`', line: kw.line });
      }
      c.expect('{', { path: 'ttrl', message: 'expected `canvas <key> {`', line: kw.line });
      const canvas: TtrlCanvasBlock = { key: key.text, mode: 'auto', nodes: {}, collapsed: [] };
      for (;;) {
        if (c.is('}')) { c.next(); break; }
        if (c.is(',')) { c.next(); continue; }
        const prop = c.next();
        if (!prop) throw new ScanError({ path: 'ttrl', message: `unclosed canvas ${key.text}`, line: key.line });
        c.sep();
        switch (prop.text) {
          case 'skin': {
            const v = c.next();
            if (v?.kind !== 'string') throw new ScanError({ path: 'ttrl.skin', message: '`skin` must be a string', line: prop.line });
            canvas.skin = v.text;
            break;
          }
          case 'mode': {
            const v = c.next();
            if (v?.text !== 'auto' && v?.text !== 'manual') throw new ScanError({ path: 'ttrl.mode', message: '`mode` must be `auto` or `manual`', line: prop.line });
            canvas.mode = v.text;
            break;
          }
          case 'nodes':
            parseNodeMap(c, canvas.nodes, {});
            break;
          case 'collapsed': {
            c.expect('[', { path: 'ttrl.collapsed', message: '`collapsed` must be a list of strings', line: prop.line });
            for (;;) {
              if (c.is(']')) { c.next(); break; }
              if (c.is(',')) { c.next(); continue; }
              const v = c.next();
              if (v?.kind !== 'string' && v?.kind !== 'word') throw new ScanError({ path: 'ttrl.collapsed', message: '`collapsed` must be a list of strings', line: prop.line });
              canvas.collapsed.push(v.text);
            }
            break;
          }
          default:
            c.skipValue(); // chains — TTR-P's SSA orphaning record, meaningless to a layout
            if (prop.text !== 'chains') warnings.push({ path: `ttrl.${prop.text}`, message: `unknown canvas property \`${prop.text}\` — ignored`, line: prop.line });
        }
      }
      canvases.push(canvas);
    }
    return { ok: true, version: 1, canvases, warnings };
  } catch (e) {
    if (e instanceof ScanError) return { ok: false, errors: [e.issue], warnings };
    throw e;
  }
}

/** A TTR read: the document plus where its positions came from (V-11). */
export type TtrReadResult =
  | { ok: true; doc: LayoutDocument; warnings: LayoutIssue[]; positionsFrom: 'in-file' | 'sidecar' | null }
  | { ok: false; errors: LayoutIssue[]; warnings: LayoutIssue[] };

/**
 * A `.ttrg` and, optionally, its sibling `.ttrl` → one layout document (V-11). Positions come from the
 * sidecar's `canvas <graph name>` when it has nodes (the sidecar wins), else from the in-file block.
 * The sidecar also contributes `skin` and `collapsed`, which the `.ttrg` block cannot carry.
 */
export function mergeTtr(ttrgText: string, ttrlText?: string, opts: ReadOptions = {}): TtrReadResult {
  const parsed = parseTtrg(ttrgText);
  const warnings = [...parsed.warnings];
  if (!parsed.graph) return { ok: false, errors: parsed.errors, warnings };
  const g = parsed.graph;
  if (g.model === undefined) return { ok: false, errors: [{ path: 'model', message: 'the graph has no `model:`', line: g.lines.name }], warnings };

  let positions = g.nodes;
  let positionsFrom: 'in-file' | 'sidecar' | null = Object.keys(g.nodes).length > 0 ? 'in-file' : null;
  const positionLines: Record<string, number> = { ...g.lines.nodes };
  let skin: string | undefined;
  let collapsed: string[] | undefined;
  if (ttrlText !== undefined) {
    const side = readTtrl(ttrlText);
    if (!side.ok) return { ok: false, errors: side.errors, warnings: [...warnings, ...side.warnings] };
    warnings.push(...side.warnings);
    const canvas = side.canvases.find((cv) => cv.key === g.name);
    if (!canvas) {
      warnings.push({ path: 'ttrl', message: `the sidecar has no canvas ${g.name} — its positions are not used` });
    } else {
      if (Object.keys(canvas.nodes).length > 0) {
        positions = canvas.nodes;
        positionsFrom = 'sidecar';
        for (const k of Object.keys(positionLines)) delete positionLines[k];
      }
      if (canvas.skin) skin = canvas.skin;
      if (canvas.collapsed.length > 0) collapsed = canvas.collapsed;
    }
  }

  const doc: LayoutDocument = { name: g.name, model: g.model as LayoutModel, objects: g.objects, positions };
  if (skin !== undefined) doc.skin = skin;
  if (collapsed !== undefined) doc.collapsed = collapsed;
  const v = validateDocument(doc);
  if (!v.ok) {
    const lineOf = (path: string): number | undefined => {
      if (path === 'name') return g.lines.name;
      if (path === 'model') return g.lines.model;
      const obj = /^objects\[(\d+)\]$/.exec(path);
      if (obj) return g.lines.objects[Number(obj[1])];
      const pos = /^positions\.(.*?)(?:\.[xy])?$/.exec(path);
      return pos ? positionLines[pos[1]] : undefined;
    };
    return { ok: false, errors: v.errors.map((e) => ({ ...e, line: lineOf(e.path) })), warnings: [...warnings, ...v.warnings] };
  }
  return { ok: true, doc: canonicalize(doc).doc, warnings: [...warnings, ...v.warnings, ...stemWarning(opts.fileName, g.name)], positionsFrom };
}

/** A `.ttrg` on its own (V-11 a). */
export function readTtrg(text: string, opts: ReadOptions = {}): TtrReadResult {
  return mergeTtr(text, undefined, opts);
}
