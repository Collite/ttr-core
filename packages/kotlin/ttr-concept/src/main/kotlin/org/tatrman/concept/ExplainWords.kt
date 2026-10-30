// SPDX-License-Identifier: Apache-2.0
package org.tatrman.concept

import org.tatrman.concept.v1.Granularity
import org.tatrman.concept.v1.Instant
import java.time.LocalDate

/**
 * The explanations' words (CN-P3 S3.6, A-CN-52): English and Czech — the two locales the corpus carries; any other
 * locale reads English (`cn/explain-locale-fallback`). Function words only — concept names come from their labels
 * ([ExplainLabels]). Czech counts take their plural form (1 · 2–4 · 5+); a Czech phrase that would decline the label
 * after it is written as `<word>: <label>` instead, so a label always stands in its nominative.
 */
internal class ExplainWords private constructor(
    /** `en` or `cs`. */
    val code: String,
) {
    private val en = code == "en"

    private fun w(
        en: String,
        cs: String,
    ) = if (this.en) en else cs

    val and = w("and", "a")
    val or = w("or", "nebo")
    val not = w("not", "nikoli")
    val period = w("period", "období")
    val start = w("start", "začátek")
    val end = w("end", "konec")
    val at = w("time", "čas")
    val empty = w("empty", "prázdné")
    val isEmpty = w("is empty", "je prázdné")
    val isNotEmpty = w("is not empty", "není prázdné")
    val inList = w("in", "v")
    val notInList = w("not in", "mimo")
    val under = w("under", "pod")
    val notUnder = w("not under", "mimo")
    val exists = w("exists", "existuje")
    val notExists = w("no", "neexistuje")
    val involving = w("involving", "účastník")
    val asLabel = w("as", "jako")
    val where = w("where", "kde")
    val whileHolding = w("while", "dokud")
    val by = w("by", "řazeno:")
    val from = w("from", "od")
    val until = w("until", "do")
    val to = w("to", "do")
    val groupBy = w("by", "členění:")
    val orderedBy = w("ordered by", "řazení:")
    val descending = w("descending", "sestupně")
    val comparedWith = w("compared with", "srovnání:")
    val versus = "vs."
    val ongoing = w("ongoing", "trvá")
    val endUnknown = w("end unknown", "konec neznámý")
    val assertedAsOf = w("asserted as of", "tvrzeno k")
    val asOf = w("as of", "ke dni")
    val source = w("source", "zdroj")
    val refused = w("refused", "zamítnuto")
    val allTime = w("all time", "za celou dobu")

    /** An occurrence's instant: `at 2026-03-14 09:30` / `14. 3. 2026 09:30`. */
    fun atInstant(t: String) = w("at $t", t)

    fun involvingClause(who: String) = w(" involving $who", ", účastník $who")

    fun whereClause(p: String) = w(" where $p", ", kde $p")

    fun byClause(x: String) = w(", by $x", ", řazeno: $x")

    /** An aggregate's quality: `sum of net revenue in (…)` / `součet čistý obrat v (…)`. */
    fun memberIn(m: String) = w("$m in ", "$m v ")

    fun bool(b: Boolean) = if (b) w("true", "pravda") else w("false", "nepravda")

    fun mode(m: org.tatrman.statement.v1.CompareMode): String? =
        when (m) {
            org.tatrman.statement.v1.CompareMode.ABSOLUTE -> w("absolute", "absolutně")
            org.tatrman.statement.v1.CompareMode.DELTA -> w("difference", "rozdíl")
            org.tatrman.statement.v1.CompareMode.RATIO -> w("ratio", "poměr")
            else -> null
        }

    /** A concept aggregate's function word (`count` → `number of` / `počet`); null — not one the explain knows. */
    fun aggregate(function: String): String? =
        when (function) {
            "count" -> w("number of", "počet")
            "sum" -> w("sum of", "součet")
            "avg" -> w("average of", "průměr")
            "min" -> w("minimum of", "minimum")
            "max" -> w("maximum of", "maximum")
            "latest" -> w("latest", "poslední")
            else -> null
        }

    /** A DC-12 word (A-CN-14). */
    fun temporal(op: org.tatrman.ttrp.expr.TemporalOp): String =
        when (op) {
            org.tatrman.ttrp.expr.TemporalOp.AT_OR_BEFORE -> w("at or before", "nejpozději k")
            org.tatrman.ttrp.expr.TemporalOp.BEFORE -> w("before", "před")
            org.tatrman.ttrp.expr.TemporalOp.CONTAINS -> w("contains", "obsahuje")
            org.tatrman.ttrp.expr.TemporalOp.ENDED_BEFORE -> w("ended before", "skončilo před")
            org.tatrman.ttrp.expr.TemporalOp.OVERLAPS -> w("overlaps", "se překrývá s")
        }

    /** How a definition reads its instants, when it declares `imprecision:` (AM-42). */
    fun reading(certainly: Boolean) = if (certainly) w("certainly", "jistě") else w("possibly", "možná")

    // ---- counts ------------------------------------------------------------------------------------------------

    /**
     * `30 days` · `30 dní` · `1 den` — [unit] a TTR-P duration unit (`days`, `working_days`, …); [shown] — how the
     * amount reads (a parameter: `grace (= 30)`), its form still [n]'s.
     */
    fun count(
        n: Long,
        unit: String,
        shown: String = n.toString(),
    ): String = "$shown ${noun(n, unit)}"

    /** `last 30 days` · `posledních 30 dní` · `poslední 3 dny` · `last day` · `poslední den`. */
    fun last(
        n: Long,
        unit: String,
        shown: String = n.toString(),
    ): String =
        when {
            n == 1L && shown == "1" -> w("last ", "poslední ") + noun(1, unit)
            en -> "last $shown ${noun(n, unit)}"
            form(n) == 2 -> "posledních $shown ${noun(n, unit)}"
            else -> "poslední $shown ${noun(n, unit)}"
        }

    /** `within the last 30 days` · `za posledních 30 dní`. */
    fun withinLast(
        n: Long,
        unit: String,
        shown: String = n.toString(),
    ): String = w("within the ", "za ") + last(n, unit, shown)

    /** `first 10` · `prvních 10` · `první 3`. */
    fun first(n: Long): String =
        when {
            en -> "first $n"
            form(n) == 2 -> "prvních $n"
            else -> "první $n"
        }

    /** Czech plural form: 0 — one, 1 — two to four, 2 — five and more (and zero). */
    private fun form(n: Long): Int =
        when {
            n == 1L -> 0
            n in 2L..4L -> 1
            else -> 2
        }

    private fun noun(
        n: Long,
        unit: String,
    ): String {
        if (en) {
            val one = EN_UNITS[unit] ?: return unit
            return if (n == 1L) one else "${one}s"
        }
        return CS_UNITS[unit]?.get(form(n)) ?: unit
    }

    // ---- time --------------------------------------------------------------------------------------------------

    private fun month(m: Int): String = (if (en) EN_MONTHS else CS_MONTHS)[m - 1]

    /** `2026-01` → `January 2026` / `leden 2026`; `2026Q1` → `Q1 2026` / `1. čtvrtletí 2026`; `2026` as is. */
    fun periodCode(code: String): String {
        Regex("(\\d{4})-(\\d{2})").matchEntire(code)?.let { m ->
            val month = m.groupValues[2].toInt()
            if (month in 1..12) return "${month(month)} ${m.groupValues[1]}"
        }
        Regex("(\\d{4})Q([1-4])").matchEntire(code)?.let { m ->
            return w("Q${m.groupValues[2]} ${m.groupValues[1]}", "${m.groupValues[2]}. čtvrtletí ${m.groupValues[1]}")
        }
        return code
    }

    /** A day: `2026-03-14` / `14. 3. 2026`. */
    fun day(d: LocalDate): String = if (en) d.toString() else "${d.dayOfMonth}. ${d.monthValue}. ${d.year}"

    /** An instant at its granularity: `2026` · `March 2026` · `2026-03-14` · `2026-03-14 09h` · `2026-03-14 09:30`. */
    fun instant(i: Instant): String {
        val t = FrameTime.interval(i).getOrNull() ?: return i.value
        val lo = t.lo
        val hh = "%02d".format(lo.hour)
        return when (i.granularity) {
            Granularity.YEAR -> lo.year.toString()
            Granularity.MONTH -> "${month(lo.monthValue)} ${lo.year}"
            Granularity.DAY -> day(lo.toLocalDate())
            Granularity.HOUR -> "${day(lo.toLocalDate())} " + w("${hh}h", "$hh h")
            else -> "${day(lo.toLocalDate())} $hh:${"%02d".format(lo.minute)}"
        }
    }

    companion object {
        /** The locales the explanations speak. */
        val SUPPORTED: List<String> = listOf("en", "cs")

        /** The words for [locale] (BCP-47 — `cs-CZ` reads `cs`); null when the explanations have none for it. */
        fun of(locale: String): ExplainWords? =
            locale
                .substringBefore('-')
                .substringBefore('_')
                .lowercase()
                .takeIf { it in SUPPORTED }
                ?.let(::ExplainWords)

        val ENGLISH: ExplainWords = ExplainWords("en")

        private val EN_MONTHS =
            listOf(
                "January",
                "February",
                "March",
                "April",
                "May",
                "June",
                "July",
                "August",
                "September",
                "October",
                "November",
                "December",
            )
        private val CS_MONTHS =
            listOf(
                "leden",
                "únor",
                "březen",
                "duben",
                "květen",
                "červen",
                "červenec",
                "srpen",
                "září",
                "říjen",
                "listopad",
                "prosinec",
            )
        private val EN_UNITS =
            mapOf(
                "days" to "day",
                "working_days" to "working day",
                "weeks" to "week",
                "months" to "month",
                "minutes" to "minute",
                "hours" to "hour",
                "years" to "year",
            )
        private val CS_UNITS =
            mapOf(
                "days" to listOf("den", "dny", "dní"),
                "working_days" to listOf("pracovní den", "pracovní dny", "pracovních dní"),
                "weeks" to listOf("týden", "týdny", "týdnů"),
                "months" to listOf("měsíc", "měsíce", "měsíců"),
                "minutes" to listOf("minuta", "minuty", "minut"),
                "hours" to listOf("hodina", "hodiny", "hodin"),
                "years" to listOf("rok", "roky", "let"),
            )
    }
}
