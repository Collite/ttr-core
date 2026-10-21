// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.emit.sql

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.tatrman.ttr.metadata.fixtures.MetadataFixtures
import org.tatrman.ttrp.graph.TtrpPipeline
import org.tatrman.ttrp.project.TtrpManifest

/**
 * review-119 B5 — the text = integer key coercion is a recipe statement's (`obligation.key` is text), never an
 * ordinary program's: a program comparing a text column with a number emits the comparison exactly as written, as it
 * did before AU-core-P2 (MSSQL and DuckDB compare such pairs numerically; a cast to text would change the rows).
 */
class TextKeysScopeSpec :
    FunSpec({
        test("an ordinary program's text = number comparison is emitted as written — no CAST") {
            val source =
                """
                uses world "acme.worlds.dev"

                container c(out o) target erp_pg {
                    load(files.sales_2026, schema: sales_csv) -> filter(customer = amount) -> o
                }
                """.trimIndent()
            val plan =
                TtrpPipeline(
                    TtrpManifest(world = "acme.worlds.dev", manifestDir = MetadataFixtures.erpProjectRoot()),
                    MetadataFixtures.erpModelsRoot(),
                ).plan(source, "probe.ttrp")
            plan.ok.shouldBeTrue()
            val island = plan.exec!!.islands.single { it.engine == "erp_pg" }
            val sql = SqlIslandEmitter(plan.bound!!).emit(island, plan.graph!!).text
            sql shouldContain "\"customer\" = \"amount\""
            sql shouldNotContain "CAST"
        }
    })
