// SPDX-License-Identifier: Apache-2.0
import type { Trivia } from './cst/trivia.js';

/**
 * Source-file location for a parsed node or diagnostic.
 *
 * Conventions (match ANTLR's TokenStream, not LSP):
 *   - `line` and `endLine` are 1-indexed.
 *   - `column` and `endColumn` are 0-indexed (column of the first character of the token / one past the last).
 *   - `offsetStart` and `offsetEnd` are 0-indexed byte offsets into the source file;
 *     `offsetEnd` is exclusive.
 *
 * LSP consumers must subtract 1 from `line`/`endLine` to produce LSP positions.
 */
export interface SourceLocation {
  file: string;
  line: number;
  column: number;
  endLine: number;
  endColumn: number;
  offsetStart: number;
  offsetEnd: number;
}

// ============================================================================
// Common AST types (used by multiple per-kind types)
// ============================================================================

export type PropertyValue =
  | StringValue
  | TripleStringValue
  | TaggedBlockValue
  | NumberValue
  | BoolValue
  | NullValue
  | IdValue
  | ListValue
  | ObjectValue
  | FunctionCallValue;

export interface StringValue {
  kind: 'string';
  value: string;
  source: SourceLocation;
  leadingTrivia?: Trivia[];
  trailingTrivia?: Trivia[];
}

export interface TripleStringValue {
  kind: 'tripleString';
  value: string;
  source: SourceLocation;
  leadingTrivia?: Trivia[];
  trailingTrivia?: Trivia[];
}

/** Embedded-language dialects backed by a generated SQL grammar (embedded-sql contracts §3). */
export type SqlDialect = 'tsql' | 'postgres' | 'duckdb' | 'mysql' | 'bigquery';

/** Resolved embedded-language kind. Mirrors {@link QueryLanguage}. */
export type LanguageKind = QueryLanguage;

/**
 * A tagged triple-quoted block (`"""<tag>␊…"""`) carrying embedded foreign-language
 * source — SQL today (embedded-sql DESIGN §3, contracts §2.1). Produced only by the
 * `sourceText` / `definitionSql` properties via `embeddedBlock`. The tag is peeled
 * before `value`, so it never reaches the executed SQL.
 */
export interface TaggedBlockValue {
  kind: 'taggedBlock';
  tag: string;                  // raw tag text, e.g. 'sql' | 'ms-sql' | 'postgres'
  language: LanguageKind;       // resolved from the tag via TAG_REGISTRY
  dialect: SqlDialect | null;   // null for a bare `sql` (→ modeler.toml default) or non-SQL
  value: string;                // tag/fence stripped, dedented, one trailing newline removed
  tagSource: SourceLocation;    // span of the tag token text
  valueSource: SourceLocation;  // span of `value` within the file (post-dedent)
  indentWidth: number;          // common indent removed by dedent (for the §8 source map)
  source: SourceLocation;       // whole literal
  leadingTrivia?: Trivia[];
  trailingTrivia?: Trivia[];
}

export interface NumberValue {
  kind: 'number';
  value: number;
  source: SourceLocation;
  leadingTrivia?: Trivia[];
  trailingTrivia?: Trivia[];
}

export interface BoolValue {
  kind: 'bool';
  value: boolean;
  source: SourceLocation;
  leadingTrivia?: Trivia[];
  trailingTrivia?: Trivia[];
}

export interface NullValue {
  kind: 'null';
  source: SourceLocation;
  leadingTrivia?: Trivia[];
  trailingTrivia?: Trivia[];
}

export interface IdValue {
  kind: 'id';
  path: string;
  parts: string[];
  source: SourceLocation;
  leadingTrivia?: Trivia[];
  trailingTrivia?: Trivia[];
}

export interface ListValue {
  kind: 'list';
  items: PropertyValue[];
  source: SourceLocation;
  leadingTrivia?: Trivia[];
  trailingTrivia?: Trivia[];
}

export interface ObjectValue {
  kind: 'object';
  entries: ObjectEntry[];
  source: SourceLocation;
  leadingTrivia?: Trivia[];
  trailingTrivia?: Trivia[];
}

export interface ObjectEntry {
  key: string;
  value: PropertyValue;
  source: SourceLocation;
  leadingTrivia?: Trivia[];
  trailingTrivia?: Trivia[];
}

export interface FunctionCallValue {
  kind: 'functionCall';
  name: string;
  args: PropertyValue[];
  source: SourceLocation;
  leadingTrivia?: Trivia[];
  trailingTrivia?: Trivia[];
}

export interface Reference {
  path: string;
  parts: string[];
  source: SourceLocation;
  leadingTrivia?: Trivia[];
  trailingTrivia?: Trivia[];
}

/**
 * `{ en: "...", cs: "..." }` — the one localised shape in the language. Used by
 * `label` / `displayLabel` / `value_labels` and, from grammar 0.13 (NLS-P10),
 * by `description:` as well.
 *
 * Where a definition carries `descriptionLocalized`, it is the map form of the
 * SAME property as `description`: exactly one of the two is ever set, because a
 * `.ttrm` author writes one form or the other. The parser does not fold the map
 * to a single locale — that selection is the consumer's (Veles serves
 * `ObjectDescriptor.description` through the D7 fallback chain).
 */
export interface LocalizedString {
  kind: 'localizedString';
  entries: Record<string, string>;
  source: SourceLocation;
  leadingTrivia?: Trivia[];
  trailingTrivia?: Trivia[];
}

export interface LocalizedStringList {
  kind: 'localizedStringList';
  entries: Record<string, string[]>;
  source: SourceLocation;
  leadingTrivia?: Trivia[];
  trailingTrivia?: Trivia[];
}

export type DataType = SimpleDataType | StructuredDataType;

export interface SimpleDataType {
  kind: 'simple';
  name: string;
  source: SourceLocation;
  leadingTrivia?: Trivia[];
  trailingTrivia?: Trivia[];
}

export interface StructuredDataType {
  kind: 'structured';
  typeName: string;
  length?: number;
  precision?: number;
  source: SourceLocation;
  leadingTrivia?: Trivia[];
  trailingTrivia?: Trivia[];
}

export type IndexType = 'primary' | 'secondary' | 'ordered' | 'btree' | 'fulltext';
export type ConstraintType = 'unique' | 'notNull';
export type QueryLanguage = 'SQL' | 'TRANSFORMATION_DSL' | 'DATAFRAME_DSL' | 'REL_NODE';
export type ParameterDirection = 'IN' | 'OUT' | 'INOUT';

/**
 * The match method carried by `searchable` (grammar 0.12, RV-32): `EXACT` |
 * `TYPOS(n)` | `TOKENS`. The parser stays mechanical — `name` is the identifier
 * exactly as authored and `argument` the raw numeric literal; the vocabulary,
 * the arity rule (only `TYPOS` takes an argument) and the distance range are
 * validated in `@tatrman/semantics` (the `allocation:` precedent).
 */
export interface MatchMethod {
  kind: 'matchMethod';
  name: string;
  argument?: number;
  source: SourceLocation;
}

export interface SearchBlock {
  kind: 'searchBlock';
  keywords?: LocalizedStringList;
  patterns?: string[];
  descriptions?: LocalizedStringList;
  examples?: string[];
  aliases?: string[];
  /**
   * Lexicon inclusion (RV-31 source (3)). `true` for bare `searchable` as well
   * as `searchable: true` — the bare form is the 0.12 inclusion marker.
   */
  searchable?: boolean;
  /** @deprecated grammar 0.12 (RV-32) — use `method` (`fuzzy: true` ≡ `method: TYPOS(1)`). */
  fuzzy?: boolean;
  method?: MatchMethod;
  duplicateProperties?: string[];
  source: SourceLocation;
  leadingTrivia?: Trivia[];
  trailingTrivia?: Trivia[];
}

/**
 * Grounding Phase 1 (grammar 4.2) — the free-form `semantics { … }` block. The
 * parser stays mechanical: entries are captured as raw key→value pairs (ids as
 * their identifier text, string literals unquoted, numbers/booleans as JS
 * primitives) with NO vocabulary or shape checking — that is ttr-semantics'
 * job (`@tatrman/semantics` `semantics-block/`). Lists and nested objects are
 * carried verbatim (MS, vocabulary v3: `measures: [amount_czk, { attribute:
 * quantity, aggregation: avg }]`); only a `functionCall` value, which has no data
 * meaning here, is still rejected into `ttr/semantics-non-scalar`.
 *
 * NB the block carries `source` (not `location`) like every other AST node, so
 * the trivia machinery (`cst/attach.ts`) recognises it and a leading comment on
 * the block survives.
 */
export interface SemanticsBlock {
  kind: 'semanticsBlock';
  /** Raw, unvalidated entries; last-wins on a duplicate key. */
  entries: Record<string, SemanticsValue>;
  /**
   * Keys that appeared more than once (the search-block bookkeeping pattern).
   *
   * Since MS this covers every depth: a top-level repeat is the bare key, a repeat inside
   * a nested object is the dotted/indexed path to it (`measures[1].attribute`). The
   * entries themselves stay last-wins at every depth — this array is the record that a
   * value was overwritten, which ttr-semantics turns into `SemDuplicateKey`.
   */
  duplicateProperties?: string[];
  source: SourceLocation;
  leadingTrivia?: Trivia[];
  trailingTrivia?: Trivia[];
}

/**
 * A semantics entry value — ids arrive as their identifier text (a string).
 *
 * MS (vocabulary v3) widened this from scalars only. The grammar's `value` rule has
 * always admitted `list` and `object_`; it was the walker that flattened them away, which
 * left the v3 `measures:` surface unrepresentable. Nesting is arbitrary here on purpose —
 * the parser has no vocabulary, so it cannot know how deep a given key is allowed to go.
 */
export type SemanticsValue =
  | string
  | number
  | boolean
  | null
  | ReadonlyArray<SemanticsValue>
  | { readonly [key: string]: SemanticsValue };

/**
 * PL-P4.S3 (grammar 0.11, H-1) — a document-level `security { … }` block:
 * declarative access sugar over model objects, consumed one-way by
 * `ttr-security-gen` → Rego. Structured (own/classify/grant/mask); object refs
 * are kept as opaque qname strings here (dotted ids stay verbatim) — resolution is
 * `@tatrman/semantics`' job, and advisory only (never a compile block). The block
 * is fingerprint-neutral. Mirrors the Kotlin `SecurityBlock` in `org.tatrman:ttr-parser`.
 */
export interface SecurityBlock {
  kind: 'securityBlock';
  statements: SecurityStatement[];
  source: SourceLocation;
  leadingTrivia?: Trivia[];
  trailingTrivia?: Trivia[];
}

export type SecurityStatement = OwnStatement | ClassifyStatement | GrantStatement | MaskStatement;

/** `own <object>: <owner-role>` — ownership declaration. */
export interface OwnStatement {
  verb: 'own';
  objectRef: string;
  owner: string;
  source: SourceLocation;
  leadingTrivia?: Trivia[];
  trailingTrivia?: Trivia[];
}

/** `classify <object>: <classification>` — classifications are the native grant vocabulary (HQ-1). */
export interface ClassifyStatement {
  verb: 'classify';
  objectRef: string;
  classification: string;
  source: SourceLocation;
  leadingTrivia?: Trivia[];
  trailingTrivia?: Trivia[];
}

/** `grant <privilege> on <object> to <role|classification>` — grants only. */
export interface GrantStatement {
  verb: 'grant';
  privilege: string;
  objectRef: string;
  grantee: string;
  source: SourceLocation;
  leadingTrivia?: Trivia[];
  trailingTrivia?: Trivia[];
}

/** `mask <object>` — column mask (default: for all; policy maps exceptions). */
export interface MaskStatement {
  verb: 'mask';
  objectRef: string;
  source: SourceLocation;
  leadingTrivia?: Trivia[];
  trailingTrivia?: Trivia[];
}

export interface ValueLabels {
  kind: 'valueLabels';
  /** v4.4 S2 (A4-β) — `aliases` per value: `"1": { label: {…}, aliases: ["živý"] }`. */
  entries: Array<{ key: string; label: LocalizedString; aliases?: string[]; source: SourceLocation }>;
  source: SourceLocation;
  leadingTrivia?: Trivia[];
  trailingTrivia?: Trivia[];
}

// ----- v2.1: inline bindings -----

export interface BindingPropertyBareId {
  kind: 'bareId';
  id: Reference;
  source: SourceLocation;
  leadingTrivia?: Trivia[];
  trailingTrivia?: Trivia[];
}

export interface BindingPropertyBlock {
  kind: 'block';
  target?: ObjectValue | Reference;
  columns?: BindingColumnEntry[];
  fk?: Reference;
  source: SourceLocation;
  leadingTrivia?: Trivia[];
  trailingTrivia?: Trivia[];
}

export type BindingProperty = BindingPropertyBareId | BindingPropertyBlock;

export interface BindingColumnEntry {
  name: string;
  value: BindingColumnValue;
  source: SourceLocation;
  leadingTrivia?: Trivia[];
  trailingTrivia?: Trivia[];
}

export type BindingColumnValue =
  | { kind: 'bareId'; id: Reference; source: SourceLocation }
  | { kind: 'object'; object: ObjectValue; source: SourceLocation };

export interface ParameterDef {
  name: string;
  type?: DataType;
  label?: string;
  direction?: ParameterDirection;
  source: SourceLocation;
  leadingTrivia?: Trivia[];
  trailingTrivia?: Trivia[];
}

// ============================================================================
// Model directive  (v4.0 — was the `schema <code> [namespace]` directive)
// ============================================================================

export interface ModelDirective {
  modelCode: string;
  /** The db/binding namespace binding id (v4.0 `model <code> schema <id>`). */
  schema?: string;
  /**
   * The unit-level locale (v4.4 `model lexicon locale <id>`). Grammar accepts it
   * on any model directive (permissive superset); locale-only-on-lexicon is
   * enforced in semantics.
   */
  locale?: string;
  source: SourceLocation;
  leadingTrivia?: Trivia[];
  trailingTrivia?: Trivia[];
}

/**
 * A canonical lexicon entry (v4.4 — RG-P4/RS-9): `def term|pattern|example <id>
 * { for: <target>, forms|match|text: … }`. One shared shape for all three kinds
 * (the grammar body is a permissive superset); per-kind required-field validity
 * (term needs `forms`, pattern needs `match`, example needs `text`) is enforced
 * in `@tatrman/semantics`, not the parser.
 */
export interface LexiconEntryDef {
  kind: 'term' | 'pattern' | 'example';
  name: string;
  source: SourceLocation;
  leadingTrivia?: Trivia[];
  trailingTrivia?: Trivia[];
  description?: StringValue | TripleStringValue;
  descriptionLocalized?: LocalizedString;
  tags?: string[];
  /** The `for:` target ref (er/db/md), span-carrying; resolved in semantics. */
  target?: Reference;
  /** `forms: [...]` — a term's surface forms. */
  forms?: string[];
  /** `match: "..."` — a pattern's regex. */
  match?: string;
  /** `text: "..."` — an example utterance. */
  text?: string;
}

/**
 * Inline `lexicon { … }` sugar (v4.4) attachable to data-bearing carriers
 * (table/column/entity/attribute AND measure/dimension/cubelet). Desugars in
 * `@tatrman/semantics` to canonical `term` entries targeting the enclosing def.
 * The parser stays mechanical: the free-form `object_` body is retained as
 * `object` for full fidelity, with `terms`/`patterns`/`examples` extracted for
 * the common case. Additive keys ride the raw object untouched.
 */
export interface LexiconBlock {
  kind: 'lexiconBlock';
  /** `terms: [...]` — surface forms desugaring to `term` entries. */
  terms?: string[];
  /** `patterns: [...]` — regexes desugaring to `pattern` entries. */
  patterns?: string[];
  /** `examples: [...]` — utterances desugaring to `example` entries. */
  examples?: string[];
  /** The raw block object (fidelity for additive keys the shorthands miss). */
  object: ObjectValue;
  source: SourceLocation;
  leadingTrivia?: Trivia[];
  trailingTrivia?: Trivia[];
}

// ============================================================================
// Per-kind definition types
// ============================================================================

export interface ProjectDef {
  kind: 'project';
  name: string;
  source: SourceLocation;
  leadingTrivia?: Trivia[];
  trailingTrivia?: Trivia[];
  description?: StringValue | TripleStringValue;
  descriptionLocalized?: LocalizedString;
  tags?: string[];
  version?: string;
}

export interface TableDef {
  kind: 'table';
  name: string;
  source: SourceLocation;
  leadingTrivia?: Trivia[];
  trailingTrivia?: Trivia[];
  description?: StringValue | TripleStringValue;
  descriptionLocalized?: LocalizedString;
  tags?: string[];
  primaryKey?: string[];
  columns?: ColumnDef[];
  indices?: IndexDef[];
  constraints?: ConstraintDef[];
  search?: SearchBlock;
  semantics?: SemanticsBlock;
  /** Inline `lexicon { … }` sugar (v4.4) — desugars to canonical `term` entries. */
  lexicon?: LexiconBlock;
  /** EN-P1 (0.10) — `management: data|canon`; absent ⇒ default `data` (applied in semantics). */
  management?: string;
  /** EN-P1 (0.10) — `changeSemantics: <mode> { <role>: <column> }`; absent ⇒ optimistic (§10). */
  changeSemantics?: ChangeSemanticsDecl;
  /** EN-P1 (0.10) — Q-8 `writeback { … }` reservation; parses inert (rung-v3 rides FO v1.1+). */
  writeback?: WritebackReservation;
}

/**
 * EN-P1 (0.10) — a `changeSemantics: <mode> { <role>: <column>, … }` declaration on a table
 * (write-behaviour axis, FO §9). `mode` + the role→column map stay opaque here; vocabulary
 * (scd1|scd2|ledger) + role legality are validated in ttr-semantics (parser stays mechanical).
 * Mirrors the Kotlin `ChangeSemanticsDecl`.
 */
export interface ChangeSemanticsDecl {
  mode: string;
  /** Declared role → column-id (e.g. `validFrom → valid_from`, `reversalLink → reversal_of`). */
  roles: Record<string, string>;
  source: SourceLocation;
}

/**
 * EN-P1 (0.10) — the Q-8 `writeback { … }` reservation (demand §4). A structured no-op: it parses
 * (free-form scalar body, the semantics-block shape) but carries no meaning this wave. Mirrors the
 * Kotlin `WritebackReservation`.
 */
export interface WritebackReservation {
  entries: Record<string, SemanticsValue>;
  source: SourceLocation;
}

export interface ViewDef {
  kind: 'view';
  name: string;
  source: SourceLocation;
  leadingTrivia?: Trivia[];
  trailingTrivia?: Trivia[];
  description?: StringValue | TripleStringValue;
  descriptionLocalized?: LocalizedString;
  tags?: string[];
  columns?: ColumnDef[];
  definitionSql?: StringValue | TripleStringValue | TaggedBlockValue;
  search?: SearchBlock;
}

/**
 * Grammar 0.14 — a named row schema (`def schema <name> { columns: [...] }`): an ordered column list,
 * each column written exactly like a table column. Tier-neutral; consumed by TTR-P (`display(<name>)`
 * rows are held to it). Not a loadable object.
 */
export interface SchemaDef {
  kind: 'schema';
  name: string;
  source: SourceLocation;
  leadingTrivia?: Trivia[];
  trailingTrivia?: Trivia[];
  description?: StringValue | TripleStringValue;
  descriptionLocalized?: LocalizedString;
  tags?: string[];
  columns?: ColumnDef[];
}

export interface ColumnDef {
  kind: 'column';
  name: string;
  source: SourceLocation;
  leadingTrivia?: Trivia[];
  trailingTrivia?: Trivia[];
  description?: StringValue | TripleStringValue;
  descriptionLocalized?: LocalizedString;
  tags?: string[];
  type?: DataType;
  optional?: boolean;
  isKey?: boolean;
  indexed?: boolean;
  search?: SearchBlock;
  semantics?: SemanticsBlock;
  /** Inline `lexicon { … }` sugar (v4.4) — desugars to canonical `term` entries. */
  lexicon?: LexiconBlock;
}

export interface IndexDef {
  kind: 'index';
  name: string;
  source: SourceLocation;
  leadingTrivia?: Trivia[];
  trailingTrivia?: Trivia[];
  description?: StringValue | TripleStringValue;
  descriptionLocalized?: LocalizedString;
  indexType?: IndexType;
  columns?: string[];
}

export interface ConstraintDef {
  kind: 'constraint';
  name: string;
  source: SourceLocation;
  leadingTrivia?: Trivia[];
  trailingTrivia?: Trivia[];
  description?: StringValue | TripleStringValue;
  descriptionLocalized?: LocalizedString;
  constraintType?: ConstraintType;
  columns?: string[];
}

export interface FkDef {
  kind: 'fk';
  name: string;
  source: SourceLocation;
  leadingTrivia?: Trivia[];
  trailingTrivia?: Trivia[];
  description?: StringValue | TripleStringValue;
  descriptionLocalized?: LocalizedString;
  tags?: string[];
  from?: PropertyValue;
  to?: PropertyValue;
}

export interface ProcedureDef {
  kind: 'procedure';
  name: string;
  source: SourceLocation;
  leadingTrivia?: Trivia[];
  trailingTrivia?: Trivia[];
  description?: StringValue | TripleStringValue;
  descriptionLocalized?: LocalizedString;
  tags?: string[];
  parameters?: ParameterDef[];
  resultColumns?: ColumnDef[];
}

export interface EntityDef {
  kind: 'entity';
  name: string;
  source: SourceLocation;
  leadingTrivia?: Trivia[];
  trailingTrivia?: Trivia[];
  description?: StringValue | TripleStringValue;
  descriptionLocalized?: LocalizedString;
  tags?: string[];
  labelPlural?: string;
  nameAttribute?: Reference;
  codeAttribute?: Reference;
  aliases?: string[];
  attributes?: AttributeDef[];
  roles?: string[];
  displayLabel?: LocalizedString;
  search?: SearchBlock;
  semantics?: SemanticsBlock;
  /** Inline `lexicon { … }` sugar (v4.4) — desugars to canonical `term` entries. */
  lexicon?: LexiconBlock;
  binding?: BindingProperty;
}

export interface AttributeDef {
  kind: 'attribute';
  name: string;
  source: SourceLocation;
  leadingTrivia?: Trivia[];
  trailingTrivia?: Trivia[];
  description?: StringValue | TripleStringValue;
  descriptionLocalized?: LocalizedString;
  tags?: string[];
  type?: DataType;
  isKey?: boolean;
  optional?: boolean;
  valueLabels?: ValueLabels;
  displayLabel?: LocalizedString;
  search?: SearchBlock;
  semantics?: SemanticsBlock;
  /** Inline `lexicon { … }` sugar (v4.4) — desugars to canonical `term` entries. */
  lexicon?: LexiconBlock;
  binding?: BindingProperty;
  // v3.1 MD — the shared attribute body also serves `schema md` dimensions.
  // Both shapes are accepted by the grammar; per-schema validity (md requires
  // `domain:` & forbids `type:`; er the reverse) is enforced in semantics.
  /** MD — `domain:` ref the attribute ranges over (opaque string, resolved in semantics). */
  domainRef?: string;
  /** MD — roll-up aggregation in a hierarchy (e.g. latest-valid address). */
  aggregation?: AggregationSpec;
  /** MD — span-carrying view of `domainRef` (editor-only). */
  crossRefs?: CrossRef[];
}

export interface RelationDef {
  kind: 'relation';
  name: string;
  source: SourceLocation;
  leadingTrivia?: Trivia[];
  trailingTrivia?: Trivia[];
  description?: StringValue | TripleStringValue;
  descriptionLocalized?: LocalizedString;
  tags?: string[];
  from?: PropertyValue;
  to?: PropertyValue;
  cardinality?: ObjectValue;
  join?: ListValue;
  search?: SearchBlock;
  binding?: BindingProperty;
}

export interface Er2dbEntityDef {
  kind: 'er2dbEntity';
  name: string;
  source: SourceLocation;
  leadingTrivia?: Trivia[];
  trailingTrivia?: Trivia[];
  description?: StringValue | TripleStringValue;
  descriptionLocalized?: LocalizedString;
  tags?: string[];
  entity?: Reference;
  target?: ObjectValue | Reference;
  whereFilter?: ObjectValue;
}

export interface Er2dbAttributeDef {
  kind: 'er2dbAttribute';
  name: string;
  source: SourceLocation;
  leadingTrivia?: Trivia[];
  trailingTrivia?: Trivia[];
  description?: StringValue | TripleStringValue;
  descriptionLocalized?: LocalizedString;
  tags?: string[];
  attribute?: Reference;
  target?: ObjectValue | Reference;
}

export interface Er2dbRelationDef {
  kind: 'er2dbRelation';
  name: string;
  source: SourceLocation;
  leadingTrivia?: Trivia[];
  trailingTrivia?: Trivia[];
  description?: StringValue | TripleStringValue;
  descriptionLocalized?: LocalizedString;
  tags?: string[];
  relation?: Reference;
  fk?: Reference;
}

export interface QueryDef {
  kind: 'query';
  name: string;
  source: SourceLocation;
  leadingTrivia?: Trivia[];
  trailingTrivia?: Trivia[];
  description?: StringValue | TripleStringValue;
  descriptionLocalized?: LocalizedString;
  tags?: string[];
  language?: QueryLanguage;
  parameters?: ParameterDef[];
  sourceText?: StringValue | TripleStringValue | TaggedBlockValue;
  search?: SearchBlock;
}

export interface RoleDef {
  kind: 'role';
  name: string;
  source: SourceLocation;
  leadingTrivia?: Trivia[];
  trailingTrivia?: Trivia[];
  description?: StringValue | TripleStringValue;
  descriptionLocalized?: LocalizedString;
  tags?: string[];
  label?: LocalizedString;
  search?: SearchBlock;
}

export interface Er2cncRoleDef {
  kind: 'er2cncRole';
  name: string;
  source: SourceLocation;
  leadingTrivia?: Trivia[];
  trailingTrivia?: Trivia[];
  description?: StringValue | TripleStringValue;
  descriptionLocalized?: LocalizedString;
  tags?: string[];
  entity?: Reference;
  role?: Reference;
}

/**
 * v2.2 — `def drill_map <id> { from, to, args, display?, override? }`.
 *
 * `from` / `to` reference existing `def query` patterns (cross-package allowed).
 * `args` maps target-parameter names (declared on the `to` pattern) to column
 * names in `from`'s result projection or string literals — values are kept as
 * `StringValue`s here; ai-platform decides whether each is a column or literal.
 * `display` is a localised chip label; the loader supplies a default when absent.
 * `overrideAuto` suppresses auto-derived drills with the same target.
 *
 * One-to-one for v1: one declaration per (from, to) pair. Multiple drill
 * targets on one source pattern → multiple `def drill_map` blocks.
 */
export interface DrillMapDef {
  kind: 'drillMap';
  name: string;
  source: SourceLocation;
  leadingTrivia?: Trivia[];
  trailingTrivia?: Trivia[];
  description?: StringValue | TripleStringValue;
  descriptionLocalized?: LocalizedString;
  tags?: string[];
  from?: Reference;
  to?: Reference;
  args: DrillArgEntry[];
  display?: LocalizedString;
  overrideAuto?: boolean;
}

export interface DrillArgEntry {
  name: string;
  value: StringValue | TripleStringValue;
  source: SourceLocation;
  leadingTrivia?: Trivia[];
  trailingTrivia?: Trivia[];
}

// ============================================================================
// v3.1 — MD (multidimensional) logical objects (schema md). Contracts §2.
// Cross-references are opaque strings at the parser layer (resolved in semantics).
// ============================================================================

/**
 * A span-carrying view of one MD cross-reference, parallel to the opaque-string
 * ref fields (the contracts §2 semantic surface). Editor-only convenience — like
 * {@link AreaDef.packageSources} — populated by the walker so semantics can emit
 * positioned `md/unknown-ref` and the LSP can navigate. `role` selects the target
 * namespace during resolution (contracts §5).
 */
export interface CrossRef {
  role: 'domain' | 'map' | 'dimension' | 'measure' | 'hierarchy' | 'grain';
  path: string;
  source: SourceLocation;
}

export interface MdDomainDef {
  kind: 'mdDomain';
  name: string;
  source: SourceLocation;
  leadingTrivia?: Trivia[];
  trailingTrivia?: Trivia[];
  description?: StringValue | TripleStringValue;
  descriptionLocalized?: LocalizedString;
  tags?: string[];
  type?: DataType; // reuses the existing DataType
  /** Grammar accepts any id; the `calc`/`bound` value-set is validated in semantics. */
  domainKind?: string;
  restrict?: RestrictClause[];
  /**
   * MD dot-path arc (contracts §1.4): `publish: members` opts the domain into the member
   * catalog (§7). Default (clause absent) = not published. Filled by the walker from the
   * `publishClause` production once S0-B lands the grammar.
   */
  publishMembers?: boolean;
}

export interface RestrictClause {
  /** Open set: 'range' | 'members' | 'pattern' | 'length' | … (validated in semantics). */
  clause: string;
  value: RangeLiteral | DomainMember[] | PropertyValue;
  source: SourceLocation;
}

export interface RangeLiteral {
  kind: 'rangeLiteral';
  lo: number;
  hi: number;
  source: SourceLocation;
}

export interface DomainMember {
  key: string;
  labels: LocalizedString;
  source: SourceLocation;
}

export interface DimensionDef {
  kind: 'dimension';
  name: string;
  source: SourceLocation;
  leadingTrivia?: Trivia[];
  trailingTrivia?: Trivia[];
  description?: StringValue | TripleStringValue;
  descriptionLocalized?: LocalizedString;
  tags?: string[];
  /** Attribute name naming member identity; required-in-semantics. */
  key?: string;
  /** Inline `def attribute` list (shared AttributeDef node). */
  attributes: AttributeDef[];
  /** References to HierarchyDef names. */
  hierarchies?: string[];
  /** Span-carrying view of `hierarchies` (editor-only). */
  crossRefs?: CrossRef[];
  /** Inline `lexicon { … }` sugar (v4.4) — desugars to canonical `term` entries. */
  lexicon?: LexiconBlock;
}

export interface MdMapDef {
  kind: 'mdMap';
  name: string;
  source: SourceLocation;
  leadingTrivia?: Trivia[];
  trailingTrivia?: Trivia[];
  description?: StringValue | TripleStringValue;
  descriptionLocalized?: LocalizedString;
  tags?: string[];
  /** 1..n opaque domain refs. */
  from: string[];
  /** 1..n opaque domain refs (usually one). */
  to: string[];
  /** Normalised from the grammar's `cardinality` object; default N:1 (semantics). */
  cardinality?: '1:1' | 'N:1';
  /** Absent ⇒ table-backed (case-table supplied by md2db_map). */
  calc?: CalcRef;
  /** Span-carrying view of `from`/`to` domain refs (editor-only). */
  crossRefs?: CrossRef[];
}

export interface CalcRef {
  kind: 'calcRef';
  name: string;
  args: CalcArg[];
  source: SourceLocation;
}

export interface CalcArg {
  name: string;
  value: PropertyValue;
  source: SourceLocation;
}

export interface HierarchyDef {
  kind: 'hierarchy';
  name: string;
  source: SourceLocation;
  leadingTrivia?: Trivia[];
  trailingTrivia?: Trivia[];
  description?: StringValue | TripleStringValue;
  descriptionLocalized?: LocalizedString;
  tags?: string[];
  dimensionRef?: string; // opaque
  /** Leaf→root order PRESERVED. */
  levels: HierarchyLevel[];
  /** Span-carrying view of `dimensionRef` and per-level `via` map refs (editor-only). */
  crossRefs?: CrossRef[];
}

export interface HierarchyLevel {
  attribute: string;
  /** Optional `via <mapRef>` pinning the connecting map (opaque). */
  via?: string;
  source: SourceLocation;
}

export interface MeasureDef {
  kind: 'measure';
  name: string;
  source: SourceLocation;
  leadingTrivia?: Trivia[];
  trailingTrivia?: Trivia[];
  description?: StringValue | TripleStringValue;
  descriptionLocalized?: LocalizedString;
  tags?: string[];
  domainRef?: string; // opaque
  /** Grammar accepts any id; the value-set is validated in semantics. */
  measureClass?: string;
  aggregation?: AggregationSpec;
  validBy?: string; // attribute name
  /** Span-carrying view of `domainRef` (editor-only). */
  crossRefs?: CrossRef[];
  /** Inline `lexicon { … }` sugar (v4.4) — desugars to canonical `term` entries. */
  lexicon?: LexiconBlock;
}

export interface AggregationSpec {
  /** e.g. 'sum' | 'max' | 'latestValid' | … */
  default?: string;
  /** Per-dimension overrides, e.g. { time: 'latestValid' }. */
  perDimension?: Record<string, string>;
  source: SourceLocation;
}

export interface CubeletDef {
  kind: 'cubelet';
  name: string;
  source: SourceLocation;
  leadingTrivia?: Trivia[];
  trailingTrivia?: Trivia[];
  description?: StringValue | TripleStringValue;
  descriptionLocalized?: LocalizedString;
  tags?: string[];
  /** Dotted `Dimension.attribute` opaque refs. */
  grain: string[];
  /** Measure refs or inline defs. */
  measures: (string | MeasureDef)[];
  /** Span-carrying view of grain refs + string measure refs (editor-only). */
  crossRefs?: CrossRef[];
  /** Inline `lexicon { … }` sugar (v4.4) — desugars to canonical `term` entries. */
  lexicon?: LexiconBlock;
}

// ============================================================================
// v3.1 — MD binding objects (schema binding). Contracts §2 / §4. References are
// opaque strings; shape/journaling discriminated unions are normalised in the
// walker, validated in semantics (Phase 3).
// ============================================================================

export type ShapeSpec =
  | { shape: 'wide' }
  | { shape: 'long'; codeColumn: string; valueColumn: string };

export type AttrColumnBinding =
  | { column: string }
  /** Map-mediated grain column (design §6.1). */
  | { via: string; from: { table: string; column: string } };

export type MeasureColumnBinding = { column: string } | { code: string }; // wide | long

export type JournalingSpec =
  | { mode: 'overwrite' }
  | { mode: 'diff' }
  | { mode: 'invalidate'; validColumn: string };

/**
 * Writeback spread strategy (v0.10, contracts R21). A bare id applies one strategy
 * to every spread dimension (`allocation: proportional`); the object form maps
 * dimension → strategy (`allocation: { time: equal }`). The strategy VALUE
 * (`equal`/`proportional`) is validated in semantics, not the parser.
 */
export type AllocationSpec =
  | { uniform: string }
  | { byDimension: Record<string, string> };

export interface Md2DbCubeletDef {
  kind: 'md2dbCubelet';
  name: string;
  source: SourceLocation;
  leadingTrivia?: Trivia[];
  trailingTrivia?: Trivia[];
  description?: StringValue | TripleStringValue;
  descriptionLocalized?: LocalizedString;
  tags?: string[];
  cubeletRef: string;
  table: string; // target fact-table ref
  shape: ShapeSpec;
  attributes: Record<string, AttrColumnBinding>;
  measures: Record<string, MeasureColumnBinding>;
  journaling?: JournalingSpec;
  /** Writeback spread strategy (v0.10, R21) — absent when the binding declares none. */
  allocation?: AllocationSpec;
}

export interface Md2DbDomainDef {
  kind: 'md2dbDomain';
  name: string;
  source: SourceLocation;
  leadingTrivia?: Trivia[];
  trailingTrivia?: Trivia[];
  description?: StringValue | TripleStringValue;
  descriptionLocalized?: LocalizedString;
  tags?: string[];
  domainRef: string;
  source_: { table: string; column: string };
}

export interface Md2DbMapDef {
  kind: 'md2dbMap';
  name: string;
  source: SourceLocation;
  leadingTrivia?: Trivia[];
  trailingTrivia?: Trivia[];
  description?: StringValue | TripleStringValue;
  descriptionLocalized?: LocalizedString;
  tags?: string[];
  mapRef: string;
  table: string;
  /** from/to domain → case-table column. */
  columns: Record<string, string>;
}

export interface Md2ErCubeletDef {
  kind: 'md2erCubelet';
  name: string;
  source: SourceLocation;
  leadingTrivia?: Trivia[];
  trailingTrivia?: Trivia[];
  description?: StringValue | TripleStringValue;
  descriptionLocalized?: LocalizedString;
  tags?: string[];
  cubeletRef: string;
  entity: string; // target ER entity
  /** attribute → ER attribute (structural only). */
  attributes: Record<string, string>;
  /**
   * Physical props that must NOT appear on a structural md→er binding. The
   * grammar accepts them (permissive superset); semantics rejects via
   * `md/md2er-physical-prop`. Lists the offending keys ('shape'|'measures'|
   * 'journaling'|'allocation') when present; absent when the binding is clean.
   */
  physicalProps?: string[];
}

export type Definition =
  | ProjectDef
  | TableDef
  | ViewDef
  | ColumnDef
  | IndexDef
  | ConstraintDef
  | FkDef
  | ProcedureDef
  | EntityDef
  | AttributeDef
  | RelationDef
  | Er2dbEntityDef
  | Er2dbAttributeDef
  | Er2dbRelationDef
  | QueryDef
  | RoleDef
  | Er2cncRoleDef
  | DrillMapDef
  | AreaDef
  | SchemaDef
  | MdDomainDef
  | DimensionDef
  | MdMapDef
  | HierarchyDef
  | MeasureDef
  | CubeletDef
  | Md2DbCubeletDef
  | Md2DbDomainDef
  | Md2DbMapDef
  | Md2ErCubeletDef
  | WorldDef
  | LexiconEntryDef;

// ============================================================================
// Document / parse result
// ============================================================================

export interface PackageDecl {
  kind: 'packageDecl';
  name: string;
  parts: string[];
  source: SourceLocation;
  leadingTrivia?: Trivia[];
  trailingTrivia?: Trivia[];
}

export interface ImportDecl {
  kind: 'importDecl';
  target: string;
  targetParts: string[];
  wildcard: boolean;
  source: SourceLocation;
  leadingTrivia?: Trivia[];
  trailingTrivia?: Trivia[];
}

export interface GraphLayout {
  viewport?: {
    zoom: number;
    panX: number;
    panY: number;
    displayMode: string; // Validated against the DisplayMode union in §11.2 by the semantics layer.
  };
  nodes: Record<string, { x: number; y: number }>;
  edges: Record<string, { bendPoints?: [number, number][] }>;
}

export interface GraphBlock {
  kind: 'graphBlock';
  name: string;
  schema?: 'db' | 'er' | 'binding' | 'query' | 'cnc';
  description?: string;
  tags?: string[];
  objects: string[];
  layout?: GraphLayout;
  source: SourceLocation;
  leadingTrivia?: Trivia[];
  trailingTrivia?: Trivia[];
}

/**
 * Subject area (v3.0 — `def area <id> { … }`, replacing the v2.3 `.ttrd` domain
 * block). A normal definition that lives in ordinary model files and registers a
 * resolvable symbol. Drives the resolved-packages `domains` artifact (recursive
 * package closure + entity set).
 */
export interface AreaDef {
  kind: 'area';
  name: string;
  description?: StringValue | TripleStringValue;
  descriptionLocalized?: LocalizedString;
  tags?: string[];
  /** Recursive members: each pulls the package and all descendants. May be empty. */
  packages: string[];
  /** Individual entity qnames loaded in addition to whole packages. May be empty. */
  entities: string[];
  /**
   * Per-member source locations, parallel to `packages` / `entities` (editor-only;
   * for go-to-def / find-refs). Contracts carry only the string members; these
   * locations are an additive editor convenience.
   */
  packageSources?: SourceLocation[];
  entitySources?: SourceLocation[];
  source: SourceLocation;
  leadingTrivia?: Trivia[];
  trailingTrivia?: Trivia[];
}

// ============================================================================
// v4.1 — world model (schema code `world`, ttr-metadata M0; D-d-α). The world is
// the ONLY top-level def kind; engine/executor/storage/world-schema are nested.
// Manifest entries are transported opaque (T6 β data — MD5: never interpreted at
// the parser/semantics layer; TTR-P Stage 2.2 owns the format).
// ============================================================================

/** `def world <id> { … }` — a deployment world (D-d-α). */
export interface WorldDef {
  kind: 'world';
  name: string;
  source: SourceLocation;
  leadingTrivia?: Trivia[];
  trailingTrivia?: Trivia[];
  description?: StringValue | TripleStringValue;
  descriptionLocalized?: LocalizedString;
  tags?: string[];
  /** Optional world-level type overlay ref (grammar-permissive; usually unused). */
  extends?: string;
  engines: EngineDef[];
  executors: ExecutorDef[];
  storages: StorageDef[];
}

/** `def engine <id> { … }` inside a world (D-d-α). */
export interface EngineDef {
  kind: 'engine';
  name: string;
  source: SourceLocation;
  leadingTrivia?: Trivia[];
  trailingTrivia?: Trivia[];
  description?: StringValue | TripleStringValue;
  descriptionLocalized?: LocalizedString;
  tags?: string[];
  /** `type:` discriminator (raw dataType text, e.g. `postgres`). */
  type?: string;
  /** `version:` string literal. */
  version?: string;
  /** `extends:` type-manifest ref (dotted id; resolved in M2). */
  extends?: string;
  /** Free-form manifest entries (T6 β data — transported opaque, MD5). */
  manifest: Record<string, PropertyValue>;
}

/** `def executor <id> { … }` inside a world (same body as engine). */
export interface ExecutorDef {
  kind: 'executor';
  name: string;
  source: SourceLocation;
  leadingTrivia?: Trivia[];
  trailingTrivia?: Trivia[];
  description?: StringValue | TripleStringValue;
  descriptionLocalized?: LocalizedString;
  tags?: string[];
  type?: string;
  version?: string;
  extends?: string;
  manifest: Record<string, PropertyValue>;
}

/** `def storage <id> { … }` inside a world (D-d-α/D-d-i/D-f). */
export interface StorageDef {
  kind: 'storage';
  name: string;
  source: SourceLocation;
  leadingTrivia?: Trivia[];
  trailingTrivia?: Trivia[];
  description?: StringValue | TripleStringValue;
  descriptionLocalized?: LocalizedString;
  tags?: string[];
  type?: string;
  extends?: string;
  /** `via:` engine this storage is reached through. */
  via?: string;
  /** `hosts: [pkg]` — model packages this storage serves (D-d-i). */
  hosts: string[];
  /** `staging: true` — exactly-one enforced in semantics/WorldResolver (D-f). */
  staging?: boolean;
  /** Named `def schema` declarations local to this storage (D-c world home). */
  schemas: WorldSchemaDef[];
  manifest: Record<string, PropertyValue>;
}

/** `def schema <id> { field: type, … }` nested in a storage (D-c world home). */
export interface WorldSchemaDef {
  kind: 'worldSchema';
  name: string;
  source: SourceLocation;
  leadingTrivia?: Trivia[];
  trailingTrivia?: Trivia[];
  fields: WorldSchemaField[];
}

export interface WorldSchemaField {
  name: string;
  /** Raw dataType text (e.g. `string`, `decimal`). */
  type: string;
  source: SourceLocation;
}

export interface Document {
  packageDecl?: PackageDecl;
  imports: ImportDecl[];
  modelDirective?: ModelDirective;
  graph?: GraphBlock;
  definitions: Definition[];
  /** PL-P4.S3 — document-level `security { … }` blocks in file order. */
  securityBlocks: SecurityBlock[];
  source: SourceLocation;
  leadingTrivia?: Trivia[];
  trailingTrivia?: Trivia[];
}

export interface ParseError {
  code?: string;
  message: string;
  severity: 'error' | 'warning' | 'info';
  source: SourceLocation;
  leadingTrivia?: Trivia[];
  trailingTrivia?: Trivia[];
}

export interface ParseResult {
  ast?: Document;
  errors: ParseError[];
  sourceFile: string;
}