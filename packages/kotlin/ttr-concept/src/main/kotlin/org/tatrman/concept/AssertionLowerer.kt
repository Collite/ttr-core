// SPDX-License-Identifier: Apache-2.0
package org.tatrman.concept

import org.tatrman.assertion.v1.Assertion
import org.tatrman.assertion.v1.Identity
import org.tatrman.assertion.v1.Participant
import org.tatrman.assertion.v1.QualityValue
import org.tatrman.concept.v1.EndState
import org.tatrman.concept.v1.Granularity
import org.tatrman.concept.v1.Instant
import org.tatrman.concept.v1.Value
import org.tatrman.ttr.metadata.model.Attribute
import org.tatrman.ttr.metadata.model.Concept
import org.tatrman.ttr.metadata.model.Entity
import org.tatrman.ttr.metadata.model.Relation
import java.math.BigDecimal
import java.time.format.DateTimeFormatter
import org.tatrman.ttr.metadata.model.EndState as ModelEndState

/**
 * `assertionLower` (cn contracts §2.3, §3; CN-P3 S3.5): the `Statement[<Entity>]` row payload — the event's row on its
 * entity, plus what the frame carries beside it. Data only: cn holds no write path (architecture §8), whoever owns the
 * write (a ledger, consolidation) executes it. It starts with [AssertionValidator] and refuses on any ERROR; the
 * validator runs [RowPlanner] once its own checks pass, so `assertionValidate` reports every refusal lowering makes.
 */
internal class AssertionLowerer(
    model: ResolvedCnModel,
) {
    private val validator = AssertionValidator(model)

    fun lower(a: Assertion): RowObjects {
        val checked = validator.check(a)
        val plan = checked.plan
        if (plan == null || checked.diagnostics.any { it.severity == Severity.ERROR }) {
            return RowObjects(diagnostics = checked.diagnostics)
        }
        return RowObjects(
            rows = listOf(RowObject(plan.entity, plan.values)),
            diagnostics = checked.diagnostics,
            assertedAsOf = if (a.hasAssertedAsOf()) a.assertedAsOf.value else null,
            meta =
                RowMeta(
                    provenance = if (a.hasProvenance()) a.provenance else null,
                    derivedFrom = a.derivedFromList,
                    snapshotHash = a.snapshotHash,
                    locale = a.locale,
                    endState = plan.endState,
                    verbatims = plan.verbatims,
                ),
            obligations = plan.obligations,
        )
    }
}

/** One asserted participant on its way to the row: its frame index, the participant, its path onto E-R. */
internal class PlannedParticipant(
    val index: Int,
    val participant: Participant,
    val path: ParticipantPaths.Path,
)

/** One asserted quality on its way to the row: its frame index, the quality, the attribute of the event's entity. */
internal class PlannedQuality(
    val index: Int,
    val quality: QualityValue,
    val attribute: String,
)

/**
 * A subkind's settable discriminator (⚑D, review-175 F7): its definition `<name> = '<value>'` read as the claimed row's
 * attribute [attribute] — the lowering sets it; a claim writing another value is `cn/slot-mismatch`.
 */
internal class Discriminator(
    val subkind: Concept,
    val name: String,
    val attribute: String,
    val value: String,
)

/** The event's row, planned: [values] by attribute, in writing order (identity, participants, period, qualities). */
internal class RowPlan(
    val entity: String,
    val values: Map<String, String?>,
    val obligations: List<Obligation>,
    val endState: EndState,
    val verbatims: Map<String, String>,
)

/**
 * The event's row (A-CN-47/48) — what [AssertionValidator] runs once its own checks pass:
 *
 * - **identity** — a known identity's key values; a NEW one none (consolidation assigns — AGEL CR-2);
 * - **participants** — FK values only (the P3 default — every participant carries a resolved member, validated): a
 *   one-hop path writes the member's value into the event entity's foreign key of that relation; a value kind (AM-27)
 *   into its column. A multi-hop path writes nothing — when another participant's one-hop path writes its first
 *   relation's key, it becomes an [Obligation] (the row it ends at must be the member — the writer checks it); else it
 *   is refused, as is a path whose first relation the event's row does not hold, or holds on several attributes (a
 *   member carries one value) — `cn/participant-path-broken`;
 * - **period** — the grounding's `period:` attributes: `at` / `start` the instant, a stored upper bound (`at_hi:` /
 *   `start_hi:`) the instant plus one granule (exclusive, A-CN-45); `end` the end, or — absent, the period open or
 *   unknown — SQL NULL with the end state in the payload's meta;
 * - **qualities** — the value as text, as the attribute's type takes it.
 *
 * An instant is written as the frame writes it, ISO-8601 at its granularity (`2026-03`, `2026-03-14T09:30`) — cn never
 * converts; the writer reads it as its interval's start. A value the attribute's type does not take, and NULL where
 * the attribute is not optional, are `cn/slot-mismatch`; an attribute written twice with two values (a period and a
 * quality on one column, say) is `cn/malformed-frame`.
 */
internal class RowPlanner(
    model: ResolvedCnModel,
) {
    private val lattice = ConceptChain(model.cn)
    private val paths = ParticipantPaths(model)

    fun plan(
        a: Assertion,
        event: Concept,
        entity: Entity,
        participants: List<PlannedParticipant>,
        qualities: List<PlannedQuality>,
        f: Findings,
        discriminators: List<Discriminator> = emptyList(),
    ): RowPlan {
        val row = Row(entity, f)
        val keys =
            if (a.identity.hasKnown()) {
                a.identity.known.valuesMap
                    .toSortedMap()
            } else {
                emptyMap()
            }
        for ((k, v) in keys) row.text(k, v, "identity.known")
        val obligations = participants(participants, a.identity.howCase == Identity.HowCase.NEW, row, f)
        val endState = period(a, event, row, f)
        val verbatims = linkedMapOf<String, String>()
        for (q in qualities) {
            row.value(q.attribute, q.quality.value, "qualities[${q.index}].value")
            if (q.quality.verbatim.isNotEmpty()) verbatims[q.attribute] = q.quality.verbatim
        }
        for (d in discriminators) {
            val by = row.writer(d.attribute)
            if (by != null && row.values[d.attribute] != d.value) {
                val written = row.values[d.attribute]?.let { "`$it`" } ?: "NULL"
                f.error(
                    FrameSlugs.SLOT_MISMATCH,
                    by,
                    "`${d.subkind.ref}` is `${d.name} = '${d.value}'` (its definition) — the claim writes $written",
                )
            } else {
                row.text(d.attribute, d.value, "concept")
            }
        }
        return RowPlan(entity.qname.name, row.values, obligations, endState, verbatims)
    }

    /**
     * A same-row participant (`label: []`, review-175 F1): the participant is the claim's own row, so its member is the
     * row's key — written to the entity's one key attribute. A `new` identity has no key yet (consolidation assigns
     * it), and a composite key takes more than a member's one value: both refuse.
     */
    private fun sameRow(
        p: PlannedParticipant,
        identityNew: Boolean,
        row: Row,
        f: Findings,
    ) {
        val subject = "participants[${p.index}]"
        val label = p.participant.label
        val name = row.entity.qname.name
        val keys = row.entity.attributes.filter { it.isKey }
        when {
            identityNew ->
                f.error(
                    FrameSlugs.MALFORMED_FRAME,
                    subject,
                    "`$label` is the claim's own `$name` row (`$label: []`) — its member names the row's key, " +
                        "but the identity is `new`",
                )
            keys.size != 1 ->
                f.error(
                    FrameSlugs.PARTICIPANT_PATH_BROKEN,
                    subject,
                    "`$label` is the claim's own `$name` row (`$label: []`), whose key is ${keys.size} attributes — " +
                        "a member carries one value",
                )
            else ->
                row.text(
                    keys
                        .single()
                        .qname.name
                        .substringAfterLast('.'),
                    p.participant.member.resolvedValue,
                    "$subject.member",
                )
        }
    }

    private fun participants(
        ps: List<PlannedParticipant>,
        identityNew: Boolean,
        row: Row,
        f: Findings,
    ): List<Obligation> {
        val name = row.entity.qname.name
        val onePath = mutableMapOf<String, PlannedParticipant>() // a first relation → the participant writing its key
        val multi = mutableListOf<Pair<PlannedParticipant, List<Relation>>>()
        for (p in ps) {
            val subject = "participants[${p.index}]"
            val label = p.participant.label
            val value = p.participant.member.resolvedValue
            when (val path = p.path) {
                is ParticipantPaths.Column ->
                    if (path.entity != name) {
                        f.error(
                            FrameSlugs.PARTICIPANT_PATH_BROKEN,
                            subject,
                            "`$label` is a column of `${path.entity}`, not of the event's entity `$name`",
                        )
                    } else {
                        row.text(path.attribute, value, "$subject.member")
                    }
                is ParticipantPaths.Relations -> {
                    val first = path.relations.firstOrNull()
                    if (first == null) {
                        sameRow(p, identityNew, row, f)
                        continue
                    }
                    val r = first.qname.name
                    when {
                        first.fromEntity.name != name ->
                            f.error(
                                FrameSlugs.PARTICIPANT_PATH_BROKEN,
                                subject,
                                "`$label`'s path starts with `$r`, whose key `${first.fromEntity.name}` holds — " +
                                    "the event's row holds no foreign key for it, and a lowering writes that row " +
                                    "only (A-CN-47)",
                            )
                        first.joinPairs.size != 1 ->
                            f.error(
                                FrameSlugs.PARTICIPANT_PATH_BROKEN,
                                subject,
                                "`$label`'s path starts with `$r`, which joins on ${first.joinPairs.size} " +
                                    "attributes — a member carries one value",
                            )
                        path.relations.size == 1 -> {
                            row.text(fk(first), value, "$subject.member")
                            onePath.putIfAbsent(r, p)
                        }
                        else -> multi += p to path.relations
                    }
                }
            }
        }
        return multi.mapNotNull { (p, rels) ->
            val first = rels.first()
            val anchor = onePath[first.qname.name]
            val label = p.participant.label
            if (anchor == null) {
                f.error(
                    FrameSlugs.PARTICIPANT_PATH_BROKEN,
                    "participants[${p.index}]",
                    "`$label` reaches `$name` only through `${first.qname.name}` — no asserted participant writes " +
                        "that relation's key, and the event's row holds no other for `$label` (A-CN-47)",
                )
                null
            } else {
                Obligation(
                    label = label,
                    value = p.participant.member.resolvedValue,
                    relations = rels.map { it.qname.name },
                    anchor = fk(first),
                    anchoredBy = anchor.participant.label,
                )
            }
        }
    }

    /** A one-pair relation's foreign key — the attribute its `from` side holds. */
    private fun fk(r: Relation): String =
        r.joinPairs
            .single()
            .fromAttr.name
            .substringAfterLast('.')

    /** The period onto the grounding's `period:` attributes; returns how an absent end reads. */
    private fun period(
        a: Assertion,
        event: Concept,
        row: Row,
        f: Findings,
    ): EndState {
        val declared = lattice.chain(event).firstNotNullOfOrNull { it.period } ?: return EndState.END_STATE_UNSPECIFIED
        if (!a.hasPeriod()) return EndState.END_STATE_UNSPECIFIED
        val bound = paths.grounding(event) { it.period != null }?.period
        val name = row.entity.qname.name

        fun attribute(
            ref: String?,
            what: String,
        ): String? {
            val (entity, attr) =
                ParticipantPaths.attributeRef(ref) ?: run {
                    f.error(
                        FrameSlugs.NOT_ASSERTABLE,
                        "period",
                        "`${event.ref}` declares the period's `$what`, but no `cn2er_event` names its attribute",
                    )
                    return null
                }
            if (entity != name) {
                f.error(
                    FrameSlugs.NOT_ASSERTABLE,
                    "period",
                    "`${event.ref}`'s period `$what` lands on `$entity.$attr`, not on the event's entity `$name`",
                )
                return null
            }
            return attr
        }

        fun instant(
            ref: String?,
            hi: String?,
            what: String,
            i: Instant,
            subject: String,
        ) {
            attribute(ref, what)?.let { row.instant(it, i, subject) }
            if (hi != null) attribute(hi, "${what}_hi")?.let { row.instant(it, next(i), subject) }
        }
        val p = a.period
        if (declared.at != null) {
            instant(bound?.at, bound?.atHi, "at", p.start, "period.start")
            return EndState.END_STATE_UNSPECIFIED
        }
        instant(bound?.start, bound?.startHi, "start", p.start, "period.start")
        if (declared.end == null) return EndState.END_STATE_UNSPECIFIED
        if (p.hasEnd()) {
            attribute(bound?.end, "end")?.let { row.instant(it, p.end, "period.end") }
            return EndState.END_STATE_UNSPECIFIED
        }
        attribute(bound?.end, "end")?.let { row.text(it, null, "period.end") }
        return when {
            p.endState == EndState.END_OPEN || p.endState == EndState.END_UNKNOWN -> p.endState
            declared.endState == ModelEndState.UNKNOWN -> EndState.END_UNKNOWN
            else -> EndState.END_OPEN
        }
    }

    /** [i] plus one granule, at [i]'s granularity — a stored upper bound (exclusive, A-CN-45). */
    private fun next(i: Instant): Instant {
        val hi = FrameTime.interval(i).getOrThrow().hi
        return Instant
            .newBuilder()
            .setValue(hi.format(SPELLING.getValue(i.granularity)))
            .setGranularity(i.granularity)
            .build()
    }

    /** The event's row as it is written — each attribute once. */
    private class Row(
        val entity: Entity,
        val f: Findings,
    ) {
        val values = linkedMapOf<String, String?>()
        private val by = mutableMapOf<String, String>()

        /** The frame subject that wrote [attr]; null when nothing has. */
        fun writer(attr: String): String? = by[attr]

        private fun attribute(name: String): Attribute? =
            entity.attributes.firstOrNull { it.qname.name.substringAfterLast('.') == name }

        private fun put(
            attr: String,
            value: String?,
            subject: String,
        ) {
            if (attr in values) {
                if (values[attr] != value) {
                    f.error(
                        FrameSlugs.MALFORMED_FRAME,
                        subject,
                        "`${entity.qname.name}.$attr` is written twice — ${shown(values[attr])} by ${by[attr]}, " +
                            "${shown(value)} by $subject",
                    )
                }
                return
            }
            values[attr] = value
            by[attr] = subject
        }

        private fun shown(v: String?): String = v?.let { "`$it`" } ?: "NULL"

        private fun mismatch(
            attr: String,
            subject: String,
            what: String,
        ) = f.error(
            FrameSlugs.SLOT_MISMATCH,
            subject,
            "`${entity.qname.name}.$attr` is ${attribute(attr)?.type ?: "?"} — it does not take $what",
        )

        /** Text (a key value, a member's value): checked against a numeric or boolean type; NULL where optional. */
        fun text(
            attr: String,
            value: String?,
            subject: String,
        ) {
            val type = attribute(attr)?.type
            if (value == null) {
                if (attribute(attr)?.nullable == false) {
                    f.error(
                        FrameSlugs.SLOT_MISMATCH,
                        subject,
                        "`${entity.qname.name}.$attr` is not optional — it does not take NULL",
                    )
                    return
                }
                return put(attr, null, subject)
            }
            val fits =
                when (family(type)) {
                    Family.INTEGER -> INTEGER.matches(value)
                    Family.DECIMAL -> value.toBigDecimalOrNull() != null
                    Family.BOOLEAN -> value == "true" || value == "false"
                    Family.TEMPORAL -> false
                    Family.TEXT, null -> true
                }
            if (!fits) {
                val how = if (family(type) == Family.TEMPORAL) " — an instant is written as an instant" else ""
                return mismatch(attr, subject, "`$value`$how")
            }
            put(attr, value, subject)
        }

        fun instant(
            attr: String,
            i: Instant,
            subject: String,
        ) {
            when (family(attribute(attr)?.type)) {
                Family.TEMPORAL, Family.TEXT, null -> put(attr, i.value, subject)
                else -> mismatch(attr, subject, "an instant")
            }
        }

        fun value(
            attr: String,
            v: Value,
            subject: String,
        ) {
            if (v.isNull) return text(attr, null, subject)
            val fam = family(attribute(attr)?.type)
            when (v.vCase) {
                Value.VCase.STRING_VALUE -> text(attr, v.stringValue, subject)
                Value.VCase.INT_VALUE ->
                    if (fam == Family.BOOLEAN || fam == Family.TEMPORAL) {
                        mismatch(attr, subject, "the integer `${v.intValue}`")
                    } else {
                        put(attr, v.intValue.toString(), subject)
                    }
                Value.VCase.FLOAT_VALUE ->
                    when {
                        !v.floatValue.isFinite() -> mismatch(attr, subject, "`${v.floatValue}`")
                        fam == Family.DECIMAL || fam == Family.TEXT || fam == null ->
                            put(attr, BigDecimal.valueOf(v.floatValue).stripTrailingZeros().toPlainString(), subject)
                        else -> mismatch(attr, subject, "the number `${v.floatValue}`")
                    }
                Value.VCase.BOOL_VALUE ->
                    if (fam == Family.BOOLEAN || fam == Family.TEXT || fam == null) {
                        put(attr, v.boolValue.toString(), subject)
                    } else {
                        mismatch(attr, subject, "`${v.boolValue}`")
                    }
                Value.VCase.INSTANT_VALUE -> instant(attr, v.instantValue, subject)
                else -> {} // no value: the validator refused it
            }
        }
    }

    private enum class Family { INTEGER, DECIMAL, BOOLEAN, TEMPORAL, TEXT }

    private companion object {
        val INTEGER = Regex("-?\\d+")

        /** The attribute type's family (the canonical er types and their db spellings); null — not known, any text. */
        fun family(type: String?): Family? =
            when (ErType.of(type)) {
                ErType.INTEGER -> Family.INTEGER
                ErType.DECIMAL -> Family.DECIMAL
                ErType.BOOLEAN -> Family.BOOLEAN
                ErType.DATE, ErType.TIMESTAMP -> Family.TEMPORAL
                ErType.TEXT -> Family.TEXT
                null -> null
            }

        val SPELLING: Map<Granularity, DateTimeFormatter> =
            mapOf(
                Granularity.YEAR to DateTimeFormatter.ofPattern("uuuu"),
                Granularity.MONTH to DateTimeFormatter.ofPattern("uuuu-MM"),
                Granularity.DAY to DateTimeFormatter.ofPattern("uuuu-MM-dd"),
                Granularity.HOUR to DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH"),
                Granularity.MINUTE to DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm"),
            )
    }
}
