// SPDX-License-Identifier: Apache-2.0
package org.tatrman.concept

import org.tatrman.statement.v1.Ask
import org.tatrman.statement.v1.BoolOp
import org.tatrman.statement.v1.Compare
import org.tatrman.statement.v1.CompareMode
import org.tatrman.statement.v1.ComparisonOp
import org.tatrman.statement.v1.Filter
import org.tatrman.statement.v1.QualityFilter
import org.tatrman.statement.v1.Statement
import org.tatrman.statement.v1.TimeSpec
import org.tatrman.ttr.metadata.model.Concept
import org.tatrman.ttr.metadata.model.GroundingLayer
import org.tatrman.ttrp.parser.TtrpParser

/**
 * `statementValidate` (cn contracts §2.2, §3; CN-P3 S3.2): structure, refs, ambiguity, time and reachability PER
 * CONSUMER — a statement lowers through md (AM-4), so every slot needs an md face:
 *
 *  - an **aggregating** slot (measures, group_by) needs the concept's own md grounding; a group-by may also reach md
 *    through the **reverse hop** ([Concept.reverseHop], ⚑CN-P8 — a value kind whose E-R landing a cubelet's grain
 *    attribute hops to); a measure never does (the hop lands grain attributes, not measures);
 *  - a **filter** slot (member, quality) likewise, the reverse hop included (L14-2);
 *  - a **PhaseFilter** needs its definition to render on md — every concept it reads, inlined transitively, with an
 *    md face — or, for a grounded phase, the stacked entity's md face (AM-5); a definition-level error on it
 *    (`cn/definition-cycle`, `-syntax`, `-unknown-name`, …) is re-reported against the slot and refuses.
 *
 * Every finding names its slot as the frame's JsonFormat path (`measures[0]`, `filters[1].phase.phase`, `time`).
 * Whether the md path can CARRY a reachable slot (a carrier cubelet, a renderable predicate) is the lower's (S3.3).
 */
internal class StatementValidator(
    private val model: ResolvedCnModel,
) {
    private val cn = model.cn

    /** The model's error-grade definition findings — computed once; a PhaseFilter re-reports its closure's. */
    private val definitionErrors: List<Diagnostic> by lazy {
        model.definitionDiagnostics().filter { it.severity == Severity.ERROR }
    }

    fun validate(s: Statement): List<Diagnostic> {
        val f = Findings()
        val refs = FrameRefs(model, FrameSlugs.AMBIGUOUS_STATEMENT, f)
        ask(s.ask, f)
        if (s.measuresCount == 0) f.error(FrameSlugs.MALFORMED_FRAME, "measures", "a value ask names a measure")
        val measures =
            s.measuresList.mapIndexedNotNull { i, r ->
                refs.concept(r, "measures[$i]", "measure", QUALITY)?.also {
                    reach(it, "measures[$i]", "a measure", reverseHop = false, f)
                }
            }
        s.groupByList.forEachIndexed { i, r ->
            refs.concept(r, "groupBy[$i]", "group-by", GROUPABLE)?.let {
                reach(it, "groupBy[$i]", "a group-by", reverseHop = true, f)
            }
        }
        s.filtersList.forEachIndexed { i, x -> filter(x, "filters[$i]", refs, f) }
        time(s, f)
        if (s.hasAsOf()) instant(s.asOf, "asOf", f)
        if (s.hasOrderLimit()) {
            s.orderLimit.byList.forEachIndexed { i, k ->
                val subject = "orderLimit.by[$i].by"
                val c = refs.concept(k.by, subject, "order key", QUALITY) ?: return@forEachIndexed
                if (measures.none { it.qname == c.qname }) {
                    f.error(FrameSlugs.MALFORMED_FRAME, subject, "`${c.ref}` orders the result but is no measure of it")
                }
            }
        }
        if (s.hasCompare()) compare(s.compare, refs, f)
        if (s.snapshotHash.isNotEmpty() && s.snapshotHash != model.snapshotHash) {
            f.warning(
                FrameSlugs.SNAPSHOT_MISMATCH,
                "snapshotHash",
                "the frame was built on snapshot `${s.snapshotHash}`; this model is `${model.snapshotHash}`",
            )
        }
        return f.sorted()
    }

    private fun ask(
        a: Ask,
        f: Findings,
    ) {
        when (a) {
            Ask.ASK_VALUE -> {}
            Ask.ASK_SET ->
                f.error(
                    FrameSlugs.UNSUPPORTED_ASK,
                    "ask",
                    "`ASK_SET` is reserved and rejected in v1 — a set-valued ask is a sibling IR (E-6, AM-6)",
                )
            else -> f.error(FrameSlugs.UNSUPPORTED_ASK, "ask", "the frame names no ask (v1 answers `ASK_VALUE`)")
        }
    }

    /** [c]'s md face for [slot]: its md grounding, else (when [reverseHop]) an md element its E-R landing hops to. */
    private fun reach(
        c: Concept,
        subject: String,
        slot: String,
        reverseHop: Boolean,
        f: Findings,
    ) {
        if (mdFace(c, reverseHop)) return
        val landing = c.erTarget?.let { ", landing on E-R at `$it`" } ?: ""
        val hop = if (reverseHop) " and no md element hops to its E-R landing" else ""
        f.error(
            FrameSlugs.NOT_ANALYTICALLY_REACHABLE,
            subject,
            "`${c.ref}` as $slot reads md (AM-4), but it is ${FrameRefs.grade(c.grade)}$landing — no md grounding$hop",
        )
    }

    private fun mdFace(
        c: Concept,
        reverseHop: Boolean = true,
    ): Boolean = c.grounding?.layer == GroundingLayer.MD || (reverseHop && c.reverseHop.isNotEmpty())

    private fun filter(
        x: Filter,
        subject: String,
        refs: FrameRefs,
        f: Findings,
    ) {
        when (x.formCase) {
            Filter.FormCase.MEMBER -> {
                val m = x.member
                val c = refs.concept(m.concept, "$subject.member.concept", "member filter", MEMBERED)
                if (c != null) reach(c, "$subject.member.concept", "a member filter", reverseHop = true, f)
                refs.member(m.member, "$subject.member.member", c?.let { "`${it.ref}`" } ?: "the filter")
            }
            Filter.FormCase.QUALITY -> quality(x.quality, "$subject.quality", refs, f)
            Filter.FormCase.PHASE ->
                refs.concept(x.phase.phase, "$subject.phase.phase", "phase filter", PHASE)?.let {
                    phase(it, "$subject.phase.phase", f)
                }
            Filter.FormCase.GROUP -> {
                val g = x.group
                val n = g.filtersCount
                when (g.op) {
                    BoolOp.NOT ->
                        if (n !=
                            1
                        ) {
                            f.error(FrameSlugs.MALFORMED_FRAME, "$subject.group", "`NOT` takes one filter, not $n")
                        }
                    BoolOp.ALL, BoolOp.ANY ->
                        if (n == 0) f.error(FrameSlugs.MALFORMED_FRAME, "$subject.group", "`${g.op}` over no filters")
                    else -> f.error(FrameSlugs.MALFORMED_FRAME, "$subject.group", "the group names no operator")
                }
                g.filtersList.forEachIndexed { j, y -> filter(y, "$subject.group.filters[$j]", refs, f) }
            }
            Filter.FormCase.EXPR -> {
                val text = x.expr.plExpr
                if (text.isBlank()) {
                    f.error(
                        FrameSlugs.MALFORMED_FRAME,
                        "$subject.expr.plExpr",
                        "an expression filter with no expression",
                    )
                } else {
                    TtrpParser.parseExpression(text, "<statement>").diagnostics.forEach { d ->
                        f.error(FrameSlugs.MALFORMED_FRAME, "$subject.expr.plExpr", "${d.id.id}: ${d.message}")
                    }
                }
            }
            else -> f.error(FrameSlugs.MALFORMED_FRAME, subject, "a filter with no form")
        }
    }

    private fun quality(
        q: QualityFilter,
        subject: String,
        refs: FrameRefs,
        f: Findings,
    ) {
        refs.concept(q.quality, "$subject.quality", "quality filter", QUALITY)?.let {
            reach(it, "$subject.quality", "a quality filter", reverseHop = true, f)
        }
        val op = q.op
        if (op == ComparisonOp.COMPARISON_OP_UNSPECIFIED || op == ComparisonOp.UNRECOGNIZED) {
            f.error(FrameSlugs.MALFORMED_FRAME, "$subject.op", "the quality filter names no comparison")
        }
        val v = q.value
        when {
            !q.hasValue() || (v.vCase == org.tatrman.concept.v1.Value.VCase.V_NOT_SET && !v.isNull) ->
                f.error(FrameSlugs.MALFORMED_FRAME, "$subject.value", "the quality filter compares with no value")
            v.isNull && op != ComparisonOp.EQ && op != ComparisonOp.NEQ ->
                f.error(FrameSlugs.MALFORMED_FRAME, "$subject.value", "a null compares by `EQ` / `NEQ` only, not `$op`")
            v.vCase == org.tatrman.concept.v1.Value.VCase.INSTANT_VALUE -> instant(v.instantValue, "$subject.value", f)
        }
    }

    /** A PhaseFilter (AM-5): the definition renders on md, or the grounded phase's stacked entity has an md face. */
    private fun phase(
        p: Concept,
        subject: String,
        f: Findings,
    ) {
        val defs = model.definitions.closure(p.qname)
        if (defs.isEmpty()) {
            if (mdFace(p)) return
            f.error(
                FrameSlugs.NOT_ANALYTICALLY_REACHABLE,
                subject,
                "`${p.ref}` has no definition to render on md, and its grounding has no md face " +
                    "(${FrameRefs.grade(p.grade)}) — AM-5",
            )
            return
        }
        val owners = defs.mapNotNull { cn.concepts[it.owner]?.ref }.toSet()
        val errors = definitionErrors.filter { it.subject in owners }
        for (d in errors) f.error(d.slug, subject, "`${d.subject}`: ${d.message}")
        if (errors.isNotEmpty()) return
        val reads = defs.flatMap { it.reads.concepts }.distinct().mapNotNull { cn.concepts[it] }
        val noMd = reads.filterNot { mdFace(it) }
        if (noMd.isEmpty()) return
        val names = noMd.joinToString(", ") { "`${it.qname.name}`" }
        f.error(
            FrameSlugs.NOT_ANALYTICALLY_REACHABLE,
            subject,
            "`${p.ref}`'s definition cannot render on md — a statement's PhaseFilter lowers through md (AM-5)",
        )
        f.error(
            FrameSlugs.definitionUnreachable(Layer.MD),
            subject,
            "$names ha${if (noMd.size == 1) "s" else "ve"} no md grounding, and no md element hops to " +
                "${if (noMd.size == 1) "its" else "their"} E-R landing",
        )
    }

    private fun time(
        s: Statement,
        f: Findings,
    ) {
        val t = s.time
        when (t.specCase) {
            TimeSpec.SpecCase.PERIOD_CODE ->
                if (!FrameTime.periodCode(t.periodCode)) {
                    f.error(
                        FrameSlugs.MALFORMED_FRAME,
                        "time.periodCode",
                        "the period code `${t.periodCode}` is not `YYYY`, `YYYY-MM` or `YYYYQn`",
                    )
                }
            TimeSpec.SpecCase.RANGE -> {
                val r = t.range
                val from = if (r.hasFrom()) instant(r.from, "time.range.from", f) else null
                val to = if (r.hasTo()) instant(r.to, "time.range.to", f) else null
                if (!r.hasFrom() || !r.hasTo()) {
                    f.error(FrameSlugs.MALFORMED_FRAME, "time.range", "a range names both `from` and `to`")
                } else if (from != null && to != null && !from.lo.isBefore(to.lo)) {
                    f.error(
                        FrameSlugs.MALFORMED_FRAME,
                        "time.range",
                        "the range [`${r.from.value}`, `${r.to.value}`) is empty — `from` is not before `to`",
                    )
                }
            }
            TimeSpec.SpecCase.RELATIVE ->
                FrameTime.relativeProblem(t.relative)?.let { f.error(FrameSlugs.MALFORMED_FRAME, "time.relative", it) }
            TimeSpec.SpecCase.ALL_TIME ->
                if (!t.allTime) {
                    f.error(FrameSlugs.MISSING_TIME, "time", "`all_time: false` names no time (E-3)")
                }
            else ->
                f.error(
                    FrameSlugs.MISSING_TIME,
                    "time",
                    "the statement names no time — a period, a range, a relative window or `all_time` (E-3)",
                )
        }
    }

    private fun compare(
        c: Compare,
        refs: FrameRefs,
        f: Findings,
    ) {
        when (c.againstCase) {
            Compare.AgainstCase.PERIOD_CODE ->
                if (!FrameTime.periodCode(c.periodCode)) {
                    f.error(
                        FrameSlugs.MALFORMED_FRAME,
                        "compare.periodCode",
                        "the period code `${c.periodCode}` is not `YYYY`, `YYYY-MM` or `YYYYQn`",
                    )
                }
            Compare.AgainstCase.MEMBERS -> {
                refs.member(c.members.left, "compare.members.left", "the comparison")
                refs.member(c.members.right, "compare.members.right", "the comparison")
            }
            else ->
                f.error(
                    FrameSlugs.MALFORMED_FRAME,
                    "compare",
                    "a comparison against nothing (E-1: a period or members)",
                )
        }
        if (c.mode == CompareMode.COMPARE_MODE_UNSPECIFIED || c.mode == CompareMode.UNRECOGNIZED) {
            f.error(FrameSlugs.MALFORMED_FRAME, "compare.mode", "the comparison names no mode")
        }
    }

    private fun instant(
        i: org.tatrman.concept.v1.Instant,
        subject: String,
        f: Findings,
    ): FrameTime.Interval? =
        FrameTime.interval(i).getOrElse {
            f.error(FrameSlugs.MALFORMED_FRAME, subject, it.message ?: "not an instant")
            null
        }

    private companion object {
        val QUALITY = setOf("quality")
        val PHASE = setOf("phase")
        val MEMBERED = setOf("kind", "role")
        val GROUPABLE = setOf("kind", "role", "quality")
    }
}
