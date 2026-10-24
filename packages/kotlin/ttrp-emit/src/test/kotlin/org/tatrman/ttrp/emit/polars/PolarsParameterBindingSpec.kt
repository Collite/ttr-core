// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.emit.polars

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.tatrman.ttr.metadata.fixtures.MetadataFixtures
import org.tatrman.ttrp.emit.EmitDiagnosticId
import org.tatrman.ttrp.emit.TtrpEmitException
import org.tatrman.ttrp.graph.TtrpPipeline
import org.tatrman.ttrp.project.TtrpManifest

class PolarsParameterBindingSpec :
    FunSpec({
        test("a non-recipe Polars island refuses an unbound parameter at emit time") {
            val source =
                """
                uses world "acme.worlds.dev"
                raw = load(files.sales_2026, schema: sales_csv)
                container shaped(in sales, out rows) target polars ${"\"\"\""}sql
                    SELECT s.customer, s.amount FROM sales s WHERE s.amount > :threshold
                ${"\"\"\""}
                raw -> shaped.sales
                shaped.rows -> display(shaped_rows)
                """.trimIndent()
            val plan =
                TtrpPipeline(
                    TtrpManifest(world = "acme.worlds.dev", manifestDir = MetadataFixtures.erpProjectRoot()),
                    MetadataFixtures.erpModelsRoot(),
                ).plan(source, "unbound.ttrp")
            withClue(plan.diagnostics.joinToString("\n")) { plan.ok shouldBe true }
            val graph = plan.graph!!
            val island = plan.exec!!.islands.single { it.engine == "polars" }
            val emitter = PolarsGraphEmitter(graph, plan.bound!!)
            val failure =
                shouldThrow<TtrpEmitException> {
                    PolarsIslandEmitter().emit(island.name, emitter.steps(graph.containers.getValue(island.id)))
                }
            failure.id shouldBe EmitDiagnosticId.UNSUPPORTED_NODE
        }
    })
