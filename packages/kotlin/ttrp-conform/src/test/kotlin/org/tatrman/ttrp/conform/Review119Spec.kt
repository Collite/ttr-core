// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.conform

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe

/**
 * The AU-core-P2 phase-exit review (review-119), the cases it found untested (m1) — each **executed**: the reviewer's
 * probe compiled against the fixture model and run over the hartland sample, plus the rows it planted, on embedded
 * DuckDB (always) and on Postgres (`TTRP_CONFORM_PG=1`, [RecipePlanted.pgEngine]). The candidates are every order
 * (1–8, 11–14), so a case reads dates and NULLs the planted release_order candidates do not have.
 */
class Review119Spec :
    FunSpec({
        val head =
            """
            uses world "hartland.worlds.demo"
            import hartland.*
            import hartland.automation.*
            recipe {
                key: order.order_number
                candidates every(out orders) target erp ${"\"\"\""}sql
                    SELECT o.order_number, o.channel, o.customer, o.value, o.placed_date, o.delivered_date
                    FROM   er.entity.order o
                ${"\"\"\""}
            """.trimIndent()

        /** A recipe of [head], [contexts], a decision reading [reads] and switching every order on `value > 0`. */
        fun recipe(
            contexts: String,
            reads: List<String> = emptyList(),
        ): String {
            val ins = (listOf("orders") + reads).joinToString(", ") { "in $it" }
            val joins =
                reads.joinToString(
                    "",
                ) { " -> join(right: $it, kind: left, on: left.order_number = right.order_number)" }
            return "$head\n$contexts\n" +
                "decision v($ins) {\n  j = orders$joins\n  j -> switch { go: value > 0  because: \"x\" }\n}\n" +
                "actions { v.go -> release { order: order_number } }\n}\n"
        }

        val engines =
            buildList {
                add("duckdb")
                if (RecipePolars.enabled) add("polars")
                if (RecipePlanted.pgEnabled) add("pg")
            }

        /** [source] run on [engine] over the sample plus [extra] rows: every output's rows. */
        fun run(
            engine: String,
            source: String,
            extra: String = "",
        ): Map<String, List<Map<String, Any?>>> {
            val scripts = RecipePlanted.scripts + listOfNotNull(extra.takeIf { it.isNotBlank() })
            val e =
                if (engine ==
                    "pg"
                ) {
                    RecipePlanted.pgEngine("ttrp_review119", scripts)
                } else {
                    DuckDbRecipeEngine(scripts)
                }
            return e.use {
                RecipePlanted.rows(
                    if (engine ==
                        "polars"
                    ) {
                        RecipePolars.run(it.connection, RecipePlanted.planSource(source), RecipePlanted.AS_OF)
                    } else {
                        RecipeConform().runOn(it, RecipePlanted.planSource(source), RecipePlanted.AS_OF)
                    },
                )
            }
        }

        fun List<Map<String, Any?>>?.orders() = orEmpty().map { (it["order_number"] as Number).toInt() }

        fun List<Map<String, Any?>>?.byOrder(column: String) =
            orEmpty().associate { (it["order_number"] as Number).toInt() to (it[column] as Number?)?.toInt() }

        val delivered = listOf(11, 12, 13, 14)
        val undelivered = listOf(1, 2, 3, 4, 5, 6, 7, 8)

        for (engine in engines) {
            // ---- a named `else` takes exactly the untaken rows; a NULL condition is not taken -------------------
            for (mode in listOf("overlapping", "exclusive")) {
                test("$engine: a $mode switch's named else takes every row no route took, a NULL condition untaken") {
                    // delivered_date is NULL on 1–8: `delivered_date < :as_of` is NULL there — not taken, so else
                    val src =
                        "$head\ndecision v(in orders) {\n" +
                            "orders -> switch {\n" +
                            "    mode: $mode\n" +
                            "    big:       value > 50000             because: \"big\"\n" +
                            "    delivered: delivered_date < :as_of   because: \"delivered\"\n" +
                            "    else:      rest                      because: \"the rest\"\n" +
                            "} }\n" +
                            "actions { v.big, v.delivered, v.rest -> release { order: order_number } }\n}\n"
                    val r = run(engine, src)
                    r["v.big"].orders() shouldContainExactlyInAnyOrder listOf(2)
                    r["v.delivered"].orders() shouldContainExactlyInAnyOrder delivered
                    r["v.rest"].orders() shouldContainExactlyInAnyOrder undelivered - 2
                }
            }

            // ---- M2: a NULL non-text `because:` fill reads as empty -------------------------------------------
            test("$engine: a NULL date in a because: fill reads as empty, the reason is not NULL (M2)") {
                val src =
                    "$head\ndecision v(in orders) {\n" +
                        "orders -> switch {\n" +
                        "    big:  value > 50000  because: \"it's {channel} {delivered_date}!\"\n" +
                        "    else: none\n" +
                        "} }\n" +
                        "actions { v.big -> release { order: order_number } }\n}\n"
                run(engine, src).getValue("v.big").single()["verdict_reason"] shouldBe "it's web !"
            }

            // ---- B2: a qualified calendar argument is THAT column ---------------------------------------------
            test("$engine: age(since: cp.valid_from) after joins repeating valid_from counts cp's date (B2)") {
                // customer 10's own price of item 101 from 2026-10-01 (32 days before :as_of); the list's from 01-01
                val extra =
                    "INSERT INTO customer_price (cp_item_sk, cp_customer_sk, cp_valid_from, cp_price, cp_valid_to) " +
                        "VALUES (101, 10, DATE '2026-10-01', 9.50, NULL);"
                val ctx =
                    """
                    context prices(in orders, out rows) target erp {
                        lp = load(er.entity.price_list)
                        cp = load(er.entity.customer_price)
                        orders -> join(right: load(er.entity.sales_line), kind: inner, on: relation rel_order_line)
                               -> join(right: lp, kind: inner, on: left.item = right.item and left.channel = right.channel)
                               -> join(right: cp, kind: inner, on: left.item = right.item and left.customer = right.customer)
                               -> calc(a_cp = age(since: cp.valid_from), a_lp = age(since: lp.valid_from))
                               -> select(order_number, a_cp, a_lp)
                               -> rows
                    }
                    """.trimIndent()
                val rows = run(engine, recipe(ctx, listOf("prices")), extra)["prices.rows"]
                // customer 10's orders of item 101 → 32; customer 20's (its price from 01-01) → 305
                rows.byOrder("a_cp") shouldBe
                    mapOf(1 to 32, 2 to 32, 4 to 32, 6 to 32, 11 to 32, 12 to 32, 8 to 305, 14 to 305)
                rows.byOrder("a_lp").values.toSet() shouldBe setOf(305)
            }

            // ---- M6: `latest` keeps a NULL `per:` group --------------------------------------------------------
            test("$engine: latest(by:, per:) keeps the group whose per: value is NULL (M6)") {
                val extra =
                    "INSERT INTO inventory (inv_date_sk, inv_item_sk, inv_warehouse_sk, inv_quantity_on_hand) " +
                        "VALUES ((DATE '2026-11-02' - DATE '1900-01-02') + 2415022, 104, NULL, 5);"
                val ctx =
                    """
                    context stock(out rows) target erp {
                        load(er.entity.inventory) -> latest(by: as_of_date, per: item, warehouse) -> rows
                    }
                    """.trimIndent()
                val rows =
                    run(
                        engine,
                        recipe(ctx, emptyList()).replace("decision v(in orders)", "decision v(in orders, in stock)"),
                        extra,
                    )
                rows
                    .getValue("stock.rows")
                    .map {
                        "${(it["item"] as Number).toInt()}/${(it["warehouse"] as Number?)?.toInt()}:${(it["quantity_on_hand"] as Number).toInt()}"
                    }.shouldContainExactlyInAnyOrder(
                        listOf("101/1:40", "102/1:3", "103/2:100", "103/1:0", "104/null:5"),
                    )
            }

            // ---- M1: `:as_of - <date column>` is the days between, wherever it is written ------------------------
            test("$engine: :as_of - placed_date in a filter is the days between (M1)") {
                val ctx =
                    """
                    context old(in orders, out rows) target erp {
                        orders -> filter(:as_of - placed_date > 30) -> select(order_number) -> rows
                    }
                    """.trimIndent()
                // placed 2026-08-01 (93 days) and 2026-06-01 (154); 2026-10-10 is 23
                run(engine, recipe(ctx, listOf("old")))["old.rows"].orders() shouldContainExactlyInAnyOrder
                    listOf(12, 13)
            }

            test("$engine: :as_of - placed_date in a TTR-SQL SELECT is an integer count of days (M1)") {
                val ctx =
                    """
                    context ages(in orders, out rows) target erp ${"\"\"\""}sql
                        SELECT o.order_number, :as_of - o.placed_date AS age FROM orders o
                    ${"\"\"\""}
                    """.trimIndent()
                val rows = run(engine, recipe(ctx, listOf("ages")))["ages.rows"]
                rows.byOrder("age") shouldBe
                    mapOf(
                        1 to 1,
                        2 to 1,
                        3 to 1,
                        4 to 1,
                        5 to 1,
                        7 to 1,
                        8 to 1,
                        6 to 3,
                        11 to 23,
                        12 to 93,
                        13 to 154,
                        14 to 13,
                    )
                rows.orEmpty().forEach { (it["age"] is Number) shouldBe true }
            }
        }
    })
