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

/**
 * The definition-level `cn/…` slugs (contracts §7; CN-D29 — Kotlin's, reported by `ttr cn hygiene`, never in the
 * editor), over the parsed, resolved definitions of a [ResolvedCnModel] (CN-P2 S2.6 T6):
 *
 *  - `cn/definition-cycle` (error) — phases / defined qualities / member-restricted kinds that inline each other (AM-26);
 *  - `cn/definition-unresolved` (hygiene) — what a definition reads, inlined transitively, includes an ungrounded
 *    concept;
 *  - `cn/definition-unreachable(md)` / `(er)` (hygiene, model) — a concept it reads has no md grounding / no er
 *    landing (`erTarget`): the definition cannot render on that layer;
 *  - `cn/basis-mismatch` (error) — a condition's definition does not read its declared `basis:` ([BASIS_SHAPES]);
 *  - `cn/imprecise-comparison` (advisory) — under `imprecision: certainly` a comparison of two instants whose
 *    granularities are known and differ (AM-42; `period: { precision }`, else the attribute's type; `:as_of` is a day).
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
            out += unresolved(cn, owner, d, closure)
            out += unreachable(cn, owner, d, closure)
            if (d.kind == BlockKind.DEFINITION) {
                basis(owner, d, closure)?.let { out += it }
                out += imprecise(cn, er, owner, d)
            }
        }
        return out
    }

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
        val noMd = reads.filter { it.grounding?.layer != GroundingLayer.MD }
        val noEr = reads.filter { it.erTarget == null }
        return listOfNotNull(
            noMd.takeIf { it.isNotEmpty() }?.let {
                Diagnostic(
                    "$UNREACHABLE(md)",
                    Severity.HYGIENE,
                    "the ${d.kind.property} cannot render on md: ${names(it)} ha${if (it.size == 1) "s" else "ve"} " +
                        "no md grounding",
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
    private fun shapeOf(c: InstantComparison): Basis? {
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
                if (closure.any { it.reads.asOf }) "no reading of `:as_of` the basis table knows" else "no `:as_of`"
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

    private fun granularity(spelling: String?): Granularity? =
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
    private fun ofType(type: String?): Granularity? =
        when (type?.lowercase()?.substringBefore('(')) {
            "date" -> Granularity.DAY
            "datetime", "datetime2", "timestamp", "timestamptz", "smalldatetime" -> Granularity.SECOND
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
            if (l == r) return@mapNotNull null
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
