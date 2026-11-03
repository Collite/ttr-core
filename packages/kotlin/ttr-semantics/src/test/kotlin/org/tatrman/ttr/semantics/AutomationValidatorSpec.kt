// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.semantics

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.tatrman.ttr.parser.loader.AutomationStdlib
import org.tatrman.ttr.parser.loader.ParseResult
import org.tatrman.ttr.parser.loader.TtrLoader
import org.tatrman.ttr.parser.model.EntityDef
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Grammar 0.16 — the automation validator (AU-core-P0 S0.2 T3). Design `17` §1–§2 as written for 0.16 parse, and
 * since grammar 0.22 (D-142) refuse exactly their orchestration constructs, one `ttr/automation-removed` each; the
 * Core fixture model as cut validates clean against the implicit stdlib — the only findings are the INFO "recipe not
 * in the catalog yet"; each planted negative fires exactly its code. Mirrors the TS `automation-validate.test.ts` on
 * the same fixtures.
 */
class AutomationValidatorSpec :
    StringSpec({

        "CG-2 resolves action by and every startable_by role" {
            val action =
                TtrLoader.parseString(
                    """
                    model automation
                    def role manager { members: ["maya"] }
                    def action release { columns: [def column x { type: text }], sinks: { native: writer }, approval: required, by: role.nobody }
                    def program p { recipe: r, port: none, mode: batch }
                    """.trimIndent(),
                )
            val world =
                TtrLoader.parseString(
                    """
                    model world
                    def world demo {
                        def executor engine { extends: native }
                        def deployment d { program: p, target: engine, run_as: caller, startable_by: [role.manager, role.absent] }
                    }
                    """.trimIndent(),
                )
            val findings = AutomationValidator.validate(listOf(action, world)).filter { it.severity != "info" }
            findings.map { it.code } shouldContainExactly
                listOf("ttr/automation-unknown-reference", "ttr/automation-unknown-reference")
            val missing =
                TtrLoader.parseString(
                    "model automation\ndef action a { columns: [def column x { type: text }], sinks: { native: writer }, approval: required }",
                )
            AutomationValidator.validate(listOf(missing)).single { it.message.contains("by") }.code shouldBe
                "ttr/automation-missing-property"
        }

        fun hartland(): List<ParseResult> =
            listOf("automation.ttrm", "programs.ttrm", "demo-world.ttrm").map {
                parse(locate("packages/kotlin/ttr-parser/src/test/resources/automation").resolve(it))
            } + parse(resource("automation/hartland-er-stub.ttrm"))

        fun cutIds(diags: List<AutomationDiagnostic>): List<String> =
            diags.filter { it.code == RemovedConstructs.CODE }.map {
                Regex("CUT-\\d{3}").find(it.message)?.value ?: error("no TTRP-CUT id in ${it.message}")
            }

        "D-142: 17 §1–§2 as written refuse exactly their removed constructs, one finding each" {
            val diags = AutomationValidator.validate(hartland())
            diags.filter { it.severity != "info" }.map { it.code }.toSet() shouldBe setOf(RemovedConstructs.CODE)
            diags.filter { it.code == RemovedConstructs.CODE }.forEach { it.severity shouldBe "error" }
            cutIds(diags).groupingBy { it }.eachCount() shouldBe
                mapOf(
                    "CUT-001" to 1, // def process return_line_flow
                    "CUT-002" to 1, // returns_n8n { process: … }
                    "CUT-003" to 4, // trigger: schedule … on four deployments
                    "CUT-004" to 8, // target: native
                    "CUT-006" to 2, // deliver: (digest, immediate)
                    "CUT-007" to 4, // schedule:
                    "CUT-009" to 3, // port: event
                    "CUT-010" to 3, // event: schema.…
                    "CUT-011" to 2, // sinks.native: deliver
                    "CUT-012" to 2, // sinks.native: task
                    "CUT-013" to 1, // sinks.native: start
                    "CUT-016" to 2, // role delivery:
                )
            val process = diags.single { it.message.contains("CUT-001") }
            process.message shouldContain "process 'return_line_flow'"
            process.message shouldContain "model it in the process engine"
        }

        "D-142: the constructs 17 does not use are refused too (ask_due, emit, on_due, connectors)" {
            val project =
                listOf(
                    parse(resource("automation/hartland-er-stub.ttrm")),
                    parse(resource("automation/negative/common.ttrm")),
                    parse(resource("automation/removed/rest.ttrm")),
                    parse(resource("automation/removed/rest-world.ttrm")),
                )
            val diags = AutomationValidator.validate(project)
            diags.filter { it.severity != "info" }.map { it.code }.toSet() shouldBe setOf(RemovedConstructs.CODE)
            cutIds(diags) shouldContainExactlyInAnyOrder
                listOf("CUT-008", "CUT-014", "CUT-015", "CUT-005")
        }

        "D-142: `target: native` is refused only where the world declares no executor named `native`" {
            val project =
                listOf(
                    parse(resource("automation/hartland-er-stub.ttrm")),
                    parse(resource("automation/negative/common.ttrm")),
                    parse(resource("automation/removed/native-executor.ttrm")),
                )
            val diags = AutomationValidator.validate(project).filter { it.severity != "info" }
            diags.map { it.code } shouldContainExactly listOf(RemovedConstructs.CODE)
            cutIds(diags) shouldContainExactly listOf("CUT-004")
            diags.single().source.line shouldBe 13 // world `bare`'s deployment
        }

        "D-142: a removed key written twice is refused at each entry" {
            val project =
                listOf(
                    parse(resource("automation/hartland-er-stub.ttrm")),
                    parse(resource("automation/negative/common.ttrm")),
                    parse(resource("automation/removed/duplicate-key.ttrm")),
                )
            val cuts = AutomationValidator.validate(project).filter { it.code == RemovedConstructs.CODE }
            cutIds(cuts) shouldContainExactly listOf("CUT-007", "CUT-007")
            cuts.map { it.source.line } shouldContainExactly listOf(6, 7)
        }

        "the Core fixture model as cut validates clean: only the pre-catalog recipe INFOs" {
            val root = locate("packages/kotlin/ttrp-graph/src/test/resources/fixtures/recipes/model/hartland")
            val files =
                Files.walk(root).use { s -> s.filter { it.toString().endsWith(".ttrm") }.sorted().toList() }
            val diags = AutomationValidator.validate(files.map { parse(it) })
            diags.filter { it.severity != "info" } shouldBe emptyList()
            diags.map { it.code }.toSet() shouldBe setOf("ttr/automation-recipe-precatalog")
        }

        "the stdlib joins a project that declares `model automation` (obligation.state resolves)" {
            AutomationStdlib.isNeededBy(hartland()) shouldBe true
            val withoutStdlib = AutomationValidator.validate(hartland(), includeStdlib = false)
            // `fulfil` transitions obligation.state: without the stdlib the entity is unknown.
            withoutStdlib.any {
                it.code == "ttr/automation-unknown-attribute" &&
                    it.message.contains(
                        "obligation",
                    )
            } shouldBe
                true
        }

        listOf(
            "neg-01-deployment-without-program.ttrm" to listOf("ttr/automation-missing-property"),
            "neg-02-duplicate-grant.ttrm" to listOf("ttr/automation-duplicate-grant"),
            "neg-03-not-a-schema.ttrm" to listOf("ttr/automation-event-not-schema"),
            "neg-04-transitions-unknown-state.ttrm" to listOf("ttr/states-unknown-state"),
            "neg-05-action-transition-unknown-state.ttrm" to listOf("ttr/states-unknown-state"),
            "neg-06-writable-unknown-entity.ttrm" to listOf("ttr/automation-writable-unknown-entity"),
            "neg-07-set-malformed.ttrm" to listOf("ttr/automation-malformed-property"),
            "neg-08-transition-not-allowed.ttrm" to listOf("ttr/states-transition-not-allowed"),
        ).forEach { (name, expected) ->
            "negative $name fires exactly ${expected.joinToString()}" {
                val project =
                    listOf(
                        parse(resource("automation/hartland-er-stub.ttrm")),
                        parse(resource("automation/negative/common.ttrm")),
                        parse(resource("automation/negative/$name")),
                    )
                val findings = AutomationValidator.validate(project).filter { it.severity != "info" }
                findings.map { it.code } shouldContainExactlyInAnyOrder expected
            }
        }

        // review-114 — the contract spellings, per-world references, the validator gaps, stdlib precedence.
        listOf(
            "s01-grant-spellings.ttrm" to emptyList(),
            "s02-duplicate-member.ttrm" to listOf("ttr/automation-duplicate-member"),
            "s03-identity-other-world.ttrm" to listOf("ttr/automation-unknown-reference"),
            "s04-durations.ttrm" to listOf("ttr/automation-invalid-value", "ttr/automation-invalid-value"),
            "s05-int-fraction.ttrm" to listOf("ttr/automation-invalid-value"),
            "s06-misplaced.ttrm" to listOf("ttr/automation-misplaced-property"),
            "s07-duplicate-transition.ttrm" to listOf("ttr/states-duplicate-transition"),
            "s08-writable-non-id.ttrm" to listOf("ttr/automation-invalid-value"),
            "s09-duplicate-property.ttrm" to listOf("ttr/automation-duplicate-property"),
            "s10-user-obligation" to emptyList(),
        ).forEach { (name, expected) ->
            "review-114 $name fires exactly ${expected.ifEmpty { listOf("nothing") }.joinToString()}" {
                val scenario = resource("automation/review-114/$name")
                val files =
                    if (Files.isDirectory(scenario)) {
                        Files.list(scenario).use { s -> s.filter { it.toString().endsWith(".ttrm") }.sorted().toList() }
                    } else {
                        listOf(scenario)
                    }
                val project =
                    listOf(
                        parse(resource("automation/hartland-er-stub.ttrm")),
                        parse(resource("automation/negative/common.ttrm")),
                    ) + files.map { parse(it) }
                val findings = AutomationValidator.validate(project).filter { it.severity != "info" }
                findings.map { it.code } shouldContainExactlyInAnyOrder expected
            }
        }

        "the Kotlin and TS stdlib copies are byte-identical, and the stdlib parses clean" {
            val ts = Files.readString(locate("packages/semantics/src/stock").resolve("automation-stdlib.ttrm"))
            AutomationStdlib.source shouldBe ts
            AutomationStdlib.result.errors shouldBe emptyList()
            AutomationStdlib.result.definitions.map { it.name } shouldContainExactly
                listOf("obligation", "state_history", "approvals")
            val obligation =
                AutomationStdlib.result.definitions
                    .filterIsInstance<EntityDef>()
                    .first()
            obligation.attributes.single { it.name == "state" }.states shouldContainExactly
                listOf("open", "fulfilled", "overdue", "cancelled", "reopened")
            obligation.attributes.any { it.name == "created_at" } shouldBe true
        }
    })

private fun parse(path: Path): ParseResult = TtrLoader.parseFile(path).also { it.errors shouldBe emptyList() }

private fun resource(path: String): Path =
    Paths.get(
        object {}
            .javaClass.classLoader
            .getResource(path)
            ?.toURI() ?: error("missing test resource: $path"),
    )

private fun locate(rel: String): Path {
    var dir: Path? = Paths.get("").toAbsolutePath()
    while (dir != null) {
        val candidate = dir.resolve(rel)
        if (Files.isDirectory(candidate)) return candidate
        dir = dir.parent
    }
    error("could not locate $rel")
}
