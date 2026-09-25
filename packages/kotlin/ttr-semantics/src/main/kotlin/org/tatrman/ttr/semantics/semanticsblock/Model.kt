// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.semantics.semanticsblock

/**
 * Grounding Phase 1 (grammar 4.2) — the typed, validated result of a `semantics`
 * block. Emitted by the validator ONLY when the block is diagnostics-free for that
 * element (degrade, don't fail). Mirrors `semantics-block/model.ts`.
 */
sealed interface ResolvedSemantics

/** A resolved cross-reference to another symbol (entity or sibling attribute). */
data class SymbolRef(
    /** The reference text as written (opaque id, e.g. `AccountingPeriod`). */
    val path: String,
    /** The resolved target's canonical qname, when resolution succeeded. */
    val qname: String? = null,
)

/**
 * One declared measure: the attribute that carries the value, and how it aggregates.
 *
 * The aggregation lives HERE, on the measure, and not on the attribute definition — a def-level
 * `aggregation:` already means "this attribute is derived by an aggregation" (EN-P1.2), which is a
 * different claim about a different thing.
 */
data class MeasureRef(
    /** Resolved against the OWNING entity's attributes (or the table's columns). */
    val attribute: SymbolRef,
    /** One of [Vocabulary.AGGREGATIONS]; `"sum"` when the item was written as a bare id. */
    val aggregation: String,
)

/**
 * The resolved `semantics` block on an entity or db table.
 *
 * [kind] is the grounding facet and is now OPTIONAL: v3 lets an entity declare only how humans refer
 * to it ([name]/[code]/[measures]) without claiming to be a period table, a calendar, a POI or an
 * fx-rate table. (The TS twin types it as the closed `EntityKind` union; `String?` here is only
 * because Kotlin has no union type — contracts §3.)
 */
data class ResolvedEntitySemantics(
    val kind: String? = null,
    /** `name:` → the attribute that carries this entity's human-readable name. */
    val name: SymbolRef? = null,
    /** `code:` → the attribute that carries its business code / identifier. */
    val code: SymbolRef? = null,
    /**
     * `measures:` → the attributes people ask for as VALUES, in declared order. The FIRST is the
     * default measure. Always present: empty means none were declared, and callers never have to
     * distinguish empty from absent.
     */
    val measures: List<MeasureRef> = emptyList(),
    /**
     * LP review-103 (D4) — `code_pattern:` → a Java regex every value of the [code] attribute
     * matches (`"^[A-P]{16}$"`), so a quoted literal can be recognised as a code even when it has
     * no digit in it. Only ever set together with [code], and only once it has compiled — the
     * analyzer refuses the block otherwise (TTR-SEM-219). Last, and defaulted, so every positional
     * construction written before it still means what it did.
     */
    val codePattern: String? = null,
) : ResolvedSemantics

/** The resolved `semantics` block on an attribute or db column. */
data class ResolvedAttributeSemantics(
    val role: String,
    /** `period:` → the period-table entity; `currency:` → sibling `currency_code`. */
    val period: SymbolRef? = null,
    val currency: SymbolRef? = null,
    /** `code_format:` on `period_code` (default `"yyyyMM"`). */
    val codeFormat: String? = null,
) : ResolvedSemantics
