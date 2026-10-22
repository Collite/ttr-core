// SPDX-License-Identifier: Apache-2.0
package org.tatrman.concept

import org.tatrman.ttr.metadata.model.Concept
import org.tatrman.ttr.metadata.model.Imprecision
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
import org.tatrman.ttrp.expr.ParamRef
import org.tatrman.ttrp.expr.Participant
import org.tatrman.ttrp.expr.TemporalPredicate
import org.tatrman.ttrp.expr.ThisRef
import java.time.LocalDate

/**
 * A phase's `definition:` as prose (CN-P3 S3.6 T2, A-CN-53) — a deterministic verbaliser over the parsed 0.5 AST: a
 * concept, quality or kind name reads as its label, `this` as the bearer's; operators stay symbols (`>`, `<=`, `+`);
 * `and` / `or` / `not`, `in`, `exists … involving … where`, the concept aggregates, `within(…)`, `descendants(…)` and
 * the DC-12 words read in the locale; a `:name` reads `<its label or name> (= <the world's value>)` (R-cn-7), `:as_of`
 * `as_of (= <the evaluation day>)`. A definition declaring `imprecision:` says how it reads its instants. A construct the
 * verbaliser lacks — or a block that does not parse — reads as the definition's source text in quotes: never a guess.
 */
internal class DefinitionProse(
    private val model: ResolvedCnModel,
    private val words: ExplainWords,
    private val labels: ExplainLabels,
    private val asOf: LocalDate,
) {
    private class Unsaid : RuntimeException(null, null, false, false)

    private class Said(
        val text: String,
        val precedence: Int,
    )

    /** [phase]'s definition as prose; null when it has none. */
    fun of(phase: Concept): String? {
        val def = model.definitions.of(phase.qname).firstOrNull { it.kind == BlockKind.DEFINITION } ?: return null
        val quoted = "\"" + def.block.text.trim() + "\""
        val e = def.expression ?: return quoted
        val bearer = def.bearer?.let { model.cn.concepts[it] }
        val prose =
            try {
                said(e, bearer).text
            } catch (_: Unsaid) {
                return quoted
            }
        val imprecision = phase.imprecision
        if (imprecision == null || def.reads.comparisons.isEmpty()) return prose
        return "$prose (${words.reading(imprecision == Imprecision.CERTAINLY)})"
    }

    private fun inner(
        e: Expression,
        bearer: Concept?,
        min: Int,
    ): String {
        val s = said(e, bearer)
        return if (s.precedence < min) "(${s.text})" else s.text
    }

    private fun said(
        e: Expression,
        bearer: Concept?,
    ): Said =
        when (e) {
            is FunctionCall -> call(e, bearer)
            is ColumnRef -> Said(names((listOfNotNull(e.port) + e.column).flatMap { it.split('.') }), ATOM)
            is ThisRef ->
                Said(
                    if (e.path.isEmpty()) {
                        bearer?.let {
                            ExplainLabels.lowerFirst(
                                labels.of(it),
                            )
                        } ?: "this"
                    } else {
                        names(e.path)
                    },
                    ATOM,
                )
            is Literal -> Said(literal(e.value), ATOM)
            is ParamRef -> Said(param(e.name), ATOM)
            is DurationLiteral -> Said(words.count(e.amount, e.unit.spelling), ATOM)
            is DurationParam -> {
                val (n, shown) = durationParam(e)
                Said(words.count(n, e.unit.spelling, shown), ATOM)
            }
            is InList -> {
                val items = e.items.joinToString(", ") { said(it, bearer).text }
                Said("${inner(e.expr, bearer, CMP)} ${if (e.negated) words.notInList else words.inList} ($items)", CMP)
            }
            is IsNull ->
                Said(
                    "${inner(e.expr, bearer, CMP)} ${if (e.negated) words.isNotEmpty else words.isEmpty}",
                    CMP,
                )
            is InCall -> {
                val call = e.call as? FunctionCall
                val kind = (call?.args?.getOrNull(0) as? ColumnRef)?.takeIf { it.port == null }?.column
                val code = ((call?.args?.getOrNull(1) as? Literal)?.value as? LiteralValue.Str)?.value
                if (call == null || call.function.name != "descendants" || kind == null || code == null) throw Unsaid()
                val under = if (e.negated) words.notUnder else words.under
                Said("${inner(e.expr, bearer, CMP)} $under ${word(kind)} '$code'", CMP)
            }
            is TemporalPredicate ->
                Said("${inner(e.lhs, bearer, CMP)} ${words.temporal(e.op)} ${inner(e.rhs, bearer, CMP)}", CMP)
            is ExistsExpr -> {
                val head = if (e.negated) words.notExists else words.exists
                Said("$head ${body(e.concepts, e.involving, e.where, bearer)}", CMP)
            }
            is ConceptAggregate -> {
                val fn = words.aggregate(e.function) ?: throw Unsaid()
                val by =
                    e.namedArgs.joinToString("") { (name, v) ->
                        if (name != "by") throw Unsaid()
                        words.byClause(said(v, bearer).text)
                    }
                val member = if (e.member.isEmpty()) "" else words.memberIn(names(e.member))
                Said("$fn $member(${body(e.concepts, e.involving, e.where, bearer)}$by)", ATOM)
            }
            else -> throw Unsaid()
        }

    /** `<concept> involving <participants> where <predicate>` — the ranged concepts by their labels. */
    private fun body(
        concepts: List<ConceptBinding>,
        involving: List<Participant>,
        where: Expression?,
        bearer: Concept?,
    ): String {
        val ranged = concepts.joinToString(", ") { b -> word(b.concept) + (b.variable?.let { " $it" } ?: "") }
        val who =
            involving.joinToString(", ") { p ->
                said(p.path, bearer).text + (p.label?.let { " ${words.asLabel} $it" } ?: "")
            }
        return ranged +
            (if (involving.isEmpty()) "" else words.involvingClause(who)) +
            (where?.let { words.whereClause(said(it, bearer).text) } ?: "")
    }

    private fun call(
        e: FunctionCall,
        bearer: Concept?,
    ): Said {
        val id = e.function.value
        BINARY[id]?.let { (symbol, precedence) ->
            if (e.args.size != 2) throw Unsaid()
            // non-associative: a right operand of the same precedence keeps its parentheses (`a - (b - c)`)
            val right = if (id in ASSOCIATIVE) precedence else precedence + 1
            return Said(
                "${inner(e.args[0], bearer, precedence)} $symbol ${inner(e.args[1], bearer, right)}",
                precedence,
            )
        }
        return when (id) {
            // an `and` / `or` mix is parenthesized, never left to precedence (the SQL layout's rule, A-CN-42)
            "op.and" -> Said(e.args.joinToString(" ${words.and} ") { inner(it, bearer, AND) }, AND)
            "op.or" -> Said(e.args.joinToString(" ${words.or} ") { inner(it, bearer, NOT) }, OR)
            "op.not" -> Said("${words.not} ${inner(e.args.single(), bearer, UNARY)}", NOT)
            "op.neg" -> Said("-${inner(e.args.single(), bearer, UNARY)}", UNARY)
            else -> if (e.function.name == "within") within(e, bearer) else throw Unsaid()
        }
    }

    /** `within(x, last: d)` · `within(x, from: a, to: b)`. */
    private fun within(
        e: FunctionCall,
        bearer: Concept?,
    ): Said {
        val names = e.argNames
        val at = if (names.isEmpty()) 0 else names.indexOf(null)
        val x = inner(e.args.getOrNull(at) ?: throw Unsaid(), bearer, CMP)

        fun named(n: String): Expression? = names.indexOf(n).takeIf { it >= 0 }?.let { e.args[it] }
        named("last")?.let { d ->
            val (n, shown) =
                when (d) {
                    is DurationLiteral -> d.amount to d.amount.toString()
                    is DurationParam -> durationParam(d)
                    else -> throw Unsaid()
                }
            return Said("$x ${words.withinLast(n, (d as DurationExpr).unit.spelling, shown)}", CMP)
        }
        val from = named("from")?.let { "${words.from} ${said(it, bearer).text}" }
        val to = named("to")?.let { "${words.until} ${said(it, bearer).text}" }
        if (from == null && to == null) throw Unsaid()
        return Said(listOfNotNull(x, from, to).joinToString(" "), CMP)
    }

    /** A dotted name: each segment its label (a concept, a quality), `period.start` its word, anything else as is. */
    private fun names(segments: List<String>): String {
        val out = mutableListOf<String>()
        var i = 0
        while (i < segments.size) {
            val s = segments[i]
            val bound =
                if (s == "period") {
                    when (segments.getOrNull(i + 1)) {
                        "start" -> words.start
                        "end" -> words.end
                        "at" -> words.at
                        else -> null
                    }
                } else {
                    null
                }
            when {
                bound != null -> {
                    out += bound
                    i += 2
                }
                s == "period" -> {
                    out += words.period
                    i++
                }
                else -> {
                    out += word(s)
                    i++
                }
            }
        }
        return out.joinToString(" ")
    }

    /** A concept / quality name → its label; a participant label or a variable stays; another name humanized. */
    private fun word(name: String): String =
        labels.named(name)?.let { ExplainLabels.lowerFirst(labels.of(it)) }
            ?: if ('_' in name) ExplainLabels.humanized(name) else name

    private fun literal(v: LiteralValue): String =
        when (v) {
            is LiteralValue.Str -> "'${v.value}'"
            is LiteralValue.Num -> v.raw
            is LiteralValue.Bool -> words.bool(v.value)
            LiteralValue.Null -> words.empty
        }

    /** `:name` → `<label or name> (= <value>)` (R-cn-7); `:as_of` → `as_of (= <day>)`. */
    private fun param(name: String): String {
        if (name == "as_of") return "as_of (= ${words.day(asOf)})"
        return labels.parameter(name) + (labels.parameterValue(name)?.let { " (= $it)" } ?: "")
    }

    /** A `days(:grace)` duration's count (unknown: many) and how its amount is shown. */
    private fun durationParam(d: DurationParam): Pair<Long, String> {
        val v = labels.parameterValue(d.param)
        return (v?.toLongOrNull() ?: MANY) to (labels.parameter(d.param) + (v?.let { " (= $it)" } ?: ""))
    }

    private companion object {
        const val OR = 1
        const val AND = 2
        const val NOT = 3
        const val CMP = 4
        const val ADD = 5
        const val MUL = 6
        const val UNARY = 7
        const val ATOM = 9

        /** A count read in its "many" form when the amount is not known. */
        const val MANY = 5L

        val BINARY: Map<String, Pair<String, Int>> =
            mapOf(
                "op.eq" to ("=" to CMP),
                "op.neq" to ("!=" to CMP),
                "op.lt" to ("<" to CMP),
                "op.lte" to ("<=" to CMP),
                "op.gt" to (">" to CMP),
                "op.gte" to (">=" to CMP),
                "op.add" to ("+" to ADD),
                "op.sub" to ("-" to ADD),
                "op.mul" to ("*" to MUL),
                "op.div" to ("/" to MUL),
            )
        val ASSOCIATIVE = setOf("op.add", "op.mul")
    }
}
