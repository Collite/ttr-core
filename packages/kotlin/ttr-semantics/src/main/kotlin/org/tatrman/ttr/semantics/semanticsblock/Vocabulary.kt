// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.semantics.semanticsblock

/**
 * Grounding Phase 1 (grammar 4.2) — the closed `semantics { … }` vocabulary.
 *
 * NORMATIVE table, mirroring `packages/semantics/src/semantics-block/vocabulary.ts`
 * and `docs/features/semantics-block/README.md` §Vocabulary exactly. It is the
 * cross-repo sync key with ai-platform's closed proto enums: the vocabulary here
 * and the proto enums version TOGETHER via [SEMANTICS_VOCABULARY_VERSION].
 */
object Vocabulary {
    /**
     * Cross-repo sync key — bumps in lock-step with ai-platform's proto enums. v2 (MD dot-path S5C-B.4)
     * adds the journal-role family (`valid_flag`, `version`, `authored_by`, `written_at`) — the closed
     * six-role set of contracts §12 R30; `valid_from`/`valid_to` were already present and are reused. The
     * shared `AttributeSemanticRole` proto-enum promotion (ids 60–63) is the cross-repo half, coordinated
     * at the metadata-proto release (ai-platform §4) — out of this repo.
     *
     * v3 (MS — mention semantics) adds the MENTION facet. Everything the table held until now answers
     * "what computation grounds on this column" (a date to filter on, a coordinate to measure from); v3
     * adds the orthogonal question "how do humans refer to this entity" — as a name, a code, or a value.
     * That facet is declared ENTITY-side (`semantics { name: · code: · measures: [...] }`, contracts §1.1),
     * not as a role: `role:` is single-valued, and one column is routinely both an amount to convert and
     * the measure people ask for. So the role table gains no members — it gains [RoleSpec.facet] and
     * [RoleSpec.family], which is what forces a future role to say which question it answers. Per-measure
     * aggregation rides the `measures:` list item and is NOT the def-level `aggregation:` property (EN-P1.2
     * derived attributes) nor md's measure `aggregation:` — three surfaces, deliberately kept apart.
     *
     * What v3 spans, end to end: the three entity mention keys (this table + `SemanticsAnalyzer`), the
     * resolved [MeasureRef] shape, the [MentionKinds] derivation table, and the lexicon archive's
     * per-ref `targets` map that carries its output to the resolver (`ttr-lexicon-compiled/v3`).
     *
     * Kept in lock-step with the TS twin (`packages/semantics/src/semantics-block/vocabulary.ts`), which
     * `VocabularyParitySpec` asserts mechanically; the `meta.v1` proto follows in MS-P2 (additive:
     * `EntitySemantics.measures`, `AttributeSemantics.aggregation`).
     *
     * LP review-103 (D4) adds one more MENTION key, `code_pattern:` — a regex the code attribute's
     * values match — WITHOUT a version bump: the number moves with the closed proto ENUMS, and a
     * free-text key adds no enum member. The lexicon archive's `TargetFacts.codeFormat` is its only
     * channel downstream; `meta.v1` does not carry it.
     */
    const val SEMANTICS_VOCABULARY_VERSION: Int = 3

    /** Entity/table kinds (`kind:`). */
    val ENTITY_KINDS: List<String> = listOf("period_table", "calendar", "poi", "fx_rate")

    /** One extra key (besides `role`) a role accepts, with its reference kind. */
    data class ExtraKey(
        val key: String,
        val kind: RefKind,
        val required: Boolean,
    )

    enum class RefKind { EntityRef, AttrRef, StringLit }

    /** The type-family a role's attribute/column must declare. */
    enum class TypeConstraint { Date, Text, Numeric }

    /**
     * Which question a role answers. Only `grounding` exists today — the mention facet is declared
     * entity-side, not as a role (see the v3 note on [SEMANTICS_VOCABULARY_VERSION]). String-typed, like
     * the rest of the Kotlin side; the TS twin closes it as a union.
     */
    const val FACET_GROUNDING: String = "grounding"

    /** The families a grounding role belongs to — README §Vocabulary groups the table this way. */
    val ROLE_FAMILIES: List<String> = listOf("dates", "geo", "finance", "journal")

    data class RoleSpec(
        val extraKeys: List<ExtraKey> = emptyList(),
        val typeConstraint: TypeConstraint? = null,
        /** Required, not optional: a new role has to declare which question it answers. */
        val facet: String,
        /** Required, for the same reason as [facet]. One of [ROLE_FAMILIES]. */
        val family: String,
    )

    /** Attribute/column roles (`role:`) → their extra keys + type constraint. */
    val ATTRIBUTE_ROLES: Map<String, RoleSpec> =
        linkedMapOf(
            "period_start" to
                RoleSpec(typeConstraint = TypeConstraint.Date, facet = FACET_GROUNDING, family = "dates"),
            "period_end" to
                RoleSpec(typeConstraint = TypeConstraint.Date, facet = FACET_GROUNDING, family = "dates"),
            "period_code" to
                RoleSpec(
                    extraKeys = listOf(ExtraKey("code_format", RefKind.StringLit, required = false)),
                    typeConstraint = TypeConstraint.Text,
                    facet = FACET_GROUNDING,
                    family = "dates",
                ),
            "event_date" to
                RoleSpec(
                    extraKeys = listOf(ExtraKey("period", RefKind.EntityRef, required = false)),
                    typeConstraint = TypeConstraint.Date,
                    facet = FACET_GROUNDING,
                    family = "dates",
                ),
            "document_date" to
                RoleSpec(
                    extraKeys = listOf(ExtraKey("period", RefKind.EntityRef, required = false)),
                    typeConstraint = TypeConstraint.Date,
                    facet = FACET_GROUNDING,
                    family = "dates",
                ),
            "posting_date" to
                RoleSpec(
                    extraKeys = listOf(ExtraKey("period", RefKind.EntityRef, required = false)),
                    typeConstraint = TypeConstraint.Date,
                    facet = FACET_GROUNDING,
                    family = "dates",
                ),
            "due_date" to
                RoleSpec(
                    extraKeys = listOf(ExtraKey("period", RefKind.EntityRef, required = false)),
                    typeConstraint = TypeConstraint.Date,
                    facet = FACET_GROUNDING,
                    family = "dates",
                ),
            "valid_from" to
                RoleSpec(typeConstraint = TypeConstraint.Date, facet = FACET_GROUNDING, family = "dates"),
            "valid_to" to
                RoleSpec(typeConstraint = TypeConstraint.Date, facet = FACET_GROUNDING, family = "dates"),
            // Journal-role family (S5C-B.4, contracts §12 R30 · MDS8) — technical columns of a journaled
            // cubelet's backing table. `valid_flag` is boolean (no numeric/text/date family — left
            // unconstrained); `version` int, `authored_by` varchar, `written_at` timestamp (date family).
            "valid_flag" to RoleSpec(facet = FACET_GROUNDING, family = "journal"),
            "version" to
                RoleSpec(typeConstraint = TypeConstraint.Numeric, facet = FACET_GROUNDING, family = "journal"),
            "authored_by" to
                RoleSpec(typeConstraint = TypeConstraint.Text, facet = FACET_GROUNDING, family = "journal"),
            "written_at" to
                RoleSpec(typeConstraint = TypeConstraint.Date, facet = FACET_GROUNDING, family = "journal"),
            "calendar_date" to
                RoleSpec(typeConstraint = TypeConstraint.Date, facet = FACET_GROUNDING, family = "dates"),
            "geo_lat" to
                RoleSpec(typeConstraint = TypeConstraint.Numeric, facet = FACET_GROUNDING, family = "geo"),
            "geo_lon" to
                RoleSpec(typeConstraint = TypeConstraint.Numeric, facet = FACET_GROUNDING, family = "geo"),
            "geo_point" to
                RoleSpec(typeConstraint = TypeConstraint.Text, facet = FACET_GROUNDING, family = "geo"),
            "amount" to
                RoleSpec(
                    extraKeys = listOf(ExtraKey("currency", RefKind.AttrRef, required = false)),
                    typeConstraint = TypeConstraint.Numeric,
                    facet = FACET_GROUNDING,
                    family = "finance",
                ),
            "amount_domestic" to
                RoleSpec(typeConstraint = TypeConstraint.Numeric, facet = FACET_GROUNDING, family = "finance"),
            "currency_code" to
                RoleSpec(typeConstraint = TypeConstraint.Text, facet = FACET_GROUNDING, family = "finance"),
            "fx_from_currency" to
                RoleSpec(typeConstraint = TypeConstraint.Text, facet = FACET_GROUNDING, family = "finance"),
            "fx_to_currency" to
                RoleSpec(typeConstraint = TypeConstraint.Text, facet = FACET_GROUNDING, family = "finance"),
            "fx_rate" to
                RoleSpec(typeConstraint = TypeConstraint.Numeric, facet = FACET_GROUNDING, family = "finance"),
        )

    /** A single required-role clause in a kind's completeness rule (exactly `count` times). */
    data class CompletenessClause(
        val role: String,
        val count: Int = 1,
    )

    /**
     * Per-kind completeness rules (validated on the owning entity/table). `poi` is
     * special (geo_point XOR lat/lon pair) — handled in the validator, so it maps
     * to an empty list here.
     */
    val KIND_COMPLETENESS: Map<String, List<CompletenessClause>> =
        mapOf(
            "period_table" to
                listOf(
                    CompletenessClause("period_start"),
                    CompletenessClause("period_end"),
                    CompletenessClause("period_code"),
                ),
            "calendar" to listOf(CompletenessClause("calendar_date")),
            "poi" to emptyList(),
            "fx_rate" to
                listOf(
                    CompletenessClause("fx_from_currency"),
                    CompletenessClause("fx_to_currency"),
                    CompletenessClause("fx_rate"),
                ),
        )

    val ALL_ROLES: List<String> = ATTRIBUTE_ROLES.keys.toList()

    /**
     * The keys legal on an entity/table `semantics` block.
     *
     * `kind` is the grounding facet (what this table IS); `name`/`code`/`code_pattern`/`measures` are the
     * mention facet (which attribute carries the entity when a human refers to it by name, by code, or as
     * a value — and, for the code, what a code looks like). Order is the order the README table and the
     * `SemMisplacedKeyword` message use.
     */
    val ALL_ENTITY_KEYS: List<String> = listOf("kind", "name", "code", "code_pattern", "measures")

    /**
     * The closed aggregation vocabulary for a `measures:` item.
     *
     * ⚠ This is the aggregation of a MEASURE — declared where the measure is declared,
     * `{ attribute: quantity, aggregation: avg }`. It is not the def-level `aggregation:` attribute
     * property (which says an attribute is DERIVED by an aggregation, EN-P1.2) and not md's measure
     * `aggregation:` property. Three surfaces, three meanings.
     */
    val AGGREGATIONS: List<String> = listOf("sum", "avg", "min", "max", "count", "last")

    /** A bare id in `measures:` means this. */
    const val DEFAULT_AGGREGATION: String = "sum"
}
