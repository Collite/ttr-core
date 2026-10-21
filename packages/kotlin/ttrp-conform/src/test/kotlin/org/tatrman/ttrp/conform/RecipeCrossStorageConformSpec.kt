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
