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
            val loaded =
                ModelReconciler(ModelDescriptor("export", "export")).reconcile(
                    roots.mapIndexed {
                        i,
                        path,
                        ->
                        FileBasedSource("export-$i", 0, LocalFsStorage("export-$i", path.toRealPath())).load()
                    },
                )
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
            require(original.process == null) { "process deployments require the P1 process exporter" }
            val deployment = original.copy(granularity = granularity ?: original.granularity)
            val program =
                requireNotNull(
                    objects.filterIsInstance<AutomationProgram>().singleOrNull {
                        it.qname.name ==
                            deployment.program
                    },
                ) { "unknown or ambiguous program ${deployment.program}" }
            val recipeName = requireNotNull(program.recipe)
            val temp = if (bundlePath == null) Files.createTempDirectory("ttrp-export-build") else null
            try {
                val dir =
                    bundlePath?.let { Path.of(it).toAbsolutePath().normalize() } ?: run {
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
                            outDir = temp!!,
                        ).dir
                    }
                val bundle = RunManifest.fromJson(Files.readString(dir.resolve("manifest.json")))
                val registrySnapshot =
                    org.tatrman.ttr.metadata.registry.RegistrySnapshot(
                        model = loaded.model,
                        graph =
                            org.tatrman.ttr.metadata.graph.ModelGraph
                                .build(loaded.model),
                        swappedAt = java.time.Instant.EPOCH,
                        warnings = emptyList(),
                    )
                val resolved =
                    org.tatrman.ttr.metadata.world
                        .WorldResolver(registrySnapshot)
                        .resolve(world.qname)
                require(
                    resolved is org.tatrman.ttr.metadata.world.WorldResolution.Ok,
                ) { "cannot resolve export world: $resolved" }
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
                val graph =
                    RecipeGraphProjector.project(
                        bundle,
                        world,
                        program,
                        deployment,
                        objects.filterIsInstance<AutomationAction>(),
                    )
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
                    ExecutorManifestGate
                        .checkRecipe(
                            manifest,
                            program,
                            deployment,
                            RegistryRecipe(
                                graph.recipe.kinds,
                                graph.actions
                                    .mapValues {
                                        RecipeGraphProjector.sink(it.value, graph.deployment.type)
                                    },
                            ),
                        )
                require(diagnostics.isEmpty()) { diagnostics.joinToString("\n") { it.render() } }
                val plugin = EmitPluginLoader.builtin(graph.deployment.type)
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
                        "or target version fingerprint differs from the served bundle; re-export the deployment."
                val fixtures = bundle.recipe!!.tests.orEmpty()
                val conformance =
                    buildJsonObject {
                        put("fixtures", JsonArray(fixtures.map(::JsonPrimitive)))
                        put("cells", JsonArray(listOf(JsonPrimitive("${graph.deployment.type}.${program.mode}"))))
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
                        put("granularity", graph.deployment.granularity)
                        put("connections", JsonArray(bundle.connections.sorted().map(::JsonPrimitive)))
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
                val fixtureBytes =
                    fixtures.associateWith { relative ->
                        require(relative in bundle.files) { "fixture missing from bundle hashes: $relative" }
                        Files.readAllBytes(ExportFolderWriter.bundleFile(dir, relative))
                    }
                ExportFolderWriter.write(
                    folder,
                    formatter.encodeToString(JsonObject.serializer(), exportManifest) + "\n",
                    result,
                    fixtureBytes,
                )
                if (json) {
                    echo(
                        buildJsonObject {
                            put("directory", folder.toString())
                            put("manifest", exportManifest)
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
