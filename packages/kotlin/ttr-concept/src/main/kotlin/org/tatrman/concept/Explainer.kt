// SPDX-License-Identifier: Apache-2.0
package org.tatrman.concept

import org.tatrman.assertion.v1.Assertion
import org.tatrman.concept.v1.ConceptRef
import org.tatrman.concept.v1.EndState
import org.tatrman.concept.v1.MemberRef
import org.tatrman.concept.v1.Value
import org.tatrman.statement.v1.BoolOp
import org.tatrman.statement.v1.ComparisonOp
import org.tatrman.statement.v1.Compare
import org.tatrman.statement.v1.Filter
import org.tatrman.statement.v1.OrderLimit
import org.tatrman.statement.v1.Statement
import org.tatrman.statement.v1.TimeSpec
import org.tatrman.ttr.metadata.model.AutomationSchema
import org.tatrman.ttr.metadata.model.Concept
import org.tatrman.ttr.parser.model.AutoValue
import java.math.BigDecimal
import java.time.Clock
import java.time.LocalDate
import org.tatrman.ttr.metadata.model.EndState as ModelEndState

/**
 * `statementExplain` and `assertionExplain` (contracts §3; CN-P3 S3.6): the sentence a frame says, in the locale
 * ([ExplainWords] — en, cs; another locale reads en, `cn/explain-locale-fallback`). FROM THE FRAME, never from a
 * lowering: concept labels ([ExplainLabels]), members as spoken, instants at their granularity, a phase filter's
 * definition as prose ([DefinitionProse] — on by default, A-CN-54). No md / er element name is ever written, so a slot
 * bound through the md→er hop says nothing about it. A frame its validator refuses is still explained — what it claims,
 * then `refused:` and the findings' slugs and subjects (their messages, which name E-R elements, stay in
 * [Explanation.diagnostics] with the validator's findings).
 *
 * **A statement:** ` · `-joined, ending in `.`, every label lower-cased but the sentence's first (A-CN-52): measures;
 * filters (`<concept> <member>`, `<quality> <op> <value>`, `<phase>[: <definition>]`, `and` / `or` / `not` groups, an
 * expression filter quoted); `by <group-bys>`; the time; `as of <instant>`; the order and limit; the comparison.
 * **An assertion** (the inspector's sentence): the event (and a known identity's key values); each participant
 * `<label>: <Concept> <member>`; the period; each quality `<quality>: <value> <unit>`; `asserted as of <instant>`;
 * `source: <producer>`.
 */
internal class Explainer(
    private val model: ResolvedCnModel,
    private val clock: Clock,
) {
    private val statements = StatementValidator(model)
    private val assertions = AssertionValidator(model)
    private val lattice = ConceptChain(model.cn)

    /** The words for [locale] — English when it is blank — and the fallback finding when there are none for it. */
    private fun words(locale: String): Pair<ExplainWords, List<Diagnostic>> {
        if (locale.isBlank()) return ExplainWords.ENGLISH to emptyList()
        val w =
            ExplainWords.of(locale) ?: return ExplainWords.ENGLISH to
                listOf(
                    Diagnostic(
                        FALLBACK,
                        Severity.WARNING,
                        "no `$locale` words — explained in en (the explanations speak " +
                            ExplainWords.SUPPORTED.joinToString(", ") + ")",
                        "locale",
                    ),
                )
        return w to emptyList()
    }

    /** [said] and — refused — `— refused: <slug> (<subject>); …` (alone when the frame says nothing). */
    private fun sentence(
        words: ExplainWords,
        said: List<String>,
        ds: List<Diagnostic>,
    ): String {
        val text = ExplainLabels.capitalized(said.joinToString(" · "))
        val errors = ds.filter { it.severity == Severity.ERROR }
        if (errors.isEmpty()) return text
        val why = "${words.refused}: " + errors.joinToString("; ") { "${it.slug} (${it.subject})" }
        return if (text.isEmpty()) ExplainLabels.capitalized(why) else "$text — $why"
    }

    // ---- statements -------------------------------------------------------------------------------------------

    fun statement(
        s: Statement,
        locale: String,
        definitions: Boolean,
    ): Explanation {
        val (words, fallback) = words(locale.ifBlank { s.locale })
        val labels = ExplainLabels(model, words)
        val asOf =
            (if (s.hasAsOf()) FrameTime.evaluationDay(s.asOf) else null) ?: LocalDate.now(clock)
        val prose = DefinitionProse(model, words, labels, asOf).takeIf { definitions }
        val say = StatementSentence(words, labels, prose)
        val items = mutableListOf<String>()
        s.measuresList.forEach { items += labels.ref(it) }
        s.filtersList.forEach { items += say.filter(it, top = true) }
        if (s.groupByCount > 0) items += "${words.groupBy} " + s.groupByList.joinToString(", ") { labels.ref(it) }
        if (s.hasTime()) say.time(s.time)?.let { items += it }
        if (s.hasAsOf()) items += "${words.asOf} ${words.instant(s.asOf)}"
        if (s.hasOrderLimit()) say.order(s.orderLimit)?.let { items += it }
        if (s.hasCompare()) say.compare(s.compare)?.let { items += it }
        val ds = statements.validate(s)
        val text = sentence(words, items, ds) + "."
        return Explanation(words.code, text, fallback + ds)
    }

    private class StatementSentence(
        val words: ExplainWords,
        val labels: ExplainLabels,
        val prose: DefinitionProse?,
    ) {
        fun filter(
            f: Filter,
            top: Boolean,
        ): String =
            when (f.formCase) {
                Filter.FormCase.MEMBER -> "${labels.ref(f.member.concept)} ${member(words, f.member.member)}"
                Filter.FormCase.QUALITY ->
                    "${labels.ref(f.quality.quality)} ${OPS[f.quality.op] ?: "?"} ${value(words, f.quality.value)}"
                Filter.FormCase.PHASE -> {
                    val ref = f.phase.phase
                    val phase = labels.resolved(ref)
                    val definition = phase?.let { prose?.of(it) }?.let { ": $it" } ?: ""
                    (if (f.phase.negated) "${words.not} " else "") + labels.ref(ref) + definition
                }
                Filter.FormCase.GROUP -> {
                    val g = f.group
                    val inner = g.filtersList.map { filter(it, top = false) }
                    val said =
                        when (g.op) {
                            BoolOp.ALL -> inner.joinToString(" ${words.and} ")
                            BoolOp.ANY -> inner.joinToString(" ${words.or} ")
                            BoolOp.NOT -> "${words.not} (${inner.joinToString(" ${words.and} ")})"
                            else -> inner.joinToString(" ? ")
                        }
                    if (top || g.op == BoolOp.NOT) said else "($said)"
                }
                Filter.FormCase.EXPR -> "\"${f.expr.plExpr}\""
                else -> "?"
            }

        fun time(t: TimeSpec): String? =
            when (t.specCase) {
                TimeSpec.SpecCase.PERIOD_CODE -> words.periodCode(t.periodCode)
                TimeSpec.SpecCase.RANGE ->
                    listOfNotNull(
                        t.range.takeIf { it.hasFrom() }?.let { "${words.from} ${words.instant(it.from)}" },
                        t.range.takeIf { it.hasTo() }?.let { "${words.until} ${words.instant(it.to)}" },
                    ).joinToString(" ").ifEmpty { null }
                TimeSpec.SpecCase.RELATIVE ->
                    FrameTime.relative(t.relative)?.let { (n, unit) -> words.last(n, unit) } ?: "\"${t.relative}\""
                TimeSpec.SpecCase.ALL_TIME -> words.allTime.takeIf { t.allTime }
                else -> null
            }

        fun order(o: OrderLimit): String? {
            val keys =
                o.byList.joinToString(", ") { k ->
                    labels.ref(k.by) +
                        if (k.descending) " ${words.descending}" else ""
                }
            val limit = if (o.limit > 0) words.first(o.limit.toLong()) else null
            return when {
                keys.isEmpty() -> limit
                limit == null -> "${words.orderedBy} $keys"
                else -> "${words.orderedBy} $keys, $limit"
            }
        }

        fun compare(c: Compare): String? {
            val against =
                when (c.againstCase) {
                    Compare.AgainstCase.PERIOD_CODE -> words.periodCode(c.periodCode)
                    Compare.AgainstCase.MEMBERS ->
                        "${member(words, c.members.left)} ${words.versus} ${member(words, c.members.right)}"
                    else -> return null
                }
            return "${words.comparedWith} $against" + (words.mode(c.mode)?.let { " ($it)" } ?: "")
        }
    }

    // ---- assertions -------------------------------------------------------------------------------------------

    fun assertion(
        a: Assertion,
        locale: String,
    ): Explanation {
        val (words, fallback) = words(locale.ifBlank { a.locale })
        val labels = ExplainLabels(model, words)
        val event = labels.resolved(a.event)
        val items = mutableListOf<String>()
        val keys =
            if (a.identity.hasKnown()) {
                a.identity.known.valuesMap
                    .toSortedMap()
                    .values
                    .joinToString(", ")
            } else {
                ""
            }
        items += labels.ref(a.event, lower = false) + if (keys.isEmpty()) "" else " $keys"
        for (p in a.participantsList) {
            val concept =
                if (p.hasConcept()) {
                    labels.ref(p.concept, lower = false)
                } else {
                    event
                        ?.let { lattice.participant(it, p.label) }
                        ?.second
                        ?.concept
                        ?.let { model.cn.concepts[it] }
                        ?.let { labels.of(it) }
                }
            items += "${p.label.ifEmpty { "?" }}: " + listOfNotNull(concept, member(words, p.member)).joinToString(" ")
        }
        if (a.hasPeriod()) period(a, event, words)?.let { items += it }
        for (q in a.qualitiesList) {
            items += "${labels.ref(q.quality)}: ${value(words, q.value)}" + (if (q.unit.isEmpty()) "" else " ${q.unit}")
        }
        if (a.hasAssertedAsOf()) items += "${words.assertedAsOf} ${words.instant(a.assertedAsOf)}"
        if (a.provenance.producer.isNotEmpty()) items += "${words.source}: ${a.provenance.producer}"
        val ds = assertions.validate(a)
        val text = sentence(words, items, ds)
        return Explanation(words.code, text, fallback + ds)
    }

    /** `at <instant>` for an occurrence; `from <start> to <end>`, `(ongoing)`, `(end unknown)` for an episode. */
    private fun period(
        a: Assertion,
        event: Concept?,
        words: ExplainWords,
    ): String? {
        val p = a.period
        if (!p.hasStart()) return null
        val start = words.instant(p.start)
        val declared = event?.let { e -> lattice.chain(e).firstNotNullOfOrNull { it.period } }
        if (declared?.at != null || (declared == null && !p.hasEnd())) return words.atInstant(start)
        if (p.hasEnd()) return "${words.from} $start ${words.to} ${words.instant(p.end)}"
        val unknown =
            p.endState == EndState.END_UNKNOWN ||
                (p.endState == EndState.END_STATE_UNSPECIFIED && declared?.endState == ModelEndState.UNKNOWN)
        return "${words.from} $start (${if (unknown) words.endUnknown else words.ongoing})"
    }

    companion object {
        /** WARNING — the locale asked for has no words; the explanation is English (A-CN-52). */
        const val FALLBACK = "cn/explain-locale-fallback"

        private val OPS =
            mapOf(
                ComparisonOp.EQ to "=",
                ComparisonOp.NEQ to "!=",
                ComparisonOp.LT to "<",
                ComparisonOp.LTE to "<=",
                ComparisonOp.GT to ">",
                ComparisonOp.GTE to ">=",
            )

        /** A member as spoken (its resolved value when it has no label); an unresolved one asks: `E-9?`. */
        private fun member(
            words: ExplainWords,
            m: MemberRef,
        ): String {
            val shown = m.label.ifEmpty { m.resolvedValue }
            return if (m.resolvedValue.isEmpty()) "$shown?" else shown
        }

        /** A frame value as written: text bare, a number plain, an instant at its granularity, NULL `empty`. */
        private fun value(
            words: ExplainWords,
            v: Value,
        ): String =
            when {
                v.isNull -> words.empty
                v.vCase == Value.VCase.STRING_VALUE -> v.stringValue
                v.vCase == Value.VCase.INT_VALUE -> v.intValue.toString()
                v.vCase == Value.VCase.FLOAT_VALUE && v.floatValue.isFinite() ->
                    BigDecimal.valueOf(v.floatValue).stripTrailingZeros().toPlainString()
                v.vCase == Value.VCase.FLOAT_VALUE -> v.floatValue.toString()
                v.vCase == Value.VCase.BOOL_VALUE -> words.bool(v.boolValue)
                v.vCase == Value.VCase.INSTANT_VALUE -> words.instant(v.instantValue)
                else -> "?"
            }
    }
}

/**
 * What a concept is called in the locale (A-CN-52): its `label:` in the locale → the lexicon's term in the locale (a
 * `default` one first) → its English label → its name, humanized (`LargeSale` → `large sale`). A `def parameter` reads
 * its label in the locale, else its English label, else its name as written (`large_sale` — R-cn-7 names the
 * parameter the steward wrote).
 */
internal class ExplainLabels(
    model: ResolvedCnModel,
    private val words: ExplainWords,
) {
    private val byName: Map<String, List<Concept>> =
        model.cn.concepts.values
            .groupBy { it.qname.name }
    private val parameters =
        (model.model.schemas["automation"] as? AutomationSchema)
            ?.parameters
            ?.associateBy { it.qname.name }
            .orEmpty()

    fun of(c: Concept): String =
        c.label.byLanguage[words.code]?.takeIf { it.isNotBlank() }
            ?: c.lexiconLinks
                .filter { it.entryKind == "term" && it.locale == words.code && it.forms.isNotEmpty() }
                .sortedByDescending { it.default == true }
                .firstOrNull()
                ?.forms
                ?.first()
            ?: c.label.byLanguage["en"]?.takeIf { it.isNotBlank() }
            ?: humanized(c.qname.name)

    /** The one concept named [name] (any package); null for none or several. */
    fun named(name: String): Concept? = byName[name]?.singleOrNull()

    /** The concept [r] names, when it names exactly one. */
    fun resolved(r: ConceptRef): Concept? = if (r.candidatesCount > 0) null else named(r.qname.removePrefix("cn."))

    /** A frame ref read aloud: its label; an ambiguous one its candidates' (`net revenue or gross revenue?`). */
    fun ref(
        r: ConceptRef,
        lower: Boolean = true,
    ): String {
        fun shown(qname: String): String {
            val name = qname.removePrefix("cn.")
            val label = named(name)?.let(::of) ?: humanized(name.ifEmpty { "?" })
            return if (lower) lowerFirst(label) else label
        }
        if (r.candidatesCount > 0) return r.candidatesList.joinToString(" ${words.or} ") { shown(it.qname) } + "?"
        return shown(r.qname)
    }

    fun parameter(name: String): String {
        val label = parameters[name]?.label?.byLanguage
        return label?.get(words.code)?.takeIf { it.isNotBlank() } ?: label?.get("en")?.takeIf { it.isNotBlank() }
            ?: name
    }

    /** The world's value of `:name`, as written; null when it has none. */
    fun parameterValue(name: String): String? =
        when (val v = parameters[name]?.value) {
            is AutoValue.Str -> v.raw
            is AutoValue.Num -> v.text
            is AutoValue.Bool -> v.raw.toString()
            else -> null
        }

    companion object {
        /** `Net revenue` → `net revenue`; an acronym (`ICU`, `SNOMED code`) keeps its case. */
        fun lowerFirst(s: String): String =
            if (s.isEmpty() || (s.length > 1 && s[1].isUpperCase())) s else s[0].lowercaseChar() + s.substring(1)

        fun capitalized(s: String): String = if (s.isEmpty()) s else s[0].uppercaseChar() + s.substring(1)

        /** `LargeSale` → `large sale`; `transfer_kind` → `transfer kind`. */
        fun humanized(name: String): String =
            name
                .replace(Regex("([a-z0-9])([A-Z])"), "$1 $2")
                .replace('_', ' ')
                .lowercase()
    }
}
