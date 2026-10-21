// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.emit.recipe

import io.kotest.assertions.fail
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.tatrman.ttrp.emit.GoldenSupport
import org.tatrman.ttrp.emit.sql.SqlTier
import java.nio.file.Files
import java.nio.file.Paths

/**
 * AU-core-P2 · S2.1 T1/T6, S2.2 T1/T6 — the four `17` recipes' statements, one per output, in the tier the bundle
 * ships them (SQL over E-R, the door translates): which outputs, their columns and the `:name`s they bind, and each
 * statement's text against its golden (`src/test/golden/recipes/<recipe>/<island>.<port>.sql`; regenerate with
 * `-DupdateGolden=true` and review the diff). That they compute the right rows is RecipeLivePgSpec's (ttrp-conform).
 */
class RecipeStatementsSpec :
    StringSpec({
        val statements =
            RecipeFixtures.SEVENTEEN.associateWith { f ->
                val plan = RecipeFixtures.sharedRecipe(f)
                plan.ok shouldBe true
                RecipeStatements.plan(plan)
            }

        "one statement per output — the candidates', each context's, each route and each route's detail" {
            statements.mapValues { (_, s) -> s.map { "${it.island}.${it.port}" } } shouldBe
                mapOf(
                    "release_order.ttrp" to
                        listOf(
                            "placed_orders.orders",
                            "shortfall.rows",
                            "price_differences.rows",
                            "overdue.rows",
                            "verdict.hold_sales",
                            "verdict.hold_ar",
                            "verdict.release_confirmed",
                            "verdict.release",
                            "verdict.hold_sales.shortfall",
                            "verdict.hold_sales.price_differences",
                            "verdict.hold_ar.overdue",
                        ),
                    "decide_return.ttrp" to
                        listOf(
                            "received.lines",
                            "bought_here.rows",
                            "return_share.rows",
                            "request_value.rows",
                            "stock_at_center.rows",
                            "way.reject_not_ours",
                            "way.reject_late",
                            "way.reject_abuse",
                            "way.to_customer_care",
                            "way.refund_no_return",
                            "way.replacement_no_return",
                            "way.exchange",
                            "way.refund_after_return",
                        ),
                    "monitor_inbound.ttrp" to
                        listOf(
                            "awaiting.lines",
                            "receipts.rows",
                            "age.rows",
                            "reminded.rows",
                            "inbound.complete",
                            "inbound.damaged",
                            "inbound.expired",
                            "inbound.remind",
                            "inbound.complete.receipts",
                            "inbound.damaged.receipts",
                        ),
                    "settle.ttrp" to
                        listOf(
                            "settling.lines",
                            "open_obligations.rows",
                            "documents.rows",
                            "overdue_days.rows",
                            "settlement.credit_note_issued",
                            "settlement.money_received",
                            "settlement.goods_shipped",
                            "settlement.money_overdue",
                            "settlement.goods_overdue",
                            "settlement.closed",
                            "settlement.money_overdue.open_obligations",
                            "settlement.goods_overdue.open_obligations",
                        ),
                )
        }

        "a route row is the decision's + `route`, `verdict_reason`; a detail row its context's + `route`, `context`" {
            val release = statements.getValue("release_order.ttrp")
            val hold = release.single { it.port == "hold_sales" }.columns.map { it.name }
            hold.takeLast(2) shouldContainExactly listOf("route", "verdict_reason")
            hold shouldContain "order_number"
            hold shouldContain "n_short"
            release
                .single { it.port == "hold_sales.shortfall" }
                .columns
                .map { it.name } shouldContainExactly
                listOf("order_number", "line_no", "item", "quantity", "on_hand", "outage", "short", "route", "context")
        }

        "no statement joins without a condition, and none hands out a synthesized `_ttrp_` column" {
            for ((_, list) in statements) {
                for (s in list) {
                    s.sql shouldNotContain "CROSS JOIN"
                    s.columns.none { it.name.startsWith("_ttrp_") } shouldBe true
                }
            }
        }

        "each statement binds `:as_of`, the parameters and the derived values it reads — typed, by name" {
            val bound = { f: String, island: String, port: String ->
                statements.getValue(f).single { it.island == island && it.port == port }.params
            }
            bound("release_order.ttrp", "price_differences", "rows") shouldContainExactly listOf("tolerance")
            bound("release_order.ttrp", "verdict", "release") shouldContainExactlyInAnyOrder
                listOf("as_of", "tolerance", "confirmation_threshold")
            bound("decide_return.ttrp", "bought_here", "rows") shouldContainExactly
                listOf("as_of_minus_return_window_days")
            bound("decide_return.ttrp", "return_share", "rows") shouldContainExactly listOf("as_of_minus_365_days")
            bound("settle.ttrp", "overdue_days", "rows") shouldContainExactly listOf("as_of")
        }

        "the E-R statement names entities and attributes; the DB rendering of the same plan names tables" {
            val plan = RecipeFixtures.sharedRecipe("release_order.ttrp")
            val er = RecipeStatements.plan(plan).single { it.island == "placed_orders" }.sql
            val db = RecipeStatements.plan(plan, SqlTier.DB).single { it.island == "placed_orders" }.sql
            er shouldContain "FROM \"order\""
            er shouldNotContain "so_order_number"
            db shouldContain "FROM \"sales_order\""
            db shouldContain "\"so_order_number\" AS \"order_number\""
        }

        "the statements match their goldens" {
            val all =
                statements.flatMap { (f, list) ->
                    list
                        .flatMap { s ->
                            val suffix = if (s.parts.isEmpty()) ".sql" else ".duckdb.sql"
                            val file = "${s.island}.${s.port}$suffix"
                            listOf("recipes/${f.removeSuffix(".ttrp")}/$file" to s.sql) +
                                s.parts.map { p -> "recipes/${f.removeSuffix(".ttrp")}/${p.file}" to p.sql }
                        }.distinct()
                }
            if (System.getProperty("updateGolden") == "true") {
                // every golden at once (GoldenSupport stops at the first); then fail, so the diff is reviewed
                for ((rel, sql) in all) {
                    val file = Paths.get("src", "test", "golden").resolve(rel)
                    Files.createDirectories(file.parent)
                    Files.writeString(file, sql.trimEnd('\n') + "\n")
                }
                fail("${all.size} recipe goldens updated — re-run without -DupdateGolden")
            }
            for ((rel, sql) in all) GoldenSupport.assertMatchesGolden(sql, rel)
        }
    })
