// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.emit.sql

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.tatrman.ttr.metadata.fixtures.MetadataFixtures
import org.tatrman.ttrp.emit.GoldenSupport
import org.tatrman.ttrp.graph.TtrpPipeline
import org.tatrman.ttrp.project.TtrpManifest
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * S3.5 T3.5.4 — the PG-crunch runtime script. Feeds the **real** crunch@erp_pg per-output SQL
 * (from [SqlIslandEmitter.emitOutputs]) into [PgAdbcIslandEmitter] and asserts the emitted Python
 * (a) matches its golden and (b) compiles (`py_compile`, when python3 is on PATH — CI runs it).
 * This is the offline hygiene gate for the runtime island; the live PG execution is the gated
 * `HeroConformLiveTest` (docker-CI, `TTRP_CONFORM_PG=1`).
 */
class PgAdbcIslandEmitterTest :
    FunSpec({
        val heroSource = Files.readString(Paths.get("src/test/resources/fixtures/hero.ttrp"))

        fun crunchScript(): String {
            val plan =
                TtrpPipeline(
                    TtrpManifest(world = "acme.worlds.dev", manifestDir = MetadataFixtures.erpProjectRoot()),
                    MetadataFixtures.erpModelsRoot(),
                ).plan(heroSource, "hero.ttrp", targetOverrides = mapOf("crunch" to "erp_pg"))
            val island = plan.exec!!.islands.single { it.name == "crunch" }
            val outputs = SqlIslandEmitter(plan.bound!!).emitOutputs(island, plan.graph!!)
            return PgAdbcIslandEmitter().emit(
                connEnv = "TTR_CONN_ERP_PG",
                // Same-engine `accounts` handoff: acc_prep's fragment SQL inlined as a temp table.
                sqlTemps =
                    listOf(
                        PgAdbcIslandEmitter.SqlTemp(
                            "accounts",
                            "SELECT account_id, branch_code, region\nFROM erp.accounts\nWHERE status = 'ACTIVE'",
                        ),
                    ),
                // sales CSV → typed temp (declared sales_csv schema: customer/region string, amount decimal).
                csvTemps =
                    listOf(
                        PgAdbcIslandEmitter.CsvTemp(
                            "sales_2026",
                            "files/sales_2026.csv",
                            listOf(
                                PgAdbcIslandEmitter.pgColumn("customer", "string"),
                                PgAdbcIslandEmitter.pgColumn("region", "string"),
                                PgAdbcIslandEmitter.pgColumn("amount", "float"),
                            ),
                        ),
                    ),
                outputs =
                    listOf(
                        PgAdbcIslandEmitter.Output(outputs.getValue("result").text, "out/main_result.arrow"),
                        PgAdbcIslandEmitter.Output(outputs.getValue("low").text, "staging/low.arrow"),
                    ),
            )
        }

        test("crunch ADBC script matches golden") {
            val script = crunchScript()
            script shouldContain "adbc_driver_postgresql"
            script shouldContain "fetch_arrow_table"
            script shouldContain "adbc_ingest(\"sales_2026\", _t_sales_2026, mode=\"create\", temporary=True)"
            GoldenSupport.assertMatchesGolden(script, "pg_adbc/hero_crunch.py")
        }

        test("a CSV decimal column reads at its declared precision/scale, a bare one at decimal128(19, 2)") {
            PgAdbcIslandEmitter.pgColumn("a", "decimal").arrowType shouldBe "_pa.decimal128(19, 2)"
            PgAdbcIslandEmitter.pgColumn("a", "{type:decimal,length:12,precision:4}").arrowType shouldBe
                "_pa.decimal128(12, 4)"
            PgAdbcIslandEmitter.pgColumn("a", "numeric").arrowType shouldBe "_pa.decimal128(19, 2)"
            PgAdbcIslandEmitter.pgColumn("a", "number").arrowType shouldBe "_pa.float64()"
            PgAdbcIslandEmitter.pgColumn("a", "string").arrowType shouldBe "_pa.string()"
        }

        test("crunch ADBC script compiles (py_compile)") {
            val python3 = which("python3")
            if (python3 == null) {
                System.err.println("SKIP: python3 not on PATH — py_compile skipped (CI runs it)")
                return@test
            }
            val tmp = Files.createTempFile("ttrp_pg_adbc", ".py")
            Files.writeString(tmp, crunchScript())
            val proc =
                ProcessBuilder(python3.toString(), "-m", "py_compile", tmp.toString())
                    .redirectErrorStream(true)
                    .start()
            val out = proc.inputStream.readBytes().decodeToString()
            val code = proc.waitFor()
            Files.deleteIfExists(tmp)
            io.kotest.assertions
                .withClue(out) { code shouldBe 0 }
        }
    })

private fun which(cmd: String): Path? =
    System.getenv("PATH").orEmpty().split(java.io.File.pathSeparator).map { Paths.get(it, cmd) }.firstOrNull {
        Files.isExecutable(it)
    }
