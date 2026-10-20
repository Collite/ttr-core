// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.conform

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.tatrman.ttrp.emit.recipe.RecipeStatements
import java.net.URI

/**
 * AU-core-P2 · S2.1 T6 / S2.2 T6 / S2.4 T1 — the four `17` recipes compiled and **run**: every statement of every
 * output (the DB rendering of the plan the bundle ships in E-R) executes on Postgres over the hartland sample
 * (`recipes/hartland/{schema,sample}.sql`, the recipe-checker fixture model's tables), and every route takes exactly
 * the keys [RecipePlanted] holds — the switch lowering, the detail joins, `latest`, the calendar counts and the derived
 * `:as_of` values, against the data. Then the same recipe on embedded DuckDB, every output compared with Postgres's
 * on the seven points ([RecipeConform]): DuckDB is Postgres-equal — but for a decimal ratio, which DuckDB divides in
 * DOUBLE (no exact decimal division), held to the declared [RecipePlanted.NUMERIC_DELTAS].
 *
 * Gated by `TTRP_CONFORM_PG=1`; the server from `TTR_CONN_ERP_PG` (`postgresql://user:pass@host:port/db`), else the
 * local ttrp-pg container (`spike.pg.*`, localhost:55432). It creates and drops its own database.
 */
class RecipeLivePgSpec :
    FunSpec({
        val enabled = System.getenv("TTRP_CONFORM_PG") == "1"
        val erpPg = System.getenv("TTR_CONN_ERP_PG")?.let { URI(it) }
        val userInfo = erpPg?.userInfo?.split(":", limit = 2)
        val server =
            erpPg?.let { "jdbc:postgresql://${it.host}:${if (it.port > 0) it.port else 5432}" }
                ?: System.getProperty("spike.pg.server", "jdbc:postgresql://localhost:55432")
        val user = userInfo?.getOrNull(0) ?: System.getProperty("spike.pg.user", "postgres")
        val password = userInfo?.getOrNull(1) ?: System.getProperty("spike.pg.password", "ttrp")

        lateinit var pg: PostgresRecipeEngine
        lateinit var duck: DuckDbRecipeEngine

        beforeSpec {
            if (!enabled) return@beforeSpec
            pg = PostgresRecipeEngine(server, user, password, "ttrp_recipes", RecipePlanted.scripts)
            duck = DuckDbRecipeEngine(RecipePlanted.scripts)
        }

        afterSpec {
            if (!enabled) return@afterSpec
            pg.close()
            duck.close()
        }

        for (recipe in RecipePlanted.RECIPES) {
            test("$recipe runs on Postgres: every route takes its planted keys").config(enabled = enabled) {
                val run = RecipeConform().runOn(pg, RecipePlanted.plan(recipe), RecipePlanted.AS_OF)
                RecipePlanted.check(recipe, RecipePlanted.rows(run))
            }

            test("$recipe: DuckDB equals Postgres on every output (the seven points)").config(enabled = enabled) {
                val plan = RecipePlanted.plan(recipe)
                val conform = RecipeConform(numericDeltas = RecipePlanted.NUMERIC_DELTAS)
                val outcome =
                    conform.compare(
                        listOf(
                            conform.runOn(pg, plan, RecipePlanted.AS_OF),
                            conform.runOn(duck, plan, RecipePlanted.AS_OF),
                        ),
                    )
                withClue(outcome.summary()) { outcome.exitCode shouldBe 0 }
                outcome.reports.size shouldBe RecipeStatements.plan(plan).size
            }
        }
    })
