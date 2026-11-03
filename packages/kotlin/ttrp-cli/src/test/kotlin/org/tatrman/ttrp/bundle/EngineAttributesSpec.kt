// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.bundle

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.tatrman.ttrp.graph.TtrpPipeline
import org.tatrman.ttrp.graph.capability.ExecutorManifestGate
import org.tatrman.ttrp.graph.capability.RecipeExecutorManifest
import org.tatrman.ttrp.graph.capability.RegistryRecipe
import org.tatrman.ttrp.project.TtrpManifestReader
import java.nio.file.Files
import java.nio.file.Path

/** CG-2: the engine seam is observable in the checker, catalog and manifest v3. */
class EngineAttributesSpec :
    StringSpec({
        fun locate(rel: String): Path {
            var dir: Path? = Path.of("").toAbsolutePath()
            while (dir != null) {
                val candidate = dir.resolve(rel)
                if (Files.exists(candidate)) return candidate
                dir = dir.parent
            }
            error("could not locate $rel")
        }
        val fixture = locate("packages/kotlin/ttrp-frontend/src/test/resources/recipes/hartland-engine")

        fun project(): Path {
            val dir = Files.createTempDirectory("cg2-project")
            Files.walk(fixture).use { paths ->
                paths.forEach { p ->
                    val target = dir.resolve(fixture.relativize(p).toString())
                    if (Files.isDirectory(p)) Files.createDirectories(target) else Files.copy(p, target)
                }
            }
            return dir
        }

        fun edit(
            dir: Path,
            file: String,
            before: String,
            after: String,
        ) {
            val path = dir.resolve(file)
            val source = Files.readString(path)
            check(before in source)
            Files.writeString(path, source.replace(before, after))
        }

        fun plan(
            dir: Path,
            name: String = "release_order",
        ) = TtrpPipeline(
            TtrpManifestReader.resolve(dir).manifest,
        ).plan(Files.readString(dir.resolve("$name.ttrp")), "$name.ttrp")

        fun build(
            dir: Path,
            name: String = "release_order",
        ) = BundleAssembler("1.0.0").build(
            source = Files.readString(dir.resolve("$name.ttrp")),
            fileName = "$name.ttrp",
            recipeDir = dir,
            pipelineManifest = TtrpManifestReader.resolve(dir).manifest,
            modelsRoot = dir.resolve("models"),
            outDir = Files.createTempDirectory("cg2-bundle"),
        )
        val automation = "models/hartland/automation/automation.ttrm"
        val world = "models/hartland/worlds/demo.ttrm"

        "startable_by checks every role in a list" {
            val dir = project()
            edit(dir, world, "startable_by: role.sales_manager", "startable_by: [role.sales_manager, role.nobody]")
            val result = plan(dir)
            result.ok shouldBe false
            result.diagnostics.single { it.id.id == "TTRP-ACT-002" }.message shouldContain "nobody"
        }
        "malformed startable_by stays fail-closed and the checker refuses it" {
            for (value in listOf("42", "[role.sales_manager, 42]", "sales_manager")) {
                val dir = project()
                edit(dir, world, "startable_by: role.sales_manager", "startable_by: $value")
                val checked = plan(dir)
                checked.ok shouldBe false
                checked.diagnostics.any { it.id.id == "TTRP-ACT-002" } shouldBe true
            }
        }
        "an empty startable_by list is preserved and egress_url must be a string" {
            val dir = project()
            edit(dir, world, "startable_by: role.sales_manager", "startable_by: []")
            val manifest = Json.parseToJsonElement(build(dir).manifest.toJson()).jsonObject
            manifest
                .getValue("recipe")
                .jsonObject
                .getValue("deployments")
                .jsonObject
                .getValue("release_event")
                .jsonObject
                .getValue("startableBy")
                .jsonArray.size shouldBe 0
            edit(dir, world, "egress_url: \"http://process-engine-egress/automation\"", "egress_url: 42")
            val checked = plan(dir)
            checked.ok shouldBe false
            checked.diagnostics.any { it.id.id == "TTRP-ACT-004" } shouldBe true
        }
        "action by checks the declared role" {
            val dir = project()
            edit(dir, automation, "by:         role.sales_manager", "by:         role.nobody")
            val result = plan(dir)
            result.ok shouldBe false
            result.diagnostics.single { it.id.id == "TTRP-ACT-001" }.message shouldContain "nobody"
        }
        "approval required without by is ACT-001" {
            val dir = project()
            edit(dir, automation, "by:         role.sales_manager", "")
            val result = plan(dir)
            result.ok shouldBe false
            result.diagnostics.single { it.id.id == "TTRP-ACT-001" }.message shouldContain "release_confirmed"
        }
        "due must be a whole non-negative duration with a supported unit" {
            for (due in listOf("\"tomorrow\"", "-1 days", "1.5 days", "2 bananas")) {
                val dir = project()
                edit(dir, automation, "due:        2 working_days", "due:        $due")
                val result = plan(dir)
                result.ok shouldBe false
                result.diagnostics.any { it.id.id == "TTRP-ACT-003" } shouldBe true
            }
        }
        "the four engine recipes check and bundle against the data-only process_engine manifest" {
            val type =
                RecipeExecutorManifest.parse(
                    requireNotNull(
                        javaClass.getResourceAsStream("/manifests/process_engine.ttrm"),
                    ).bufferedReader().use { it.readText() },
                )
            type.values("ports") shouldBe listOf("none", "key", "keys")
            type
                .entries("sinks")
                .keys
                .intersect(setOf("task", "ask", "start", "wait"))
                .shouldBeEmpty()
            type.entries("sinks")["user"] shouldBe "user task"
            type.entries("sinks")["call"] shouldBe "call activity"
            for (name in listOf("release_order", "decide_return", "monitor_inbound", "settle")) {
                val dir = project()
                val checked = plan(dir, name)
                checked.diagnostics.shouldBeEmpty()
                checked.ok shouldBe true
                val worldModel =
                    (
                        checked.frontendReport!!
                            .modelIndex!!
                            .snapshot.model.schemas["world"] as org.tatrman.ttr.metadata.model.WorldSchema
                    ).worlds.values.single {
                        it.qname ==
                            checked.bound!!.world.qname
                    }
                val programs =
                    checked.frontendReport!!
                        .modelIndex!!
                        .automationAll()
                        .filterIsInstance<org.tatrman.ttr.metadata.model.AutomationProgram>()
                        .associateBy { it.qname.name }
                val actions = RecipeActionDependenciesForTest.actions(checked)
                for (deployment in worldModel.deployments.filter {
                    it.target == "engine" &&
                        programs[it.program]?.recipe == name
                }) {
                    ExecutorManifestGate
                        .checkRecipe(
                            type,
                            programs.getValue(deployment.program),
                            deployment,
                            RegistryRecipe(
                                checked.recipe!!.kinds,
                                actions.mapValues { (_, a) ->
                                    a.sinks["process_engine"]
                                        ?: a.sinks["native"]
                                        ?: "no"
                                },
                            ),
                        ).shouldBeEmpty()
                }
                val bundled = build(dir, name)
                bundled.manifest.schemaVersion shouldBe 3
                RunManifest.fromJson(bundled.manifest.toJson()) shouldBe bundled.manifest
            }
        }
        "by due startable_by and egress_url survive catalog and manifest round-trips" {
            val dir = project()
            edit(dir, world, "startable_by: role.sales_manager", "startable_by: [role.sales_manager, role.ops]")
            val bundled = build(dir)
            val recipe =
                Json
                    .parseToJsonElement(bundled.manifest.toJson())
                    .jsonObject
                    .getValue("recipe")
                    .jsonObject
            val action =
                recipe
                    .getValue("actions")
                    .jsonObject
                    .getValue("release_confirmed")
                    .jsonObject
            action.getValue("by").jsonPrimitive.content shouldBe "role.sales_manager"
            action
                .getValue("due")
                .jsonObject
                .getValue("amount")
                .jsonPrimitive.content
                .toDouble() shouldBe 2.0
            action
                .getValue("due")
                .jsonObject
                .getValue("unit")
                .jsonPrimitive.content shouldBe "working_days"
            recipe
                .getValue("deployments")
                .jsonObject
                .getValue("release_event")
                .jsonObject
                .getValue("startableBy")
                .jsonArray
                .map { it.jsonPrimitive.content } shouldBe listOf("role.sales_manager", "role.ops")
            recipe
                .getValue(
                    "executors",
                ).jsonObject
                .getValue("engine")
                .jsonObject
                .getValue("egressUrl")
                .jsonPrimitive.content shouldBe
                "http://process-engine-egress/automation"
            com.networknt.schema.JsonSchemaFactory
                .getInstance(com.networknt.schema.SpecVersion.VersionFlag.V202012)
                .getSchema(javaClass.getResourceAsStream("/schemas/bundle-manifest-v3.schema.json")!!)
                .validate(
                    com.fasterxml.jackson.databind
                        .ObjectMapper()
                        .readTree(bundled.manifest.toJson()),
                ).shouldBeEmpty()
            val catalog = Files.readString(bundled.dir.resolve("catalog/release_order.ttrm"))
            catalog shouldContain "by: role.sales_manager"
            catalog shouldContain "due: 2 working_days"
            catalog shouldContain "startable_by: [role.sales_manager, role.ops]"
            catalog shouldContain "egress_url: \"http://process-engine-egress/automation\""
            org.tatrman.ttr.parser.loader.TtrLoader
                .parseString(catalog)
                .errors
                .shouldBeEmpty()
        }
    })

private object RecipeActionDependenciesForTest {
    fun actions(plan: TtrpPipeline.PlanResult) =
        requireNotNull(
            org.tatrman.ttrp.emit.recipe.RecipeActionDependencies
                .wiredActions(plan, requireNotNull(plan.recipe)),
        )
}
