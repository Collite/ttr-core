// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.recipe

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.tatrman.ttrp.ast.AssignEntry
import org.tatrman.ttrp.ast.Assignment
import org.tatrman.ttrp.ast.ChainStmt
import org.tatrman.ttrp.ast.ContainerDecl
import org.tatrman.ttrp.ast.ExprArg
import org.tatrman.ttrp.ast.FragmentBody
import org.tatrman.ttrp.ast.OpCall
import org.tatrman.ttrp.expr.ColumnRef
import org.tatrman.ttrp.parser.TtrpParser

/** AU-core-P1 — the language design's argument forms read canonically, and the TTR-SQL fixes the recipes needed. */
class LdFormsSpec :
    StringSpec({
        fun desugared(src: String) =
            LdForms.desugar(TtrpParser.parseString(src.trimIndent() + "\n", "ld.ttrp").document)

        fun ops(src: String): List<OpCall> =
            desugared(src).statements.flatMap { s ->
                ((s as? Assignment)?.chain ?: (s as? ChainStmt)?.chain)?.elements.orEmpty().filterIsInstance<OpCall>()
            }

        "`kind:` is the join's `type:`" {
            val join = ops("x = a -> join(right: b, kind: left, on: left.k = right.k)").single()
            join.args.map { it.name } shouldContainExactly listOf("right", "type", "on")
        }

        "an op written as an argument is hoisted to a variable ahead of its statement" {
            val doc = desugared("x = a -> join(right: load(er.entity.sales_line), kind: inner, on: relation r)")
            doc.statements.size shouldBe 2
            val hoisted = doc.statements[0] as Assignment
            hoisted.target shouldBe "_ld0"
            (hoisted.chain.elements.single() as OpCall).name shouldBe "load"
            val join = (doc.statements[1] as Assignment).chain.elements[1] as OpCall
            ((join.args.first { it.name == "right" }.value as ExprArg).expr as ColumnRef).column shouldBe "_ld0"
        }

        "`calc(a = e, …)` is `calc { a = e, … }`" {
            val calc = ops("x = a -> calc(n = coalesce(n, 0), m = n + 1)")[0]
            calc.args shouldBe emptyList()
            calc.config!!.entries.map { (it as AssignEntry).name } shouldContainExactly listOf("n", "m")
        }

        "`select(c, x = e)` is `calc { x = e } -> select(c, x)`" {
            val (calc, select) = ops("x = a -> select(order_number, short = quantity - on_hand)")
            calc.name shouldBe "calc"
            (calc.config!!.entries.single() as AssignEntry).name shouldBe "short"
            select.args.map { ((it.value as ExprArg).expr as ColumnRef).column } shouldContainExactly
                listOf("order_number", "short")
        }

        "a hoisted variable never takes a name the document binds" {
            val doc =
                desugared(
                    "_ld0 = load(er.entity.order)\nx = _ld0 -> join(right: load(er.entity.sales_line), kind: inner, on: relation r)",
                )
            (doc.statements[1] as Assignment).target shouldBe "_ld1"
        }

        "review-115 m9: an argument form the desugar does not read is TTRP-PRS-009, not dropped" {
            fun ids(src: String) = TtrpParser.parseString(src + "\n", "f.ttrp").diagnostics.map { it.id.id }
            ids("x = a -> calc(big = amount + 1, invoice_id > 3)") shouldContainExactly listOf("TTRP-PRS-009")
            ids("x = a -> calc(big: amount + 1)") shouldContainExactly listOf("TTRP-PRS-009")
            ids("x = a -> join(right: b, kind: left, type: inner, on: left.k = right.k)") shouldContainExactly
                listOf("TTRP-PRS-009")
            ids("x = a -> calc(big = amount + 1)") shouldContainExactly emptyList()
        }

        "review-115 m8: `latest` / `within` outside a recipe are refused at parse time (they compile with P2)" {
            TtrpParser
                .parseString("x = load(er.entity.order) -> within(placed_date, last: days(30))\n", "f.ttrp")
                .diagnostics
                .map { it.id.id } shouldContainExactly listOf("TTRP-REC-099")
        }

        "a filter's `=` is a comparison, untouched" {
            ops("x = a -> filter(k = 1)").single().args.size shouldBe 1
        }

        fun sqlStatements(sql: String) =
            (
                TtrpParser
                    .parseString("container c(in q, out rows) target e \"\"\"sql\n$sql\n\"\"\"\n", "s.ttrp")
                    .document.statements
                    .single() as ContainerDecl
            ).body.let { (it as FragmentBody).decomposition!!.statements }

        "a TTR-SQL `AS alias` names the projected column" {
            val project =
                sqlStatements("SELECT q.k, q.a * q.b AS line_value FROM q")
                    .filterIsInstance<Assignment>()
                    .last()
                    .chain.elements
                    .filterIsInstance<OpCall>()
                    .last()
            project.args.map { it.name } shouldContainExactly listOf(null, "line_value")
        }

        "every table joined so far is the next join's `left`" {
            val text =
                sqlStatements(
                    "SELECT q.k FROM q LEFT JOIN er.entity.a x ON x.k = q.k LEFT JOIN er.entity.b y ON y.k = q.k AND y.m = x.m",
                ).toString()
            // the second join's ON reads q and x through `left`
            text.contains("ColumnRef(port=left, column=k") shouldBe true
            text.contains("ColumnRef(port=left, column=m") shouldBe true
            text.contains("ColumnRef(port=null, column=k") shouldBe false
        }

        "a duration in a TTR-SQL island is a call, as in canonical TTR-P" {
            val text =
                sqlStatements(
                    "SELECT q.k FROM q WHERE q.d >= :as_of - days(365) AND q.e < :as_of - days(:w)",
                ).toString()
            text.contains("DurationLiteral(amount=365, unit=DAYS") shouldBe true
            text.contains("DurationParam(unit=DAYS, param=w") shouldBe true
        }
    })
