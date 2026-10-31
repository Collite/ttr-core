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
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

class RecipeExportSpec :
    StringSpec({
        val temporaryDirectories = mutableListOf<Path>()

        fun temporaryDirectory(prefix: String): Path =
            Files.createTempDirectory(prefix).also { temporaryDirectories.add(it) }
        afterSpec { temporaryDirectories.asReversed().forEach { it.toFile().deleteRecursively() } }

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
                outDir = temporaryDirectory("recipe-export-spec"),
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

        fun exportProject(): Path {
            val project = temporaryDirectory("n8n-export-project")
            for (folder in listOf("automation", "worlds")) {
                val target = project.resolve("models/hartland/$folder")
                Files.createDirectories(target)
                Files.list(root.resolve("hartland/$folder")).use { paths ->
                    paths.filter { it.fileName.toString() != "checker-probes.ttrm" }.forEach {
                        Files.copy(it, target.resolve(it.fileName))
                    }
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
            Files.writeString(
                project.resolve("modeler.toml"),
                "[ttrp]\nworld = \"hartland.worlds.demo\"\n",
            )
            return project
        }
        "n8n CLI export renders the compiled H-1 recipe and publishes the exact workflow calls" {
            val project = exportProject()
            val bundle = build("release_order")
            val out = temporaryDirectory("n8n-real-h1")
            val result =
                TtrpCommand().subcommands(EmitCommand()).test(
                    listOf(
                        "emit",
                        "--deployment",
                        "release_n8n",
                        "--project",
                        project.toString(),
                        "--bundle",
                        bundle.dir.toString(),
                        "--out",
                        out.toString(),
                    ),
                )
            io.kotest.assertions.withClue(result.output) { result.statusCode shouldBe 0 }
            val folder = out.resolve("release_n8n.n8n_l1.export")
            val workflow =
                kotlinx.serialization.json.Json
                    .parseToJsonElement(
                        Files.readString(folder.resolve("workflow.json")),
                    ).jsonObject
            val manifest =
                kotlinx.serialization.json.Json
                    .parseToJsonElement(
                        Files.readString(folder.resolve("manifest.json")),
                    ).jsonObject
            golden("release_n8n_workflow", Files.readString(folder.resolve("workflow.json")).trimEnd())
            manifest["calls"] shouldBe
                workflow
                    .getValue("meta")
                    .jsonObject
                    .getValue("tatrman")
                    .jsonObject["calls"]
            Files.readString(folder.resolve("workflow.json")) shouldContain "programFingerprint"
            Files.readString(folder.resolve("README.md")) shouldContain "tatrman-automation"
            Files.readString(folder.resolve("README.md")) shouldContain "Stale when"
        }
        "compiled morning_release renders the full H-1 batch digest golden" {
            val bundle = build("release_order")
            val graph =
                org.tatrman.ttrp.emit.recipe.RecipeGraphProjector.project(
                    bundle.manifest,
                    world,
                    program,
                    deployment.copy(target = "n8n"),
                    actions,
                )
            val result =
                EmitPluginLoader.builtin("n8n_l1").emitRecipe(
                    org.tatrman.ttrp.emit.spi
                        .RecipeEmitRequest(graph, "{}", "stale rule", "{}"),
                )
            golden(
                "morning_release_workflow",
                result.files
                    .getValue("workflow.json")
                    .decodeToString()
                    .trimEnd(),
            )
        }
        "H-2 export refuses missing customer address and still verifies every process bundle" {
            val project = exportProject()
            val bundles = temporaryDirectory("n8n-h2-bundles")
            listOf("decide_return", "monitor_inbound", "settle").forEach { recipe ->
                val compiled = build(recipe)
                val fixture = compiled.dir.resolve("tests/shared.yaml")
                Files.createDirectories(fixture.parent)
                Files.writeString(fixture, "recipe: $recipe\n")
                val sha =
                    "sha256:" +
                        java.security.MessageDigest
                            .getInstance("SHA-256")
                            .digest(Files.readAllBytes(fixture))
                            .joinToString("") { "%02x".format(it) }
                val manifest =
                    compiled.manifest.copy(
                        recipe = compiled.manifest.recipe!!.copy(tests = listOf("tests/shared.yaml")),
                        files = compiled.manifest.files + ("tests/shared.yaml" to sha),
                    )
                Files.writeString(compiled.dir.resolve("manifest.json"), manifest.toJson())
                compiled.dir.toFile().copyRecursively(bundles.resolve(recipe).toFile())
            }
            val out = temporaryDirectory("n8n-real-h2")
            val result =
                TtrpCommand().subcommands(EmitCommand()).test(
                    listOf(
                        "emit",
                        "--deployment",
                        "returns_n8n",
                        "--project",
                        project.toString(),
                        "--bundle",
                        bundles.toString(),
                        "--out",
                        out.toString(),
                    ),
                )
            result.statusCode shouldBe 2
            result.output shouldContain "emailSend action notify_customer needs exactly one addressing column"
            Files.exists(out.resolve("returns_n8n.n8n_l1.export")) shouldBe false
            // A stale/missing step refuses the whole package, before any output directory exists.
            Files.delete(bundles.resolve("settle/manifest.json"))
            val failedOut = temporaryDirectory("n8n-missing-step")
            val failed =
                TtrpCommand().subcommands(EmitCommand()).test(
                    listOf(
                        "emit",
                        "--deployment",
                        "returns_n8n",
                        "--project",
                        project.toString(),
                        "--bundle",
                        bundles.toString(),
                        "--out",
                        failedOut.toString(),
                    ),
                )
            failed.statusCode shouldBe 2
            failed.output shouldContain "settle/manifest.json"
            Files.exists(failedOut.resolve("returns_n8n.n8n_l1.export")) shouldBe false
        }
        "R9 every process step contributes fingerprints and conformance to the manifest" {
            // Synthetic three-recipe envelopes use the H-1 action shape; real H-2 remains a refusal above.
            val project = exportProject()
            val bundles = temporaryDirectory("n8n-process-manifests")
            val compiled = build("release_order")
            val recipes = listOf("decide_return", "monitor_inbound", "settle")
            for (recipe in recipes) {
                compiled.dir.toFile().copyRecursively(bundles.resolve(recipe).toFile())
                val fixture = bundles.resolve("$recipe/tests/shared.yaml")
                Files.createDirectories(fixture.parent)
                Files.writeString(fixture, "recipe: $recipe\n")
                val sha =
                    "sha256:" +
                        java.security.MessageDigest
                            .getInstance(
                                "SHA-256",
                            ).digest(Files.readAllBytes(fixture))
                            .joinToString("") {
                                "%02x".format(it)
                            }
                val manifest =
                    compiled.manifest.copy(
                        recipe = compiled.manifest.recipe!!.copy(name = recipe, tests = listOf("tests/shared.yaml")),
                        files =
                            compiled.manifest.files + ("tests/shared.yaml" to sha),
                    )
                Files.writeString(bundles.resolve("$recipe/manifest.json"), manifest.toJson())
            }

            fun emit(): kotlinx.serialization.json.JsonObject {
                val out = temporaryDirectory("n8n-process-output")
                val result =
                    TtrpCommand()
                        .subcommands(
                            EmitCommand(),
                        ).test(
                            listOf(
                                "emit",
                                "--deployment",
                                "returns_n8n",
                                "--project",
                                project.toString(),
                                "--bundle",
                                bundles.toString(),
                                "--out",
                                out.toString(),
                            ),
                        )
                io.kotest.assertions.withClue(result.output) { result.statusCode shouldBe 0 }
                for (recipe in recipes) {
                    Files.readString(out.resolve("returns_n8n.n8n_l1.export/tests/$recipe/shared.yaml")) shouldBe
                        "recipe: $recipe\n"
                }
                return kotlinx.serialization.json.Json
                    .parseToJsonElement(
                        Files.readString(out.resolve("returns_n8n.n8n_l1.export/manifest.json")),
                    ).jsonObject
            }
            val before = emit()
            val path = bundles.resolve("settle/manifest.json")
            val old = RunManifest.fromJson(Files.readString(path))
            val r = old.recipe!!
            Files.writeString(
                path,
                old
                    .copy(
                        recipe =
                            r.copy(
                                fingerprints =
                                    r.fingerprints.copy(
                                        values =
                                            "sha256:" + "f".repeat(64),
                                    ),
                            ),
                    ).toJson(),
            )
            val after = emit()
            val a = before.getValue("steps").jsonArray
            val b = after.getValue("steps").jsonArray
            a.map {
                it.jsonObject
                    .getValue("recipe")
                    .jsonPrimitive.content
            } shouldBe recipes
            a.take(2) shouldBe b.take(2)
            (a[2] == b[2]) shouldBe false
            b.forEach {
                it.jsonObject.containsKey("cell") shouldBe true
                it.jsonObject.containsKey("toolchain") shouldBe
                    true
            }
        }
        "R9 a plugin without process capability refuses before rendering or reading bundles" {
            val project = exportProject()
            val path = project.resolve("models/hartland/worlds/demo.ttrm")
            Files.writeString(
                path,
                Files
                    .readString(
                        path,
                    ).replace("process: return_line_flow, target: n8n", "process: return_line_flow, target: native"),
            )
            val out = temporaryDirectory("native-process-refusal")
            val result =
                TtrpCommand()
                    .subcommands(
                        EmitCommand(),
                    ).test(
                        listOf(
                            "emit",
                            "--deployment",
                            "returns_n8n",
                            "--project",
                            project.toString(),
                            "--bundle",
                            project.resolve("nonexistent").toString(),
                            "--out",
                            out.toString(),
                        ),
                    )
            result.statusCode shouldBe 2
            result.output shouldContain "native does not support process exports"
            Files.list(out).use { it.count() shouldBe 0L }
        }
        "R5 an event schema column at colliding with the clock refuses export" {
            val project = exportProject()
            val path = project.resolve("models/hartland/automation/programs.ttrm")
            var text =
                Files
                    .readString(
                        path,
                    ).replace(
                        "def program release_on_placement   { recipe: release_order,  port: key",
                        "def program release_on_placement   { recipe: release_order,  event: schema.line_received, port: event",
                    )
            // Keep the mutation precise even if the fixture's alignment whitespace changes.
            text =
                Regex(
                    "(def program release_on_placement\\s+\\{[^}]*?)port: key",
                ).replace(text, "$1event: schema.line_received, port: event")
            text =
                text.replace(
                    "def schema line_received    { columns: [",
                    "def schema line_received    { columns: [def column at { type: text },",
                )
            Files.writeString(path, text)
            val result =
                TtrpCommand()
                    .subcommands(
                        EmitCommand(),
                    ).test(
                        listOf(
                            "emit",
                            "--deployment",
                            "release_n8n",
                            "--project",
                            project.toString(),
                            "--bundle",
                            project.resolve("unused").toString(),
                            "--out",
                            temporaryDirectory("event-refusal").toString(),
                        ),
                    )
            result.statusCode shouldBe 2
            result.output shouldContain "event schema column `at` collides with the port clock"
        }
        "R2 the customer-addressed winback action shape refuses until its producer supplies an address column" {
            val bundle = build("decide_return")
            val d = world.deployment("returns_n8n")!!
            val graph =
                org.tatrman.ttrp.emit.recipe.RecipeGraphProjector.project(
                    bundle.manifest,
                    world,
                    programs.getValue("decide_on_request"),
                    d,
                    actions,
                )
            val emit = actions.single { it.qname.name == "notify_customer" }
            emit.columns.any { it.name == "to" } shouldBe false
            val customer =
                graph.copy(
                    recipe = graph.recipe.copy(name = "winback"),
                    wirings =
                        graph.wirings
                            .filter {
                                it.action ==
                                    "notify_customer"
                            }.map { it.copy(action = "emit") },
                    actions =
                        mapOf(
                            "emit" to graph.actions.getValue("notify_customer"),
                        ),
                )
            io.kotest.assertions.throwables
                .shouldThrow<IllegalArgumentException> {
                    EmitPluginLoader
                        .builtin(
                            "n8n_l1",
                        ).emitRecipe(
                            org.tatrman.ttrp.emit.spi
                                .RecipeEmitRequest(customer, "{}", "", "{}"),
                        )
                }.message!! shouldContain
                "emailSend action emit needs exactly one addressing column"
        }
        "native CLI export is deterministic and contains no launcher or run calls" {
            val project = temporaryDirectory("recipe-export-project")
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
                    outDir = temporaryDirectory("recipe-export-bundle"),
                )
            val first = temporaryDirectory("native-export-a")
            val second = temporaryDirectory("native-export-b")

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
