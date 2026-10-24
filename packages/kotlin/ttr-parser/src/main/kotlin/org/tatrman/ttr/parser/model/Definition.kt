// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.parser.model

/**
 * Typed model produced by the TTR parser.
 *
 * Each `def <kind> <name> { ... }` block in a `.ttr` file becomes one
 * [Definition] subtype. Cross-references are kept as raw [Reference]s — the
 * parser does NOT resolve them; semantic resolution is the consumer's job.
 *
 * Triple-string content is dedented (Python `textwrap.dedent` semantics) by
 * the walker before it lands here, so consumers see the canonical form.
 *
 * This is the modeler-canonical shape (group `org.tatrman`). Differences from
 * ai-platform's historical `shared.ttr.parser.model`:
 *  - v2.0.0: top-level `searchable` is GONE from [ColumnDef]/[AttributeDef];
 *    searchable lives only inside [SearchHintsValue]. (`indexed` stays a
 *    top-level column field — it matches the canonical TS `ColumnDef`.)
 *  - D4: [SourceLocation] is the richer ANTLR superset (endLine, endColumn,
 *    offsetStart, offsetEnd) and every [PropertyValue] variant carries a `source`.
 *  - [PropertyValue.TripleStringValue] is split out from `StringValue`.
 *  - [PropertyValue.IdValue] carries `parts` (the reference split on `.`).
 */
sealed interface Definition {
    val name: String
    val source: SourceLocation
    val description: String?

    /**
     * Grammar 0.13 (NLS-P10 / ⚑GXP-D7) — the localised `description: { en: …, cs: … }`
     * form. Exactly one of [description] / [descriptionLocalized] is ever populated:
     * an author writes one form or the other, and the walker keeps them apart rather
     * than folding a map to a single locale. Empty when the plain form was used (or
     * no description was authored at all).
     */
    val descriptionLocalized: LocalizedStringValue

    val tags: List<String>
}

data class ProjectDef(
    override val name: String,
    override val source: SourceLocation,
    override val description: String? = null,
    override val descriptionLocalized: LocalizedStringValue = LocalizedStringValue(),
    override val tags: List<String> = emptyList(),
    val version: String? = null,
) : Definition

data class TableDef(
    override val name: String,
    override val source: SourceLocation,
    override val description: String? = null,
    override val descriptionLocalized: LocalizedStringValue = LocalizedStringValue(),
    override val tags: List<String> = emptyList(),
    val primaryKey: List<String> = emptyList(),
    val columns: List<ColumnDef> = emptyList(),
    val indices: List<IndexDef> = emptyList(),
    val constraints: List<ConstraintDef> = emptyList(),
    /** Top-level `search { ... }` block (grammar allows it on tables). Empty when absent. */
    val search: SearchHintsValue = SearchHintsValue(),
    /** Grounding Phase 1 (grammar 4.2) — the `semantics { … }` block; null when absent. */
    val semantics: SemanticsBlock? = null,
    /** v4.4 — inline `lexicon { … }` sugar; null when absent. Desugared in semantics. */
    val lexicon: LexiconBlock? = null,
    /** EN-P1 (0.10) — `management: data|canon`; null when absent (default `data`, applied in semantics). */
    val management: String? = null,
    /** EN-P1 (0.10) — `changeSemantics: <mode> { <role>: <column> }`; null when absent (optimistic). */
    val changeSemantics: ChangeSemanticsDecl? = null,
    /** EN-P1 (0.10) — Q-8 `writeback { … }` reservation; null when absent. Parses inert (rung-v3 rides FO v1.1+). */
    val writeback: WritebackReservation? = null,
) : Definition

/**
 * EN-P1 (0.10) — a `changeSemantics: <mode> { <role>: <column>, … }` declaration on a table
 * (write-behaviour axis, FO §9). The parser keeps [mode] and the role→column map opaque; vocabulary
 * (scd1|scd2|ledger), role-name legality, and role-column type/existence checks are SEMANTIC.
 */
data class ChangeSemanticsDecl(
    val mode: String,
    /** Declared role → column-id (e.g. `validFrom → valid_from`, `reversalLink → reversal_of`). */
    val roles: Map<String, String> = emptyMap(),
    val source: SourceLocation,
)

/**
 * EN-P1 (0.10) — the Q-8 `writeback { … }` reservation (demand §4). A structured no-op: it parses
 * (free-form scalar body, the semantics-block shape) but carries no meaning this wave.
 */
data class WritebackReservation(
    val entries: Map<String, SemanticsValue> = emptyMap(),
    val source: SourceLocation,
)

data class ViewDef(
    override val name: String,
    override val source: SourceLocation,
    override val description: String? = null,
    override val descriptionLocalized: LocalizedStringValue = LocalizedStringValue(),
    override val tags: List<String> = emptyList(),
    val columns: List<ColumnDef> = emptyList(),
    /** Flattened `definitionSql` text (see [QueryDef.sourceText]); structure in [definitionSqlBlock]. */
    val definitionSql: String? = null,
    /**
     * embedded-sql (stage 1.4): the structured `definitionSql` value
     * ([PropertyValue.StringValue] / [PropertyValue.TripleStringValue] / [TaggedBlockValue]).
     */
    val definitionSqlBlock: PropertyValue? = null,
    /** Top-level `search { ... }` block (grammar allows it on views). Empty when absent. */
    val search: SearchHintsValue = SearchHintsValue(),
) : Definition

data class ColumnDef(
    override val name: String,
    override val source: SourceLocation,
    override val description: String? = null,
    override val descriptionLocalized: LocalizedStringValue = LocalizedStringValue(),
    override val tags: List<String> = emptyList(),
    val type: DataType? = null,
    val optional: Boolean = false,
    val isKey: Boolean = false,
    val indexed: Boolean = false,
    val search: SearchHintsValue = SearchHintsValue(),
    /** Grounding Phase 1 (grammar 4.2) — the `semantics { … }` block; null when absent. */
    val semantics: SemanticsBlock? = null,
    /** v4.4 — inline `lexicon { … }` sugar; null when absent. Desugared in semantics. */
    val lexicon: LexiconBlock? = null,
) : Definition

data class IndexDef(
    override val name: String,
    override val source: SourceLocation,
    override val description: String? = null,
    override val descriptionLocalized: LocalizedStringValue = LocalizedStringValue(),
    override val tags: List<String> = emptyList(),
    val indexType: String? = null,
    val columns: List<String> = emptyList(),
) : Definition

data class ConstraintDef(
    override val name: String,
    override val source: SourceLocation,
    override val description: String? = null,
    override val descriptionLocalized: LocalizedStringValue = LocalizedStringValue(),
    override val tags: List<String> = emptyList(),
    val constraintType: String? = null,
    val columns: List<String> = emptyList(),
) : Definition

data class FkDef(
    override val name: String,
    override val source: SourceLocation,
    override val description: String? = null,
    override val descriptionLocalized: LocalizedStringValue = LocalizedStringValue(),
    override val tags: List<String> = emptyList(),
    val from: PropertyValue? = null,
    val to: PropertyValue? = null,
) : Definition

data class ProcedureDef(
    override val name: String,
    override val source: SourceLocation,
    override val description: String? = null,
    override val descriptionLocalized: LocalizedStringValue = LocalizedStringValue(),
    override val tags: List<String> = emptyList(),
    val parameters: List<PropertyValue> = emptyList(),
    val resultColumns: List<ColumnDef> = emptyList(),
) : Definition

data class EntityDef(
    override val name: String,
    override val source: SourceLocation,
    override val description: String? = null,
    override val descriptionLocalized: LocalizedStringValue = LocalizedStringValue(),
    override val tags: List<String> = emptyList(),
    val labelPlural: String? = null,
    val nameAttribute: Reference? = null,
    val codeAttribute: Reference? = null,
    val aliases: List<String> = emptyList(),
    val attributes: List<AttributeDef> = emptyList(),
    /** `roles: [fact, dimension]` shorthand; refs are resolved by the consumer. */
    val roles: List<Reference> = emptyList(),
    /** `displayLabel { cs: "...", en: "..." }`; null when absent. */
    val displayLabel: LocalizedStringValue? = null,
    /** `search { keywords {...} patterns [...] ... }`. Empty when absent. */
    val search: SearchHintsValue = SearchHintsValue(),
    /** Grounding Phase 1 (grammar 4.2) — the `semantics { … }` block; null when absent. */
    val semantics: SemanticsBlock? = null,
    /** v4.4 — inline `lexicon { … }` sugar; null when absent. Desugared in semantics. */
    val lexicon: LexiconBlock? = null,
    /** v3.0 — inline `binding: { target: ..., columns: { ... } }` block; null when absent. */
    val binding: BindingProperty? = null,
    /** Grammar 0.17 — the `concept { kind|role|event: … }` sugar; null when absent. */
    val concept: ConceptSugar? = null,
) : Definition

data class AttributeDef(
    override val name: String,
    override val source: SourceLocation,
    override val description: String? = null,
    override val descriptionLocalized: LocalizedStringValue = LocalizedStringValue(),
    override val tags: List<String> = emptyList(),
    val type: DataType? = null,
    val isKey: Boolean = false,
    val optional: Boolean = false,
    /** `displayLabel { cs: "...", en: "..." }`; null when absent. */
    val displayLabel: LocalizedStringValue? = null,
    /** `valueLabels { "1": { cs: "Aktivní", en: "Active" }, ... }`. */
    val valueLabels: Map<String, LocalizedStringValue> = emptyMap(),
    /** A4-β (v4.4 S2) — per-value `aliases` (`"1": { label: {…}, aliases: […] }`). */
    val valueLabelAliases: Map<String, List<String>> = emptyMap(),
    /** `search { keywords {...} patterns [...] ... }`. Empty when absent. */
    val search: SearchHintsValue = SearchHintsValue(),
    /** Grounding Phase 1 (grammar 4.2) — the `semantics { … }` block; null when absent. */
    val semantics: SemanticsBlock? = null,
    /** v4.4 — inline `lexicon { … }` sugar; null when absent. Desugared in semantics. */
    val lexicon: LexiconBlock? = null,
    /** v3.0 — inline `binding: <bareId>` or `binding: { target: { column: ... } }`; null when absent. */
    val binding: BindingProperty? = null,
    /** MD (v3.1) — `domain: md.X`, the domain this dimension attribute ranges over; null for ER attrs. */
    val domainRef: Reference? = null,
    /** MD (v3.1) — `aggregation:` on a dimension attribute (e.g. `name { aggregation: latestValid }`). */
    val aggregation: AggregationSpec? = null,
    /** Grammar 0.16 — `states: [placed, held, …]`, the attribute's lifecycle states (D-67); empty when absent. */
    val states: List<String> = emptyList(),
    /** Grammar 0.16 — `transitions: { placed: [held, …] }` (D-67), in authored order; empty ⇒ any. */
    val transitions: List<AttributeTransition> = emptyList(),
    /** Grammar 0.17 — the `concept { quality: … }` sugar (er: also a value kind); null when absent. */
    val concept: ConceptSugar? = null,
) : Definition

/** Grammar 0.16 — one `from: [to, …]` entry of an attribute's `transitions:` map. */
data class AttributeTransition(
    val from: String,
    val to: List<String>,
    val source: SourceLocation,
)

/**
 * v4.4 lexicon surface (RG-P4, RS-9) — inline `lexicon { … }` sugar. The canonical
 * shorthand keys the walker extracts from the free-form object; desugared in
 * semantics. Mirrors the TS `LexiconBlock`.
 */
data class LexiconBlock(
    val terms: List<String> = emptyList(),
    val patterns: List<String> = emptyList(),
    val examples: List<String> = emptyList(),
    /** Grammar 0.17 — `default: true|false` beside the shorthands (cn contracts §1.5); null when absent. */
    val default: Boolean? = null,
    /** Grammar 0.17 — `sense_priority: <n>` beside the shorthands; null when absent. */
    val sensePriority: Double? = null,
)

/**
 * v4.4 — a canonical lexicon entry (`def term|pattern|example`). One shared body;
 * [entryKind] is the TTR keyword (`"term"`/`"pattern"`/`"example"`) and the dump
 * discriminator. Per-kind required-field validity lives in semantics.
 */
data class LexiconEntryDef(
    override val name: String,
    override val source: SourceLocation,
    override val description: String? = null,
    override val descriptionLocalized: LocalizedStringValue = LocalizedStringValue(),
    override val tags: List<String> = emptyList(),
    val entryKind: String = "term",
    /** The `for:` target ref (er/db/md), span-carrying; resolved in semantics. */
    val target: Reference? = null,
    val forms: List<String> = emptyList(),
    val match: String? = null,
    val text: String? = null,
    /** Grammar 0.17 — `default: true|false`, a cn default sense (cn contracts §1.5); null when absent. */
    val default: Boolean? = null,
    /** Grammar 0.17 — `sense_priority: <n>` (higher wins); null when absent. */
    val sensePriority: Double? = null,
) : Definition

data class RelationDef(
    override val name: String,
    override val source: SourceLocation,
    override val description: String? = null,
    override val descriptionLocalized: LocalizedStringValue = LocalizedStringValue(),
    override val tags: List<String> = emptyList(),
    val from: PropertyValue? = null,
    val to: PropertyValue? = null,
    val cardinality: PropertyValue.ObjectValue? = null,
    val join: List<PropertyValue> = emptyList(),
    /** Top-level `search { ... }` block (grammar allows it on relations). Empty when absent. */
    val search: SearchHintsValue = SearchHintsValue(),
    /** v3.0 — inline `binding: <fkRef>` or `binding: { fk: <fkRef> }`; null when absent. */
    val binding: BindingProperty? = null,
) : Definition

data class Er2DbEntityDef(
    override val name: String,
    override val source: SourceLocation,
    override val description: String? = null,
    override val descriptionLocalized: LocalizedStringValue = LocalizedStringValue(),
    override val tags: List<String> = emptyList(),
    val entity: Reference? = null,
    /**
     * v2.1 — `TargetValue?` accommodates the relaxed `target: <bareId>` form
     * (equivalent to `target: { table: <bareId> }`). Pattern-match on
     * [TargetObjectValue] when the entries map is needed.
     */
    val target: TargetValue? = null,
    val whereFilter: PropertyValue.ObjectValue? = null,
) : Definition

data class Er2DbAttributeDef(
    override val name: String,
    override val source: SourceLocation,
    override val description: String? = null,
    override val descriptionLocalized: LocalizedStringValue = LocalizedStringValue(),
    override val tags: List<String> = emptyList(),
    val attribute: Reference? = null,
    /** v2.1 — `TargetValue?` accommodates the relaxed `target: <bareId>` form. */
    val target: TargetValue? = null,
) : Definition

data class Er2DbRelationDef(
    override val name: String,
    override val source: SourceLocation,
    override val description: String? = null,
    override val descriptionLocalized: LocalizedStringValue = LocalizedStringValue(),
    override val tags: List<String> = emptyList(),
    val relation: Reference? = null,
    val fk: Reference? = null,
) : Definition

data class QueryDef(
    override val name: String,
    override val source: SourceLocation,
    override val description: String? = null,
    override val descriptionLocalized: LocalizedStringValue = LocalizedStringValue(),
    override val tags: List<String> = emptyList(),
    val language: String? = null,
    val parameters: List<PropertyValue> = emptyList(),
    /**
     * Flattened text of the `sourceText` property: the plain decoded string, the
     * dedented triple-string, or — for a tagged block — its extracted [value].
     * Kept for consumers that only want the SQL text (ai-platform). The structured
     * carrier (tag/dialect/spans) lives in [sourceTextBlock].
     */
    val sourceText: String? = null,
    /**
     * embedded-sql (stage 1.4): the structured `sourceText` value —
     * [PropertyValue.StringValue] / [PropertyValue.TripleStringValue] / [TaggedBlockValue].
     * Null when the property is absent. Mirrors the TS `QueryDef.sourceText` union.
     */
    val sourceTextBlock: PropertyValue? = null,
    /** `search { keywords {...} patterns [...] ... }`. Empty when absent. */
    val search: SearchHintsValue = SearchHintsValue(),
) : Definition

/** `def role <name> { label { cs: "...", en: "..." }, description: "..." }`. */
data class RoleDef(
    override val name: String,
    override val source: SourceLocation,
    override val description: String? = null,
    override val descriptionLocalized: LocalizedStringValue = LocalizedStringValue(),
    override val tags: List<String> = emptyList(),
    val label: LocalizedStringValue? = null,
    /** `search { keywords {...} patterns [...] ... }`. Empty when absent. */
    val search: SearchHintsValue = SearchHintsValue(),
    /**
     * Grammar 0.16 — an automation role's free-form properties (`resolve: keycloak_group "…"`,
     * `delivery: { … }`, `members: [ … ]`), in authored order. Empty for a cnc role.
     */
    val entries: List<AutoEntry> = emptyList(),
) : Definition

/** `def er2cnc_role <name> { entity: er.X, role: cnc.role.fact }`. */
data class Er2CncRoleDef(
    override val name: String,
    override val source: SourceLocation,
    override val description: String? = null,
    override val descriptionLocalized: LocalizedStringValue = LocalizedStringValue(),
    override val tags: List<String> = emptyList(),
    val entity: Reference? = null,
    val role: Reference? = null,
) : Definition

/**
 * v2.2 — `def drill_map <id> { from, to, args, display?, override? }`.
 *
 * `from` / `to` are unresolved references to `def query` patterns. `args` maps
 * target-parameter names to source-column names or literal strings (raw here;
 * the validator decides column-vs-literal). `display` is a localised chip
 * label. `overrideAuto` suppresses auto-derived drills with the same target.
 */
data class DrillMapDef(
    override val name: String,
    override val source: SourceLocation,
    override val description: String? = null,
    override val descriptionLocalized: LocalizedStringValue = LocalizedStringValue(),
    override val tags: List<String> = emptyList(),
    val from: Reference? = null,
    val to: Reference? = null,
    val args: Map<String, String> = emptyMap(),
    val display: LocalizedStringValue? = null,
    val overrideAuto: Boolean = false,
) : Definition

/**
 * v3.0 — `def area <id> { ... }` subject area, replacing the v2.3 `.ttrd` domain
 * block. A normal definition that lives in ordinary model files and registers a
 * resolvable symbol. Drives the resolved-packages `domains` artifact (recursive
 * package closure + entity set). Mirrors the TS `AreaDef` (`ast.ts`).
 */
data class AreaDef(
    override val name: String,
    override val source: SourceLocation,
    override val description: String? = null,
    override val descriptionLocalized: LocalizedStringValue = LocalizedStringValue(),
    override val tags: List<String> = emptyList(),
    /** Recursive members: each pulls the package and all descendants. May be empty. */
    val packages: List<String> = emptyList(),
    /** Individual entity qnames loaded in addition to whole packages. May be empty. */
    val entities: List<String> = emptyList(),
    /** Per-member source locations, parallel to [packages] (editor-only). */
    val packageSources: List<SourceLocation> = emptyList(),
    /** Per-member source locations, parallel to [entities] (editor-only). */
    val entitySources: List<SourceLocation> = emptyList(),
) : Definition

/**
 * Grammar 0.14 — a named row schema (`def schema <name> { columns: [...] }`): an ordered column
 * list, each column written exactly like a table column (`type`, `optional`, `description`, …).
 * Tier-neutral (maps to `db` by default, the `area` precedent); consumed by TTR-P, which holds
 * `display(<name>)` rows to it. Not a loadable object.
 */
data class SchemaDef(
    override val name: String,
    override val source: SourceLocation,
    override val description: String? = null,
    override val descriptionLocalized: LocalizedStringValue = LocalizedStringValue(),
    override val tags: List<String> = emptyList(),
    /** The row's columns, in declared order. */
    val columns: List<ColumnDef> = emptyList(),
) : Definition

// ============================ MD (multidimensional) model defs ============================
// v3.1 MD Layer A — the subset the dot-path resolver needs (MDS2): domains, dimensions +
// attributes, maps, measures, cubelets, hierarchies. The `md2db_*` binding defs (S4 lowering)
// are intentionally NOT modelled here. Kind strings + field names mirror the TS twin
// (`@tatrman/parser` ast.ts) so cross-target AST dumps stay comparable (AST-NAMING).

/** `def domain <id> { type:, kind:, restrict:, publish: members }` (contracts §1.4). */
data class MdDomainDef(
    override val name: String,
    override val source: SourceLocation,
    override val description: String? = null,
    override val descriptionLocalized: LocalizedStringValue = LocalizedStringValue(),
    override val tags: List<String> = emptyList(),
    val type: DataType? = null,
    /** `kind: calc | bound` (open id; validated in semantics). */
    val domainKind: String? = null,
    /** `publish: members` opts the domain into the member catalog (§1.4). Default: not published. */
    val publishMembers: Boolean = false,
    /**
     * The enumerable member set from `restrict: { range: 1..12 }` (expanded) or `restrict: { members:
     * {…} }` (the declared keys) — empty when the domain declares no enumerable restrict (a bare `pattern`/
     * `length`, or no restrict). Feeds disconnected member enumeration (writeback equal-spread, S5-B.2);
     * the resolver's connected member existence-check still goes through the catalog.
     */
    val restrictMembers: List<String> = emptyList(),
    /** Grammar 0.17 — the `concept { kind|role: … }` sugar; null when absent. */
    val concept: ConceptSugar? = null,
) : Definition

/**
 * A measure/attribute aggregation spec: `aggregation: sum` (default only) or
 * `aggregation: { default: sum, time: latestValid }` (per-dimension overrides).
 */
data class AggregationSpec(
    val default: String? = null,
    val perDimension: Map<String, String> = emptyMap(),
)

/** `def dimension <id> { key:, attributes: [def attribute …], hierarchies: [refs] }`. */
data class DimensionDef(
    override val name: String,
    override val source: SourceLocation,
    override val description: String? = null,
    override val descriptionLocalized: LocalizedStringValue = LocalizedStringValue(),
    override val tags: List<String> = emptyList(),
    val key: String? = null,
    val attributes: List<AttributeDef> = emptyList(),
    val hierarchies: List<Reference> = emptyList(),
    /** Grammar 0.17 — the `concept { kind|role: … }` sugar; null when absent. */
    val concept: ConceptSugar? = null,
) : Definition

/** A calc-map reference: `truncToDay`, or `fiscalYearOfDate(fiscalYearStartMonth: 4)` (named args). */
data class CalcRef(
    val name: String,
    val args: Map<String, String> = emptyMap(),
    val source: SourceLocation,
)

/** `def map <id> { from:, to:, cardinality:, calc: }` (a calc map is implicitly N:1). */
data class MdMapDef(
    override val name: String,
    override val source: SourceLocation,
    override val description: String? = null,
    override val descriptionLocalized: LocalizedStringValue = LocalizedStringValue(),
    override val tags: List<String> = emptyList(),
    val from: List<Reference> = emptyList(),
    val to: List<Reference> = emptyList(),
    /** Normalized "1:1" or "N:1"; null when unspecified (defaults N:1 for a table map). */
    val cardinality: String? = null,
    val calc: CalcRef? = null,
) : Definition

/** One hierarchy level: `day`, or `month via md.day_to_month` (leaf→root order). */
data class HierarchyLevel(
    val attribute: String,
    val via: Reference? = null,
    val source: SourceLocation,
)

/** `def hierarchy <id> { dimension:, levels: [level via map, …] }`. */
data class HierarchyDef(
    override val name: String,
    override val source: SourceLocation,
    override val description: String? = null,
    override val descriptionLocalized: LocalizedStringValue = LocalizedStringValue(),
    override val tags: List<String> = emptyList(),
    val dimensionRef: Reference? = null,
    val levels: List<HierarchyLevel> = emptyList(),
) : Definition

/** `def measure <id> { domain:, class:, aggregation:, validBy: }`. */
data class MeasureDef(
    override val name: String,
    override val source: SourceLocation,
    override val description: String? = null,
    override val descriptionLocalized: LocalizedStringValue = LocalizedStringValue(),
    override val tags: List<String> = emptyList(),
    val domainRef: Reference? = null,
    /** `class: additive | semiAdditive | nonAdditive` (open id). */
    val measureClass: String? = null,
    val aggregation: AggregationSpec? = null,
    val validBy: String? = null,
    /** Grammar 0.17 — the `concept { quality: … }` sugar; null when absent. */
    val concept: ConceptSugar? = null,
) : Definition

/** A cubelet measure: a ref to a standalone [MeasureDef], or an inline def. */
sealed interface CubeletMeasure {
    data class Ref(
        val ref: Reference,
    ) : CubeletMeasure

    data class Inline(
        val measure: MeasureDef,
    ) : CubeletMeasure
}

/** `def cubelet <id> { grain: [Dimension.attribute, …], measures: [refs | inline defs] }`. */
data class CubeletDef(
    override val name: String,
    override val source: SourceLocation,
    override val description: String? = null,
    override val descriptionLocalized: LocalizedStringValue = LocalizedStringValue(),
    override val tags: List<String> = emptyList(),
    /** Dotted `Dimension.attribute` grain refs (opaque; resolved in semantics). */
    val grain: List<Reference> = emptyList(),
    val measures: List<CubeletMeasure> = emptyList(),
    /** Grammar 0.17 — the `concept { event: …, involves: [ … ] }` sugar; null when absent. */
    val concept: ConceptSugar? = null,
) : Definition

// ===== MD → physical binding defs (schema binding, contracts §2/§4) =====
// The `md2db_*` binding defs (S4 lowering) — parsed here as the structural graph the read/write
// lowering consumes; validators stay TS-side (MDS2). Field names mirror the TS twin (`ast.ts`
// `Md2Db*Def`, contracts §2) for AST parity; cross-refs are opaque [Reference]s (Kotlin MD-def
// convention), leaf column names are plain strings.

/** `md2db_cubelet` shape (contracts §2): `wide`, or `long` with a code/value column pair. */
sealed interface ShapeSpec {
    /** One column per measure. */
    data object Wide : ShapeSpec

    /** A measure-code column + a single value column (measures selected by `code`). */
    data class Long(
        val codeColumn: String,
        val valueColumn: String,
    ) : ShapeSpec
}

/** An attribute→column binding: a plain column, or map-mediated (`via` a map, `from` a table/column). */
sealed interface AttrColumnBinding {
    data class Column(
        val column: String,
    ) : AttrColumnBinding

    /** Map-mediated grain column (design §6.1): the attribute reaches its column through [via]. */
    data class Via(
        val via: Reference,
        val from: FromColumn,
    ) : AttrColumnBinding

    /** The `from: { table, column }` backing of a map-mediated binding. */
    data class FromColumn(
        val table: Reference,
        val column: String,
    )
}

/** A measure→column binding: a column (wide), or a measure `code` (long). */
sealed interface MeasureColumnBinding {
    data class Column(
        val column: String,
    ) : MeasureColumnBinding

    data class Code(
        val code: String,
    ) : MeasureColumnBinding
}

/** Journaling mode of a cubelet binding (contracts §2/§12). */
sealed interface JournalingSpec {
    data object Overwrite : JournalingSpec

    data object Diff : JournalingSpec

    /** Invalidate mode requires a `validColumn` (the valid role until S5C promotes it). */
    data class Invalidate(
        val validColumn: String,
    ) : JournalingSpec
}

/**
 * Writeback spread strategy (v0.10, contracts §5 R21). [Uniform] applies one strategy to every spread
 * dimension (`allocation: proportional`); [PerDimension] maps dimension → strategy (`allocation: {
 * time: equal, product: proportional }`). The strategy string (`equal`/`proportional`) stays opaque
 * here — the parser is mechanical; the known-strategy vocabulary is validated in semantics/lowering.
 */
sealed interface AllocationSpec {
    data class Uniform(
        val strategy: String,
    ) : AllocationSpec

    data class PerDimension(
        val byDimension: Map<String, String>,
    ) : AllocationSpec
}

/** `def md2db_cubelet <id> { cubelet:, target:, shape:, attributes:, measures:, journaling? }`. */
data class Md2dbCubeletDef(
    override val name: String,
    override val source: SourceLocation,
    override val description: String? = null,
    override val descriptionLocalized: LocalizedStringValue = LocalizedStringValue(),
    override val tags: List<String> = emptyList(),
    /** `cubelet:` — the logical cubelet this binds (opaque ref). */
    val cubeletRef: Reference? = null,
    /** `target:` — the backing fact table (flattened from a bare id or `{ table }`). */
    val table: Reference? = null,
    val shape: ShapeSpec? = null,
    /** `attributes:` — grain attribute (`Dimension.attribute`) → its column binding. */
    val attributes: Map<String, AttrColumnBinding> = emptyMap(),
    /** `measures:` — measure name → its column binding. */
    val measures: Map<String, MeasureColumnBinding> = emptyMap(),
    val journaling: JournalingSpec? = null,
    /** `allocation:` — writeback spread strategy (v0.10, R21); null when the binding declares none. */
    val allocation: AllocationSpec? = null,
) : Definition

/** `def md2db_domain <id> { domain:, source: { table, column } }` (feeds a `kind: bound` domain). */
data class Md2dbDomainDef(
    override val name: String,
    override val source: SourceLocation,
    override val description: String? = null,
    override val descriptionLocalized: LocalizedStringValue = LocalizedStringValue(),
    override val tags: List<String> = emptyList(),
    val domainRef: Reference? = null,
    /** `source: { table, column }` (TS twin field `source_`; renamed here — `source` is the location). */
    val columnSource: ColumnSource? = null,
) : Definition

/** A `{ table, column }` member source for a bound domain. */
data class ColumnSource(
    val table: Reference,
    val column: String,
)

/** `def md2db_map <id> { map:, target:, columns: { from/to domain → case-table column } }`. */
data class Md2dbMapDef(
    override val name: String,
    override val source: SourceLocation,
    override val description: String? = null,
    override val descriptionLocalized: LocalizedStringValue = LocalizedStringValue(),
    override val tags: List<String> = emptyList(),
    val mapRef: Reference? = null,
    val table: Reference? = null,
    val columns: Map<String, String> = emptyMap(),
) : Definition

/** An explicit dimension → entity landing, including key and one-relation attribute paths. */
data class Md2erDimensionDef(
    override val name: String,
    override val source: SourceLocation,
    override val description: String? = null,
    override val descriptionLocalized: LocalizedStringValue = LocalizedStringValue(),
    override val tags: List<String> = emptyList(),
    val dimensionRef: Reference? = null,
    val entity: Reference? = null,
    val attributes: Map<String, String> = emptyMap(),
) : Definition

/**
 * `def md2er_cubelet <id> { cubelet:, target: <entity>, attributes: { attr → er attr } }` —
 * structural-only. Physical props (shape/allocation/journaling) are a permissive parse superset and
 * rejected in semantics (`md/md2er-physical-prop`); [physicalProps] records any that appeared.
 */
data class Md2erCubeletDef(
    override val name: String,
    override val source: SourceLocation,
    override val description: String? = null,
    override val descriptionLocalized: LocalizedStringValue = LocalizedStringValue(),
    override val tags: List<String> = emptyList(),
    val cubeletRef: Reference? = null,
    val entity: Reference? = null,
    val attributes: Map<String, String> = emptyMap(),
    val physicalProps: List<String> = emptyList(),
    val measures: Map<String, String> = emptyMap(),
) : Definition

/**
 * v4.1 — `def world <id> { … }`, a deployment world (D-d-α; ttr-metadata M0).
 * The ONLY top-level world def kind; engine/executor/storage/world-schema are
 * nested. Manifest entries are transported opaque (T6 β data — MD5).
 */
data class WorldDef(
    override val name: String,
    override val source: SourceLocation,
    override val description: String? = null,
    override val descriptionLocalized: LocalizedStringValue = LocalizedStringValue(),
    override val tags: List<String> = emptyList(),
    /** Optional world-level type overlay ref (grammar-permissive; usually unused). */
    val extends: String? = null,
    val engines: List<EngineDef> = emptyList(),
    val executors: List<ExecutorDef> = emptyList(),
    val storages: List<StorageDef> = emptyList(),
    /** Grammar 0.16 — `def identity <id> { principal, credential }` service principals (D-90). */
    val identities: List<WorldAutoMember> = emptyList(),
    /** Grammar 0.16 — unnamed `def grant { who, program, as }` (D-91), in authored order. */
    val grants: List<WorldAutoMember> = emptyList(),
    /** Grammar 0.16 — `def deployment <id> { program|process, target, run_as, trigger, … }` (D-107). */
    val deployments: List<WorldAutoMember> = emptyList(),
    /** Grammar 0.16 — `def process <id> { instance, steps: [ def step … ] }` (D-39, D-86). */
    val processes: List<ProcessDef> = emptyList(),
) : Definition

/** `def engine <id> { … }` inside a world (D-d-α). */
data class EngineDef(
    val name: String,
    val source: SourceLocation,
    val description: String? = null,
    val descriptionLocalized: LocalizedStringValue = LocalizedStringValue(),
    val tags: List<String> = emptyList(),
    /** `type:` discriminator (raw dataType text, e.g. `postgres`). */
    val type: String? = null,
    /** `version:` string literal. */
    val version: String? = null,
    /** `extends:` type-manifest ref (dotted id; resolved in M2). */
    val extends: String? = null,
    /** Free-form manifest entries (T6 β data — transported opaque, MD5). */
    val manifest: Map<String, PropertyValue> = emptyMap(),
)

/** `def executor <id> { … }` inside a world (same body as engine). */
data class ExecutorDef(
    val name: String,
    val source: SourceLocation,
    val description: String? = null,
    val descriptionLocalized: LocalizedStringValue = LocalizedStringValue(),
    val tags: List<String> = emptyList(),
    val type: String? = null,
    val version: String? = null,
    val extends: String? = null,
    val manifest: Map<String, PropertyValue> = emptyMap(),
)

/** `def storage <id> { … }` inside a world (D-d-α/D-d-i/D-f). */
data class StorageDef(
    val name: String,
    val source: SourceLocation,
    val description: String? = null,
    val descriptionLocalized: LocalizedStringValue = LocalizedStringValue(),
    val tags: List<String> = emptyList(),
    val type: String? = null,
    val extends: String? = null,
    /** `via:` engine this storage is reached through. */
    val via: String? = null,
    /** `hosts: [pkg]` — model packages this storage serves (D-d-i). */
    val hosts: List<String> = emptyList(),
    /** `staging: true` — exactly-one enforced in semantics/WorldResolver (D-f). */
    val staging: Boolean = false,
    /** Named `def schema` declarations local to this storage (D-c world home). */
    val schemas: List<WorldSchemaDef> = emptyList(),
    val manifest: Map<String, PropertyValue> = emptyMap(),
)

/** `def schema <id> { field: type, … }` nested in a storage (D-c world home). */
data class WorldSchemaDef(
    val name: String,
    val source: SourceLocation,
    val fields: List<WorldSchemaField> = emptyList(),
)

data class WorldSchemaField(
    val name: String,
    /** Raw dataType text (e.g. `string`, `decimal`). */
    val type: String,
    val source: SourceLocation,
)

// ============================ 0.16 automation model ============================
// `model automation` (AU-core-P0 S0.2): six def kinds share ONE permissive body ([AutoBody]) —
// typed common fields + an ordered free-form entry list; the automation world members
// (identity / grant / deployment / process step) reuse it. Values are carried structurally;
// their meaning (AU ttr-core contracts §2–§3) is validated in ttr-semantics, never here.

/** A free-form automation value (`autoValue`). Lists and objects recurse through it. */
sealed interface AutoValue {
    val source: SourceLocation

    /** A string literal; [triple] = written as a triple-quoted string (dedented). */
    data class Str(
        val raw: String,
        val triple: Boolean,
        override val source: SourceLocation,
    ) : AutoValue

    /** A number; [text] is the number as written (parameter values feed the D-13 values fingerprint). */
    data class Num(
        val raw: Double,
        override val source: SourceLocation,
        val text: String = raw.toString(),
    ) : AutoValue

    data class Bool(
        val raw: Boolean,
        override val source: SourceLocation,
    ) : AutoValue

    data class Null(
        override val source: SourceLocation,
    ) : AutoValue

    /** A bare or dotted id (`none`, `role.ops`, `schema.line_received`). */
    data class Id(
        val ref: Reference,
        val parts: List<String>,
        override val source: SourceLocation,
    ) : AutoValue

    /** `2 working_days` — the unit is validated in semantics. */
    data class Duration(
        val amount: Double,
        val unit: String,
        override val source: SourceLocation,
    ) : AutoValue

    /** `schedule "06:00 Europe/Prague"` · `keycloak_group "hartland-sales"`. */
    data class Tagged(
        val tag: String,
        val value: String,
        override val source: SourceLocation,
    ) : AutoValue

    /** `hold { order: order_number }` — a call-shaped value. */
    data class Call(
        val name: String,
        val entries: List<AutoEntry>,
        override val source: SourceLocation,
    ) : AutoValue

    /** `*` — any (`program: *`). */
    data class Star(
        override val source: SourceLocation,
    ) : AutoValue

    data class Obj(
        val entries: List<AutoEntry>,
        override val source: SourceLocation,
    ) : AutoValue {
        /** The last value written under [key] (a later duplicate wins, the dump's rule). */
        operator fun get(key: String): AutoValue? = entries.lastOrNull { it.key == key }?.value
    }

    data class ListV(
        val items: List<AutoValue>,
        override val source: SourceLocation,
    ) : AutoValue
}

data class AutoEntry(
    val key: String,
    val value: AutoValue,
    val source: SourceLocation,
)

/** `transition: order.state -> released { from: [placed, held] }` / `-> {to}`. */
data class AutoTransition(
    /** The attribute the transition writes (`order.state`). */
    val attribute: String,
    /** The target state, when written as a bare state. */
    val to: String?,
    /** The action column naming the state, when written as `{column}`. */
    val toColumn: String?,
    /** The `{ from: … }` guard; empty = no guard. */
    val from: List<String>,
    val source: SourceLocation,
)

/** `set: order.held_for = department`. */
data class AutoSet(
    val attribute: String,
    val column: String,
    val source: SourceLocation,
)

/** The shared automation body: typed common fields + the ordered free-form entries. */
data class AutoBody(
    val description: String? = null,
    val descriptionLocalized: LocalizedStringValue = LocalizedStringValue(),
    val tags: List<String> = emptyList(),
    val label: LocalizedStringValue? = null,
    val type: DataType? = null,
    val columns: List<ColumnDef> = emptyList(),
    val target: TargetValue? = null,
    val transition: AutoTransition? = null,
    val set: AutoSet? = null,
    val entries: List<AutoEntry> = emptyList(),
) {
    /** The last value written under [key] (a later duplicate wins, the dump's rule). */
    operator fun get(key: String): AutoValue? = entries.lastOrNull { it.key == key }?.value
}

/**
 * Grammar 0.16 — `def parameter | action | service | approval | program | recipe <id> { … }`.
 * [kind] is the def keyword. One class for the six kinds: they share one grammar body; the
 * per-kind property contract is semantic (AutomationValidator).
 */
data class AutomationDef(
    val kind: String,
    override val name: String,
    override val source: SourceLocation,
    val body: AutoBody = AutoBody(),
) : Definition {
    override val description: String? get() = body.description
    override val descriptionLocalized: LocalizedStringValue get() = body.descriptionLocalized
    override val tags: List<String> get() = body.tags

    companion object {
        /** The six automation def keywords, in grammar order. */
        val KINDS: List<String> = listOf("parameter", "action", "service", "approval", "program", "recipe")
    }
}

/**
 * Grammar 0.16 — an automation world member: `def identity <id>`, `def grant { … }` (unnamed:
 * [name] is null, keyed by its who/program/as triple), `def deployment <id>`, or a process's
 * `def step <id>`. [kind] is the def keyword.
 */
data class WorldAutoMember(
    val kind: String,
    val name: String?,
    val source: SourceLocation,
    val body: AutoBody = AutoBody(),
)

/** Grammar 0.16 — world `def process <id> { instance: …, steps: [ def step … ] }` (D-39, D-86). */
data class ProcessDef(
    val name: String,
    val source: SourceLocation,
    val description: String? = null,
    val descriptionLocalized: LocalizedStringValue = LocalizedStringValue(),
    val tags: List<String> = emptyList(),
    val steps: List<WorldAutoMember> = emptyList(),
    val entries: List<AutoEntry> = emptyList(),
) {
    /** The last value written under [key]. */
    operator fun get(key: String): AutoValue? = entries.lastOrNull { it.key == key }?.value
}

// ============================ 0.17 conceptual model ============================
// `model cn` (CN-P1, cn contracts §1): the concept defs, a cn `def role` (⚑CN-P6), the ten cn bindings
// and the `concept { }` sugar read one body — every property, in authored order, to one
// [ConceptProperty] (its key as written, its span), typed where the grammar types it and a free-form
// [ConceptProperty.Entry] otherwise. A repeated key is a `ttr/duplicate-property` error; the first is
// read. Meaning, legality per stereotype and references are CN-P2's. Mirrors the TS twin (`ast.ts`).

/**
 * Grammar 0.17 — `def kind | role | event | phase | quality | relation_type <id> { … }`. One class for
 * every stereotype (the [AutomationDef] precedent); a `def role` is a concept only in a `model cn` file.
 */
data class ConceptDef(
    val stereotype: String,
    override val name: String,
    override val source: SourceLocation,
    val body: ConceptBody = ConceptBody(),
) : Definition {
    override val description: String? get() = body.description
    override val descriptionLocalized: LocalizedStringValue get() = body.descriptionLocalized
    override val tags: List<String> get() = body.tags

    companion object {
        /** The concept stereotypes, in grammar order (`role` is the shared `def role`). */
        val STEREOTYPES: List<String> = listOf("kind", "role", "event", "phase", "quality", "relation_type")
    }
}

/** Grammar 0.17 — `def cn2md_* | cn2er_* | cn2db_concept <id> { … }`; [bindingKind] is the def keyword. */
data class ConceptBindingDef(
    val bindingKind: String,
    override val name: String,
    override val source: SourceLocation,
    val body: ConceptBody = ConceptBody(),
) : Definition {
    override val description: String? get() = body.description
    override val descriptionLocalized: LocalizedStringValue get() = body.descriptionLocalized
    override val tags: List<String> get() = body.tags

    companion object {
        /** The ten cn binding keywords, in grammar order. */
        val KINDS: List<String> =
            listOf(
                "cn2md_kind",
                "cn2md_role",
                "cn2md_quality",
                "cn2md_event",
                "cn2er_kind",
                "cn2er_role",
                "cn2er_event",
                "cn2er_quality",
                "cn2er_phase",
                "cn2db_concept",
            )
    }
}

/** Grammar 0.17 — `concept { … }` inside an er or md def: that def's own concept (desugared in CN-P2). */
data class ConceptSugar(
    val body: ConceptBody,
    /** The whole `concept { … }` property. */
    val source: SourceLocation,
)

/**
 * One cn body. [properties] keeps every property in authored order, repeats included; the convenience
 * fields hold the FIRST `description` / `label` / `tags` (the one the walker reads).
 */
data class ConceptBody(
    val properties: List<ConceptProperty> = emptyList(),
    val description: String? = null,
    val descriptionLocalized: LocalizedStringValue = LocalizedStringValue(),
    val tags: List<String> = emptyList(),
    val label: LocalizedStringValue? = null,
) {
    /** The first property written under [key] (the one the walker reads), or null. */
    operator fun get(key: String): ConceptProperty? = properties.firstOrNull { it.key == key }

    /** The first property of type [T], or null. */
    inline fun <reified T : ConceptProperty> property(): T? = properties.firstNotNullOfOrNull { it as? T }

    /** The stereotype ref written under [key] (`kind`, `role`, `quality`, `event`, `phase`, `concept`). */
    fun ref(key: String): Reference? = (this[key] as? ConceptProperty.Ref)?.ref

    /** The free-form value written under [key] (`of`, `is_a`, `participants`, …). */
    fun entry(key: String): PropertyValue? = (this[key] as? ConceptProperty.Entry)?.value
}

/** One property of a cn body. [key] is the key as written; [source] spans the whole property. */
sealed interface ConceptProperty {
    val key: String
    val source: SourceLocation

    /** `description: "…"` / `"""…"""` ([value]) or `{ en: …, cs: … }` ([localized]). */
    data class Description(
        override val key: String,
        val value: PropertyValue?,
        val localized: LocalizedStringValue?,
        override val source: SourceLocation,
    ) : ConceptProperty

    data class Label(
        override val key: String,
        val value: LocalizedStringValue,
        override val source: SourceLocation,
    ) : ConceptProperty

    data class Tags(
        override val key: String,
        val value: List<String>,
        override val source: SourceLocation,
    ) : ConceptProperty

    /** A stereotype ref — [key] is `kind | role | quality | event | phase | concept`. */
    data class Ref(
        override val key: String,
        val ref: Reference,
        override val source: SourceLocation,
    ) : ConceptProperty

    data class Grounds(
        override val key: String,
        val ref: Reference,
        override val source: SourceLocation,
    ) : ConceptProperty

    /** `value_space: ProductCategory`. */
    data class ValueSpace(
        override val key: String,
        val ref: Reference,
        override val source: SourceLocation,
    ) : ConceptProperty

    data class Involves(
        override val key: String,
        val participants: List<ConceptParticipant>,
        override val source: SourceLocation,
    ) : ConceptProperty

    data class Period(
        override val key: String,
        val value: PropertyValue.ObjectValue,
        override val source: SourceLocation,
    ) : ConceptProperty

    data class Extent(
        override val key: String,
        val value: String,
        override val source: SourceLocation,
    ) : ConceptProperty

    data class Basis(
        override val key: String,
        val value: String,
        override val source: SourceLocation,
    ) : ConceptProperty

    /** `definition: """…"""` — an expression block (⚑CN-P5), parsed by the TTR-P expression grammar in P2. */
    data class DefinitionBlock(
        override val key: String,
        val block: EmbeddedBlock,
        override val source: SourceLocation,
    ) : ConceptProperty

    /** `members: """…"""` — an expression block. */
    data class MembersBlock(
        override val key: String,
        val block: EmbeddedBlock,
        override val source: SourceLocation,
    ) : ConceptProperty

    /** A binding's `filter: """…"""` — the expression block of a valued state's series (A-CN-18). */
    data class FilterBlock(
        override val key: String,
        val block: EmbeddedBlock,
        override val source: SourceLocation,
    ) : ConceptProperty

    data class Lexicon(
        override val key: String,
        val block: LexiconBlock,
        override val source: SourceLocation,
    ) : ConceptProperty

    /** A binding's `target:` (an object or a bare reference). */
    data class Target(
        override val key: String,
        val value: TargetValue,
        override val source: SourceLocation,
    ) : ConceptProperty

    /** A free-form entry (`of`, `is_a`, `qualities`, `relations`, `hierarchy`, `via`, …), structural. */
    data class Entry(
        override val key: String,
        val value: PropertyValue,
        override val source: SourceLocation,
    ) : ConceptProperty
}

/** One `involves:` participant: a concept ref, optionally labelled (`Warehouse as from`). */
data class ConceptParticipant(
    val ref: Reference,
    val label: String? = null,
    val source: SourceLocation,
)

/**
 * Grammar 0.17 (⚑CN-P5) — an expression block (`definition:`, `members:`, a binding's `filter:`): its
 * text exactly as the expression parser reads it, plus where every line of it starts in the file.
 * [form] is how it was written (`string` · `tripleString` · `tagged` with `"""pl`); [language] is
 * always `pl`. [lineOffsets] holds the file offset of each line of [text] once the dedent removed its
 * indent; [textSource] spans [text]. Mirrors the TS `EmbeddedBlock`.
 */
data class EmbeddedBlock(
    val language: String = "pl",
    val form: String,
    val text: String,
    val textSource: SourceLocation,
    val lineOffsets: List<Int>,
    val indentWidth: Int,
    /** The whole literal, quotes included. */
    val source: SourceLocation,
    /**
     * The string form only: the index in [text] of every character written escaped (two characters in
     * the file, one in [text]). Positions count code points.
     */
    val escapes: List<Int> = emptyList(),
) {
    /**
     * The file span of a position inside [text] — [line] 0-based within the block, [column] 0-based
     * within that line of [text], both in code points. Mirrors the TS `embeddedBlockLocation`.
     */
    fun location(
        line: Int,
        column: Int,
        length: Int = 1,
    ): SourceLocation {
        val l = line.coerceIn(0, lineOffsets.size - 1)
        // A string-form block's escapes each take one more character of the file than of `text`: count the
        // ones on this line before the column ([escapes] index the whole text, in code points).
        val lineStart = text.split("\n").take(l).sumOf { it.codePointCount(0, it.length) + 1 }

        fun shift(c: Int) = escapes.count { it >= lineStart && it < lineStart + c }
        val base = if (l == 0) textSource.column else indentWidth
        val start = column + shift(column)
        val end = column + length + shift(column + length)
        return SourceLocation(
            file = source.file,
            line = textSource.line + l,
            column = base + start,
            endLine = textSource.line + l,
            endColumn = base + end,
            offsetStart = lineOffsets[l] + start,
            offsetEnd = lineOffsets[l] + end,
        )
    }
}

/**
 * Parser-side carrier for `{ cs: "...", en: "..." }` blocks. No proto
 * dependency; consumers convert into their own localised-string type.
 */
data class LocalizedStringValue(
    val byLanguage: Map<String, String> = emptyMap(),
)

/** Parser-side carrier for `{ cs: ["...", "..."], en: ["..."] }` blocks. */
data class LocalizedStringListValue(
    val byLanguage: Map<String, List<String>> = emptyMap(),
)

/**
 * Parser-side carrier for the `search { ... }` block on queries, entities,
 * attributes, columns, relations, and conceptual roles. Absence of an inner
 * property is an empty value (not null) so consumers can iterate freely.
 */
data class SearchHintsValue(
    val keywords: LocalizedStringListValue = LocalizedStringListValue(),
    val patterns: List<String> = emptyList(),
    val descriptions: LocalizedStringListValue = LocalizedStringListValue(),
    val examples: List<String> = emptyList(),
    val aliases: List<String> = emptyList(),
    val searchable: Boolean = false,
    /** Deprecated by grammar 0.12 (RV-32) — use [method] (`fuzzy: true` ≡ `method: TYPOS(1)`). */
    val fuzzy: Boolean = false,
    /**
     * Grammar 0.12 (RV-32) — the match method authored on `searchable`. Mechanical:
     * the name is as authored and the argument raw; the EXACT/TYPOS/TOKENS
     * vocabulary and the arity rule live in ttr-semantics (`SearchMethod.kt`).
     */
    val method: MatchMethodValue? = null,
    /**
     * Whether `fuzzy` was authored AT ALL, as opposed to defaulting to false.
     * The 0.12 mapping needs the distinction (`fuzzy: false` → `EXACT`, absent →
     * the RV-32 default `TYPOS(1)`), and widening [fuzzy] itself to `Boolean?`
     * would break every published-artifact consumer that reads it as a Boolean.
     * Appended, so positional construction stays source-compatible.
     */
    val fuzzyAuthored: Boolean = false,
)

/**
 * Grammar 0.12 (RV-P1.5, RV-32) — `method: EXACT | TYPOS(n) | TOKENS`. Parser-side
 * carrier: [name] exactly as authored, [argument] the raw numeric literal. Mirrors
 * the TS `MatchMethod` (`ast.ts`).
 */
data class MatchMethodValue(
    val name: String,
    val argument: Double? = null,
) {
    /**
     * The authored surface form — `TYPOS(2)`, `EXACT`. Whole arguments render
     * without a `.0` so the writer's output and the conformance dump stay
     * byte-identical with the TS and Python targets.
     */
    fun toSurfaceText(): String = name + (argument?.let { "(${formatNumber(it)})" } ?: "")

    companion object {
        fun formatNumber(n: Double): String = if (n % 1.0 == 0.0) n.toLong().toString() else n.toString()

        private val SURFACE = Regex("""^([A-Za-z_][A-Za-z0-9_]*)(?:\(\s*(-?\d+(?:\.\d+)?)\s*\))?$""")

        /**
         * The inverse of [toSurfaceText] — `TYPOS(2)` ⇄ `MatchMethodValue("TYPOS", 2.0)`. Returns
         * null for anything that is not a method surface form, so a carrier that stored something
         * else degrades to "no method" rather than to a wrong one.
         */
        fun ofSurfaceText(text: String): MatchMethodValue? {
            val m = SURFACE.matchEntire(text.trim()) ?: return null
            return MatchMethodValue(
                name = m.groupValues[1],
                argument = m.groupValues[2].takeIf { it.isNotEmpty() }?.toDoubleOrNull(),
            )
        }
    }
}

/**
 * Grounding Phase 1 (grammar 4.2) — the free-form `semantics { … }` block. The
 * parser stays mechanical: entries are captured as raw key→value pairs (ids as
 * their identifier text, string literals unquoted, numbers/booleans as
 * primitives, `null` as [SemanticsValue.NullV]) with NO vocabulary or shape
 * checking — that is ttr-semantics' job. Lists and nested objects are carried
 * verbatim (MS, vocabulary v3: `measures: [amount_czk, { attribute: quantity,
 * aggregation: avg }]`); only a `functionCall` value, which has no data meaning
 * here, is still rejected into `ttr/semantics-non-scalar`. Mirrors the TS
 * `SemanticsBlock` (`ast.ts`).
 *
 * `source` spans the `{ … }` object (the search-block convention). Last-wins on
 * a duplicate key; repeated keys are recorded in [duplicateProperties].
 */
data class SemanticsBlock(
    val entries: Map<String, SemanticsValue> = emptyMap(),
    val duplicateProperties: List<String> = emptyList(),
    val source: SourceLocation,
)

/**
 * A single `semantics` entry value. Ids arrive as their identifier text (a [Str]).
 *
 * MS (vocabulary v3) added [ListV] and [ObjV]. The grammar's `value` rule has always
 * admitted `list` and `object_`; it was the walker that flattened them away, which left
 * the v3 `measures:` surface unrepresentable. Nesting is arbitrary on purpose — the
 * parser has no vocabulary, so it cannot know how deep a given key is allowed to go.
 *
 * Mirrors the TS `SemanticsValue` union (`ast.ts`); the conformance dump compares both.
 */
sealed interface SemanticsValue {
    data class Str(
        val value: String,
    ) : SemanticsValue

    data class Num(
        val value: Double,
    ) : SemanticsValue

    data class Bool(
        val value: Boolean,
    ) : SemanticsValue

    data object NullV : SemanticsValue

    /** A `[ … ]` value, in authored order — order is contract for `measures:`. */
    data class ListV(
        val items: List<SemanticsValue> = emptyList(),
    ) : SemanticsValue

    /** A `{ … }` value. Last-wins on a duplicate key, as the block itself is. */
    data class ObjV(
        val entries: Map<String, SemanticsValue> = emptyMap(),
    ) : SemanticsValue

    /** Display form for diagnostic messages (matches the TS `String(value)`). */
    fun display(): String =
        when (this) {
            is Str -> value
            is Num ->
                if (value == kotlin.math.floor(value) &&
                    value.isFinite()
                ) {
                    value.toLong().toString()
                } else {
                    value.toString()
                }
            is Bool -> value.toString()
            is NullV -> "null"
            // TS renders these through `String(value)`, which is what a diagnostic that
            // interpolates a structured value would show there. Kept identical so a
            // message built from one is byte-identical to a message built from the other.
            // ⛑ `Array.prototype.join` renders null (and undefined) as the EMPTY string, not
            // as "null" — `String([null, 'a'])` is `",a"`. A bare `it.display()` here gave
            // "null,a" and quietly broke the byte-identity this comment claims (MS-P1·S1).
            is ListV -> items.joinToString(",") { if (it is NullV) "" else it.display() }
            is ObjV -> "[object Object]"
        }
}

/**
 * PL-P4.S3 (grammar 0.11, H-1) — a document-level `security { … }` block:
 * declarative access sugar over model objects, consumed one-way by
 * `ttr-security-gen` → Rego. Structured (own/classify/grant/mask); object refs
 * are kept as opaque qname text (resolution is semantic + advisory — never a
 * compile block). Fingerprint-neutral: the block never enters WorldFingerprint /
 * the T6 semantic hash and never alters emitted plans. Mirrors the TS
 * `SecurityBlock` in `@tatrman/parser`.
 */
data class SecurityBlock(
    val statements: List<SecurityStatement> = emptyList(),
    val source: SourceLocation,
)

/**
 * One statement inside a [SecurityBlock]. `objectRef` is the opaque qname the
 * statement targets (a dotted id kept verbatim); the four verbs mirror the
 * platform contracts §11 vocabulary.
 */
sealed interface SecurityStatement {
    val verb: String
    val objectRef: String
    val source: SourceLocation

    /** `own <object>: <owner-role>` — ownership declaration. */
    data class Own(
        override val objectRef: String,
        val owner: String,
        override val source: SourceLocation,
    ) : SecurityStatement {
        override val verb: String get() = "own"
    }

    /** `classify <object>: <classification>` — classifications are the native grant vocabulary (HQ-1). */
    data class Classify(
        override val objectRef: String,
        val classification: String,
        override val source: SourceLocation,
    ) : SecurityStatement {
        override val verb: String get() = "classify"
    }

    /** `grant <privilege> on <object> to <role|classification>` — grants only (deny-overrides at bundle build). */
    data class Grant(
        val privilege: String,
        override val objectRef: String,
        val grantee: String,
        override val source: SourceLocation,
    ) : SecurityStatement {
        override val verb: String get() = "grant"
    }

    /** `mask <object>` — column mask (default: for all; policy maps exceptions). */
    data class Mask(
        override val objectRef: String,
        override val source: SourceLocation,
    ) : SecurityStatement {
        override val verb: String get() = "mask"
    }
}

/**
 * Surface or physical type carrier. `name` is the canonical type token
 * (`text`, `int`, `varchar`, `decimal`, etc.). `length`/`precision` are the declared bounds
 * of the structured form `{ type: …, length: …, precision: … }` on any typed def
 * (attribute, column, automation member). For text, `length` is the character limit;
 * for decimal, `length` is total digits and `precision` is scale. Null means undeclared.
 */
data class DataType(
    val name: String,
    val length: Int? = null,
    val precision: Int? = null,
)

/**
 * Cross-reference identifier. The consumer resolves the dotted [path] against the
 * in-memory model graph. Carries [parts] (the path split on `.`) and the [source]
 * span of the reference *token* — mirrors the canonical TS `Reference`
 * (`{ path, parts, source }` in `ast.ts`), so diagnostics/navigation built from a
 * collected reference point at the reference itself, not its enclosing def.
 *
 * The single-arg [constructor] (path only) is a convenience for model construction
 * outside the parser (tests, synthesizers) where no token span exists; it derives
 * `parts` and uses [SourceLocation.UNKNOWN]. The walker always supplies a real span.
 */
data class Reference(
    val path: String,
    val parts: List<String>,
    val source: SourceLocation,
) {
    constructor(path: String) : this(path, path.split("."), SourceLocation.UNKNOWN)

    override fun toString(): String = path
}

/**
 * Generic value form for ANY-typed properties (`from`, `to`, `target`, etc.).
 * The walker emits the structural shape it observed; the consumer interprets
 * per-property semantics. D4: every variant carries a [source].
 */
sealed interface PropertyValue {
    val source: SourceLocation

    data class StringValue(
        val raw: String,
        override val source: SourceLocation,
    ) : PropertyValue

    /** Split out from [StringValue] per the canonical TS shape — content is dedented. */
    data class TripleStringValue(
        val raw: String,
        override val source: SourceLocation,
    ) : PropertyValue

    data class NumberValue(
        val raw: Double,
        override val source: SourceLocation,
    ) : PropertyValue

    data class BoolValue(
        val raw: Boolean,
        override val source: SourceLocation,
    ) : PropertyValue

    data class NullValue(
        override val source: SourceLocation,
    ) : PropertyValue

    data class IdValue(
        val ref: Reference,
        /** The reference split on `.` (matches the TS `IdValue.parts`). */
        val parts: List<String>,
        override val source: SourceLocation,
    ) : PropertyValue

    data class ListValue(
        val items: List<PropertyValue>,
        override val source: SourceLocation,
    ) : PropertyValue

    data class ObjectValue(
        val entries: Map<String, PropertyValue>,
        override val source: SourceLocation,
    ) : PropertyValue

    data class FunctionCall(
        val name: String,
        val args: List<PropertyValue>,
        override val source: SourceLocation,
    ) : PropertyValue
}

/**
 * Resolved embedded-language kind. Mirrors the TS `LanguageKind = QueryLanguage`
 * (`'SQL' | 'TRANSFORMATION_DSL' | 'DATAFRAME_DSL' | 'REL_NODE'`). Kept as a
 * `String` alias so the conformance dump matches byte-for-byte (the TS union
 * serialises to the same bare strings).
 */
typealias LanguageKind = String

/**
 * embedded-sql (DESIGN §3, contracts §2.2): a tagged triple-quoted block
 * (`"""<tag>␊…"""`) carrying embedded foreign-language source. Produced only by
 * the `sourceText` / `definitionSql` properties via the `embeddedBlock` rule.
 * The tag is peeled before [value], so it never reaches the executed SQL.
 *
 * A top-level [PropertyValue] variant (same package) mirroring the TS
 * `TaggedBlockValue` interface; carries the value contract only — no SQL
 * analysis (that lands in `@modeler/sql` / Phase 2).
 */
data class TaggedBlockValue(
    val tag: String,
    val language: LanguageKind,
    val dialect: String?, // dialect id string; null for a bare `sql` (→ modeler.toml default) or non-SQL
    val value: String,
    val tagSource: SourceLocation,
    val valueSource: SourceLocation,
    val indentWidth: Int,
    override val source: SourceLocation,
) : PropertyValue

// ----- v3.0: inline bindings (was v2.1 `mapping:`) -----

/**
 * v3.0 — inline `binding:` property on entity / attribute / relation defs and
 * on explicit `def er2db_*` declarations' `target:` slot. Two surface forms:
 *
 * - [BindingPropertyBareId] — `binding: db.dbo.fk_item_category` (relation FK
 *   shorthand) or attribute-level `binding: COLUMN_NAME`.
 * - [BindingPropertyBlock]  — `binding: { target: ..., columns: { ... } }`.
 */
sealed interface BindingProperty {
    val source: SourceLocation
}

data class BindingPropertyBareId(
    val id: Reference,
    override val source: SourceLocation,
) : BindingProperty

data class BindingPropertyBlock(
    val target: TargetValue? = null,
    val columns: List<BindingColumnEntry> = emptyList(),
    val fk: Reference? = null,
    override val source: SourceLocation,
) : BindingProperty

/**
 * v2.1 — `target:` value union. Both inline `binding: { target: ... }` blocks
 * and explicit `def er2db_*` `target:` slots accept either an object (e.g.
 * `{ table: db.dbo.T }`) or a bare reference (e.g. `db.dbo.T`).
 */
sealed interface TargetValue {
    val source: SourceLocation
}

data class TargetObjectValue(
    val obj: PropertyValue.ObjectValue,
    override val source: SourceLocation,
) : TargetValue

data class TargetReferenceValue(
    val ref: Reference,
    override val source: SourceLocation,
) : TargetValue

/** v3.0 — one entry in a `binding: { columns: { sk: i_item_sk, ... } }` map. */
data class BindingColumnEntry(
    val name: String,
    val value: BindingColumnValue,
    val source: SourceLocation,
)

/**
 * v3.0 — one value in the inline `columns:` map. Two surface forms:
 *  - [BindingColumnBareId] — `sk: i_item_sk`
 *  - [BindingColumnObject] — `kód_artiklu: { target: KOD_ZBOZI }` etc.
 */
sealed interface BindingColumnValue {
    val source: SourceLocation
}

data class BindingColumnBareId(
    val id: Reference,
    override val source: SourceLocation,
) : BindingColumnValue

data class BindingColumnObject(
    val obj: PropertyValue.ObjectValue,
    override val source: SourceLocation,
) : BindingColumnValue

/** File-level `model <code> [schema <id>]` directive. */
data class ModelDirective(
    val modelCode: String,
    val schema: String? = null,
    /** v4.4 — unit-level `model lexicon locale <id>` (permissive; semantics enforces lexicon-only). */
    val locale: String? = null,
    val source: SourceLocation,
)

/** File-level `import <qualifiedName> [.*]` statement. */
data class ImportStatement(
    val target: String,
    val wildcard: Boolean,
    val source: SourceLocation,
)

/** File-level `package <qualifiedName>` declaration. Mirrors [ImportStatement]'s shape. */
data class PackageDeclaration(
    val name: String,
    val source: SourceLocation,
)

/**
 * ANTLR-style source span (D4 superset).
 *  - `line` / `endLine` are 1-indexed (match ANTLR `token.line`).
 *  - `column` / `endColumn` are 0-indexed (match ANTLR `charPositionInLine`);
 *    `endColumn` is one past the last character.
 *  - `offsetStart` / `offsetEnd` are 0-indexed byte offsets; `offsetEnd` exclusive.
 *
 * LSP-style consumers subtract 1 from `line` / `endLine`.
 */
data class SourceLocation(
    val file: String,
    val line: Int,
    val column: Int,
    val endLine: Int,
    val endColumn: Int,
    val offsetStart: Int,
    val offsetEnd: Int,
) {
    override fun toString(): String = "$file:$line:$column"

    companion object {
        val UNKNOWN = SourceLocation("<unknown>", -1, -1, -1, -1, -1, -1)
    }
}
