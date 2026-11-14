// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.metadata.source

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.tatrman.ttr.metadata.export.GeneratedHeader
import org.tatrman.ttr.metadata.export.GeneratedInputs
import org.tatrman.ttr.metadata.export.Hashes
import org.tatrman.ttr.profile.ProfileDefinition
import org.tatrman.ttr.profile.SourceEntry
import org.tatrman.ttr.profile.SourcesJsonWriter
import org.tatrman.ttr.yaml.CanonicalDocument
import org.tatrman.ttr.yaml.CanonicalYaml
import org.tatrman.ttr.yaml.CanonicalYamlException
import java.nio.file.Path
import java.util.SortedMap
import java.util.TreeMap

/**
 * What `profile apply` writes (contracts §5), by output path: one canonical document per package and model of a dialect
 * run (`<package>/<model>.yaml`, the package's dots as directories), each canonical input file under its own path, one
 * `<package>.sources.json` sidecar per package (S3.6 fills its entries) and `report.json` (`documents[]`, `ignored[]`,
 * `overridden[]`). [conflicts] are the outputs two
 * inputs would both write, anchored on the second writer's storage path. [problems] are the errors the grouped run
 * recorded (a file it dropped); the plan's are the loader's to report, so they are not repeated here (review-269 F1).
 */
class ProfileApplyResult(
    val files: SortedMap<String, String>,
    val conflicts: List<LoadWarning>,
    val problems: List<LoadWarning> = emptyList(),
    /** Each generated document by its output path (a dialect run's, never a canonical input's), for the guard. */
    val generated: Map<String, CanonicalDocument> = emptyMap(),
    /** The plan's errors (the walk and the declarations), which the loader also reports; the guard needs them. */
    val planned: List<LoadWarning> = emptyList(),
    /** Each generated document's chain identity (`<name>/<major>`, as its header names it), by output path. */
    val generatedBy: Map<String, String> = emptyMap(),
    /**
     * The identity (`<name>/<major>`) of every chain the tree's declarations resolved, known even when a run then fails
     * (review-277 N3): the guard tells an override's document from a broken tree's by it.
     */
    val declared: Set<String> = emptySet(),
)

/**
 * Where [ProfileApply] writes and who writes it, for the generated-file header (contracts §6): input paths in a header
 * are relative to the generated file's directory under [out]; [generator] is the ttr-core version.
 */
data class HeaderOptions(
    val out: Path,
    val generator: String,
)

/**
 * A profile command's one pass over a tree (S3.6, from S3.5 review-269 F8): [source] loads it and [apply] runs the
 * grouped migration over the same plan, so the walk, the reads, the declarations and the parse happen once. Each run
 * keeps its own problems; the results are those of a separate [YamlFileSource] load and [ProfileApply.run].
 */
class ProfileSession(
    private val storage: ModelStorage,
    profile: String?,
    catalog: Map<String, Path>,
    private val sourceId: String,
) {
    private val reader = YamlTreeReader(profile, catalog)

    fun source(priority: Int = 100): ModelSource = YamlFileSource(sourceId, priority, storage, reader)

    fun apply(header: HeaderOptions? = null): ProfileApplyResult = ProfileApply.run(storage, reader, sourceId, header)
}

/**
 * MW S3.5 — the migration half of design §4.D: the tree the `yaml` source kind reads, written as canonical YAML. The
 * walk and the declarations are the loader's ([YamlTreeReader]); a dialect chain runs once, grouped by package and
 * model; a canonical file is read and written by the codec, so a canonical tree in the codec's own form comes out
 * byte-identical. Output is deterministic: files, documents and report entries are sorted.
 */
object ProfileApply {
    private val json = Json { prettyPrint = true }

    fun run(
        storage: ModelStorage,
        profile: String? = null,
        catalog: Map<String, Path> = emptyMap(),
        sourceId: String = "apply",
        header: HeaderOptions? = null,
    ): ProfileApplyResult = run(storage, YamlTreeReader(profile, catalog), sourceId, header)

    /** As above, over [reader]'s plan: a [ProfileSession] shares it with the load. */
    internal fun run(
        storage: ModelStorage,
        reader: YamlTreeReader,
        sourceId: String,
        header: HeaderOptions?,
    ): ProfileApplyResult {
        val listed = storage.listFiles(listOf("yaml") + reader.extensions)
        val plan = reader.plan(listed, storage, sourceId)
        val relative = listed.associate { it.path to it.underRoot() }
        val files = TreeMap<String, String>()
        val writers = mutableMapOf<String, List<String>>()
        val conflicts = mutableListOf<LoadWarning>()
        val documents = mutableListOf<JsonObject>()
        val ignored = mutableListOf<JsonObject>()
        val overridden = mutableListOf<JsonObject>()
        val generated = TreeMap<String, CanonicalDocument>()
        val generatedBy = TreeMap<String, String>()
        val sources = TreeMap<String, MutableList<SourceEntry>>()
        val generatedFrom = TreeMap<String, String>()

        /**
         * [from] are the storage paths of the files the document comes from, sorted (review-269 F4). [profile] is a
         * generated document's chain identity, null for a canonical input file, which is not generated; [inputs] its
         * header inputs when a header is written.
         */
        fun write(
            value: JsonObject,
            text: String,
            from: List<String>,
            at: String? = null,
            profile: String? = null,
            inputs: GeneratedInputs? = null,
            document: CanonicalDocument? = null,
        ) {
            val pkg = (value["package"] as? JsonPrimitive)?.content.orEmpty()
            val model =
                (value["model"] as? JsonPrimitive)?.content
                    ?: from.first().substringAfterLast('/').substringBeforeLast('.')
            val path = at ?: listOf(pkg.replace('.', '/'), "$model.yaml").filter { it.isNotEmpty() }.joinToString("/")
            val first = writers.putIfAbsent(path, from)
            if (first != null) {
                fun names(files: List<String>) = files.joinToString(", ") { relative[it] ?: it }
                conflicts +=
                    LoadWarning(
                        sourceId,
                        from.first(),
                        1,
                        1,
                        "apply: $path is written by both ${names(first)} and ${names(from)}",
                    )
                return
            }
            files[path] = inputs?.header(path)?.line(GeneratedHeader.Form.YAML).orEmpty() + text
            if (document != null) generated[path] = document
            if (profile != null) generatedBy[path] = profile
            if (pkg.isNotEmpty()) {
                sources.getOrPut(pkg) { mutableListOf() }
                if (inputs != null && pkg !in generatedFrom) {
                    generatedFrom[pkg] = inputs.header("$pkg.sources.json").text
                }
            }
            documents +=
                buildJsonObject {
                    put("package", pkg)
                    put("model", model)
                    put("imports", value["imports"] ?: JsonArray(emptyList()))
                    put("defs", (value["defs"] as? JsonArray)?.size ?: 0)
                }
        }
        for ((file, text) in plan.canonical) {
            val document =
                try {
                    CanonicalYaml.read(text, file.path)
                } catch (e: CanonicalYamlException) {
                    continue // the loader reports it (`ys/canonical-invalid`)
                }
            // A canonical file keeps its own path: the identity profile leaves the tree as it is (§5 fixed point).
            write(document.value, CanonicalYaml.write(document), listOf(file.path), at = file.underRoot())
        }
        // The plan's drops are the loader's too; what the grouped runs record after this point is apply's own.
        val planned = plan.problems.errors.size
        // The snapshot is the whole tree, hashed once per run and only when a header is written.
        val rootSha = lazy { Hashes.tree(requireNotNull(plan.root).toAbsolutePath().normalize()) }
        plan.dialect.forEach { (chain, inputs) ->
            val root = requireNotNull(plan.root)
            val (result, _) =
                reader.run(root, chain, inputs, plan.problems, perFile = false)
                    ?: return@forEach
            val identity = identity(chain)
            val headerInputs =
                header?.let {
                    GeneratedInputs(
                        root.toAbsolutePath().normalize(),
                        overlay(root, chain),
                        identity,
                        it.generator,
                        it.out,
                        rootSha,
                    )
                }
            result.sources.forEach { sources.getOrPut(it.pkg) { mutableListOf() } += it }
            // In a grouped run a document has no single file: its writers are the dialect files its records came from.
            val paths = inputs.map { it.first.path }.toSet()
            result.documents.forEach { document ->
                val from =
                    document.locations.values
                        .map { it.file }
                        .filter { it in paths }
                        .distinct()
                        .sorted()
                write(
                    document.value,
                    document.write(),
                    from.ifEmpty { listOf(inputs.first().first.path) },
                    profile = identity,
                    inputs = headerInputs,
                    document = document.canonical,
                )
            }
            val report = result.reportJson()
            report.getValue("ignored").jsonArray.forEach { item ->
                val entry = item.jsonObject
                val file = entry["file"]?.jsonPrimitive?.content
                ignored += JsonObject(entry + ("file" to JsonPrimitive(relative[file] ?: file)))
            }
            overridden += report.getValue("overridden").jsonArray.map { it.jsonObject }
        }
        // Every package with a document gets its sidecar; a dialect run's records are its entries (S3.6 T4).
        sources.forEach { (pkg, entries) ->
            files["$pkg.sources.json"] = SourcesJsonWriter.write(generatedFrom[pkg], entries)
        }
        files["report.json"] =
            json.encodeToString(
                JsonObject.serializer(),
                buildJsonObject {
                    put(
                        "documents",
                        array(documents.sortedWith(compareBy({ it.text("package") }, { it.text("model") }))),
                    )
                    put(
                        "ignored",
                        array(
                            ignored.sortedWith(
                                compareBy(
                                    { it.text("file") },
                                    { it.number("line") },
                                    { it.number("column") },
                                    { it.text("path") },
                                ),
                            ),
                        ),
                    )
                    put(
                        "overridden",
                        array(overridden.sortedWith(compareBy({ it.text("qname") }, { it.text("property") }))),
                    )
                },
            ) + "\n"
        return ProfileApplyResult(
            files,
            conflicts,
            plan.problems.errors.drop(planned),
            generated,
            plan.problems.errors.take(planned),
            generatedBy,
            plan.dialect.map { identity(it.first) }.toSet(),
        )
    }

    /** A chain's identity, `<name>/<major>` of its last profile, as a generated header names it. */
    private fun identity(chain: List<ProfileDefinition>): String = chain.last().let { "${it.name}/${it.major}" }

    /**
     * A chain's overlay input: the overlay of the nearest profile that declares one (an inline overlay is its profile
     * file; every overlay lies inside the root, ✅R260-B); null when none does.
     */
    private fun overlay(
        root: Path,
        chain: List<ProfileDefinition>,
    ): Path? =
        chain.lastOrNull { it.overlay != null }?.let { profile ->
            val file = Path.of(profile.file).let { if (it.isAbsolute) it else root.resolve(it) }
            when (val value = profile.overlay) {
                is JsonPrimitive -> file.resolveSibling(value.content).normalize()
                else -> file
            }
        }

    private fun array(items: List<JsonElement>) = buildJsonArray { items.forEach { add(it) } }

    private fun JsonObject.text(key: String) = (this[key] as? JsonPrimitive)?.content.orEmpty()

    private fun JsonObject.number(key: String) = (this[key] as? JsonPrimitive)?.int ?: 0
}
