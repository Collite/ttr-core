// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.conform

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/** Two independent databases: a part cannot accidentally read the other storage's tables. */
class RecipeCrossStorageConformSpec :
    FunSpec({
        val fixtures =
            java.nio.file.Path
                .of("src/test/resources/recipes/hartland")
        val erp =
            java.nio.file.Files
                .readString(fixtures.resolve("erp.sql"))
        val automation =
            java.nio.file.Files
                .readString(fixtures.resolve("automation.sql"))
        test("review-121: part copy keeps huge integer sums numeric and timestamps zoned") {
            DuckDbRecipeEngine(
                listOf(
                    "CREATE TABLE amounts (n INTEGER, amount DECIMAL(12,2)); " +
                        "INSERT INTO amounts VALUES (10,1234.50),(2,2.25);",
                ),
            ).use { source ->
                DuckDbRecipeEngine(emptyList()).use { target ->
                    RecipeCrossStorage.copy(
                        source.connection,
                        target.connection,
                        "sums",
                        "SELECT SUM(n) AS n, SUM(amount) AS amount, " +
                            "TIMESTAMPTZ '2026-11-02 10:00:00+02' AS observed_at FROM amounts",
                        emptyMap(),
                    )
                    val rows =
                        JdbcStatements.query(
                            target.connection,
                            "SELECT n > 9 AS numeric_order, amount, observed_at FROM sums",
                            emptyMap(),
                        )
                    rows.rows.single()[0] shouldBe true
                    (rows.rows.single()[1] as java.math.BigDecimal).compareTo(java.math.BigDecimal("1236.75")) shouldBe
                        0
                    target.connection.createStatement().use { st ->
                        st.executeQuery("SELECT typeof(observed_at) FROM sums").use { rs ->
                            rs.next()
                            rs.getString(1) shouldBe "TIMESTAMP WITH TIME ZONE"
                        }
                    }
                }
            }
        }
        test(
            "review-121: unconstrained Postgres numeric aggregate survives a part copy",
        ).config(enabled = RecipePlanted.pgEnabled) {
            RecipePlanted
                .pgEngine(
                    "au_copy_numeric",
                    listOf("CREATE TABLE amounts (amount NUMERIC(12,2)); INSERT INTO amounts VALUES (1234.50),(2.25);"),
                ).use { source ->
                    DuckDbRecipeEngine(emptyList()).use { target ->
                        RecipeCrossStorage.copy(
                            source.connection,
                            target.connection,
                            "sums",
                            "SELECT SUM(amount) AS amount FROM amounts",
                            emptyMap(),
                        )
                        val result = JdbcStatements.query(target.connection, "SELECT amount FROM sums", emptyMap())
                        (result.rows.single().single() as java.math.BigDecimal).compareTo(
                            java.math.BigDecimal("1236.75"),
                        ) shouldBe
                            0
                    }
                }
        }
        test("review-121: preserved-side LEFT JOIN ON predicates stay in the combine") {
            val original =
                java.nio.file.Files.readString(
                    java.nio.file.Path.of(
                        "../ttrp-frontend/src/test/resources/recipes/monitor_inbound.ttrp",
                    ),
                )
            val source =
                original
                    .replace("JOIN   er.entity.obligation ob", "LEFT JOIN er.entity.obligation ob")
                    .replace(
                        "ob.entity = 'return_request_line'",
                        "l.quantity > 999 AND ob.entity = 'return_request_line'",
                    )
            val plan = RecipePlanted.planSource(source, "monitor_inbound.ttrp")
            DuckDbRecipeEngine(RecipePlanted.scripts).use { oracle ->
                DuckDbRecipeEngine(listOf(erp), "erp_db").use { erpSource ->
                    DuckDbRecipeEngine(listOf(automation), "automation_db").use { ledger ->
                        val conform = RecipeConform(numericDeltas = RecipePlanted.NUMERIC_DELTAS)
                        val expected = conform.runOn(oracle, plan, RecipePlanted.AS_OF)
                        val actual =
                            conform.runAcross(
                                mapOf("erp_db" to erpSource, "automation_db" to ledger),
                                plan,
                                RecipePlanted.AS_OF,
                            )
                        val comparison = conform.compare(listOf(expected, actual))
                        withClue(comparison.summary()) { comparison.exitCode shouldBe 0 }
                        (
                            expected.outputs
                                .getValue("awaiting.lines")
                                .rows
                                .isNotEmpty()
                        ) shouldBe true
                    }
                }
            }
        }
        for (recipe in RecipePlanted.RECIPES) {
            test("$recipe: two DuckDB attachments and combines equal the single-database oracle") {
                DuckDbRecipeEngine(RecipePlanted.scripts).use { oracle ->
                    DuckDbRecipeEngine(listOf(erp), "erp_db").use { source ->
                        DuckDbRecipeEngine(listOf(automation), "automation_db").use { ledger ->
                            val conform = RecipeConform(numericDeltas = RecipePlanted.NUMERIC_DELTAS)
                            val plan = RecipePlanted.plan(recipe)
                            val run =
                                conform.runAcross(
                                    mapOf("erp_db" to source, "automation_db" to ledger),
                                    plan,
                                    RecipePlanted.AS_OF,
                                )
                            RecipePlanted.check(recipe, RecipePlanted.rows(run))
                            val outcome = conform.compare(listOf(conform.runOn(oracle, plan, RecipePlanted.AS_OF), run))
                            withClue(outcome.summary()) { outcome.exitCode shouldBe 0 }
                        }
                    }
                }
            }
            test(
                "$recipe: two Postgres databases and DuckDB combines equal the single-database oracle",
            ).config(enabled = RecipePlanted.pgEnabled) {
                RecipePlanted.pgEngine("au_cross_oracle").use { oracle ->
                    RecipePlanted.pgEngine("au_cross_erp", listOf(erp)).use { source ->
                        RecipePlanted.pgEngine("au_cross_automation", listOf(automation)).use { ledger ->
                            val conform = RecipeConform(numericDeltas = RecipePlanted.NUMERIC_DELTAS)
                            val plan = RecipePlanted.plan(recipe)
                            val run =
                                conform.runAcross(
                                    mapOf("erp_db" to source, "automation_db" to ledger),
                                    plan,
                                    RecipePlanted.AS_OF,
                                )
                            RecipePlanted.check(recipe, RecipePlanted.rows(run))
                            val outcome = conform.compare(listOf(conform.runOn(oracle, plan, RecipePlanted.AS_OF), run))
                            withClue(outcome.summary()) { outcome.exitCode shouldBe 0 }
                        }
                    }
                }
            }
        }
    })
