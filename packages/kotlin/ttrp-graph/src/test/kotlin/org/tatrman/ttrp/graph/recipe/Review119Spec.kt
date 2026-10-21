// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.graph.recipe

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.tatrman.ttrp.diagnostics.Severity
import org.tatrman.ttrp.diagnostics.TtrpDiagnosticId

/**
 * The AU-core-P2 phase-exit review (review-119): the checker-side regressions — each the reviewer's own probe. The
 * executing ones (the switch, the fills, the calendar counts, `latest`) are ttrp-conform's Review119Spec.
 */
class Review119Spec :
    StringSpec({
        val head =
            """
            uses world "hartland.worlds.demo"
            import hartland.*
            import hartland.automation.*
            recipe {
                key: order.order_number
                candidates placed(out orders) target erp ${"\"\"\""}sql
                    SELECT o.order_number, o.customer, o.value, o.placed_date, o.delivered_date
                    FROM   er.entity.order o WHERE o.state = 'placed'
                ${"\"\"\""}
            """.trimIndent()
        val tail =
            """
                decision v(in orders, in c) {
                    j = orders -> join(right: c, kind: left, on: left.order_number = right.order_number)
                    j -> switch { go: value > 0  because: "x" }
                }
                actions { v.go -> release { order: order_number } }
            }
            """.trimIndent()

        fun errors(context: String) =
            RecipeFixtures
                .findings(RecipeFixtures.plan("$head\n$context\n$tail", "review119.ttrp"))
                .filter { it.severity == Severity.ERROR }

        // ---- B4: a per-candidate output carries the key under its own name -------------------------------------

        "B4: a per-candidate SQL context that renames the key is refused — the runner filters on `order_number`" {
            val e =
                errors(
                    "context c(in orders, out rows) target erp \"\"\"sql\n" +
                        "SELECT o.order_number AS onum, o.value FROM orders o\n\"\"\"",
                ).single { it.id == TtrpDiagnosticId.REC_004 }
            e.message shouldContain "`c` is per candidate"
            e.message shouldContain "order_number"
        }

        "B4: the same rename in a canonical context is no per-candidate output — set-level, REC-010" {
            val r =
                RecipeFixtures.plan(
                    "$head\ncontext c(in orders, out rows) target erp {\n" +
                        "orders -> calc(onum = order_number) -> select(onum, value) -> rows\n}\n$tail",
                    "review119.ttrp",
                )
            r.recipe!!
                .contexts
                .single { it.name == "c" }
                .contextClass shouldBe ContextClass.SET_LEVEL
            RecipeFixtures.findings(r).map { it.id } shouldBe listOf(TtrpDiagnosticId.REC_010)
        }

        "B4: the key kept under its own name checks clean" {
            errors(
                "context c(in orders, out rows) target erp \"\"\"sql\n" +
                    "SELECT o.order_number, o.value AS v FROM orders o\n\"\"\"",
            ).shouldBeEmpty()
        }

        // ---- m5: relation inference reads the port's rows ------------------------------------------------------

        "m5: `on: relation R` over port rows without R's join attribute is RES-004, not a silent join" {
            val r =
                RecipeFixtures.plan(
                    head.replace("o.order_number, o.customer, o.value", "o.order_number, o.value") +
                        "\ncontext c(in orders, out rows) target erp {\n" +
                        "orders -> join(right: load(er.entity.customer), kind: inner, on: relation rel_line_customer)" +
                        " -> select(order_number) -> rows\n}\n$tail",
                    "review119.ttrp",
                )
            RecipeFixtures.findings(r).single { it.id == TtrpDiagnosticId.RES_004 }.message shouldContain "customer"
        }

        // ---- M1: what date arithmetic the lowering cannot turn into a count is refused ---------------------------

        "M1: a date column minus a date column is TYP-001 — the door subtracts no dates" {
            errors(
                "context c(in orders, out rows) target erp {\n" +
                    "orders -> calc(d = delivered_date - placed_date) -> select(order_number, d) -> rows\n}",
            ).single { it.id == TtrpDiagnosticId.TYP_001 }.message shouldContain "age(since:"
        }

        "M1: a bound date minus a column lowers — in a filter, a calc and a TTR-SQL SELECT" {
            for (ctx in listOf(
                "context c(in orders, out rows) target erp {\n" +
                    "orders -> filter(:as_of - placed_date > 30) -> select(order_number) -> rows\n}",
                "context c(in orders, out rows) target erp {\n" +
                    "orders -> calc(d = :as_of - placed_date) -> select(order_number, d) -> rows\n}",
                "context c(in orders, out rows) target erp \"\"\"sql\n" +
                    "SELECT o.order_number, :as_of - o.placed_date AS d FROM orders o\n\"\"\"",
            )) {
                errors(ctx).shouldBeEmpty()
            }
        }

        "M1: a column plus days stays refused (TYP-001), with the derived-bound hint" {
            errors(
                "context c(in orders, out rows) target erp {\n" +
                    "orders -> filter(placed_date + days(30) > :as_of) -> select(order_number) -> rows\n}",
            ).map { it.id } shouldBe listOf(TtrpDiagnosticId.TYP_001)
        }
    })
