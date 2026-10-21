// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.cli

import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.testing.test
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotBeEmpty
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
 * The AU-core-P2 phase-exit review (review-119), the CLI half: `ttrp check` refuses what `ttrp build` cannot bundle
 * (M5, TTRP-REC-080), `ttrp build --json` prints the diagnostics on failure (M8), and `ttrp conform` on a recipe
 * with errors reports them, never the placement-variant path (m11). Over a project around the fixture model.
 */
class RecipeCliReview119Spec :
    FunSpec({
        fun locate(rel: String): Path {
            var dir: Path? = Path.of("").toAbsolutePath()
            while (dir != null) {
                val c = dir.resolve(rel)
                if (Files.exists(c)) return c
                dir = dir.parent
            }
            error("could not locate $rel")
        }

        val fixtures = locate("packages/kotlin/ttrp-graph/src/test/resources/fixtures/recipes")
        val base = Files.readString(fixtures.resolve("base-order.ttrp"))
        val project =
            Files.createTempDirectory("ttrp-review119").also { dir ->
                Files.writeString(dir.resolve("modeler.toml"), "[ttrp]\nworld = \"hartland.worlds.demo\"\n")
                Files.createSymbolicLink(dir.resolve("models"), fixtures.resolve("model"))
            }

        /** [source] written into the project as [name]. */
        fun recipe(
            name: String,
            source: String,
        ): Path = project.resolve(name).also { Files.writeString(it, source) }

        fun check(file: Path): Pair<Int, String> {
            val lines = mutableListOf<String>()
            return TtrpCli.runCheck(file, { lines += it }) to lines.joinToString("\n")
        }

        fun ttrp() = TtrpCommand().subcommands(BuildCommand(), ConformCommand())

        /** `{ diagnostics: [ … ] }`: each diagnostic's id, every field present. */
        fun diagnosticIds(stdout: String): List<String> {
            val ds =
                Json
                    .parseToJsonElement(stdout)
                    .jsonObject
                    .getValue("diagnostics")
                    .jsonArray
            ds.forEach { d ->
                d.jsonObject.keys shouldBe setOf("id", "severity", "message", "file", "line", "column")
            }
            return ds.map {
                it.jsonObject
                    .getValue("id")
                    .jsonPrimitive.content
            }
        }

        val statuss = recipe("statuss.ttrp", base.replace("i.status = 'open'", "i.statuss = 'open'"))
        val badRoute = recipe("bad_route.ttrp", base.replace("hold_ar: n_due > 0", "hold_ar: n_duex > 0"))
        // the agent's answer asks AR before it releases (`ask` is approval: required — no REC-040)
        val turn =
            recipe(
                "turn.ttrp",
                Files
                    .readString(fixtures.resolve("negative/rec-040-turn-release.ttrp"))
                    .replace(
                        "verdict.release -> release { order: order_number }",
                        "verdict.release -> ask { who: role.ar, about: order_number, action: release { order: order_number } }",
                    ),
            )
        val unknownAction = recipe("unknown_action.ttrp", base.replace("-> release { order", "-> relase { order"))

        test("the base recipe checks clean and builds — the control") {
            check(recipe("base.ttrp", base)).first shouldBe 0
            val r = ttrp().test("build ${project.resolve("base.ttrp")} --json --out ${Files.createTempDirectory("b")}")
            r.statusCode shouldBe 0
            Json.parseToJsonElement(r.stdout).jsonObject.keys shouldBe setOf("bundle", "manifest", "diagnostics")
        }

        test("M5: check refuses a fragment column no row carries — REC-080 at the island, as build does") {
            val (code, out) = check(statuss)
            code shouldBe 1
            out shouldContain "TTRP-REC-080"
            out shouldContain "`overdue` does not compile"
            out shouldContain "statuss"
        }

        test("M5: check refuses a `turn` island — the bundle runs SQL statements only") {
            val (code, out) = check(turn)
            code shouldBe 1
            out shouldContain "TTRP-REC-080"
            out shouldContain "`care` is a `turn` island"
        }

        test("M8: build --json prints every diagnostic as an object — a checker error") {
            val r = ttrp().test("build $badRoute --json --out ${Files.createTempDirectory("b")}")
            r.statusCode shouldBe 1
            diagnosticIds(r.stdout).shouldNotBeEmpty()
        }

        test("M8: build --json prints the REC-080 of an island that does not compile — stdout is never empty") {
            val r = ttrp().test("build $statuss --json --out ${Files.createTempDirectory("b")}")
            r.statusCode shouldBe 1
            diagnosticIds(r.stdout) shouldContain "TTRP-REC-080"
        }

        test("m11: conform on a recipe with errors reports them — exit 2, no placement variants") {
            val r = ttrp().test("conform $unknownAction --engines duckdb --as-of 2026-11-02T08:00")
            r.statusCode shouldBe 2
            r.stderr shouldContain "TTRP-REC-001"
            r.stdout shouldNotContain "variant"
        }

        test("M5: conform refuses a recipe whose islands do not compile, as check does — before any engine runs") {
            val r = ttrp().test("conform $badRoute --engines duckdb --as-of 2026-11-02T08:00")
            r.statusCode shouldBe 2
            r.stderr shouldContain "TTRP-REC-080"
            r.stderr shouldContain "n_duex"
        }
    })
