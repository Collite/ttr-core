// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.conform

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.types.shouldBeInstanceOf
import org.tatrman.translate.v1.Language
import org.tatrman.translator.framework.FixtureModel
import org.tatrman.translator.orchestrator.ParseResult
import org.tatrman.translator.orchestrator.Translator
import org.tatrman.translator.orchestrator.UnparseResult

/** D-231: execute the translated SQL and its Polars twin; route equality must not need trimming. */
class RaggedRouteSpec :
    FunSpec({
        val engines =
            buildList {
                add("duckdb")
                if (RecipePlanted.pgEnabled) add("pg")
                if (RecipePolars.enabled) add("polars")
            }
        for (engine in engines) {
            for (longRoute in listOf("to_customer_care", "replacement_no_return")) {
                test("$engine: CASE routes a/$longRoute retain their exact text") {
                    val quote = "\"\"\""
                    val source =
                        """
                        uses world "hartland.worlds.demo"
                        import hartland.*
                        import hartland.automation.*
                        recipe {
                            key: order.order_number
                            candidates every(out orders) target erp ${quote}sql
                                SELECT o.order_number,
                                       CASE WHEN o.order_number = 1 THEN 'a' ELSE '$longRoute' END AS proposed_route
                                FROM er.entity.order o
                                WHERE o.order_number IN (1, 2)
                            $quote
                            decision v(in orders) {
                                orders -> switch {
                                    short: proposed_route = 'a' because: "short"
                                    else: rest
                                }
                            }
                            actions { v.short, v.rest -> release { order: order_number } }
                        }
                        """.trimIndent()
                    val plan = RecipePlanted.planSource(source)
                    val db =
                        if (engine ==
                            "pg"
                        ) {
                            RecipePlanted.pgEngine("ttrp_ragged")
                        } else {
                            DuckDbRecipeEngine(RecipePlanted.scripts)
                        }
                    db.use {
                        if (engine != "polars") {
                            // Exercise the SQL door too; direct REL_NODE emission alone did not pad on master.
                            it.connection.createStatement().use { statement ->
                                statement.execute("CREATE TABLE customers (id BIGINT)")
                                statement.execute("INSERT INTO customers VALUES (1), (2)")
                                for (sql in listOf(
                                    "SELECT CASE WHEN id = 1 THEN 'a' ELSE '$longRoute' END AS route FROM customers",
                                    "SELECT 'a' AS route FROM customers WHERE id = 1 UNION ALL " +
                                        "SELECT '$longRoute' AS route FROM customers WHERE id = 2",
                                )) {
                                    val translator = Translator(FixtureModel.handle())
                                    val parsed = translator.parseToRelNode(sql, Language.SQL)
                                    parsed.shouldBeInstanceOf<ParseResult.Success>()
                                    val output = translator.unparseFromRelNode(parsed.plan, Language.SQL, db.dialect)
                                    output.shouldBeInstanceOf<UnparseResult.Success>()
                                    statement.executeQuery(output.output).use { result ->
                                        val values = buildList { while (result.next()) add(result.getString(1)) }
                                        values shouldContainExactlyInAnyOrder listOf("a", longRoute)
                                    }
                                }
                            }
                        }
                        val run =
                            if (engine == "polars") {
                                RecipePolars.run(it.connection, plan, RecipePlanted.AS_OF)
                            } else {
                                RecipeConform().runOn(it, plan, RecipePlanted.AS_OF)
                            }
                        val rows = RecipePlanted.rows(run)
                        (
                            rows.getValue(
                                "v.short",
                            ) + rows.getValue("v.rest")
                        ).map { it["proposed_route"] } shouldContainExactlyInAnyOrder
                            listOf("a", longRoute)
                        rows
                            .getValue(
                                "v.short",
                            ).map { (it["order_number"] as Number).toInt() } shouldContainExactlyInAnyOrder
                            listOf(1)
                        rows
                            .getValue(
                                "v.rest",
                            ).map { (it["order_number"] as Number).toInt() } shouldContainExactlyInAnyOrder
                            listOf(2)
                    }
                }
            }
        }
    })
