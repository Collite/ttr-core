// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.conform

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * AU-core-P2 · S2.4 T1 — the four `17` recipes **run on embedded DuckDB** (D-18/D-40: the native runner's second
 * engine): every statement of every output, rendered in the DuckDB dialect over the hartland sample (the same
 * `recipes/hartland/{schema,sample}.sql` the Postgres spec loads), and every route takes exactly the keys
 * [RecipePlanted] holds Postgres to. In memory, no server — always on. The Postgres-equal comparison (the seven
 * points, output by output) is [RecipeLivePgSpec]'s, where a Postgres is.
 */
class RecipeDuckDbSpec :
    FunSpec({
        lateinit var engine: DuckDbRecipeEngine

        beforeSpec { engine = DuckDbRecipeEngine(RecipePlanted.scripts) }
        afterSpec { engine.close() }

        for (recipe in RecipePlanted.RECIPES) {
            test("$recipe runs on DuckDB: every statement executes, every route takes its planted keys") {
                val run = RecipeConform().runOn(engine, RecipePlanted.plan(recipe), RecipePlanted.AS_OF)
                RecipePlanted.check(recipe, RecipePlanted.rows(run))
            }
        }

        test("the sample's calendar loads alike on both engines: 2026-10-28 a holiday, 487 days") {
            engine.connection.createStatement().use { st ->
                st.executeQuery("SELECT COUNT(*), SUM(CASE WHEN d_working_day THEN 1 ELSE 0 END) FROM date_dim").use {
                    it.next()
                    it.getLong(1) shouldBe 487L
                    // 487 days, 138 weekend days, one holiday
                    it.getLong(2) shouldBe 348L
                }
            }
        }
    })
