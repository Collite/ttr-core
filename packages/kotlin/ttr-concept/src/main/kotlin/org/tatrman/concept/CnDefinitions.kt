// SPDX-License-Identifier: Apache-2.0
package org.tatrman.concept

import org.tatrman.ttr.metadata.model.AutomationSchema
import org.tatrman.ttr.metadata.model.CnPeriod
import org.tatrman.ttr.metadata.model.CnSchema
import org.tatrman.ttr.metadata.model.Concept
import org.tatrman.ttr.metadata.model.ErSchema
import org.tatrman.ttr.metadata.model.Grounding
import org.tatrman.ttr.metadata.model.Model
import org.tatrman.ttr.metadata.model.QualifiedName
import org.tatrman.ttr.parser.model.EmbeddedBlock
import org.tatrman.ttr.parser.model.SourceLocation
import org.tatrman.ttrp.expr.AggregateCall
import org.tatrman.ttrp.expr.CaseWhen
import org.tatrman.ttrp.expr.Cast
import org.tatrman.ttrp.expr.ColumnRef
import org.tatrman.ttrp.expr.ConceptAggregate
import org.tatrman.ttrp.expr.ConceptBinding
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
import org.tatrman.ttrp.expr.TemporalPredicate
import org.tatrman.ttrp.expr.ThisRef
import org.tatrman.ttrp.parser.TtrpParser
import org.tatrman.ttrp.ast.SourceLocation as ExprLocation

/** Which property of a concept or a binding a definition block is. */
enum class BlockKind(
    val property: String,
) {
    DEFINITION("definition"),
    MEMBERS("members"),
    FILTER("filter"),
}

/** An instant- or interval-valued term a definition compares — what the basis and imprecision lints read (T6). */
sealed interface InstantTerm {
    /** `:as_of` — the evaluation instant (AM-41, A-CN-25). */
    data object AsOf : InstantTerm

    /** `e.period.start` / `.end` / `.at`: [owner]'s period bound, carried by its quality [quality]. */
    data class PeriodBound(
        val owner: QualifiedName,
        val bound: String,
        val quality: String,
    ) : InstantTerm

    /** `e.period` — the interval itself. */
    data class Period(
        val owner: QualifiedName,
    ) : InstantTerm

    /** A quality value: a name in [owner]'s `qualities:`, or the def quality [concept]. */
    data class Quality(
        val owner: QualifiedName,
        val name: String,
        val concept: QualifiedName?,
    ) : InstantTerm
}

/** One comparison a definition makes — a DC-12 word or `< <= > >= = <>` ([op] is its spelling). */
data class InstantComparison(
    val op: String,
    val lhs: InstantTerm?,
    val rhs: InstantTerm?,
    val location: SourceLocation?,
)

/** What a definition reads, for the lints and (P3) the renderers. */
data class DefinitionReads(
    /** The concepts whose grounding a rendering needs: ranged, traversed, quality owners, grounded phases, kinds. */
    val concepts: Set<QualifiedName> = emptySet(),
    /** The defined phases and qualities it names — inlined transitively when rendered (AM-26). */
    val definitions: Set<QualifiedName> = emptySet(),
    /** The `:name` parameters it reads (A-CN-26). */
    val parameters: Set<String> = emptySet(),
    /** Whether it reads `:as_of` (A-CN-25). */
    val asOf: Boolean = false,
    val comparisons: List<InstantComparison> = emptyList(),
)

/**
 * One parsed `definition:` / `members:` / binding `filter:` block (CN-P2 S2.6 T5). [expression] is null when the block
 * does not parse; [bearer] is what `this` is (a phase's or a quality's `of:`, a member-restricted kind's parent).
 */
data class CnDefinition(
    val owner: QualifiedName,
    val kind: BlockKind,
    val block: EmbeddedBlock,
    val bearer: QualifiedName?,
    val expression: Expression?,
    val reads: DefinitionReads = DefinitionReads(),
    val diagnostics: List<Diagnostic> = emptyList(),
)

/**
 * Every definition block of a model, parsed with the TTR-P 0.5 expression grammar (`TtrpParser.parseExpression`,
 * CN-D29) and name-resolved against the typed cn tier. A syntax error lands in the `.ttrm` through the block's source
 * map (code points, a string form's escapes). The names (contracts §1.1, "The 0.5 spellings"):
 *
 *  - `this` is the bearer; a bare name reads the innermost subject first — the concept an `exists` / a concept
 *    aggregate ranges over, then the bearer — and a variable (`e`, `e1`) or a ranged concept's name
 *    (`Transfer.to.department`) names its row;
 *  - on a subject: `period` (+ `.start` / `.end` / `.at`), a participant label (`this.from`), a quality (in its
 *    `qualities:` or a `def quality` of it — through `of:` and `is_a`), a phase of it (a predicate, AM-26);
 *  - `exists` / aggregate concepts are concept names, each participant a concept the ranged one involves — by label
 *    (`as customer`) or, unlabelled, its one participant of that concept;
 *  - `descendants(<Kind>, '<code>')` needs a kind with a `hierarchy:` grounding;
 *  - `:as_of` is the evaluation instant, `:name` a `def parameter`;
 *  - a binding's `filter:` reads its target entity's attributes.
 */
class CnDefinitions(
    val all: List<CnDefinition>,
) {
    fun of(owner: QualifiedName): List<CnDefinition> = all.filter { it.owner == owner }

    val diagnostics: List<Diagnostic> get() = all.flatMap { it.diagnostics }

    companion object {
        /** The block does not parse as a TTR-P 0.5 expression (the message names the `TTRP-*` id). */
        const val SYNTAX = "cn/definition-syntax"

        /** A name the definition's scope does not have: a concept, quality, label, variable, parameter or function. */
        const val UNKNOWN_NAME = "cn/definition-unknown-name"

        /** `descendants(<Kind>, …)` over a kind whose grounding has no `hierarchy:`. */
        const val NO_HIERARCHY = "cn/descendants-no-hierarchy"

        fun parse(model: Model): CnDefinitions {
            val cn = model.schemas["cn"] as? CnSchema ?: return CnDefinitions(emptyList())
            val parameters =
                (model.schemas["automation"] as? AutomationSchema)
                    ?.parameters
                    ?.map { it.qname.name }
                    ?.toSet()
                    .orEmpty()
            val resolver = DefinitionResolver(cn, model.schemas["er"] as? ErSchema, parameters)
            val out = mutableListOf<CnDefinition>()
            for (c in cn.concepts.values.sortedWith(compareBy({ it.qname.`package` }, { it.qname.name }))) {
                c.definition?.let { out += resolver.concept(c, BlockKind.DEFINITION, it) }
                c.members?.let { out += resolver.concept(c, BlockKind.MEMBERS, it) }
            }
            for (g in cn.bindings.values.sortedWith(compareBy({ it.qname.`package` }, { it.qname.name }))) {
                g.filter?.let { out += resolver.filter(g, it) }
            }
            return CnDefinitions(out)
        }
    }
}

/** The definition-name resolver (T5). One per model; [concept] / [filter] per block. */
internal class DefinitionResolver(
    private val cn: CnSchema,
    private val er: ErSchema?,
    private val parameters: Set<String>,
) {
    private val byName: Map<String, List<Concept>> = cn.concepts.values.groupBy { it.qname.name }

    /** A concept by its name as written in package [pkg]: that package's first, else the one of that name. */
    fun named(
        name: String,
        pkg: String,
    ): Concept? = byName[name]?.let { cs -> cs.firstOrNull { it.qname.`package` == pkg } ?: cs.singleOrNull() }

    private fun of(q: QualifiedName?): Concept? = q?.let { cn.concepts[it] }

    /** [c] and what it specializes — a role's or a phase's `of:`, every `is_a` — breadth first. */
    fun chain(c: Concept): List<Concept> {
        val out = linkedMapOf(c.qname to c)
        val queue = ArrayDeque(listOf(c))
        while (queue.isNotEmpty()) {
            val x = queue.removeFirst()
            for (next in listOfNotNull(of(x.of)) + x.isA.mapNotNull { of(it) }) {
                if (out.putIfAbsent(next.qname, next) == null) queue += next
            }
        }
        return out.values.toList()
    }

    /** Whether a [want] slot takes a [got] row: one specializes the other. */
    fun compatible(
        want: Concept,
        got: Concept,
    ): Boolean = chain(got).any { it.qname == want.qname } || chain(want).any { it.qname == got.qname }

    fun concept(
        c: Concept,
        kind: BlockKind,
        block: EmbeddedBlock,
    ): CnDefinition {
        val bearer = if (kind == BlockKind.MEMBERS) of(c.isA.firstOrNull()) else of(c.of)
        val ctx = Ctx(c.ref, c.qname.`package`, bearer, block)
        val parsed =
            ctx.parse() ?: return CnDefinition(c.qname, kind, block, bearer?.qname, null, diagnostics = ctx.diags)
        // A definition with no bearer has no scope (cn/bearer-missing is the model-level finding): parse only.
        if (bearer != null || kind == BlockKind.MEMBERS) ctx.visit(parsed, Scope(null, emptyList(), bearer))
        return CnDefinition(c.qname, kind, block, bearer?.qname, parsed, ctx.reads(), ctx.diags)
    }

    fun filter(
        g: Grounding,
        block: EmbeddedBlock,
    ): CnDefinition {
        val ctx = Ctx("cn.${g.qname.name}", g.qname.`package`, null, block)
        val parsed =
            ctx.parse() ?: return CnDefinition(g.qname, BlockKind.FILTER, block, null, null, diagnostics = ctx.diags)
        val entity = g.target?.let { entityOf(it, g.qname.`package`) }
        // an attribute's qname is `<entity>.<attribute>`
        ctx.filterWalk(parsed, entity?.let { e -> e.attributes.map { it.qname.name.substringAfterLast('.') }.toSet() })
        return CnDefinition(g.qname, BlockKind.FILTER, block, g.concept, parsed, ctx.reads(), ctx.diags)
    }

    /** The er entity of an element ref (`er.entity.<e>[.<a>]`), [pkg]'s first. */
    private fun entityOf(
        ref: String,
        pkg: String,
    ): org.tatrman.ttr.metadata.model.Entity? {
        val segs = ref.split('.')
        if (segs.size < 3 || segs[0] != "er" || segs[1] != "entity") return null
        val all =
            er
                ?.entities
                ?.values
                ?.filter { it.qname.name == segs[2] }
                .orEmpty()
        return all.firstOrNull { it.qname.`package` == pkg } ?: all.singleOrNull()
    }

    // ---- what a name resolves to -----------------------------------------------------------------------------

    private sealed interface Value {
        data class Instance(
            val concept: Concept,
        ) : Value

        data class Quality(
            val owner: Concept,
            val name: String,
            val def: Concept?,
        ) : Value

        data class PeriodBound(
            val owner: Concept,
            val bound: String,
            val quality: String,
        ) : Value

        data class Period(
            val owner: Concept,
        ) : Value

        data class Phase(
            val phase: Concept,
        ) : Value

        data object AsOf : Value

        /** A literal, a call, a comparison — or a name that did not resolve (already reported). */
        data object Opaque : Value
    }

    private sealed interface Walk {
        data class Ok(
            val value: Value,
            val concepts: List<QualifiedName>,
            val definitions: List<QualifiedName>,
        ) : Walk

        data class Fail(
            val message: String,
        ) : Walk
    }

    /** One `exists` / aggregate row: the concept (null when its name is unknown — reported once) and its variable. */
    private class Ranged(
        val concept: Concept?,
        val variable: String?,
    )

    private class Scope(
        val parent: Scope?,
        val ranged: List<Ranged>,
        /** The subject a bare name reads: the one ranged concept, or (top level) the bearer. */
        val implicit: Concept?,
    ) {
        fun outward(): Sequence<Scope> = generateSequence(this) { it.parent }
    }

    private data class QualityHit(
        val owner: Concept,
        val def: Concept?,
    )

    private fun quality(
        s: Concept,
        name: String,
    ): QualityHit? {
        for (c in chain(s)) {
            val def =
                byName[name]?.firstOrNull { it.stereotype == "quality" && it.of == c.qname }
            if (def != null) return QualityHit(c, def)
            if (name in c.qualities) return QualityHit(c, null)
        }
        return null
    }

    private fun participant(
        s: Concept,
        label: String,
    ): Pair<Concept, org.tatrman.ttr.metadata.model.CnParticipant>? {
        for (c in chain(s)) c.involves.firstOrNull { it.label == label }?.let { return c to it }
        return null
    }

    private fun periodOf(s: Concept): Pair<Concept, CnPeriod>? {
        for (c in chain(s)) c.period?.let { return c to it }
        return null
    }

    private fun phaseOn(
        s: Concept,
        name: String,
    ): Concept? {
        val owners = chain(s).map { it.qname }.toSet()
        return byName[name]?.firstOrNull { it.stereotype == "phase" && it.of in owners }
    }

    /** Walks [parts] from [i] on the subject [s]; reads are collected, committed only on success. */
    private fun walk(
        s: Concept,
        parts: List<String>,
        i: Int,
        concepts: MutableList<QualifiedName> = mutableListOf(),
        definitions: MutableList<QualifiedName> = mutableListOf(),
    ): Walk {
        if (i == parts.size) return Walk.Ok(Value.Instance(s), concepts, definitions)
        val p = parts[i]
        val rest = parts.size - i - 1
        if (p == "period") {
            val (owner, period) = periodOf(s) ?: return Walk.Fail("`${s.qname.name}` has no `period:`")
            concepts += owner.qname
            if (rest == 0) return Walk.Ok(Value.Period(owner), concepts, definitions)
            val bound = parts[i + 1]
            val q =
                when (bound) {
                    "start" -> period.start
                    "end" -> period.end
                    "at" -> period.at
                    else -> return Walk.Fail("a period has `start`, `end` and `at` — not `$bound`")
                } ?: return Walk.Fail("`${owner.qname.name}`'s period has no `$bound`")
            if (rest > 1) return Walk.Fail("`period.$bound` is an instant — nothing follows it")
            return Walk.Ok(Value.PeriodBound(owner, bound, q), concepts, definitions)
        }
        participant(s, p)?.let { (owner, pt) ->
            val pc = of(pt.concept) ?: return Walk.Fail("`${owner.qname.name}`'s participant `$p` names no concept")
            concepts += owner.qname
            concepts += pc.qname
            return walk(pc, parts, i + 1, concepts, definitions)
        }
        quality(s, p)?.let { hit ->
            val def = hit.def
            when {
                def == null -> concepts += hit.owner.qname
                def.definition != null -> definitions += def.qname
                else -> concepts += def.qname
            }
            if (rest == 0) return Walk.Ok(Value.Quality(hit.owner, p, def), concepts, definitions)
            val vs = of(def?.valueSpace) ?: return Walk.Fail("`$p` is a value — nothing follows it")
            return walk(vs, parts, i + 1, concepts, definitions)
        }
        phaseOn(s, p)?.let { ph ->
            if (ph.definition != null) definitions += ph.qname else concepts += ph.qname
            if (rest > 0) return Walk.Fail("`$p` is a phase (a predicate) — nothing follows it")
            return Walk.Ok(Value.Phase(ph), concepts, definitions)
        }
        return Walk.Fail("`${s.qname.name}` has no quality, participant, period or phase `$p`")
    }

    // ---- one block -------------------------------------------------------------------------------------------

    private inner class Ctx(
        val subject: String,
        val pkg: String,
        val bearer: Concept?,
        val block: EmbeddedBlock,
    ) {
        val diags = mutableListOf<Diagnostic>()
        private val concepts = linkedSetOf<QualifiedName>()
        private val definitions = linkedSetOf<QualifiedName>()
        private val params = linkedSetOf<String>()
        private var asOf = false
        private val comparisons = mutableListOf<InstantComparison>()

        fun reads() = DefinitionReads(concepts.toSet(), definitions.toSet(), params.toSet(), asOf, comparisons.toList())

        /** A TTR-P span (code points) in the `.ttrm`; an empty one (a syntax error at the block's end) stays empty. */
        fun at(loc: ExprLocation): SourceLocation =
            block.location(
                loc.line - 1,
                loc.column,
                if (loc.endLine == loc.line) maxOf(0, loc.endColumn - loc.column) else 1,
            )

        fun report(
            slug: String,
            message: String,
            loc: ExprLocation,
            severity: Severity = Severity.ERROR,
        ) {
            diags += Diagnostic(slug, severity, message, subject, at(loc))
        }

        /** The block parsed (TTRP 0.5, EOF-anchored); null — with its syntax errors reported — when it does not. */
        fun parse(): Expression? {
            val r = TtrpParser.parseExpression(block.text, block.source.file)
            if (r.diagnostics.isEmpty()) return r.expression
            for (d in r.diagnostics) {
                report(CnDefinitions.SYNTAX, "${d.id.id}: ${d.message}", d.location)
            }
            return null
        }

        private fun read(w: Walk.Ok): Value {
            concepts += w.concepts
            definitions += w.definitions
            return w.value
        }

        /** A dotted name: a variable, a ranged concept's name, else a member of the innermost subject that has it. */
        private fun names(
            parts: List<String>,
            scope: Scope,
            loc: ExprLocation,
        ): Value {
            val first = parts.first()
            scope.outward().flatMap { it.ranged }.firstOrNull { it.variable == first }?.let { r ->
                val c = r.concept ?: return Value.Opaque // its concept is unknown — already reported
                return when (val w = walk(c, parts, 1)) {
                    is Walk.Ok -> read(w)
                    is Walk.Fail -> unknown(w.message, loc)
                }
            }
            scope
                .outward()
                .flatMap { it.ranged }
                .firstOrNull { it.variable == null && it.concept?.qname?.name == first }
                ?.let { r ->
                    return when (val w = walk(r.concept!!, parts, 1)) {
                        is Walk.Ok -> read(w)
                        is Walk.Fail -> unknown(w.message, loc)
                    }
                }
            var firstFailure: String? = null
            for (s in scope.outward()) {
                val subj = s.implicit ?: continue
                when (val w = walk(subj, parts, 0)) {
                    is Walk.Ok -> return read(w)
                    is Walk.Fail -> if (firstFailure == null) firstFailure = w.message
                }
            }
            return unknown(firstFailure ?: "`${parts.joinToString(".")}` names nothing in scope", loc)
        }

        private fun unknown(
            message: String,
            loc: ExprLocation,
        ): Value {
            report(CnDefinitions.UNKNOWN_NAME, message, loc)
            return Value.Opaque
        }

        private fun term(v: Value): InstantTerm? =
            when (v) {
                Value.AsOf -> InstantTerm.AsOf
                is Value.PeriodBound -> InstantTerm.PeriodBound(v.owner.qname, v.bound, v.quality)
                is Value.Period -> InstantTerm.Period(v.owner.qname)
                is Value.Quality -> InstantTerm.Quality(v.owner.qname, v.name, v.def?.qname)
                else -> null
            }

        private fun compare(
            op: String,
            l: Value,
            r: Value,
            loc: ExprLocation,
        ) {
            val lt = term(l)
            val rt = term(r)
            if (lt != null || rt != null) comparisons += InstantComparison(op, lt, rt, at(loc))
        }

        fun visit(
            e: Expression,
            scope: Scope,
        ): Value =
            when (e) {
                is Literal -> Value.Opaque
                is ParamRef -> param(e.name, e.location)
                is DurationLiteral -> Value.Opaque
                is DurationParam -> param(e.param, e.location)
                is ColumnRef -> names((e.port?.split('.') ?: emptyList()) + e.column, scope, e.location)
                is ThisRef ->
                    when (val b = bearer) {
                        null -> unknown("`this` has no bearer here", e.location)
                        else ->
                            when (val w = walk(b, e.path, 0)) {
                                is Walk.Ok -> read(w)
                                is Walk.Fail -> unknown(w.message, e.location)
                            }
                    }
                is FunctionCall -> call(e, scope)
                is AggregateCall -> {
                    e.args.forEach { visit(it, scope) }
                    Value.Opaque
                }
                is Cast -> {
                    visit(e.expr, scope)
                    Value.Opaque
                }
                is CaseWhen -> {
                    e.branches.forEach { (w, t) ->
                        visit(w, scope)
                        visit(t, scope)
                    }
                    e.elseExpr?.let { visit(it, scope) }
                    Value.Opaque
                }
                is InList -> {
                    visit(e.expr, scope)
                    e.items.forEach { visit(it, scope) }
                    Value.Opaque
                }
                is IsNull -> {
                    visit(e.expr, scope)
                    Value.Opaque
                }
                is MdPath -> unknown("an md dot-path is not a definition form — name a quality", e.location)
                is ExistsExpr -> {
                    val inner = range(e.concepts, e.involving, scope)
                    e.where?.let { visit(it, inner) }
                    Value.Opaque
                }
                is ConceptAggregate -> aggregate(e, scope)
                is InCall -> {
                    visit(e.expr, scope)
                    visit(e.call, scope)
                    Value.Opaque
                }
                is TemporalPredicate -> {
                    compare(e.op.spelling, visit(e.lhs, scope), visit(e.rhs, scope), e.location)
                    Value.Opaque
                }
            }

        private fun param(
            name: String,
            loc: ExprLocation,
        ): Value {
            if (name == "as_of") {
                asOf = true
                return Value.AsOf
            }
            params += name
            if (name !in parameters) return unknown("`:$name` reads no `def parameter`", loc)
            return Value.Opaque
        }

        private fun call(
            e: FunctionCall,
            scope: Scope,
        ): Value {
            val id = e.function.value
            if (id.startsWith("op.")) {
                val values = e.args.map { visit(it, scope) }
                COMPARISON_SPELLING[id]?.let { op -> compare(op, values[0], values[1], e.location) }
                return Value.Opaque
            }
            val name = e.function.name
            if (name == "descendants") return descendants(e, scope)
            if (name !in TIME_FUNCTIONS && TtrpParser.catalog.resolve(name).isEmpty()) {
                e.args.forEach { visit(it, scope) }
                return unknown("no function `$name`", e.location)
            }
            val values = e.args.map { visit(it, scope) }
            when (name) {
                "within" -> window(e, values)
                // `age(since: t)` is the days from `t` to `:as_of` (AU 06 A-6: `calc(:as_of - t)`) — it reads `:as_of`.
                "age" -> asOf = true
            }
            return Value.Opaque
        }

        /**
         * A window reads as the comparisons it stands for (AU 06 A-6, the recipe's lowering; contracts §7):
         * `within(x, last: d)` is `x >= :as_of - d and x <= :as_of` — it reads `:as_of`, and `x <= :as_of` is the
         * comparison the basis table knows; `within(x, from: a, to: b)` is `x >= a and x <= b`.
         */
        private fun window(
            e: FunctionCall,
            values: List<Value>,
        ) {
            val names = e.argNames
            val x = values.getOrNull(names.indexOf(null)) ?: return

            fun named(n: String): Value? = values.getOrNull(names.indexOf(n))
            if ("last" in names) {
                asOf = true
                compare(">=", x, Value.Opaque, e.location)
                compare("<=", x, Value.AsOf, e.location)
                return
            }
            named("from")?.let { compare(">=", x, it, e.location) }
            named("to")?.let { compare("<=", x, it, e.location) }
        }

        /** `descendants(<Kind>, '<code>')` (DC-1 / DC-8): a kind with a `hierarchy:` grounding, and a code. */
        private fun descendants(
            e: FunctionCall,
            scope: Scope,
        ): Value {
            val kindRef = e.args.getOrNull(0) as? ColumnRef
            val code = (e.args.getOrNull(1) as? Literal)?.value as? LiteralValue.Str
            if (e.args.size != 2 || kindRef == null || kindRef.port != null || code == null) {
                report(CnDefinitions.SYNTAX, "`descendants` is written `descendants(<Kind>, '<code>')`", e.location)
                return Value.Opaque
            }
            val kind = named(kindRef.column, pkg) ?: return unknown("no concept `${kindRef.column}`", kindRef.location)
            concepts += kind.qname
            if (kind.grounding?.hierarchy == null) {
                report(
                    CnDefinitions.NO_HIERARCHY,
                    "`descendants(${kind.qname.name}, …)` reads a kind's `hierarchy:` grounding — " +
                        "`${kind.qname.name}` has none",
                    kindRef.location,
                )
            }
            return Value.Opaque
        }

        /** The rows an `exists` / a concept aggregate ranges over; each participant checked against them. */
        private fun range(
            bindings: List<ConceptBinding>,
            involving: List<Participant>,
            outer: Scope,
        ): Scope {
            val ranged =
                bindings.map { b ->
                    val c = named(b.concept, pkg)
                    if (c == null) {
                        report(CnDefinitions.UNKNOWN_NAME, "no concept `${b.concept}`", b.location)
                    } else {
                        concepts += c.qname
                    }
                    Ranged(c, b.variable)
                }
            bindings
                .mapNotNull { it.variable }
                .groupBy { it }
                .filterValues { it.size > 1 }
                .keys
                .forEach { v ->
                    report(CnDefinitions.UNKNOWN_NAME, "the variable `$v` is bound twice", bindings.first().location)
                }
            for (pt in involving) {
                val got = (visit(pt.path, outer) as? Value.Instance)?.concept
                if (got == null) {
                    // an unresolved path is already reported; a value is not a participant
                    continue
                }
                for (r in ranged) r.concept?.let { participantOf(it, got, pt) }
            }
            return Scope(outer, ranged, ranged.singleOrNull()?.concept)
        }

        private fun participantOf(
            ranged: Concept,
            got: Concept,
            pt: Participant,
        ) {
            val name = ranged.qname.name
            val label = pt.label
            if (label != null) {
                val (_, part) =
                    participant(ranged, label)
                        ?: return report(CnDefinitions.UNKNOWN_NAME, "`$name` has no participant `$label`", pt.location)
                val want = of(part.concept) ?: return
                if (!compatible(want, got)) {
                    report(
                        CnDefinitions.UNKNOWN_NAME,
                        "`$name`'s participant `$label` is a `${want.qname.name}`, not a `${got.qname.name}`",
                        pt.location,
                    )
                }
                return
            }
            val matches =
                chain(ranged).flatMap { it.involves }.filter { p ->
                    of(p.concept)?.let { compatible(it, got) } ==
                        true
                }
            when {
                matches.isEmpty() ->
                    report(CnDefinitions.UNKNOWN_NAME, "`$name` involves no `${got.qname.name}`", pt.location)
                matches.size > 1 ->
                    report(
                        CnDefinitions.UNKNOWN_NAME,
                        "`$name` involves a `${got.qname.name}` as ${matches.joinToString(
                            " and ",
                        ) { "`${it.label}`" }} " +
                            "— name one with `as`",
                        pt.location,
                    )
            }
        }

        private fun aggregate(
            e: ConceptAggregate,
            scope: Scope,
        ): Value {
            if (e.function !in CONCEPT_AGGREGATES) {
                unknown(
                    "`${e.function}(<concept> involving …)` — a concept aggregate is one of ${CONCEPT_AGGREGATES.joinToString()}",
                    e.location,
                )
            }
            val inner = range(e.concepts, e.involving, scope)
            e.where?.let { visit(it, inner) }
            e.namedArgs.forEach { (_, v) -> visit(v, inner) }
            if (e.member.isEmpty()) return Value.Opaque
            val subject =
                inner.implicit
                    ?: return unknown(
                        "`.${e.member.joinToString(".")}` after an aggregate over several concepts",
                        e.location,
                    )
            return when (val w = walk(subject, e.member, 0)) {
                is Walk.Ok -> read(w)
                is Walk.Fail -> unknown(w.message, e.location)
            }
        }

        /** A binding's `filter:` (A-CN-18): the target entity's attributes, the bindings, plain predicates. */
        fun filterWalk(
            e: Expression,
            attributes: Set<String>?,
        ) {
            when (e) {
                is ColumnRef ->
                    if (attributes != null && e.column !in attributes) {
                        report(
                            CnDefinitions.UNKNOWN_NAME,
                            "the target entity has no attribute `${e.column}`",
                            e.location,
                        )
                    }
                is ParamRef -> param(e.name, e.location)
                is DurationParam -> param(e.param, e.location)
                is FunctionCall -> e.args.forEach { filterWalk(it, attributes) }
                is AggregateCall -> e.args.forEach { filterWalk(it, attributes) }
                is Cast -> filterWalk(e.expr, attributes)
                is CaseWhen -> {
                    e.branches.forEach { (w, t) ->
                        filterWalk(w, attributes)
                        filterWalk(t, attributes)
                    }
                    e.elseExpr?.let { filterWalk(it, attributes) }
                }
                is InList -> (listOf(e.expr) + e.items).forEach { filterWalk(it, attributes) }
                is IsNull -> filterWalk(e.expr, attributes)
                is Literal, is DurationLiteral -> Unit
                is MdPath,
                is ExistsExpr,
                is ConceptAggregate,
                is ThisRef,
                is InCall,
                is TemporalPredicate,
                ->
                    report(
                        CnDefinitions.UNKNOWN_NAME,
                        "a binding's `filter:` selects the target entity's rows — write it over its attributes",
                        e.location,
                    )
            }
        }
    }

    private companion object {
        /** The comparison operators a definition's instant comparisons are read from. */
        val COMPARISON_SPELLING =
            mapOf("op.lt" to "<", "op.lte" to "<=", "op.gt" to ">", "op.gte" to ">=", "op.eq" to "=", "op.neq" to "<>")

        /** TTR-P's time functions a definition may call (named arguments; durations fold before this). */
        val TIME_FUNCTIONS = setOf("within", "age", "working_days")

        val CONCEPT_AGGREGATES = setOf("count", "sum", "avg", "min", "max", "latest")
    }
}
