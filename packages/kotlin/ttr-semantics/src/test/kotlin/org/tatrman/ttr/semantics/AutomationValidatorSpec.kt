// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.semantics

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import org.tatrman.ttr.parser.loader.AutomationStdlib
import org.tatrman.ttr.parser.loader.ParseResult
import org.tatrman.ttr.parser.loader.TtrLoader
import org.tatrman.ttr.parser.model.EntityDef
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Grammar 0.16 — the automation validator (AU-core-P0 S0.2 T3). Design `17` §1–§2 (as written for
 * 0.16) validate clean against a hartland ER stub plus the implicit stdlib — the only findings are
 * the INFO "recipe not in the catalog yet" (the recipes are compiled at P2); each planted negative
 * fires exactly its code. Mirrors the TS `automation-validate.test.ts` on the same fixtures.
 */
class AutomationValidatorSpec :
    StringSpec({

        fun hartland(): List<ParseResult> =
            listOf("automation.ttrm", "programs.ttrm", "demo-world.ttrm").map {
                parse(locate("packages/kotlin/ttr-parser/src/test/resources/automation").resolve(it))
            } + parse(resource("automation/hartland-er-stub.ttrm"))

        "17 §1–§2 validate clean: only the pre-catalog recipe INFOs" {
            val diags = AutomationValidator.validate(hartland())
            diags.filter { it.severity != "info" } shouldBe emptyList()
            diags.map { it.code }.toSet() shouldBe setOf("ttr/automation-recipe-precatalog")
            diags.size shouldBe 8 // one per program
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
            "neg-01-deployment-program-and-process.ttrm" to listOf("ttr/automation-deployment-program-and-process"),
            "neg-02-duplicate-grant.ttrm" to listOf("ttr/automation-duplicate-grant"),
            "neg-03-event-not-schema.ttrm" to listOf("ttr/automation-event-not-schema"),
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
