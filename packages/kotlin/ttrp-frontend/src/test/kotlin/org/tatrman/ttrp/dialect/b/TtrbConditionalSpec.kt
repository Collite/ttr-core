// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.dialect.b

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.tatrman.ttrp.ast.AssignEntry
import org.tatrman.ttrp.ast.ChainStmt
import org.tatrman.ttrp.ast.OpCall
import org.tatrman.ttrp.expr.CaseWhen
import org.tatrman.ttrp.expr.ColumnRef
import org.tatrman.ttrp.expr.Expression
import org.tatrman.ttrp.expr.FunctionCall
import org.tatrman.ttrp.expr.IsNull
import org.tatrman.ttrp.expr.Literal
import org.tatrman.ttrp.expr.LiteralValue

/**
 * B7 — a conditional value: `Spočti x jako 1, když <p>, jinak 2.` / `Compute x as 1 when <p>, otherwise 2.`
 * → `calc { x = case when p then 1 else 2 end }`; several `<value>, když <p>` arms read in order; no
 * `jinak` / `otherwise` ⇒ no else (NULL). `je prázdný` / `is empty` works in the condition.
 */
class TtrbConditionalSpec :
    StringSpec({
        fun e(x: Expression?): String =
            when (x) {
                null -> "-"
                is ColumnRef -> "col(${x.column})"
                is Literal ->
                    when (val v = x.value) {
                        is LiteralValue.Str -> "\"${v.value}\""
                        is LiteralValue.Num -> v.raw
                        is LiteralValue.Bool -> v.value.toString()
                        else -> v.toString()
                    }
                is FunctionCall -> "${x.function.value}(${x.args.joinToString(",") { e(it) }})"
                is IsNull -> "isnull${if (x.negated) "!n" else ""}(${e(x.expr)})"
                is CaseWhen ->
                    "case(" + x.branches.joinToString(";") { "${e(it.first)}=>${e(it.second)}" } +
                        " else ${e(x.elseExpr)})"
                else -> x.toString()
            }

        val en = TtrbCorpus.decompose("conditional/conditional.ttrb")
        val cs = TtrbCorpus.decompose("conditional/conditional.ttrb-cs")
        val calcs =
            (
                en.statements
                    .filterIsInstance<ChainStmt>()
                    .single()
                    .chain.elements
            ).filterIsInstance<OpCall>()
                .filter { it.name == "calc" }
                .map { op ->
                    op.config!!
                        .entries
                        .filterIsInstance<AssignEntry>()
                        .single()
                        .let { "${it.name}=${e(it.value)}" }
                }

        "the conditional fixtures decompose clean; the Czech twin lowers identically" {
            en.diagnostics.shouldBeEmpty()
            cs.diagnostics.shouldBeEmpty()
            TtrbCorpus.canonical(cs.statements) shouldBe TtrbCorpus.canonical(en.statements)
        }

        "each sentence is one calc entry: a case per conditional, the plain compute unchanged" {
            calcs shouldContainExactly
                listOf(
                    "priority=case(op.gt(col(amount),1000)=>1 else 2)",
                    "band=case(op.gt(col(amount),1000)=>\"A\";op.gt(col(amount),100)=>\"B\" else \"C\")",
                    "contact=case(isnull(col(email))=>\"missing\" else col(email))",
                    "flagged=case(isnull!n(col(note))=>true else -)",
                    "total=op.mul(col(amount),2)",
                )
        }
    })
