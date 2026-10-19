// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.recipe

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.tatrman.ttrp.ast.RecipeDecl
import org.tatrman.ttrp.parser.Fixtures
import org.tatrman.ttrp.parser.TtrpParser

/** AU-core-P0 · S0.1 T6 — `ttrp explain` prints the recipe skeleton; pinned on H-1 (AU design `17` §3). */
class RecipeExplainSpec :
    StringSpec({
        "H-1 explains as its skeleton: roles and forms, the overlapping routes, the wiring" {
            val r = TtrpParser.parseString(Fixtures.read("/recipes/release_order.ttrp"), "release_order.ttrp")
            RecipeExplain.render(
                r.document.statements
                    .filterIsInstance<RecipeDecl>()
                    .single(),
            ) shouldBe
                """
                recipe · key order.order_number · as of now · parameters tolerance, confirmation_threshold
                  document releasing-a-placed-order.md
                candidates placed_orders(out orders)  target erp  [sql]
                context    shortfall(in orders, out rows)  target erp  [canonical]
                context    price_differences(in orders, out rows)  target erp  [sql]
                context    overdue(in orders, out rows)  target erp  [sql]
                decision   verdict(in orders, in shortfall, in price_differences, in overdue)  switch v  overlapping
                  route hold_sales         because "stock shortfall or price difference"  detail shortfall, price_differences
                  route hold_ar            because "overdue invoices"  detail overdue
                  route release_confirmed  because "all checks passed; above the confirmation threshold"
                  route release            because "all checks passed"
                actions
                  verdict.release           -> release { order }
                  verdict.release_confirmed -> ask { who, about, action, due, on_due: hold }
                  verdict.hold_sales        -> hold { order, department }
                  verdict.hold_sales        -> notify { who, detail }
                  verdict.hold_ar           -> hold { order, department }
                  verdict.hold_ar           -> notify { who, detail }
                """.trimIndent()
        }

        "an else route and the non-target forms render too" {
            val r = TtrpParser.parseString(Fixtures.read("/recipes/forms/island-forms.ttrp"), "island-forms.ttrp")
            val text =
                RecipeExplain.render(
                    r.document.statements
                        .filterIsInstance<RecipeDecl>()
                        .single(),
                )
            text.lines().first() shouldBe
                "recipe forms_tour · key sales_line.order_number, sales_line.line_no · " +
                "as of \"2026-10-01T06:00:00+02:00\" · parameters tolerance · protocol on_route"
            text.lines().count { it.contains("route else  -> none") } shouldBe 1
            text.lines().count { it.contains("foreign python per candidate schema line_score") } shouldBe 1
            text.lines().count { it.contains("window by item, order_number over rows 5") } shouldBe 1
        }
    })
