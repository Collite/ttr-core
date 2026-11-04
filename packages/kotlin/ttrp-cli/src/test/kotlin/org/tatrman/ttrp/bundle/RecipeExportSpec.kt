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
                    programs.getValue(d.program),
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
                    programs.getValue(d.program),
                    d.copy(properties = d.properties + ("connectors" to connector)),
                    actions,
                )
            // D-142 took the connectors: an authored `connectors:` (refused by the checkers) never reaches the SPI
            connected.deployment.connectors shouldBe emptyMap()
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
                    programs.getValue(d.program),
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
        "the H-1 and H-2 registry grid includes every deployment" {
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
            // one cell per deployment: D-142 took `returns_n8n`'s process steps; `decide_n8n` is H-2 on n8n
            grid.size shouldBe 12
            golden(
                "registry_grid",
                grid.joinToString("\n") { "${it.deployment}|${it.program}|${it.type}|${it.mode}|${it.ok}" },
            )
            // D-142: `ask` and `notify` lost their native sinks. The Server records them as action rows; python_client
            // inherited them through the native fallback and now refuses until the actions name a python sink.
            grid.filterNot { it.ok }.map {
                "${it.deployment}: ${it.diagnostics.joinToString { d ->
                    d.message
                }}"
            } shouldBe
                listOf(
                    "release_py: release_py: action ask sink no not supported by python_client, " +
                        "release_py: action notify sink no not supported by python_client",
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
        "the CLI rejects nonpositive template versions before loading a project" {
            for (version in listOf("0", "-1", "not-a-number")) {
                val result =
                    TtrpCommand().subcommands(EmitCommand()).test(
                        listOf("emit", "--deployment", "release_bpmn", "--template-version", version),
                    )
                (result.statusCode != 0) shouldBe true
                result.output shouldContain "template-version"
            }
        }
        "Camunda CLI exports the real H-1 in both dialects and structural replay needs no Server" {
            for (target in listOf("camunda_7", "camunda_8")) {
                val project = exportProject()
                val worldPath = project.resolve("models/hartland/worlds/demo.ttrm")
                Files.writeString(
                    worldPath,
                    Files.readString(worldPath).replace("extends: camunda_8", "extends: $target"),
                )
                val bundleModels = temporaryDirectory("camunda-bundle-models")
                root.toFile().copyRecursively(bundleModels.toFile(), overwrite = true)
                Files.writeString(bundleModels.resolve("hartland/worlds/demo.ttrm"), Files.readString(worldPath))
                val source = locate("packages/kotlin/ttrp-frontend/src/test/resources/recipes/release_order.ttrp")
                val bundle =
                    BundleAssembler("test").build(
                        source = Files.readString(source),
                        fileName = source.toString(),
                        recipeDir = source.parent,
                        pipelineManifest =
                            org.tatrman.ttrp.project.TtrpManifest(
                                world = "hartland.worlds.demo",
                                manifestDir = project,
                            ),
                        modelsRoot = bundleModels,
                        outDir = temporaryDirectory("camunda-h1-bundle"),
                    )
                // World choices need each deployed program's own recipe metadata.
                Files.createDirectories(project.resolve("recipes"))
                Files.list(source.parent).use { paths ->
                    paths.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".ttrp") }.forEach {
                        Files.copy(it, project.resolve("recipes").resolve(it.fileName))
                    }
                }
                Files.delete(project.resolve("models/hartland/writable.ttrm"))
                // Preserve source text, relocating declared package hartland into its loader directory.
                val queriesRoot = locate("packages/kotlin/ttrp-cli/src/test/resources/fixtures/hartland-queries")
                Files.walk(queriesRoot).use { paths ->
                    paths
                        .filter {
                            Files.isRegularFile(it) && it.fileName.toString().endsWith(".ttrm")
                        }.forEach { path ->
                            val name = queriesRoot.relativize(path).toString().replace('/', '_')
                            Files.copy(path, project.resolve("models/hartland/$name"))
                        }
                }
                // Supply the stock vocabulary that normally accompanies the metadata service's source roots.
                Files.copy(
                    locate("packages/kotlin/ttr-semantics/src/main/resources/builtin/cnc-stock-roles.ttr"),
                    project.resolve("models/stock.ttrm"),
                )
                val out = temporaryDirectory("camunda-real-h1")
                val result =
                    TtrpCommand()
                        .subcommands(
                            EmitCommand(),
                        ).test(
                            listOf(
                                "emit",
                                "--deployment",
                                "release_bpmn",
                                "--template-version",
                                "7",
                                "--project",
                                project.toString(),
                                "--bundle",
                                bundle.dir.toString(),
                                "--out",
                                out.toString(),
                            ),
                        )
                io.kotest.assertions.withClue(result.output) { result.statusCode shouldBe 0 }
                val folder = out.resolve("release_bpmn.$target.export")
                val suffix = if (target == "camunda_7") "c7" else "c8"
                val file = folder.resolve("release_order.$suffix.bpmn")
                val xml = Files.readString(file)
                val contract =
                    org.tatrman.ttrp.conform.replay.CamundaStructureDriver
                        .contract(xml)
                val manifest =
                    kotlinx.serialization.json.Json
                        .parseToJsonElement(
                            Files.readString(folder.resolve("manifest.json")),
                        ).jsonObject
                val authoring = manifest.getValue("authoring").jsonObject
                authoring.getValue("steps").jsonArray.size shouldBe 32
                val omission =
                    authoring
                        .getValue("omitted")
                        .jsonArray
                        .single()
                        .jsonObject
                omission.getValue("qname").jsonPrimitive.content shouldBe "query.query.customer_channel_overlap"
                omission.getValue("reason").jsonPrimitive.content shouldContain
                    "TTRP-EXPORT-QUERY: query.query.customer_channel_overlap:"
                val readme = Files.readString(folder.resolve("README.md"))
                readme shouldContain omission.getValue("reason").jsonPrimitive.content
                readme shouldContain "START_INSTANCE"
                xml shouldContain "hartland-process-owners"
                org.tatrman.ttrp.conform.replay.CamundaStructureDriver
                    .compareArtifacts(folder) shouldBe emptyList()
                val queryDmn = folder.resolve("dmn/query.query.channel_revenue_monthly_decision.dmn")
                Files.readString(queryDmn) shouldContain "typeRef=\"number\""
                Files.readString(folder.resolve("templates/$suffix/release_on_placement.json")) shouldContain
                    "tolerance"
                kotlinx.serialization.json.Json
                    .parseToJsonElement(
                        Files.readString(
                            folder.resolve("templates/$suffix/release_on_placement.json"),
                        ),
                    ).jsonObject
                    .getValue("version")
                    .jsonPrimitive.content shouldBe "7"
                manifest.getValue("calls") shouldBe contract.getValue("calls")
                contract
                    .getValue("calls")
                    .jsonArray.size
                    .let { it > 10 } shouldBe true
                golden("release_bpmn_$suffix", xml.trimEnd())
                val fixture = out.resolve("structure.yaml")
                Files.writeString(
                    fixture,
                    "recipe: release_order\nas_of: \"2026-11-01\"\nkeys: [4711]\nexpected: {verdicts: [{key: 4711, route: release}]}\npinned_from: {run: prior, at: now, by: test}\n",
                )
                val output = mutableListOf<String>()
                org.tatrman.ttrp.cli.ReplayConformCli.run(
                    fixture.toString(),
                    listOf("$target.on_event"),
                    null,
                    null,
                    null,
                    null,
                    listOf(folder.toString()),
                    emptyMap(),
                    output::add,
                ) shouldBe
                    0
                output.single() shouldContain "structure-agree"
                val id =
                    contract
                        .getValue("calls")
                        .jsonArray
                        .first()
                        .jsonObject
                        .getValue("task")
                        .jsonPrimitive.content
                Files.writeString(
                    file,
                    xml.replace(Regex("<bpmn:serviceTask[^>]*id=\"$id\"[\\s\\S]*?</bpmn:serviceTask>"), ""),
                )
                org.tatrman.ttrp.cli.ReplayConformCli.run(
                    fixture.toString(),
                    listOf(
                        "$target.on_event",
                    ),
                    null,
                    null,
                    null,
                    null,
                    listOf(folder.toString()),
                    emptyMap(),
                    {
                    },
                ) shouldBe
                    3
            }
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
        "D-142: batch n8n export uses an external webhook without an embedded schedule" {
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
            val workflow = result.files.getValue("workflow.json").decodeToString()
            workflow shouldContain "n8n-nodes-base.webhook"
            workflow.contains("n8n-nodes-base.scheduleTrigger") shouldBe false
        }
        "H-2 export addresses customer mail per row (decide_return on n8n)" {
            val project = exportProject()
            val compiled = build("decide_return")
            val fixture = compiled.dir.resolve("tests/shared.yaml")
            Files.createDirectories(fixture.parent)
            Files.writeString(fixture, "recipe: decide_return\n")
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
            val out = temporaryDirectory("n8n-real-h2")
            val result =
                TtrpCommand().subcommands(EmitCommand()).test(
                    listOf(
                        "emit",
                        "--deployment",
                        "decide_n8n",
                        "--project",
                        project.toString(),
                        "--bundle",
                        compiled.dir.toString(),
                        "--out",
                        out.toString(),
                    ),
                )
            io.kotest.assertions.withClue(result.output) { result.statusCode shouldBe 0 }
            val rendered = Files.readString(out.resolve("decide_n8n.n8n_l1.export/workflow.json"))
            rendered shouldContain "/recipes/decide_return/runs"
            golden("decide_n8n_workflow", rendered.trimEnd())
            // R2 (R189-A): every customer mail reads the route row's address column; nothing is sent to a role or "".
            val workflow =
                kotlinx.serialization.json.Json
                    .parseToJsonElement(rendered)
                    .jsonObject
            val nodes = workflow.getValue("nodes").jsonArray.map { it.jsonObject }
            val mail =
                workflow
                    .getValue("meta")
                    .jsonObject
                    .getValue("tatrman")
                    .jsonObject
                    .getValue("mail")
                    .jsonArray
                    .map { it.jsonObject }
            mail.size shouldBe 5 // reject_not_ours, reject_late, reject_abuse, exchange, refund_after_return
            mail.forEach {
                it.getValue("action").jsonPrimitive.content shouldBe "notify_customer"
                it.getValue("addressKind").jsonPrimitive.content shouldBe "row"
                it.getValue("addressColumn").jsonPrimitive.content shouldBe "to"
            }
            val groups =
                nodes.filter {
                    it
                        .getValue("name")
                        .jsonPrimitive.content
                        .endsWith(" notify_customer groups")
                }
            groups.size shouldBe 5 // reject_not_ours, reject_late, reject_abuse, exchange, refund_after_return
            groups.forEach {
                it
                    .getValue("parameters")
                    .jsonObject
                    .getValue("jsCode")
                    .jsonPrimitive.content shouldContain
                    "const to=row[\"customer_email\"];"
            }
            nodes
                .filter { it.getValue("type").jsonPrimitive.content == "n8n-nodes-base.emailSend" }
                .forEach {
                    val to =
                        it
                            .getValue("parameters")
                            .jsonObject
                            .getValue("toEmail")
                            .jsonPrimitive.content
                    to shouldContain ".item.json.mail.to"
                    (to.contains("TTR_MAIL_RECIPIENTS") || to.contains("[\"\"]")) shouldBe false
                }
            Files.readString(out.resolve("decide_n8n.n8n_l1.export/tests/shared.yaml")) shouldBe
                "recipe: decide_return\n"
        }
        "R2 the customer-addressed action shape renders its fixture address column and refuses without it" {
            val bundle = build("decide_return")
            val d = world.deployment("decide_n8n")!!
            val graph =
                org.tatrman.ttrp.emit.recipe.RecipeGraphProjector.project(
                    bundle.manifest,
                    world,
                    programs.getValue("decide_on_request"),
                    d,
                    actions,
                )
            val emit = actions.single { it.qname.name == "notify_customer" }
            emit.columns.single { it.name == "to" }.type shouldBe "customer.email"
            graph.wirings
                .filter { it.action == "notify_customer" }
                .forEach { it.columns["to"] shouldBe "customer_email" }
            val addressed =
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
            String(
                EmitPluginLoader
                    .builtin("n8n_l1")
                    .emitRecipe(
                        org.tatrman.ttrp.emit.spi
                            .RecipeEmitRequest(addressed, "{}", "", "{}"),
                    ).files
                    .getValue("workflow.json"),
            ) shouldContain "const to=row[\\\"customer_email\\\"];"
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
                            "emit" to
                                graph.actions.getValue("notify_customer").let { a ->
                                    a.copy(columns = a.columns - "to")
                                },
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
