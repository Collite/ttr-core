// SPDX-License-Identifier: Apache-2.0
import type {
  Document, Definition, SourceLocation, ParameterDef, GraphBlock, Trivia,
  ConceptBody, ConceptBindingDef, ConceptDef, ConceptParticipant, ConceptProperty, ConceptSugar, ListValue, ObjectValue,
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
  /** The cn printer's comment ledger: trivia it re-emitted, and the source ranges it sliced verbatim. */
  cn?: CnLedger;
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

/**
 * `sep` is what stands between key and value — `: ` except for the `concept { }` sugar. `atKey`: the
 * node starts at the key (a cn property, the sugar), so its leading comments print above the property
 * and re-attach there on the next parse. A typed value node starts after its key; a comment before it
 * would move on every pass, so it is not re-emitted.
 */
interface Prop { key: string; value: Doc; node?: TriviaNode; sep?: string; atKey?: boolean }

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
      if (def.concept) p.push(conceptSugarProp(def.concept, ctx));
      break;
    case 'attribute':
      add('description', v(def.description), def.description); add('tags', def.tags && strListDoc(def.tags, true));
      add('type', v(def.type), def.type); add('isKey', b(def.isKey)); add('optional', b(def.optional));
      add('valueLabels', v(def.valueLabels), def.valueLabels); add('displayLabel', v(def.displayLabel), def.displayLabel); add('search', v(def.search), def.search); add('semantics', v(def.semantics), def.semantics); add('lexicon', v(def.lexicon), def.lexicon);
      // 0.16 — lifecycle states (D-67); the property nodes carry the comments written on them.
      add('states', def.states && strListDoc(def.states, false), def.statesNode);
      add(
        'transitions',
        def.transitions && text(`{ ${def.transitions.map((t) => `${t.from}: [${t.to.join(', ')}]`).join(', ')} }`),
        def.transitionsNode,
      );
      if (def.concept) p.push(conceptSugarProp(def.concept, ctx));
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
      // 0.17 — the cn default sense (cn contracts §1.5).
      add('default', b(def.default));
      add('sense_priority', def.sensePriority === undefined ? undefined : text(String(def.sensePriority)));
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
    concat([text(pr.sep !== undefined ? `${pr.key}${pr.sep}` : align ? `${pr.key}:`.padEnd(maxKey + 2) : `${pr.key}: `), pr.value]);
  // Interleave manually so a value's trailing comment lands after the comma; a leading comment of an
  // `atKey` property goes on the lines above it.
  const parts: Doc[] = [];
  props.forEach((pr, i) => {
    const last = i === props.length - 1;
    if (broken && pr.atKey) parts.push(...leadingDocs(pr.node));
    parts.push(entry(pr));
    if (!last) parts.push(text(','));
    parts.push(trailingInline(pr.node));
    if (!last) parts.push(broken ? hardline : line);
  });
  return concat(parts);
}

/** A def's properties on one line: `k: v, k2: v2`. */
function inlinePropsDoc(props: Prop[]): Doc {
  return join(props.map((pr) => concat([text(`${pr.key}${pr.sep ?? ': '}`), pr.value])), text(', '));
}

/** True if rendering this def inline would swallow a `//` comment to end-of-line. */
/**
 * A value that always breaks (a def list) cannot sit in a one-line def: in 'preserve' mode the def
 * must break too, else the output spans several lines and the next pass breaks it (not idempotent).
 */
function hasHardline(d: Doc): boolean {
  switch (d.t) {
    case 'hardline':
      return true;
    case 'concat':
      return d.parts.some(hasHardline);
    case 'indent':
    case 'group':
      return hasHardline(d.doc);
    default:
      return false;
  }
}

function defHasComments(def: Definition, props: Prop[]): boolean {
  return hasTrailing(def) || props.some((pr) => hasTrailing(pr.node) || (!!pr.atKey && !!pr.node?.leadingTrivia?.length));
}

/** The def body without its own leading/trailing trivia (added by callers). */
function formatDefCore(def: Definition, ctx: Ctx, topLevel: boolean): Doc {
  // Kept VERBATIM — source order, every comment, every value form (review-114 M9): a world (its nested
  // members have no canonical layout yet; it used to print `def world w {}`), the six automation kinds and
  // an automation role (their free-form properties are in authored order, interleaved with the typed ones).
  if (isVerbatimDef(def)) return verbatim(slice(ctx.source, def.source));
  if (def.kind === 'concept' || def.kind === 'conceptBinding') return formatCnDef(def, ctx);
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

  const broken = topLevelBroken(def, ctx, commentsForceBreak, props.map((pr) => pr.value));

  if (!broken) {
    return concat([text(header), text(' '), inlinePropsDoc(props), text(' }')]);
  }
  const body = concat([indent(concat([hardline, propsDoc(props, ctx, true)])), hardline, text('}')]);
  return concat([text(header), body]);
}

/**
 * Top-level layout is governed by the `separator` setting:
 *   'newline'  → always one property per line
 *   'comma'    → always a single line (forced inline, regardless of width)
 *   'preserve' → break iff the original def spanned multiple lines
 * Comments always break it.
 */
function topLevelBroken(def: Definition, ctx: Ctx, commentsForceBreak: boolean, values: Doc[]): boolean {
  if (commentsForceBreak || ctx.config.separator === 'newline') return true;
  if (ctx.config.separator === 'comma') return false;
  return def.source.line !== def.source.endLine || values.some(hasHardline);
}

// The kinds `propsOf` lays out (plus the cn defs, laid out by `formatCnDef`). Every other kind is kept
// VERBATIM: the md model (domain · dimension · hierarchy · measure · cubelet · map · md2db_* ·
// md2er_cubelet), drill_map, the world's engine / executor / storage, a world, the six automation kinds
// — none has a canonical layout, and printing one through `propsOf` emptied its body (`def dimension
// Product {}`; an md domain even came back as the unparseable `def mdDomain …`).
const LAID_OUT_KINDS = new Set([
  'project', 'table', 'view', 'column', 'index', 'constraint', 'fk', 'procedure', 'entity', 'attribute',
  'relation', 'er2dbEntity', 'er2dbAttribute', 'er2dbRelation', 'query', 'role', 'er2cncRole', 'schema',
  'area', 'term', 'pattern', 'example', 'concept', 'conceptBinding',
]);

function isVerbatimDef(def: Definition): boolean {
  return !LAID_OUT_KINDS.has(def.kind) || (def.kind === 'role' && (def.entries?.length ?? 0) > 0);
}

// --- 0.17 the conceptual model (CN-P1 S1.4 T2) ---------------------------------------------------
// A cn body (a concept def, the `concept { }` sugar, a cn binding) prints its properties in ONE
// canonical order; a key the order does not list keeps its source order after the listed ones. Values
// print as written (a verbatim slice of the source); an expression block (`definition:`, `members:`,
// `filter:`) is never reflowed. `involves:` past three participants and a binding's `participants:`
// break one per line. Every comment in the body is either re-emitted or inside a slice; when the
// layout cannot place one, the def (or the sugar) is kept verbatim instead — never a lost comment.

/** Concept defs and the sugar: identity, documentation, typing, grounding, shape, behaviour, the rest. */
const CN_BODY_ORDER = [
  'description', 'label', 'tags', 'sources',
  'is_a', 'abstract', 'of', 'event',
  'extent', 'grounds', 'hierarchy', 'involves', 'period',
  'qualities', 'value_space', 'valued_state',
  'definition', 'imprecision', 'basis', 'members',
  'relations', 'lexicon',
];
/** The sugar's stereotype ref leads its body; `event:` only when no kind / role / quality does. */
const CN_SUGAR_STEREOTYPES = ['kind', 'role', 'quality', 'phase', 'event'];
/** Bindings, the mapping-def way (`er2db_entity`): documentation, the concept, its target, the path. */
const CN_BINDING_ORDER = [
  'description', 'tags',
  'kind', 'role', 'quality', 'event', 'phase', 'concept',
  'target', 'period', 'hierarchy', 'participants', 'via', 'filter',
];
/** `involves:` stays on one line up to this many participants. */
const CN_INVOLVES_INLINE_MAX = 3;

interface CnLedger {
  emitted: Set<Trivia>;
  slices: Array<[number, number]>;
  /** The slices of the first pass: a comment inside one is printed by it, never re-emitted. */
  sliced: (t: Trivia) => boolean;
}

function cnOrder(body: ConceptBody, order: string[]): ConceptProperty[] {
  const rank = (p: ConceptProperty) => {
    const i = order.indexOf(p.key);
    return i === -1 ? order.length : i;
  };
  // Array.prototype.sort is stable: equal ranks (unlisted keys, a repeated key) keep source order.
  return [...body.properties].sort((a, b) => rank(a) - rank(b));
}

/**
 * Where a `key: value`'s value starts (`node` starts at its key): past the key, whitespace and one `:`.
 * A comment after the `:` starts the value (it travels with it); one before the `:` is skipped — it
 * is then in no slice, so the ledger keeps the def verbatim rather than print a second `:` after it.
 */
function cnValueStart(ctx: Ctx, node: { key: string; source: SourceLocation }): number {
  const src = ctx.source;
  const end = node.source.offsetEnd;
  let i = node.source.offsetStart + node.key.length;
  let colon = false;
  while (i < end) {
    const c = src[i];
    if (c === ' ' || c === '\t' || c === '\r' || c === '\n') i++;
    else if (c === ':' && !colon) { colon = true; i++; }
    else if (!colon && (src.startsWith('//', i) || src.startsWith('/*', i))) {
      const close = src.startsWith('//', i) ? src.indexOf('\n', i) : src.indexOf('*/', i) + 2;
      i = close <= i ? end : Math.min(end, close);
    } else break;
  }
  return i;
}

/** A verbatim slice of the source, recorded in the ledger (a comment inside it is preserved). */
function cnSlice(ctx: Ctx, start: number, end: number): Doc {
  ctx.cn?.slices.push([start, end]);
  return verbatim(ctx.source.slice(start, end));
}

/** `node`'s comments the layout re-emits (not inside a verbatim slice), recorded in the ledger. */
function cnOwn(ctx: Ctx, node: TriviaNode): TriviaNode {
  const ledger = ctx.cn;
  if (!ledger) return node;
  const keep = (ts: Trivia[] | undefined) => ts?.filter((t) => !ledger.sliced(t));
  const own = { leadingTrivia: keep(node.leadingTrivia), trailingTrivia: keep(node.trailingTrivia) };
  for (const t of [...(own.leadingTrivia ?? []), ...(own.trailingTrivia ?? [])]) ledger.emitted.add(t);
  return own;
}

function hasTrivia(node: TriviaNode | undefined): boolean {
  return !!node?.leadingTrivia?.length || !!node?.trailingTrivia?.length;
}

function participantDoc(pt: ConceptParticipant): Doc {
  return text(pt.label === undefined ? pt.ref.path : `${pt.ref.path} as ${pt.label}`);
}

/** `[A, B as b, C]`, or one participant per line past three (or with a comment on one). */
function involvesDoc(ctx: Ctx, participants: ConceptParticipant[]): Doc {
  const broken = participants.length > CN_INVOLVES_INLINE_MAX || participants.some((pt) => hasTrivia(pt));
  if (!broken) return concat([text('['), join(participants.map(participantDoc), text(', ')), text(']')]);
  return bracketedLines(ctx, '[', ']', participants.map((pt) => ({ node: pt, doc: participantDoc(pt) })));
}

/** A binding's `participants: { label: [path], … }` — one label per line. */
function participantsObjectDoc(ctx: Ctx, obj: ObjectValue): Doc {
  if (obj.entries.length === 0) return text('{}');
  return bracketedLines(ctx, '{', '}', obj.entries.map((e) => ({
    node: e,
    doc: concat([text(`${e.key}: `), cnSlice(ctx, cnValueStart(ctx, e), e.source.offsetEnd)]),
  })));
}

/** `open` · one item per line (its comments around it) · `close`. */
function bracketedLines(ctx: Ctx, open: string, close: string, items: Array<{ node: TriviaNode; doc: Doc }>): Doc {
  const parts: Doc[] = [];
  items.forEach(({ node, doc }, i) => {
    const last = i === items.length - 1;
    const own = cnOwn(ctx, node);
    parts.push(...leadingDocs(own), doc);
    if (!last) parts.push(text(','));
    parts.push(trailingInline(own));
    if (!last) parts.push(hardline);
  });
  return concat([text(open), indent(concat([hardline, concat(parts)])), hardline, text(close)]);
}

/** A list of plain items (ids, strings, numbers, booleans, no comments) prints like `tags:` — `[a, b]`. */
function plainListDoc(ctx: Ctx, list: ListValue): Doc | undefined {
  const plain = list.items.every((it) => PLAIN_ITEM_KINDS.has(it.kind) && !hasTrivia(it));
  if (!plain || hasTrivia(list)) return undefined;
  const items = list.items.map((it) => cnSlice(ctx, it.source.offsetStart, it.source.offsetEnd));
  return concat([text('['), join(items, text(', ')), text(']')]);
}
const PLAIN_ITEM_KINDS = new Set(['id', 'string', 'number', 'bool', 'null']);

function cnValueDoc(ctx: Ctx, prop: ConceptProperty): Doc {
  if (prop.kind === 'involves') return involvesDoc(ctx, prop.participants);
  if (prop.kind === 'entry' && prop.key === 'participants' && prop.value.kind === 'object') {
    return participantsObjectDoc(ctx, prop.value);
  }
  if (prop.kind === 'entry' && prop.value.kind === 'list') {
    const doc = plainListDoc(ctx, prop.value);
    if (doc) return doc;
  }
  return cnSlice(ctx, cnValueStart(ctx, prop), prop.source.offsetEnd);
}

function cnProps(ctx: Ctx, properties: ConceptProperty[]): Prop[] {
  return properties.map((pr) => {
    const value = cnValueDoc(ctx, pr);
    return { key: pr.key, value, node: cnOwn(ctx, pr), atKey: true };
  });
}

/** Every comment the parser attached anywhere under `node`. */
function triviaUnder(node: unknown, out: Trivia[] = []): Trivia[] {
  if (node === null || typeof node !== 'object') return out;
  if (Array.isArray(node)) {
    for (const x of node) triviaUnder(x, out);
    return out;
  }
  for (const [k, v] of Object.entries(node as Record<string, unknown>)) {
    if (k === 'leadingTrivia' || k === 'trailingTrivia') out.push(...(v as Trivia[]));
    else if (k !== 'source') triviaUnder(v, out);
  }
  return out;
}

/**
 * Lay `properties` out; undefined when a comment would be lost. Two passes: the first finds where the
 * verbatim slices fall, the second re-emits only the comments outside them — the parser may hand a
 * comment inside one value to the property before it (in `a: x, b: <comment> y` it is `a`'s trailing
 * one). Every comment under the body must then be re-emitted or sliced.
 */
function cnLayout(ctx: Ctx, body: ConceptBody, properties: ConceptProperty[]): Prop[] | undefined {
  const outer = ctx.cn;
  try {
    ctx.cn = { emitted: new Set(), slices: [], sliced: () => false };
    cnProps(ctx, properties);
    const slices = ctx.cn.slices;
    const sliced = (t: Trivia) => slices.some(([s, e]) => s <= t.source.offsetStart && t.source.offsetEnd <= e);
    const ledger: CnLedger = { emitted: new Set(), slices: [], sliced };
    ctx.cn = ledger;
    const props = cnProps(ctx, properties);
    return triviaUnder(body.properties).every((t) => ledger.emitted.has(t) || sliced(t)) ? props : undefined;
  } finally {
    ctx.cn = outer;
  }
}

/** A concept def or a cn binding — always top-level (neither nests in a def list). */
function formatCnDef(def: ConceptDef | ConceptBindingDef, ctx: Ctx): Doc {
  const header = `def ${def.kind === 'concept' ? def.stereotype : def.bindingKind} ${def.name}`;
  const order = def.kind === 'concept' ? CN_BODY_ORDER : CN_BINDING_ORDER;
  const props = cnLayout(ctx, def, cnOrder(def, order));
  if (!props) return verbatim(slice(ctx.source, def.source));
  if (props.length === 0) return text(`${header} {}`);
  const comments = hasTrailing(def) || props.some((pr) => hasTrivia(pr.node));
  const broken = topLevelBroken(def, ctx, comments, props.map((pr) => pr.value));
  return concat([text(`${header} `), cnBodyDoc(ctx, props, broken ? 'broken' : 'inline')]);
}

/** `{ k: v, … }` — one per line (`broken`), one line (`inline`), or whichever fits the width (`fit`). */
function cnBodyDoc(ctx: Ctx, props: Prop[], mode: 'broken' | 'inline' | 'fit'): Doc {
  if (mode === 'inline') return concat([text('{ '), inlinePropsDoc(props), text(' }')]);
  if (mode === 'broken') return concat([text('{'), indent(concat([hardline, propsDoc(props, ctx, true)])), hardline, text('}')]);
  return group(concat([text('{'), indent(concat([line, propsDoc(props, ctx, false)])), line, text('}')]));
}

/**
 * The `concept { … }` sugar of an entity or attribute (cn contracts §1.4 — written without the `:`).
 * Its stereotype ref leads, the rest in the cn body order; it breaks when it does not fit the width
 * or carries a comment. A sugar whose comments the layout cannot place is kept verbatim.
 */
function conceptSugarProp(sugar: ConceptSugar, ctx: Ctx): Prop {
  const stereotype = CN_SUGAR_STEREOTYPES.find((k) => sugar.properties.some((pr) => pr.key === k));
  const order = stereotype ? [stereotype, ...CN_BODY_ORDER.filter((k) => k !== stereotype)] : CN_BODY_ORDER;
  const props = cnLayout(ctx, sugar, cnOrder(sugar, order));
  if (!props) {
    // Verbatim from the `{`; the sugar's own comments are re-emitted only where they lie outside it.
    const start = cnValueStart(ctx, { key: 'concept', source: sugar.source });
    return { key: 'concept', sep: ' ', value: verbatim(ctx.source.slice(start, sugar.source.offsetEnd)), node: outsideTrivia(sugar, sugar.source), atKey: true };
  }
  const value = props.length === 0
    ? text('{}')
    : cnBodyDoc(ctx, props, props.some((pr) => hasTrivia(pr.node)) ? 'broken' : 'fit');
  return { key: 'concept', sep: ' ', value, node: sugar, atKey: true };
}

/** `node`'s comments that lie outside `span` (one inside a verbatim slice of `span` is already in it). */
function outsideTrivia(node: TriviaNode, span: SourceLocation): TriviaNode {
  const outside = (t: Trivia) => t.source.offsetEnd <= span.offsetStart || t.source.offsetStart >= span.offsetEnd;
  return { leadingTrivia: node.leadingTrivia?.filter(outside), trailingTrivia: node.trailingTrivia?.filter(outside) };
}

function formatDef(def: Definition, ctx: Ctx, topLevel: boolean): Doc {
  const core = formatDefCore(def, ctx, topLevel);
  // A def kept verbatim (no layout, or a cn def whose comments the layout cannot place) is one slice.
  return withTrivia(core.t === 'verbatim' ? outsideTrivia(def, def.source) : def, core);
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
    // `locale` (v4.4) was dropped until 0.17 — a `model lexicon locale en` came back as `model lexicon`.
    blocks.push(blockWithComments(sd, `model ${sd.modelCode}${sd.schema ? ` schema ${sd.schema}` : ''}${sd.locale ? ` locale ${sd.locale}` : ''}`));
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
