// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.bundle

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.tatrman.ttr.metadata.fixtures.MetadataFixtures
import org.tatrman.ttrp.conform.EmitDeterminismKit
import org.tatrman.ttrp.project.TtrpManifest
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * PL-P5.S4.T6 — certify `org.tatrman:ttr-emit-airflow3` against the REAL hero program through the whole core
 * pipeline. The erp fixture world declares no airflow3 executor, so `BundleAssembler` populates `executorInstance`
 * as empty → the plugin emits its NATIVE binding (E-3-β). Proves: the core wiring resolves+passes executorInstance,
 * the real graph renders a native DAG, and the determinism kit certifies it (Q-6). Plugin resolved via ServiceLoader
 * (the ttrp-cli `runtimeOnly` + `BUILTIN_TARGETS` registration).
 */
class Airflow3HeroEmitTest :
    FunSpec({
        val heroSource = Files.readString(Paths.get("src/test/resources/fixtures/hero.ttrp"))

        fun buildHero(outDir: Path): BundleAssembler.BundleResult =
            BundleAssembler("1.0.0", EmitPluginLoader.builtin("airflow3")).build(
                source = heroSource,
                fileName = "hero.ttrp",
                pipelineManifest =
                    TtrpManifest(
                        world = "acme.worlds.dev",
                        manifestDir = MetadataFixtures.erpProjectRoot(),
                    ),
                modelsRoot = MetadataFixtures.erpModelsRoot(),
                outDir = outDir,
            )

        test("the hero bundle carries a native Airflow DAG (dag.py, no run.sh) with the real graph's shape") {
            val result = buildHero(Files.createTempDirectory("ttrp-af3"))
            val dir = result.dir

            Files.exists(dir.resolve("dag.py")) shouldBe true
            Files.exists(dir.resolve("run.sh")) shouldBe false
            Files.exists(dir.resolve("islands/acc_prep.sql")) shouldBe true
            Files.exists(dir.resolve("islands/crunch.py")) shouldBe true

            val dag = Files.readString(dir.resolve("dag.py"))
            dag shouldContain "from airflow.sdk import DAG"
            // acc_prep (PG) → BashOperator psql; crunch (Polars) → PythonOperator running the island script.
            dag shouldContain "acc_prep = BashOperator("
            dag shouldContain "-v ON_ERROR_STOP=1 --no-psqlrc -f islands/acc_prep.sql"
            dag shouldContain "op_args=[\"islands/crunch.py\"]"
            dag shouldContain "\"TTR_CONN_ERP_PG\": \"{{ conn.ttr_conn_erp_pg.get_uri() }}\""
            // E-3-β native: no platform reference anywhere.
            dag shouldNotContain "://"
            dag.lowercase() shouldNotContain "door"
        }

        test("emit-determinism PASSES for the airflow3 plugin over the hero request (Q-6 gate)") {
            val req = buildHero(Files.createTempDirectory("ttrp-af3-det")).emitRequest!!
            // The core populated executorInstance from the world (empty here — no airflow3 executor declared).
            req.executorInstance.text shouldBe ""
            val report = EmitDeterminismKit.check(EmitPluginLoader.builtin("airflow3"), listOf(req, req))
            report.deterministic.shouldBeTrue()
            report.render() shouldContain "PASS"
        }
    })
