// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.emit.polars

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.tatrman.ttr.metadata.fixtures.MetadataFixtures
import org.tatrman.ttrp.graph.TtrpPipeline
import org.tatrman.ttrp.project.TtrpManifest

/**
 * AU-core-P1 (review-115 m12): a TTR-SQL select item's `AS alias` names the projected column on the graph path —
 * a SQL fragment the planner places on Polars emits `.alias("<name>")`, not an engine default like `_expr1`.
 */
class SqlAliasPolarsEmitTest :
    FunSpec({
        test("a SQL fragment's aliased computed column keeps its name in the Polars script") {
            val source =
                """
                uses world "acme.worlds.dev"

                raw = load(files.sales_2026, schema: sales_csv)

                container shaped(in sales, out rows) target polars ${"\"\"\""}sql
                    SELECT s.customer, s.amount * 2 AS doubled FROM sales s
                ${"\"\"\""}

                raw -> shaped.sales
                shaped.rows -> display(shaped_rows)
                """.trimIndent() + "\n"
            val plan =
                TtrpPipeline(
                    TtrpManifest(world = "acme.worlds.dev", manifestDir = MetadataFixtures.erpProjectRoot()),
                    MetadataFixtures.erpModelsRoot(),
                ).plan(source, "alias.ttrp")
            withClue(plan.diagnostics.joinToString("\n") { it.render() }) { plan.ok.shouldBeTrue() }
            val g = plan.graph!!
            val island = plan.exec!!.islands.single { it.engine == "polars" }
            val container = g.containers.getValue(island.id)
            val emitter = PolarsGraphEmitter(g, plan.bound!!)
            val script =
                PolarsIslandEmitter()
                    .emit(
                        island.name,
                        emitter.steps(container),
                        partitions = emitter.partitions(container),
                    ).text
            withClue(script) {
                script shouldContain ".alias(\"doubled\")"
                script shouldNotContain "_expr"
            }
        }
    })
