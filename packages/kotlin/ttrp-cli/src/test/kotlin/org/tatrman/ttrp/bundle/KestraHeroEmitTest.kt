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
 * PL-P5.S3.T6/T7 — certify `org.tatrman:ttr-emit-kestra` against the REAL hero program (not a hand-built graph):
 * assemble the hero bundle through the Kestra plugin, prove the emitted `flow.yaml` has the native Kestra shape,
 * and run the H-6 determinism kit over its EmitRequest (the Q-6 certification gate — must PASS). The plugin is
 * resolved via `ServiceLoader` (`EmitPluginLoader.builtin`), so this also proves the in-tree registration wired
 * by ttrp-cli's `runtimeOnly` dependency + `BUILTIN_TARGETS` entry.
 */
class KestraHeroEmitTest :
    FunSpec({
        val heroSource = Files.readString(Paths.get("src/test/resources/fixtures/hero.ttrp"))

        fun buildHero(outDir: Path): BundleAssembler.BundleResult =
            BundleAssembler("1.0.0", EmitPluginLoader.builtin("kestra")).build(
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

        test("the hero bundle carries a native Kestra flow.yaml (no run.sh) with the real graph's shape") {
            val result = buildHero(Files.createTempDirectory("ttrp-kestra"))
            val dir = result.dir

            // The plugin owns the launcher: flow.yaml is present, run.sh is not (a different target was emitted).
            Files.exists(dir.resolve("flow.yaml")) shouldBe true
            Files.exists(dir.resolve("run.sh")) shouldBe false
            // Core-owned payloads are still written by the core, unchanged.
            Files.exists(dir.resolve("islands/acc_prep.sql")) shouldBe true
            Files.exists(dir.resolve("islands/crunch.py")) shouldBe true
            Files.exists(dir.resolve("manifest.json")) shouldBe true

            val flow = Files.readString(dir.resolve("flow.yaml"))
            flow shouldContain "io.kestra.plugin.core.flow.Parallel"
            // acc_prep is a PG island → a shell psql task; crunch is the Polars mainline → a python task.
            flow shouldContain "io.kestra.plugin.scripts.shell.Script"
            flow shouldContain "-v ON_ERROR_STOP=1 --no-psqlrc -f islands/acc_prep.sql"
            flow shouldContain "python3 islands/crunch.py"
            // Connections are secret placeholders, never material (B-4).
            flow shouldContain "TTR_CONN_ERP_PG"
            flow shouldContain "secret("
            // E-3-β native: no program-door URL anywhere.
            flow shouldNotContain "://"
            flow.lowercase() shouldNotContain "door"
        }

        test("emit-determinism PASSES for the kestra plugin over the hero request (Q-6 gate)") {
            val req = buildHero(Files.createTempDirectory("ttrp-kestra-det")).emitRequest!!
            val report = EmitDeterminismKit.check(EmitPluginLoader.builtin("kestra"), listOf(req, req))
            report.deterministic.shouldBeTrue()
            report.render() shouldContain "PASS"
        }
    })
