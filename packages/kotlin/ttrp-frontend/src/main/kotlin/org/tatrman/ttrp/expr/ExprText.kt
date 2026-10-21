// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.expr

/**
 * An expression back in TTR-P's own spelling (AU-core-P2) — what manifest v3 records of a wiring's values and its
 * `when:` (`way = "refund_after_return"`, `hold { … }`'s arguments), for the runner and the Studio to read. One
 * reading, not the authored text: operators spaced, strings double-quoted, a sub-expression parenthesised.
 */
object ExprText {
    private val INFIX =
        mapOf(
            "op.or" to "or",
            "op.and" to "and",
            "op.eq" to "=",
            "op.neq" to "<>",
            "op.lt" to "<",
            "op.lte" to "<=",
            "op.gt" to ">",
            "op.gte" to ">=",
            "op.add" to "+",
            "op.sub" to "-",
            "op.mul" to "*",
            "op.div" to "/",
            "op.concat" to "||",
        )

    fun render(e: Expression): String =
        when (e) {
            is ColumnRef -> (e.port?.let { "$it." } ?: "") + e.column
            is Literal -> literal(e.value)
            is ParamRef -> ":${e.name}"
            is DurationLiteral -> "${e.amount} ${e.unit.spelling}"
            is DurationParam -> "${e.unit.spelling}(:${e.param})"
            is FunctionCall -> call(e)
            is AggregateCall -> "${e.function.name}(${if (e.distinct) "distinct " else ""}${e.args.joinToString(
                ", ",
            ) { render(it) }})"
            is Cast -> "cast(${render(e.expr)} as ${e.target.canonical})"
            is CaseWhen ->
                "case " + e.branches.joinToString(" ") { (w, t) -> "when ${render(w)} then ${render(t)}" } +
                    (e.elseExpr?.let { " else ${render(it)}" } ?: "") + " end"
            is InList -> "${operand(
                e.expr,
            )} ${if (e.negated) "not in" else "in"} (${e.items.joinToString(", ") { render(it) }})"
            is IsNull -> "${operand(e.expr)} is ${if (e.negated) "not " else ""}null"
            is MdPath -> "<md path>"
            // a cn definition form never reaches a manifest: a TTR-P document refuses it (TTRP-CN-001)
            is ConceptForm -> "<cn form>"
        }

    private fun call(e: FunctionCall): String {
        val op = INFIX[e.function.value]
        return when {
            op != null && e.args.size == 2 -> "${operand(e.args[0])} $op ${operand(e.args[1])}"
            e.function.value == "op.not" && e.args.size == 1 -> "not ${operand(e.args[0])}"
            e.function.value == "op.neg" && e.args.size == 1 -> "-${operand(e.args[0])}"
            else -> {
                val args =
                    e.args.mapIndexed { i, a ->
                        (e.argNames.getOrNull(i)?.let { "$it: " } ?: "") + render(a)
                    }
                "${e.function.name}(${args.joinToString(", ")})"
            }
        }
    }

    /** [e] as an operand: parenthesised when it is itself an infix expression. */
    private fun operand(e: Expression): String {
        val text = render(e)
        val infix =
            (e is FunctionCall && (e.function.value in INFIX || e.function.value == "op.not")) ||
                e is InList ||
                e is IsNull
        return if (infix) "($text)" else text
    }

    private fun literal(v: LiteralValue): String =
        when (v) {
            is LiteralValue.Str -> "\"" + v.value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
            is LiteralValue.Num -> v.raw
            is LiteralValue.Bool -> v.value.toString()
            LiteralValue.Null -> "null"
        }
}
