// SPDX-License-Identifier: Apache-2.0
package org.tatrman.concept

import org.tatrman.ttr.metadata.model.Basis
import org.tatrman.ttr.metadata.model.CnSchema
import org.tatrman.ttr.metadata.model.Concept
import org.tatrman.ttr.metadata.model.ErSchema
import org.tatrman.ttr.metadata.model.GroundingGrade
import org.tatrman.ttr.metadata.model.GroundingLayer
import org.tatrman.ttr.metadata.model.Imprecision
import org.tatrman.ttr.metadata.model.QualifiedName
import org.tatrman.ttrp.expr.AggregateCall
import org.tatrman.ttrp.expr.CaseWhen
import org.tatrman.ttrp.expr.Cast
import org.tatrman.ttrp.expr.ColumnRef
import org.tatrman.ttrp.expr.ConceptAggregate
import org.tatrman.ttrp.expr.DurationExpr
import org.tatrman.ttrp.expr.ExistsExpr
import org.tatrman.ttrp.expr.Expression
import org.tatrman.ttrp.expr.FunctionCall
import org.tatrman.ttrp.expr.InCall
import org.tatrman.ttrp.expr.InList
import org.tatrman.ttrp.expr.IsNull
import org.tatrman.ttrp.expr.Literal
import org.tatrman.ttrp.expr.MdPath
import org.tatrman.ttrp.expr.ParamRef
import org.tatrman.ttrp.expr.TemporalPredicate
import org.tatrman.ttrp.expr.ThisRef

/**
 * The definition-level `cn/…` slugs (contracts §7; CN-D29 — Kotlin's, reported by `ttr cn hygiene`, never in the
 * editor), over the parsed, resolved definitions of a [ResolvedCnModel] (CN-P2 S2.6 T6):
 *
 *  - `cn/definition-cycle` (error) — phases / defined qualities / member-restricted kinds that inline each other (AM-26);
 *  - `cn/definition-unresolved` (hygiene) — what a definition reads, inlined transitively, includes an ungrounded
 *    concept;
 *  - `cn/definition-unreachable(md)` / `(er)` (hygiene, model) — a concept it reads has no md face (its md grounding,
 *    or the reverse hop — ⚑CN-P8, A-CN-37; CN-P3 S3.3) / no er landing (`erTarget`): the definition cannot render on
 *    that layer;
 *  - `cn/basis-mismatch` (error) — a condition's definition does not read its declared `basis:` ([BASIS_SHAPES]);
 *  - `cn/imprecise-comparison` (advisory) — under `imprecision: certainly` a comparison of two instants whose
 *    granularities are known and differ (AM-42; `period: { precision }`, else the attribute's type; `:as_of` is a day),
 *    unless the coarser one is a period bound whose grounding stores its upper bound (`start_hi:` / `at_hi:`, ⚑CN-P10 —
 *    the er renderer compares the stored bound, exactly; CN-P3 S3.4, A-CN-45).
 */
object CnDefinitionLints {
    const val CYCLE = "cn/definition-cycle"
    const val UNRESOLVED = "cn/definition-unresolved"
    const val UNREACHABLE = "cn/definition-unreachable"
    const val BASIS_MISMATCH = "cn/basis-mismatch"
    const val IMPRECISE = "cn/imprecise-comparison"

    /**
     * The basis shapes (08b §3 / D-T7, pinned at S2.6 T6): what a condition's definition reads against `:as_of` for each
     * `basis:`. A definition matches its basis when at least one of its comparisons (its own or an inlined one's) has
     * the basis' shape; `<` / `<=` / `before` / `at_or_before` count alike, and `:as_of >` / `>=` mirror them.
     *
     *  | basis | holds as of *t* when … | the comparison |
     *  |---|---|---|
     *  | `occurrence` | an occurrence happened at or before *t* | `e.period.start` / `.at` / `e.period` *before* `:as_of` |
     *  | `current_state` | an episode contains *t* | `e.period contains :as_of` |
     *  | `past_state` | an episode ended before *t* | `e.period ended_before :as_of`, `e.period.end` *before* `:as_of` |
     */
    val BASIS_SHAPES: Map<Basis, String> =
        mapOf(
            Basis.OCCURRENCE to "an occurrence at or before `:as_of` (`e.period.start at_or_before :as_of`)",
            Basis.CURRENT_STATE to "an episode containing `:as_of` (`e.period contains :as_of`)",
            Basis.PAST_STATE to "an episode ended before `:as_of` (`e.period ended_before :as_of`)",
        )

    private val BEFORE_OPS = setOf("<", "<=", "before", "at_or_before")
    private val AFTER_OPS = setOf(">", ">=")

    fun run(model: ResolvedCnModel): List<Diagnostic> {
        val cn = model.cn
        val er = model.model.schemas["er"] as? ErSchema
        // A definition that does not parse or resolve is reported as that (T5); its reads are partial, so no lint reads it.
        val conceptDefs =
            model.definitions.all.filter {
                it.kind != BlockKind.FILTER &&
                    it.expression != null &&
                    it.diagnostics.none { d -> d.severity == Severity.ERROR }
            }
        val byOwner = conceptDefs.groupBy { it.owner }
        val out = mutableListOf<Diagnostic>()
        out += cycles(cn, byOwner)
        for (d in conceptDefs) {
            val owner = cn.concepts[d.owner] ?: continue
            val closure = closure(d, byOwner)
            if (owner.stereotype == "subkind") {
                // The same rule as the semantic layer's portable text check (review-175 F9): `:as_of` (written, or read
                // by `within(…, last:)` / `age(…)`), a `:name`, a DC-12 predicate — `within(…)` in either form is one —
                // or a phase / valued-state read. A bare `period` read is rigid in both.
                val temporal =
                    closure.any { block ->
                        block.reads.asOf ||
                            callsWithin(block.expression) ||
                            block.reads.parameters.isNotEmpty() ||
                            block.reads.comparisons.any {
                                it.op in
                                    setOf("before", "at_or_before", "contains", "ended_before", "overlaps")
                            } ||
                            (block.reads.concepts + block.reads.definitions).any { q ->
                                cn.concepts[q]?.let {
                                    it.stereotype ==
                                        "phase" ||
                                        it.valuedState
                                } ==
                                    true
                            }
                    }
                if (temporal) {
                    out +=
                        Diagnostic(
                            "cn/subkind-temporal-definition",
                            Severity.ERROR,
                            "`${owner.ref}` has a temporal or parametric definition",
                            owner.ref,
                            d.block.source,
                        )
                }
            }
            out += unresolved(cn, owner, d, closure)
            out += unreachable(cn, owner, d, closure)
            if (d.kind == BlockKind.DEFINITION) {
                basis(owner, d, closure)?.let { out += it }
                out += imprecise(cn, er, owner, d)
            }
        }
        return out
    }

    /**
     * Whether [e] calls `within(…)` anywhere — a DC-12 predicate whatever its bounds (`from:`/`to:` or `last:`). A dotted
     * `x.within(…)` is a projection read, not the predicate — as the text rule reads it (A-CN-118, review-175 R2).
     */
    private fun callsWithin(e: Expression?): Boolean =
        when (e) {
            null -> false
            is FunctionCall -> isWithin(e.function.value) || e.args.any(::callsWithin)
            is AggregateCall -> e.args.any(::callsWithin)
            is Cast -> callsWithin(e.expr)
            is CaseWhen -> e.branches.any { callsWithin(it.first) || callsWithin(it.second) } || callsWithin(e.elseExpr)
            is InList -> callsWithin(e.expr) || e.items.any(::callsWithin)
            is IsNull -> callsWithin(e.expr)
            is ExistsExpr -> e.involving.any { callsWithin(it.path) } || callsWithin(e.where)
            is ConceptAggregate ->
                e.involving.any { callsWithin(it.path) } ||
                    callsWithin(e.where) ||
                    e.namedArgs.any { callsWithin(it.second) }
            is InCall -> callsWithin(e.expr) || callsWithin(e.call)
            is TemporalPredicate -> callsWithin(e.lhs) || callsWithin(e.rhs)
            is ColumnRef, is Literal, is MdPath, is ParamRef, is DurationExpr, is ThisRef -> false
        }

    /** `within` as the catalogue spells it — bare, or under `fn.`. */
    private fun isWithin(id: String): Boolean = id == "within" || id == "fn.within"

    /** What [d] reads with every definition it names inlined, transitively (cycle-safe). */
    private fun closure(
        d: CnDefinition,
        byOwner: Map<QualifiedName, List<CnDefinition>>,
    ): List<CnDefinition> {
        val seen = linkedMapOf<QualifiedName, CnDefinition>()
        val queue = ArrayDeque(listOf(d))
        while (queue.isNotEmpty()) {
            val x = queue.removeFirst()
            if (seen.putIfAbsent(x.owner, x) != null) continue
            x.reads.definitions.forEach { q -> byOwner[q]?.forEach { queue += it } }
        }
        return seen.values.toList()
    }

    private fun cycles(
        cn: CnSchema,
        byOwner: Map<QualifiedName, List<CnDefinition>>,
    ): List<Diagnostic> {
        val edges = byOwner.mapValues { (_, ds) -> ds.flatMap { it.reads.definitions }.filter { it in byOwner } }
        val out = mutableListOf<Diagnostic>()
        for (start in byOwner.keys.sortedBy { it.name }) {
            // the shortest path back to [start], if any — breadth first
            val prev = mutableMapOf<QualifiedName, QualifiedName>()
            val queue = ArrayDeque(edges[start].orEmpty().onEach { prev.putIfAbsent(it, start) })
            var found = start in edges[start].orEmpty()
            while (queue.isNotEmpty() && !found) {
                val x = queue.removeFirst()
                for (y in edges[x].orEmpty()) {
                    if (y == start) {
                        prev[start] = x
                        found = true
                        break
                    }
                    if (prev.putIfAbsent(y, x) == null) queue += y
                }
            }
            if (!found) continue
            val path = mutableListOf(start)
            var at = prev.getValue(start)
            while (at != start) {
                path += at
                at = prev.getValue(at)
            }
            path += start
            val names = path.reversed().joinToString(" → ") { it.name }
            val d = byOwner.getValue(start).first()
            out +=
                Diagnostic(
                    CYCLE,
                    Severity.ERROR,
                    "the definitions inline each other: $names (AM-26)",
                    cn.concepts[start]?.ref ?: start.name,
                    d.block.source,
                )
        }
        return out
    }

    private fun read(
        cn: CnSchema,
        closure: List<CnDefinition>,
    ): List<Concept> = closure.flatMap { it.reads.concepts }.distinct().mapNotNull { cn.concepts[it] }

    private fun unresolved(
        cn: CnSchema,
        owner: Concept,
        d: CnDefinition,
        closure: List<CnDefinition>,
    ): List<Diagnostic> {
        val ungrounded = read(cn, closure).filter { it.grade == GroundingGrade.UNGROUNDED }
        if (ungrounded.isEmpty()) return emptyList()
        return listOf(
            Diagnostic(
                UNRESOLVED,
                Severity.HYGIENE,
                "the ${d.kind.property} reads ungrounded ${names(ungrounded)}",
                owner.ref,
                d.block.source,
            ),
        )
    }

    private fun unreachable(
        cn: CnSchema,
        owner: Concept,
        d: CnDefinition,
        closure: List<CnDefinition>,
    ): List<Diagnostic> {
        val reads = read(cn, closure)
        val noMd = reads.filter { it.grounding?.layer != GroundingLayer.MD && it.reverseHop.isEmpty() }
        val noEr = reads.filter { it.erTarget == null }
        return listOfNotNull(
            noMd.takeIf { it.isNotEmpty() }?.let {
                Diagnostic(
                    "$UNREACHABLE(md)",
                    Severity.HYGIENE,
                    "the ${d.kind.property} cannot render on md: ${names(it)} ha${if (it.size == 1) "s" else "ve"} " +
                        "no md grounding and no md element hops to ${if (it.size == 1) "its" else "their"} E-R landing",
                    owner.ref,
                    d.block.source,
                )
            },
            noEr.takeIf { it.isNotEmpty() }?.let {
                Diagnostic(
                    "$UNREACHABLE(er)",
                    Severity.HYGIENE,
                    "the ${d.kind.property} cannot render on er: ${names(it)} land${if (it.size == 1) "s" else ""} " +
                        "nowhere on E-R",
                    owner.ref,
                    d.block.source,
                )
            },
        )
    }

    private fun names(cs: List<Concept>): String = cs.joinToString(", ") { "`${it.qname.name}`" }

    /** The basis shapes a comparison has (at most one), per [BASIS_SHAPES]. */
    internal fun shapeOf(c: InstantComparison): Basis? {
        // normalize `:as_of > x` to `x < :as_of`
        val (op, l, r) =
            when {
                c.lhs == InstantTerm.AsOf && c.op in AFTER_OPS -> Triple("<", c.rhs, c.lhs)
                else -> Triple(c.op, c.lhs, c.rhs)
            }
        if (r != InstantTerm.AsOf) return null
        return when {
            op == "contains" && l is InstantTerm.Period -> Basis.CURRENT_STATE
            op == "ended_before" && l is InstantTerm.Period -> Basis.PAST_STATE
            op in BEFORE_OPS && l is InstantTerm.PeriodBound && l.bound == "end" -> Basis.PAST_STATE
            op in BEFORE_OPS && l is InstantTerm.PeriodBound -> Basis.OCCURRENCE
            op in BEFORE_OPS && l is InstantTerm.Period -> Basis.OCCURRENCE
            else -> null
        }
    }

    private fun basis(
        owner: Concept,
        d: CnDefinition,
        closure: List<CnDefinition>,
    ): Diagnostic? {
        val declared = owner.basis ?: return null
        val shapes = closure.flatMap { it.reads.comparisons }.mapNotNull { shapeOf(it) }.toSet()
        if (declared in shapes) return null
        val found =
            if (shapes.isEmpty()) {
                if (closure.any { it.reads.asOf }) "`:as_of` in none of the table's shapes" else "no `:as_of`"
            } else {
                shapes.joinToString(" and ") { "`${it.name.lowercase()}`" }
            }
        return Diagnostic(
            BASIS_MISMATCH,
            Severity.ERROR,
            "`basis: ${declared.name.lowercase()}` holds on ${BASIS_SHAPES.getValue(
                declared,
            )}; the definition reads $found",
            owner.ref,
            d.block.source,
        )
    }

    // ---- imprecision -------------------------------------------------------------------------------------------

    /** Instant granularities, finest first; `:as_of` is a day (S0.3 §4). */
    enum class Granularity { SECOND, MINUTE, HOUR, DAY, WEEK, MONTH, QUARTER, YEAR }

    internal fun granularity(spelling: String?): Granularity? =
        when (spelling?.lowercase()) {
            "second" -> Granularity.SECOND
            "minute" -> Granularity.MINUTE
            "hour" -> Granularity.HOUR
            "day" -> Granularity.DAY
            "week" -> Granularity.WEEK
            "month" -> Granularity.MONTH
            "quarter" -> Granularity.QUARTER
            "year" -> Granularity.YEAR
            else -> null
        }

    /** An attribute type's granularity: a date is a day, a timestamp a second; anything else is not known. */
    internal fun ofType(type: String?): Granularity? =
        when (ErType.of(type)) {
            ErType.DATE -> Granularity.DAY
            ErType.TIMESTAMP -> Granularity.SECOND
            else -> null
        }

    private fun attributeType(
        er: ErSchema?,
        elementRef: String?,
        attribute: String?,
    ): String? {
        if (er == null || elementRef == null) return null
        val segs = elementRef.split('.')
        if (segs.size < 3 || segs[0] != "er" || segs[1] != "entity") return null
        val attr = if (segs.size >= 4) segs[3] else attribute ?: return null
        return er.entities.values
            .filter { it.qname.name == segs[2] }
            .flatMap { it.attributes }
            .firstOrNull { it.qname.name.substringAfterLast('.') == attr } // the qname is `<entity>.<attribute>`
            ?.type
    }

    private fun granularity(
        cn: CnSchema,
        er: ErSchema?,
        t: InstantTerm?,
    ): Granularity? =
        when (t) {
            null -> null
            InstantTerm.AsOf -> Granularity.DAY
            is InstantTerm.PeriodBound -> {
                val owner = cn.concepts[t.owner]
                granularity(owner?.period?.precision) ?: run {
                    // the binding's `period:` names the attribute (an element ref); else the quality is the attribute
                    val bound =
                        when (t.bound) {
                            "start" -> owner?.grounding?.period?.start
                            "end" -> owner?.grounding?.period?.end
                            else -> owner?.grounding?.period?.at
                        }
                    ofType(
                        if (bound != null) {
                            attributeType(er, bound, null)
                        } else {
                            attributeType(er, owner?.grounding?.target, t.quality)
                        },
                    )
                }
            }
            is InstantTerm.Period -> {
                val owner = cn.concepts[t.owner]
                val p = owner?.period
                granularity(p?.precision) ?: granularity(
                    cn,
                    er,
                    (p?.start ?: p?.at)?.let {
                        InstantTerm.PeriodBound(t.owner, if (p?.start != null) "start" else "at", it)
                    },
                )
            }
            is InstantTerm.Quality -> {
                val def = t.concept?.let { cn.concepts[it] }
                if (def?.grounding != null) {
                    ofType(attributeType(er, def.grounding?.target, null))
                } else {
                    ofType(attributeType(er, cn.concepts[t.owner]?.grounding?.target, t.name))
                }
            }
        }

    /**
     * Whether a comparison whose coarser side has a stored upper bound is DECIDED by that bound — the er renderer reads
     * it through the bound, so it is exact under `imprecision: certainly` (A-CN-45, review-125 R125-4): the coarser side
     * on the lesser side of `<` / `<=` / `before` / `at_or_before`, or on the greater side of `>` / `>=` — and, for the
     * non-strict ones, the finer side `:as_of` (its upper end `{as_of_hi}` is derivable; a column's is not, G-ER-1).
     * Elsewhere the stored instant decides, and the advisory stands. Shared by this lint and the renderer.
     */
    internal fun decidedByBound(
        op: String,
        coarserIsLhs: Boolean,
        finerIsAsOf: Boolean,
    ): Boolean {
        val lesser = if (coarserIsLhs) op in setOf("<", "<=", "before", "at_or_before") else op in setOf(">", ">=")
        val strict = op in setOf("<", ">", "before")
        return lesser && (strict || finerIsAsOf)
    }

    /** Whether [t] is a period bound whose grounding names a stored upper bound for it (⚑CN-P10). */
    private fun storedBound(
        cn: CnSchema,
        t: InstantTerm?,
    ): Boolean {
        if (t !is InstantTerm.PeriodBound) return false
        val p = cn.concepts[t.owner]?.grounding?.period ?: return false
        return when (t.bound) {
            "start" -> p.startHi != null
            "at" -> p.atHi != null
            else -> false
        }
    }

    private fun imprecise(
        cn: CnSchema,
        er: ErSchema?,
        owner: Concept,
        d: CnDefinition,
    ): List<Diagnostic> {
        if ((owner.imprecision ?: Imprecision.CERTAINLY) != Imprecision.CERTAINLY) return emptyList()
        return d.reads.comparisons.mapNotNull { c ->
            val l = granularity(cn, er, c.lhs) ?: return@mapNotNull null
            val r = granularity(cn, er, c.rhs) ?: return@mapNotNull null
            val (coarserTerm, finerTerm) = if (l > r) c.lhs to c.rhs else c.rhs to c.lhs
            val bound = storedBound(cn, coarserTerm) && decidedByBound(c.op, l > r, finerTerm is InstantTerm.AsOf)
            if (l == r || bound) return@mapNotNull null
            val coarser = maxOf(l, r).name.lowercase()
            Diagnostic(
                IMPRECISE,
                Severity.ADVISORY,
                "`${c.op}` compares a ${l.name.lowercase()} with a ${r.name.lowercase()} under " +
                    "`imprecision: certainly` — the coarser instant is certainly ${c.op} only when its " +
                    "whole $coarser is (say `imprecision: possibly`, or compare at one granularity)",
                owner.ref,
                c.location,
            )
        }
    }
}
