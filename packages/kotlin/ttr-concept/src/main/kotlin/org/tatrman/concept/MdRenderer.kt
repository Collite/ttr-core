// SPDX-License-Identifier: Apache-2.0
package org.tatrman.concept

import org.tatrman.ttr.md.resolve.PathComponent
import org.tatrman.ttr.metadata.model.AutomationSchema
import org.tatrman.ttr.metadata.model.Concept
import org.tatrman.ttr.metadata.model.ErSchema
import org.tatrman.ttr.metadata.model.QualifiedName
import org.tatrman.ttr.parser.model.AutoValue
import org.tatrman.ttrp.expr.ColumnRef
import org.tatrman.ttrp.expr.ConceptAggregate
import org.tatrman.ttrp.expr.DurationLiteral
import org.tatrman.ttrp.expr.DurationParam
import org.tatrman.ttrp.expr.DurationUnit
import org.tatrman.ttrp.expr.ExistsExpr
import org.tatrman.ttrp.expr.Expression
import org.tatrman.ttrp.expr.FunctionCall
import org.tatrman.ttrp.expr.InCall
import org.tatrman.ttrp.expr.InList
import org.tatrman.ttrp.expr.Literal
import org.tatrman.ttrp.expr.LiteralValue
import org.tatrman.ttrp.expr.ParamRef
import org.tatrman.ttrp.expr.TemporalPredicate
import org.tatrman.ttrp.expr.ThisRef
import java.time.LocalDate
import java.util.IdentityHashMap

/**
 * The md definition renderer (CN-P3 S3.3 T5; contracts §3 `definitionRender(…, MD)` — S3.4 dispatches to it): a
 * phase's `definition:`, every phase it names inlined (transitively, cycle-safe), rendered on a carrying cubelet as what
 * the md path can say —
 *
 *  - **coordinates** where the predicate is a conjunction of DC-5 comparisons on md attributes: `x = v` (pinned),
 *    `x in (…)` (a set), `x >= a and x <= b` · `within(x, from: a, to: b)` · `within(x, last: d)` (an inclusive range —
 *    a window is `x >= :as_of - d and x <= :as_of`, contracts §7);
 *  - **a same-cubelet existence** (⚑CN-P9's default): `exists <E> involving this where <md-renderable>`, `<E>` the
 *    carrier's own grain event and `this` a dimension of its grain — a grain-key semi-join the lowering composes on
 *    `plan.v1` ([SemiJoin]).
 *
 * `:name` reads the world's `def parameter` value (recorded in [Predicate.parameters]); `:as_of` is the evaluation day.
 * Anything else cannot render on md: [Result.Unrenderable] names the construct and, where one is issued, the demand
 * that would let it (`plan/demands/ttrp-catalog-dc-gaps.md`: `G-MD-1` an existence over another event, a negated or
 * nested one · `G-MD-2` a closure · `G-MD-3` a working-day window · `G-MD-4` a sub-day one · `G-MD-6` the DC-12 words ·
 * `G-MD-7` an aggregate over another event).
 */
internal class MdRenderer(
    private val model: ResolvedCnModel,
) {
    private val cn = model.cn

    /** Every `def parameter` → its value as written (null when it has none). */
    private val parameterValues: Map<String, String?> =
        (model.model.schemas["automation"] as? AutomationSchema)
            ?.parameters
            ?.associate { it.qname.name to it.value?.let(::text) }
            .orEmpty()

    private val resolver = DefinitionResolver(cn, model.model.schemas["er"] as? ErSchema, parameterValues.keys)

    /** A same-cubelet existence: the carrier's rows whose [key] attribute is among the keys a second read finds. */
    data class SemiJoin(
        val key: String,
        val coordinates: List<Coord>,
    )

    /** What a phase renders to on a carrier. */
    data class Predicate(
        val coordinates: List<Coord> = emptyList(),
        val semiJoins: List<SemiJoin> = emptyList(),
        /** The md faces the definition reads — a participant, a quality, a ranged event (never its time attribute). */
        val reads: List<MdFace> = emptyList(),
        /** The phases inlined, the rendered one first (`cn.<Name>`). */
        val inlined: List<String> = emptyList(),
        /** `:name` → the world's value. */
        val parameters: Map<String, String> = emptyMap(),
        /** Whether it read `:as_of`. */
        val asOf: Boolean = false,
        /**
         * The member values a placeholder stands for (S3.4, `definitionRender(…, MD)`), by identity: a `:name` or `:as_of`
         * value its name, a window's bounds `window_lo` (derived — [derivations]) and `as_of`.
         */
        val symbols: Map<PathComponent, String> = emptyMap(),
        /** A derived placeholder → how the consumer computes it (A-CN-22: `as_of - days(365)`). */
        val derivations: Map<String, String> = emptyMap(),
    )

    sealed interface Result {
        data class Rendered(
            val predicate: Predicate,
        ) : Result

        /** [construct] cannot render on md; [demand] is the issued demand that would let it, when there is one. */
        data class Unrenderable(
            val construct: String,
            val demand: String? = null,
        ) : Result {
            val message: String get() = construct + (demand?.let { " ($it)" } ?: "")
        }
    }

    /** [phase] rendered on [carrier]; `:as_of` is [asOf]. */
    fun render(
        phase: Concept,
        carrier: Carrier,
        asOf: LocalDate,
    ): Result =
        try {
            Result.Rendered(Ctx(carrier, asOf).phase(phase))
        } catch (s: Stop) {
            s.result
        }

    private class Stop(
        val result: Result.Unrenderable,
    ) : RuntimeException(result.message, null, false, false)

    private fun stop(
        construct: String,
        demand: String? = null,
    ): Nothing = throw Stop(Result.Unrenderable(construct, demand))

    /**
     * Where a name is read: `this` is [bearer]; inside an existence a bare name reads the ranged event first ([ranged],
     * or its [variable]); coordinates go to [out] (the outer read, or the existence's inner read), a pair of one-sided
     * bounds is held in [bounds] until the conjunction ends.
     */
    private class Scope(
        val bearer: Concept,
        val pkg: String,
        val ranged: Concept? = null,
        val variable: String? = null,
        val out: MutableList<Coord> = mutableListOf(),
        val bounds: MutableMap<String, Array<PathComponent?>> = linkedMapOf(),
    )

    private inner class Ctx(
        val carrier: Carrier,
        val asOfDay: LocalDate,
    ) {
        val semiJoins = mutableListOf<SemiJoin>()
        val reads = linkedSetOf<MdFace>()
        val inlined = linkedSetOf<String>()
        val params = linkedMapOf<String, String>()
        var asOf = false
        val inlining = ArrayDeque<QualifiedName>()
        val symbols = IdentityHashMap<PathComponent, String>()
        val derivations = linkedMapOf<String, String>()

        fun phase(p: Concept): Predicate {
            val scope = inline(p, null)
            flush(scope)
            return Predicate(scope.out, semiJoins, reads.toList(), inlined.toList(), params, asOf, symbols, derivations)
        }

        /** [c], remembered as the value of the placeholder [name]. */
        fun symbol(
            c: PathComponent,
            name: String,
        ): PathComponent = c.also { symbols[it] = name }

        /** [p]'s definition conjoined into [into] (a new scope on its bearer when null); the scope it went to. */
        fun inline(
            p: Concept,
            into: Scope?,
        ): Scope {
            if (p.qname in inlining) stop("the definitions inline each other at `${p.ref}` (cn/definition-cycle)")
            val def =
                model.definitions.of(p.qname).firstOrNull { it.kind == BlockKind.DEFINITION }
                    ?: stop("`${p.ref}` has no `definition:` — a grounded phase reads its stacked entity's md face")
            val expression = def.expression ?: stop("`${p.ref}`'s definition does not parse")
            val bearer = def.bearer?.let { cn.concepts[it] } ?: stop("`${p.ref}` has no bearer")
            on(bearer)
            val scope = into ?: Scope(bearer, p.qname.`package`)
            inlined += p.ref
            inlining.addLast(p.qname)
            // an inlined phase on another bearer reads its own `this`, into the same coordinates
            val target =
                if (into == null || into.bearer == bearer) {
                    scope
                } else {
                    Scope(bearer, p.qname.`package`, out = scope.out, bounds = scope.bounds)
                }
            conjoin(expression, target)
            inlining.removeLast()
            return scope
        }

        /** Refuses a bearer that is not on the carrier: an event whose cubelet it is not, a dimension it lacks. */
        fun on(bearer: Concept) {
            val faces = MdFace.of(bearer)
            val here =
                if (bearer.stereotype == "event") {
                    faces.any { (it.element as? MdElement.Cubelet)?.name == carrier.name }
                } else {
                    faces.any { carrier.attributeOf(it) != null }
                }
            if (!here) stop("its bearer `${bearer.ref}` is not on the carrying cubelet `${carrier.name}`", "G-MD-1")
        }

        fun conjoin(
            e: Expression,
            scope: Scope,
        ) {
            when (e) {
                is FunctionCall ->
                    when (e.function.value) {
                        "op.and" -> e.args.forEach { conjoin(it, scope) }
                        "op.eq" -> pin(e.args[0], e.args[1], scope)
                        "op.gte" -> bound(e.args[0], e.args[1], scope, lower = true)
                        "op.lte" -> bound(e.args[0], e.args[1], scope, lower = false)
                        "op.neq" -> {
                            attribute(split(e.args[0], e.args[1]).first, scope)
                            stop("`<>` — md has no complement")
                        }
                        "op.lt", "op.gt" -> {
                            attribute(split(e.args[0], e.args[1]).first, scope)
                            stop("a strict comparison — an md range is inclusive")
                        }
                        "op.or" -> stop("a disjunction — md coordinates are a conjunction")
                        "op.not" -> stop("`not` — md has no complement")
                        else ->
                            when (e.function.name) {
                                "within" -> window(e, scope)
                                "age" -> stop("`age(…)` — period arithmetic", "G-MD-4")
                                else -> stop("`${e.function.name}(…)`")
                            }
                    }
                is InList ->
                    if (e.negated) {
                        stop("`not in` — md has no complement")
                    } else {
                        val atoms = e.items.map { constant(it) ?: stop("an `in` list of non-constants") }
                        scope.out += Coord(attribute(e.expr, scope), PathComponent.SetLit(atoms))
                    }
                is ExistsExpr -> exists(e, scope)
                is ColumnRef, is ThisRef ->
                    when (val v = resolve(e, scope)) {
                        is DefinitionResolver.Value.Phase -> inline(v.phase, scope)
                        else -> stop("`${spelled(e)}` is not a predicate")
                    }
                is TemporalPredicate -> stop("the DC-12 word `${e.op.spelling}`", "G-MD-6")
                is InCall -> stop("a closure membership (`in descendants(…)`)", "G-MD-2")
                is ConceptAggregate -> stop("`${e.function}(…)` over another event", "G-MD-7")
                else -> stop("`${e::class.simpleName}`")
            }
        }

        fun pin(
            a: Expression,
            b: Expression,
            scope: Scope,
        ) {
            val (term, value) = split(a, b)
            scope.out += Coord(attribute(term, scope), value)
        }

        fun bound(
            a: Expression,
            b: Expression,
            scope: Scope,
            lower: Boolean,
        ) {
            // `v <= x` is `x >= v`: the constant's side flips the bound
            val flipped = constant(a) != null
            val (term, value) = split(a, b)
            val slot = scope.bounds.getOrPut(attribute(term, scope)) { arrayOfNulls(2) }
            val i = if (lower != flipped) 0 else 1
            if (slot[i] != null) stop("a second ${if (i == 0) "lower" else "upper"} bound on one attribute")
            slot[i] = value
        }

        /** The one-sided bounds of [scope] paired into ranges; a bound without its other side cannot render. */
        fun flush(scope: Scope) {
            for ((attribute, b) in scope.bounds) {
                val lo = b[0] ?: stop("a one-sided comparison on `$attribute` — an md range needs both bounds")
                val hi = b[1] ?: stop("a one-sided comparison on `$attribute` — an md range needs both bounds")
                scope.out += Coord(attribute, PathComponent.RangeLit(lo, hi))
            }
            scope.bounds.clear()
        }

        /** A comparison's attribute side and its constant side. */
        fun split(
            a: Expression,
            b: Expression,
        ): Pair<Expression, PathComponent> {
            constant(b)?.let { return a to it }
            constant(a)?.let { return b to it }
            stop("a comparison of two terms — md pins an attribute to a constant")
        }

        /** `within(x, last: d)` → `[as_of - d, as_of]`; `within(x, from: a, to: b)` → `[a, b]` — on a date attribute. */
        fun window(
            e: FunctionCall,
            scope: Scope,
        ) {
            val names = e.argNames
            val x = e.args.getOrNull(names.indexOf(null)) ?: stop("`within` with no instant")
            val attribute = attribute(x, scope)
            if (!carrier.isDate(attribute)) stop("a window on `$attribute`, which is no date")

            fun named(n: String): Expression? = e.args.getOrNull(names.indexOf(n)).takeIf { n in names }
            val last = named("last")
            if (last != null) {
                asOf = true
                val lo = back(asOfDay, last)
                val name =
                    derivations.entries.firstOrNull { it.value == "as_of - ${spelled(last)}" }?.key
                        ?: "window_lo${if (derivations.isEmpty()) "" else "_${derivations.size + 1}"}"
                derivations[name] = "as_of - ${spelled(last)}"
                scope.out +=
                    Coord(
                        attribute,
                        PathComponent.RangeLit(
                            symbol(PathComponent.Quoted(lo.toString()), name),
                            symbol(PathComponent.Quoted(asOfDay.toString()), "as_of"),
                        ),
                    )
                return
            }
            val from = named("from")?.let { constant(it) ?: stop("a window bound that is no constant") }
            val to = named("to")?.let { constant(it) ?: stop("a window bound that is no constant") }
            if (from == null || to == null) stop("a window with one bound — an md range needs both")
            scope.out += Coord(attribute, PathComponent.RangeLit(from, to))
        }

        /** [day] less the duration [d] (days, weeks, months); a working-day or a sub-day duration cannot render on md. */
        fun back(
            day: LocalDate,
            d: Expression,
        ): LocalDate {
            val (amount, unit) =
                when (d) {
                    is DurationLiteral -> d.amount to d.unit
                    is DurationParam -> {
                        val v = parameter(d.param)
                        (v.toBigDecimalOrNull()?.toLong() ?: stop("`:${d.param}` is no whole number")) to d.unit
                    }
                    else -> stop("a window whose `last:` is no duration")
                }
            return when (unit) {
                DurationUnit.DAYS -> day.minusDays(amount)
                DurationUnit.WEEKS -> day.minusWeeks(amount)
                DurationUnit.MONTHS -> day.minusMonths(amount)
                DurationUnit.WORKING_DAYS -> stop("a working-day window — md has no working-day calendar", "G-MD-3")
                DurationUnit.MINUTES, DurationUnit.HOURS -> stop("a sub-day window on a day", "G-MD-4")
            }
        }

        /**
         * ⚑CN-P9 — `exists <E> involving this where <W>`, `<E>` the carrier's own grain event: the carrier's rows whose
         * grain key (`this`'s attribute) is among the keys of a second read on the carrier, restricted by `<W>`.
         */
        fun exists(
            e: ExistsExpr,
            scope: Scope,
        ) {
            if (scope.ranged != null) stop("a nested existence", "G-MD-1")
            if (e.negated) stop("`not exists` — an anti-join", "G-MD-1")
            val b = e.concepts.singleOrNull() ?: stop("an existence over several concepts", "G-MD-1")
            val event = resolver.named(b.concept, scope.pkg) ?: stop("no concept `${b.concept}`")
            val face =
                MdFace.of(event).firstOrNull { (it.element as? MdElement.Cubelet)?.name == carrier.name }
                    ?: stop("an existence over `${event.ref}`, which is not the carrying cubelet's event", "G-MD-1")
            val participant = e.involving.singleOrNull()
            if (participant == null || (participant.path as? ThisRef)?.path?.isEmpty() != true) {
                stop("an existence involving anything but `this`", "G-MD-1")
            }
            val key =
                key(scope.bearer) ?: stop("an existence keyed by `${scope.bearer.ref}`, no grain dimension", "G-MD-1")
            val inner = Scope(scope.bearer, scope.pkg, event, b.variable)
            e.where?.let { conjoin(it, inner) }
            flush(inner)
            reads += face
            semiJoins += SemiJoin(key, inner.out.toList())
        }

        /** The grain attribute a kind / role bearer is on the carrier — `this` as a coordinate; null for an event. */
        fun key(bearer: Concept): String? =
            if (bearer.stereotype == "event") {
                null
            } else {
                MdFace.of(bearer).firstNotNullOfOrNull { carrier.attributeOf(it) }
            }

        /** What a name reads, as an md attribute of the carrier. */
        fun attribute(
            e: Expression,
            scope: Scope,
        ): String {
            when (e) {
                is ConceptAggregate -> stop("`${e.function}(…)` over another event's rows", "G-MD-7")
                is FunctionCall -> stop("`${e.function.name}(…)` as a term — md compares an attribute")
                else -> Unit
            }
            return when (val v = resolve(e, scope)) {
                is DefinitionResolver.Value.Instance ->
                    if (v.concept == scope.bearer) {
                        key(v.concept) ?: stop("`this` — the event itself — is no md attribute")
                    } else {
                        face(v.concept, record = true)
                    }
                is DefinitionResolver.Value.Quality -> {
                    val def = v.def ?: stop("`${v.name}` of `${v.owner.ref}` is no quality concept — no md grounding")
                    face(def, record = true)
                }
                is DefinitionResolver.Value.PeriodBound -> {
                    val q =
                        cn.concepts.values.firstOrNull {
                            it.stereotype == "quality" && it.qname.name == v.quality && it.of == v.owner.qname
                        } ?: stop("`${v.owner.ref}`'s `period.${v.bound}` reads `${v.quality}`, no quality concept")
                    face(q, record = false)
                }
                else -> stop("`${spelled(e)}` is no md attribute")
            }
        }

        /** [c]'s attribute on the carrier; a measure in a predicate cannot render (md filters coordinates, not rows). */
        fun face(
            c: Concept,
            record: Boolean,
        ): String {
            val faces = MdFace.of(c)
            if (faces.any { it.element is MdElement.Measure }) {
                stop(
                    "a comparison on the measure `${c.ref}` — a row filter on a measure, which md has no coordinate for",
                )
            }
            val here = faces.filter { carrier.attributeOf(it) != null }
            val f =
                here.singleOrNull()
                    ?: stop(
                        if (here.isEmpty()) {
                            "`${c.ref}` has no md attribute on the carrying cubelet `${carrier.name}`"
                        } else {
                            "`${c.ref}` reaches `${carrier.name}` by ${here.joinToString(
                                " and ",
                            ) { "`${it.element.written}`" }}"
                        },
                    )
            if (record) reads += f
            return carrier.attributeOf(f)!!
        }

        fun resolve(
            e: Expression,
            scope: Scope,
        ): DefinitionResolver.Value? {
            val walk =
                when (e) {
                    is ThisRef -> resolver.walk(scope.bearer, e.path, 0)
                    is ColumnRef -> {
                        val parts = (e.port?.split('.') ?: emptyList()) + e.column
                        val ranged = scope.ranged
                        when {
                            ranged != null && parts.first() == (scope.variable ?: ranged.qname.name) ->
                                resolver.walk(ranged, parts, 1)
                            ranged != null ->
                                (resolver.walk(ranged, parts, 0) as? DefinitionResolver.Walk.Ok)
                                    ?: resolver.walk(scope.bearer, parts, 0)
                            else -> resolver.walk(scope.bearer, parts, 0)
                        }
                    }
                    else -> return null
                }
            return (walk as? DefinitionResolver.Walk.Ok)?.value
        }

        /** A literal or a parameter as a member; null for anything else. */
        fun constant(e: Expression): PathComponent? =
            when (e) {
                is Literal ->
                    when (val v = e.value) {
                        is LiteralValue.Str -> PathComponent.Quoted(v.value)
                        is LiteralValue.Num -> member(v.raw)
                        is LiteralValue.Bool -> PathComponent.Quoted(v.value.toString())
                        LiteralValue.Null -> stop("a comparison with `null`")
                    }
                is ParamRef ->
                    if (e.name == "as_of") {
                        asOf = true
                        symbol(PathComponent.Quoted(asOfDay.toString()), "as_of")
                    } else {
                        symbol(member(parameter(e.name)), e.name)
                    }
                else -> null
            }

        /** `:name`'s value in the world, recorded; a parameter with no value cannot render. */
        fun parameter(name: String): String {
            val v =
                parameterValues[name] ?: stop("`:$name` has no value in the world — a `def parameter` with `value:`")
            params[name] = v
            return v
        }
    }

    private companion object {
        fun text(v: AutoValue): String? =
            when (v) {
                is AutoValue.Str -> v.raw
                is AutoValue.Num -> v.text
                is AutoValue.Bool -> v.raw.toString()
                else -> null
            }

        fun spelled(e: Expression): String =
            when (e) {
                is ThisRef -> (listOf("this") + e.path).joinToString(".")
                is ColumnRef -> listOfNotNull(e.port, e.column).joinToString(".")
                is DurationLiteral -> "${e.unit.spelling}(${e.amount})"
                is DurationParam -> "${e.unit.spelling}(:${e.param})"
                else -> e::class.simpleName ?: "?"
            }
    }
}

/** A member as a dot-path component: digits a number (`month.1`), anything else quoted — the one spelling (R125-10). */
internal fun member(text: String): PathComponent =
    if (text.isNotEmpty() && text.all { it.isDigit() }) PathComponent.IntLit(text) else PathComponent.Quoted(text)
