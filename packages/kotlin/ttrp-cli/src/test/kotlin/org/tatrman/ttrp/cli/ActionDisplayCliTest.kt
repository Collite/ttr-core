// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.cli

import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.testing.test
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path

/**
 * `ttrp check` / `ttrp build` on action displays (grammar 0.14 `def schema`), through the clikt commands the
 * shipped binary runs: a clean program checks and builds (one manifest entry per source); a missing schema
 * column / a duplicated ordinary display name fail `check` with their TTRP-DSP id; the comma cast form is a
 * TTRP-PRS-001 diagnostic on both commands (it used to escape as a NullPointerException).
 */
class ActionDisplayCliTest :
    FunSpec({
        val programs: Path =
            run {
                val rel = Path.of("packages/kotlin/ttrp-frontend/src/test/resources/display/programs")
                var dir: Path? = Path.of("").toAbsolutePath()
                while (dir != null) {
                    if (Files.isDirectory(dir.resolve(rel))) return@run dir.resolve(rel)
                    dir = dir.parent
                }
                error("could not locate the display fixture programs")
            }

        fun ttrp() = TtrpCommand().subcommands(BuildCommand(), CheckCommand())

        test("check: a clean action-display program exits 0 with no diagnostics") {
            val r = ttrp().test("check ${programs.resolve("notify.ttrp")}")
            withClue(r.output) { r.statusCode shouldBe 0 }
            r.stdout.trim() shouldBe ""
        }

        test("check: a missing schema column exits 1 with TTRP-DSP-001") {
            val r = ttrp().test("check ${programs.resolve("negative/missing-column.ttrp")}")
            r.statusCode shouldBe 1
            r.stdout shouldContain "TTRP-DSP-001"
            r.stdout shouldContain "`subject`"
        }

        test("check and build: chain-to-port missing a required column is TTRP-DSP-001 (sql-text and Polars)") {
            listOf("negative/chain-port-missing.ttrp", "negative/chain-port-missing-local.ttrp").forEach { p ->
                val c = ttrp().test("check ${programs.resolve(p)}")
                withClue("$p\n${c.output}") {
                    c.statusCode shouldBe 1
                    c.stdout shouldContain "TTRP-DSP-001"
                    c.stdout shouldContain "`subject`"
                }
                val out = Files.createTempDirectory("ttrp-cli-chain-port")
                val b = ttrp().test("build ${programs.resolve(p)} --out $out")
                withClue("$p\n${b.output}") {
                    b.statusCode shouldBe 1
                    b.output shouldContain "TTRP-DSP-001"
                }
            }
        }

        test("check: a duplicated ordinary display name exits 1 with TTRP-DSP-004") {
            val r = ttrp().test("check ${programs.resolve("negative/duplicate-evidence.ttrp")}")
            r.statusCode shouldBe 1
            r.stdout shouldContain "TTRP-DSP-004"
        }

        test("check and build: `cast(x, string)` is TTRP-PRS-001, not an exception") {
            val c = ttrp().test("check ${programs.resolve("negative/comma-cast.ttrp")}")
            c.statusCode shouldBe 1
            c.stdout shouldContain "TTRP-PRS-001"
            c.stdout shouldContain "malformed cast"
            val out = Files.createTempDirectory("ttrp-cli-cast")
            val b = ttrp().test("build ${programs.resolve("negative/comma-cast.ttrp")} --out $out")
            b.statusCode shouldBe 1
            b.output shouldContain "TTRP-PRS-001"
            b.output shouldNotContain "NullPointerException"
        }

        test("build: two sources into one action display → two manifest entries, each with its own source") {
            val out = Files.createTempDirectory("ttrp-cli-action")
            val r = ttrp().test("build ${programs.resolve("notify.ttrp")} --out $out")
            withClue(r.output) { r.statusCode shouldBe 0 }
            val manifest =
                Json
                    .parseToJsonElement(
                        Files.readString(out.resolve("notify.bundle/manifest.json")),
                    ).jsonObject
            val displays = manifest.getValue("displays").jsonArray.map { it.jsonObject }
            displays.map { it.getValue("name").jsonPrimitive.content } shouldContainExactly
                listOf("notify", "notify", "overdue")
            displays.map {
                val s = it.getValue("source").jsonObject
                s.getValue("island").jsonPrimitive.content + "." + s.getValue("port").jsonPrimitive.content
            } shouldContainExactly listOf("review.late", "review.large", "review.overdue")
        }
    })
