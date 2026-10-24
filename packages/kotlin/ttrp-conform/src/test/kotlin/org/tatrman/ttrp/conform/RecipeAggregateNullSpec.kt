// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.conform

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class RecipeAggregateNullSpec :
    FunSpec({
        for (grouped in listOf(false, true)) {
            test("SQL and Polars aggregates preserve NULL for ${if (grouped) "an all-NULL group" else "empty input"}")
                .config(enabled = RecipePolars.enabled) {
                    val query =
                        if (grouped) {
                            "SELECT s.order_number, SUM(s.unit_price) AS total, AVG(s.unit_price) AS mean_value, MIN(s.unit_price) AS minimum, MAX(s.unit_price) AS maximum FROM er.entity.sales_line s WHERE s.order_number = 999 GROUP BY s.order_number"
                        } else {
                            "SELECT SUM(s.unit_price) AS total, AVG(s.unit_price) AS mean_value, MIN(s.unit_price) AS minimum, MAX(s.unit_price) AS maximum FROM er.entity.sales_line s WHERE s.order_number = -1"
                        }
                    val source =
                        """
                        uses world "hartland.worlds.demo"
                        import hartland.*
                        import hartland.automation.*
                        recipe {
                            key: order.order_number
                            candidates orders(out rows) target erp ${"\"\"\""}sql
                                SELECT o.order_number FROM er.entity.order o
                            ${"\"\"\""}
                            context totals(out rows) target erp ${"\"\"\""}sql
                                $query
                            ${"\"\"\""}
                            decision verdict(in orders) { orders -> switch { release: true } }
                            actions { verdict.release -> release { order: order_number } }
                        }
                        """.trimIndent()
                    val scripts =
                        RecipePlanted.scripts +
                            "INSERT INTO sales_line (sl_order_number, sl_line_no, sl_unit_price) VALUES (999, 1, NULL), (999, 2, NULL);"
                    val plan = RecipePlanted.planSource(source, "null_aggregates.ttrp")
                    DuckDbRecipeEngine(scripts).use { duck ->
                        val conform = RecipeConform(numericDeltas = mapOf("mean_value" to 1e-12))
                        val sql = conform.runOn(duck, plan, RecipePlanted.AS_OF)
                        val polars = RecipePolars.run(duck.connection, plan, RecipePlanted.AS_OF)
                        for (run in listOf(sql, polars)) {
                            val row = RecipePlanted.rows(run).getValue("totals.rows").single()
                            for (column in listOf("total", "mean_value", "minimum", "maximum")) {
                                row[column] shouldBe
                                    null
                            }
                        }
                        conform.compare(listOf(sql, polars)).exitCode shouldBe 0
                        if (RecipePlanted.pgEnabled) {
                            val name = if (grouped) "r136_null_group" else "r136_null_empty"
                            RecipePlanted.pgEngine(name, scripts).use { pg ->
                                conform
                                    .compare(
                                        listOf(conform.runOn(pg, plan, RecipePlanted.AS_OF), sql, polars),
                                    ).exitCode shouldBe
                                    0
                            }
                        }
                    }
                }
        }
    })
