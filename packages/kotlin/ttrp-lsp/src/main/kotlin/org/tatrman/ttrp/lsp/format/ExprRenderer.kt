// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.lsp.format

import org.tatrman.ttrp.expr.AggregateCall
import org.tatrman.ttrp.expr.CaseWhen
import org.tatrman.ttrp.expr.Cast
import org.tatrman.ttrp.expr.CatalogId
import org.tatrman.ttrp.expr.ColumnRef
import org.tatrman.ttrp.expr.Expression
import org.tatrman.ttrp.expr.FunctionCall
import org.tatrman.ttrp.expr.InList
import org.tatrman.ttrp.expr.IsNull
import org.tatrman.ttrp.expr.Literal
import org.tatrman.ttrp.expr.LiteralValue
import org.tatrman.ttrp.expr.MdPath
import org.tatrman.ttrp.expr.MdPathAtom
import org.tatrman.ttrp.expr.MdPathComponent

/**
 * Renders the one PL expression IR back to canonical TTR-P surface text (C3-a). Operators
 * are catalogue ids over [FunctionCall]; this maps them to infix/prefix spellings with
 * precedence-minimal parentheses. The formatter never *rewrites* an expression (S9
 * `==`→`=` is a diagnostic, not a format fix) — it only re-lays-out; so the canonical
 * spelling here matches what the parser accepted.
 */
object ExprRenderer {
    private val infix =
        mapOf(
            CatalogId.OR.value to "or",
            CatalogId.AND.value to "and",
            CatalogId.EQ.value to "=",
            CatalogId.NEQ.value to "<>", // TTR-P's inequality — `!=` does not lex (was rendered `!=`, a re-parse error)
            CatalogId.LT.value to "<",
            CatalogId.LTE.value to "<=",
            CatalogId.GT.value to ">",
            CatalogId.GTE.value to ">=",
            CatalogId.ADD.value to "+",
            CatalogId.SUB.value to "-",
            CatalogId.MUL.value to "*",
            CatalogId.DIV.value to "/",
        )

    fun render(expr: Expression): String = render(expr, 0)

    private fun render(
        expr: Expression,
        parentPrec: Int,
    ): String {
        val prec = precedence(expr)
        val text = raw(expr)
        return if (prec < parentPrec) "($text)" else text
    }

    private fun raw(expr: Expression): String =
        when (expr) {
            is ColumnRef -> if (expr.port != null) "${expr.port}.${expr.column}" else expr.column
            is Literal -> literal(expr.value)
            is IsNull -> "${render(expr.expr, precedence(expr) + 1)} is ${if (expr.negated) "not " else ""}null"
            is InList -> {
                val items = expr.items.joinToString(", ") { render(it, 0) }
                "${render(expr.expr, precedence(expr) + 1)} ${if (expr.negated) "not in" else "in"} ($items)"
            }
            is Cast -> "cast(${render(expr.expr, 0)} as ${expr.target})"
            is CaseWhen -> caseWhen(expr)
            is AggregateCall -> {
                val d = if (expr.distinct) "distinct " else ""
                "${expr.function.name}($d${expr.args.joinToString(", ") { render(it, 0) }})"
            }
            is FunctionCall -> functionCall(expr)
            is MdPath -> expr.components.joinToString(".") { mdPathComponent(it) }
            // AU-core-P0: the recipe's `:name` binding and the duration literal (contracts §1).
            is org.tatrman.ttrp.expr.ParamRef -> ":${expr.name}"
            is org.tatrman.ttrp.expr.DurationLiteral -> "${expr.amount} ${expr.unit.spelling}"
            is org.tatrman.ttrp.expr.DurationParam -> "${expr.unit.spelling}(:${expr.param})"
            // TTRP 0.5 (CN-D29): the conceptual-model forms — refused in a program (TTRP-CN-001), laid out as written.
            is org.tatrman.ttrp.expr.ExistsExpr ->
                (if (expr.negated) "not exists " else "exists ") +
                    conceptRange(expr.concepts, expr.involving, expr.where)
            is org.tatrman.ttrp.expr.ConceptAggregate -> {
                val named = expr.namedArgs.joinToString("") { (n, v) -> ", $n: ${render(v, 0)}" }
                val member = expr.member.joinToString("") { ".$it" }
                "${expr.function}(${conceptRange(expr.concepts, expr.involving, expr.where)}$named)$member"
            }
            is org.tatrman.ttrp.expr.ThisRef -> (listOf("this") + expr.path).joinToString(".")
            is org.tatrman.ttrp.expr.InCall ->
                "${render(
                    expr.expr,
                    precedence(expr) + 1,
                )} ${if (expr.negated) "not in" else "in"} ${render(expr.call, 0)}"
            is org.tatrman.ttrp.expr.TemporalPredicate ->
                "${render(
                    expr.lhs,
                    precedence(expr) + 1,
                )} ${expr.op.spelling} ${render(expr.rhs, precedence(expr) + 1)}"
        }

    /** `C v, C2 involving this as customer, this.item where …` — the range of an `exists` or a concept aggregate. */
    private fun conceptRange(
        concepts: List<org.tatrman.ttrp.expr.ConceptBinding>,
        involving: List<org.tatrman.ttrp.expr.Participant>,
        where: Expression?,
    ): String {
        val cs = concepts.joinToString(", ") { c -> c.variable?.let { "${c.concept} $it" } ?: c.concept }
        val ps = involving.joinToString(", ") { p -> render(p.path, 0) + (p.label?.let { " as $it" } ?: "") }
        return "$cs involving $ps" + (where?.let { " where ${render(it, 0)}" } ?: "")
    }

    private fun mdPathComponent(c: MdPathComponent): String =
        when (c) {
            is MdPathComponent.Name -> c.text
            is MdPathComponent.IntLit -> c.text
            is MdPathComponent.StrLit -> "\"${c.text}\""
            is MdPathComponent.Star -> "*"
            is MdPathComponent.MemberSet -> "{${c.atoms.joinToString(", ") { mdPathAtom(it) }}}"
            is MdPathComponent.Range -> "${mdPathAtom(c.lo)}..${mdPathAtom(c.hi)}"
        }

    private fun mdPathAtom(a: MdPathAtom): String =
        when (a) {
            is MdPathAtom.Name -> a.text
            is MdPathAtom.IntLit -> a.text
            is MdPathAtom.StrLit -> "\"${a.text}\""
        }

    private fun functionCall(fc: FunctionCall): String {
        val op = infix[fc.function.value]
        if (op != null && fc.args.size == 2) {
            val p = precedence(fc)
            // Left-associative: left child at p, right child at p+1 (forces parens on same-prec right nesting).
            return "${render(fc.args[0], p)} $op ${render(fc.args[1], p + 1)}"
        }
        if (fc.function == CatalogId.NOT && fc.args.size == 1) {
            return "not ${render(fc.args[0], precedence(fc))}"
        }
        if (fc.function == CatalogId.NEG && fc.args.size == 1) {
            return "-${render(fc.args[0], precedence(fc))}"
        }
        // Ordinary function — named arguments keep their names (`age(since: x)`).
        val args =
            fc.args.mapIndexed { i, a ->
                val name = fc.argNames.getOrNull(i)
                if (name != null) "$name: ${render(a, 0)}" else render(a, 0)
            }
        return "${fc.function.name}(${args.joinToString(", ")})"
    }

    private fun caseWhen(cw: CaseWhen): String =
        buildString {
            append("case")
            for ((cond, result) in cw.branches) append(" when ${render(cond, 0)} then ${render(result, 0)}")
            cw.elseExpr?.let { append(" else ${render(it, 0)}") }
            append(" end")
        }

    private fun literal(v: LiteralValue): String =
        when (v) {
            is LiteralValue.Str -> "\"${v.value}\""
            is LiteralValue.Num -> v.raw
            is LiteralValue.Bool -> v.value.toString()
            is LiteralValue.Null -> "null"
        }

    private fun precedence(expr: Expression): Int =
        when (expr) {
            is FunctionCall ->
                when (expr.function) {
                    CatalogId.OR -> 1
                    CatalogId.AND -> 2
                    CatalogId.NOT -> 3
                    CatalogId.EQ, CatalogId.NEQ, CatalogId.LT, CatalogId.LTE, CatalogId.GT, CatalogId.GTE -> 4
                    CatalogId.ADD, CatalogId.SUB -> 5
                    CatalogId.MUL, CatalogId.DIV -> 6
                    CatalogId.NEG -> 7
                    else -> 8 // ordinary function call binds tightest
                }
            is IsNull, is InList -> 4
            // `where` is greedy and a participant list is comma-separated: an `exists` keeps its own parentheses
            // under any operator or argument list.
            is org.tatrman.ttrp.expr.ExistsExpr -> 0
            is org.tatrman.ttrp.expr.InCall, is org.tatrman.ttrp.expr.TemporalPredicate -> 4
            is CaseWhen -> 8
            else -> 8
        }
}
