// SPDX-License-Identifier: Apache-2.0
// Grounding Phase 1 (grammar 4.2) — the closed `semantics { … }` vocabulary.
//
// This table is NORMATIVE and mirrors `docs/features/semantics-block/README.md`
// §Vocabulary exactly. It is the cross-repo sync key with ai-platform's closed
// proto enums (`com.tatrman.metadata.v1` AttributeSemantics / EntitySemantics,
// feature-grounding-contracts.md §4): the vocabulary here and the proto enums
// version TOGETHER via SEMANTICS_VOCABULARY_VERSION (the md-catalog
// MD_CATALOG_VERSION precedent). Bump it whenever a role/kind is added or a
// signature changes, and cut the matching proto release in lock-step.

/** Cross-repo sync key — bumps in lock-step with ai-platform's proto enums. */
// v2 (MD dot-path S5C-B.4) adds the journal-role family (valid_flag, version,
// authored_by, written_at) — contracts §12 R30; valid_from/valid_to were already
// present and are reused.
//
// v3 (MS — mention semantics) adds the MENTION facet. Everything the table held
// until now answers "what computation grounds on this column" (a date to filter on,
// a coordinate to measure from); v3 adds the orthogonal question "how do humans
// refer to this entity" — as a name, a code, or a value. That facet is declared
// ENTITY-side (`semantics { name: · code: · measures: [...] }`, contracts §1.1), not
// as a role: `role:` is single-valued, and one column is routinely both an amount to
// convert and the measure people ask for. So the role table gains no members — it
// gains `facet`/`family` metadata, which is what forces a future role to say which
// question it answers. Per-measure aggregation rides the `measures:` list item and is
// NOT the def-level `aggregation:` property (EN-P1.2 derived attributes) nor md's
// measure `aggregation:` — three different surfaces, deliberately kept apart.
//
// What v3 spans, end to end: the three entity mention keys (vocabulary + validator), the
// resolved `MeasureRef` shape, the `MentionKinds` derivation table (mention-kinds.ts, with
// the producing twin in Kotlin), and the lexicon archive's per-ref `targets` map that
// carries its output to the resolver (`ttr-lexicon-compiled/v3`).
//
// Kept in lock-step with the Kotlin twin (Vocabulary.kt), which VocabularyParitySpec asserts
// mechanically; the `meta.v1` proto follows in MS-P2 (additive: EntitySemantics.measures,
// AttributeSemantics.aggregation).
//
// LP review-103 (D4) adds one more MENTION key, `code_pattern:` — a regex the code
// attribute's values match — WITHOUT a version bump: the number moves with the closed
// proto ENUMS, and a free-text key adds no enum member. The lexicon archive's
// `TargetFacts.codeFormat` is its only channel downstream; `meta.v1` does not carry it.
export const SEMANTICS_VOCABULARY_VERSION = 3 as const;

/** The type-family a role's attribute/column must declare. */
export type TypeConstraint = 'date' | 'text' | 'numeric';

/** Entity/table kinds (`kind:`). */
export const ENTITY_KINDS = ['period_table', 'calendar', 'poi', 'fx_rate'] as const;
export type EntityKind = (typeof ENTITY_KINDS)[number];

/**
 * Which question a role answers. Only `grounding` exists today — the mention facet is
 * declared entity-side, not as a role (see the v3 note above). The column exists so that
 * a role added later must state its facet rather than inherit one by default.
 */
export type RoleFacet = 'grounding';

/** The family a grounding role belongs to — README §Vocabulary groups the table this way. */
export type RoleFamily = 'dates' | 'geo' | 'finance' | 'journal';

/** Cross-reference keys a role may carry beyond `role:` itself. */
export interface RoleSpec {
  /** Optional extra keys (besides `role`) this role accepts, with their kind. */
  readonly extraKeys: ReadonlyArray<{ key: string; kind: 'entityRef' | 'attrRef' | 'string'; required: boolean }>;
  /** The declared-type family the attribute/column must have (undefined = any). */
  readonly typeConstraint?: TypeConstraint;
  /** Required, not optional: a new role has to declare which question it answers. */
  readonly facet: RoleFacet;
  /** Required, for the same reason as `facet`. */
  readonly family: RoleFamily;
}

/**
 * Attribute/column roles (`role:`) → their extra keys + type constraint. Mirrors
 * README §Vocabulary's role table 1:1.
 */
export const ATTRIBUTE_ROLES: Readonly<Record<string, RoleSpec>> = {
  period_start: { extraKeys: [], typeConstraint: 'date', facet: 'grounding', family: 'dates' },
  period_end: { extraKeys: [], typeConstraint: 'date', facet: 'grounding', family: 'dates' },
  period_code: {
    extraKeys: [{ key: 'code_format', kind: 'string', required: false }],
    typeConstraint: 'text',
    facet: 'grounding',
    family: 'dates',
  },
  event_date: {
    extraKeys: [{ key: 'period', kind: 'entityRef', required: false }],
    typeConstraint: 'date',
    facet: 'grounding',
    family: 'dates',
  },
  document_date: {
    extraKeys: [{ key: 'period', kind: 'entityRef', required: false }],
    typeConstraint: 'date',
    facet: 'grounding',
    family: 'dates',
  },
  posting_date: {
    extraKeys: [{ key: 'period', kind: 'entityRef', required: false }],
    typeConstraint: 'date',
    facet: 'grounding',
    family: 'dates',
  },
  due_date: {
    extraKeys: [{ key: 'period', kind: 'entityRef', required: false }],
    typeConstraint: 'date',
    facet: 'grounding',
    family: 'dates',
  },
  valid_from: { extraKeys: [], typeConstraint: 'date', facet: 'grounding', family: 'dates' },
  valid_to: { extraKeys: [], typeConstraint: 'date', facet: 'grounding', family: 'dates' },
  // Journal-role family (S5C-B.4, contracts §12 R30): technical columns of a journaled cubelet's
  // backing table. valid_flag is boolean (no numeric/text/date family — left unconstrained).
  valid_flag: { extraKeys: [], facet: 'grounding', family: 'journal' },
  version: { extraKeys: [], typeConstraint: 'numeric', facet: 'grounding', family: 'journal' },
  authored_by: { extraKeys: [], typeConstraint: 'text', facet: 'grounding', family: 'journal' },
  written_at: { extraKeys: [], typeConstraint: 'date', facet: 'grounding', family: 'journal' },
  calendar_date: { extraKeys: [], typeConstraint: 'date', facet: 'grounding', family: 'dates' },
  geo_lat: { extraKeys: [], typeConstraint: 'numeric', facet: 'grounding', family: 'geo' },
  geo_lon: { extraKeys: [], typeConstraint: 'numeric', facet: 'grounding', family: 'geo' },
  geo_point: { extraKeys: [], typeConstraint: 'text', facet: 'grounding', family: 'geo' },
  amount: {
    extraKeys: [{ key: 'currency', kind: 'attrRef', required: false }],
    typeConstraint: 'numeric',
    facet: 'grounding',
    family: 'finance',
  },
  amount_domestic: { extraKeys: [], typeConstraint: 'numeric', facet: 'grounding', family: 'finance' },
  currency_code: { extraKeys: [], typeConstraint: 'text', facet: 'grounding', family: 'finance' },
  fx_from_currency: { extraKeys: [], typeConstraint: 'text', facet: 'grounding', family: 'finance' },
  fx_to_currency: { extraKeys: [], typeConstraint: 'text', facet: 'grounding', family: 'finance' },
  fx_rate: { extraKeys: [], typeConstraint: 'numeric', facet: 'grounding', family: 'finance' },
} as const;

export type AttributeRole = keyof typeof ATTRIBUTE_ROLES;

/**
 * A single required-role clause in a kind's completeness rule: the role must
 * appear exactly `count` times among the owner's attributes/columns.
 */
export interface CompletenessClause {
  readonly role: AttributeRole;
  readonly count: 1;
}

/**
 * Per-kind completeness rules (validated on the owning entity/table). `poi` is
 * special (geo_point XOR lat/lon pair) — handled in the validator, not as a flat
 * clause list — so it maps to an empty list here and is documented as such.
 */
export const KIND_COMPLETENESS: Readonly<Record<EntityKind, ReadonlyArray<CompletenessClause>>> = {
  period_table: [
    { role: 'period_start', count: 1 },
    { role: 'period_end', count: 1 },
    { role: 'period_code', count: 1 },
  ],
  calendar: [{ role: 'calendar_date', count: 1 }],
  // poi: exactly one geo_point XOR (one geo_lat AND one geo_lon) — the XOR is not
  // expressible as a flat count list, so the validator special-cases it (210).
  poi: [],
  fx_rate: [
    { role: 'fx_from_currency', count: 1 },
    { role: 'fx_to_currency', count: 1 },
    { role: 'fx_rate', count: 1 },
    // valid_from/valid_to are optional as a pair (211), not required — so absent here.
  ],
} as const;

export const ALL_ROLES: ReadonlyArray<string> = Object.keys(ATTRIBUTE_ROLES);

/** The keys legal at all: `role` plus every role's extra keys, deduped. */
export const ALL_ATTRIBUTE_KEYS: ReadonlyArray<string> = [
  'role',
  ...new Set(Object.values(ATTRIBUTE_ROLES).flatMap((r) => r.extraKeys.map((k) => k.key))),
];

/**
 * The keys legal on an entity/table `semantics` block.
 *
 * `kind` is the grounding facet (what this table IS); `name`/`code`/`code_pattern`/
 * `measures` are the mention facet (which attribute carries the entity when a human refers
 * to it by name, by code, or as a value — and, for the code, what a code looks like).
 * Order is the order the README table and the `SemMisplacedKeyword` message use.
 */
export const ALL_ENTITY_KEYS: ReadonlyArray<string> = ['kind', 'name', 'code', 'code_pattern', 'measures'];

/**
 * The closed aggregation vocabulary for a `measures:` item.
 *
 * ⚠ This is the aggregation of a MEASURE — declared where the measure is declared,
 * `{ attribute: quantity, aggregation: avg }`. It is not the def-level `aggregation:`
 * attribute property (which says an attribute is DERIVED by an aggregation, EN-P1.2)
 * and not md's measure `aggregation:` property. Three surfaces, three meanings.
 */
export const AGGREGATIONS = ['sum', 'avg', 'min', 'max', 'count', 'last'] as const;
export type Aggregation = (typeof AGGREGATIONS)[number];

/** A bare id in `measures:` means this. */
export const DEFAULT_AGGREGATION: Aggregation = 'sum';
