// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.graph.recipe

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldEndWith
import org.tatrman.ttr.semantics.RemovedConstructs
import org.tatrman.ttrp.diagnostics.Severity
import org.tatrman.ttrp.diagnostics.TtrpDiagnosticId
import org.tatrman.ttrp.graph.TtrpPipeline
import org.tatrman.ttrp.project.TtrpManifest
import java.nio.file.Files
import java.nio.file.Path

/**
 * AU-core CG (D-142) T1 — a recipe build refuses every orchestration construct the grammar cut removed: one
 * `TTRP-CUT-nnn` per occurrence, at the construct, with the engine replacement as its suggestion. The planted model is
 * the Core fixture model as cut plus one of each removed construct (16 ids, 17 occurrences); the same recipes over the
 * model as cut check clean ([RecipeCheckerSpec]).
 */
class RemovedConstructSpec :
    StringSpec({
        /** The Core fixture model, copied, with [world] appended to `def world demo` and [automation] as `cut.ttrm`. */
        fun planted(
            world: String,
            automation: String?,
        ): Path {
            val root = Files.createTempDirectory("cut-planted")
            val src = RecipeFixtures.root.resolve("model")
            Files.walk(src).use { paths ->
                paths.forEach { p ->
                    val dst = root.resolve("model").resolve(src.relativize(p).toString())
                    if (Files.isDirectory(p)) Files.createDirectories(dst) else Files.copy(p, dst)
                }
            }
            val demo = root.resolve("model/hartland/worlds/demo.ttrm")
            val text = Files.readString(demo)
            Files.writeString(demo, text.substring(0, text.lastIndexOf('}')) + world + "}\n")
            automation?.let { Files.writeString(root.resolve("model/hartland/automation/cut.ttrm"), it) }
            return root
        }

        val everyWorldCut =
            """
            |    def deployment cut_native  { program: morning_release, target: native, run_as: caller, trigger: schedule "06:00 Europe/Prague" }
            |    def deployment cut_connect { program: morning_release, target: n8n, run_as: caller, connectors: { deliver: "smtp" } }
            |    def process cut_flow { instance: order.order_number, steps: [ def step s { program: morning_release, port: key } ] }
            |    def deployment cut_proc    { process: cut_flow, target: n8n, run_as: caller }
            |
            """.trimMargin()

        val everyAutomationCut =
            """
            |package hartland.automation
            |model automation
            |
            |def program cut_program { recipe: release_order, port: event, event: schema.cut_event, mode: on_event,
            |                          as_of: event, deliver: immediate, schedule: "06:00 Europe/Prague", ask_due: 2 days }
            |def schema cut_event { columns: [def column order_number { type: order.order_number }] }
            |def action cut_mail  { columns: [def column order { type: order.order_number }], sinks: { native: deliver }, approval: none }
            |def action cut_task  { columns: [def column order { type: order.order_number }], sinks: { native: task }, approval: required,
            |                       on_due: hold { order: order, department: "Sales" } }
            |def action cut_start { columns: [def column order { type: order.order_number }], sinks: { native: start }, approval: none }
            |def action cut_emit  { columns: [def column order { type: order.order_number }], sinks: { native: emit }, approval: none }
            |def role cut_role    { resolve: keycloak_group "cut", delivery: { email: "cut@hartland.example" } }
            |
            """.trimMargin()

        fun plan(
            root: Path,
            source: String,
            fileName: String,
        ) = TtrpPipeline(TtrpManifest(world = "hartland.worlds.demo", manifestDir = root), root.resolve("model"))
            .plan(source, fileName)

        fun cut(r: TtrpPipeline.PlanResult) = r.diagnostics.filter { it.id.id.startsWith("TTRP-CUT-") }

        "a model with removed constructs fails the recipe: one TTRP-CUT per occurrence, with the replacement" {
            val root = planted(everyWorldCut, everyAutomationCut)
            val source =
                Files
                    .readString(RecipeFixtures.shared.resolve("release_order.ttrp"))
                    .replace(
                        "due: 2 working_days }",
                        "due: 2 working_days, on_due: hold { order: order_number, department: \"Sales\" } }",
                    )
            val r = plan(root, source, "release_order.ttrp")
            r.ok shouldBe false
            cut(r).map { it.id.id } shouldContainExactlyInAnyOrder
                listOf(
                    "TTRP-CUT-001", // def process cut_flow
                    "TTRP-CUT-002", // cut_proc { process: … } — a deployment that does not load (no `program:`)
                    "TTRP-CUT-003", // cut_native { trigger: … }
                    "TTRP-CUT-004", // cut_native { target: native }
                    "TTRP-CUT-005", // cut_connect { connectors: … }
                    "TTRP-CUT-006", // cut_program { deliver: … }
                    "TTRP-CUT-007", // cut_program { schedule: … }
                    "TTRP-CUT-008", // cut_program { ask_due: … }
                    "TTRP-CUT-009", // cut_program { port: event }
                    "TTRP-CUT-010", // cut_program { event: … }
                    "TTRP-CUT-011", // cut_mail { sinks.native: deliver }
                    "TTRP-CUT-012", // cut_task { sinks.native: task }
                    "TTRP-CUT-013", // cut_start { sinks.native: start }
                    "TTRP-CUT-014", // cut_emit { sinks.native: emit }
                    "TTRP-CUT-015", // cut_task { on_due: … }
                    "TTRP-CUT-015", // the recipe's wiring `on_due:`
                    "TTRP-CUT-016", // cut_role { delivery: … }
                )
            for (d in cut(r)) {
                d.severity shouldBe Severity.ERROR
                d.message shouldContain "removed in grammar 0.22 (D-142)"
                d.suggestedAlternative shouldBe
                    RemovedConstructs.Cut.entries
                        .single { "TTRP-${it.ref}" == d.id.id }
                        .replacement
            }
            cut(r).single { it.id == TtrpDiagnosticId.CUT_001 }.let {
                it.message shouldContain "process `cut_flow`"
                it.location.file shouldEndWith "demo.ttrm"
            }
            cut(r)
                .filter {
                    it.id == TtrpDiagnosticId.CUT_015
                }.map { it.location.file.substringAfterLast('/') } shouldContainExactlyInAnyOrder
                listOf("cut.ttrm", "release_order.ttrp")
        }

        "a half-migrated world (its process deleted, the deployment left) still fails, each cut at its own entry" {
            val line =
                "    def deployment cut_left { process: gone_flow, target: native, run_as: caller, " +
                    "trigger: schedule \"06:00 Europe/Prague\" }"
            val root = planted("$line\n", null)
            val r =
                plan(root, Files.readString(RecipeFixtures.shared.resolve("release_order.ttrp")), "release_order.ttrp")
            r.ok shouldBe false
            cut(r).map { it.id.id } shouldContainExactly listOf("TTRP-CUT-002", "TTRP-CUT-004", "TTRP-CUT-003")
            val at = Files.readAllLines(root.resolve("model/hartland/worlds/demo.ttrm")).indexOf(line) + 1
            for ((d, entry) in cut(r).zip(listOf("process:", "native", "trigger:"))) {
                d.message shouldContain "deployment `cut_left`"
                d.location.file shouldEndWith "demo.ttrm"
                d.location.line shouldBe at
                d.location.column shouldBe line.indexOf(entry)
            }
        }

        "the hero recipes over the model as cut say nothing of removed constructs" {
            for (f in RecipeFixtures.SEVENTEEN) cut(RecipeFixtures.sharedRecipe(f)).shouldBeEmpty()
        }
    })
