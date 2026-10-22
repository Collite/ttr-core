// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.expr.catalog

import org.tatrman.ttrp.expr.CatalogId
import org.tatrman.ttrp.expr.TtrpType

/**
 * The v1 general function library (T5-c-β). Deliberately minimal (P1); grow only
 * when a later stage needs a function. Operators live here too — one uniform
 * catalogue — but their typing is handled directly by [org.tatrman.ttrp.expr.ExpressionTypechecker]
 * (comparison -> bool, arithmetic -> promoted, Kleene `and/or`); the entries below
 * mainly pin arity + return-type rules for the roster/manifest.
 *
 * Closed alias reject table ([ALIASES]) is deterministic (P2, never fuzzy): a hit
 * yields `TTRP-FN-001` with a "use <canonical>" alternative.
 */
object BuiltinCatalog : FunctionCatalog {
    override val catalogId: String = "ttrp.builtin"

    private val bool = TtrpType.Bool
    private val str = TtrpType.Str
    private val int = TtrpType.Integer
    private val dec = TtrpType.Decimal()
    private val num = TtrpType.Number
    private val dt = TtrpType.Datetime

    private val entries: List<CatalogEntry> =
        listOf(
            // Logical operators — Kleene 3VL (CUSTOM null handling).
            op("op.and", "and", listOf(bool, bool), ReturnTypeRule.Fixed(bool), NullRule.CUSTOM),
            op("op.or", "or", listOf(bool, bool), ReturnTypeRule.Fixed(bool), NullRule.CUSTOM),
            op("op.not", "not", listOf(bool), ReturnTypeRule.Fixed(bool), NullRule.CUSTOM),
            // Comparison operators — STRICT -> bool.
            op("op.eq", "eq", listOf(num, num), ReturnTypeRule.Fixed(bool), NullRule.STRICT),
            op("op.neq", "neq", listOf(num, num), ReturnTypeRule.Fixed(bool), NullRule.STRICT),
            op("op.lt", "lt", listOf(num, num), ReturnTypeRule.Fixed(bool), NullRule.STRICT),
            op("op.lte", "lte", listOf(num, num), ReturnTypeRule.Fixed(bool), NullRule.STRICT),
            op("op.gt", "gt", listOf(num, num), ReturnTypeRule.Fixed(bool), NullRule.STRICT),
            op("op.gte", "gte", listOf(num, num), ReturnTypeRule.Fixed(bool), NullRule.STRICT),
            // Arithmetic operators — STRICT, promoted numeric.
            op("op.add", "add", listOf(num, num), ReturnTypeRule.Promoted, NullRule.STRICT),
            op("op.sub", "sub", listOf(num, num), ReturnTypeRule.Promoted, NullRule.STRICT),
            op("op.mul", "mul", listOf(num, num), ReturnTypeRule.Promoted, NullRule.STRICT),
            op("op.div", "div", listOf(num, num), ReturnTypeRule.Promoted, NullRule.STRICT),
            op("op.neg", "neg", listOf(num), ReturnTypeRule.Promoted, NullRule.STRICT),
            // Scalar functions.
            // `coalesce` returns its first arg's type (SameAsArg(0)) and does NOT unify
            // its arguments in v1 — mixed-kind `coalesce(decimal, 'str')` is accepted, not
            // an error (review-001 1.2-H). Cross-arg unification is a Stage-2 refinement
            // (it needs a "unify all args" return rule); the v1 roster stays minimal (P1).
            scalar("fn.coalesce", "coalesce", listOf(str, str), ReturnTypeRule.SameAsArg(0), NullRule.CUSTOM),
            scalar("fn.substring", "substring", listOf(str, int, int), ReturnTypeRule.Fixed(str), NullRule.STRICT),
            scalar("fn.upper", "upper", listOf(str), ReturnTypeRule.Fixed(str), NullRule.STRICT),
            scalar("fn.lower", "lower", listOf(str), ReturnTypeRule.Fixed(str), NullRule.STRICT),
            scalar("fn.length", "length", listOf(str), ReturnTypeRule.Fixed(int), NullRule.STRICT),
            scalar("fn.abs", "abs", listOf(num), ReturnTypeRule.SameAsArg(0), NullRule.STRICT),
            scalar("fn.round", "round", listOf(num), ReturnTypeRule.SameAsArg(0), NullRule.STRICT),
            scalar("fn.ceil", "ceil", listOf(num), ReturnTypeRule.SameAsArg(0), NullRule.STRICT),
            // Grounding twins (grammar 4.2 / ai-platform feature-grounding-contracts.md §6 —
            // the NORMATIVE signature table; GroundingFunctionsSignatureSpec drift-guards these).
            // period_start/period_end map a period code (`"202605"`) to the [start, end)
            // half-open datetime bounds of that fiscal period; the optional 2nd arg is the
            // code_format (defaults to the period-table's format). The catalog has no
            // optional-param marker, so the optional 2nd arg is two overloads (1-arg + 2-arg).
            scalar("fn.period_start", "period_start", listOf(str), ReturnTypeRule.Fixed(dt), NullRule.STRICT),
            scalar("fn.period_start", "period_start", listOf(str, str), ReturnTypeRule.Fixed(dt), NullRule.STRICT),
            // period_end returns the EXCLUSIVE end (the half-open upper bound), so range
            // predicates use `< period_end(code)`, never `<=`.
            scalar("fn.period_end", "period_end", listOf(str), ReturnTypeRule.Fixed(dt), NullRule.STRICT),
            scalar("fn.period_end", "period_end", listOf(str, str), ReturnTypeRule.Fixed(dt), NullRule.STRICT),
            // geo_distance_m(lat1, lon1, lat2, lon2) → great-circle metres between two points.
            scalar(
                "fn.geo_distance_m",
                "geo_distance_m",
                listOf(num, num, num, num),
                ReturnTypeRule.Fixed(num),
                NullRule.STRICT,
            ),
            // Aggregates — the distinct [AggregateCall] arm.
            agg("agg.sum", "sum", listOf(num), ReturnTypeRule.Fixed(dec)),
            agg("agg.avg", "avg", listOf(num), ReturnTypeRule.Fixed(dec)),
            agg("agg.count", "count", listOf(num), ReturnTypeRule.Fixed(int)),
            agg("agg.min", "min", listOf(num), ReturnTypeRule.SameAsArg(0)),
            agg("agg.max", "max", listOf(num), ReturnTypeRule.SameAsArg(0)),
        )

    /**
     * Internal validity functions (RJ-P1, R-E1-α / contracts §2): total, boolean, never-NULL, and
     * NOT surface-authorable. The guard calc synthesized by the reject-elaboration stratum calls
     * one per reject-capable expr. `internalOnly = true` keeps them out of [resolve] (surface use →
     * `TTRP-FN-001`) while [internal] exposes them to the rewriter/emitter.
     */
    private val internalEntries: List<CatalogEntry> =
        listOf(
            internalFn("internal.is_castable", "is_castable", listOf(str, str)),
            internalFn("internal.is_nonzero", "is_nonzero", listOf(num)),
            internalFn("internal.is_parseable_dt", "is_parseable_dt", listOf(str, str)),
        )

    private val byName: Map<String, List<CatalogEntry>> = (entries + internalEntries).groupBy { it.name }

    private val byId: Map<String, CatalogEntry> = (entries + internalEntries).associateBy { it.id.value }

    /** Look up a catalogue entry (incl. internal-only) by its fully-qualified id. */
    fun entry(id: String): CatalogEntry? = byId[id]

    /** The internal validity function for [id] (`internal.*`), or null. */
    fun internal(id: String): CatalogEntry? = byId[id]?.takeIf { it.internalOnly }

    /** The aggregate surface names — the walker uses this to fold [AggregateCall] vs [FunctionCall]. */
    val aggregateNames: Set<String> = entries.filter { it.kind == FunctionKind.AGGREGATE }.map { it.name }.toSet()

    /**
     * Closed, deterministic alias reject table (P2): a known misspelling maps to its
     * canonical function. A hit is `TTRP-FN-001` with "use <canonical>".
     */
    val aliases: Map<String, String> =
        mapOf(
            "mean" to "avg",
            "substr" to "substring",
            "nvl" to "coalesce",
            "ifnull" to "coalesce",
            "ucase" to "upper",
            "lcase" to "lower",
            // Grounding twins (grammar 4.2): closed misspellings of the grounding fns.
            "period_from" to "period_start",
            "distance" to "geo_distance_m",
        )

    // Surface resolution hides internal-only entries (R-E1-α): `is_castable(...)` authored
    // directly is an unknown function, never a silent hit.
    override fun resolve(name: String): List<CatalogEntry> = byName[name].orEmpty().filterNot { it.internalOnly }

    private fun internalFn(
        id: String,
        name: String,
        params: List<TtrpType>,
    ) = CatalogEntry(
        CatalogId(id),
        name,
        FunctionKind.SCALAR,
        params,
        ReturnTypeRule.Fixed(bool),
        NullRule.CUSTOM,
        pure = true,
        internalOnly = true,
    )

    private fun op(
        id: String,
        name: String,
        params: List<TtrpType>,
        ret: ReturnTypeRule,
        nulls: NullRule,
    ) = CatalogEntry(CatalogId(id), name, FunctionKind.SCALAR, params, ret, nulls)

    private fun scalar(
        id: String,
        name: String,
        params: List<TtrpType>,
        ret: ReturnTypeRule,
        nulls: NullRule,
    ) = CatalogEntry(CatalogId(id), name, FunctionKind.SCALAR, params, ret, nulls)

    private fun agg(
        id: String,
        name: String,
        params: List<TtrpType>,
        ret: ReturnTypeRule,
    ) = CatalogEntry(CatalogId(id), name, FunctionKind.AGGREGATE, params, ret, NullRule.STRICT)
}
