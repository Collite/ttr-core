// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.conform

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/** The same planted rows and seven-point gate as the SQL engines, executed by real Polars. */
class RecipePolarsSpec :
    FunSpec({
        for (recipe in RecipePlanted.RECIPES) {
            test(
                "$recipe executes on Polars and agrees with DuckDB on every output",
            ).config(enabled = RecipePolars.enabled) {
                DuckDbRecipeEngine(RecipePlanted.scripts).use { source ->
                    val plan = RecipePlanted.plan(recipe)
                    val conform = RecipeConform(numericDeltas = RecipePlanted.NUMERIC_DELTAS)
                    val sql = conform.runOn(source, plan, RecipePlanted.AS_OF)
                    val polars = RecipePolars.run(source.connection, plan, RecipePlanted.AS_OF)
                    RecipePlanted.check(recipe, RecipePlanted.rows(polars))
                    val outcome = conform.compare(listOf(sql, polars))
                    check(outcome.exitCode == 0) {
                        outcome.toString() + "\n" +
                            sql.outputs.entries.joinToString(
                                "\n",
                            ) { (n, t) -> "$n: ${t.columns} vs ${polars.outputs[n]?.columns}" }
                    }
                    outcome.exitCode shouldBe 0
                }
            }
        }
    })
