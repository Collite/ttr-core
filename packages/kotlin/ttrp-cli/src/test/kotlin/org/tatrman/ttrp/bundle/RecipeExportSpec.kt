// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.bundle

import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.testing.test
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.tatrman.ttr.metadata.source.FileBasedSource
import org.tatrman.ttr.metadata.source.LocalFsStorage
import org.tatrman.ttrp.cli.EmitCommand
import org.tatrman.ttrp.cli.TtrpCommand
import org.tatrman.ttrp.emit.spi.TtrEmitPlugin
import org.tatrman.ttrp.graph.capability.RegistryGrid
import org.tatrman.ttrp.graph.capability.RecipeManifestSource
import org.tatrman.ttrp.graph.capability.RegistryRecipe
import org.tatrman.ttrp.graph.capability.RegistryBundle
import org.tatrman.ttrp.graph.capability.ExecutorManifestGate
import java.nio.file.Files
import java.nio.file.Path
import java.util.ServiceLoader

class RecipeExportSpec :
    StringSpec({
        fun locate(rel: String): Path {
            var p: Path? = Path.of("").toAbsolutePath()
            while (p != null) {
                val c = p.resolve(rel)
                if (Files.exists(c)) return c
                p = p.parent
            }
            error(rel)
        }
        val plugins =
            ServiceLoader.load(TtrEmitPlugin::class.java).toList().filter {
                it.targetId in
                    setOf("native", "n8n_l1", "camunda_7", "camunda_8", "python_client", "python_offline", "sql_job")
            }
        val manifests = RecipeManifestSource(plugins.map { it.executorTypeManifest() })
        val root = locate("packages/kotlin/ttrp-graph/src/test/resources/fixtures/recipes/model")
        val snapshot = FileBasedSource("test", 0, LocalFsStorage("test", root)).load()
        val world = snapshot.worlds.values.single { it.qname.name == "demo" }
        val programs =
            snapshot.automation.values
                .filterIsInstance<org.tatrman.ttr.metadata.model.AutomationProgram>()
                .associateBy {
                    it.qname.name
                }
        val program = programs.getValue("morning_release")
        val deployment = world.deployment("release_native_morning")!!

        fun build(recipeName: String): BundleAssembler.BundleResult {
            val source = locate("packages/kotlin/ttrp-frontend/src/test/resources/recipes/$recipeName.ttrp")
            return BundleAssembler("test").build(
                source = Files.readString(source),
                fileName = source.toString(),
                recipeDir = source.parent,
                pipelineManifest =
                    org.tatrman.ttrp.project.TtrpManifest(
                        world = "hartland.worlds.demo",
                        manifestDir = root.parent,
                    ),
                modelsRoot = root,
                outDir = Files.createTempDirectory("recipe-export-spec"),
            )
        }
        val model =
            org.tatrman.ttr.metadata.reconcile
                .ModelReconciler(
                    org.tatrman.ttr.metadata.model
                        .ModelDescriptor("test", "test"),
                ).reconcile(listOf(snapshot))
                .model
        val actions = model.objectByQname().values.filterIsInstance<org.tatrman.ttr.metadata.model.AutomationAction>()

        fun golden(
            name: String,
            text: String,
        ) {
            val path = locate("packages/kotlin/ttrp-cli/src/test/resources").resolve("exporters/$name.txt")
            if (System.getenv("UPDATE_AU_EXPORT_GOLDENS") ==
                "1"
            ) {
                Files.createDirectories(path.parent)
                Files.writeString(path, text + "\n")
            }
            Files.readString(path) shouldBe text + "\n"
        }
        "SPI v2 projects every H-1 deployment field and native passes the H-6 determinism kit" {
            val bundle = build("release_order")
            val d = world.deployment("release_n8n")!!
            val graph =
                org.tatrman.ttrp.emit.recipe.RecipeGraphProjector.project(
                    bundle.manifest,
                    world,
                    programs.getValue(d.program!!),
                    d,
                    actions,
                )
            val source = org.tatrman.ttr.parser.model.SourceLocation.UNKNOWN
            val connector =
                org.tatrman.ttr.parser.model.AutoValue.Obj(
                    listOf(
                        org.tatrman.ttr.parser.model.AutoEntry(
                            "ask",
                            org.tatrman.ttr.parser.model.AutoValue
                                .Str("tasks", false, source),
                            source,
                        ),
                    ),
                    source,
                )
            val connected =
                org.tatrman.ttrp.emit.recipe.RecipeGraphProjector.project(
                    bundle.manifest,
                    world,
                    programs.getValue(d.program!!),
                    d.copy(properties = d.properties + ("connectors" to connector)),
                    actions,
                )
            connected.deployment.connectors shouldBe mapOf("ask" to "tasks")
            golden("release_n8n_recipe_graph", graph.toString().replace(", ", ",\n"))
            val native = world.deployment("release_native_morning")!!
            val nativeGraph =
                org.tatrman.ttrp.emit.recipe.RecipeGraphProjector.project(
                    bundle.manifest,
                    world,
                    program,
                    native,
                    actions,
                )
            org.tatrman.ttrp.conform.EmitDeterminismKit
                .checkRecipes(
                    EmitPluginLoader.builtin("native"),
                    listOf(
                        org.tatrman.ttrp.emit.spi
                            .RecipeEmitRequest(nativeGraph, "{}", "readme", "{}"),
                    ),
                ).deterministic shouldBe
                true
        }
        "island projection preserves verified waves and refuses incomplete or duplicate island schedules" {
            val bundle = build("release_order").manifest
            val d = world.deployment("release_n8n")!!

            fun project(manifest: RunManifest) =
                org.tatrman.ttrp.emit.recipe.RecipeGraphProjector.project(
                    manifest,
                    world,
                    programs.getValue(d.program!!),
                    d,
                    actions,
                )
            project(bundle).islandWaves shouldBe bundle.waves
            io.kotest.assertions.throwables.shouldThrow<IllegalArgumentException> {
                project(bundle.copy(waves = emptyList()))
            }
            io.kotest.assertions.throwables.shouldThrow<IllegalArgumentException> {
                project(bundle.copy(waves = bundle.waves + listOf(listOf(bundle.islands.first().name))))
            }
            io.kotest.assertions.throwables.shouldThrow<IllegalArgumentException> {
                project(bundle.copy(waves = bundle.waves + listOf(listOf("unknown"))))
            }
        }
        "process projection resolves all three recipes with effective ports under the parent deployment" {
            val process = world.processes.single { it.qname.name == "return_line_flow" }
            val d = world.deployment("returns_n8n")!!
            val bundles =
                process.steps.associate { step ->
                    val program = programs.getValue(step.program!!)
                    step.name to build(program.recipe!!).manifest
                }
            val graphs =
                process.steps.associate { step ->
                    val program = programs.getValue(step.program!!).copy(port = step.port)
                    step.name to
                        org.tatrman.ttrp.emit.recipe.RecipeGraphProjector.project(
                            bundles.getValue(step.name),
                            world,
                            program,
                            d,
                            actions,
                        )
                }
            val first = process.steps.first()

            fun project(steps: Map<String, org.tatrman.ttrp.emit.spi.RecipeGraph>) =
                org.tatrman.ttrp.emit.recipe.RecipeGraphProjector.project(
                    bundles.getValue(first.name),
                    world,
                    programs.getValue(first.program!!).copy(port = first.port),
                    d,
                    actions,
                    process = process,
                    stepGraphs = steps,
                )
            val projected = project(graphs).process!!
            projected.steps.map { it.graph!!.recipe.name } shouldBe listOf("decide_return", "monitor_inbound", "settle")
            projected.steps.forEach { step ->
                step.graph!!.deployment.name shouldBe "returns_n8n"
                step.graph!!.program.port shouldBe "key"
                step.graph!!.process shouldBe null
            }
            io.kotest.assertions.throwables
                .shouldThrow<IllegalArgumentException> { project(graphs - first.name) }
            io.kotest.assertions.throwables.shouldThrow<IllegalArgumentException> {
                project(
                    graphs + (
                        first.name to
                            graphs.getValue(first.name).copy(
                                program = graphs.getValue(first.name).program.copy(port = "event"),
                            )
                    ),
                )
            }
        }
        "seven plugin-owned TTR-M manifests load through metadata world objects" {
            manifests.all().map { it.id } shouldBe
                listOf("camunda_7", "camunda_8", "n8n_l1", "native", "python_client", "python_offline", "sql_job")
            manifests.load("n8n-l1")!!.entries("idempotency") shouldBe
                mapOf("on_event" to "derived", "batch" to "execution_id")
            manifests.load("n8n_l1")!!.entries("nodeVersions")["httpRequest"] shouldBe "4.2"
            golden("n8n_l1_manifest", plugins.single { it.targetId == "n8n_l1" }.executorTypeManifest().trimEnd())
        }
        "all five cell refusals name the unsupported capability" {
            fun check(
                type: String,
                p: org.tatrman.ttr.metadata.model.AutomationProgram = program,
                d: org.tatrman.ttr.metadata.model.WorldDeployment = deployment,
                recipe: RegistryRecipe = RegistryRecipe(listOf("sql"), emptyMap()),
            ) = ExecutorManifestGate.checkRecipe(manifests.load(type)!!, p, d, recipe).map { it.id.name }
            check("n8n_l1", program.copy(mode = "windowed")) shouldBe listOf("TGT_001")
            check(
                "python_offline",
                d = deployment.copy(ungoverned = true),
                recipe = RegistryRecipe(listOf("foreign:python"), emptyMap()),
            ) shouldBe
                listOf("TGT_002")
            check("sql_job", recipe = RegistryRecipe(listOf("sql"), mapOf("confirm" to "ask"))) shouldBe
                listOf("TGT_003", "TGT_005")
            check("camunda_7", d = deployment.copy(granularity = "islands")) shouldBe listOf("TGT_004")
            check("python_offline") shouldBe listOf("TGT_005")
        }
        "the H-1 and H-2 registry grid includes every deployment and process step" {
            val recipes =
                listOf("release_order", "decide_return", "monitor_inbound", "settle").associateWith { name ->
                    val recipe = build(name).manifest.recipe!!
                    RegistryRecipe(
                        recipe.kinds,
                        emptyMap(),
                        recipe.wiring.map { it.action }.distinct().associateWith { action ->
                            actions.single { it.qname.name == action }.sinks
                        },
                    )
                }
            val grid = RegistryGrid(manifests).compute(world, RegistryBundle(programs, recipes))
            grid.size shouldBe 14
            grid.all { it.ok } shouldBe true
            golden(
                "registry_grid",
                grid.joinToString("\n") { "${it.deployment}|${it.program}|${it.type}|${it.mode}|${it.ok}" },
            )
        }
        "native CLI export is deterministic and contains no launcher or run calls" {
            val project = Files.createTempDirectory("recipe-export-project")
            for (folder in listOf("automation", "worlds")) {
                val target = project.resolve("models/hartland/$folder")
                Files.createDirectories(target)
                Files.list(root.resolve("hartland/$folder")).use { paths ->
                    paths
                        .filter {
                            it.fileName.toString() !=
                                "checker-probes.ttrm"
                        }.forEach { Files.copy(it, target.resolve(it.fileName)) }
                }
            }
            Files.writeString(
                project.resolve("models/hartland/writable.ttrm"),
                """
                package hartland
                model er
                def entity order { attributes: [def attribute order_number {type: int, isKey: true}, def attribute state {type: text}, def attribute held_for {type: text}] }
                def entity return_request_line { attributes: [def attribute line_id {type: int, isKey: true}, def attribute state {type: text}, def attribute way {type: text}] }
                def entity reorder_proposal { attributes: [def attribute id {type: int, isKey: true}] }
                def entity winback_mail { attributes: [def attribute id {type: int, isKey: true}] }
                """.trimIndent(),
            )
            Files.writeString(project.resolve("modeler.toml"), "[ttrp]\nworld = \"hartland.worlds.demo\"\n")
            val recipe = locate("packages/kotlin/ttrp-frontend/src/test/resources/recipes/release_order.ttrp")
            Files.copy(recipe, project.resolve("release_order.ttrp"))
            val bundle =
                BundleAssembler("test").build(
                    source = Files.readString(recipe),
                    fileName = recipe.toString(),
                    recipeDir = recipe.parent,
                    pipelineManifest =
                        org.tatrman.ttrp.project.TtrpManifest(
                            world = "hartland.worlds.demo",
                            manifestDir = root.parent,
                        ),
                    modelsRoot = root,
                    outDir = Files.createTempDirectory("recipe-export-bundle"),
                )
            val first = Files.createTempDirectory("native-export-a")
            val second = Files.createTempDirectory("native-export-b")

            fun emit(out: Path) =
                TtrpCommand()
                    .subcommands(
                        EmitCommand(),
                    ).test(
                        listOf(
                            "emit",
                            "--deployment",
                            "release_native_morning",
                            "--project",
                            project.toString(),
                            "--bundle",
                            bundle.dir.toString(),
                            "--out",
                            out.toString(),
                            "--json",
                        ),
                    )
            val a = emit(first)
            io.kotest.assertions.withClue(a.output) { a.statusCode shouldBe 0 }
            val b = emit(second)
            io.kotest.assertions.withClue(b.output) { b.statusCode shouldBe 0 }
            val folder = "release_native_morning.native.export"
            val files =
                Files.list(first.resolve(folder)).use {
                    it
                        .map { p ->
                            p.fileName.toString()
                        }.sorted()
                        .toList()
                }
            files shouldBe listOf("README.md", "conformance.json", "manifest.json")
            files.forEach { name ->
                Files.readString(first.resolve(folder).resolve(name)) shouldBe
                    Files.readString(second.resolve(folder).resolve(name))
            }
            Files.readString(first.resolve(folder).resolve("manifest.json")) shouldContain "\"calls\": []"
        }
    })
