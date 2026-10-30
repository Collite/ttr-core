// SPDX-License-Identifier: Apache-2.0
package org.tatrman.concept

import org.tatrman.concept.CnDefinitionLints.Granularity
import org.tatrman.ttr.metadata.model.Attribute
import org.tatrman.ttr.metadata.model.AutomationSchema
import org.tatrman.ttr.metadata.model.CnPeriod
import org.tatrman.ttr.metadata.model.Concept
import org.tatrman.ttr.metadata.model.EndState
import org.tatrman.ttr.metadata.model.Entity
import org.tatrman.ttr.metadata.model.ErSchema
import org.tatrman.ttr.metadata.model.Grounding
import org.tatrman.ttr.metadata.model.GroundingGrade
import org.tatrman.ttr.metadata.model.GroundingLayer
import org.tatrman.ttr.metadata.model.HierarchyRanges
import org.tatrman.ttr.metadata.model.Imprecision
import org.tatrman.ttr.metadata.model.QualifiedName
import org.tatrman.ttr.metadata.model.Relation
import org.tatrman.ttrp.expr.AggregateCall
import org.tatrman.ttrp.expr.CaseWhen
import org.tatrman.ttrp.expr.Cast
import org.tatrman.ttrp.expr.ColumnRef
import org.tatrman.ttrp.expr.ConceptAggregate
import org.tatrman.ttrp.expr.ConceptBinding
import org.tatrman.ttrp.expr.DurationExpr
import org.tatrman.ttrp.expr.DurationLiteral
import org.tatrman.ttrp.expr.DurationParam
import org.tatrman.ttrp.expr.ExistsExpr
import org.tatrman.ttrp.expr.Expression
import org.tatrman.ttrp.expr.FunctionCall
import org.tatrman.ttrp.expr.InCall
import org.tatrman.ttrp.expr.InList
import org.tatrman.ttrp.expr.IsNull
import org.tatrman.ttrp.expr.Literal
import org.tatrman.ttrp.expr.LiteralValue
import org.tatrman.ttrp.expr.MdPath
import org.tatrman.ttrp.expr.ParamRef
import org.tatrman.ttrp.expr.Participant
import org.tatrman.ttrp.expr.TemporalOp
import org.tatrman.ttrp.expr.TemporalPredicate
import org.tatrman.ttrp.expr.ThisRef

/**
 * A statement read on E-R (CN-P6 ⚑S62-1, A-CN-101) — what [StatementLowerer]'s er lane hands [ErRenderer.statement]:
 * the grain [event] (the query reads its landing), the [measures] with their aggregate functions (`measures[i]` in frame
 * order), the [phases], member / quality [conditions] on the event's row, the [groupBy] concepts (`groupBy[i]`), the
 * HAVINGs (a measure's index, an operator, a literal), the time as a half-open day range `[lo, hi)`, the order (a
 * measure's index, descending) and the limit. [asOf] gives the evaluation day every `:as_of` (and a bound derived from
 * it) reads — asked only when a placeholder needs it, so a frame that fixes its time reads no clock; [values] binds the
 * `:name` parameters a phase reads. A refusal names the frame slot it is about (review-154 E8).
 */
internal data class ErStatement(
    val event: Concept,
    val measures: List<ErMeasure>,
    val phases: List<ErPhase> = emptyList(),
    val conditions: List<ErCondition> = emptyList(),
    val groupBy: List<Concept> = emptyList(),
    val havings: List<Triple<Int, String, String>> = emptyList(),
    val time: Pair<java.time.LocalDate, java.time.LocalDate>? = null,
    val orderBy: List<Pair<Int, Boolean>> = emptyList(),
    val limit: Int? = null,
    val asOf: () -> java.time.LocalDate,
    val values: Map<String, String> = emptyMap(),
)

/** A measure of an [ErStatement]: the quality and the SQL aggregate it reads with (`SUM`, `AVG`, `MIN`, `MAX`, `COUNT`). */
internal data class ErMeasure(
    val quality: Concept,
    val function: String,
)

/** A phase filter of an [ErStatement]: the phase, whether it is negated, and its frame [slot] (`filters[i].phase.phase`). */
internal data class ErPhase(
    val phase: Concept,
    val negated: Boolean = false,
    val slot: String = "filters[0].phase.phase",
)

/** A condition of an [ErStatement] on the event's row; [slot] is its filter's frame path (`filters[i]`, nested ones too). */
internal sealed interface ErCondition {
    val slot: String

    /**
     * [concept] is [value] — its column on the event's row, or the key the event holds to it: a participant's member IS
     * that key (A-CN-47's reading, ⚑R152-c), so a value the key's type cannot take is `cn/slot-mismatch`.
     */
    data class Member(
        val concept: Concept,
        val value: String,
        override val slot: String,
    ) : ErCondition

    /** [concept] `<op>` [literal] — an SQL literal, already spelled; null is `IS [NOT] NULL` (`op` `=` / `<>`). */
    data class Quality(
        val concept: Concept,
        val op: String,
        val literal: String?,
        override val slot: String,
    ) : ErCondition

    /**
     * [concept] `<op>` an instant — read as its interval `[lo, hi)` (`FrameTime.interval`, ⚑R152-f): `=` is `>= lo AND
     * < hi`, `<>` its complement, `>` is `>= hi`, `>=` is `>= lo`, `<` is `< lo`, `<=` is `< hi`. A bound is a `DATE`
     * on a date column when it falls on a day, else a full `TIMESTAMP`.
     */
    data class Within(
        val concept: Concept,
        val op: String,
        val lo: java.time.LocalDateTime,
        val hi: java.time.LocalDateTime,
        override val slot: String,
    ) : ErCondition
}

/**
 * The er definition renderer (CN-P3 S3.4; contracts §3 `definitionRender(…, ER)`, the renderer rules S0.3 measured —
 * A-CN-12…22): a phase's, a defined quality's or a member-restricted kind's definition as SQL over entities, every
 * definition it names inlined (transitively; a cycle is `cn/definition-cycle`).
 *
 *  - **PREDICATE** — `SELECT <bearer key> FROM <bearer> WHERE <predicate>` (a defined quality: the key and `<value> AS
 *    <name>`); a stacked-entity phase (`cn2er_phase`) is `SELECT <key> FROM <entity>`.
 *  - **CONTEXT** (A-CN-20, H-1) — `FROM <the candidates' bearer>` joined to the target's bearer through a participant
 *    path, the candidates' predicate first in the WHERE; an existence keyed by the bearer's detail rows (`exists E
 *    involving this` at the top of the conjunction) becomes an inner JOIN whose rows the projection keys; any other
 *    existence a LEFT JOIN of a filtered derived table read as `<alias>.<key> IS NOT NULL`. The projection: the keys of
 *    the finest joined rows (a row another joined row determines is left out), then `columns`.
 *
 * Shapes (S0.3 §3): every participant hop an explicit `JOIN … ON` from the binding's relation path — two hops onto the
 * same key elide the entity they share (`invoice.customer = "order".customer`); existence → `[NOT] EXISTS (SELECT 1 …)`
 * with the correlation written out; closure → `IN (SELECT <descendant> FROM <closure> WHERE <ancestor> = '<code>')`
 * (`ranges: lexical` → `BETWEEN`); `latest(… by:)` → a `ROW_NUMBER` window in a LEFT JOIN (unbounded at now, A-CN-17);
 * a count / sum → `JOIN + GROUP BY + HAVING` (the one aggregate of a predicate whose comparison is false on no rows),
 * else a grouped derived table in a LEFT JOIN; a `max` / `min` lookup over a validity interval (`… where period contains
 * <instant>`) → a flat LEFT JOIN of the ranged entity — at most one row is valid at an instant (A-CN-41); **never a
 * correlated scalar sub-query** (A-CN-21 — an aggregate whose `where` reads outside its rows otherwise is refused,
 * G-ER-3); DC-12 words → comparisons with NULL arms per `end_open:` (A-CN-14/19) and the S0.3 §4 bounds per
 * `imprecision:`; `within(x, last: d)` → `x >= {window_lo} AND x <= {as_of}`, `window_lo` derived (A-CN-22).
 *
 * Refused (`cn/definition-unreachable(er)`, naming the construct and the demand): a concept with no E-R landing (`cn/not-
 * recipe-reachable` when its md grounding's hop is missing), a window over columns ± a duration or any duration on the
 * er path (G-ER-1), a datetime difference or `age` (G-ER-2), a correlated aggregate (G-ER-3).
 */
internal class ErRenderer(
    private val model: ResolvedCnModel,
    /** One line instead of the layout ([SqlPrinter]'s compact form) — a reader's, and the tests'. */
    private val compact: Boolean = false,
) {
    private val cn = model.cn
    private val er: ErSchema? = model.model.schemas["er"] as? ErSchema
    private val resolver =
        DefinitionResolver(
            cn,
            er,
            (model.model.schemas["automation"] as? AutomationSchema)
                ?.parameters
                ?.map { it.qname.name }
                ?.toSet()
                .orEmpty(),
        )
    private val lattice = ConceptChain(cn)
    private val paths = ParticipantPaths(model)

    /** [target] rendered on er under [options]; a refusal is an empty text and one error-grade diagnostic. */
    fun render(
        target: Concept,
        options: RenderOptions,
    ): Rendering {
        val run = Run(target, options)
        return try {
            run.render()
        } catch (r: Refused) {
            refused(target, r)
        }
    }

    /**
     * [target]'s predicate-mode rendering as the `SELECT` tree, with what it read — `materialize`'s input. The tree is
     * null when the renderer refuses; the rendering then carries why (review-125 R125-1 — never an exception).
     */
    internal fun select(target: Concept): Pair<Select?, Rendering> {
        val run = Run(target, RenderOptions())
        return try {
            run.tree() to run.render()
        } catch (r: Refused) {
            null to refused(target, r)
        }
    }

    /**
     * CN-P6 ⚑S62-1 (A-CN-101) — [spec] read on E-R: `FROM` the grain event's landing; the group-bys (a column of that
     * row, or the key it holds to a participant) and the measures aggregated; each phase filter's definition inlined on
     * the event's row; member / quality conditions on its columns; the time on its period's day ([dayOf]); then
     * `HAVING`, `ORDER BY`, `LIMIT`. Every placeholder is bound ([ErStatement.asOf], [ErStatement.values], a derived
     * bound computed from the day), and every `latest(…, by:)` is capped at the evaluation day (⚑R152-d, the md lane's
     * `asOfDay` parity); a placeholder that cannot be bound is a refusal.
     *
     * The rows stay the event's rows (⚑R152-b): a phase whose inlining joins a relation from its many side (the event's
     * rows would repeat under the aggregate) is refused; a negated phase is read `NOT COALESCE((…), FALSE)` — two-valued,
     * so a row where the phase is NULL counts as "not" — and only while its inlining joins nothing (a joining one needs a
     * complement over the event's key, G-MD-1).
     *
     * A refusal is an empty text and frame findings on the slot it is about (⚑R152-e): `cn/not-analytically-reachable`
     * (or `cn/slot-mismatch` for a member the key's type cannot take), plus `cn/definition-unreachable(er)` on the same
     * slot when a phase's definition does not render — md's pair (`StatementLowerer`).
     */
    fun statement(spec: ErStatement): Rendering {
        val run = Run(spec.event, RenderOptions(), statementRead = true)
        return try {
            run.statement(spec)
        } catch (r: Refused) {
            val subject = r.subject ?: "measures[0]"
            val frame = Diagnostic(r.frame ?: FrameSlugs.NOT_ANALYTICALLY_REACHABLE, Severity.ERROR, r.message, subject)
            Rendering(
                imprecisionMode = mode(spec.event),
                diagnostics =
                    if (r.phase) {
                        listOf(
                            frame,
                            Diagnostic(r.slug, Severity.ERROR, r.message, subject),
                        )
                    } else {
                        listOf(frame)
                    },
            )
        }
    }

    private fun refused(
        target: Concept,
        r: Refused,
    ) = Rendering(
        imprecisionMode = mode(target),
        diagnostics = listOf(Diagnostic(r.slug, Severity.ERROR, r.message, target.ref)),
    )

    /**
     * A refusal: [slug] the definition finding; on a statement read also the frame [subject] it is about, the frame slug
     * when it is not `cn/not-analytically-reachable` ([frame]), and whether a phase's definition failed to render ([phase]).
     */
    private class Refused(
        val slug: String,
        override val message: String,
        val subject: String? = null,
        val phase: Boolean = false,
        val frame: String? = null,
    ) : RuntimeException(message, null, false, false)

    /** A derived table's `where` read outside its rows — the caller picks another shape or refuses (G-ER-3). */
    private class Correlated : RuntimeException(null, null, false, false)

    private fun refuse(
        message: String,
        demand: String? = null,
    ): Nothing = throw Refused(UNREACHABLE, message + (demand?.let { " ($it)" } ?: ""))

    private fun mode(c: Concept): ImprecisionMode =
        if (c.imprecision == Imprecision.POSSIBLY) ImprecisionMode.POSSIBLY else ImprecisionMode.CERTAINLY

    // ---- the er model -------------------------------------------------------------------------------------------

    private fun entityNamed(name: String): Entity? = er?.entities?.values?.firstOrNull { it.qname.name == name }

    /** `er.entity.<e>` → the entity; `er.entity.<e>.<a>` → null. */
    private fun entityOf(ref: String?): Entity? {
        val segs = ref?.split('.') ?: return null
        if (segs.size != 3 || segs[0] != "er" || segs[1] != "entity") return null
        return entityNamed(segs[2])
    }

    /** Where [c] lands on E-R as a row source — its entity — or null (a value kind lands on an attribute). */
    private fun entityOf(c: Concept): Entity? = entityOf(c.erTarget)

    private fun relation(ref: String): Relation? = paths.relation(ref)

    private fun simple(a: Attribute): String = a.qname.name.substringAfterLast('.')

    private fun attribute(
        e: Entity,
        name: String,
    ): Attribute? = e.attributes.firstOrNull { simple(it) == name }

    private fun keys(e: Entity): List<String> = e.attributes.filter { it.isKey }.map(::simple)

    /** The cn2er grounding up [c]'s chain that names [pick] (its participants, its period). */
    private fun grounding(
        c: Concept,
        pick: (Grounding) -> Boolean,
    ): Grounding? = paths.grounding(c, pick)

    private fun unreachable(c: Concept): Nothing =
        refuse(
            "`${c.ref}` has no E-R landing" +
                if (c.grade == GroundingGrade.MD_GROUNDED_HOP_MISSING) {
                    " — its md grounding's hop to E-R is missing (cn/not-recipe-reachable)"
                } else {
                    ""
                },
        )

    private fun snake(name: String): String = name.replace(Regex("([a-z0-9])([A-Z])"), "$1_$2").lowercase()

    // ---- what the walk yields -----------------------------------------------------------------------------------

    /** A row of the query: [alias] over [entity], in the `SELECT` it is a source of; [hops] — the rows joined from it. */
    private class Row(
        val alias: String,
        val entity: Entity,
        var select: Select,
    ) {
        val hops = mutableMapOf<String, Row>()
    }

    /** A concept instance: a row of the query ([Bound]), or the foreign key a row holds to it ([Held] — joined on use). */
    private sealed interface Ref {
        val concept: Concept
    }

    private class Bound(
        val row: Row,
        override val concept: Concept,
    ) : Ref

    private class Held(
        val holder: Row,
        val relation: Relation,
        override val concept: Concept,
    ) : Ref

    /** An instant's granularity, its stored upper bound (⚑CN-P10), and whether it is `:as_of`. */
    private data class Moment(
        val granularity: Granularity?,
        val hi: SqlExpr? = null,
        val asOf: Boolean = false,
    )

    private sealed interface V

    private data class Scalar(
        val sql: SqlExpr,
        val moment: Moment? = null,
    ) : V

    private data class RowV(
        val ref: Ref,
    ) : V

    private data class PeriodV(
        val ref: Ref,
        val owner: Concept,
    ) : V

    private class Ranged(
        val concept: Concept,
        val variable: String?,
        val ref: Ref,
    )

    /**
     * Where a name is read (the resolver's rules, `CnDefinitions`): a variable or a ranged concept's name, else the
     * innermost [implicit] subject outward; `this` is [self] — the bearer of the definition being rendered, whose
     * [owner] it is. [select] is the query conditions go to; a [sealed] scope (a derived table's) reads nothing outside.
     */
    private class Scope(
        val parent: Scope?,
        val ranged: List<Ranged>,
        val implicit: Ref?,
        val self: Ref?,
        val owner: Concept,
        val select: Select,
        val sealed: Boolean = false,
    ) {
        fun outward(): Sequence<Scope> = generateSequence(this) { it.parent }
    }

    private inner class Run(
        val target: Concept,
        val options: RenderOptions,
        /** A statement read ([statement]): every `latest(…, by:)` is capped at the evaluation day (⚑R152-d). */
        val statementRead: Boolean = false,
    ) {
        val context = options.mode == RenderMode.CONTEXT

        /** A statement read's own `SELECT` — the one its aggregates are over ([fannedOut] watches it). */
        var outer: Select? = null

        /** The relations joined from their many side on [outer] — each repeats the event's rows (⚑R152-b, E5). */
        val fannedOut = mutableListOf<String>()

        /** The frame slot being rendered, and the slot that first read each placeholder — where a refusal points. */
        var slot: String? = null
        val paramSlots = mutableMapOf<String, String>()
        val aliases = mutableSetOf<String>()
        val rows = mutableMapOf<String, Row>()
        val inlined = linkedSetOf<String>()
        val inlining = ArrayDeque<QualifiedName>()
        val derived = linkedMapOf<String, String>()
        val advisories = linkedMapOf<String, Diagnostic>()

        /** The defined qualities being inlined, innermost last — a derived table is named after the one it computes. */
        val qualities = ArrayDeque<String>()
        val memo = mutableMapOf<Pair<QualifiedName, String>, V>()

        private var built: Select? = null

        fun tree(): Select = built ?: build().also { built = it }

        fun render(): Rendering {
            val select = tree()
            val printer = SqlPrinter(options.placeholderStyle, compact)
            val text = printer.print(select)
            return Rendering(
                text = text,
                parametersRead = printer.params.map { ParameterRead(it, derived[it]) },
                definitionsInlined = inlined.toList(),
                imprecisionMode = mode(target),
                diagnostics = advisories.values.toList(),
            )
        }

        fun fresh(base: String): String {
            var name = base
            var i = 2
            while (!aliases.add(name)) name = "${base}_${i++}"
            return name
        }

        fun row(
            entity: Entity,
            select: Select,
            alias: String = fresh(entity.qname.name),
        ): Row = Row(alias, entity, select).also { rows[alias] = it }

        /** Runs [block]; on [Correlated] every record it made is rolled back and [fallback] answers. */
        fun <T> attempt(
            block: () -> T,
            fallback: () -> T,
        ): T {
            val a = aliases.toSet()
            val r = rows.toMap()
            val i = inlined.toList()
            val d = derived.toMap()
            val adv = advisories.toMap()
            val m = memo.toMap()
            return try {
                block()
            } catch (c: Correlated) {
                aliases.retainAll(a)
                rows.keys.retainAll(r.keys)
                inlined.retainAll(i.toSet())
                derived.keys.retainAll(d.keys)
                advisories.keys.retainAll(adv.keys)
                memo.keys.retainAll(m.keys)
                fallback()
            }
        }

        // ---- the target ---------------------------------------------------------------------------------------

        private fun build(): Select {
            inlining.addLast(target.qname)
            val def =
                model.definitions.of(target.qname).firstOrNull {
                    it.kind == BlockKind.DEFINITION || it.kind == BlockKind.MEMBERS
                }
            if (def == null && target.stereotype == "phase" && (target.fromEvent != null || target.period != null)) {
                val bearer = target.of?.let { cn.concepts[it] } ?: refuse("`${target.ref}` has no episode subject")
                val expression =
                    ColumnRef(null, target.qname.name, location = org.tatrman.ttrp.ast.SourceLocation.UNKNOWN)
                return if (context) context(bearer, expression, false) else predicate(bearer, expression, false)
            }
            if (def == null) {
                // a stacked-entity phase (`cn2er_phase`) renders FROM its entity, nothing more
                val g = target.grounding
                val entity =
                    (
                        if (target.stereotype in setOf("phase", "subkind") &&
                            g?.layer == GroundingLayer.ER
                        ) {
                            entityOf(g.target)
                        } else {
                            null
                        }
                    )
                        ?: refuse("`${target.ref}` has no definition to render and no stacked entity on E-R")
                val select = Select(Source.Entity(entity.qname.name, fresh(entity.qname.name)))
                select.items += keys(entity).map { Item(SqlExpr.Col(select.from.alias, it)) }
                return select
            }
            val expression = def.expression ?: refuse("`${target.ref}`'s definition does not parse")
            val bearer = def.bearer?.let { cn.concepts[it] } ?: refuse("`${target.ref}` has no bearer")
            val quality = target.stereotype == "quality"
            return if (context) context(bearer, expression, quality) else predicate(bearer, expression, quality)
        }

        private fun predicate(
            bearer: Concept,
            expression: Expression,
            quality: Boolean,
        ): Select {
            val entity = entityOf(bearer) ?: unreachable(bearer)
            val select = Select(Source.Entity(entity.qname.name, fresh(entity.qname.name)))
            val self = Bound(row(entity, select, select.from.alias), bearer)
            val scope = Scope(null, emptyList(), self, self, target, select)
            select.items += keys(entity).map { Item(SqlExpr.Col(select.from.alias, it)) }
            if (quality) select.items += Item(qualityValue(expression, scope), target.qname.name)
            project(options.columns, scope, select)
            if (!quality) {
                val conjuncts = conjuncts(expression)
                val grouped = conjuncts.mapNotNull { groupable(it) }.singleOrNull()
                for (c in conjuncts) {
                    if (grouped != null &&
                        c === grouped.conjunct
                    ) {
                        group(grouped, scope)
                    } else {
                        select.where += predicateOf(c, scope)
                    }
                }
            }
            return select
        }

        /** A defined quality's value as the target — a derived table it lifts is named after it, as when it is read. */
        private fun qualityValue(
            expression: Expression,
            scope: Scope,
        ): SqlExpr {
            qualities.addLast(target.qname.name)
            val v = scalar(value(expression, scope))
            qualities.removeLast()
            return v
        }

        private fun context(
            bearer: Concept,
            expression: Expression,
            quality: Boolean,
        ): Select {
            val candidates =
                options.candidates?.let { ref ->
                    val name = ref.qname.removePrefix("cn.")
                    cn.concepts.values.firstOrNull { it.qname.name == name && it.stereotype == "phase" }
                        ?: refuse("the candidates `${ref.qname}` are no phase")
                }
            val candidateDef =
                candidates?.let { c ->
                    model.definitions.of(c.qname).firstOrNull { it.kind == BlockKind.DEFINITION }
                        ?: refuse("the candidates `${c.ref}` have no `definition:`")
                }
            val root = candidateDef?.bearer?.let { cn.concepts[it] } ?: bearer
            val rootEntity = entityOf(root) ?: unreachable(root)
            val select = Select(Source.Entity(rootEntity.qname.name, fresh(rootEntity.qname.name)))
            val rootRow = row(rootEntity, select, select.from.alias)
            val rootRef = Bound(rootRow, root)
            val grain = mutableListOf(rootRow)
            val determined = mutableSetOf<Row>()

            val self = link(bearer, rootRef, select)
            if (self is Bound && self.row !== rootRow) {
                grain += self.row
                determined += rootRow
            }
            val frame = Scope(null, emptyList(), self, self, target, select)

            // an existence keyed by the bearer's detail rows, at the top of the conjunction: an inner JOIN
            val conjuncts = if (quality) emptyList() else conjuncts(expression)
            val lifted = linkedMapOf<ExistsExpr, Ranged>()
            for (c in conjuncts) {
                if (c !is ExistsExpr || c.negated || c.concepts.size != 1) continue
                val p = c.involving.singleOrNull() ?: continue
                if ((p.path as? ThisRef)?.path?.isEmpty() != true) continue
                val b = c.concepts.single()
                val ranged = named(b, frame)
                val entity = entityOf(ranged) ?: unreachable(ranged)
                val r = row(entity, select, fresh(b.variable ?: entity.qname.name))
                val (on, by) = correlate(r, ranged, p.label, RowV(self))
                select.joins += Join(false, Source.Entity(entity.qname.name, r.alias), SqlExpr.and(on)!!)
                grain += r
                by?.let { determined += it }
                lifted[c] = Ranged(ranged, b.variable, Bound(r, ranged))
            }

            // the projection first — its lifted joins come before the WHERE's (AU's island order)
            val candidateScope = Scope(null, emptyList(), rootRef, rootRef, candidates ?: target, select)
            val bearerScope = Scope(candidateScope, emptyList(), self, self, target, select)
            val columnScope =
                Scope(bearerScope, lifted.values.toList(), lifted.values.singleOrNull()?.ref, self, target, select)
            for (g in grain) {
                if (g !in determined) select.items += keys(g.entity).map { Item(SqlExpr.Col(g.alias, it)) }
            }
            if (quality) select.items += Item(qualityValue(expression, frame), target.qname.name)
            project(options.columns, columnScope, select)

            if (candidates != null && candidateDef != null) {
                val candidateExpression =
                    candidateDef.expression ?: refuse("`${candidates.ref}`'s definition does not parse")
                select.where += inline(candidates, candidateExpression, rootRef, select, record = true)
            }
            for (c in conjuncts) {
                val ranged = lifted[c]
                if (ranged != null) {
                    c as ExistsExpr
                    val inner = Scope(frame, listOf(ranged), ranged.ref, self, target, select)
                    c.where?.let { select.where += predicateOf(it, inner) }
                } else {
                    select.where += predicateOf(c, frame)
                }
            }
            return select
        }

        // ---- a statement read (CN-P6 ⚑S62-1) --------------------------------------------------------------------

        fun statement(spec: ErStatement): Rendering {
            val entity = at("measures[0]") { entityOf(spec.event) ?: unreachable(spec.event) }
            val select = Select(Source.Entity(entity.qname.name, fresh(entity.qname.name)))
            outer = select
            val row = row(entity, select, select.from.alias)
            val self = Bound(row, spec.event)
            spec.groupBy.forEachIndexed { i, g ->
                at("groupBy[$i]") {
                    val c = slotColumn(g, self, "a group-by")
                    select.items += Item(c, snake(g.qname.name))
                    select.groupBy += c
                }
            }
            val aggregates =
                spec.measures.mapIndexed { i, m ->
                    at("measures[$i]") {
                        val (on, attribute) =
                            ParticipantPaths.attributeRef(m.quality.erTarget)
                                ?: refuse("the measure `${m.quality.ref}` lands on no attribute of E-R")
                        if (on != entity.qname.name) {
                            refuse(
                                "the measure `${m.quality.ref}` lands on `$on`, not on the event's `${entity.qname.name}`",
                            )
                        }
                        SqlExpr.Fn(m.function, listOf(SqlExpr.Col(row.alias, attribute))).also {
                            select.items += Item(it, m.quality.qname.name)
                        }
                    }
                }
            for (p in spec.phases) phase(p, row, select)
            for (c in spec.conditions) select.where += condition(c, self)
            spec.time?.let { (lo, hi) ->
                at("time") {
                    val day = instantColumn(spec.event, row)
                    select.where += SqlExpr.Bin(">=", day, SqlExpr.Lit("DATE '$lo'"))
                    select.where += SqlExpr.Bin("<", day, SqlExpr.Lit("DATE '$hi'"))
                }
            }
            select.having =
                SqlExpr.and(
                    spec.havings.map { (i, op, literal) ->
                        SqlExpr.Bin(op, aggregates[i], SqlExpr.Lit(literal))
                    },
                )
            select.orderBy +=
                spec.orderBy.map { (i, desc) ->
                    SqlExpr.Lit(
                        ErNames.sql(
                            spec.measures[i]
                                .quality.qname.name,
                        ),
                    ) to desc
                }
            select.limit = spec.limit
            return print(select, spec)
        }

        /** [block] rendered as frame slot [s]: a refusal inside names [s] — and, [phase], a phase that does not render. */
        private fun <T> at(
            s: String,
            phase: Boolean = false,
            block: () -> T,
        ): T {
            val was = slot
            slot = s
            try {
                return block()
            } catch (r: Refused) {
                throw if (r.subject != null) r else Refused(r.slug, r.message, s, phase, r.frame)
            } finally {
                slot = was
            }
        }

        /** [p]'s definition inlined on the event's [row] — while the rows stay the event's rows (⚑R152-b). */
        private fun phase(
            p: ErPhase,
            row: Row,
            select: Select,
        ) {
            val ph = p.phase
            val (bearer, block) =
                at(p.slot) {
                    val block =
                        model.definitions.of(ph.qname).firstOrNull { it.kind == BlockKind.DEFINITION }
                            ?: refuse(
                                "`${ph.ref}` has no `definition:` to inline (a stacked-entity phase is a follow-up)",
                            )
                    val bearer = block.bearer?.let { cn.concepts[it] } ?: refuse("`${ph.ref}` has no bearer")
                    if (entityOf(bearer)?.qname != row.entity.qname) {
                        refuse(
                            "`${ph.ref}` is a phase of `${bearer.ref}`, which does not land on the event's `${row.entity.qname.name}`",
                        )
                    }
                    bearer to block
                }
            val joins = select.joins.size
            val fanned = fannedOut.size
            val sql =
                at(p.slot, phase = true) {
                    val expression = block.expression ?: refuse("`${ph.ref}`'s definition does not parse")
                    inline(ph, expression, Bound(row, bearer), select, record = true)
                }
            at(p.slot) {
                if (fannedOut.size > fanned) {
                    refuse(
                        "`${ph.ref}` joins `${fannedOut.drop(fanned).joinToString("`, `")}` from its many side — the " +
                            "event's rows would repeat under the aggregate (a follow-up: an `EXISTS` over the event's key)",
                    )
                }
                if (p.negated && select.joins.size > joins) {
                    val joined =
                        select.joins.drop(joins).joinToString("`, `") {
                            when (val s = it.source) {
                                is Source.Entity -> s.name
                                is Source.Derived -> s.alias
                            }
                        }
                    refuse(
                        "a negated phase (`not ${ph.ref}`) that joins `$joined` needs a complement over the event's " +
                            "key — not read on E-R (G-MD-1; a follow-up)",
                    )
                }
            }
            // two-valued (the wire has no `IS NOT TRUE`): where the phase is NULL the row counts as "not"
            select.where +=
                if (p.negated) SqlExpr.Not(SqlExpr.Fn("COALESCE", listOf(sql, SqlExpr.Lit("FALSE")))) else sql
        }

        /** A member / quality condition on the event's row, its refusals on the filter's own part of the slot. */
        private fun condition(
            c: ErCondition,
            self: Bound,
        ): SqlExpr =
            when (c) {
                is ErCondition.Member -> {
                    val col = at("${c.slot}.member.concept") { slotColumn(c.concept, self, "a member filter") }
                    SqlExpr.Bin("=", col, at("${c.slot}.member.member") { typed(c, col, self.row.entity) })
                }
                is ErCondition.Quality -> {
                    val col = at("${c.slot}.quality.quality") { slotColumn(c.concept, self, "a quality filter") }
                    if (c.literal ==
                        null
                    ) {
                        SqlExpr.IsNull(col, c.op == "<>")
                    } else {
                        SqlExpr.Bin(c.op, col, SqlExpr.Lit(c.literal))
                    }
                }
                is ErCondition.Within -> at("${c.slot}.quality.quality") { within(c, self.row) }
            }

        /** [c] as a column of the event's row: its own attribute (a value kind, a quality), or the key it holds to [c]. */
        private fun slotColumn(
            c: Concept,
            self: Bound,
            slot: String,
        ): SqlExpr {
            val entity = self.row.entity.qname.name
            ParticipantPaths.attributeRef(c.erTarget)?.let { (on, attribute) ->
                if (on == entity) return SqlExpr.Col(self.row.alias, attribute)
            }
            val label =
                participantOn(self.concept, c)
                    ?: refuse(
                        "`${c.ref}` as $slot is no column of `$entity` and no participant of `${self.concept.ref}`",
                    )
            // the participant's key reads the participant as declared, or a broader concept — a narrower one's
            // restriction (`members:`, a definition) would be dropped (review-154 E7)
            val declared =
                lattice
                    .chain(self.concept)
                    .flatMap { it.involves }
                    .firstOrNull { it.label == label }
                    ?.concept
                    ?.let { cn.concepts[it] }
            if (declared != null && lattice.chain(declared).none { it.qname == c.qname }) {
                refuse("`${c.ref}` narrows `${declared.ref}` — its restriction is not read on E-R (a follow-up)")
            }
            val rels = path(self.concept, label)
            if (rels.isEmpty()) {
                // `label: []` — the participant is the event's own row, read by its (one) key
                val key =
                    keys(self.row.entity).singleOrNull()
                        ?: refuse("`${c.ref}` as $slot is the `$entity` row itself, which has no single key")
                return col(self.row, key)
            }
            val r =
                rels.singleOrNull()
                    ?: refuse(
                        "`${c.ref}` as $slot is ${rels.size} relations away from `$entity` — one is read as its key",
                    )
            if (r.fromEntity.name != entity || r.joinPairs.size != 1) {
                refuse("`${c.ref}` as $slot: `${r.qname.name}` holds no single key on `$entity`")
            }
            return col(
                self.row,
                r.joinPairs
                    .single()
                    .fromAttr.name,
            )
        }

        /**
         * A member's value as the literal its column takes: digits for a numeric column, else quoted. A participant's
         * member is the key the event holds (⚑R152-c), so a value a numeric key cannot take is `cn/slot-mismatch`.
         */
        private fun typed(
            m: ErCondition.Member,
            col: SqlExpr,
            entity: Entity,
        ): SqlExpr {
            val name = (col as? SqlExpr.Col)?.name
            val type = entity.attributes.firstOrNull { simple(it) == name }?.let { ErType.of(it.type) }
            if (type != ErType.INTEGER && type != ErType.DECIMAL) return SqlExpr.Lit(quote(m.value))
            if (m.value.toBigDecimalOrNull() == null) {
                throw Refused(
                    UNREACHABLE,
                    "`${m.value}` is no ${type.name.lowercase()} — `${m.concept.ref}`'s member is the key " +
                        "`${entity.qname.name}.$name` holds",
                    frame = FrameSlugs.SLOT_MISMATCH,
                )
            }
            return SqlExpr.Lit(m.value)
        }

        /** An instant filter as bounds on its interval (⚑R152-f) — on the column's day when it keys a calendar. */
        private fun within(
            c: ErCondition.Within,
            row: Row,
        ): SqlExpr {
            val (on, attribute) =
                ParticipantPaths.attributeRef(c.concept.erTarget)?.takeIf { it.first == row.entity.qname.name }
                    ?: refuse("`${c.concept.ref}` compared with an instant is no column of `${row.entity.qname.name}`")
            val day = dayOf(row, attribute)
            val dated = day.moment?.granularity == Granularity.DAY

            fun bound(t: java.time.LocalDateTime): SqlExpr =
                SqlExpr.Lit(
                    if (dated && t.toLocalTime() == java.time.LocalTime.MIDNIGHT) {
                        "DATE '${t.toLocalDate()}'"
                    } else {
                        "TIMESTAMP '${TIMESTAMP.format(t)}'"
                    },
                )
            val lo = bound(c.lo)
            val hi = bound(c.hi)
            val col = day.sql
            return when (c.op) {
                "=" -> SqlExpr.and(listOf(SqlExpr.Bin(">=", col, lo), SqlExpr.Bin("<", col, hi)))!!
                "<>" -> SqlExpr.or(listOf(SqlExpr.Bin("<", col, lo), SqlExpr.Bin(">=", col, hi)))!!
                ">" -> SqlExpr.Bin(">=", col, hi)
                ">=" -> SqlExpr.Bin(">=", col, lo)
                "<" -> SqlExpr.Bin("<", col, lo)
                "<=" -> SqlExpr.Bin("<", col, hi)
                else -> refuse("`${c.op}` on an instant")
            }
        }

        /**
         * The day [event]'s period instant falls on, read on [row] — its `at`, else its `start` ([periodBound], so the
         * grounding's `period:` first, else the cn period's quality; through [dayOf] either way).
         */
        private fun instantColumn(
            event: Concept,
            row: Row,
        ): SqlExpr {
            val owner = lattice.chain(event).firstOrNull { it.period != null }
            val p = owner?.period
            val gp = grounding(event) { it.period != null }?.period
            val bound =
                when {
                    gp?.at != null -> "at"
                    gp?.start != null -> "start"
                    p?.at != null -> "at"
                    p?.start != null -> "start"
                    else -> refuse("`${event.ref}` has no period on E-R — the statement's time has nothing to read")
                }
            return periodBound(
                Bound(row, event),
                owner ?: event,
                bound,
                (
                    if (bound ==
                        "at"
                    ) {
                        p?.at
                    } else {
                        p?.start
                    }
                ) ?: "",
            ).sql
        }

        /**
         * The day [attr] of [row] dates (review-154 E6): the column when it is a date or a timestamp (or of no known type);
         * else — a key into a calendar (`sold_date → date_dim.sk`) — the calendar the one relation keyed by it reaches,
         * joined through [hop] (so a second read reuses the join), at its `semantics { role: event_date }` column.
         */
        private fun dayOf(
            row: Row,
            attr: String,
        ): Scalar {
            val a = attribute(row.entity, attr)
            val type = a?.let { ErType.of(it.type) }
            if (type == null || type == ErType.DATE || type == ErType.TIMESTAMP) {
                return Scalar(SqlExpr.Col(row.alias, attr), CnDefinitionLints.ofType(a?.type)?.let { Moment(it) })
            }
            val keyed =
                er?.relations?.values.orEmpty().filter { r ->
                    r.fromEntity.name == row.entity.qname.name &&
                        r.joinPairs.size == 1 &&
                        r.joinPairs
                            .single()
                            .fromAttr.name
                            .substringAfterLast('.') == attr
                }
            val r =
                keyed.singleOrNull()
                    ?: refuse(
                        "`${row.entity.qname.name}.$attr` is no date, and no one relation keys a calendar by it",
                    )
            val calendar = entityNamed(r.toEntity.name) ?: refuse("no entity behind `${r.qname.name}`")
            val day =
                calendar.attributes.singleOrNull { it.semantics?.role == "event_date" }
                    ?: refuse("`${calendar.qname.name}` (by `${r.qname.name}`) marks no `event_date` column")
            return Scalar(
                SqlExpr.Col(hop(row, r).alias, simple(day)),
                Moment(
                    CnDefinitionLints.ofType(day.type) ?: Granularity.DAY,
                ),
            )
        }

        /**
         * The read printed once, every placeholder bound as it is printed (review-154 E13): `as_of` the day, a derived
         * bound computed, a `:name` from [ErStatement.values]; one that cannot be bound refuses on the slot that read it.
         */
        private fun print(
            select: Select,
            spec: ErStatement,
        ): Rendering {
            val printer =
                SqlPrinter(PlaceholderStyle.CURLY, compact) { name ->
                    val at = paramSlots[name]
                    try {
                        when {
                            name == "as_of" -> "DATE '${spec.asOf()}'"
                            name in derived -> "DATE '${derivedDay(derived.getValue(name), spec.asOf(), name)}'"
                            else ->
                                spec.values[name]
                                    ?: refuse("`:$name` has no value in the world — a `def parameter` with `value:`")
                        }
                    } catch (r: Refused) {
                        throw Refused(r.slug, r.message, r.subject ?: at, phase = at?.endsWith(".phase.phase") == true)
                    }
                }
            return Rendering(
                text = printer.print(select),
                parametersRead = printer.params.map { ParameterRead(it, derived[it]) },
                definitionsInlined = inlined.toList(),
                imprecisionMode = mode(target),
                diagnostics = advisories.values.toList(),
            )
        }

        /** `as_of ± <unit>(<n>)` on [asOf] — the derivations [derive] records; any other cannot be bound. */
        private fun derivedDay(
            derivation: String,
            asOf: java.time.LocalDate,
            name: String,
        ): java.time.LocalDate {
            val m =
                Regex("""as_of ([+-]) (days|weeks|months)\((\d+)\)""").matchEntire(derivation)
                    ?: refuse("the bound `$name` (`$derivation`) cannot be computed for a statement")
            val n = m.groupValues[3].toLong() * if (m.groupValues[1] == "-") -1 else 1
            return when (m.groupValues[2]) {
                "days" -> asOf.plusDays(n)
                "weeks" -> asOf.plusWeeks(n)
                else -> asOf.plusMonths(n)
            }
        }

        /**
         * The target's bearer reached from the candidates' row [root]: the same row; a detail of it (a participant of the
         * bearer lands on it — the bearer's entity joined back along that path); or its master (a participant of it lands
         * on the bearer — the foreign key, joined on use).
         */
        private fun link(
            bearer: Concept,
            root: Bound,
            select: Select,
        ): Ref {
            if (entityOf(bearer)?.qname == root.row.entity.qname) return Bound(root.row, bearer)
            participantOn(bearer, root.concept)?.let { label ->
                val rels = path(bearer, label)
                val r =
                    rels.singleOrNull()
                        ?: refuse(
                            "the bearer `${bearer.ref}` reaches the candidates by a path of ${rels.size} relations — one is joined back",
                        )
                val entity = entityOf(bearer) ?: unreachable(bearer)
                val b = row(entity, select)
                val on =
                    r.joinPairs.map { p ->
                        SqlExpr.Bin("=", col(b, p.fromAttr.name), col(root.row, p.toAttr.name))
                    }
                select.joins += Join(false, Source.Entity(entity.qname.name, b.alias), SqlExpr.and(on)!!)
                b.hops[r.qname.name] = root.row
                return Bound(b, bearer)
            }
            participantOn(root.concept, bearer)?.let { label ->
                val rels = path(root.concept, label)
                // `label: []` names the candidates' own row, and the bearer is not on it (the same-row case is above)
                if (rels.isEmpty()) {
                    refuse(
                        "`${root.concept.ref}`'s participant `$label` is its own row — the bearer `${bearer.ref}` is not on it",
                    )
                }
                var cur = root.row
                for (r in rels.dropLast(1)) cur = hop(cur, r)
                return Held(cur, rels.last(), bearer)
            }
            refuse("the candidates' bearer `${root.concept.ref}` and the bearer `${bearer.ref}` share no participant")
        }

        /** The label of [of]'s one participant that [lands] can stand for; null when there is none (or several). */
        private fun participantOn(
            of: Concept,
            lands: Concept,
        ): String? =
            lattice
                .chain(of)
                .flatMap { it.involves }
                .filter { p -> p.concept?.let { cn.concepts[it] }?.let { lattice.compatible(it, lands) } == true }
                .singleOrNull()
                ?.label

        /**
         * The label an episode's [owner] (a phase, or an event bounding one) names its subject [bearer] by (review-175
         * F5, R3): one of its own `involves:` the bearer can be, else one of its grounding's participant labels, else
         * the nearest chain level's — null when there is none, or several where the first are found. A phase's chain
         * holds its bearer, so a bearer that involves its own type (`Warehouse as parent`) never competes with the
         * phase's own subject.
         */
        private fun subjectOn(
            owner: Concept,
            bearer: Concept,
        ): String? {
            fun fits(concept: QualifiedName?) =
                concept?.let { cn.concepts[it] }?.let { lattice.compatible(it, bearer) } == true

            val own = owner.involves.filter { fits(it.concept) }.map { it.label }
            if (own.isNotEmpty()) return own.singleOrNull()
            val grounded =
                owner.grounding
                    ?.participants
                    ?.keys
                    .orEmpty()
                    .filter { fits(lattice.participant(owner, it)?.second?.concept) }
            if (grounded.isNotEmpty()) return grounded.singleOrNull()
            for (level in lattice.chain(owner).drop(1)) {
                val labels = level.involves.filter { fits(it.concept) }.map { it.label }
                if (labels.isNotEmpty()) return labels.singleOrNull()
            }
            return null
        }

        /** [owner]'s participant [label] as relations, holder first (`Grounding.participants`). */
        private fun path(
            owner: Concept,
            label: String,
        ): List<Relation> =
            (paths.of(owner, label) as? ParticipantPaths.Relations)?.relations
                ?: refuse(
                    "`${owner.ref}`'s participant `$label` has no relation path on E-R (cn/participant-path-broken)",
                )

        // ---- projection ---------------------------------------------------------------------------------------

        /** `columns` (A-CN-20): a participant — its key column(s); a grounded quality — its column; a defined one `AS`. */
        private fun project(
            columns: List<String>,
            scope: Scope,
            select: Select,
        ) {
            for (name in columns) {
                val (v, value) = names(name.split('.'), scope)
                when (v) {
                    is RowV -> select.items += keyColumns(v.ref).map { Item(it) }
                    is Scalar -> {
                        val defined = (value as? DefinitionResolver.Value.Quality)?.def?.definition != null
                        select.items += Item(v.sql, if (defined) name.substringAfterLast('.') else null)
                    }
                    is PeriodV -> refuse("the column `$name` is a period — name its `start`, `end` or `at`")
                }
            }
        }

        private fun keyColumns(ref: Ref): List<SqlExpr> =
            when (ref) {
                is Bound -> keys(ref.row.entity).map { SqlExpr.Col(ref.row.alias, it) }
                is Held -> ref.relation.joinPairs.map { col(ref.holder, it.fromAttr.name) }
            }

        /**
         * The key [ref] stands for, by its attribute names on the entity it is a row of: a bound row's own key, a held
         * one's relation target attributes (each the holder's foreign key) — what a key equality pairs by name, never
         * by position (review-175 R7).
         */
        private fun keyedColumns(ref: Ref): Map<String, SqlExpr> =
            when (ref) {
                is Bound -> keys(ref.row.entity).associateWith { SqlExpr.Col(ref.row.alias, it) }
                is Held -> ref.relation.joinPairs.associate { it.toAttr.name to col(ref.holder, it.fromAttr.name) }
            }

        /** `<alias>.<k> = <outer k>` for every key [k] of [inner], paired by name; null when the two keys differ. */
        private fun keyEqualities(
            alias: String,
            inner: List<String>,
            outer: Ref,
        ): List<SqlExpr>? {
            val columns = keyedColumns(outer)
            if (inner.toSet() != columns.keys) return null
            return inner.map { k -> SqlExpr.Bin("=", SqlExpr.Col(alias, k), columns.getValue(k)) }
        }

        // ---- predicates ---------------------------------------------------------------------------------------

        private fun conjuncts(e: Expression): List<Expression> =
            if (e is FunctionCall && e.function.value == "op.and") e.args.flatMap(::conjuncts) else listOf(e)

        private fun predicateOf(
            e: Expression,
            scope: Scope,
        ): SqlExpr = (value(e, scope) as? Scalar)?.sql ?: refuse("`${spelled(e)}` is not a predicate")

        /** One aggregate comparison a predicate can group by: `count(E involving …) >= 2` — false on no rows. */
        private inner class Grouped(
            val conjunct: Expression,
            val aggregate: ConceptAggregate,
            val op: String,
            val other: Expression,
            val aggregateFirst: Boolean,
        )

        private fun groupable(c: Expression): Grouped? {
            if (c !is FunctionCall) return null
            val op = COMPARISONS[c.function.value] ?: return null
            val (agg, other, first) =
                when {
                    c.args[0] is ConceptAggregate -> Triple(c.args[0] as ConceptAggregate, c.args[1], true)
                    c.args[1] is ConceptAggregate -> Triple(c.args[1] as ConceptAggregate, c.args[0], false)
                    else -> return null
                }
            if (agg.function !in GROUPED || agg.concepts.size != 1 || lookup(agg)) return null
            if (agg.function == "count") {
                val n = ((other as? Literal)?.value as? LiteralValue.Num)?.raw?.toBigDecimalOrNull() ?: return null
                // the comparison as `count <op> n`, and whether it holds for a bearer with no rows (count 0)
                val asWritten = if (first) op else FLIPPED.getValue(op)
                val holdsAtZero =
                    when (asWritten) {
                        ">=" -> n.signum() <= 0
                        ">" -> n.signum() < 0
                        "=" -> n.signum() == 0
                        else -> true
                    }
                if (holdsAtZero) return null
            } else if (other !is Literal && other !is ParamRef) {
                return null
            }
            return Grouped(c, agg, op, other, first)
        }

        /** The grouped form: the ranged rows joined, their `where` in the WHERE, grouped by the bearer, the HAVING. */
        private fun group(
            g: Grouped,
            scope: Scope,
        ) {
            val select = scope.select
            val b = g.aggregate.concepts.single()
            val ranged = named(b, scope)
            val entity = entityOf(ranged) ?: unreachable(ranged)
            val r = row(entity, select, fresh(b.variable ?: entity.qname.name))
            val on = g.aggregate.involving.flatMap { p -> correlate(r, ranged, p.label, value(p.path, scope)).first }
            select.joins += Join(false, Source.Entity(entity.qname.name, r.alias), SqlExpr.and(on)!!)
            val inner =
                Scope(
                    scope,
                    listOf(Ranged(ranged, b.variable, Bound(r, ranged))),
                    Bound(r, ranged),
                    scope.self,
                    scope.owner,
                    select,
                )
            g.aggregate.where?.let { select.where += predicateOf(it, inner) }
            select.groupBy += select.items.map { it.expr }
            val aggregate = aggregateCall(g.aggregate, Bound(r, ranged))
            val other = scalar(value(g.other, scope))
            select.having =
                if (g.aggregateFirst) SqlExpr.Bin(g.op, aggregate, other) else SqlExpr.Bin(g.op, other, aggregate)
        }

        private fun aggregateCall(
            a: ConceptAggregate,
            ranged: Bound,
        ): SqlExpr =
            if (a.function == "count") {
                SqlExpr.Fn("COUNT", listOf(SqlExpr.Star))
            } else {
                SqlExpr.Fn(a.function.uppercase(), listOf(member(a, ranged)))
            }

        private fun member(
            a: ConceptAggregate,
            ranged: Ref,
        ): SqlExpr {
            if (a.member.isEmpty()) refuse("`${a.function}(…)` with no quality after it")
            return scalar(walkFrom(ranged, a.member, 0).first)
        }

        // ---- values -------------------------------------------------------------------------------------------

        private fun scalar(v: V): SqlExpr =
            when (v) {
                is Scalar -> v.sql
                is RowV ->
                    keyColumns(v.ref).singleOrNull()
                        ?: refuse("`${v.ref.concept.ref}` has a composite key — compare its parts")
                is PeriodV -> refuse("a period is no value — name its `start`, `end` or `at`")
            }

        fun value(
            e: Expression,
            scope: Scope,
        ): V =
            when (e) {
                is Literal -> Scalar(SqlExpr.Lit(literal(e.value)))
                is ParamRef ->
                    if (e.name == "as_of") {
                        slot?.let { paramSlots.putIfAbsent(e.name, it) }
                        Scalar(SqlExpr.Param("as_of"), Moment(Granularity.DAY, asOf = true))
                    } else {
                        slot?.let { paramSlots.putIfAbsent(e.name, it) }
                        Scalar(SqlExpr.Param(e.name))
                    }
                is DurationExpr ->
                    refuse(
                        "a duration (`${duration(e)}`) — interval arithmetic is not on the er path",
                        "G-ER-1",
                    )
                is ColumnRef -> names((e.port?.split('.') ?: emptyList()) + e.column, scope).first
                is ThisRef -> {
                    val self =
                        scope.self
                            ?: if (scope.outward().any { it.sealed }) {
                                throw Correlated()
                            } else {
                                refuse(
                                    "`this` has no bearer here",
                                )
                            }
                    walkFrom(self, e.path, 0).first
                }
                is FunctionCall -> call(e, scope)
                is AggregateCall ->
                    refuse(
                        "`${e.function.name}(…)` — an aggregate is written over a concept (`count(E involving …)`)",
                    )
                is Cast -> refuse("a cast")
                is CaseWhen ->
                    Scalar(
                        SqlExpr.Case(
                            e.branches.map { (w, t) -> predicateOf(w, scope) to scalar(value(t, scope)) },
                            e.elseExpr?.let { scalar(value(it, scope)) },
                        ),
                    )
                is InList ->
                    Scalar(
                        SqlExpr.InList(
                            scalar(value(e.expr, scope)),
                            e.items.map { scalar(value(it, scope)) },
                            e.negated,
                        ),
                    )
                is IsNull -> Scalar(SqlExpr.IsNull(scalar(value(e.expr, scope)), e.negated))
                is MdPath -> refuse("an md dot-path")
                is ExistsExpr -> Scalar(exists(e, scope))
                is ConceptAggregate -> aggregate(e, scope)
                is InCall -> Scalar(closure(e, scope))
                is TemporalPredicate -> Scalar(temporal(e, scope))
            }

        private fun call(
            e: FunctionCall,
            scope: Scope,
        ): V {
            val id = e.function.value
            projection(e, scope)?.let { return it }
            COMPARISONS[id]?.let { op ->
                return Scalar(compare(op, value(e.args[0], scope), value(e.args[1], scope), scope))
            }
            return when (id) {
                "op.and" -> Scalar(SqlExpr.and(e.args.map { predicateOf(it, scope) })!!)
                "op.or" -> Scalar(SqlExpr.or(e.args.map { predicateOf(it, scope) })!!)
                "op.not" -> Scalar(SqlExpr.Not(predicateOf(e.args[0], scope)))
                "op.add", "op.sub" -> {
                    if (e.args.any { it is DurationExpr }) {
                        refuse(
                            "an instant ${if (id == "op.add") "+" else "-"} a duration — interval arithmetic",
                            "G-ER-1",
                        )
                    }
                    val l = value(e.args[0], scope)
                    val r = value(e.args[1], scope)
                    if ((l as? Scalar)?.moment != null || (r as? Scalar)?.moment != null) {
                        refuse("a datetime difference — datetime arithmetic is not on the plan.v1 wire", "G-ER-2")
                    }
                    Scalar(SqlExpr.Bin(if (id == "op.add") "+" else "-", scalar(l), scalar(r)))
                }
                "op.mul" -> Scalar(SqlExpr.Bin("*", scalar(value(e.args[0], scope)), scalar(value(e.args[1], scope))))
                "op.div" -> Scalar(SqlExpr.Bin("/", scalar(value(e.args[0], scope)), scalar(value(e.args[1], scope))))
                "op.neg" -> Scalar(SqlExpr.Bin("-", SqlExpr.Lit("0"), scalar(value(e.args[0], scope))))
                else -> {
                    // a dotted name is a projection read or nothing — never a SQL function (nor a built-in) of its
                    // last segment: `this.within(…)` is no window (review-175 R2)
                    if ('.' in id && CATALOG_PREFIXES.none { id.startsWith(it) }) {
                        refuse("`$id(…)` names no projected quality (DC-13) in scope")
                    }
                    when (e.function.name) {
                        "within" -> Scalar(window(e, scope))
                        "age", "working_days" -> refuse("`${e.function.name}(…)` — a datetime difference", "G-ER-2")
                        "descendants" -> refuse("`descendants(…)` outside `in`")
                        else -> Scalar(SqlExpr.Fn(e.function.name.uppercase(), e.args.map { scalar(value(it, scope)) }))
                    }
                }
            }
        }

        /**
         * DC-13: `this.q(…)`, `this.<path>.q(…)` or a bare `q(…)` read through [projectedCall]. A `this.` receiver walks
         * from the bearer (`self`); a bare one from the innermost implicit subject that has the projection, else from
         * `self`. The participant is bound to the row the receiver was resolved on, so the two never disagree.
         */
        private fun projection(
            e: FunctionCall,
            scope: Scope,
        ): V? {
            // the catalogue's own calls (`op.*`, `fn.*`, `agg.*`) are never a receiver path — no walk (review-175 R9)
            if (CATALOG_PREFIXES.any { e.function.value.startsWith(it) }) return null
            val parts = e.function.value.split('.')
            if (parts.first() == "this" && scope.self == null) {
                if (scope.outward().any { it.sealed }) throw Correlated()
                return null
            }
            val roots =
                if (parts.first() == "this") {
                    listOfNotNull(scope.self)
                } else {
                    (scope.outward().mapNotNull { it.implicit } + listOfNotNull(scope.self)).toList()
                }
            for (root in roots) {
                val receiver = projectionReceiver(e, root.concept, resolver) ?: continue
                val a =
                    projectedCall(e, root.concept, resolver, receiver)
                        ?: refuse(
                            "`${e.function.value}(…)` reads `${receiver.quality.ref}` — name `at:` and its `per:` participant only",
                        )
                return flat(a, scope, bound = walkFrom(root, receiver.path, 0).first)
            }
            return null
        }

        /** A comparison; two instants of different granularities under `certainly` are advised (degraded to as written). */
        private fun compare(
            op: String,
            l: V,
            r: V,
            scope: Scope,
        ): SqlExpr {
            if (l is RowV && r is RowV) {
                val pairs = keyColumns(l.ref).zip(keyColumns(r.ref)).map { (a, b) -> SqlExpr.Bin(op, a, b) }
                return when (op) {
                    "=" -> SqlExpr.and(pairs)!!
                    "<>" -> SqlExpr.or(pairs)!!
                    else -> refuse("`$op` between two rows")
                }
            }
            val ls = scalar(l)
            val rs = scalar(r)
            val lm = (l as? Scalar)?.moment
            val rm = (r as? Scalar)?.moment
            if (lm != null && rm != null) {
                throughBound(op, l as Scalar, r as Scalar, scope)?.let { return it }
                advise(op, lm, rm, scope)
            }
            return SqlExpr.Bin(op, ls, rs)
        }

        /**
         * A plain comparison read CERTAINLY through the coarser side's stored upper bound where that bound decides it
         * ([CnDefinitionLints.decidedByBound], A-CN-45, review-125 R125-4) — as the DC-12 words are: `x <= t` → `x_hi <=
         * {as_of_hi}`, `x < t` → `x_hi <= t`, mirrored for `t >= x` / `t > x`. Null — the stored instants and the advisory
         * — when the bound does not decide, there is none, or `t`'s upper end is not derivable (a column: G-ER-1).
         */
        private fun throughBound(
            op: String,
            l: Scalar,
            r: Scalar,
            scope: Scope,
        ): SqlExpr? {
            if (scope.owner.imprecision == Imprecision.POSSIBLY) return null
            val gl = l.moment?.granularity ?: return null
            val gr = r.moment?.granularity ?: return null
            val (x, t) = if (gl > gr) l to r else r to l
            if (gl == gr || !CnDefinitionLints.decidedByBound(op, gl > gr, t.moment?.asOf == true)) return null
            val hi = x.moment?.hi ?: return null
            return if (op == "<" || op == ">") SqlExpr.Bin("<=", hi, t.sql) else SqlExpr.Bin("<=", hi, upper(t))
        }

        private fun advise(
            op: String,
            a: Moment,
            b: Moment,
            scope: Scope,
        ) {
            val ga = a.granularity ?: return
            val gb = b.granularity ?: return
            // a comparison read through a stored bound never reaches here (throughBound, the DC-12 branch) — R125-4
            if (ga == gb || scope.owner.imprecision == Imprecision.POSSIBLY) return
            val coarser = maxOf(ga, gb).name.lowercase()
            val message =
                "`$op` compares a ${ga.name.lowercase()} with a ${gb.name.lowercase()} under " +
                    "`imprecision: certainly` — rendered at the instants as stored; the coarser one is certainly " +
                    "$op only when its whole $coarser is (a stored upper bound `at_hi:` / `start_hi:`, or " +
                    "`imprecision: possibly`; G-ER-1 derives it)"
            val advisory = Diagnostic(CnDefinitionLints.IMPRECISE, Severity.ADVISORY, message, target.ref)
            advisories.putIfAbsent(message, advisory)
        }

        // ---- names --------------------------------------------------------------------------------------------

        /** A dotted name (the resolver's order): a variable, a ranged concept's name, else the innermost subject that has it. */
        private fun names(
            parts: List<String>,
            scope: Scope,
        ): Pair<V, DefinitionResolver.Value> {
            val first = parts.first()
            scope.outward().flatMap { it.ranged }.firstOrNull { it.variable == first }?.let {
                return walkFrom(
                    it.ref,
                    parts,
                    1,
                )
            }
            scope
                .outward()
                .flatMap { it.ranged }
                .firstOrNull { it.variable == null && it.concept.qname.name == first }
                ?.let { return walkFrom(it.ref, parts, 1) }
            for (s in scope.outward()) {
                val subject = s.implicit ?: continue
                if (resolver.walk(
                        subject.concept,
                        parts,
                        0,
                    ) is DefinitionResolver.Walk.Ok
                ) {
                    return walkFrom(subject, parts, 0)
                }
            }
            if (scope.outward().any { it.sealed }) throw Correlated()
            refuse("`${parts.joinToString(".")}` names nothing in scope")
        }

        /** [parts] from [i] walked on [ref]'s concept (the shared walk) and replayed on the query: joins, columns. */
        private fun walkFrom(
            ref: Ref,
            parts: List<String>,
            i: Int,
        ): Pair<V, DefinitionResolver.Value> {
            val steps = mutableListOf<DefinitionResolver.Step>()
            val w = resolver.walk(ref.concept, parts, i, steps = steps)
            if (w !is DefinitionResolver.Walk.Ok) refuse((w as DefinitionResolver.Walk.Fail).message)
            var cur: V = RowV(ref)
            for (s in steps) {
                val at = (cur as? RowV)?.ref ?: refuse("`${parts.joinToString(".")}` walks on past a value")
                cur =
                    when (s) {
                        is DefinitionResolver.Step.Participant -> participant(at, s.label, s.concept)
                        is DefinitionResolver.Step.ValueSpace -> refuse("`${s.quality}`'s value space as a path")
                    }
            }
            val v =
                when (val value = w.value) {
                    is DefinitionResolver.Value.Instance -> cur
                    is DefinitionResolver.Value.Quality -> quality((cur as RowV).ref, value)
                    is DefinitionResolver.Value.PeriodBound ->
                        periodBound(
                            (cur as RowV).ref,
                            value.owner,
                            value.bound,
                            value.quality,
                        )
                    is DefinitionResolver.Value.Period -> PeriodV((cur as RowV).ref, value.owner)
                    is DefinitionResolver.Value.Phase -> Scalar(phase((cur as RowV).ref, value.phase))
                    else -> refuse("`${parts.joinToString(".")}` is no value on E-R")
                }
            return v to w.value
        }

        private fun named(
            b: ConceptBinding,
            scope: Scope,
        ): Concept = resolver.named(b.concept, scope.owner.qname.`package`) ?: refuse("no concept `${b.concept}`")

        // ---- rows and hops ------------------------------------------------------------------------------------

        private fun col(
            row: Row,
            qualified: String,
        ): SqlExpr.Col = SqlExpr.Col(row.alias, qualified.substringAfterLast('.'))

        /** [row] joined along [r] (forward: `JOIN <to> ON <to>.<key> = <row>.<fk>`; backward the other way) — once. */
        private fun hop(
            row: Row,
            r: Relation,
        ): Row {
            row.hops[r.qname.name]?.let { return it }
            val forward = r.fromEntity.name == row.entity.qname.name
            if (!forward && r.toEntity.name != row.entity.qname.name) {
                refuse(
                    "the relation `${r.qname.name}` does not start at `${row.entity.qname.name}` (cn/participant-path-broken)",
                )
            }
            val entity =
                entityNamed(if (forward) r.toEntity.name else r.fromEntity.name)
                    ?: refuse("no entity behind `${r.qname.name}`")
            val next = row(entity, row.select)
            val on =
                r.joinPairs.map { p ->
                    if (forward) {
                        SqlExpr.Bin("=", col(next, p.toAttr.name), col(row, p.fromAttr.name))
                    } else {
                        SqlExpr.Bin("=", col(next, p.fromAttr.name), col(row, p.toAttr.name))
                    }
                }
            row.select.joins += Join(false, Source.Entity(entity.qname.name, next.alias), SqlExpr.and(on)!!)
            row.hops[r.qname.name] = next
            if (!forward && row.select === outer) fannedOut += r.qname.name
            return next
        }

        private fun bound(ref: Ref): Row =
            when (ref) {
                is Bound -> ref.row
                is Held -> hop(ref.holder, ref.relation)
            }

        private fun refKey(ref: Ref): String =
            when (ref) {
                is Bound -> ref.row.alias
                is Held -> "${ref.holder.alias}>${ref.relation.qname.name}"
            }

        /** [ref]'s participant [label]: the relation path joined but its last hop, which stays a held key. */
        private fun participant(
            ref: Ref,
            label: String,
            concept: Concept,
        ): V {
            val row = bound(ref)
            val rels =
                when (val p = paths.of(ref.concept, label, concept)) {
                    null ->
                        refuse(
                            "`${ref.concept.ref}`'s participant `$label` has no relation path on E-R (cn/participant-path-broken)",
                        )
                    // a value kind is a column of the event's own entity (AM-27)
                    is ParticipantPaths.Column -> {
                        if (p.entity != row.entity.qname.name) {
                            refuse("`$label` is a column of `${p.entity}`, not of `${row.entity.qname.name}`")
                        }
                        return Scalar(SqlExpr.Col(row.alias, p.attribute))
                    }
                    is ParticipantPaths.Relations -> p.relations
                }
            if (rels.isEmpty()) {
                if (entityOf(concept)?.qname != row.entity.qname) refuse("an empty participant path crosses entities")
                return RowV(Bound(row, concept))
            }
            var cur = row
            for (r in rels.dropLast(1)) cur = hop(cur, r)
            val last = rels.last()
            return if (last.fromEntity.name ==
                cur.entity.qname.name
            ) {
                RowV(Held(cur, last, concept))
            } else {
                RowV(Bound(hop(cur, last), concept))
            }
        }

        private fun attributeRef(ref: String?): Pair<String, String>? = ParticipantPaths.attributeRef(ref)

        /** `<row>.<attribute>` for an `er.entity.<e>.<a>` ref that must be on [row]'s entity. */
        private fun column(
            row: Row,
            ref: String,
        ): Scalar {
            val (entity, attr) = attributeRef(ref) ?: refuse("`$ref` is no E-R attribute")
            if (entity !=
                row.entity.qname.name
            ) {
                refuse("`$ref` is not on `${row.entity.qname.name}`, the row it is read on")
            }
            val type = attribute(row.entity, attr)?.type
            return Scalar(SqlExpr.Col(row.alias, attr), CnDefinitionLints.ofType(type)?.let { Moment(it) })
        }

        /**
         * The rows of [ranged] (a row [r] of its entity) that [outer] — a participant read in the outer scope — is
         * the participant [label] of (unlabelled: its one participant [outer] can stand for): the path's hops but the
         * last joined at [r], the last written as `<r>.<fk> = <outer key>` — onto the key [outer] holds when two keys
         * point at one entity. The row it determines, when [outer] is a row of the query.
         */
        private fun correlate(
            r: Row,
            ranged: Concept,
            label: String?,
            outer: V,
        ): Pair<List<SqlExpr>, Row?> {
            val ref = (outer as? RowV)?.ref
            val l =
                label ?: ref?.let { participantOn(ranged, it.concept) }
                    ?: refuse("`${ranged.ref}` involves no one participant that the row named can be")
            val rels =
                when (val p = paths.of(ranged, l)) {
                    null ->
                        refuse(
                            "`${ranged.ref}`'s participant `$l` has no relation path on E-R (cn/participant-path-broken)",
                        )
                    is ParticipantPaths.Column -> {
                        if (p.entity != r.entity.qname.name) {
                            refuse("`$l` is a column of `${p.entity}`, not of `${r.entity.qname.name}`")
                        }
                        return listOf(SqlExpr.Bin("=", SqlExpr.Col(r.alias, p.attribute), scalar(outer))) to null
                    }
                    is ParticipantPaths.Relations -> p.relations
                }
            if (ref == null) refuse("`${ranged.ref}`'s participant `$l` is a row — a value cannot stand for it")
            if (rels.isEmpty()) {
                // `l: []` — the ranged row is the participant itself: its key meets the key the outer row stands for
                val entity = r.entity.qname.name
                if (entityOf(ref.concept)?.qname?.name != entity) {
                    refuse("`${ranged.ref}`'s participant `$l` is its own `$entity` row — `${ref.concept.ref}` is not")
                }
                val pairs =
                    keyEqualities(r.alias, keys(r.entity), ref)
                        ?: refuse(
                            "`${ranged.ref}`'s participant `$l` is its own `$entity` row — the row named holds another key",
                        )
                return pairs to (ref as? Bound)?.row
            }
            var cur = r
            for (x in rels.dropLast(1)) cur = hop(cur, x)
            val last = rels.last()
            if (last.fromEntity.name !=
                cur.entity.qname.name
            ) {
                refuse(
                    "`${ranged.ref}`'s participant `$l` is reached backwards — no key of `${cur.entity.qname.name}` points at it",
                )
            }
            val target = last.toEntity.name
            if (ref is Held && ref.relation.toEntity.name == target) {
                val pairs =
                    last.joinPairs.map { p ->
                        p to
                            ref.relation.joinPairs.firstOrNull { it.toAttr.name == p.toAttr.name }
                    }
                if (pairs.all { it.second != null }) {
                    return pairs.map { (p, q) ->
                        SqlExpr.Bin("=", col(cur, p.fromAttr.name), col(ref.holder, q!!.fromAttr.name))
                    } to
                        null
                }
            }
            val x = bound(ref)
            if (x.entity.qname.name !=
                target
            ) {
                refuse(
                    "`${ranged.ref}`'s participant `$l` is a `$target` — `${ref.concept.ref}` lands on `${x.entity.qname.name}`",
                )
            }
            return last.joinPairs.map { p -> SqlExpr.Bin("=", col(cur, p.fromAttr.name), col(x, p.toAttr.name)) } to x
        }

        /** The deepest query among the rows [e] reads — where a join reading them may sit. */
        private fun deepest(
            e: List<SqlExpr>,
            fallback: Select,
        ): Select {
            val aliases = mutableSetOf<String>()

            fun visit(x: SqlExpr) {
                when (x) {
                    is SqlExpr.Col -> aliases += x.alias
                    is SqlExpr.Bin -> {
                        visit(x.l)
                        visit(x.r)
                    }
                    is SqlExpr.Junction -> x.parts.forEach(::visit)
                    is SqlExpr.Not -> visit(x.e)
                    is SqlExpr.Fn -> x.args.forEach(::visit)
                    is SqlExpr.IsNull -> visit(x.e)
                    is SqlExpr.InList -> (x.items + x.e).forEach(::visit)
                    is SqlExpr.InSelect -> visit(x.e)
                    is SqlExpr.Between -> listOf(x.e, x.lo, x.hi).forEach(::visit)
                    else -> Unit
                }
            }
            e.forEach(::visit)
            return aliases.mapNotNull { rows[it]?.select }.maxByOrNull { it.depth } ?: fallback
        }

        // ---- qualities, periods, phases -----------------------------------------------------------------------

        private fun quality(
            ref: Ref,
            q: DefinitionResolver.Value.Quality,
        ): V {
            val def =
                q.def ?: refuse("`${q.name}` of `${q.owner.ref}` is no quality concept — nothing grounds it on E-R")
            val block = model.definitions.of(def.qname).firstOrNull { it.kind == BlockKind.DEFINITION }
            if (block != null) {
                val key = def.qname to refKey(ref)
                memo[key]?.let { return it }
                if (def.qname in
                    inlining
                ) {
                    throw Refused(CnDefinitionLints.CYCLE, "the definitions inline each other at `${def.ref}`")
                }
                val expression = block.expression ?: refuse("`${def.ref}`'s definition does not parse")
                inlined += def.ref
                inlining.addLast(def.qname)
                qualities.addLast(def.qname.name)
                val select = bound(ref).select
                val v = value(expression, Scope(null, emptyList(), ref, ref, def, select))
                qualities.removeLast()
                inlining.removeLast()
                memo[key] = v
                return v
            }
            val target = def.erTarget ?: unreachable(def)
            return column(bound(ref), target)
        }

        private fun periodOf(c: Concept): CnPeriod? = lattice.chain(c).firstNotNullOfOrNull { it.period }

        /** `period.<bound>` of [ref]: the grounding's `period:` attribute, else the quality's; its stored upper bound. */
        private fun periodBound(
            ref: Ref,
            owner: Concept,
            bound: String,
            qualityName: String,
        ): Scalar {
            val row = bound(ref)
            val gp = grounding(ref.concept) { it.period != null }?.period
            val attr =
                when (bound) {
                    "start" -> gp?.start
                    "end" -> gp?.end
                    else -> gp?.at
                }
            val base =
                if (attr != null) {
                    periodColumn(row, attr)
                } else {
                    val q =
                        cn.concepts.values.firstOrNull {
                            it.stereotype == "quality" &&
                                it.qname.name == qualityName &&
                                it.of == owner.qname
                        }
                            ?: refuse(
                                "`${owner.ref}`'s `period.$bound` reads `$qualityName`, which nothing grounds on E-R",
                            )
                    periodColumn(row, q.erTarget ?: unreachable(q))
                }
            val hi =
                when (bound) {
                    "start" -> gp?.startHi
                    "at" -> gp?.atHi
                    else -> null
                }?.let { column(row, it).sql }
            val granularity = CnDefinitionLints.granularity(periodOf(owner)?.precision) ?: base.moment?.granularity
            return Scalar(base.sql, Moment(granularity, hi))
        }

        /** A period's `er.entity.<e>.<a>` on [row]'s entity, read as the day it dates ([dayOf], review-154 E6). */
        private fun periodColumn(
            row: Row,
            ref: String,
        ): Scalar {
            val (entity, attr) = attributeRef(ref) ?: refuse("`$ref` is no E-R attribute")
            if (entity !=
                row.entity.qname.name
            ) {
                refuse("`$ref` is not on `${row.entity.qname.name}`, the row it is read on")
            }
            return dayOf(row, attr)
        }

        private fun phase(
            ref: Ref,
            phase: Concept,
        ): SqlExpr {
            if (phase.fromEvent != null && phase.untilEvent != null) return boundedEpisode(ref, phase)
            val grounding = phase.grounding
            if (phase.stereotype == "phase" && grounding?.layer == GroundingLayer.ER && grounding.episodes != null) {
                val loc = org.tatrman.ttrp.ast.SourceLocation.UNKNOWN
                val bearer = phase.of?.let { cn.concepts[it] } ?: refuse("an episode table without its subject")
                // the label the episode's grounding names its subject by — as written, never guessed from a name
                val label =
                    subjectOn(phase, bearer)
                        ?: refuse(
                            "`${phase.ref}` involves no one participant `${bearer.ref}` can be (cn/episode-subject-missing)",
                        )
                val expression =
                    ExistsExpr(
                        listOf(ConceptBinding(phase.qname.name, null, loc)),
                        listOf(Participant(ThisRef(emptyList(), loc), label, loc)),
                        TemporalPredicate(
                            TemporalOp.CONTAINS,
                            ColumnRef(null, "period", location = loc),
                            ParamRef("as_of", loc),
                            loc,
                        ),
                        false,
                        loc,
                    )
                inlined += phase.ref
                return correlated(expression, Scope(null, emptyList(), ref, ref, phase, bound(ref).select))
            }
            val block =
                model.definitions.of(phase.qname).firstOrNull {
                    it.kind == BlockKind.DEFINITION ||
                        it.kind == BlockKind.MEMBERS
                }
                    ?: return stacked(ref, phase)
            return inline(
                phase,
                block.expression ?: refuse("`${phase.ref}`'s definition does not parse"),
                ref,
                bound(ref).select,
                record = true,
            )
        }

        /**
         * A stacked-entity subkind or phase (its own `cn2er_*` target, no definition) as a predicate: the subset test —
         * the row is one of its entity's rows, `EXISTS (SELECT 1 FROM <entity> WHERE <its key> = <the row's key>)`, the
         * same key attributes paired by name; a stacked entity whose key is named otherwise refuses (A-CN-117(d), R7).
         */
        private fun stacked(
            ref: Ref,
            phase: Concept,
        ): SqlExpr {
            val g = phase.grounding
            if (g?.layer != GroundingLayer.ER) {
                refuse("`${phase.ref}` has no `definition:` and no stacked entity on E-R")
            }
            val own = entityOf(phase) ?: unreachable(phase)
            val query = Select(Source.Entity(own.qname.name, fresh(own.qname.name)), bound(ref).select.depth + 1)
            query.items += Item(SqlExpr.Lit("1"))
            query.where +=
                keyEqualities(query.from.alias, keys(own), ref)
                    ?: refuse("`${phase.ref}`'s entity `${own.qname.name}` shares no key with the row it is read on")
            inlined += phase.ref
            return SqlExpr.Exists(query, false)
        }

        /**
         * CN-P8 (A-CN-114, ⚑A): the subject is in [phase] at `:as_of` when one of its start occurrences is at or before
         * it and no termination of the subject falls in `[start, :as_of]` — a termination at the start's own instant
         * closes the episode (`[t, t)` is empty), and so does one at the instant a new start reopens it.
         * `EXISTS (SELECT 1 FROM <starts> s WHERE s.subject = <key> AND s.at <= :as_of AND NOT EXISTS (SELECT 1 FROM
         * <terminations> f WHERE f.subject = s.subject AND f.at >= s.at AND f.at <= :as_of))`.
         */
        private fun boundedEpisode(
            ref: Ref,
            phase: Concept,
        ): SqlExpr {
            val bearer = phase.of?.let { cn.concepts[it] } ?: refuse("an episode without its subject")
            val started = phase.fromEvent?.let { cn.concepts[it] } ?: refuse("an episode without its start event")
            val finished = phase.untilEvent?.let { cn.concepts[it] } ?: refuse("an episode without its until event")
            val outerKeys = keyColumns(ref)
            val depth = bound(ref).select.depth

            /** `SELECT <subject key> AS subject_<i>, <instant> AS at FROM <event's entity> …` — one row per occurrence. */
            fun stream(
                event: Concept,
                at: Int,
            ): Select {
                val entity = entityOf(event) ?: unreachable(event)
                val query = Select(Source.Entity(entity.qname.name, fresh(entity.qname.name)), at)
                val eventRef = Bound(row(entity, query, query.from.alias), event)
                val label = subjectOn(event, bearer) ?: refuse("`${event.ref}` does not involve `${bearer.ref}`")
                val subject =
                    (participant(eventRef, label, bearer) as? RowV)?.ref
                        ?: refuse("an episode subject is not an entity")
                val keys = keyColumns(subject)
                if (keys.size != outerKeys.size) refuse("an episode subject has incompatible keys")
                val period = periodOf(event) ?: refuse("an episode bound has no instant")
                val instant =
                    periodBound(
                        eventRef,
                        event,
                        "at",
                        period.at ?: refuse("an episode bound is not instantaneous"),
                    ).sql
                query.items += keys.mapIndexed { i, key -> Item(key, "subject_$i") }
                query.items += Item(instant, "at")
                return query
            }
            val sa = fresh("starts")
            val starts = Select(Source.Derived(stream(started, depth + 2), sa), depth + 1)
            starts.items += Item(SqlExpr.Lit("1"))
            starts.where += outerKeys.mapIndexed { i, key -> SqlExpr.Bin("=", SqlExpr.Col(sa, "subject_$i"), key) }
            starts.where += SqlExpr.Bin("<=", SqlExpr.Col(sa, "at"), SqlExpr.Param("as_of"))
            val ea = fresh("terminations")
            val stops = Select(Source.Derived(stream(finished, depth + 3), ea), depth + 2)
            stops.items += Item(SqlExpr.Lit("1"))
            stops.where +=
                outerKeys.indices.map { i ->
                    SqlExpr.Bin("=", SqlExpr.Col(ea, "subject_$i"), SqlExpr.Col(sa, "subject_$i"))
                }
            stops.where += SqlExpr.Bin(">=", SqlExpr.Col(ea, "at"), SqlExpr.Col(sa, "at"))
            stops.where += SqlExpr.Bin("<=", SqlExpr.Col(ea, "at"), SqlExpr.Param("as_of"))
            starts.where += SqlExpr.Exists(stops, true)
            inlined += phase.ref
            return SqlExpr.Exists(starts, false)
        }

        /** [p]'s definition [expression] as a predicate on [ref] — `this` is [ref]; recorded as inlined. */
        private fun inline(
            p: Concept,
            expression: Expression,
            ref: Ref,
            select: Select,
            record: Boolean,
        ): SqlExpr {
            if (p.qname in
                inlining
            ) {
                throw Refused(CnDefinitionLints.CYCLE, "the definitions inline each other at `${p.ref}`")
            }
            if (record) inlined += p.ref
            inlining.addLast(p.qname)
            val sql = predicateOf(expression, Scope(null, emptyList(), ref, ref, p, select))
            inlining.removeLast()
            return sql
        }

        // ---- existence ----------------------------------------------------------------------------------------

        private fun exists(
            e: ExistsExpr,
            scope: Scope,
        ): SqlExpr {
            if (context && e.concepts.size == 1) {
                return attempt({ lifted(e, scope) }, { correlated(e, scope) })
            }
            return correlated(e, scope)
        }

        /** `[NOT] EXISTS (SELECT 1 FROM <E> WHERE <correlation> AND <where>)` — one sub-query per ranged concept. */
        private fun correlated(
            e: ExistsExpr,
            scope: Scope,
        ): SqlExpr {
            val outer = e.involving.map { p -> p.label to value(p.path, scope) }
            val selects = mutableListOf<Select>()
            val ranged = mutableListOf<Ranged>()
            var depth = scope.select.depth
            for (b in e.concepts) {
                val c = named(b, scope)
                val entity = entityOf(c) ?: unreachable(c)
                val alias = fresh(b.variable ?: entity.qname.name)
                val select = Select(Source.Entity(entity.qname.name, alias), ++depth)
                select.items += Item(SqlExpr.Lit("1"))
                val r = row(entity, select, alias)
                for ((label, v) in outer) select.where += correlate(r, c, label, v).first
                selects += select
                ranged += Ranged(c, b.variable, Bound(r, c))
            }
            val inner = Scope(scope, ranged, ranged.singleOrNull()?.ref, scope.self, scope.owner, selects.last())
            e.where?.let { selects.last().where += predicateOf(it, inner) }
            for (i in selects.indices.reversed().drop(1)) selects[i].where += SqlExpr.Exists(selects[i + 1], false)
            return SqlExpr.Exists(selects.first(), e.negated)
        }

        /** CONTEXT (A-CN-20): `LEFT JOIN (SELECT <key> FROM <E> WHERE <where>) <alias> ON …`, read `<alias>.<key> IS NOT NULL`. */
        private fun lifted(
            e: ExistsExpr,
            scope: Scope,
        ): SqlExpr {
            val b = e.concepts.single()
            val c = named(b, scope)
            val entity = entityOf(c) ?: unreachable(c)
            val d = Select(Source.Entity(entity.qname.name, fresh(entity.qname.name)), scope.select.depth + 1)
            val r = row(entity, d, d.from.alias)
            val sealed =
                Scope(
                    null,
                    listOf(Ranged(c, b.variable, Bound(r, c))),
                    Bound(r, c),
                    null,
                    scope.owner,
                    d,
                    sealed = true,
                )
            e.where?.let { d.where += predicateOf(it, sealed) }
            val (inner, outerCols) = derivedKeys(r, c, e.involving, scope)
            d.items += inner.map { Item(it) }
            val alias = fresh(qualities.lastOrNull() ?: snake(c.qname.name))
            val on = inner.zip(outerCols).map { (i, o) -> SqlExpr.Bin("=", SqlExpr.Col(alias, i.name), o) }
            deepest(outerCols, scope.select).joins += Join(true, Source.Derived(d, alias), SqlExpr.and(on)!!)
            return SqlExpr.IsNull(SqlExpr.Col(alias, inner.first().name), !e.negated)
        }

        /** A derived table's correlation: its own key columns, and the outer columns they meet. */
        private fun derivedKeys(
            r: Row,
            c: Concept,
            involving: List<Participant>,
            scope: Scope,
        ): Pair<List<SqlExpr.Col>, List<SqlExpr>> {
            val pairs =
                involving.flatMap { p ->
                    correlate(r, c, p.label, value(p.path, scope)).first.map { it as SqlExpr.Bin }
                }
            if (pairs.isEmpty()) refuse("`${c.ref}` ranged over with no participant — every row would count")
            return pairs.map { it.l as SqlExpr.Col } to pairs.map { it.r }
        }

        // ---- aggregates ---------------------------------------------------------------------------------------

        /** A `max` / `min` over a validity interval: `… where period contains <instant>` on the ranged event. */
        private fun lookup(a: ConceptAggregate): Boolean {
            if (a.function != "max" && a.function != "min") return false
            val ranged = a.concepts.singleOrNull() ?: return false
            return a.where?.let(::conjuncts).orEmpty().any { c ->
                c is TemporalPredicate &&
                    c.op == TemporalOp.CONTAINS &&
                    (c.lhs as? ColumnRef)?.let {
                        it.column == "period" &&
                            (it.port == null || it.port == ranged.variable || it.port == ranged.concept)
                    } ==
                    true
            }
        }

        private fun aggregate(
            a: ConceptAggregate,
            scope: Scope,
        ): V {
            if (a.concepts.size != 1) refuse("`${a.function}(…)` over several concepts")
            return when {
                a.function == "latest" -> latest(a, scope)
                lookup(a) -> flat(a, scope)
                a.function in GROUPED -> grouped(a, scope)
                else -> refuse("`${a.function}(…)`")
            }
        }

        /** The lookup flat (A-CN-41): `LEFT JOIN <E> ON <correlation> AND <where>`, read `<E>.<quality>`. */
        private fun flat(
            a: ConceptAggregate,
            scope: Scope,
            /** The first participant's row, already resolved (a DC-13 receiver, [projection]). */
            bound: V? = null,
        ): V {
            val b = a.concepts.single()
            val c = named(b, scope)
            val entity = entityOf(c) ?: unreachable(c)
            val r = row(entity, scope.select, fresh(b.variable ?: entity.qname.name))
            val on =
                a.involving
                    .flatMapIndexed { i, p ->
                        correlate(r, c, p.label, if (i == 0 && bound != null) bound else value(p.path, scope)).first
                    }.toMutableList()
            val inner =
                Scope(
                    scope,
                    listOf(Ranged(c, b.variable, Bound(r, c))),
                    Bound(r, c),
                    scope.self,
                    scope.owner,
                    scope.select,
                )
            a.where?.let { on += predicateOf(it, inner) }
            val attach = deepest(on, scope.select)
            r.select = attach
            attach.joins += Join(true, Source.Entity(entity.qname.name, r.alias), SqlExpr.and(on)!!)
            return walkFrom(Bound(r, c), a.member, 0).first
        }

        /** DC-9 (A-CN-17): the latest row per correlation key — a `ROW_NUMBER` window in a LEFT JOIN, `rn = 1`. */
        private fun latest(
            a: ConceptAggregate,
            scope: Scope,
        ): V {
            val b = a.concepts.single()
            val c = named(b, scope)
            val entity = entityOf(c) ?: unreachable(c)
            val by = a.namedArgs.firstOrNull { it.first == "by" }?.second ?: refuse("`latest(…)` with no `by:`")
            return attempt({
                val d = Select(Source.Entity(entity.qname.name, fresh(entity.qname.name)), scope.select.depth + 1)
                val r = row(entity, d, d.from.alias)
                val sealed =
                    Scope(
                        null,
                        listOf(Ranged(c, b.variable, Bound(r, c))),
                        Bound(r, c),
                        null,
                        scope.owner,
                        d,
                        sealed = true,
                    )
                a.where?.let { d.where += predicateOf(it, sealed) }
                val order = value(by, sealed) as? Scalar ?: refuse("`latest(…, by:)` orders by no value")
                if (options.asOf != null || statementRead) {
                    if (order.moment?.granularity == null) {
                        refuse(
                            "`latest(…, by: ${spelled(
                                by,
                            )})` bounded by an as-of — `${spelled(by)}` is no date (an sk joins the calendar)",
                        )
                    }
                    d.where += SqlExpr.Bin("<=", order.sql, SqlExpr.Param("as_of"))
                }
                val m =
                    member(a, Bound(r, c)) as? SqlExpr.Col
                        ?: refuse("`latest(…).${a.member.joinToString(".")}` is no column of `${entity.qname.name}`")
                val (inner, outerCols) = derivedKeys(r, c, a.involving, scope)
                d.items += inner.map { Item(it) } + Item(m) + Item(SqlExpr.RowNumber(inner, order.sql), "rn")
                val alias = fresh(qualities.lastOrNull() ?: "latest_${snake(c.qname.name)}")
                val on =
                    inner.zip(outerCols).map { (i, o) -> SqlExpr.Bin("=", SqlExpr.Col(alias, i.name), o) } +
                        SqlExpr.Bin("=", SqlExpr.Col(alias, "rn"), SqlExpr.Lit("1"))
                deepest(outerCols, scope.select).joins += Join(true, Source.Derived(d, alias), SqlExpr.and(on)!!)
                Scalar(
                    SqlExpr.Col(alias, m.name),
                    CnDefinitionLints.ofType(attribute(entity, m.name)?.type)?.let { Moment(it) },
                ) as V
            }, {
                refuse(
                    "`latest(…)` whose `where` reads outside the ranged rows — a correlated scalar sub-query",
                    "G-ER-3",
                )
            })
        }

        /** A count / sum / … per correlation key: a grouped derived table in a LEFT JOIN (a count of none reads 0). */
        private fun grouped(
            a: ConceptAggregate,
            scope: Scope,
        ): V {
            val b = a.concepts.single()
            val c = named(b, scope)
            val entity = entityOf(c) ?: unreachable(c)
            return attempt({
                val d = Select(Source.Entity(entity.qname.name, fresh(entity.qname.name)), scope.select.depth + 1)
                val r = row(entity, d, d.from.alias)
                val sealed =
                    Scope(
                        null,
                        listOf(Ranged(c, b.variable, Bound(r, c))),
                        Bound(r, c),
                        null,
                        scope.owner,
                        d,
                        sealed = true,
                    )
                a.where?.let { d.where += predicateOf(it, sealed) }
                val (inner, outerCols) = derivedKeys(r, c, a.involving, scope)
                val name = if (a.function == "count") "n" else (member(a, Bound(r, c)) as? SqlExpr.Col)?.name ?: "v"
                d.items += inner.map { Item(it) } + Item(aggregateCall(a, Bound(r, c)), name)
                d.groupBy += inner
                val alias = fresh(qualities.lastOrNull() ?: "${a.function}_${snake(c.qname.name)}")
                val on = inner.zip(outerCols).map { (i, o) -> SqlExpr.Bin("=", SqlExpr.Col(alias, i.name), o) }
                deepest(outerCols, scope.select).joins += Join(true, Source.Derived(d, alias), SqlExpr.and(on)!!)
                val read: SqlExpr = SqlExpr.Col(alias, name)
                Scalar(if (a.function == "count") SqlExpr.Fn("COALESCE", listOf(read, SqlExpr.Lit("0"))) else read) as V
            }, {
                refuse(
                    "`${a.function}(…)` whose `where` reads outside the ranged rows — a correlated scalar sub-query",
                    "G-ER-3",
                )
            })
        }

        // ---- closure ------------------------------------------------------------------------------------------

        /** DC-1 / DC-8 (A-CN-13): `x IN (SELECT <descendant> FROM <closure> WHERE <ancestor> = '<code>')`. */
        private fun closure(
            e: InCall,
            scope: Scope,
        ): SqlExpr {
            val call = e.call as? FunctionCall
            if (call == null || call.function.name != "descendants") refuse("`in` over anything but `descendants(…)`")
            val kindRef =
                call.args.getOrNull(0) as? ColumnRef
                    ?: refuse("`descendants` is written `descendants(<Kind>, '<code>')`")
            val code =
                ((call.args.getOrNull(1) as? Literal)?.value as? LiteralValue.Str)?.value
                    ?: refuse("`descendants` is written `descendants(<Kind>, '<code>')`")
            val kind =
                resolver.named(kindRef.column, scope.owner.qname.`package`) ?: refuse("no concept `${kindRef.column}`")
            val h = kind.grounding?.hierarchy ?: refuse("`${kind.ref}`'s grounding has no `hierarchy:`")
            val closure = entityOf(h.closure) ?: refuse("`${kind.ref}`'s closure `${h.closure}` is no entity")
            val ancestor =
                h.ancestor?.substringAfterLast('.') ?: refuse("`${kind.ref}`'s hierarchy names no `ancestor:`")
            val descendant =
                h.descendant?.substringAfterLast('.') ?: refuse("`${kind.ref}`'s hierarchy names no `descendant:`")
            val lhs = scalar(value(e.expr, scope))
            val alias = fresh(closure.qname.name)
            val sub = Select(Source.Entity(closure.qname.name, alias), scope.select.depth + 1)
            row(closure, sub, alias)
            sub.items += Item(SqlExpr.Col(alias, descendant))
            val anc = SqlExpr.Col(alias, ancestor)
            sub.where +=
                if (h.ranges == HierarchyRanges.LEXICAL && '-' in code) {
                    SqlExpr.Between(
                        anc,
                        SqlExpr.Lit(quote(code.substringBefore('-'))),
                        SqlExpr.Lit(quote(code.substringAfter('-'))),
                    )
                } else {
                    SqlExpr.Bin("=", anc, SqlExpr.Lit(quote(code)))
                }
            return SqlExpr.InSelect(lhs, sub, e.negated)
        }

        // ---- time ---------------------------------------------------------------------------------------------

        /** A bound the consumer derives (A-CN-22): one name per derivation (`window_lo`, `window_lo_2`, …). */
        private fun derive(
            base: String,
            derivation: String,
        ): SqlExpr.Param {
            derived.entries
                .firstOrNull {
                    it.key.startsWith(
                        base,
                    ) &&
                        it.value == derivation
                }?.let { return SqlExpr.Param(it.key) }
            var name = base
            var i = 2
            while (name in derived) name = "${base}_${i++}"
            derived[name] = derivation
            slot?.let { paramSlots.putIfAbsent(name, it) }
            return SqlExpr.Param(name)
        }

        private fun duration(d: Expression): String =
            when (d) {
                is DurationLiteral -> "${d.unit.spelling}(${d.amount})"
                is DurationParam -> "${d.unit.spelling}(:${d.param})"
                else -> refuse("a window whose `last:` is no duration")
            }

        /** DC-3: `within(x, last: d)` → `x >= {window_lo} AND x <= {as_of}`; `within(x, from: a, to: b)` → `x >= a AND x <= b`. */
        private fun window(
            e: FunctionCall,
            scope: Scope,
        ): SqlExpr {
            val names = e.argNames
            val x = value(e.args.getOrNull(names.indexOf(null)) ?: refuse("`within` with no instant"), scope)

            fun named(n: String): Expression? = if (n in names) e.args[names.indexOf(n)] else null
            named("last")?.let { last ->
                val lo = derive("window_lo", "as_of - ${duration(last)}")
                val asOf = Scalar(SqlExpr.Param("as_of"), Moment(Granularity.DAY, asOf = true))
                return SqlExpr.and(listOf(SqlExpr.Bin(">=", scalar(x), lo), compare("<=", x, asOf, scope)))!!
            }
            val parts = mutableListOf<SqlExpr>()
            named("from")?.let { parts += compare(">=", x, value(it, scope), scope) }
            named("to")?.let { parts += compare("<=", x, value(it, scope), scope) }
            return SqlExpr.and(parts) ?: refuse("`within` with no bound")
        }

        private fun instant(
            e: Expression,
            scope: Scope,
        ): Scalar =
            when (val v = value(e, scope)) {
                is Scalar -> v
                // `e.period before t` reads the period's start (its `at` for an instant) — the occurrence
                is PeriodV -> {
                    val p = periodOf(v.owner) ?: refuse("`${v.owner.ref}` has no `period:`")
                    if (p.at != null) {
                        periodBound(v.ref, v.owner, "at", p.at!!)
                    } else {
                        periodBound(
                            v.ref,
                            v.owner,
                            "start",
                            p.start ?: refuse("`${v.owner.ref}`'s period has no start"),
                        )
                    }
                }
                is RowV -> refuse("`${spelled(e)}` is a row, not an instant")
            }

        /** `:as_of`'s upper end — a derived bound (S0.3 §4); a column's needs interval arithmetic (G-ER-1). */
        private fun upper(t: Scalar): SqlExpr =
            if (t.moment?.asOf ==
                true
            ) {
                derive("as_of_hi", "as_of + days(1)")
            } else {
                refuse("the upper end of an instant column", "G-ER-1")
            }

        /** DC-12 (A-CN-14, S0.3 §4): the words as comparisons, three-valued per the definition's `imprecision:`. */
        private fun temporal(
            e: TemporalPredicate,
            scope: Scope,
        ): SqlExpr {
            val possibly = scope.owner.imprecision == Imprecision.POSSIBLY
            return when (e.op) {
                TemporalOp.AT_OR_BEFORE, TemporalOp.BEFORE -> {
                    val strict = e.op == TemporalOp.BEFORE
                    val x = instant(e.lhs, scope)
                    val t = instant(e.rhs, scope)
                    val xm = x.moment
                    val tm = t.moment
                    val gx = xm?.granularity
                    val gt = tm?.granularity
                    val plain = SqlExpr.Bin(if (strict) "<" else "<=", x.sql, t.sql)
                    val hi = xm?.hi
                    when {
                        xm == null || tm == null || gx == null || gt == null || gx == gt -> plain
                        gx > gt && possibly -> if (strict) plain else SqlExpr.Bin("<", x.sql, upper(t))
                        gx > gt && hi != null -> SqlExpr.Bin("<=", hi, if (strict) t.sql else upper(t))
                        else -> {
                            advise(e.op.spelling, xm, tm, scope)
                            plain
                        }
                    }
                }
                TemporalOp.CONTAINS -> {
                    val p = period(e.lhs, scope)
                    val t = instant(e.rhs, scope)
                    if (p.at != null) refuse("`contains` on an instantaneous period")
                    p.start?.let { s -> s.moment?.let { m -> t.moment?.let { advise("contains", m, it, scope) } } }
                    SqlExpr.and(
                        listOfNotNull(
                            p.start?.let {
                                SqlExpr.Bin("<=", it.sql, t.sql)
                            },
                            p.end?.let { endArm(it.sql, ">=", t.sql, p.state, possibly) },
                        ),
                    )
                        ?: refuse("`contains` on a period with no bounds")
                }
                TemporalOp.ENDED_BEFORE -> {
                    val p = period(e.lhs, scope)
                    val t = instant(e.rhs, scope)
                    val end = p.end?.sql ?: refuse("`ended_before` on a period with no end")
                    val before = SqlExpr.Bin("<", end, t.sql)
                    when {
                        p.state == EndState.OPEN || (p.state == EndState.UNKNOWN && !possibly) ->
                            SqlExpr.and(listOf(SqlExpr.IsNull(end, true), before))!!
                        p.state == EndState.UNKNOWN -> SqlExpr.or(listOf(SqlExpr.IsNull(end, false), before))!!
                        else -> before
                    }
                }
                TemporalOp.OVERLAPS -> {
                    val a = period(e.lhs, scope)
                    val b = period(e.rhs, scope)
                    val aStart = (a.start ?: a.at)?.sql ?: refuse("`overlaps` on a period with no start")
                    val bStart = (b.start ?: b.at)?.sql ?: refuse("`overlaps` on a period with no start")
                    SqlExpr.and(
                        listOfNotNull(
                            (b.end ?: b.at)?.let {
                                endArm(
                                    it.sql,
                                    ">=",
                                    aStart,
                                    if (b.at !=
                                        null
                                    ) {
                                        EndState.PRESENT
                                    } else {
                                        b.state
                                    },
                                    possibly,
                                )
                            },
                            (a.end ?: a.at)?.let {
                                endArm(
                                    it.sql,
                                    ">=",
                                    bStart,
                                    if (a.at !=
                                        null
                                    ) {
                                        EndState.PRESENT
                                    } else {
                                        a.state
                                    },
                                    possibly,
                                )
                            },
                        ),
                    ) ?: refuse("`overlaps` on periods with no end")
                }
            }
        }

        /** `end <op> t`, with the NULL arm `end_open:` asks for (A-CN-19): `open` always, `unknown` when possibly. */
        private fun endArm(
            end: SqlExpr,
            op: String,
            t: SqlExpr,
            state: EndState?,
            possibly: Boolean,
        ): SqlExpr {
            val cmp = SqlExpr.Bin(op, end, t)
            val arm = state == EndState.OPEN || (state == EndState.UNKNOWN && possibly)
            return if (arm) SqlExpr.or(listOf(SqlExpr.IsNull(end, false), cmp))!! else cmp
        }

        private inner class Period(
            val start: Scalar?,
            val end: Scalar?,
            val at: Scalar?,
            val state: EndState?,
        )

        private fun period(
            e: Expression,
            scope: Scope,
        ): Period {
            val v = value(e, scope) as? PeriodV ?: refuse("`${spelled(e)}` is no period")
            val p = periodOf(v.owner) ?: refuse("`${v.owner.ref}` has no `period:`")
            return Period(
                p.start?.let { periodBound(v.ref, v.owner, "start", it) },
                p.end?.let { periodBound(v.ref, v.owner, "end", it) },
                p.at?.let { periodBound(v.ref, v.owner, "at", it) },
                p.endState,
            )
        }
    }

    private companion object {
        const val UNREACHABLE = "cn/definition-unreachable(er)"

        val COMPARISONS =
            mapOf(
                "op.eq" to "=",
                "op.neq" to "<>",
                "op.lt" to "<",
                "op.lte" to "<=",
                "op.gt" to ">",
                "op.gte" to ">=",
            )

        /** `a <op> b` read as `b <flipped> a`. */
        val FLIPPED = mapOf("=" to "=", "<>" to "<>", "<" to ">", "<=" to ">=", ">" to "<", ">=" to "<=")

        val GROUPED = setOf("count", "sum", "avg", "min", "max")

        /** The catalogue's own dotted ids; any other dotted call is a receiver path (DC-13). */
        val CATALOG_PREFIXES = listOf("op.", "fn.", "agg.")

        /** A full timestamp literal's text — never a shorter one (⚑R152-f). */
        val TIMESTAMP: java.time.format.DateTimeFormatter =
            java.time.format.DateTimeFormatter.ofPattern(
                "yyyy-MM-dd HH:mm:ss",
            )

        fun quote(s: String): String = "'" + s.replace("'", "''") + "'"

        fun literal(v: LiteralValue): String =
            when (v) {
                is LiteralValue.Str -> quote(v.value)
                is LiteralValue.Num -> v.raw
                is LiteralValue.Bool -> if (v.value) "TRUE" else "FALSE"
                LiteralValue.Null -> "NULL"
            }

        fun spelled(e: Expression): String =
            when (e) {
                is ThisRef -> (listOf("this") + e.path).joinToString(".")
                is ColumnRef -> listOfNotNull(e.port, e.column).joinToString(".")
                else -> e::class.simpleName ?: "?"
            }
    }
}
