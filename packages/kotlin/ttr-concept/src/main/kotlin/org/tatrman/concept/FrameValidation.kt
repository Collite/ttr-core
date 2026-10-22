// SPDX-License-Identifier: Apache-2.0
package org.tatrman.concept

import org.tatrman.concept.v1.ConceptRef
import org.tatrman.concept.v1.Granularity
import org.tatrman.concept.v1.Instant
import org.tatrman.concept.v1.MemberRef
import org.tatrman.ttr.metadata.model.Concept
import org.tatrman.ttr.metadata.model.GroundingGrade
import org.tatrman.ttrp.lang.KeywordTable
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.format.DateTimeParseException

/**
 * The consumer slugs a frame is refused with (contracts §7; CN-P3 S3.2). A refusal is an ERROR — the lower that
 * follows a validate refuses on any; `cn/snapshot-mismatch` alone is a warning.
 */
object FrameSlugs {
    const val AMBIGUOUS_STATEMENT = "cn/ambiguous-statement"
    const val AMBIGUOUS_ASSERTION = "cn/ambiguous-assertion"
    const val MISSING_TIME = "cn/missing-time"
    const val NOT_ANALYTICALLY_REACHABLE = "cn/not-analytically-reachable"
    const val NOT_ASSERTABLE = "cn/not-assertable"
    const val PARTICIPANT_PATH_BROKEN = "cn/participant-path-broken"
    const val UNIT_MISMATCH = "cn/unit-mismatch"
    const val BASIS_MISMATCH = CnDefinitionLints.BASIS_MISMATCH

    /** `ASK_SET` (reserved, rejected in v1 — E-6 closed as a sibling IR) or no ask at all (A-CN-32). */
    const val UNSUPPORTED_ASK = "cn/unsupported-ask"

    /** A participant label the event does not declare, or none (R-cn-5; A-CN-33). */
    const val UNKNOWN_PARTICIPANT_LABEL = "cn/unknown-participant-label"

    /**
     * A resolved ref the slot does not take — a stereotype outside the slot's (a role as a measure), a participant
     * concept the label's declared one is not compatible with, a quality of another bearer (A-CN-34).
     */
    const val SLOT_MISMATCH = "cn/slot-mismatch"

    /**
     * A frame field out of the shape the contracts give it — an instant that does not parse at its granularity, a
     * `relative` / period code outside the vocabulary, a range whose `from` is not before its `to`, a filter with no
     * form, an `order_limit` key that is no measure of the statement, a period the event cannot carry (A-CN-34).
     */
    const val MALFORMED_FRAME = "cn/malformed-frame"

    /** A state claim without `asserted_as_of` — REQUIRED for state claims (AM-38; A-CN-34). */
    const val MISSING_AS_OF = "cn/missing-as-of"

    /** WARNING — the frame names a model snapshot other than the one validating it (A-CN-34). */
    const val SNAPSHOT_MISMATCH = "cn/snapshot-mismatch"

    /** The consumer's refusal for a definition that cannot render on [layer] (contracts §7 — a hygiene row model-side). */
    fun definitionUnreachable(layer: Layer): String = "${CnDefinitionLints.UNREACHABLE}(${layer.name.lowercase()})"
}

/**
 * A validator's findings, returned in ONE order (S3.2 T5) — by subject (indices compared as numbers, so `measures[2]`
 * precedes `measures[10]`), then slug, then message — so two runs over one frame are byte-identical.
 */
internal class Findings {
    private val out = mutableListOf<Diagnostic>()

    fun error(
        slug: String,
        subject: String,
        message: String,
    ) {
        out += Diagnostic(slug, Severity.ERROR, message, subject)
    }

    fun warning(
        slug: String,
        subject: String,
        message: String,
    ) {
        out += Diagnostic(slug, Severity.WARNING, message, subject)
    }

    fun hasErrors(): Boolean = out.any { it.severity == Severity.ERROR }

    fun sorted(): List<Diagnostic> =
        out.distinct().sortedWith(
            Comparator<Diagnostic> { a, b -> naturalCompare(a.subject, b.subject) }
                .thenBy { it.slug }
                .thenBy { it.message },
        )

    private companion object {
        val CHUNK = Regex("\\d+|\\D+")

        fun naturalCompare(
            a: String,
            b: String,
        ): Int {
            val xs = CHUNK.findAll(a).map { it.value }.toList()
            val ys = CHUNK.findAll(b).map { it.value }.toList()
            for (i in 0 until minOf(xs.size, ys.size)) {
                val x = xs[i]
                val y = ys[i]
                val c =
                    if (x[0].isDigit() && y[0].isDigit()) {
                        compareValuesBy(x, y, { it.length }, { it })
                    } else {
                        x.compareTo(y)
                    }
                if (c != 0) return c
            }
            return xs.size - ys.size
        }
    }
}

/**
 * The frame-ref resolver every family shares (S3.2 T1): a `ConceptRef` → the concept, or a refusal. Candidates, an
 * empty qname, a qname that is not `cn.<Name>`, a name no concept has and a name two packages share are all
 * [ambiguous] (`cn/ambiguous-statement` / `-assertion`, by family) — refusal over guess, never the first; a resolved
 * concept whose stereotype the slot does not take is [FrameSlugs.SLOT_MISMATCH].
 */
internal class FrameRefs(
    model: ResolvedCnModel,
    private val ambiguous: String,
    private val findings: Findings,
) {
    private val byName: Map<String, List<Concept>> =
        model.cn.concepts.values
            .groupBy { it.qname.name }

    /** The concepts named [name], any package. */
    fun lookup(name: String): List<Concept> = byName[name].orEmpty()

    /** `cn.<Name>` → `<Name>`; null when [qname] is not that spelling. */
    fun nameOf(qname: String): String? = conceptName(qname)

    /** Reports [ref]'s ambiguity (candidates, an empty qname) — true when it has none. */
    fun unambiguous(
        ref: ConceptRef,
        subject: String,
        slot: String,
    ): Boolean {
        if (ref.candidatesCount > 0) {
            val names = ref.candidatesList.joinToString(", ") { "`${it.qname}`" }
            findings.error(ambiguous, subject, "the $slot is ambiguous — candidates $names; refused, never the first")
            return false
        }
        if (ref.qname.isEmpty()) {
            findings.error(ambiguous, subject, "the $slot names no concept")
            return false
        }
        return true
    }

    /** [ref] resolved in a slot that takes the stereotypes [takes]; null — reported — when it does not resolve. */
    fun concept(
        ref: ConceptRef,
        subject: String,
        slot: String,
        takes: Set<String>,
    ): Concept? {
        if (!unambiguous(ref, subject, slot)) return null
        val c = single(ref.qname, subject, slot) ?: return null
        if (c.stereotype !in takes) {
            findings.error(
                FrameSlugs.SLOT_MISMATCH,
                subject,
                "the $slot takes a ${takes.sorted().joinToString(" or ")}; `${ref.qname}` is a ${c.stereotype}",
            )
            return null
        }
        return c
    }

    /** The one concept [qname] names; null — reported as [ambiguous] — for a bad spelling, none or several. */
    fun single(
        qname: String,
        subject: String,
        slot: String,
    ): Concept? {
        val name = nameOf(qname)
        if (name == null) {
            findings.error(ambiguous, subject, "the $slot `$qname` is not a concept ref — a frame writes `cn.<Name>`")
            return null
        }
        val found = lookup(name)
        when {
            found.isEmpty() -> findings.error(ambiguous, subject, "the $slot `$qname` names no concept")
            found.size > 1 ->
                findings.error(
                    ambiguous,
                    subject,
                    "the $slot `$qname` names a concept in ${found.size} packages " +
                        "(${found.map { it.qname.`package` }.sorted().joinToString(", ") { "`$it`" }}) — " +
                        "refused, never the first",
                )
            else -> return found.single()
        }
        return null
    }

    /** Reports an unresolved member (an empty `resolved_value`) of [of] — true when it is resolved. */
    fun member(
        m: MemberRef,
        subject: String,
        of: String,
    ): Boolean {
        if (m.resolvedValue.isNotEmpty()) return true
        val spoken = if (m.label.isEmpty()) "" else " `${m.label}`"
        findings.error(ambiguous, subject, "the member$spoken of $of is unresolved (no `resolved_value`)")
        return false
    }

    companion object {
        /** How the grade reads in a message: `er-grounded`, `md-grounded-hop-missing`. */
        fun grade(g: GroundingGrade): String = g.name.lowercase().replace('_', '-')

        /** `cn.<Name>` → `<Name>`; null when [qname] is not that spelling (a frame's, and `definitionRender`'s target). */
        fun conceptName(qname: String): String? =
            qname.removePrefix("cn.").takeIf { qname.startsWith("cn.") && it.isNotEmpty() && '.' !in it }
    }
}

/**
 * The frame's time vocabulary (refs.proto `Instant`, statement.proto `TimeSpec`; S3.2 T2): an instant is ISO-8601 text
 * truncated to its granularity and stands for `[value, value + 1 granularity)`; a period code is `YYYY` · `YYYY-MM` ·
 * `YYYYQn`; a `relative` is `last(<n>,<unit>)` with the TTR-P duration units (A-CN-35).
 */
internal object FrameTime {
    /** An instant's interval `[lo, hi)`. */
    data class Interval(
        val lo: LocalDateTime,
        val hi: LocalDateTime,
    )

    private val SHAPES =
        mapOf(
            Granularity.YEAR to Regex("\\d{4}"),
            Granularity.MONTH to Regex("\\d{4}-\\d{2}"),
            Granularity.DAY to Regex("\\d{4}-\\d{2}-\\d{2}"),
            Granularity.HOUR to Regex("\\d{4}-\\d{2}-\\d{2}T\\d{2}"),
            Granularity.MINUTE to Regex("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}"),
        )

    /** How each granularity is written — the message of an instant that does not parse. */
    private val SPELLING =
        mapOf(
            Granularity.YEAR to "YYYY",
            Granularity.MONTH to "YYYY-MM",
            Granularity.DAY to "YYYY-MM-DD",
            Granularity.HOUR to "YYYY-MM-DDTHH",
            Granularity.MINUTE to "YYYY-MM-DDTHH:MM",
        )

    private val PERIOD_CODE = Regex("\\d{4}|\\d{4}-(0[1-9]|1[0-2])|\\d{4}Q[1-4]")

    private val RELATIVE = Regex("last\\(\\s*(\\d+)\\s*,\\s*([a-z_]+)\\s*\\)")

    /** The units a `relative` window counts in — TTR-P's duration units (`days(30)`, `hours(24)`). */
    val RELATIVE_UNITS: Set<String> = KeywordTable.durationUnits

    /** [i]'s interval, or why it is not one. */
    fun interval(i: Instant): Result<Interval> {
        val shape =
            SHAPES[i.granularity]
                ?: return Result.failure(IllegalArgumentException("the instant `${i.value}` has no granularity"))
        if (!shape.matches(i.value)) {
            return Result.failure(
                IllegalArgumentException(
                    "the ${i.granularity.name.lowercase()} instant `${i.value}` is not written " +
                        SPELLING.getValue(i.granularity),
                ),
            )
        }
        return try {
            val v = i.value
            Result.success(
                when (i.granularity) {
                    Granularity.YEAR ->
                        LocalDate
                            .of(
                                v.toInt(),
                                1,
                                1,
                            ).atStartOfDay()
                            .let { Interval(it, it.plusYears(1)) }
                    Granularity.MONTH ->
                        YearMonth
                            .parse(
                                v,
                            ).atDay(1)
                            .atStartOfDay()
                            .let { Interval(it, it.plusMonths(1)) }
                    Granularity.DAY -> LocalDate.parse(v).atStartOfDay().let { Interval(it, it.plusDays(1)) }
                    Granularity.HOUR -> LocalDateTime.parse("$v:00").let { Interval(it, it.plusHours(1)) }
                    else -> LocalDateTime.parse(v).let { Interval(it, it.plusMinutes(1)) }
                },
            )
        } catch (e: DateTimeParseException) {
            Result.failure(IllegalArgumentException("the instant `${i.value}` is no date: ${e.message}"))
        }
    }

    fun periodCode(code: String): Boolean = PERIOD_CODE.matches(code)

    /** Why [relative] is outside the vocabulary; null when it is in it. */
    fun relativeProblem(relative: String): String? {
        val m =
            RELATIVE.matchEntire(relative.trim())
                ?: return "`$relative` is not `last(<n>,<unit>)`"
        val (n, unit) = m.destructured
        if (n.toBigInteger().signum() == 0) return "`$relative` counts no ${m.groupValues[2]}"
        if (unit !in RELATIVE_UNITS) {
            return "`$relative` counts in `$unit` — the units are ${RELATIVE_UNITS.sorted().joinToString(", ")}"
        }
        return null
    }
}
