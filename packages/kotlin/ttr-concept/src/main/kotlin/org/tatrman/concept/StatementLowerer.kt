// SPDX-License-Identifier: Apache-2.0
package org.tatrman.concept

import org.tatrman.concept.v1.ConceptRef
import org.tatrman.concept.v1.Granularity
import org.tatrman.concept.v1.Value
import org.tatrman.plan.v1.AggregateCall
import org.tatrman.plan.v1.AggregateNode
import org.tatrman.plan.v1.ColumnRef
import org.tatrman.plan.v1.Expression
import org.tatrman.plan.v1.FilterNode
import org.tatrman.plan.v1.FunctionCall
import org.tatrman.plan.v1.LimitOffsetNode
import org.tatrman.plan.v1.Literal
import org.tatrman.plan.v1.NamedExpression
import org.tatrman.plan.v1.PlanNode
import org.tatrman.plan.v1.ProjectNode
import org.tatrman.plan.v1.SortKey
import org.tatrman.plan.v1.SortNode
import org.tatrman.plan.v1.SubqueryExpression
import org.tatrman.statement.v1.BoolOp
import org.tatrman.statement.v1.ComparisonOp
import org.tatrman.statement.v1.Filter
import org.tatrman.statement.v1.PhaseFilter
import org.tatrman.statement.v1.Statement
import org.tatrman.statement.v1.TimeSpec
import org.tatrman.ttr.md.resolve.CanonicalRenderer
import org.tatrman.ttr.md.resolve.DefaultMdPathResolver
import org.tatrman.ttr.md.resolve.PathComponent
import org.tatrman.ttr.md.resolve.PathShape
import org.tatrman.ttr.md.resolve.ResolutionOutcome
import org.tatrman.ttr.metadata.model.Concept
import org.tatrman.ttr.semantics.md.AttrBinding
import org.tatrman.ttr.semantics.md.MD_CATALOG_VERSION
import org.tatrman.ttr.semantics.md.MdCubelet
import org.tatrman.ttrp.emit.sql.MdLoweringException
import org.tatrman.ttrp.emit.sql.MdPathLowering
import org.tatrman.ttrp.resolve.MdRepo
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * `statementLower` (CN-P3 S3.3; contracts §3): a statement that validates ([StatementValidator] — any ERROR refuses,
 * its findings in the result) → an md dot-path on ONE carrying cubelet → `plan.v1` through the md chain
 * (`DefaultMdPathResolver` + `MdPathLowering`), with the provenance that reproduces it.
 *
 *  1. **Slots** (T1, [MdFace]): the measure, each group-by and member / quality filter reach md through their one md
 *     grounding, or a value kind / an er-grounded concept through the reverse hop (⚑CN-P8, A-CN-37).
 *  2. **The carrier** (T2): among the cubelets carrying the measure and every slot's dimension, the grain event's
 *     (the measure's bearer's cubelet) wins; else the one; none ⇒ `cn/not-analytically-reachable`, several ⇒
 *     `cn/ambiguous-statement` with the candidates — never the first.
 *  3. **Coordinates** (T3): a member pins the dimension's grain attribute on the carrier (`customer.name: "Kaufland"`);
 *     a group-by frees it (`*`); a quality filter on an md attribute pins it (`EQ` only), one on the measure is a
 *     HAVING over the read; time binds to the grain event's `period:` quality when it is an md attribute of the carrier,
 *     else the carrier's one date attribute — a period code through the model's calc maps (`year`, `month`,
 *     `quarter`; the day range when there is none), a range half-open → inclusive (`to − 1 day`), `last(n,unit)`
 *     back from the evaluation day; a phase filter renders on md ([MdRenderer]).
 *  4. **Resolve + lower** (T4): `Ambiguous` ⇒ `cn/ambiguous-statement`, `Failed` / `MdLoweringException` ⇒
 *     `cn/not-analytically-reachable` naming the md code; a same-cubelet existence composes as ⚑CN-P9's grain-key
 *     semi-join; `order_limit` sorts and limits on the measure.
 *
 * 5. **The er lane** (CN-P6 ⚑S62-1, A-CN-101): when md carries no single read — the validator finds a slot md cannot
 *    reach (`cn/not-analytically-reachable`, `cn/definition-unreachable(md)`) and nothing else, or the md lowering refuses
 *    (no carrier, several, …) — the statement is read on E-R instead ([ErRenderer.statement]): the grain event's landing,
 *    the measures aggregated by their md measure's aggregation, phases inlined on the event's row, the time on its
 *    period's day. The result carries [LowerResult.erSql] and the advisory `cn/lowered-on-er` saying why md did not; a
 *    statement neither lane reads is refused with both lanes' findings, md's first.
 *
 * The evaluation day is the frame's `as_of`, else today on [clock] (recorded when read). Everything here is a pure
 * function of the model and the frame (architecture §7) — the clock is the one input beside them.
 */
internal class StatementLowerer(
    private val model: ResolvedCnModel,
    private val clock: Clock,
) {
    private val validator = StatementValidator(model)
    private val renderer = MdRenderer(model)
    private val chain = ConceptChain(model.cn)

    fun lower(s: Statement): LowerResult {
        val findings = validator.validate(s)
        val errors = findings.filter { it.severity == Severity.ERROR }
        // a slot md cannot reach is no refusal yet — E-R may read the statement (A-CN-101); any other error is one
        if (errors.any { it.slug !in MD_REACH }) return LowerResult(diagnostics = findings)
        val kept = findings - errors.toSet()
        if (errors.isNotEmpty()) return ErLane(s).lower(kept, because = errors)
        val mdRefusal =
            try {
                val md =
                    model.md
                        ?: refuse(
                            FrameSlugs.NOT_ANALYTICALLY_REACHABLE,
                            "measures[0]",
                            "the model declares no md tier (no cubelet) — a statement lowers through md (AM-4)",
                        )
                return Lowering(s, md).run(findings)
            } catch (r: Refused) {
                r.diagnostics
            }
        return ErLane(s).lower(kept, because = mdRefusal)
    }

    private class Refused(
        val diagnostics: List<Diagnostic>,
    ) : RuntimeException(diagnostics.first().message, null, false, false)

    private fun refuse(
        slug: String,
        subject: String,
        message: String,
        vararg causes: Diagnostic,
    ): Nothing = throw Refused(listOf(Diagnostic(slug, Severity.ERROR, message, subject)) + causes)

    private fun concept(ref: ConceptRef): Concept {
        val name = ref.qname.removePrefix("cn.")
        return model.cn.concepts.values
            .single { it.qname.name == name } // validated: one, unambiguous
    }

    /** The measure's bearer event (`of:`, else the one event listing it in `qualities:`). */
    private fun grainEvent(measure: Concept): Concept? {
        measure.of
            ?.let { model.cn.concepts[it] }
            ?.takeIf { it.stereotype == "event" }
            ?.let { return it }
        return model.cn.concepts.values
            .filter { it.stereotype == "event" && measure.qname.name in it.qualities }
            .singleOrNull()
    }

    /** The frame's evaluation day: its `as_of` (the last day when coarser than a day), else today on the clock. */
    private fun evaluationDay(s: Statement): LocalDate =
        if (s.hasAsOf()) checkNotNull(FrameTime.evaluationDay(s.asOf)) { "as_of validated" } else LocalDate.now(clock)

    /** One slot's ask of the carrier, in frame order: a coordinate, a phase to render, or a HAVING on the measure. */
    private sealed interface Ask {
        val slot: String

        data class Coordinate(
            override val slot: String,
            val concept: Concept,
            val faces: List<MdFace>,
            val target: PathComponent,
        ) : Ask

        data class Phase(
            override val slot: String,
            val filter: PhaseFilter,
        ) : Ask

        data class Having(
            override val slot: String,
            val face: MdFace,
            val op: ComparisonOp,
            val value: Value,
        ) : Ask
    }

    private inner class Lowering(
        val s: Statement,
        val md: MdRepo.Loaded,
    ) {
        val paths = mutableListOf<BindingPath>()
        val inlined = linkedSetOf<String>()
        val params = linkedMapOf<String, String>()
        var asOfRead = false

        /** The evaluation day: the frame's `as_of` (its last day when coarser than a day), else today on the clock. */
        val asOfDay: LocalDate by lazy {
            if (!s.hasAsOf()) return@lazy LocalDate.now(clock)
            checkNotNull(FrameTime.evaluationDay(s.asOf)) { "as_of validated" }
        }

        fun run(warnings: List<Diagnostic>): LowerResult {
            if (s.hasCompare()) {
                refuse(
                    FrameSlugs.NOT_ANALYTICALLY_REACHABLE,
                    "compare",
                    "a `compare` combines two md reads — not lowered in P3 (refused, never half-answered)",
                )
            }
            if (s.measuresCount > 1) {
                refuse(
                    FrameSlugs.NOT_ANALYTICALLY_REACHABLE,
                    "measures[1]",
                    "a dot-path reads one measure — `${s.getMeasures(1).qname}` is a second; ask it on its own",
                )
            }
            val measureConcept = concept(s.getMeasures(0))
            val measureFaces = MdFace.of(measureConcept)
            val measureFace =
                measureFaces.firstOrNull { it.element is MdElement.Measure }
                    ?: refuse(
                        FrameSlugs.NOT_ANALYTICALLY_REACHABLE,
                        "measures[0]",
                        "`${measureConcept.ref}` grounds on " +
                            "${measureFaces.firstOrNull()?.element?.written ?: "nothing"} — a measure grounds on an md measure",
                    )
            val measure = (measureFace.element as MdElement.Measure).name
            paths += measureFace.path("measures[0]")

            val asks = mutableListOf<Ask>()
            s.filtersList.forEachIndexed { i, f -> filter(f, "filters[$i]", asks) }
            s.groupByList.forEachIndexed { i, ref ->
                val c = concept(ref)
                asks +=
                    Ask.Coordinate(
                        "groupBy[$i]",
                        c,
                        coordinateFaces(c, "groupBy[$i]", "a group-by"),
                        PathComponent.Star,
                    )
            }

            val event = grainEvent(measureConcept)
            val periodFace =
                event?.let { periodQuality(it) }?.let { q ->
                    MdFace.of(q).firstOrNull { it.element is MdElement.Attribute }
                }
            val timed =
                s.time.specCase != TimeSpec.SpecCase.ALL_TIME && s.time.specCase != TimeSpec.SpecCase.SPEC_NOT_SET
            val carrier = carrier(measure, asks, event, periodFace.takeIf { timed })

            val coords = mutableListOf<Coord>()
            val semiJoins = mutableListOf<Pair<String, MdRenderer.SemiJoin>>() // the phase filter's subject → it
            val phased = mutableListOf<Pair<Ask.Phase, Coord>>() // what each phase filter pins on the carrier
            val havings = mutableListOf<Ask.Having>()
            for (a in asks) {
                when (a) {
                    is Ask.Coordinate -> coords += bind(a, carrier)
                    is Ask.Phase -> {
                        val p = phase(a, carrier)
                        coords += p.coordinates
                        phased += p.coordinates.map { a to it }
                        semiJoins += p.semiJoins.map { "${a.slot}.phase.phase" to it }
                    }
                    is Ask.Having -> {
                        if (a.face.element != measureFace.element) {
                            refuse(
                                FrameSlugs.NOT_ANALYTICALLY_REACHABLE,
                                "${a.slot}.quality.quality",
                                "a having-filter on `${a.face.concept.ref}` — the read aggregates `${measureConcept.ref}` only",
                            )
                        }
                        paths += a.face.path(a.slot)
                        havings += a
                    }
                }
            }
            if (timed) {
                val t = time(carrier, periodFace)
                // a phase filter and the time on one attribute: cn's refusal, not the md resolver's MD-006 (R125-8)
                for (c in t) {
                    val (phase, pinned) = phased.firstOrNull { it.second.attribute == c.attribute } ?: continue
                    refuse(
                        FrameSlugs.NOT_ANALYTICALLY_REACHABLE,
                        "${phase.slot}.phase.phase",
                        "`${phase.filter.phase.qname}` constrains `${pinned.spelled}`, which the statement's time " +
                            "constrains too — a follow-up (intersecting the two)",
                    )
                }
                coords += t
            }

            // ⚑CN-P9: the outer read keeps each semi-join's key free; a key the statement already pins cannot be both
            val requested = coords.filter { it.target == PathComponent.Star }.map { it.attribute }.toSet()
            val keys = semiJoins.associate { (subject, sj) -> sj.key to keyColumn(carrier, sj.key, subject) }
            for ((subject, sj) in semiJoins) {
                val clash = coords.firstOrNull { it.attribute == sj.key && it.target != PathComponent.Star }
                if (clash != null) {
                    refuse(
                        FrameSlugs.NOT_ANALYTICALLY_REACHABLE,
                        subject,
                        "the existence is keyed by `${clash.spelled}`, which the statement pins too — a follow-up",
                    )
                }
                if (coords.none { it.attribute == sj.key }) coords += Coord(sj.key, PathComponent.Star)
            }

            val (outerText, outerPlan) = read(carrier, coords, measure, requested + keys.keys)
            var plan = outerPlan
            var text = outerText
            if (semiJoins.isNotEmpty()) {
                val ins =
                    semiJoins.map { (_, sj) ->
                        val inner = listOf(Coord(sj.key, PathComponent.Star)) + sj.coordinates
                        val (innerText, innerPlan) = read(carrier, inner, measure, setOf(sj.key))
                        "${Coord(sj.key, PathComponent.Star).spelled} in ($innerText)" to
                            inKeys(innerPlan, keys.getValue(sj.key))
                    }
                plan = semiJoin(plan, ins.map { it.second }, keys.filterKeys { it !in requested }.values.toSet())
                text += " where " + ins.joinToString(" and ") { it.first }
            }
            for (h in havings) {
                plan = filter(plan, call(OPS.getValue(h.op), column(measure), literal(h.value, h.slot)))
                text += " having $measure ${SPELLING.getValue(h.op)} ${valueText(h.value, h.slot)}"
            }
            if (s.hasOrderLimit()) {
                val ol = s.orderLimit
                if (ol.byCount > 0) {
                    plan =
                        PlanNode
                            .newBuilder()
                            .setSort(
                                SortNode
                                    .newBuilder()
                                    .setInput(plan)
                                    .addAllSortKeys(
                                        ol.byList.map {
                                            SortKey
                                                .newBuilder()
                                                .setColumn(ColumnRef.newBuilder().setName(measure))
                                                .setDescending(it.descending)
                                                .build()
                                        },
                                    ),
                            ).build()
                    text += " order by " + ol.byList.joinToString(", ") { measure + if (it.descending) " desc" else "" }
                }
                if (ol.limit > 0) {
                    plan =
                        PlanNode
                            .newBuilder()
                            .setLimitOffset(LimitOffsetNode.newBuilder().setInput(plan).setLimit(ol.limit.toLong()))
                            .build()
                    text += " limit ${ol.limit}"
                }
            }

            val provenance =
                LowerProvenance(
                    snapshotHash = model.snapshotHash,
                    grammarVersion = GRAMMAR_VERSION,
                    catalogVersion = MD_CATALOG_VERSION,
                    bindingPaths = paths,
                    inlinedDefinitions = inlined.toList(),
                    parametersBound = params,
                    asOf =
                        if (s.hasAsOf()) {
                            s.asOf.value
                        } else if (asOfRead) {
                            asOfDay.toString()
                        } else {
                            ""
                        },
                    imprecisionMode = ImprecisionMode.CERTAINLY,
                )
            return LowerResult(plan, text, provenance, warnings)
        }

        // ---- slots ------------------------------------------------------------------------------------------------

        fun filter(
            f: Filter,
            slot: String,
            asks: MutableList<Ask>,
        ) {
            when (f.formCase) {
                Filter.FormCase.MEMBER -> {
                    val c = concept(f.member.concept)
                    val faces = coordinateFaces(c, "$slot.member.concept", "a member filter")
                    asks += Ask.Coordinate(slot, c, faces, member(f.member.member.resolvedValue))
                }
                Filter.FormCase.QUALITY -> {
                    val q = f.quality
                    val c = concept(q.quality)
                    val faces = MdFace.of(c)
                    val measure = faces.firstOrNull { it.element is MdElement.Measure }
                    if (measure != null) {
                        asks += Ask.Having(slot, measure, q.op, q.value)
                        return
                    }
                    if (q.op != ComparisonOp.EQ) {
                        refuse(
                            FrameSlugs.NOT_ANALYTICALLY_REACHABLE,
                            "$slot.quality.op",
                            "`${SPELLING[q.op] ?: q.op.name}` on the md attribute of `${c.ref}` — an md coordinate " +
                                "pins a member (`EQ`); md has no complement and a range needs both bounds",
                        )
                    }
                    val attributes = coordinateFaces(c, "$slot.quality.quality", "a quality filter")
                    asks += Ask.Coordinate(slot, c, attributes, member(valueText(q.value, slot)))
                }
                Filter.FormCase.PHASE -> asks += Ask.Phase(slot, f.phase)
                Filter.FormCase.GROUP ->
                    when (f.group.op) {
                        BoolOp.ALL ->
                            f.group.filtersList.forEachIndexed {
                                j,
                                x,
                                ->
                                filter(x, "$slot.group.filters[$j]", asks)
                            }
                        else ->
                            refuse(
                                FrameSlugs.NOT_ANALYTICALLY_REACHABLE,
                                "$slot.group",
                                "an `${f.group.op.name}` group — md coordinates are a conjunction (ALL)",
                            )
                    }
                else ->
                    refuse(
                        FrameSlugs.NOT_ANALYTICALLY_REACHABLE,
                        "$slot.expr",
                        "an expression filter (the E-2 hatch) does not lower on md in P3",
                    )
            }
        }

        /** [c]'s md faces a coordinate can sit on — a dimension or an attribute; a measure or a cubelet cannot. */
        fun coordinateFaces(
            c: Concept,
            subject: String,
            slot: String,
        ): List<MdFace> {
            val faces = MdFace.of(c).filter { it.element is MdElement.Dimension || it.element is MdElement.Attribute }
            if (faces.isEmpty()) {
                val e =
                    MdFace
                        .of(c)
                        .firstOrNull()
                        ?.element
                        ?.written ?: "nothing on md"
                refuse(
                    FrameSlugs.NOT_ANALYTICALLY_REACHABLE,
                    subject,
                    "`${c.ref}` as $slot reaches $e — no md dimension or attribute",
                )
            }
            return faces
        }

        /** The quality carrying [event]'s instant — its `period:`'s `at`, else `start` — as a concept, or null. */
        fun periodQuality(event: Concept): Concept? {
            val withPeriod = chain.chain(event).firstOrNull { it.period != null } ?: return null
            val name = withPeriod.period?.at ?: withPeriod.period?.start ?: return null
            val owners = chain.chain(event).map { it.qname }.toSet()
            return model.cn.concepts.values.firstOrNull {
                it.stereotype == "quality" &&
                    it.qname.name == name &&
                    it.of in owners
            }
        }

        fun carrier(
            measure: String,
            asks: List<Ask>,
            event: Concept?,
            timeFace: MdFace?,
        ): Carrier {
            fun dimensionOf(f: MdFace): String? =
                when (val e = f.element) {
                    is MdElement.Dimension -> e.name
                    is MdElement.Attribute -> e.dimension
                    else -> null
                }
            val needs =
                asks.filterIsInstance<Ask.Coordinate>().map { a -> a.faces.mapNotNull(::dimensionOf).toSet() } +
                    listOfNotNull(timeFace?.let(::dimensionOf)?.let { setOf(it) })

            fun carries(c: MdCubelet): Boolean {
                val dims = c.grain.map { it.substringBeforeLast('.') }.toSet()
                return measure in c.measures && needs.all { n -> n.any { it in dims } }
            }
            val carrying =
                md.model.cubelets.values
                    .filter(::carries)
                    .sortedBy { it.name }
            val own = event?.let { e -> MdFace.of(e).mapNotNull { (it.element as? MdElement.Cubelet)?.name } }.orEmpty()
            val preferred = carrying.filter { it.name in own }
            val chosen =
                when {
                    preferred.size == 1 -> preferred.single()
                    carrying.size == 1 -> carrying.single()
                    carrying.isEmpty() ->
                        refuse(
                            FrameSlugs.NOT_ANALYTICALLY_REACHABLE,
                            "measures[0]",
                            "no cubelet carries the measure `$measure`" +
                                (
                                    if (needs.isEmpty()) {
                                        ""
                                    } else {
                                        " with ${needs.joinToString(
                                            ", ",
                                        ) { n -> n.sorted().joinToString("|") { "`$it`" } }}"
                                    }
                                ),
                        )
                    else ->
                        refuse(
                            FrameSlugs.AMBIGUOUS_STATEMENT,
                            "measures[0]",
                            "the cubelets ${carrying.joinToString(
                                ", ",
                            ) { "`${it.name}`" }} all carry the statement — " +
                                "refused, never the first",
                        )
                }
            return Carrier(chosen, md.model)
        }

        /** A coordinate ask on the carrier: the one face the carrier has, recorded as the slot's binding path. */
        fun bind(
            a: Ask.Coordinate,
            carrier: Carrier,
        ): Coord {
            val here = a.faces.filter { carrier.attributeOf(it) != null }
            val face =
                here.singleOrNull()
                    ?: refuse(
                        if (here.isEmpty()) FrameSlugs.NOT_ANALYTICALLY_REACHABLE else FrameSlugs.AMBIGUOUS_STATEMENT,
                        a.slot,
                        if (here.isEmpty()) {
                            "`${a.concept.ref}` has no md attribute on the carrying cubelet `${carrier.name}`"
                        } else {
                            "`${a.concept.ref}` reaches `${carrier.name}` by " +
                                here.joinToString(" and ") { "`${it.element.written}`" } + " — refused, never the first"
                        },
                    )
            paths += face.path(a.slot)
            return Coord(carrier.attributeOf(face)!!, a.target)
        }

        fun phase(
            a: Ask.Phase,
            carrier: Carrier,
        ): MdRenderer.Predicate {
            val subject = "${a.slot}.phase.phase"
            val ph = concept(a.filter.phase)
            if (a.filter.negated) {
                refuse(
                    FrameSlugs.NOT_ANALYTICALLY_REACHABLE,
                    subject,
                    "a negated phase (`not ${ph.ref}`) needs a complement md cannot say (G-MD-1)",
                )
            }
            return when (val r = renderer.render(ph, carrier, asOfDay)) {
                is MdRenderer.Result.Unrenderable ->
                    refuse(
                        FrameSlugs.NOT_ANALYTICALLY_REACHABLE,
                        subject,
                        "`${ph.ref}` cannot render on md: ${r.message}",
                        Diagnostic(FrameSlugs.definitionUnreachable(Layer.MD), Severity.ERROR, r.message, subject),
                    )
                is MdRenderer.Result.Rendered -> {
                    val p = r.predicate
                    paths += p.reads.map { it.path(a.slot) }
                    inlined += p.inlined
                    params += p.parameters
                    if (p.asOf) asOfRead = true
                    p
                }
            }
        }

        // ---- time -------------------------------------------------------------------------------------------------

        fun time(
            carrier: Carrier,
            periodFace: MdFace?,
        ): List<Coord> {
            val attribute =
                periodFace?.let { carrier.attributeOf(it) }?.takeIf { carrier.isDate(it) }
                    ?: carrier.cubelet.grain
                        .filter { carrier.isDate(it) }
                        .singleOrNull()
                    ?: refuse(
                        FrameSlugs.NOT_ANALYTICALLY_REACHABLE,
                        "time",
                        "the carrying cubelet `${carrier.name}` has no date coordinate for the statement's time",
                    )
            val t = s.time
            return when (t.specCase) {
                TimeSpec.SpecCase.PERIOD_CODE -> periodCode(carrier, attribute, t.periodCode)
                TimeSpec.SpecCase.RANGE -> {
                    val from = day(t.range.from, "time.range.from")
                    val to = day(t.range.to, "time.range.to")
                    listOf(range(attribute, from, to.minusDays(1))) // half-open → the md range's inclusive bound
                }
                else -> {
                    asOfRead = true
                    listOf(range(attribute, relative(t.relative), asOfDay))
                }
            }
        }

        /**
         * `YYYY` / `YYYY-MM` / `YYYYQn` → the calc coordinates the model maps the date itself to (`yearOfDate`,
         * `monthOfDate`, `quarterOfDate`); else the period's day range.
         */
        fun periodCode(
            carrier: Carrier,
            attribute: String,
            code: String,
        ): List<Coord> {
            val dim = attribute.substringBeforeLast('.')
            val date = domainOf(attribute)
            val year = code.take(4).toInt()
            val yearAttr = calc(dim, date, "yearOfDate")
            val monthAttr = calc(dim, date, "monthOfDate")
            return when {
                code.length == 4 ->
                    yearAttr?.let { listOf(Coord(it, PathComponent.IntLit("$year"))) }
                        ?: listOf(range(attribute, LocalDate.of(year, 1, 1), LocalDate.of(year, 12, 31)))
                'Q' in code -> {
                    // only a map from the date itself: md lowers no chained calc (`quarterOfMonth` over a computed month)
                    val q = code.substringAfter('Q').toInt()
                    val quarterAttr = calc(dim, date, "quarterOfDate")
                    if (quarterAttr != null && yearAttr != null) {
                        listOf(
                            Coord(quarterAttr, PathComponent.IntLit("$q")),
                            Coord(yearAttr, PathComponent.IntLit("$year")),
                        )
                    } else {
                        val first = LocalDate.of(year, 3 * q - 2, 1)
                        listOf(range(attribute, first, first.plusMonths(3).minusDays(1)))
                    }
                }
                else -> {
                    val month = code.substring(5).toInt()
                    if (monthAttr != null && yearAttr != null) {
                        listOf(
                            Coord(monthAttr, PathComponent.IntLit("$month")),
                            Coord(yearAttr, PathComponent.IntLit("$year")),
                        )
                    } else {
                        val first = LocalDate.of(year, month, 1)
                        listOf(range(attribute, first, first.plusMonths(1).minusDays(1)))
                    }
                }
            }
        }

        fun domainOf(attribute: String): String? =
            md.model.attributes[attribute]?.domainRef?.let {
                md.model.underlyingDomain(it)
            }

        /** The attribute of [dim] a `calc: <fn>` map from [from] lands on (`Time.month` for `monthOfDate`), or null. */
        fun calc(
            dim: String,
            from: String?,
            fn: String,
        ): String? {
            if (from == null) return null
            val to =
                md.model.maps.values
                    .filter { it.calc == fn && it.from.any { f -> md.model.underlyingDomain(f) == from } }
                    .mapNotNull { it.to.singleOrNull()?.let { t -> md.model.underlyingDomain(t) } }
                    .distinct()
                    .singleOrNull() ?: return null
            return md.model.dimensions[dim]
                ?.attributes
                ?.filter { a -> a.domainRef?.let { md.model.underlyingDomain(it) } == to }
                ?.singleOrNull()
                ?.let { "$dim.${it.name}" }
        }

        fun day(
            i: org.tatrman.concept.v1.Instant,
            subject: String,
        ): LocalDate {
            if (i.granularity == Granularity.HOUR || i.granularity == Granularity.MINUTE) {
                refuse(
                    FrameSlugs.NOT_ANALYTICALLY_REACHABLE,
                    subject,
                    "the md time coordinate counts days — `${i.value}` is ${i.granularity.name.lowercase()}-granular",
                )
            }
            return FrameTime
                .interval(i)
                .getOrThrow()
                .lo
                .toLocalDate()
        }

        /** `last(<n>,<unit>)`'s first day, back from the evaluation day (days, weeks, months). */
        fun relative(r: String): LocalDate {
            val (amount, unit) = checkNotNull(FrameTime.relative(r)) { "time.relative validated" }
            return when (unit) {
                "days" -> asOfDay.minusDays(amount)
                "weeks" -> asOfDay.minusWeeks(amount)
                "months" -> asOfDay.minusMonths(amount)
                "working_days" ->
                    refuse(
                        FrameSlugs.NOT_ANALYTICALLY_REACHABLE,
                        "time.relative",
                        "a working-day window — md has no working-day calendar (G-MD-3)",
                    )
                else ->
                    refuse(
                        FrameSlugs.NOT_ANALYTICALLY_REACHABLE,
                        "time.relative",
                        "a sub-day window on a day (G-MD-4)",
                    )
            }
        }

        fun range(
            attribute: String,
            lo: LocalDate,
            hi: LocalDate,
        ): Coord = Coord(attribute, PathComponent.RangeLit(PathComponent.Quoted("$lo"), PathComponent.Quoted("$hi")))

        // ---- the md chain ------------------------------------------------------------------------------------------

        /** [coords] as the md path's components on [carrier] — each a pre-qualified pair the resolver binds as one unit. */
        fun components(
            carrier: Carrier,
            coords: List<Coord>,
            measure: String,
        ): List<PathComponent> =
            listOf(PathComponent.Ident(carrier.name)) +
                coords.map { c ->
                    val name = c.attribute.substringAfterLast('.')
                    val unique =
                        md.model.attributes.values
                            .count { it.name.equals(name, ignoreCase = true) } == 1
                    when {
                        unique -> PathComponent.Pair(PathComponent.Ident(name), c.target)
                        c.target == PathComponent.Star && carrier.grain[c.dimension] == c.attribute ->
                            PathComponent.Pair(PathComponent.Ident(c.dimension), PathComponent.Star)
                        else ->
                            refuse(
                                FrameSlugs.NOT_ANALYTICALLY_REACHABLE,
                                "measures[0]",
                                "the md attribute `${c.attribute}` shares its name with another dimension's — " +
                                    "a dot-path cannot constrain it by name",
                            )
                    }
                } + PathComponent.Ident(measure)

        /**
         * One md read: resolved (disconnected — the frame's members ARE the members) and lowered to `plan.v1`, its
         * text the canonical rendering. Its SHAPE is the statement's: only the [free] attributes (a group-by, a
         * semi-join's key) are group keys — every other grain dimension, unmentioned or restricted, is aggregated over,
         * and an unmentioned one is left out of the text (the resolver fills it as `*`, which would read as free).
         */
        fun read(
            carrier: Carrier,
            coords: List<Coord>,
            measure: String,
            free: Set<String>,
        ): Pair<String, PlanNode> {
            val asOf = asOfDay.atStartOfDay(ZoneOffset.UTC).toInstant()
            val r =
                when (
                    val out =
                        DefaultMdPathResolver().resolve(
                            components(carrier, coords, measure),
                            md.model,
                            null,
                            asOf,
                        )
                ) {
                    is ResolutionOutcome.Resolved -> out
                    is ResolutionOutcome.Ambiguous ->
                        refuse(
                            FrameSlugs.AMBIGUOUS_STATEMENT,
                            "measures[0]",
                            "the md path is ambiguous — " +
                                out.alternatives.joinToString(" | ") { CanonicalRenderer.render(it.path) } +
                                "; refused, never the first",
                        )
                    is ResolutionOutcome.Failed ->
                        refuse(
                            FrameSlugs.NOT_ANALYTICALLY_REACHABLE,
                            "measures[0]",
                            "the md path does not resolve on `${carrier.name}`: " +
                                out.diagnostics.joinToString("; ") { "${it.code} ${it.detail}".trim() },
                        )
                }
            val shape =
                PathShape(
                    r.path.coordinates
                        .filter { it.attribute in free }
                        .map { it.attribute },
                )
            val plan =
                try {
                    MdPathLowering(md.bindings, md.model).lower(r.path, shape)
                } catch (e: MdLoweringException) {
                    refuse(
                        FrameSlugs.NOT_ANALYTICALLY_REACHABLE,
                        "measures[0]",
                        "the md path does not lower: ${e.code} ${e.message}",
                    )
                }
            val asked = coords.map { it.attribute }.toSet()
            val shown = r.path.copy(coordinates = r.path.coordinates.filter { it.attribute in asked })
            return CanonicalRenderer.render(shown) to plan
        }

        /** The fact column a semi-join key is bound to; a hop-bound key (a joined table's column) is a follow-up. */
        fun keyColumn(
            carrier: Carrier,
            key: String,
            subject: String,
        ): String =
            (
                md.bindings.cubelets[carrier.name]
                    ?.attributes
                    ?.get(key) as? AttrBinding.Column
            )?.column
                ?: refuse(
                    FrameSlugs.NOT_ANALYTICALLY_REACHABLE,
                    subject,
                    "the existence key `$key` is no column of `${carrier.name}`'s fact table — a follow-up",
                )

        /** `key IN (SELECT key FROM <inner>)` — the inner read's keys, projected. */
        fun inKeys(
            inner: PlanNode,
            key: String,
        ): Expression {
            val keys =
                PlanNode
                    .newBuilder()
                    .setProject(
                        ProjectNode
                            .newBuilder()
                            .setInput(inner)
                            .addExpressions(NamedExpression.newBuilder().setExpression(column(key)).setAlias(key)),
                    ).build()
            return Expression
                .newBuilder()
                .setSubquery(
                    SubqueryExpression
                        .newBuilder()
                        .setSubquery(keys)
                        .setKind("in")
                        .addOperands(column(key)),
                ).build()
        }

        /**
         * ⚑CN-P9's composition: the outer read (grouped by every key) kept where each key is `IN` its inner read's keys,
         * then aggregated again over the keys the statement did not ask for ([dropped]) — the sum of the per-key sums
         * (a `count` sums, `min` / `max` keep; an `avg` cannot be re-aggregated).
         */
        fun semiJoin(
            outer: PlanNode,
            conditions: List<Expression>,
            dropped: Set<String>,
        ): PlanNode {
            val kept = filter(outer, conditions.singleOrNull() ?: call("and", *conditions.toTypedArray()))
            if (dropped.isEmpty()) return kept
            val aggregate = outer.aggregate
            val again =
                aggregate.aggregatesList.map { a ->
                    val fn =
                        when (a.function) {
                            "sum", "count" -> "sum"
                            "min", "max" -> a.function
                            else ->
                                refuse(
                                    FrameSlugs.NOT_ANALYTICALLY_REACHABLE,
                                    "measures[0]",
                                    "an `${a.function}` over the existence's keys cannot be re-aggregated",
                                )
                        }
                    AggregateCall
                        .newBuilder(a)
                        .setFunction(fn)
                        .clearArgs()
                        .addArgs(ColumnRef.newBuilder().setName(a.alias))
                        .build()
                }
            return PlanNode
                .newBuilder()
                .setAggregate(
                    AggregateNode
                        .newBuilder()
                        .setInput(kept)
                        .addAllGroupKeys(aggregate.groupKeysList.filter { it.name !in dropped })
                        .addAllAggregates(again),
                ).build()
        }

        fun valueText(
            v: Value,
            slot: String,
        ): String =
            when {
                v.isNull ->
                    refuse(
                        FrameSlugs.NOT_ANALYTICALLY_REACHABLE,
                        "$slot.quality.value",
                        "a comparison with null",
                    )
                v.hasStringValue() -> v.stringValue
                v.hasIntValue() -> "${v.intValue}"
                v.hasFloatValue() -> "${v.floatValue}"
                v.hasBoolValue() -> "${v.boolValue}"
                v.hasInstantValue() -> v.instantValue.value
                else ->
                    refuse(
                        FrameSlugs.NOT_ANALYTICALLY_REACHABLE,
                        "$slot.quality.value",
                        "a quality filter with no value",
                    )
            }

        fun literal(
            v: Value,
            slot: String,
        ): Expression {
            val l = Literal.newBuilder()
            when {
                v.hasIntValue() -> l.setIntValue(v.intValue).setType("int")
                v.hasFloatValue() -> l.setFloatValue(v.floatValue).setType("float")
                v.hasBoolValue() -> l.setBoolValue(v.boolValue).setType("bool")
                v.hasInstantValue() -> l.setDatetimeValue(v.instantValue.value).setType("datetime")
                else -> l.setStringValue(valueText(v, slot)).setType("text")
            }
            return Expression.newBuilder().setLiteral(l).build()
        }
    }

    /**
     * The er lane (CN-P6 ⚑S62-1, A-CN-101): [s] as an [ErStatement] — the grain event, its measures with their
     * aggregate, the phases, the member / quality conditions (a quality filter on a measure a HAVING), the group-bys, the
     * time as a half-open day range, the order — rendered by [ErRenderer.statement]. [because] is why md did not read it.
     */
    private inner class ErLane(
        val s: Statement,
    ) {
        val asOfDay: LocalDate by lazy { evaluationDay(s) }

        fun lower(
            kept: List<Diagnostic>,
            because: List<Diagnostic>,
        ): LowerResult {
            // the lane applies to one grain event that lands on E-R; else md's refusal stands alone
            val events = s.measuresList.map { grainEvent(concept(it)) }.distinct()
            val landsOnEntity =
                events.singleOrNull()?.erTarget?.split('.')?.let {
                    it.size == 3 &&
                        it[0] == "er" &&
                        it[1] == "entity"
                }
            if (landsOnEntity != true) return LowerResult(diagnostics = kept + because)

            // a finding md already made (one slug, one subject) stands for both lanes, E-R's reason folded into it
            fun refusal(er: List<Diagnostic>): LowerResult {
                val same = er.groupBy { it.slug to it.subject }
                val merged =
                    because.map { d ->
                        same[d.slug to d.subject]?.let { e ->
                            d.copy(
                                message =
                                    d.message + " — and on E-R: " + e.joinToString("; ") { it.message },
                            )
                        }
                            ?: d
                    }
                val said = because.map { it.slug to it.subject }.toSet()
                return LowerResult(diagnostics = kept + merged + er.filter { (it.slug to it.subject) !in said })
            }
            val spec =
                try {
                    spec()
                } catch (r: Refused) {
                    return refusal(r.diagnostics)
                }
            val r = ErRenderer(model).statement(spec)
            if (r.diagnostics.any { it.severity == Severity.ERROR }) return refusal(r.diagnostics)
            val entity = spec.event.erTarget ?: ""
            val mdReason = because.firstOrNull()?.message ?: "no md read"
            val why =
                Diagnostic(
                    FrameSlugs.LOWERED_ON_ER,
                    Severity.ADVISORY,
                    "md carries no single read of the statement ($mdReason) — lowered on E-R over `$entity`",
                    because.firstOrNull()?.subject ?: "measures[0]",
                )
            val read = r.parametersRead.map { it.name }
            val provenance =
                LowerProvenance(
                    snapshotHash = model.snapshotHash,
                    grammarVersion = GRAMMAR_VERSION,
                    catalogVersion = MD_CATALOG_VERSION,
                    bindingPaths = bindingPaths(spec),
                    inlinedDefinitions = r.definitionsInlined,
                    parametersBound = spec.values.filterKeys { it in read },
                    asOf =
                        when {
                            s.hasAsOf() -> s.asOf.value
                            "as_of" in read ||
                                r.parametersRead.any { it.derivation?.startsWith("as_of") == true } ||
                                s.time.specCase == TimeSpec.SpecCase.RELATIVE -> asOfDay.toString()
                            else -> ""
                        },
                    imprecisionMode = r.imprecisionMode,
                )
            return LowerResult(
                provenance = provenance,
                diagnostics = kept + why + r.diagnostics,
                erSql = r.text,
            )
        }

        fun bindingPaths(spec: ErStatement): List<BindingPath> {
            fun path(
                slot: String,
                c: Concept,
            ) = BindingPath(
                slot,
                c.ref,
                c.grounding?.qname?.name ?: "",
                c.erTarget ?: "",
                c.via ?: org.tatrman.ttr.metadata.model.Via.DIRECT,
            )
            return spec.measures.mapIndexed { i, m -> path("measures[$i]", m.quality) } +
                spec.groupBy.mapIndexed { i, g -> path("groupBy[$i]", g) } +
                spec.conditions.map { c ->
                    when (c) {
                        is ErCondition.Member -> path(c.slot, c.concept)
                        is ErCondition.Quality -> path(c.slot, c.concept)
                    }
                }
        }

        fun spec(): ErStatement {
            if (s.hasCompare()) {
                refuse(
                    FrameSlugs.NOT_ANALYTICALLY_REACHABLE,
                    "compare",
                    "a `compare` — not read on E-R either (a follow-up)",
                )
            }
            val measures = s.measuresList.map(::concept)
            val events = measures.map { grainEvent(it) }
            val event =
                events.distinct().singleOrNull()
                    ?: refuse(
                        FrameSlugs.NOT_ANALYTICALLY_REACHABLE,
                        "measures[1]",
                        "the measures are qualities of different events — an E-R read is one event's rows",
                    )
            if (event == null) {
                refuse(
                    FrameSlugs.NOT_ANALYTICALLY_REACHABLE,
                    "measures[0]",
                    "`${measures.first().ref}` is a quality of no event — an E-R read is an event's rows",
                )
            }
            val phases = mutableListOf<Pair<Concept, Boolean>>()
            val conditions = mutableListOf<ErCondition>()
            val havings = mutableListOf<Triple<Int, String, String>>()

            fun filter(
                f: Filter,
                slot: String,
            ) {
                when (f.formCase) {
                    Filter.FormCase.MEMBER ->
                        conditions += ErCondition.Member(concept(f.member.concept), f.member.member.resolvedValue, slot)
                    Filter.FormCase.QUALITY -> {
                        val q = f.quality
                        val c = concept(q.quality)
                        val op =
                            SPELLING[q.op] ?: refuse(FrameSlugs.MALFORMED_FRAME, "$slot.quality.op", "no comparison")
                        val i = measures.indexOfFirst { it.qname == c.qname }
                        if (i >= 0) {
                            havings +=
                                Triple(
                                    i,
                                    op,
                                    sqlLiteral(q.value, slot)
                                        ?: refuse(
                                            FrameSlugs.NOT_ANALYTICALLY_REACHABLE,
                                            "$slot.quality.value",
                                            "a measure compared with null",
                                        ),
                                )
                        } else {
                            conditions += ErCondition.Quality(c, op, sqlLiteral(q.value, slot), slot)
                        }
                    }
                    Filter.FormCase.PHASE -> phases += concept(f.phase.phase) to f.phase.negated
                    Filter.FormCase.GROUP ->
                        when (f.group.op) {
                            BoolOp.ALL ->
                                f.group.filtersList.forEachIndexed {
                                    j,
                                    x,
                                    ->
                                    filter(x, "$slot.group.filters[$j]")
                                }
                            else ->
                                refuse(
                                    FrameSlugs.NOT_ANALYTICALLY_REACHABLE,
                                    "$slot.group",
                                    "an `${f.group.op.name}` group on E-R — a follow-up (the conjunction reads)",
                                )
                        }
                    else ->
                        refuse(
                            FrameSlugs.NOT_ANALYTICALLY_REACHABLE,
                            "$slot.expr",
                            "an expression filter (the E-2 hatch) on E-R — a follow-up",
                        )
                }
            }
            s.filtersList.forEachIndexed { i, f -> filter(f, "filters[$i]") }
            return ErStatement(
                event = event,
                measures = measures.mapIndexed { i, m -> ErMeasure(m, aggregate(m, "measures[$i]")) },
                phases = phases,
                conditions = conditions,
                groupBy = s.groupByList.map(::concept),
                havings = havings,
                time = time(),
                orderBy =
                    s.orderLimit.byList.map { k ->
                        measures.indexOfFirst { it.qname.name == k.by.qname.removePrefix("cn.") } to k.descending
                    },
                limit = s.orderLimit.limit.takeIf { it > 0 },
                asOf = { asOfDay },
                values = parameterValues,
            )
        }

        /** The measure's aggregate: its md measure's (`sum` when it names none); an E-R-only quality has none to read. */
        fun aggregate(
            q: Concept,
            slot: String,
        ): String {
            val g = q.grounding
            val name =
                g
                    ?.target
                    ?.takeIf { g.layer == org.tatrman.ttr.metadata.model.GroundingLayer.MD }
                    ?.substringAfterLast(
                        '.',
                    )
            val measure =
                name?.let {
                    model.md
                        ?.model
                        ?.measures
                        ?.get(it)
                }
                    ?: refuse(
                        FrameSlugs.NOT_ANALYTICALLY_REACHABLE,
                        slot,
                        "`${q.ref}` grounds no md measure — an E-R read takes its aggregate from one (an E-R-only measure is a follow-up)",
                    )
            return when (val fn = measure.aggregation?.default?.lowercase() ?: "sum") {
                "sum", "avg", "min", "max", "count" -> fn.uppercase()
                else ->
                    refuse(
                        FrameSlugs.NOT_ANALYTICALLY_REACHABLE,
                        slot,
                        "`${q.ref}` aggregates by `$fn` — not a plain aggregate on E-R (a follow-up)",
                    )
            }
        }

        /** The statement's time as a half-open day range, or null for all time. */
        fun time(): Pair<LocalDate, LocalDate>? {
            val t = s.time
            return when (t.specCase) {
                TimeSpec.SpecCase.PERIOD_CODE -> {
                    val code = t.periodCode
                    val year = code.take(4).toInt()
                    when {
                        code.length == 4 -> LocalDate.of(year, 1, 1).let { it to it.plusYears(1) }
                        'Q' in code ->
                            LocalDate.of(year, 3 * code.substringAfter('Q').toInt() - 2, 1).let {
                                it to
                                    it.plusMonths(3)
                            }
                        else -> LocalDate.of(year, code.substring(5).toInt(), 1).let { it to it.plusMonths(1) }
                    }
                }
                TimeSpec.SpecCase.RANGE -> day(t.range.from, "time.range.from") to day(t.range.to, "time.range.to")
                TimeSpec.SpecCase.RELATIVE -> {
                    val (amount, unit) = checkNotNull(FrameTime.relative(t.relative)) { "time.relative validated" }
                    val lo =
                        when (unit) {
                            "days" -> asOfDay.minusDays(amount)
                            "weeks" -> asOfDay.minusWeeks(amount)
                            "months" -> asOfDay.minusMonths(amount)
                            else ->
                                refuse(
                                    FrameSlugs.NOT_ANALYTICALLY_REACHABLE,
                                    "time.relative",
                                    "a `$unit` window on E-R — a follow-up",
                                )
                        }
                    lo to asOfDay.plusDays(1)
                }
                else -> null
            }
        }

        fun day(
            i: org.tatrman.concept.v1.Instant,
            subject: String,
        ): LocalDate {
            if (i.granularity == Granularity.HOUR || i.granularity == Granularity.MINUTE) {
                refuse(
                    FrameSlugs.NOT_ANALYTICALLY_REACHABLE,
                    subject,
                    "the E-R read counts days — `${i.value}` is finer",
                )
            }
            return FrameTime
                .interval(i)
                .getOrThrow()
                .lo
                .toLocalDate()
        }

        /** [v] spelled as an SQL literal; null for a null. */
        fun sqlLiteral(
            v: Value,
            slot: String,
        ): String? =
            when {
                v.isNull -> null
                v.hasStringValue() -> "'" + v.stringValue.replace("'", "''") + "'"
                v.hasIntValue() -> "${v.intValue}"
                v.hasFloatValue() -> "${v.floatValue}"
                v.hasBoolValue() -> if (v.boolValue) "TRUE" else "FALSE"
                v.hasInstantValue() ->
                    if (v.instantValue.granularity == Granularity.DAY) {
                        "DATE '${v.instantValue.value}'"
                    } else {
                        "TIMESTAMP '${v.instantValue.value.replace('T', ' ')}'"
                    }
                else -> refuse(FrameSlugs.MALFORMED_FRAME, "$slot.quality.value", "a quality filter with no value")
            }
    }

    /** The world's `def parameter` values as SQL literals — what a phase's `:name` reads on the er lane. */
    private val parameterValues: Map<String, String> by lazy {
        (model.model.schemas["automation"] as? org.tatrman.ttr.metadata.model.AutomationSchema)
            ?.parameters
            ?.mapNotNull { p ->
                when (val v = p.value) {
                    is org.tatrman.ttr.parser.model.AutoValue.Num -> p.qname.name to v.text
                    is org.tatrman.ttr.parser.model.AutoValue.Bool -> p.qname.name to (if (v.raw) "TRUE" else "FALSE")
                    is org.tatrman.ttr.parser.model.AutoValue.Str ->
                        p.qname.name to
                            ("'" + v.raw.replace("'", "''") + "'")
                    else -> null
                }
            }?.toMap()
            .orEmpty()
    }

    internal companion object {
        /** The validator's findings that say md cannot reach a slot — the er lane may still read it (A-CN-101). */
        val MD_REACH = setOf(FrameSlugs.NOT_ANALYTICALLY_REACHABLE, FrameSlugs.definitionUnreachable(Layer.MD))

        private val OPS =
            mapOf(
                ComparisonOp.EQ to "eq",
                ComparisonOp.NEQ to "ne",
                ComparisonOp.LT to "lt",
                ComparisonOp.LTE to "le",
                ComparisonOp.GT to "gt",
                ComparisonOp.GTE to "ge",
            )

        private val SPELLING =
            mapOf(
                ComparisonOp.EQ to "=",
                ComparisonOp.NEQ to "<>",
                ComparisonOp.LT to "<",
                ComparisonOp.LTE to "<=",
                ComparisonOp.GT to ">",
                ComparisonOp.GTE to ">=",
            )

        private fun column(name: String): Expression =
            Expression.newBuilder().setColumnRef(ColumnRef.newBuilder().setName(name)).build()

        private fun call(
            op: String,
            vararg operands: Expression,
        ): Expression =
            Expression
                .newBuilder()
                .setFunction(FunctionCall.newBuilder().setOperation(op).addAllOperands(operands.toList()))
                .build()

        private fun filter(
            input: PlanNode,
            condition: Expression,
        ): PlanNode =
            PlanNode.newBuilder().setFilter(FilterNode.newBuilder().setInput(input).setCondition(condition)).build()
    }
}
