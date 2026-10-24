// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.dialect.b

import org.tatrman.ttrp.ast.SwitchMode
import org.tatrman.ttrp.ast.SwitchStmt
import org.tatrman.ttrp.expr.ExprText
import org.tatrman.ttrp.expr.Expression
import org.tatrman.ttrp.expr.FunctionCall
import org.tatrman.ttrp.expr.InList
import org.tatrman.ttrp.expr.IsNull

/** Deterministic explanation sentences; identifiers and parameter names keep their canonical spelling. */
object TtrbRecipeRenderer {
    fun decision(
        sw: SwitchStmt,
        lang: String = "en",
    ): String {
        require(lang in setOf("en", "cs"))
        val cs = lang == "cs"
        return buildString {
            // Load of a declared input is a reference in TTR-B, so the same row feeds every route.
            append(if (cs) "Načti " else "Load ").append(sw.input.parts.joinToString(".")).append(".\n")
            sw.routes.forEach { route ->
                append(if (cs) "Rozhodni " else "Decide ").append(route.name)
                append(if (cs) ", když " else " when ").append(expression(route.condition, cs))
                route.because?.let { append(if (cs) ", protože " else ", because ").append(quote(it)) }
                if (route.detail.isNotEmpty()) {
                    append(if (cs) ", s detailem " else ", showing ")
                        .append(route.detail.joinToString(if (cs) " a " else " and ") { it.text })
                }
                append(".\n")
            }
            append(
                if (sw.mode == SwitchMode.OVERLAPPING) {
                    if (cs) "Rozhodni; řádek může projít více cestami." else "Decide; a row may take several routes."
                } else if (cs) {
                    "Rozhoduj výlučně."
                } else {
                    "Decide exclusively."
                },
            )
            sw.elseRoute?.let { other ->
                append('\n')
                append(
                    other.name?.let { if (cs) "Jinak rozhodni $it" else "Otherwise decide $it" }
                        ?: if (cs) "Jinak nic" else "Otherwise, nothing",
                )
                other.because?.let { append(if (cs) ", protože " else ", because ").append(quote(it)) }
                if (other.detail.isNotEmpty()) {
                    append(if (cs) ", s detailem " else ", showing ")
                        .append(other.detail.joinToString(if (cs) " a " else " and ") { it.text })
                }
                append('.')
            }
            append('\n')
        }
    }

    private val operators =
        mapOf(
            "and" to "and",
            "or" to "or",
            "eq" to "=",
            "neq" to "<>",
            "gt" to ">",
            "gte" to ">=",
            "lt" to "<",
            "lte" to "<=",
            "add" to "+",
            "sub" to "-",
            "mul" to "*",
            "div" to "/",
        )

    private fun expression(
        e: Expression,
        cs: Boolean,
    ): String =
        when (e) {
            is IsNull -> "(${expression(e.expr, cs)} ${if (cs) "je" else "is"} ${if (e.negated) {
                if (cs) {
                    "ne "
                } else {
                    "not "
                }
            } else {
                ""
            }}${if (cs) "prázdný" else "empty"})"
            is InList ->
                (
                    if (e.negated) {
                        if (cs) {
                            "ne "
                        } else {
                            "not "
                        }
                    } else {
                        ""
                    }
                ) +
                    "(${expression(
                        e.expr,
                        cs,
                    )} ${if (cs) "je jedno z" else "is one of"} (${e.items.joinToString(", ") { expression(it, cs) }}))"
            is org.tatrman.ttrp.expr.Literal ->
                when (val value = e.value) {
                    is org.tatrman.ttrp.expr.LiteralValue.Str -> quote(value.value)
                    is org.tatrman.ttrp.expr.LiteralValue.Bool ->
                        if (cs) {
                            if (value.value) {
                                "pravda"
                            } else {
                                "nepravda"
                            }
                        } else {
                            value.value.toString()
                        }
                    else -> ExprText.render(e)
                }
            is FunctionCall -> {
                val op =
                    operators[e.function.name]?.let {
                        if (cs &&
                            it == "and"
                        ) {
                            "a"
                        } else if (cs && it == "or") {
                            "nebo"
                        } else {
                            it
                        }
                    }
                when {
                    op != null -> "(${expression(e.args[0], cs)} $op ${expression(e.args[1], cs)})"
                    e.function.name == "not" -> "${if (cs) "ne" else "not"} (${expression(e.args.single(), cs)})"
                    e.function.name == "neg" -> "-(${expression(e.args.single(), cs)})"
                    else -> "${e.function.name}(${e.args.joinToString(", ") { expression(it, cs) }})"
                }
            }
            else -> ExprText.render(e)
        }

    private fun quote(text: String): String {
        require('\n' !in text && ('"' !in text || '\'' !in text)) { "TTR-B quoted text needs a free quote delimiter" }
        return if ('"' !in text) "\"$text\"" else "'$text'"
    }
}
