// SPDX-License-Identifier: Apache-2.0
package org.tatrman.concept

import org.tatrman.assertion.v1.Assertion
import org.tatrman.assertion.v1.Identity
import org.tatrman.assertion.v1.QualityValue
import org.tatrman.concept.v1.EndState
import org.tatrman.concept.v1.Value
import org.tatrman.ttr.metadata.model.Basis
import org.tatrman.ttr.metadata.model.CnPeriod
import org.tatrman.ttr.metadata.model.Concept
import org.tatrman.ttr.metadata.model.ErSchema
import org.tatrman.ttr.metadata.model.GroundingLayer
import org.tatrman.ttr.metadata.model.QualifiedName
import org.tatrman.ttr.metadata.model.Via
import org.tatrman.ttr.metadata.model.EndState as ModelEndState

/**
 * `assertionValidate` (cn contracts §2.3, §3; CN-P3 S3.2): the event resolves and lands on E-R (`cn/not-assertable` —
 * a direct `cn2er_event` or the hop); a known identity names key attributes of the event's entity; every participant
 * carries an `involves:` label of the event (R-cn-5 — `cn/unknown-participant-label`), a concept compatible with the
 * label's, a resolved member, and a relation path the grounding chains (`cn/participant-path-broken`); every quality
 * belongs to the event and lands on the event's entity, in the unit its grounding declares (`cn/unit-mismatch`,
 * ⚑CN-P10 — cn never converts); the period is the one the event declares (`cn/missing-time` when absent); a state claim
 * names its `asserted_as_of` (`cn/missing-as-of`) and is consistent with it (`cn/basis-mismatch`).
 *
 * A **state claim** (AM-38): the event bears a `current_state` / `past_state` phase, or such a phase reads its period
 * in a state shape (the episode event — [CnDefinitionLints.BASIS_SHAPES]), or an asserted quality is `valued_state`.
 */
internal class AssertionValidator(
    private val model: ResolvedCnModel,
) {
    private val cn = model.cn
    private val er = model.model.schemas["er"] as? ErSchema
    private val lattice = ConceptChain(cn)

    /** The events a state-basis phase bears or reads the period of in a state shape. */
    private val stateEvents: Set<QualifiedName> by lazy {
        val out = mutableSetOf<QualifiedName>()
        for (p in cn.concepts.values) {
            if (p.stereotype != "phase" || (p.basis != Basis.CURRENT_STATE && p.basis != Basis.PAST_STATE)) continue
            p.of?.let { out += it }
            for (d in model.definitions.closure(p.qname)) {
                for (c in d.reads.comparisons) {
                    val shape = CnDefinitionLints.shapeOf(c)
                    if (shape != Basis.CURRENT_STATE && shape != Basis.PAST_STATE) continue
                    for (t in listOf(c.lhs, c.rhs)) {
                        when (t) {
                            is InstantTerm.Period -> out += t.owner
                            is InstantTerm.PeriodBound -> out += t.owner
                            else -> {}
                        }
                    }
                }
            }
        }
        out
    }

    fun validate(a: Assertion): List<Diagnostic> {
        val f = Findings()
        val refs = FrameRefs(model, FrameSlugs.AMBIGUOUS_ASSERTION, f)
        val event = refs.concept(a.event, "event", "event", setOf("event"))
        val landed = event?.let { landing(it, f) } ?: false
        identity(a.identity, event?.takeIf { landed }, f)
        val labels = mutableSetOf<String>()
        a.participantsList.forEachIndexed { i, p ->
            val subject = "participants[$i]"
            val declared =
                when {
                    event == null -> null
                    p.label.isEmpty() -> {
                        f.error(FrameSlugs.UNKNOWN_PARTICIPANT_LABEL, subject, "the participant has no label (R-cn-5)")
                        null
                    }
                    !labels.add(p.label) -> {
                        f.error(FrameSlugs.MALFORMED_FRAME, subject, "the label `${p.label}` is asserted twice")
                        null
                    }
                    else ->
                        lattice.participant(event, p.label)?.second ?: run {
                            val known =
                                lattice
                                    .chain(event)
                                    .flatMap { it.involves }
                                    .map { it.label }
                                    .distinct()
                            f.error(
                                FrameSlugs.UNKNOWN_PARTICIPANT_LABEL,
                                subject,
                                "`${event.ref}` involves no `${p.label}` — its labels are " +
                                    known.joinToString(", ") { "`$it`" } + " (R-cn-5)",
                            )
                            null
                        }
                }
            val want = declared?.concept?.let { cn.concepts[it] }
            if (p.hasConcept()) {
                val got = refs.concept(p.concept, "$subject.concept", "participant", PARTICIPANT)
                if (got != null && want != null && !lattice.compatible(want, got)) {
                    f.error(
                        FrameSlugs.SLOT_MISMATCH,
                        "$subject.concept",
                        "`${event!!.ref}`'s participant `${p.label}` is a `${want.qname.name}`, not a `${got.qname.name}`",
                    )
                }
            }
            val of = "`${p.label.ifEmpty { "?" }}`"
            if (!p.hasMember()) {
                f.error(FrameSlugs.AMBIGUOUS_ASSERTION, "$subject.member", "the participant $of names no member")
            } else {
                refs.member(p.member, "$subject.member", "the participant $of")
            }
            if (declared != null && landed) path(event!!, p.label, subject, f)
        }
        val asserted = a.qualitiesList.mapIndexedNotNull { i, q -> quality(q, "qualities[$i]", event, landed, refs, f) }
        if (event != null) period(a, event, f)
        val asOf = if (a.hasAssertedAsOf()) instant(a.assertedAsOf, "assertedAsOf", f) else null
        if (event != null &&
            (lattice.chain(event).any { it.qname in stateEvents } || asserted.any { it.valuedState })
        ) {
            if (!a.hasAssertedAsOf()) {
                f.error(
                    FrameSlugs.MISSING_AS_OF,
                    "assertedAsOf",
                    "asserting `${event.ref}` is a state claim — it names the instant it claims the state for (AM-38)",
                )
            } else if (asOf != null && a.hasPeriod() && a.period.hasStart()) {
                val start = FrameTime.interval(a.period.start).getOrNull()
                if (start != null && !start.lo.isBefore(asOf.hi)) {
                    f.error(
                        FrameSlugs.BASIS_MISMATCH,
                        "period.start",
                        "the state is claimed as of `${a.assertedAsOf.value}` but starts `${a.period.start.value}`, " +
                            "after it — it does not hold then",
                    )
                }
            }
        }
        if (a.snapshotHash.isNotEmpty() && a.snapshotHash != model.snapshotHash) {
            f.warning(
                FrameSlugs.SNAPSHOT_MISMATCH,
                "snapshotHash",
                "the frame was built on snapshot `${a.snapshotHash}`; this model is `${model.snapshotHash}`",
            )
        }
        return f.sorted()
    }

    /** The event's E-R landing — `cn/not-assertable` when it has none; true when it lands. */
    private fun landing(
        e: Concept,
        f: Findings,
    ): Boolean {
        if (e.erTarget != null) return true
        val why =
            e.hop
                ?.message
                ?.takeIf { it.isNotEmpty() }
                ?.let { " — $it" }
                ?: e.grounding?.let { "" }
                ?: " — it has no grounding"
        f.error(
            FrameSlugs.NOT_ASSERTABLE,
            "event",
            "`${e.ref}` has no E-R landing (${FrameRefs.grade(e.grade)})$why: an assertion writes E-R rows",
        )
        return false
    }

    private fun entityOf(e: Concept): org.tatrman.ttr.metadata.model.Entity? {
        val name = e.erTarget?.split('.')?.getOrNull(2) ?: return null
        val all =
            er
                ?.entities
                ?.values
                ?.filter { it.qname.name == name }
                .orEmpty()
        return all.firstOrNull { it.qname.`package` == e.qname.`package` } ?: all.singleOrNull()
    }

    private fun attributeName(a: org.tatrman.ttr.metadata.model.Attribute): String =
        a.qname.name.substringAfterLast('.')

    private fun identity(
        id: Identity,
        landedEvent: Concept?,
        f: Findings,
    ) {
        when (id.howCase) {
            Identity.HowCase.NEW ->
                if (!id.new) {
                    f.error(
                        FrameSlugs.MALFORMED_FRAME,
                        "identity",
                        "`new: false` names no identity",
                    )
                }
            Identity.HowCase.KNOWN -> {
                val values = id.known.valuesMap
                if (values.isEmpty()) {
                    f.error(FrameSlugs.MALFORMED_FRAME, "identity.known", "a known identity with no key values")
                    return
                }
                val entity = landedEvent?.let { entityOf(it) } ?: return
                val keys =
                    entity.attributes
                        .filter { it.isKey }
                        .map(::attributeName)
                        .toSet()
                for (k in values.keys.sorted()) {
                    if (k !in keys) {
                        f.error(
                            FrameSlugs.MALFORMED_FRAME,
                            "identity.known",
                            "`$k` is no key attribute of `${landedEvent.erTarget}` — its key is " +
                                keys.sorted().joinToString(", ") { "`$it`" },
                        )
                    }
                }
            }
            else ->
                f.error(
                    FrameSlugs.MALFORMED_FRAME,
                    "identity",
                    "the assertion names no identity — known keys or `new`",
                )
        }
    }

    /** The label's relation path in the event's direct `cn2er_event` (L15-3). */
    private fun path(
        event: Concept,
        label: String,
        subject: String,
        f: Findings,
    ) {
        val g = event.grounding
        if (event.via != Via.DIRECT || g == null || g.layer != GroundingLayer.ER) {
            f.error(
                FrameSlugs.PARTICIPANT_PATH_BROKEN,
                subject,
                "`${event.ref}` lands on E-R through the hop — no grounding states `$label`'s relation path",
            )
            return
        }
        if (label !in g.participants) {
            f.error(
                FrameSlugs.PARTICIPANT_PATH_BROKEN,
                subject,
                "`${g.qname.name}` has no relation path that chains for `$label` (`participants:`)",
            )
        }
    }

    /**
     * One asserted quality: the concept (a `def quality` of the event, or a name in its `qualities:`), its E-R target on
     * the event's entity, its value and unit. Returns the quality concept, when there is one.
     */
    private fun quality(
        q: QualityValue,
        subject: String,
        event: Concept?,
        landed: Boolean,
        refs: FrameRefs,
        f: Findings,
    ): Concept? {
        if (!q.hasValue() || (q.value.vCase == Value.VCase.V_NOT_SET && !q.value.isNull)) {
            f.error(FrameSlugs.MALFORMED_FRAME, "$subject.value", "the quality is asserted with no value")
        } else if (q.value.vCase == Value.VCase.INSTANT_VALUE) {
            instant(q.value.instantValue, "$subject.value", f)
        }
        if (!refs.unambiguous(q.quality, "$subject.quality", "quality") || event == null) return null
        val qs = "$subject.quality"
        val name =
            refs.nameOf(q.quality.qname) ?: return refs.single(q.quality.qname, qs, "quality").let { null }
        val chain = lattice.chain(event)
        val owners = chain.map { it.qname }.toSet()
        val named = refs.lookup(name)
        val def = named.filter { it.stereotype == "quality" && it.of in owners }
        val c: Concept?
        when {
            def.size == 1 -> c = def.single()
            def.size > 1 -> return refs.single(q.quality.qname, qs, "quality").let { null }
            chain.any { name in it.qualities } -> c = null // a soft quality: the event entity's attribute of that name
            named.isEmpty() -> {
                f.error(
                    FrameSlugs.AMBIGUOUS_ASSERTION,
                    qs,
                    "`${q.quality.qname}` names no concept and no quality of `${event.ref}`",
                )
                return null
            }
            else -> {
                val other = named.first()
                val what =
                    if (other.stereotype == "quality") {
                        "a quality of `${other.of?.name ?: "?"}`"
                    } else {
                        "a ${other.stereotype}"
                    }
                f.error(FrameSlugs.SLOT_MISMATCH, qs, "`${q.quality.qname}` is $what, not a quality of `${event.ref}`")
                return null
            }
        }
        if (c?.definition != null) {
            f.error(FrameSlugs.NOT_ASSERTABLE, qs, "`${c.ref}` is defined (`definition:`) — computed, never asserted")
            return c
        }
        if (landed) target(c, name, event, qs, f)
        unit(q, c, subject, f)
        return c
    }

    /** The quality's E-R target: its grounding's, else the event entity's attribute of its name — on the event's entity. */
    private fun target(
        c: Concept?,
        name: String,
        event: Concept,
        subject: String,
        f: Findings,
    ) {
        val entity =
            event.erTarget
                ?.split('.')
                ?.take(3)
                ?.joinToString(".")
        val target =
            c?.erTarget
                ?: entityOf(event)?.attributes?.firstOrNull { attributeName(it) == name }?.let { "$entity.$name" }
        when {
            target == null ->
                f.error(
                    FrameSlugs.NOT_ASSERTABLE,
                    subject,
                    "`cn.$name` has no E-R target — no `cn2er_quality`, and `$entity` has no attribute `$name`",
                )
            target.split('.').take(3).joinToString(".") != entity ->
                f.error(
                    FrameSlugs.NOT_ASSERTABLE,
                    subject,
                    "`cn.$name` lands on `$target`, not on the event's entity `$entity` — its row cannot carry it",
                )
        }
    }

    /** ⚑CN-P10: an asserted unit must be the one the grounding declares; cn never converts. */
    private fun unit(
        q: QualityValue,
        c: Concept?,
        subject: String,
        f: Findings,
    ) {
        if (q.unit.isEmpty()) return
        val declared = c?.grounding?.unit
        val name = c?.ref ?: q.quality.qname
        when {
            declared == null ->
                f.error(
                    FrameSlugs.UNIT_MISMATCH,
                    "$subject.unit",
                    "the value is in `${q.unit}`, but `$name`'s grounding declares no unit — cn does not convert",
                )
            declared != q.unit ->
                f.error(
                    FrameSlugs.UNIT_MISMATCH,
                    "$subject.unit",
                    "`$name` is in `$declared`; the value is in `${q.unit}` — cn does not convert",
                )
        }
    }

    /** The period the event declares (AM-19, AM-43): present when declared, its end as `end_open:` reads. */
    private fun period(
        a: Assertion,
        event: Concept,
        f: Findings,
    ) {
        val declared: CnPeriod? = lattice.chain(event).firstNotNullOfOrNull { it.period }
        if (declared == null) {
            if (a.hasPeriod()) {
                f.error(
                    FrameSlugs.MALFORMED_FRAME,
                    "period",
                    "`${event.ref}` declares no `period:` — nothing carries one",
                )
            }
            return
        }
        if (!a.hasPeriod()) {
            f.error(FrameSlugs.MISSING_TIME, "period", "`${event.ref}` declares `period:` — the assertion names none")
            return
        }
        val p = a.period
        val start = if (p.hasStart()) instant(p.start, "period.start", f) else null
        val end = if (p.hasEnd()) instant(p.end, "period.end", f) else null
        if (!p.hasStart()) f.error(FrameSlugs.MISSING_TIME, "period.start", "the period has no start")
        if (declared.at != null) {
            if (p.hasEnd() || p.endState != EndState.END_STATE_UNSPECIFIED) {
                f.error(
                    FrameSlugs.MALFORMED_FRAME,
                    "period.end",
                    "`${event.ref}` is instantaneous (`period: { at }`) — its instant is the start, with no end",
                )
            }
            return
        }
        if (p.hasEnd()) {
            if (p.endState != EndState.END_STATE_UNSPECIFIED && p.endState != EndState.END_CLOSED) {
                f.error(
                    FrameSlugs.MALFORMED_FRAME,
                    "period.endState",
                    "the period has an end, but says `${p.endState}`",
                )
            }
            if (start != null && end != null && end.lo.isBefore(start.lo)) {
                f.error(FrameSlugs.MALFORMED_FRAME, "period.end", "the period ends before it starts")
            }
            return
        }
        val reads = declared.endState
        val want =
            when (reads) {
                ModelEndState.OPEN -> EndState.END_OPEN
                ModelEndState.UNKNOWN -> EndState.END_UNKNOWN
                else -> null
            }
        when {
            p.endState == EndState.END_CLOSED ->
                f.error(
                    FrameSlugs.MALFORMED_FRAME,
                    "period.endState",
                    "`END_CLOSED` says the end is present — there is none",
                )
            want == null ->
                f.error(
                    FrameSlugs.MISSING_TIME,
                    "period.end",
                    "`${event.ref}`'s period has no `end_open:` — its end is always present (A-CN-19)",
                )
            p.endState != EndState.END_STATE_UNSPECIFIED && p.endState != want ->
                f.error(
                    FrameSlugs.MALFORMED_FRAME,
                    "period.endState",
                    "`${event.ref}`'s absent end reads `${reads!!.name.lowercase()}` (`end_open:`), not `${p.endState}`",
                )
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
        val PARTICIPANT = setOf("kind", "role", "event", "phase")
    }
}
