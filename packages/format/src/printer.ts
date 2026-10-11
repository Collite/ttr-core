// SPDX-License-Identifier: Apache-2.0
import type {
  Document, Definition, SourceLocation, ParameterDef, GraphBlock, Trivia,
} from '@tatrman/parser';
import { Doc, text, verbatim, concat, line, hardline, indent, group, join } from './ir.js';
import { render } from './render.js';

export interface FormatConfig {
  separator: 'newline' | 'comma' | 'preserve';
  alignKeys: boolean;
  indentSpaces: number;
  width: number;
}

export const DEFAULT_FORMAT_CONFIG: FormatConfig = {
  separator: 'preserve',
  alignKeys: false,
  indentSpaces: 4,
  width: 100,
};

interface Ctx {
  source: string;
  config: FormatConfig;
}

/** Any node that may carry comment trivia (P0). */
interface TriviaNode {
  leadingTrivia?: Trivia[];
  trailingTrivia?: Trivia[];
}

function slice(src: string, loc: SourceLocation): string {
  return src.slice(loc.offsetStart, loc.offsetEnd);
}

/** Verbatim source text of any node that carries a `source` span. */
function vsrc(ctx: Ctx, node: { source: SourceLocation }): Doc {
  return verbatim(slice(ctx.source, node.source));
}

// --- comment trivia emission ------------------------------------------------
// Leading comments are emitted each on their own line above the node; trailing
// comments stay on the node's line. A comment inside a verbatim-sliced span is
// preserved for free (it is part of the raw slice) and is never re-emitted here,
// because the printer only emits trivia for nodes it renders structurally.

function leadingDocs(node: TriviaNode | undefined): Doc[] {
  if (!node?.leadingTrivia?.length) return [];
  return node.leadingTrivia.flatMap((t) => [verbatim(t.text), hardline]);
}

function trailingInline(node: TriviaNode | undefined): Doc {
  if (!node?.trailingTrivia?.length) return concat([]);
  return concat(node.trailingTrivia.map((t) => concat([text(' '), verbatim(t.text)])));
}

function hasTrailing(node: TriviaNode | undefined): boolean {
  return !!node?.trailingTrivia?.length;
}

/** Wrap a Doc with the node's leading (above) and trailing (same-line) comments. */
function withTrivia(node: TriviaNode, doc: Doc): Doc {
  const lead = leadingDocs(node);
  const trail = trailingInline(node);
  if (!lead.length && !node.trailingTrivia?.length) return doc;
  return concat([...lead, doc, trail]);
}

function strListDoc(arr: string[], quoted: boolean): Doc {
  const items = arr.map((s) => (quoted ? JSON.stringify(s) : s));
  return text(`[${items.join(', ')}]`);
}

// A bare TTR identifier (mirrors the grammar's IDENT, incl. Latin-1/Extended).
const BARE_IDENT = /^[A-Za-zÀ-ɏ_][A-Za-z0-9_À-ɏ]*$/;

/**
 * Render `primaryKey` as the clean bare-id list (`[ss_item_sk, ss_ticket_number]`) when every
 * key is a valid identifier (column names always are). Falls back to the quoted
 * form if any name isn't a bare identifier — the grammar forbids mixing strings
 * and ids in one list, so it's all-or-nothing.
 */
function keyListDoc(arr: string[]): Doc {
  return strListDoc(arr, !arr.every((s) => BARE_IDENT.test(s)));
}

interface Prop { key: string; value: Doc; node?: TriviaNode }

// The AST stores camelCase kinds, but the grammar's `def` keyword is snake_case
// for the mapping kinds.
const KIND_KEYWORD: Record<string, string> = {
  er2dbEntity: 'er2db_entity',
  er2dbAttribute: 'er2db_attribute',
  er2dbRelation: 'er2db_relation',
  er2cncRole: 'er2cnc_role',
};
function kindKeyword(kind: string): string {
  return KIND_KEYWORD[kind] ?? kind;
}

// Collect a def's present properties as ordered [key, valueDoc] pairs. Order is
// fixed (canonical) so formatting is deterministic / idempotent. Values that
// carry a `source` span are sliced verbatim; source-less primitives are
// reconstructed in their grammar form. When a value carries a `source` node we
// keep it on the Prop so a trailing comment on that line can be re-emitted.
function propsOf(def: Definition, ctx: Ctx): Prop[] {
  const p: Prop[] = [];
  const add = (key: string, value: Doc | undefined, node?: TriviaNode) => {
    if (value) p.push({ key, value, node });
  };
  const v = (n: { source: SourceLocation } | undefined) => (n ? vsrc(ctx, n) : undefined);
  const b = (x: boolean | undefined) => (x === undefined ? undefined : text(x ? 'true' : 'false'));
  const qstr = (x: string | undefined) => (x === undefined ? undefined : text(JSON.stringify(x)));
  const defList = (arr: Definition[] | undefined) => (arr && arr.length ? defListDoc(arr, ctx) : undefined);

  switch (def.kind) {
    case 'project':
      add('description', v(def.description), def.description); add('tags', def.tags && strListDoc(def.tags, true)); add('version', qstr(def.version));
      break;
    case 'table':
      add('description', v(def.description), def.description); add('tags', def.tags && strListDoc(def.tags, true));
      add('primaryKey', def.primaryKey && keyListDoc(def.primaryKey));
      add('columns', defList(def.columns)); add('indices', defList(def.indices)); add('constraints', defList(def.constraints));
      add('search', v(def.search), def.search); add('semantics', v(def.semantics), def.semantics); add('lexicon', v(def.lexicon), def.lexicon);
      break;
    case 'view':
      add('description', v(def.description), def.description); add('tags', def.tags && strListDoc(def.tags, true));
      add('columns', defList(def.columns)); add('definitionSql', v(def.definitionSql), def.definitionSql); add('search', v(def.search), def.search);
      break;
    case 'column':
      add('description', v(def.description), def.description); add('tags', def.tags && strListDoc(def.tags, true));
      add('type', v(def.type), def.type); add('optional', b(def.optional)); add('isKey', b(def.isKey)); add('indexed', b(def.indexed));
      add('search', v(def.search), def.search); add('semantics', v(def.semantics), def.semantics); add('lexicon', v(def.lexicon), def.lexicon);
      break;
    case 'index':
      // Grammar keyword for index/constraint type is `type` (DATA_TYPE token).
      add('description', v(def.description), def.description); add('type', def.indexType && text(def.indexType));
      add('columns', def.columns && strListDoc(def.columns, true));
      break;
    case 'constraint':
      add('description', v(def.description), def.description); add('type', def.constraintType && text(def.constraintType));
      add('columns', def.columns && strListDoc(def.columns, true));
      break;
    case 'fk':
      add('description', v(def.description), def.description); add('tags', def.tags && strListDoc(def.tags, true));
      add('from', v(def.from), def.from); add('to', v(def.to), def.to);
      break;
    case 'procedure':
      add('description', v(def.description), def.description); add('tags', def.tags && strListDoc(def.tags, true));
      add('parameters', def.parameters && def.parameters.length ? paramListDoc(def.parameters, ctx) : undefined);
      add('resultColumns', defList(def.resultColumns));
      break;
    case 'entity':
      add('description', v(def.description), def.description); add('tags', def.tags && strListDoc(def.tags, true));
      add('labelPlural', qstr(def.labelPlural)); add('nameAttribute', v(def.nameAttribute), def.nameAttribute); add('codeAttribute', v(def.codeAttribute), def.codeAttribute);
      add('aliases', def.aliases && strListDoc(def.aliases, true)); add('attributes', defList(def.attributes));
      add('roles', def.roles && strListDoc(def.roles, false)); add('displayLabel', v(def.displayLabel), def.displayLabel); add('search', v(def.search), def.search); add('semantics', v(def.semantics), def.semantics); add('lexicon', v(def.lexicon), def.lexicon);
      break;
    case 'attribute':
      add('description', v(def.description), def.description); add('tags', def.tags && strListDoc(def.tags, true));
      add('type', v(def.type), def.type); add('isKey', b(def.isKey)); add('optional', b(def.optional));
      add('valueLabels', v(def.valueLabels), def.valueLabels); add('displayLabel', v(def.displayLabel), def.displayLabel); add('search', v(def.search), def.search); add('semantics', v(def.semantics), def.semantics); add('lexicon', v(def.lexicon), def.lexicon);
      break;
    case 'relation':
      add('description', v(def.description), def.description); add('tags', def.tags && strListDoc(def.tags, true));
      add('from', v(def.from), def.from); add('to', v(def.to), def.to);
      add('cardinality', v(def.cardinality), def.cardinality); add('join', v(def.join), def.join); add('search', v(def.search), def.search);
      break;
    case 'er2dbEntity':
      add('description', v(def.description), def.description); add('tags', def.tags && strListDoc(def.tags, true));
      add('entity', v(def.entity), def.entity); add('target', v(def.target), def.target); add('whereFilter', v(def.whereFilter), def.whereFilter);
      break;
    case 'er2dbAttribute':
      add('description', v(def.description), def.description); add('tags', def.tags && strListDoc(def.tags, true));
      add('attribute', v(def.attribute), def.attribute); add('target', v(def.target), def.target);
      break;
    case 'er2dbRelation':
      add('description', v(def.description), def.description); add('tags', def.tags && strListDoc(def.tags, true));
      add('relation', v(def.relation), def.relation); add('fk', v(def.fk), def.fk);
      break;
    case 'query':
      add('description', v(def.description), def.description); add('tags', def.tags && strListDoc(def.tags, true));
      add('language', def.language && text(def.language));
      add('parameters', def.parameters && def.parameters.length ? paramListDoc(def.parameters, ctx) : undefined);
      add('sourceText', v(def.sourceText), def.sourceText); add('search', v(def.search), def.search);
      break;
    case 'role':
      add('description', v(def.description), def.description); add('tags', def.tags && strListDoc(def.tags, true));
      add('label', v(def.label), def.label); add('search', v(def.search), def.search);
      break;
    case 'er2cncRole':
      add('description', v(def.description), def.description); add('tags', def.tags && strListDoc(def.tags, true));
      add('entity', v(def.entity), def.entity); add('role', v(def.role), def.role);
      break;
    // 0.14 — a named row schema: the column list, formatted exactly like a view's.
    case 'schema':
      add('description', v(def.description), def.description); add('tags', def.tags && strListDoc(def.tags, true));
      add('columns', defList(def.columns));
      break;
    case 'area':
      add('description', v(def.description), def.description); add('tags', def.tags && strListDoc(def.tags, true));
      // packages/entities are bare-id (dotted) lists — never quoted.
      add('packages', def.packages.length ? strListDoc(def.packages, false) : undefined);
      add('entities', def.entities.length ? strListDoc(def.entities, false) : undefined);
      break;
    // v4.4 lexicon entries (RG-P4). One shared body; the emitted key for the
    // target is `for` (the grammar keyword), the AST field is `target`.
    case 'term':
    case 'pattern':
    case 'example':
      add('description', v(def.description), def.description); add('tags', def.tags && strListDoc(def.tags, true));
      add('for', v(def.target), def.target);
      add('forms', def.forms && strListDoc(def.forms, true));
      add('match', qstr(def.match));
      add('text', qstr(def.text));
      break;
  }
  return p;
}

function paramListDoc(params: ParameterDef[], ctx: Ctx): Doc {
  // Parameters carry a source span each; slice verbatim, one per line.
  const items = params.map((pm) => vsrc(ctx, pm));
  return concat([
    text('['),
    indent(concat([hardline, join(items, concat([text(','), hardline]))])),
    hardline,
    text(']'),
  ]);
}

function defListDoc(defs: Definition[], ctx: Ctx): Doc {
  // Build items manually so a trailing comment lands AFTER the separating comma
  // (`def column id {…}, // pk`) — keeping it on the line and re-attachable to
  // the same node on the next parse (idempotent).
  const parts: Doc[] = [];
  defs.forEach((d, i) => {
    const last = i === defs.length - 1;
    parts.push(...leadingDocs(d));
    parts.push(formatDefCore(d, ctx, false));
    if (!last) parts.push(text(','));
    parts.push(trailingInline(d));
    if (!last) parts.push(hardline);
  });
  return concat([
    text('['),
    indent(concat([hardline, concat(parts)])),
    hardline,
    text(']'),
  ]);
}

function propsDoc(props: Prop[], ctx: Ctx, broken: boolean): Doc {
  const align = ctx.config.alignKeys && broken;
  const maxKey = align ? Math.max(...props.map((pr) => pr.key.length)) : 0;
  const entry = (pr: Prop): Doc =>
    concat([text(align ? `${pr.key}:`.padEnd(maxKey + 2) : `${pr.key}: `), pr.value]);
  // Interleave manually so a value's trailing comment lands after the comma.
  const parts: Doc[] = [];
  props.forEach((pr, i) => {
    const last = i === props.length - 1;
    parts.push(entry(pr));
    if (!last) parts.push(text(','));
    parts.push(trailingInline(pr.node));
    if (!last) parts.push(broken ? hardline : line);
  });
  return concat(parts);
}

/** A def's properties on one line: `k: v, k2: v2`. */
function inlinePropsDoc(props: Prop[]): Doc {
  return join(props.map((pr) => concat([text(`${pr.key}: `), pr.value])), text(', '));
}

/** True if rendering this def inline would swallow a `//` comment to end-of-line. */
function defHasComments(def: Definition, props: Prop[]): boolean {
  return hasTrailing(def) || props.some((pr) => hasTrailing(pr.node));
}

/** The def body without its own leading/trailing trivia (added by callers). */
function formatDefCore(def: Definition, ctx: Ctx, topLevel: boolean): Doc {
  const props = propsOf(def, ctx);
  const header = `def ${kindKeyword(def.kind)} ${def.name} {`;
  if (props.length === 0) return text(`def ${kindKeyword(def.kind)} ${def.name} {}`);

  const commentsForceBreak = defHasComments(def, props);

  if (!topLevel) {
    if (commentsForceBreak) {
      const body = concat([indent(concat([hardline, propsDoc(props, ctx, true)])), hardline, text('}')]);
      return concat([text(header), body]);
    }
    // Nested defs are width-based: the group renders flat if it fits, else breaks.
    const body = concat([indent(concat([line, propsDoc(props, ctx, false)])), line, text('}')]);
    return group(concat([text(header), body]));
  }

  // Top-level layout is governed by the `separator` setting:
  //   'newline'  → always one property per line
  //   'comma'    → always a single line (forced inline, regardless of width)
  //   'preserve' → break iff the original def spanned multiple lines
  const broken = commentsForceBreak
    ? true
    : ctx.config.separator === 'newline'
      ? true
      : ctx.config.separator === 'comma'
        ? false
        : def.source.line !== def.source.endLine;

  if (!broken) {
    return concat([text(header), text(' '), inlinePropsDoc(props), text(' }')]);
  }
  const body = concat([indent(concat([hardline, propsDoc(props, ctx, true)])), hardline, text('}')]);
  return concat([text(header), body]);
}

function formatDef(def: Definition, ctx: Ctx, topLevel: boolean): Doc {
  return withTrivia(def, formatDefCore(def, ctx, topLevel));
}

function formatGraph(graph: GraphBlock): Doc {
  const props: Prop[] = [];
  if (graph.schema) props.push({ key: 'model', value: text(graph.schema) });
  if (graph.description !== undefined) props.push({ key: 'description', value: text(JSON.stringify(graph.description)) });
  if (graph.tags && graph.tags.length) props.push({ key: 'tags', value: strListDoc(graph.tags, true) });
  // objects always break one-per-line.
  const objects = concat([
    text('['),
    indent(concat([hardline, join(graph.objects.map((o) => text(o)), concat([text(','), hardline]))])),
    hardline,
    text(']'),
  ]);
  props.push({ key: 'objects', value: graph.objects.length ? objects : text('[]') });
  if (graph.layout) props.push({ key: 'layout', value: formatLayout(graph.layout) });

  const sep = concat([text(','), hardline]);
  const entry = (pr: Prop) => concat([text(`${pr.key}: `), pr.value]);
  return concat([
    text(`graph ${graph.name} {`),
    indent(concat([hardline, join(props.map(entry), sep)])),
    hardline,
    text('}'),
  ]);
}

function formatLayout(layout: NonNullable<GraphBlock['layout']>): Doc {
  const nodeKeys = Object.keys(layout.nodes);
  const nodeEntries = nodeKeys.map((q) => text(`${q}: { x: ${layout.nodes[q].x}, y: ${layout.nodes[q].y} }`));
  const nodesDoc = nodeKeys.length
    ? concat([text('{'), indent(concat([hardline, join(nodeEntries, concat([text(','), hardline]))])), hardline, text('}')])
    : text('{}');
  const items: Doc[] = [];
  if (layout.viewport) {
    const vp = layout.viewport;
    items.push(text(`viewport: { zoom: ${vp.zoom}, panX: ${vp.panX}, panY: ${vp.panY}, displayMode: ${JSON.stringify(vp.displayMode)} }`));
  }
  items.push(concat([text('nodes: '), nodesDoc]));
  return concat([text('{'), indent(concat([hardline, join(items, concat([text(','), hardline]))])), hardline, text('}')]);
}

/** Render a top-level node-backed block, prefixing/suffixing its comments. */
function blockWithComments(node: TriviaNode, body: string): string {
  const lead = (node.leadingTrivia ?? []).map((t) => t.text + '\n').join('');
  const trail = (node.trailingTrivia ?? []).map((t) => ' ' + t.text).join('');
  return lead + body + trail;
}

export function formatDocument(ast: Document, source: string, config: FormatConfig = DEFAULT_FORMAT_CONFIG): string {
  const ctx: Ctx = { source, config };
  const blocks: string[] = [];

  if (ast.packageDecl) blocks.push(blockWithComments(ast.packageDecl, `package ${ast.packageDecl.name}`));
  if (ast.imports.length) {
    blocks.push(ast.imports.map((imp) => blockWithComments(imp, `import ${imp.target}${imp.wildcard ? '.*' : ''}`)).join('\n'));
  }
  if (ast.modelDirective) {
    const sd = ast.modelDirective;
    blocks.push(blockWithComments(sd, `model ${sd.modelCode}${sd.schema ? ` schema ${sd.schema}` : ''}`));
  }
  if (ast.graph) {
    const rendered = render(formatGraph(ast.graph), { width: config.width, indentSpaces: config.indentSpaces });
    blocks.push(blockWithComments(ast.graph, rendered));
  }
  for (const def of ast.definitions) {
    blocks.push(render(formatDef(def, ctx, true), { width: config.width, indentSpaces: config.indentSpaces }));
  }

  return blocks.join('\n\n') + '\n';
}
