// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.conform

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.tatrman.ttrp.ast.RecipeDecl
import org.tatrman.ttrp.dialect.b.TtrbRecipeRenderer
import org.tatrman.ttrp.parser.TtrpParser
import java.nio.file.Files
import java.nio.file.Path

class RecipeTtrbSpec :
    FunSpec({
        test("H-1's TTR-B decision has the same route and detail rows as its canonical decision") {
            val root =
                generateSequence(Path.of("").toAbsolutePath()) {
                    it.parent
                }.first { Files.isDirectory(it.resolve("packages/kotlin/ttrp-frontend")) }
            val source =
                Files.readString(
                    root.resolve("packages/kotlin/ttrp-frontend/src/test/resources/recipes/release_order.ttrp"),
                )
            val decision =
                TtrpParser
                    .parseString(
                        source,
                    ).document.statements
                    .filterIsInstance<RecipeDecl>()
                    .single()
                    .decisions
                    .single()
            val prefix =
                """
                Load shortfall.
                Summarize count of line_no as n_short by order_number.
                Call that n_short.
                Load price_differences.
                Summarize count of line_no as n_price by order_number.
                Call that n_price.
                Load overdue.
                Summarize count of invoice_id as n_due by order_number.
                Call that n_due.
                Load orders.
                Join that optionally with n_short on left.order_number = right.order_number.
                Join that optionally with n_price on left.order_number = right.order_number.
                Join that optionally with n_due on left.order_number = right.order_number.
                Compute coalesce(n_short, 0) as n_short.
                Compute coalesce(n_price, 0) as n_price.
                Compute coalesce(n_due, 0) as n_due.
                Call that v.
                """.trimIndent()
            val replacement =
                "decision verdict(in orders, in shortfall, in price_differences, in overdue) " +
                    "\"\"\"ttrb\n$prefix\n${TtrbRecipeRenderer.decision(decision.switch)}\"\"\""
            val translated =
                source.replaceRange(
                    decision.location.offsetStart,
                    decision.location.offsetEnd,
                    replacement,
                )
            DuckDbRecipeEngine(RecipePlanted.scripts).use { engine ->
                val conform = RecipeConform()
                val canonical =
                    conform.runOn(
                        engine,
                        RecipePlanted.planSource(source, "release_order.ttrp"),
                        RecipePlanted.AS_OF,
                    )
                val sentences =
                    conform.runOn(
                        engine,
                        RecipePlanted.planSource(translated, "release_order.ttrp"),
                        RecipePlanted.AS_OF,
                    )
                RecipePlanted.check("release_order.ttrp", RecipePlanted.rows(sentences))
                val outcome = conform.compare(listOf(canonical, sentences))
                check(outcome.exitCode == 0) { outcome.summary() }
            }
        }
        test("omitted mode retains both matching routes and the same lowered graph in EN and CS") {
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
                    decision verdict(in orders) { orders -> switch { route_a: true route_b: true } }
                    actions { verdict.route_a, verdict.route_b -> release { order: order_number } }
                }
                """.trimIndent()
            val original =
                TtrpParser
                    .parseString(
                        source,
                    ).document.statements
                    .filterIsInstance<RecipeDecl>()
                    .single()
                    .decisions
                    .single()
            val canonical = RecipePlanted.planSource(source)
            for ((language, tag) in listOf("en" to "ttrb", "cs" to "ttrb-cs")) {
                val replacement = "decision verdict(in orders) \"\"\"$tag\n${TtrbRecipeRenderer.decision(
                    original.switch,
                    language,
                )}\"\"\""
                val translated =
                    RecipePlanted.planSource(
                        source.replaceRange(original.location.offsetStart, original.location.offsetEnd, replacement),
                    )
                org.tatrman.ttrp.graph.explain.NormalizedGraphJson
                    .write(translated.graph!!) shouldBe
                    org.tatrman.ttrp.graph.explain.NormalizedGraphJson
                        .write(canonical.graph!!)
                DuckDbRecipeEngine(RecipePlanted.scripts).use { engine ->
                    val conform = RecipeConform()
                    val outcome =
                        conform.compare(
                            listOf(
                                conform.runOn(engine, canonical, RecipePlanted.AS_OF),
                                conform.runOn(engine, translated, RecipePlanted.AS_OF),
                            ),
                        )
                    outcome.exitCode shouldBe 0
                }
            }
        }
        test("latest snapshot infers the unique calendar key attribute from metadata") {
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
                    context stock(out rows) target erp ${"\"\"\""}ttrb
                        Load er.entity.inventory.
                        Keep the latest snapshot per item and warehouse.
                        Send that to output rows.
                    ${"\"\"\""}
                    decision verdict(in orders, in stock) { orders -> switch { release: true because: "ok" } }
                    actions { verdict.release -> release { order: order_number } }
                }
                """.trimIndent()
            val plan = RecipePlanted.planSource(source)
            plan.recipe!!
                .implicitLatestDates.values
                .single() shouldBe "as_of_date"
            DuckDbRecipeEngine(RecipePlanted.scripts).use { engine ->
                val rows =
                    RecipePlanted
                        .rows(
                            RecipeConform().runOn(engine, plan, RecipePlanted.AS_OF),
                        ).getValue("stock.rows")
                rows.size shouldBe 4
                rows.associate { "${it["item"]}/${it["warehouse"]}" to it["quantity_on_hand"] } shouldBe
                    mapOf("101/1" to 40L, "102/1" to 3L, "103/2" to 100L, "103/1" to 0L)
            }
        }
        test("an imported parameter label resolves to the declared canonical parameter name") {
            val source =
                """
                uses world "hartland.worlds.demo"
                import hartland.*
                import hartland.automation.*
                recipe {
                    key: order.order_number
                    parameters: [tolerance]
                    candidates orders(out rows) target erp ${"\"\"\""}sql
                        SELECT o.order_number, o.value FROM er.entity.order o
                    ${"\"\"\""}
                    decision verdict(in orders) ${"\"\"\""}ttrb
                        Decide release when value is more than the price tolerance, because "within policy".
                        Decide exclusively.
                        Otherwise, nothing.
                    ${"\"\"\""}
                    actions { verdict.release -> release { order: order_number } }
                }
                """.trimIndent()
            RecipePlanted.planSource(source).ok shouldBe true
        }
    })
