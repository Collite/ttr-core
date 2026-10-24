// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.dialect.b

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.tatrman.ttrp.ast.RecipeDecl
import org.tatrman.ttrp.ast.SwitchMode
import org.tatrman.ttrp.parser.TtrpParser

class TtrbRecipeSentencesSpec :
    FunSpec({
        fun decision(
            body: String,
            tag: String = "ttrb",
        ) = TtrpParser.parseString(
            "recipe { key: order.order_number decision verdict(in orders) \"\"\"$tag\n$body\n\"\"\" }",
        )
        test("EN decision sentences decompose to the canonical overlapping SwitchStmt") {
            val result =
                decision(
                    "Decide hold_sales when n_short > 0, because \"shortfall\", showing shortfall and price_differences. Decide; a row may take several routes. Otherwise decide release.",
                )
            result.diagnostics shouldBe emptyList()
            val sw =
                result.document.statements
                    .filterIsInstance<RecipeDecl>()
                    .single()
                    .decisions
                    .single()
                    .switch
            sw.input.parts shouldBe listOf("orders")
            sw.mode shouldBe SwitchMode.OVERLAPPING
            sw.routes
                .single()
                .detail
                .map { it.text } shouldBe listOf("shortfall", "price_differences")
            sw.elseRoute!!.name shouldBe "release"
        }
        test("fixed Czech decision forms and parameter mentions") {
            val result =
                decision(
                    "Rozhodni hold_sales, když amount > :tolerance, protože \"rozdíl\", s detailem shortfall. Rozhoduj výlučně. Jinak nic.",
                    "ttrb-cs",
                )
            result.diagnostics shouldBe emptyList()
            result.document.statements
                .filterIsInstance<RecipeDecl>()
                .single()
                .decisions
                .single()
                .switch.mode shouldBe
                SwitchMode.EXCLUSIVE
        }
        test("recipe sugar sentences compile through the same canonical operators") {
            val at =
                org.tatrman.ttrp.ast
                    .SourceLocation("x", 1, 0, 1, 0, 0, 0)
            val source =
                listOf(
                    "Load inventory. Keep the latest snapshot per item and warehouse.",
                    "Keep only rows where placed_date is within the last 30 days.",
                    "Compute days_open as the working days since decided_date.",
                    "Keep only rows where amount is more than the tolerance.",
                ).joinToString(" ")
            val parsed = TtrB.decompose(source, at, "rows")
            parsed.diagnostics shouldBe emptyList()
            val ops =
                parsed.statements
                    .filterIsInstance<org.tatrman.ttrp.ast.Assignment>()
                    .flatMap {
                        it.chain.elements
                    }.filterIsInstance<org.tatrman.ttrp.ast.OpCall>()
            ops.map { it.name } shouldBe listOf("load", "latest", "within", "calc", "filter")
            val filter =
                (
                    ops
                        .last()
                        .args
                        .single()
                        .value as org.tatrman.ttrp.ast.ExprArg
                ).expr
            org.tatrman.ttrp.expr.ExprText
                .render(filter) shouldBe "amount > :tolerance"
        }
        test("decision rendering round trips route conditions, reasons, details, mode and else") {
            val original =
                decision(
                    "Decide hold_sales when amount > :tolerance, because \"price\", showing shortfall. Decide exclusively. Otherwise, nothing.",
                )
            val sw =
                original.document.statements
                    .filterIsInstance<RecipeDecl>()
                    .single()
                    .decisions
                    .single()
                    .switch
            val rendered = TtrbRecipeRenderer.decision(sw)
            val again =
                decision(
                    rendered,
                ).document.statements.filterIsInstance<RecipeDecl>().single().decisions.single().switch
            again.routes.map { it.name to it.because } shouldBe sw.routes.map { it.name to it.because }
            again.mode shouldBe sw.mode
            again.elseRoute!!.name shouldBe sw.elseRoute!!.name
        }
        test("an omitted switch mode round trips without becoming exclusive in EN and CS") {
            val parsed = decision("Decide route_a when x > 0. Decide route_b when y > 0.")
            val sw =
                parsed.document.statements
                    .filterIsInstance<RecipeDecl>()
                    .single()
                    .decisions
                    .single()
                    .switch
            sw.mode shouldBe null
            for ((lang, tag) in listOf("en" to "ttrb", "cs" to "ttrb-cs")) {
                val roundtrip = decision(TtrbRecipeRenderer.decision(sw, lang), tag)
                roundtrip.diagnostics shouldBe emptyList()
                roundtrip.document.statements
                    .filterIsInstance<RecipeDecl>()
                    .single()
                    .decisions
                    .single()
                    .switch.mode shouldBe
                    null
            }
        }
        test("optional action sentence takes its routes explicitly from the canonical skeleton") {
            val at = org.tatrman.ttrp.ast.SourceLocation.UNKNOWN
            val wiring =
                TtrbRecipeWiring
                    .parse(
                        "Do release with order as order_number.",
                        listOf(
                            org.tatrman.ttrp.ast
                                .RouteRef("verdict", "release", at),
                        ),
                        at,
                    ).single()
            wiring.action shouldBe "release"
            wiring.args.single().name shouldBe "order"
            wiring.routes.single().route shouldBe "release"
        }
        test("Czech rendering round trips boolean routes and named otherwise reasons/details") {
            val parsed =
                decision(
                    "Decide go when amount > :tolerance and n_short = 0, because \"ok\". Decide exclusively. Otherwise decide rest, because \"fallback\", showing shortfall.",
                )
            val sw =
                parsed.document.statements
                    .filterIsInstance<RecipeDecl>()
                    .single()
                    .decisions
                    .single()
                    .switch
            val result = decision(TtrbRecipeRenderer.decision(sw, "cs"), "ttrb-cs")
            result.diagnostics shouldBe emptyList()
            val again =
                result.document.statements
                    .filterIsInstance<RecipeDecl>()
                    .single()
                    .decisions
                    .single()
                    .switch
            org.tatrman.ttrp.expr.ExprText
                .render(again.routes.single().condition) shouldBe
                org.tatrman.ttrp.expr.ExprText
                    .render(sw.routes.single().condition)
            again.elseRoute!!.because shouldBe "fallback"
            again.elseRoute!!.detail.map { it.text } shouldBe listOf("shortfall")
        }
        test("route, mode and otherwise sentences in If blocks are rejected without a partial switch") {
            val programs =
                listOf(
                    "If amount > 100:\n    Decide hold when n_short > 0.\nDecide release when n_short = 0.",
                    "Decide release when n_short = 0.\nIf amount > 100:\n    Decide hold when n_short > 0.",
                    "Decide hold when n_short > 0.\nIf amount > 100:\n    Decide exclusively.",
                    "Decide hold when n_short > 0.\nIf amount > 100:\n    Otherwise decide release.",
                )
            for (text in programs) {
                val result =
                    TtrB.decompose(
                        text,
                        org.tatrman.ttrp.ast
                            .SourceLocation("x", 1, 0, 1, 0, 0, 0),
                        null,
                        inPorts = setOf("orders"),
                        decision = true,
                    )
                val diagnostic = result.diagnostics.single { it.id.id == "TTRP-B-114" }
                text.substring(diagnostic.location.offsetStart, diagnostic.location.offsetEnd).trim() shouldBe
                    text
                        .lines()
                        .last { it.startsWith("    ") }
                        .trim()
                        .removeSuffix(".")
                result.recipeSwitch shouldBe null
            }
        }
        xtest("pending LM/NLS D-64: morphology-dependent Czech parameter labels and snapshot nouns") { }
    })
