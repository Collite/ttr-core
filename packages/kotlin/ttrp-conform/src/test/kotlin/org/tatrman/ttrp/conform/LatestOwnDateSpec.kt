// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.conform

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * AU-core-P2 · S2.4e T1 (b) — ttr-server#434, **executed**: hartland's `stock_at_center` (D-153: `latest` over the
 * candidates' rows, the inventory joined twice — the line's item, then its requested variant) read per line by a
 * decision. Each item and variant has three inventory dates at center 1, so a second `latest` reducing by the
 * first instance's date keeps all three variant rows and the route fans out (`route keys must be unique`). On embedded
 * DuckDB (always), Polars and Postgres (`TTRP_CONFORM_PG=1`) as [Review119Spec].
 */
class LatestOwnDateSpec :
    FunSpec({
        /** The candidates, a `stock_at_center` context of [ctx], and a decision reading it per line. */
        fun recipe(ctx: String): String =
            """
            uses world "hartland.worlds.demo"
            import hartland.*
            import hartland.automation.*
            recipe {
                key: return_request_line.line_id
                candidates received(out lines) target erp ${"\"\"\""}sql
                    SELECT l.line_id, l.item, l.quantity, l.requested_variant, r.customer
                    FROM   er.entity.return_request_line l JOIN er.entity.return_request r ON r.request_number = l.request_number
                    WHERE  l.state = 'received'
                ${"\"\"\""}
                context stock_at_center(in lines, out rows) target erp {
            CTX
                }
                decision way(in lines, in stock_at_center) {
                    v = lines -> join(right: stock_at_center, kind: left, on: left.line_id = right.line_id)
                    v -> switch {
                        exchange: variant_on_hand >= quantity  because: "requested variant on stock"
                        else:     refund_after_return           because: "goods back, then the money"
                    }
                }
                actions {
                    way.exchange            -> set_state { line: line_id, to: awaiting_goods, way: exchange }
                    way.refund_after_return -> set_state { line: line_id, to: awaiting_goods, way: refund_after_return }
                }
            }
            """.trimIndent().replace("CTX", ctx.trimIndent().prependIndent("        "))

        // hartland's `stock_at_center` (D-153): the second inventory on the RIGHT of the second join
        val twice =
            recipe(
                """
                stock = load(er.entity.inventory)
                lines -> join(right: load(er.entity.customer), kind: inner, on: relation rel_line_customer)
                      -> join(right: stock, kind: left, on: left.item = right.item and customer.center = right.warehouse)
                      -> latest(by: as_of_date, per: line_id)
                      -> calc(item_on_hand = coalesce(stock.quantity_on_hand, 0))
                      -> join(right: stock, kind: left, on: left.requested_variant = right.item and customer.center = right.warehouse)
                      -> latest(by: as_of_date, per: line_id)
                      -> calc(variant_on_hand = coalesce(stock.quantity_on_hand, 0))
                      -> select(line_id, item_on_hand, variant_on_hand)
                      -> rows
                """,
            )

        // review-352 R1: the second inventory on the LEFT of the second join (only lines with a variant: an inner join)
        val swapped =
            recipe(
                """
                stock = load(er.entity.inventory)
                first = lines -> join(right: load(er.entity.customer), kind: inner, on: relation rel_line_customer)
                      -> join(right: stock, kind: left, on: left.item = right.item and customer.center = right.warehouse)
                      -> latest(by: as_of_date, per: line_id)
                      -> calc(item_on_hand = coalesce(stock.quantity_on_hand, 0))
                stock -> join(right: first, kind: inner, on: left.item = right.requested_variant and left.warehouse = right.center)
                      -> latest(by: as_of_date, per: line_id)
                      -> calc(variant_on_hand = coalesce(quantity_on_hand, 0))
                      -> select(line_id, item_on_hand, variant_on_hand)
                      -> rows
                """,
            )

        // A third (older) date for items 101 and 102 at center 1, and two older ones for 103 (the sample's 11-02: 0).
        fun sk(date: String) = "(DATE '$date' - DATE '1900-01-02') + 2415022"
        val extra =
            "INSERT INTO inventory (inv_date_sk, inv_item_sk, inv_warehouse_sk, inv_quantity_on_hand) VALUES " +
                "(${sk("2026-10-19")}, 101, 1, 60), (${sk("2026-10-19")}, 102, 1, 12), " +
                "(${sk("2026-10-19")}, 103, 1, 7), (${sk("2026-10-26")}, 103, 1, 5);"

        val engines =
            buildList {
                add("duckdb")
                if (RecipePolars.enabled) add("polars")
                if (RecipePlanted.pgEnabled) add("pg")
            }

        fun run(
            engine: String,
            more: String = "",
            source: String = twice,
        ): Map<String, List<Map<String, Any?>>> {
            val scripts = RecipePlanted.scripts + extra + listOfNotNull(more.takeIf { it.isNotBlank() })
            val e =
                if (engine ==
                    "pg"
                ) {
                    RecipePlanted.pgEngine("ttrp_latest_own_date", scripts)
                } else {
                    DuckDbRecipeEngine(scripts)
                }
            return e.use {
                RecipePlanted.rows(
                    if (engine == "polars") {
                        RecipePolars.run(it.connection, RecipePlanted.planSource(source), RecipePlanted.AS_OF)
                    } else {
                        RecipeConform().runOn(it, RecipePlanted.planSource(source), RecipePlanted.AS_OF)
                    },
                )
            }
        }

        fun Map<String, Any?>.int(c: String): Int = (this[c] as Number).toInt()

        // The received lines 901–907: items 101/102/103 at center 1; only 904 (item 102) asks for a variant (103).
        val lines = listOf(901, 902, 903, 904, 905, 906, 907)
        val onHand = mapOf(101 to 40, 102 to 3, 103 to 0)
        val items = mapOf(901 to 101, 902 to 101, 903 to 101, 904 to 102, 905 to 101, 906 to 101, 907 to 103)

        for (engine in engines) {
            test(
                "$engine: two latest over the inventory give one stock row per line, each at its own latest date (#434)",
            ) {
                val rows = run(engine).getValue("stock_at_center.rows")
                rows.map { it.int("line_id") }.sorted() shouldBe lines
                rows.associate { it.int("line_id") to it.int("item_on_hand") } shouldBe
                    lines.associateWith { onHand.getValue(items.getValue(it)) }
                // 904's variant 103 is 0 on hand at its latest date (11-02), not 7 or 5 of the older ones
                rows.associate { it.int("line_id") to it.int("variant_on_hand") } shouldBe
                    lines.associateWith { 0 }
            }

            test("$engine: the way joined per line routes every line exactly once (#434)") {
                val r = run(engine)
                val routed =
                    (r["way.exchange"].orEmpty() + r["way.refund_after_return"].orEmpty()).map {
                        it.int(
                            "line_id",
                        )
                    }
                routed.sorted() shouldBe lines
            }

            test("$engine: the second inventory on the LEFT of the join reduces by its own date too (review-352 R1)") {
                val rows = run(engine, source = swapped).getValue("stock_at_center.rows")
                // 904 alone asks for a variant: once, at 103's latest date (0 on hand), not its 10-19 or 10-26 (7, 5)
                rows.map { it.int("line_id") } shouldBe listOf(904)
                rows.single().int("variant_on_hand") shouldBe 0
                rows.single().int("item_on_hand") shouldBe 3
            }

            test("$engine: a line whose item has no stock at its center keeps its row — latest of no date (S2.4e)") {
                // 908 returns item 104, which center 1 never stocked: both LEFT joins match nothing, both dates NULL
                val more =
                    "INSERT INTO return_request (rr_request_number, rr_customer_sk, rr_order_number, rr_channel, " +
                        "rr_requested_date, rr_state) VALUES (808, 10, 11, 'web', DATE '2026-10-28', 'received');\n" +
                        "INSERT INTO return_request_line (rl_line_id, rl_request_number, rl_line_no, rl_item_sk, " +
                        "rl_quantity, rl_unit_price, rl_reason, rl_requested_resolution, rl_requested_variant_sk, " +
                        "rl_state, rl_way, rl_customer_sk) VALUES " +
                        "(908, 808, 1, 104, 1, 10.00, 'wrong_size', 'refund', NULL, 'received', NULL, 10);"
                val r = run(engine, more)
                val row = r.getValue("stock_at_center.rows").single { it.int("line_id") == 908 }
                row.int("item_on_hand") shouldBe 0
                row.int("variant_on_hand") shouldBe 0
                r.getValue("stock_at_center.rows").map { it.int("line_id") }.sorted() shouldBe lines + 908
            }
        }
    })
