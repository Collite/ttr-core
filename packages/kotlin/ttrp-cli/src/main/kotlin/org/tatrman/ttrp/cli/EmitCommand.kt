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
import org.tatrman.ttrp.project.TtrpManifestReader
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
            val process =
                original.process?.let { name ->
                    requireNotNull(
                        world.processes.singleOrNull { it.qname.name == name },
                    ) { "unknown or ambiguous process $name" }
                }
            val programObjects = objects.filterIsInstance<AutomationProgram>()
            val steps = process?.steps.orEmpty()
            require(process == null || steps.isNotEmpty()) { "process has no steps" }
            val programNames =
                process?.steps?.map { requireNotNull(it.program) { "process step has no program" } }
                    ?: listOf(requireNotNull(deployment.program) { "deployment has no program" })
            val programs =
                programNames.map { name ->
                    requireNotNull(
                        programObjects.singleOrNull { it.qname.name == name },
                    ) { "unknown or ambiguous program $name" }
                }
            val plugin =
                EmitPluginLoader.builtin(
                    org.tatrman.ttrp.graph.capability.RegistryGrid
                        .executorType(world, deployment),
                )
            require(
                process == null || plugin.supportsProcesses,
            ) { "${plugin.targetId} does not support process exports" }
            val temp = if (bundlePath == null) Files.createTempDirectory("ttrp-export-build") else null
            try {
                data class Loaded(
                    val graph: org.tatrman.ttrp.emit.spi.RecipeGraph,
                    val bundle: RunManifest,
                    val dir: Path,
                    val actions: List<AutomationAction>,
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
                val resolved =
                    org.tatrman.ttr.metadata.world
                        .WorldResolver(registrySnapshot, packageOf)
                        .resolve(world.qname)
                require(
                    resolved is org.tatrman.ttr.metadata.world.WorldResolution.Ok,
                ) { "cannot resolve export world: $resolved" }
                val bundleDirs = mutableMapOf<String, Path>()

                fun load(
                    program: AutomationProgram,
                    port: String?,
                ): Loaded {
                    val recipeName = requireNotNull(program.recipe)
                    if ((port ?: program.port) == "event") {
                        val event =
                            requireNotNull(
                                program.event,
                            ) { "event port needs an event schema" }.removePrefix("schema.")
                        val schemas =
                            loaded.model.rowSchemas.values
                                .filter { it.name == event }
                        val schema =
                            requireNotNull(schemas.singleOrNull()) { "unknown or ambiguous event schema $event" }
                        require(
                            schema.columns.none { it.name == "at" },
                        ) { "event schema column `at` collides with the port clock" }
                    }
                    val dir =
                        bundleDirs.getOrPut(recipeName) {
                            bundlePath?.let { base ->
                                val path = Path.of(base).toAbsolutePath().normalize()
                                if (process != null) ExportFolderWriter.safePath(path, recipeName) else path
                            } ?: run {
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
                                    outDir = if (process != null) temp!!.resolve(recipeName) else temp!!,
                                ).dir
                            }
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
                    return Loaded(
                        projected.copy(
                            program = projected.program.copy(port = port ?: projected.program.port),
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
                        wiredActions.values.toList(),
                    )
                }
                val loadedRecipes =
                    programs.mapIndexed {
                        index,
                        program,
                        ->
                        load(program, steps.getOrNull(index)?.port)
                    }
                val first = loadedRecipes.first()
                val graph =
                    if (process == null) {
                        first.graph
                    } else {
                        RecipeGraphProjector
                            .project(
                                first.bundle,
                                world,
                                programs.first(),
                                deployment,
                                first.actions,
                                process = process,
                                stepGraphs =
                                    steps
                                        .mapIndexed {
                                            index,
                                            step,
                                            ->
                                            step.name to loadedRecipes[index].graph
                                        }.toMap(),
                            ).let { it.copy(recipe = first.graph.recipe, program = first.graph.program) }
                    }
                val program = programs.first()
                val recipeName = graph.recipe.name
                val bundle = first.bundle
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
                    loadedRecipes.flatMapIndexed { index, loadedRecipe ->
                        ExecutorManifestGate.checkRecipe(
                            manifest,
                            programs[index],
                            deployment,
                            RegistryRecipe(
                                loadedRecipe.graph.recipe.kinds,
                                loadedRecipe.graph.actions.mapValues {
                                    RecipeGraphProjector.sink(it.value, graph.deployment.type)
                                },
                            ),
                        )
                    }
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
                    "Stale when any text, values, labels, program, deployment, world, toolchain " +
                        "or target version fingerprint differs for any step from the served bundle; re-export the deployment."

                fun fixturePath(
                    recipe: String,
                    relative: String,
                ): String = if (process == null) relative else "tests/$recipe/${relative.removePrefix("tests/")}"
                val fixtures =
                    loadedRecipes
                        .flatMap { loadedRecipe ->
                            loadedRecipe.bundle.recipe!!.tests.orEmpty().map { relative ->
                                fixturePath(loadedRecipe.graph.recipe.name, relative)
                            }
                        }.distinct()
                val conformance =
                    buildJsonObject {
                        put("fixtures", JsonArray(fixtures.map(::JsonPrimitive)))
                        put(
                            "cells",
                            JsonArray(programs.map { JsonPrimitive("${graph.deployment.type}.${it.mode}") }.distinct()),
                        )
                    }
                val exportManifest =
                    buildJsonObject {
                        put("schemaVersion", 1)
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
                        if (process != null) {
                            put(
                                "steps",
                                JsonArray(
                                    steps.mapIndexed { index, step ->
                                        val loadedStep = loadedRecipes[index]
                                        buildJsonObject {
                                            put("name", step.name)
                                            put("recipe", loadedStep.graph.recipe.name)
                                            put("program", loadedStep.graph.program.name)
                                            put(
                                                "fingerprints",
                                                JsonObject(
                                                    loadedStep.graph.recipe.fingerprints
                                                        .toSortedMap()
                                                        .mapValues {
                                                            JsonPrimitive(it.value)
                                                        },
                                                ),
                                            )
                                            put("toolchain", loadedStep.bundle.toolchain)
                                            put("cell", "${graph.deployment.type}.${loadedStep.graph.program.mode}")
                                        }
                                    },
                                ),
                            )
                        }
                        put("granularity", graph.deployment.granularity)
                        put(
                            "connections",
                            JsonArray(
                                loadedRecipes
                                    .flatMap { it.bundle.connections }
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
                loadedRecipes.forEach { loadedRecipe ->
                    loadedRecipe.bundle.recipe!!.tests.orEmpty().forEach { relative ->
                        require(
                            relative in loadedRecipe.bundle.files,
                        ) { "fixture missing from bundle hashes: $relative" }
                        val bytes = Files.readAllBytes(ExportFolderWriter.bundleFile(loadedRecipe.dir, relative))
                        val destination = fixturePath(loadedRecipe.graph.recipe.name, relative)
                        require(
                            fixtureBytes[destination]?.contentEquals(bytes) != false,
                        ) { "conflicting process fixture $relative" }
                        fixtureBytes[destination] = bytes
                    }
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
                val finalManifest =
                    if (workflowCalls ==
                        null
                    ) {
                        exportManifest
                    } else {
                        JsonObject(exportManifest + ("calls" to workflowCalls))
                    }
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
                temp?.toFile()?.deleteRecursively()
            }
        } catch (e: Exception) {
            echo("ttrp emit: ${e.message}", err = true)
            throw ProgramResult(2)
        }
    }

    companion object {
        val RECIPE_TYPES =
            setOf("native", "n8n_l1", "camunda_7", "camunda_8", "python_client", "python_offline", "sql_job")
    }
}
