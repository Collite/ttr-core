// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.metadata.model

import org.tatrman.ttr.semantics.semanticsblock.ResolvedAttributeSemantics
import org.tatrman.ttr.semantics.semanticsblock.ResolvedEntitySemantics
import java.time.Instant

/**
 * Top-level immutable snapshot of the metadata model.
 *
 * Constructed by the [org.tatrman.ttr.metadata.reconcile.ModelReconciler] from one or
 * more [org.tatrman.ttr.metadata.source.SourceSnapshot]s and held by
 * [org.tatrman.ttr.metadata.registry.MetadataRegistry] under an `AtomicReference`.
 * Consumers of a snapshot must not mutate it; mutations land via a fresh
 * snapshot swap.
 */
data class Model(
    val descriptor: ModelDescriptor,
    val version: ModelVersion,
    val schemas: Map<String, SchemaContents>,
    val mappings: List<Mapping>,
    val queries: Map<QualifiedName, Query>,
    /**
     * v2.2 — drill maps loaded from `def drill_map` blocks. Keyed by qname for
     * deterministic ordering; consumers iterating to populate proto bundles
     * typically just call `drillMaps.values`.
     */
    val drillMaps: Map<QualifiedName, DrillMap> = emptyMap(),
    /**
     * Golem P4 S4.2 — subject areas loaded from `def area` blocks in `.ttrm`
     * files. Keyed by the bare area name (`accounting`). Surfaced by the
     * `ResolveArea` RPC so a Golem Shem can resolve its `areas: [...]` list to
     * the concrete package set it must pull from the metadata service.
     */
    val areas: Map<String, AreaRecord> = emptyMap(),
) {
    /** Resolve a subject area by its bare name, or null if unknown. */
    fun areaByName(name: String): AreaRecord? = areas[name]

    /** Every object in the model, indexed by its qualified name. */
    fun objectByQname(): Map<QualifiedName, ModelObject> {
        val out = mutableMapOf<QualifiedName, ModelObject>()
        for ((_, schema) in schemas) {
            schema.objects().forEach { obj -> out[obj.qname] = obj }
        }
        for (m in mappings) out[m.qname] = m
        for (q in queries.values) out[q.qname] = q
        for (d in drillMaps.values) out[d.qname] = d
        return out
    }
}

/**
 * Golem P4 S4.2 — a `def area` block loaded from a `.ttrm` area file. Carries the
 * area's display description, tags, and the package set the area spans. Referenced
 * package names are kept verbatim (not validated against the model here — the
 * `GetModel` call validates packages later).
 */
data class AreaRecord(
    val name: String,
    val description: String,
    /** NLS-P10 — the localised `description: { … }` form; empty when the plain form was authored. */
    val descriptionLocalized: LocalizedText = LocalizedText.EMPTY,
    val tags: List<String>,
    val packages: List<String>,
)

/** Descriptive identity of a model bundle. */
data class ModelDescriptor(
    val id: String,
    val name: String,
    val description: String = "",
    /** NLS-P10 — the localised `description: { … }` form; empty when the plain form was authored. */
    val descriptionLocalized: LocalizedText = LocalizedText.EMPTY,
    val tags: List<String> = emptyList(),
)

/** Snapshot version stamp — opaque token, monotonic across swaps. */
data class ModelVersion(
    val value: String,
    val swappedAt: Instant,
)

/** Common surface for every queryable object in the model. */
sealed interface ModelObject {
    val internalId: String
    val qname: QualifiedName
    val kind: String
    val description: String

    /**
     * NLS-P10 (⚑GXP-D7, grammar 0.13) — the localised `description: { en: …, cs: … }`
     * form, carried beside the plain [description] rather than folded into it. At most
     * one of the two is ever populated (an author writes one form or the other).
     *
     * Readers pick a locale HERE, at the edge: Veles resolves
     * `meta.v1.ObjectDescriptor.description` through the D7 fallback chain
     * (requested locale → plain form → `en` → first entry by language code → "").
     * The wire is unchanged — `description` stays a single `string`.
     */
    val descriptionLocalized: LocalizedText

    val tags: List<String>
    val sourceFile: String
    val binding: Binding
}

/** Bound state of a model object — Round 4's first-class "synthetic" concept. */
sealed interface Binding {
    val reason: String

    data object BoundReal : Binding {
        override val reason: String = ""
    }

    data class BoundSynthetic(
        override val reason: String,
    ) : Binding

    data class Unbound(
        override val reason: String,
    ) : Binding
}

// ----- Schema layer -----

// M2: WorldSchema joins SchemaContents (contracts §2) — the `world` model tier
// (DbSchema / ErSchema / CncSchema today).
sealed interface SchemaContents {
    val schemaCode: String

    fun objects(): Sequence<ModelObject>
}

data class DbSchema(
    val namespace: String = "dbo",
    val tables: Map<QualifiedName, DbTable> = emptyMap(),
    val views: Map<QualifiedName, DbView> = emptyMap(),
    val procedures: Map<QualifiedName, DbProcedure> = emptyMap(),
    val foreignKeys: Map<QualifiedName, DbForeignKey> = emptyMap(),
) : SchemaContents {
    override val schemaCode: String = "db"

    override fun objects(): Sequence<ModelObject> =
        sequence {
            yieldAll(tables.values)
            tables.values.forEach { yieldAll(it.columns) }
            yieldAll(views.values)
            views.values.forEach { yieldAll(it.columns) }
            yieldAll(procedures.values)
            yieldAll(foreignKeys.values)
        }
}

data class ErSchema(
    val entities: Map<QualifiedName, Entity> = emptyMap(),
    val relations: Map<QualifiedName, Relation> = emptyMap(),
) : SchemaContents {
    override val schemaCode: String = "er"

    override fun objects(): Sequence<ModelObject> =
        sequence {
            yieldAll(entities.values)
            entities.values.forEach { yieldAll(it.attributes) }
            yieldAll(relations.values)
        }
}

/**
 * Phase 2.2 — conceptual schema (`cnc`). Currently holds [Role] objects;
 * future work (v1.5+) extends with traits / taxonomies.
 */
data class CncSchema(
    val roles: Map<QualifiedName, Role> = emptyMap(),
) : SchemaContents {
    override val schemaCode: String = "cnc"

    override fun objects(): Sequence<ModelObject> = sequence { yieldAll(roles.values) }
}

// ----- DB types -----

data class DbTable(
    override val internalId: String,
    override val qname: QualifiedName,
    override val description: String = "",
    override val descriptionLocalized: LocalizedText = LocalizedText.EMPTY,
    override val tags: List<String> = emptyList(),
    override val sourceFile: String = "",
    override val binding: Binding = Binding.BoundReal,
    val columns: List<DbColumn> = emptyList(),
    val primaryKey: List<String> = emptyList(),
    /**
     * Grounding Phase 1 (grammar 4.2) — the resolved `semantics { kind: … }` on this
     * table: one of `period_table` / `calendar` / `poi` / `fx_rate`, or null when the
     * table declares no block (or the block carried diagnostics — degrade, don't fail).
     * Populated by the source loader from ttr-semantics' `ResolvedEntitySemantics.kind`.
     */
    val semanticsKind: String? = null,
    /**
     * EN-P1 (grammar 0.10) — the `management:` declaration (write governance, FO §11/§12): `data`
     * or `canon`. Null when absent ⇒ the default posture is `data` (contract §2). Populated from the
     * parsed [org.tatrman.ttr.parser.model.TableDef.management].
     */
    val managementMode: String? = null,
    /**
     * EN-P1 (grammar 0.10) — the `changeSemantics:` declaration (write-behaviour axis, FO §9): mode
     * (`scd1`/`scd2`/`ledger`) + the declared role→column map. Null when absent ⇒ optimistic row
     * versioning (§10). The writability classifier + the entry lowering read this.
     */
    val changeSemantics: TableChangeSemantics? = null,
    /**
     * MS (vocabulary v3) — the resolved MENTION facet: which member carries this
     * table when a human refers to it by name, by code, or as a value
     * (`semantics { name: · code: · measures: [...] }`, contracts §1.1). The WHOLE
     * resolved block, so a consumer pattern-matches the part it needs rather than
     * this class growing a field per key. Null when no block is declared, or when the
     * block carried diagnostics (degrade, don't fail) — including the MS-D2 legacy
     * disagreement, where refusing to pick a winner is the point.
     *
     * [semanticsKind] stays as it is: the discovery-accelerator string, and the same
     * value as `mentionSemantics?.kind`.
     */
    val mentionSemantics: ResolvedEntitySemantics? = null,
) : ModelObject {
    override val kind: String = "table"
}

/**
 * EN-P1 (grammar 0.10) — a table's resolved `changeSemantics` declaration. [roleColumns] maps a
 * declared role name (`validFrom`/`validTo`/`reversalLink`) to the column it names — md-declared,
 * never name-sniffed (contract §2). Vocabulary/role legality is validated in ttr-semantics; this
 * carrier is the surfaced result.
 */
data class TableChangeSemantics(
    val mode: String,
    val roleColumns: Map<String, String> = emptyMap(),
)

data class DbView(
    override val internalId: String,
    override val qname: QualifiedName,
    override val description: String = "",
    override val descriptionLocalized: LocalizedText = LocalizedText.EMPTY,
    override val tags: List<String> = emptyList(),
    override val sourceFile: String = "",
    override val binding: Binding = Binding.BoundReal,
    val columns: List<DbColumn> = emptyList(),
    val definitionSql: String = "",
) : ModelObject {
    override val kind: String = "view"
}

data class DbColumn(
    override val internalId: String,
    override val qname: QualifiedName,
    override val description: String = "",
    override val descriptionLocalized: LocalizedText = LocalizedText.EMPTY,
    override val tags: List<String> = emptyList(),
    override val sourceFile: String = "",
    override val binding: Binding = Binding.BoundReal,
    val table: QualifiedName,
    val dataType: String,
    val nullable: Boolean = true,
    val isPrimaryKey: Boolean = false,
    val isForeignKey: Boolean = false,
    val search: SearchHints = SearchHints.EMPTY,
    /**
     * Grounding Phase 1 (grammar 4.2) — the resolved `semantics { role: … }` on this
     * column (role + resolved `period:`/`currency:` refs + `code_format:`), or null
     * when absent / diagnostics-carrying. From ttr-semantics' `ResolvedAttributeSemantics`.
     */
    val semantics: ResolvedAttributeSemantics? = null,
) : ModelObject {
    override val kind: String = "column"
}

data class DbProcedure(
    override val internalId: String,
    override val qname: QualifiedName,
    override val description: String = "",
    override val descriptionLocalized: LocalizedText = LocalizedText.EMPTY,
    override val tags: List<String> = emptyList(),
    override val sourceFile: String = "",
    override val binding: Binding = Binding.BoundReal,
    val parameters: List<DbProcedureParameter> = emptyList(),
    val resultColumns: List<DbColumn> = emptyList(),
) : ModelObject {
    override val kind: String = "procedure"
}

data class DbProcedureParameter(
    val name: String,
    val dataType: String,
    val direction: ParameterDirection,
)

enum class ParameterDirection { IN, OUT, INOUT }

data class DbForeignKey(
    override val internalId: String,
    override val qname: QualifiedName,
    override val description: String = "",
    override val descriptionLocalized: LocalizedText = LocalizedText.EMPTY,
    override val tags: List<String> = emptyList(),
    override val sourceFile: String = "",
    override val binding: Binding = Binding.BoundReal,
    val fromColumns: List<QualifiedName>,
    val toColumns: List<QualifiedName>,
) : ModelObject {
    override val kind: String = "foreign_key"
}

// ----- ER types -----

data class Entity(
    override val internalId: String,
    override val qname: QualifiedName,
    override val description: String = "",
    override val descriptionLocalized: LocalizedText = LocalizedText.EMPTY,
    override val tags: List<String> = emptyList(),
    override val sourceFile: String = "",
    override val binding: Binding = Binding.BoundReal,
    val labelPlural: String = "",
    val nameAttribute: String = "",
    val codeAttribute: String = "",
    val aliases: List<String> = emptyList(),
    val attributes: List<Attribute> = emptyList(),
    /** Phase 2.2 (G5) — localised entity name. Empty when absent. */
    val displayLabel: LocalizedText = LocalizedText.EMPTY,
    /** Search feature — search hints aggregated from the `search { ... }` block. */
    val search: SearchHints = SearchHints.EMPTY,
    /**
     * Grounding Phase 1 (grammar 4.2) — the resolved `semantics { kind: … }` on this
     * entity: one of `period_table` / `calendar` / `poi` / `fx_rate`, or null when the
     * entity declares no block (or the block carried diagnostics — degrade, don't fail).
     * Populated by the source loader from ttr-semantics' `ResolvedEntitySemantics.kind`.
     */
    val semanticsKind: String? = null,
    /**
     * MS (vocabulary v3) — the resolved MENTION facet: which member carries this
     * entity when a human refers to it by name, by code, or as a value
     * (`semantics { name: · code: · measures: [...] }`, contracts §1.1). The WHOLE
     * resolved block, so a consumer pattern-matches the part it needs rather than
     * this class growing a field per key. Null when no block is declared, or when the
     * block carried diagnostics (degrade, don't fail) — including the MS-D2 legacy
     * disagreement, where refusing to pick a winner is the point.
     *
     * [semanticsKind] stays as it is: the discovery-accelerator string, and the same
     * value as `mentionSemantics?.kind`.
     */
    val mentionSemantics: ResolvedEntitySemantics? = null,
) : ModelObject {
    override val kind: String = "entity"
}

data class Attribute(
    override val internalId: String,
    override val qname: QualifiedName,
    override val description: String = "",
    override val descriptionLocalized: LocalizedText = LocalizedText.EMPTY,
    override val tags: List<String> = emptyList(),
    override val sourceFile: String = "",
    override val binding: Binding = Binding.BoundReal,
    val entity: QualifiedName,
    val type: String,
    val isKey: Boolean = false,
    val nullable: Boolean = true,
    /**
     * EN-P1.2 — true when this attribute is derived by an aggregation (`aggregation:` on the def). The
     * writability classifier reads it: an entity with an aggregated attribute is not writable
     * (`whyNot.code = AGGREGATION`). Additive; false when absent.
     */
    val aggregated: Boolean = false,
    /** Phase 2.2 (G5) — localised attribute name. Empty when absent. */
    val displayLabel: LocalizedText = LocalizedText.EMPTY,
    /** Phase 2.2 (G4) — code → localised label, e.g. "1" → cs:"Aktivní". */
    val valueLabels: Map<String, LocalizedText> = emptyMap(),
    /** Search feature — search hints aggregated from the `search { ... }` block. */
    val search: SearchHints = SearchHints.EMPTY,
    /**
     * Grounding Phase 1 (grammar 4.2) — the resolved `semantics { role: … }` on this
     * attribute (role + resolved `period:`/`currency:` refs + `code_format:`), or null
     * when absent / diagnostics-carrying. From ttr-semantics' `ResolvedAttributeSemantics`.
     */
    val semantics: ResolvedAttributeSemantics? = null,
) : ModelObject {
    override val kind: String = "attribute"
}

/**
 * Phase 2.2 — localised text carrier in the metadata model layer (no proto
 * dependency in this package). Converted to/from the proto `LocalizedString`
 * by the gRPC layer.
 */
data class LocalizedText(
    val byLanguage: Map<String, String> = emptyMap(),
) {
    val isEmpty: Boolean get() = byLanguage.isEmpty()

    companion object {
        val EMPTY: LocalizedText = LocalizedText(emptyMap())
    }
}

/**
 * Search feature — internal model carrier for `search { ... }` content.
 * Mirrors the parser-side `SearchHintsValue` and the proto `SearchHints`
 * but lives in this layer with no proto dependency. Converted by the source
 * loader (in) and the gRPC layer (out).
 */
data class LocalizedTextList(
    val byLanguage: Map<String, List<String>> = emptyMap(),
) {
    val isEmpty: Boolean get() = byLanguage.isEmpty()

    companion object {
        val EMPTY: LocalizedTextList = LocalizedTextList(emptyMap())
    }
}

data class SearchHints(
    /**
     * The legacy inclusion hint (`search { searchable }`). Since MV it decides nothing about
     * indexing: a bare `searchable` is not a member vocabulary.
     */
    val searchable: Boolean = false,
    /**
     * MV (member-vocabulary contracts §2.1) — "does this carrier have a member vocabulary?": the
     * values it takes are indexed for matching. The question every consumer of this model asks
     * (`meta.v1.SearchHints.indexed`, `ListObjects(indexed_only=true)`, Veles'
     * `ListMemberVocabularies`, the compiled lexicon's `memberVocabulary` facet).
     *
     * Derived at ONE boundary (`Source.kt`'s `toSearchHints`): an authored `method:` of any kind —
     * `EXACT` included — or the deprecated `fuzzy: true`. A bare `searchable` stays a hint.
     *
     * Takes the constructor position the pre-MV `fuzzy` held, because that bit always meant
     * "indexed": a positional caller keeps its meaning, a named `fuzzy =` caller fails to compile
     * and has to say which of the two facts it meant.
     */
    val indexed: Boolean = false,
    val keywords: LocalizedTextList = LocalizedTextList.EMPTY,
    val patterns: List<String> = emptyList(),
    val descriptions: LocalizedTextList = LocalizedTextList.EMPTY,
    val examples: List<String> = emptyList(),
    val aliases: List<String> = emptyList(),
    /**
     * How this carrier's values are matched — `EXACT`, `TYPOS(n)`, `TOKENS` — verbatim as authored
     * (grammar 0.12, RV-32), or the deprecated `fuzzy: true` spelled as the `TYPOS(1)` grammar 0.12
     * maps it to. Null when the carrier declared none; an [indexed] carrier with no method is
     * matched `EXACT` (contracts §2.1). Kept as text rather than parsed: this model is a carrier,
     * and the RV-32 vocabulary is `ttr-semantics`' to own.
     *
     * Appended, so positional construction stays source-compatible for published-artifact consumers.
     */
    val matchMethod: String? = null,
    /**
     * Whether the deprecated `fuzzy` keyword was authored AT ALL, as opposed to defaulting. Mirrors
     * the parser's `SearchHintsValue.fuzzyAuthored` for the same reason it exists there: under 0.12
     * an authored `fuzzy: false` means EXACT, which is not the same as saying nothing.
     */
    val fuzzyAuthored: Boolean = false,
) {
    /**
     * The pre-MV name of a bit that meant "indexed" — now what its name says: the match method is
     * partial (TYPOS/TOKENS). Kept one bundle release so readers compile; for every carrier that
     * does not author both a method and the deprecated boolean it reads exactly as it did before.
     */
    @Deprecated(
        "MV: ask the question you mean — `indexed` (has a member vocabulary) or " +
            "`MatchMethods.isPartial(matchMethod)` (matched partially)",
        ReplaceWith("MatchMethods.isPartial(matchMethod)"),
    )
    val fuzzy: Boolean get() = MatchMethods.isPartial(matchMethod)

    val isEmpty: Boolean
        get() =
            !searchable &&
                !indexed &&
                keywords.isEmpty &&
                patterns.isEmpty() &&
                descriptions.isEmpty &&
                examples.isEmpty() &&
                aliases.isEmpty() &&
                matchMethod == null

    companion object {
        val EMPTY: SearchHints = SearchHints()

        @Deprecated("MV: renamed — it no longer decides indexing", ReplaceWith("MatchMethods.isPartial(method)"))
        fun methodIsFuzzy(method: String?): Boolean = MatchMethods.isPartial(method)
    }
}

/** RV-32 match-method questions over the verbatim [SearchHints.matchMethod] text. */
object MatchMethods {
    /** What an [SearchHints.indexed] carrier with no authored method is matched with. */
    const val DEFAULT: String = "EXACT"

    /**
     * Is this method PARTIAL — does it admit values that are not the query verbatim? `TYPOS(n)` by
     * definition, `TOKENS` because it matches words the value does not carry in that order. `EXACT`
     * is not, and neither is an unrecognized method: it is a diagnostic (`ttr/unknown-match-method`),
     * and widening a match on a typo would be the wrong way to find out.
     */
    fun isPartial(method: String?): Boolean {
        val name = method?.substringBefore('(')?.trim()?.uppercase() ?: return false
        return name == "TYPOS" || name == "TOKENS"
    }
}

data class Relation(
    override val internalId: String,
    override val qname: QualifiedName,
    override val description: String = "",
    override val descriptionLocalized: LocalizedText = LocalizedText.EMPTY,
    override val tags: List<String> = emptyList(),
    override val sourceFile: String = "",
    override val binding: Binding = Binding.BoundReal,
    val fromEntity: QualifiedName,
    val toEntity: QualifiedName,
    val cardinality: Cardinality,
    val joinPairs: List<AttributeJoinPair> = emptyList(),
) : ModelObject {
    override val kind: String = "relation"
}

data class Cardinality(
    val fromMin: Int,
    val fromMax: Int,
    val toMin: Int,
    val toMax: Int,
)

data class AttributeJoinPair(
    val fromAttr: QualifiedName,
    val toAttr: QualifiedName,
)

// ----- Mapping types -----

sealed interface Mapping : ModelObject {
    /**
     * v2.1 — distinguishes an explicit `def er2db_*` declaration from a symbol
     * synthesized from an inline `mapping:` property on a host
     * entity / attribute / relation. Used by the duplicate-mapping validator;
     * lookups (foreign-key resolution etc.) treat both the same.
     */
    val mappingSource: MappingSource
}

/** v2.1 — origin of an `Er2Db*Mapping` entry. */
sealed interface MappingSource {
    /** Materialised from a `def er2db_*` declaration. */
    data object Explicit : MappingSource

    /**
     * Synthesised from an inline `mapping:` block on an entity / attribute /
     * relation. [hostKind] is the host's def kind: `"entity"`, `"attribute"`,
     * or `"relation"`.
     */
    data class Inline(
        val hostKind: String,
    ) : MappingSource
}

data class Er2DbEntityMapping(
    override val internalId: String,
    override val qname: QualifiedName,
    override val description: String = "",
    override val descriptionLocalized: LocalizedText = LocalizedText.EMPTY,
    override val tags: List<String> = emptyList(),
    override val sourceFile: String = "",
    override val binding: Binding = Binding.BoundReal,
    override val mappingSource: MappingSource = MappingSource.Explicit,
    val entity: QualifiedName,
    val target: MappingTarget,
) : Mapping {
    override val kind: String = "er2db_entity_mapping"
}

data class Er2DbAttributeMapping(
    override val internalId: String,
    override val qname: QualifiedName,
    override val description: String = "",
    override val descriptionLocalized: LocalizedText = LocalizedText.EMPTY,
    override val tags: List<String> = emptyList(),
    override val sourceFile: String = "",
    override val binding: Binding = Binding.BoundReal,
    override val mappingSource: MappingSource = MappingSource.Explicit,
    val attribute: QualifiedName,
    val target: AttributeMappingTarget,
) : Mapping {
    override val kind: String = "er2db_attribute_mapping"
}

data class Er2DbRelationMapping(
    override val internalId: String,
    override val qname: QualifiedName,
    override val description: String = "",
    override val descriptionLocalized: LocalizedText = LocalizedText.EMPTY,
    override val tags: List<String> = emptyList(),
    override val sourceFile: String = "",
    override val binding: Binding = Binding.BoundReal,
    override val mappingSource: MappingSource = MappingSource.Explicit,
    val relation: QualifiedName,
    val foreignKey: QualifiedName,
) : Mapping {
    override val kind: String = "er2db_relation_mapping"
}

/** Phase 2.2 — conceptual role assigned to an ER entity. */
data class Role(
    override val internalId: String,
    override val qname: QualifiedName,
    override val description: String = "",
    override val descriptionLocalized: LocalizedText = LocalizedText.EMPTY,
    override val tags: List<String> = emptyList(),
    override val sourceFile: String = "",
    override val binding: Binding = Binding.BoundReal,
    val label: LocalizedText = LocalizedText.EMPTY,
    /** Search feature — search hints aggregated from the `search { ... }` block. */
    val search: SearchHints = SearchHints.EMPTY,
) : ModelObject {
    override val kind: String = "role"
}

/** Phase 2.2 — `er.entity.X plays cnc.role.Y` mapping. */
data class Er2CncRoleMapping(
    override val internalId: String,
    override val qname: QualifiedName,
    override val description: String = "",
    override val descriptionLocalized: LocalizedText = LocalizedText.EMPTY,
    override val tags: List<String> = emptyList(),
    override val sourceFile: String = "",
    override val binding: Binding = Binding.BoundReal,
    override val mappingSource: MappingSource = MappingSource.Explicit,
    val entity: QualifiedName,
    val role: QualifiedName,
) : Mapping {
    override val kind: String = "er2cnc_role_mapping"
}

sealed interface MappingTarget {
    data class Table(
        val qname: QualifiedName,
    ) : MappingTarget

    data class View(
        val qname: QualifiedName,
    ) : MappingTarget

    data class SqlQuery(
        val qname: QualifiedName,
    ) : MappingTarget
}

sealed interface AttributeMappingTarget {
    data class Column(
        val qname: QualifiedName,
    ) : AttributeMappingTarget

    /** Free-form expression source — preserved for round-trip but unparsed here. */
    data class Expression(
        val raw: String,
    ) : AttributeMappingTarget
}

// ----- Query type -----

data class Query(
    override val internalId: String,
    override val qname: QualifiedName,
    override val description: String = "",
    override val descriptionLocalized: LocalizedText = LocalizedText.EMPTY,
    override val tags: List<String> = emptyList(),
    override val sourceFile: String = "",
    override val binding: Binding = Binding.BoundReal,
    val sourceLanguage: String,
    val sourceText: String,
    /**
     * Source SQL dialect resolved from the embedded tagged block's tag
     * (`tsql` / `postgres` / `duckdb` / …); `null` for a bare `sql` tag (which
     * defers to the project default) or a non-SQL language. Read from the
     * structured node, not the soft-deprecated `language:` property.
     */
    val dialect: String? = null,
    val parameters: List<QueryParameterDef> = emptyList(),
    /**
     * Mutable async-parse status. The query-parse worker (Phase 1.2 Section F
     * follow-up) updates this in place; readers are expected to capture a
     * single read at the start of a request and use that consistently.
     */
    val parseStatus: ParseStatus = ParseStatus.ParsePending,
    /** Search feature — search hints aggregated from the `search { ... }` block. */
    val search: SearchHints = SearchHints.EMPTY,
    /**
     * ai-platform v2.1 (PP-17) — the query is executed **verbatim**, without parsing,
     * translation or security validation (the "raw lane"). Carrier for a host dialect: it is
     * declared in ai-platform's YAML pattern files, never in TTR-M — the TTR grammar is
     * deliberately untouched, so a query loaded from (or written back to) `.ttr` always leaves
     * this at its default. Appended last with a default so existing positional construction
     * stays source-compatible.
     *
     * Defaults to `false`: exemption from validation is only ever explicit. A parse is still
     * attempted for exempt queries, but as a **diagnostic** — it does not gate the load
     * (`W_SKIPSEC_PARSE_FAILED`); the gating itself lives in the consuming platform.
     */
    val skipSecurity: Boolean = false,
) : ModelObject {
    override val kind: String = "query"
}

data class QueryParameterDef(
    val name: String,
    val type: String,
    val label: String = "",
    /**
     * ai-platform PB — the model attributes (`entity.attribute`) whose resolved value fills this
     * parameter. Carrier for a host dialect, exactly like [Query.skipSecurity]: declared in
     * ai-platform's YAML pattern files, never in TTR-M — the grammar is untouched, so a query
     * loaded from (or written back to) `.ttr` always leaves this at its default. Appended last
     * with a default so positional construction stays source-compatible.
     */
    val binds: List<String> = emptyList(),
)

sealed interface ParseStatus {
    data object ParsePending : ParseStatus

    data class ParseSuccess(
        val canonicalFormProtoBytes: ByteArray,
    ) : ParseStatus {
        override fun equals(other: Any?): Boolean =
            other is ParseSuccess && canonicalFormProtoBytes.contentEquals(other.canonicalFormProtoBytes)

        override fun hashCode(): Int = canonicalFormProtoBytes.contentHashCode()
    }

    data class ParseFailure(
        val message: String,
        val location: String = "",
    ) : ParseStatus
}

// ----- v2.2 — drill maps -----

/**
 * v2.2 — `def drill_map { from, to, args, display?, override? }`. Lives in the
 * `query.drill.*` namespace (contracts §3). Always carries a non-null `display`
 * after load: the AST→model mapper supplies a default of "Detail" / "Detail
 * <to.description>" when the source TTR omitted one (OQ-03.A).
 */
data class DrillMap(
    override val internalId: String,
    override val qname: QualifiedName,
    override val description: String = "",
    override val descriptionLocalized: LocalizedText = LocalizedText.EMPTY,
    override val tags: List<String> = emptyList(),
    override val sourceFile: String = "",
    override val binding: Binding = Binding.BoundReal,
    /** Reference to a `def query` pattern. Resolved by the loader; may be Unbound. */
    val fromPattern: QualifiedName,
    /** Reference to a `def query` pattern. Resolved by the loader; may be Unbound. */
    val toPattern: QualifiedName,
    /** target_param_name -> source column name or quoted literal. */
    val argMapping: Map<String, String> = emptyMap(),
    /** True for explicit `def drill_map`; false for auto-derived (future). */
    val explicit: Boolean = true,
    /** If explicit && true, suppresses auto-derived drills with the same target. */
    val overrideAuto: Boolean = false,
    val display: LocalizedText = LocalizedText.EMPTY,
) : ModelObject {
    override val kind: String = "drill_map"
}
