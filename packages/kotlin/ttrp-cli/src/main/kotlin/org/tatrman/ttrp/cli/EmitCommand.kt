// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.required
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonArray
import org.tatrman.ttr.metadata.model.World
import org.tatrman.ttr.metadata.model.AutomationProgram
import org.tatrman.ttr.metadata.model.AutomationAction
import org.tatrman.ttr.metadata.model.ModelDescriptor
import org.tatrman.ttr.metadata.reconcile.ModelReconciler
import org.tatrman.ttr.metadata.source.FileBasedSource
import org.tatrman.ttr.metadata.source.LocalFsStorage
import org.tatrman.ttrp.bundle.RunManifest
import org.tatrman.ttrp.bundle.BundleAssembler
import org.tatrman.ttrp.bundle.EmitPluginLoader
import org.tatrman.ttrp.bundle.ExportFolderWriter
import org.tatrman.ttr.metadata.world.AutomationFingerprints
import org.tatrman.ttrp.emit.recipe.RecipeGraphProjector
import org.tatrman.ttrp.emit.recipe.RecipeActionDependencies
import org.tatrman.ttr.metadata.layout.LayoutResolver
import org.tatrman.ttrp.resolve.ActionResolution
import org.tatrman.ttrp.resolve.AutomationScope
import org.tatrman.ttrp.resolve.ModelIndex
import org.tatrman.ttrp.resolve.TargetResolution
import org.tatrman.ttrp.emit.spi.TtrEmitPlugin
import org.tatrman.ttrp.emit.spi.RecipeEmitRequest
import org.tatrman.ttrp.graph.capability.RecipeManifestSource
import org.tatrman.ttrp.graph.capability.RegistryRecipe
import org.tatrman.ttrp.graph.capability.ExecutorManifestGate
import org.tatrman.ttrp.graph.capability.WorldBinder
import org.tatrman.ttrp.project.TtrpManifestReader
import com.github.ajalt.clikt.parameters.options.validate
import com.github.ajalt.clikt.parameters.types.int
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.ServiceLoader

/** Export a world deployment from its compiler bundle. All filesystem writes belong to the host. */
class EmitCommand : CliktCommand(name = "emit") {
    private val deploymentName by option("--deployment").required()
    private val project by option("--project", help = "modeler.toml project root").default(".")
    private val bundlePath by option("--bundle", help = "existing recipe bundle directory; otherwise build the recipe")
    private val granularity by option("--granularity")
    private val out by option("--out").default(".")
    private val json by option("--json").flag()
    private val templateVersion by option(
        "--template-version",
        help = "positive Modeler template version; raise after fingerprint changes",
    ).int()
        .default(1)
        .validate { require(it > 0) { "template version must be a positive integer" } }

    override fun run() {
        try {
            val root = Path.of(project).toAbsolutePath().normalize()
            val config = TtrpManifestReader.resolve(root)
            require(
                config.diagnostics.none { it.severity.name == "ERROR" },
            ) { config.diagnostics.joinToString { it.render() } }
            val roots = listOf(config.manifest.modelsRoot()) + config.manifest.extraModelRootPaths()
            val snapshots =
                roots.mapIndexed { i, path ->
                    FileBasedSource("export-$i", 0, LocalFsStorage("export-$i", path.toRealPath())).load()
                }
            val loaded = ModelReconciler(ModelDescriptor("export", "export")).reconcile(snapshots)
            require(loaded.ok) { loaded.errors.joinToString { it.message } }
            val objects = loaded.model.objectByQname().values
            val worlds =
                objects.filterIsInstance<World>().filter { w ->
                    config.manifest.world == null ||
                        listOf(
                            w.qname.name,
                            w.qname.dotted(),
                            "${w.qname.`package`.ifBlank { w.qname.namespace }}.${w.qname.name}",
                        ).contains(config.manifest.world)
                }
            val pairs = worlds.mapNotNull { w -> w.deployment(deploymentName)?.let { w to it } }
            val (world, original) =
                requireNotNull(pairs.singleOrNull()) {
                    "unknown or ambiguous deployment $deploymentName (world ${config.manifest.world})"
                }
            val deployment = original.copy(granularity = granularity ?: original.granularity)
            // D-142: a deployment runs one program (a process is a BPMN process now, not an export input).
            val program =
                requireNotNull(
                    objects.filterIsInstance<AutomationProgram>().singleOrNull {
                        it.qname.name == deployment.program
                    },
                ) { "unknown or ambiguous program ${deployment.program}" }
            val executorType =
                org.tatrman.ttrp.graph.capability.RegistryGrid
                    .executorType(world, deployment)
            require(executorType !in WorldBinder.RETIRED_EXECUTOR_TYPES) { WorldBinder.retired(deployment.target) }
            val plugin = EmitPluginLoader.builtin(executorType)
            val temp = Files.createTempDirectory("ttrp-export-build")
            try {
                data class Loaded(
                    val graph: org.tatrman.ttrp.emit.spi.RecipeGraph,
                    val bundle: RunManifest,
                    val dir: Path,
                )

                val registrySnapshot =
                    org.tatrman.ttr.metadata.registry.RegistrySnapshot(
                        model = loaded.model,
                        graph =
                            org.tatrman.ttr.metadata.graph.ModelGraph
                                .build(loaded.model),
                        swappedAt = java.time.Instant.EPOCH,
                        warnings = emptyList(),
                    )
                val packageOf = LayoutResolver.packagesFromFiles(loaded.files)
                val index = ModelIndex(registrySnapshot, packageOf)
                val resolved =
                    org.tatrman.ttr.metadata.world
                        .WorldResolver(registrySnapshot, packageOf)
                        .resolve(world.qname)
                require(
                    resolved is org.tatrman.ttr.metadata.world.WorldResolution.Ok,
                ) { "cannot resolve export world: $resolved" }

                val selectedRecipe = program.recipe

                fun load(program: AutomationProgram): Loaded {
                    val recipeName = requireNotNull(program.recipe)
                    val dir =
                        bundlePath?.takeIf { recipeName == selectedRecipe }?.let { base ->
                            Path.of(base).toAbsolutePath().normalize()
                        }
                            ?: run {
                                val sources =
                                    Files.walk(root).use { paths ->
                                        paths
                                            .filter {
                                                Files.isRegularFile(it) &&
                                                    it.fileName.toString() == "$recipeName.ttrp"
                                            }.toList()
                                    }
                                val source =
                                    requireNotNull(
                                        sources.singleOrNull(),
                                    ) { "expected one $recipeName.ttrp; use --bundle for an existing bundle" }
                                BundleAssembler(
                                    ToolchainVersion.current,
                                ).build(
                                    source = Files.readString(source),
                                    fileName = source.toString(),
                                    recipeDir = source.parent,
                                    pipelineManifest = config.manifest,
                                    modelsRoot = config.manifest.modelsRoot(),
                                    outDir = temp.resolve(recipeName),
                                ).dir
                            }
                    val bundle = RunManifest.fromJson(Files.readString(dir.resolve("manifest.json")))
                    require(
                        bundle.world.fingerprint ==
                            org.tatrman.ttr.metadata.world.WorldFingerprint
                                .of(resolved.world),
                    ) {
                        "bundle world fingerprint differs from deployment world; rebuild the recipe bundle"
                    }
                    require(
                        bundle.world.qname ==
                            listOf(
                                world.qname.`package`.ifBlank {
                                    world.qname.namespace
                                },
                                world.qname.name,
                            ).filter { it.isNotEmpty() }.joinToString("."),
                    ) { "bundle world does not match deployment world" }
                    // Refuse tampered artifacts before passing their metadata to a plugin.
                    bundle.files.forEach { (relative, expected) ->
                        val path = ExportFolderWriter.bundleFile(dir, relative)
                        val actual =
                            "sha256:" +
                                MessageDigest
                                    .getInstance(
                                        "SHA-256",
                                    ).digest(Files.readAllBytes(path))
                                    .joinToString("") { "%02x".format(it) }
                        require(actual == expected) { "bundle file hash mismatch: $relative" }
                    }
                    val recipe = requireNotNull(bundle.recipe) { "bundle has no recipe" }
                    val actionObjects = objects.filterIsInstance<AutomationAction>()
                    val actionGroups = actionObjects.groupBy { it.qname.name }
                    val evidence = recipe.approvalDependencies
                    // Validate the explicit evidence format before trying to reconstruct its scope.
                    evidence?.let { RecipeActionDependencies.validate(it)?.let { bad -> error(bad.reason) } }
                    val scope =
                        evidence?.let {
                            ModelIndex(registrySnapshot, packageOf).automationScope(
                                it.imports.map { raw -> ModelIndex.importScope(raw.split('.'), raw) },
                            )
                        }
                    scope?.missingImports?.firstOrNull()?.let { missing ->
                        error("recorded import `$missing.*` resolves to no loader package in the export model")
                    }
                    val wiredActions =
                        recipe.wiring.map { it.action }.distinct().associateWith { name ->
                            val resolution =
                                scope?.action(name) ?: AutomationScope.resolveAction(name, actionGroups[name].orEmpty())
                            val location =
                                if (evidence ==
                                    null
                                ) {
                                    "export model (bundle records no approval scope)"
                                } else {
                                    "recorded approval scope"
                                }
                            when (resolution) {
                                is ActionResolution.Resolved -> resolution.action
                                is ActionResolution.Unresolved ->
                                    error(
                                        "action `$name` is unresolved in the $location",
                                    )
                                is ActionResolution.Ambiguous ->
                                    error(
                                        "action `$name` is ambiguous in the $location",
                                    )
                            }
                        }
                    if (evidence != null || wiredActions.values.any(AutomationScope::hasDefaults)) {
                        val result =
                            RecipeActionDependencies.verify(evidence, recipe.fingerprints.text, wiredActions) { ref ->
                                scope?.target(ref) ?: TargetResolution.Unresolved(ref)
                            }
                        val reason =
                            when (result) {
                                RecipeActionDependencies.Verification.Ok -> null
                                is RecipeActionDependencies.Verification.Stale ->
                                    "action approval dependencies are stale for `${result.action}`; rebuild the recipe bundle"
                                is RecipeActionDependencies.Verification.Unresolved ->
                                    "unresolved action approval dependency `${result.ref}` in the recorded recipe import scope"
                                is RecipeActionDependencies.Verification.Ambiguous ->
                                    "ambiguous action approval dependency `${result.ref}` in the recorded recipe import scope"
                                is RecipeActionDependencies.Verification.Malformed -> result.reason
                            }
                        require(reason == null) { requireNotNull(reason) }
                    }
                    val projected =
                        RecipeGraphProjector.project(
                            bundle,
                            world,
                            program,
                            deployment,
                            wiredActions.values.toList(),
                        )
                    val governed =
                        if (plugin.targetId in CAMUNDA_TARGETS) {
                            projected.copy(
                                actions =
                                    projected.actions.mapValues { (name, action) ->
                                        action.copy(
                                            candidateGroup =
                                                ApprovalGroupsProjector.group(
                                                    wiredActions.getValue(name),
                                                    index,
                                                ),
                                        )
                                    },
                                deployment =
                                    projected.deployment.copy(
                                        startableGroups = ApprovalGroupsProjector.startGroups(deployment, index),
                                    ),
                            )
                        } else {
                            projected
                        }
                    return Loaded(
                        governed.copy(
                            recipe =
                                projected.recipe.copy(
                                    fingerprints =
                                        projected.recipe.fingerprints +
                                            mapOf(
                                                "program" to AutomationFingerprints.program(program),
                                                "deployment" to AutomationFingerprints.deployment(original),
                                                "world" to bundle.world.fingerprint,
                                            ),
                                ),
                        ),
                        bundle,
                        dir,
                    )
                }
                val selected = load(program)
                val choices =
                    if (plugin.targetId in CAMUNDA_TARGETS) {
                        WorldChoicesProjector.project(loaded.model, world) { choice ->
                            if (choice.recipe == program.recipe) {
                                selected.graph.recipe.parameters
                            } else {
                                val recipe =
                                    requireNotNull(choice.recipe) { "program ${choice.qname.name} has no recipe" }
                                val source =
                                    Files
                                        .walk(root)
                                        .use { paths ->
                                            paths
                                                .filter {
                                                    Files.isRegularFile(
                                                        it,
                                                    ) &&
                                                        it.fileName.toString() == "$recipe.ttrp"
                                                }.toList()
                                        }.singleOrNull() ?: error("expected one $recipe.ttrp for world program choices")
                                val parsed =
                                    org.tatrman.ttrp.parser.TtrpParser
                                        .parseFile(source)
                                require(parsed.diagnostics.none { it.severity.name == "ERROR" }) {
                                    "cannot read $recipe parameters: ${parsed.diagnostics.joinToString { it.render() }}"
                                }
                                val decl =
                                    parsed.document.statements
                                        .filterIsInstance<org.tatrman.ttrp.ast.RecipeDecl>()
                                        .singleOrNull()
                                        ?: error("expected one recipe in $source")
                                val scope =
                                    index
                                        .automationInScope(ModelIndex.importsOf(parsed.document))
                                        .filterIsInstance<org.tatrman.ttr.metadata.model.AutomationParameter>()
                                decl.parameters.map { ref ->
                                    val param =
                                        scope.singleOrNull { it.qname.name == ref.text }
                                            ?: error("unknown or ambiguous parameter ${ref.text} in $recipe")
                                    org.tatrman.ttrp.emit.spi.ParameterMeta(
                                        ref.text,
                                        requireNotNull(param.type) { "parameter ${ref.text} has no type" },
                                        param.value?.let { value ->
                                            when (value) {
                                                is org.tatrman.ttr.parser.model.AutoValue.Str -> value.raw
                                                else -> AutomationFingerprints.render(value)
                                            }
                                        },
                                    )
                                }
                            }
                        }
                    } else {
                        org.tatrman.ttrp.emit.spi
                            .WorldChoices()
                    }
                val loaded = selected
                val modelFingerprint =
                    "sha256:" +
                        MessageDigest
                            .getInstance("SHA-256")
                            .digest(
                                roots
                                    .joinToString(
                                        "\n",
                                    ) {
                                        org.tatrman.ttrp.bundle.ModelFingerprint
                                            .of(it)
                                    }.toByteArray(Charsets.UTF_8),
                            ).joinToString("") { "%02x".format(it) }
                val graph =
                    loaded.graph.copy(
                        recipe =
                            loaded.graph.recipe.copy(
                                fingerprints = loaded.graph.recipe.fingerprints + ("model" to modelFingerprint),
                            ),
                    )
                val recipeName = graph.recipe.name
                val bundle = loaded.bundle
                val plugins = ServiceLoader.load(TtrEmitPlugin::class.java).toList()
                val manifests =
                    RecipeManifestSource(
                        plugins.filter { it.targetId in RECIPE_TYPES }.map { it.executorTypeManifest() },
                    )
                val manifest =
                    requireNotNull(
                        manifests.load(graph.deployment.type),
                    ) { "unknown executor type ${graph.deployment.type}" }
                val diagnostics =
                    ExecutorManifestGate.checkRecipe(
                        manifest,
                        program,
                        deployment,
                        RegistryRecipe(
                            graph.recipe.kinds,
                            graph.actions.mapValues { RecipeGraphProjector.sink(it.value, graph.deployment.type) },
                        ),
                    )
                require(diagnostics.isEmpty()) { diagnostics.joinToString("\n") { it.render() } }
                require(
                    plugin.supportsRecipes,
                ) { "${plugin.targetId} ships its manifest; its recipe renderer is not available yet" }
                val fingerprints =
                    graph.recipe.fingerprints +
                        mapOf(
                            "program" to AutomationFingerprints.program(program),
                            "deployment" to AutomationFingerprints.deployment(original),
                            "world" to bundle.world.fingerprint,
                        )
                val stale =
                    "Stale when any model, text, values, labels, program, deployment, world, toolchain " +
                        "or target version fingerprint differs for any step from the served bundle; re-export the deployment."

                val fixtures = bundle.recipe!!.tests.orEmpty()
                val conformance =
                    buildJsonObject {
                        put("fixtures", JsonArray(fixtures.map(::JsonPrimitive)))
                        put(
                            "cells",
                            JsonArray(listOf(JsonPrimitive("${graph.deployment.type}.${program.mode}"))),
                        )
                    }
                val exportManifest =
                    buildJsonObject {
                        put("schemaVersion", 1)
                        put("world", bundle.world.qname)
                        put("deployment", deploymentName)
                        put("program", program.qname.name)
                        put("recipe", recipeName)
                        put(
                            "target",
                            buildJsonObject {
                                put("type", graph.deployment.type)
                                put("version", graph.deployment.version)
                            },
                        )
                        put(
                            "fingerprints",
                            JsonObject(fingerprints.toSortedMap().mapValues { JsonPrimitive(it.value) }),
                        )
                        put("toolchain", bundle.toolchain)
                        put("granularity", graph.deployment.granularity)
                        put(
                            "connections",
                            JsonArray(
                                bundle.connections
                                    .distinct()
                                    .sorted()
                                    .map(::JsonPrimitive),
                            ),
                        )
                        put("calls", JsonArray(emptyList()))
                        put("conformance", "conformance.json")
                    }
                val formatter = Json { prettyPrint = true }
                val readme =
                    buildString {
                        appendLine("# $deploymentName")
                        appendLine()
                        appendLine("Native deployment of `$recipeName` with program `${program.qname.name}`.")
                        appendLine(
                            "The Server executes the original recipe bundle; " +
                                "this export contains its identity and conformance references.",
                        )
                        appendLine()
                        appendLine(
                            "Run as `${graph.deployment.runAs}`. Connections: ${bundle.connections.joinToString()}.",
                        )
                        appendLine("No client-side run API calls.")
                        appendLine()
                        appendLine(stale)
                    }
                val result =
                    plugin.emitRecipe(
                        RecipeEmitRequest(
                            graph,
                            formatter.encodeToString(JsonObject.serializer(), exportManifest) + "\n",
                            readme,
                            formatter.encodeToString(JsonObject.serializer(), conformance) + "\n",
                            choices,
                            templateVersion,
                        ),
                    )
                val folder =
                    Path
                        .of(
                            out,
                        ).toAbsolutePath()
                        .normalize()
                        .resolve("$deploymentName.${graph.deployment.type}.export")
                val fixtureBytes = linkedMapOf<String, ByteArray>()
                fixtures.forEach { relative ->
                    require(relative in bundle.files) { "fixture missing from bundle hashes: $relative" }
                    val bytes = Files.readAllBytes(ExportFolderWriter.bundleFile(loaded.dir, relative))
                    require(fixtureBytes[relative]?.contentEquals(bytes) != false) { "conflicting fixture $relative" }
                    fixtureBytes[relative] = bytes
                }
                val workflowCalls =
                    result.files["workflow.json"]?.let { bytes ->
                        Json
                            .parseToJsonElement(bytes.decodeToString())
                            .jsonObject["meta"]
                            ?.jsonObject
                            ?.get("tatrman")
                            ?.jsonObject
                            ?.get("calls")
                            ?.jsonArray
                    }
                val bpmnCalls =
                    result.files.entries.singleOrNull { it.key.endsWith(".bpmn") }?.value?.let {
                        org.tatrman.ttrp.conform.replay.CamundaStructureDriver
                            .contract(
                                it.decodeToString(),
                            ).getValue("calls")
                            .jsonArray
                    }
                val renderedCalls = workflowCalls ?: bpmnCalls
                val callManifest =
                    if (renderedCalls ==
                        null
                    ) {
                        exportManifest
                    } else {
                        JsonObject(exportManifest + ("calls" to renderedCalls))
                    }
                val finalManifest =
                    JsonObject(
                        callManifest +
                            mapOf(
                                "files" to
                                    JsonObject(
                                        (result.files + fixtureBytes).toSortedMap().mapValues { (_, bytes) ->
                                            JsonPrimitive(
                                                "sha256:" +
                                                    MessageDigest
                                                        .getInstance(
                                                            "SHA-256",
                                                        ).digest(bytes)
                                                        .joinToString("") { "%02x".format(it) },
                                            )
                                        },
                                    ),
                                "authoring" to
                                    buildJsonObject {
                                        put("dialect", if (graph.deployment.type == "camunda_8") "c8" else "c7")
                                        put(
                                            "omitted",
                                            JsonArray(
                                                choices.omitted.map { omitted ->
                                                    buildJsonObject {
                                                        put("qname", omitted.qname)
                                                        put("reason", omitted.reason)
                                                    }
                                                },
                                            ),
                                        )
                                        put(
                                            "choices",
                                            JsonArray(
                                                (
                                                    choices.programs.map {
                                                        it.name
                                                    } + choices.queries.map { it.qname }
                                                ).sorted().map(::JsonPrimitive),
                                            ),
                                        )
                                        put(
                                            "steps",
                                            JsonArray(
                                                (
                                                    choices.programs.map { p ->
                                                        Triple(
                                                            p.name,
                                                            p.params,
                                                            emptyList<org.tatrman.ttrp.emit.spi.ColumnMeta>(),
                                                        )
                                                    } +
                                                        choices.queries.map { q ->
                                                            Triple(
                                                                q.qname,
                                                                q.params,
                                                                q.columns +
                                                                    org.tatrman.ttrp.emit.spi
                                                                        .ColumnMeta("row_count", "integer"),
                                                            )
                                                        }
                                                ).map { (name, params, columns) ->
                                                    buildJsonObject {
                                                        put("name", name)
                                                        put("params", JsonArray(params.map { JsonPrimitive(it.name) }))
                                                        put(
                                                            "columns",
                                                            JsonObject(
                                                                columns.associate {
                                                                    it.name to
                                                                        JsonPrimitive(it.type)
                                                                },
                                                            ),
                                                        )
                                                        if (columns.isNotEmpty()) put("dmn", "dmn/${name}_decision.dmn")
                                                    }
                                                },
                                            ),
                                        )
                                    },
                            ).filterKeys { it != "authoring" || plugin.targetId in CAMUNDA_TARGETS },
                    )
                ExportFolderWriter.write(
                    folder,
                    formatter.encodeToString(JsonObject.serializer(), finalManifest) + "\n",
                    result,
                    fixtureBytes,
                )
                if (json) {
                    echo(
                        buildJsonObject {
                            put("directory", folder.toString())
                            put("manifest", finalManifest)
                            put("stale", stale)
                        }.toString(),
                    )
                } else {
                    echo(folder.toString())
                    echo(stale)
                }
            } finally {
                temp.toFile().deleteRecursively()
            }
        } catch (e: Exception) {
            echo("ttrp emit: ${e.message}", err = true)
            throw ProgramResult(2)
        }
    }

    companion object {
        /** The targets the Camunda renderer serves: customers' C7/C8 files and the estate's engine (D-145). */
        val CAMUNDA_TARGETS = setOf("camunda_7", "camunda_8", "process_engine")

        val RECIPE_TYPES =
            org.tatrman.ttrp.graph.capability.WorldBinder.AUTOMATION_EXECUTOR_TYPES
    }
}
