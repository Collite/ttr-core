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
)

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
    ): ProfileApplyResult {
        val reader = YamlTreeReader(profile, catalog)
        val listed = storage.listFiles(listOf("yaml") + reader.extensions)
        val plan = reader.plan(listed, storage, sourceId)
        val relative = listed.associate { it.path to it.underRoot() }
        val files = TreeMap<String, String>()
        val writers = mutableMapOf<String, List<String>>()
        val conflicts = mutableListOf<LoadWarning>()
        val documents = mutableListOf<JsonObject>()
        val ignored = mutableListOf<JsonObject>()
        val overridden = mutableListOf<JsonObject>()

        /** [from] are the storage paths of the files the document comes from, sorted (review-269 F4). */
        fun write(
            value: JsonObject,
            text: String,
            from: List<String>,
            at: String? = null,
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
            files[path] = text
            if (pkg.isNotEmpty()) files["$pkg.sources.json"] = "{\n  \"entries\": []\n}\n"
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
        plan.dialect.forEach { (chain, inputs) ->
            val (result, _) =
                reader.run(requireNotNull(plan.root), chain, inputs, plan.problems, perFile = false)
                    ?: return@forEach
            // In a grouped run a document has no single file: its writers are the dialect files its records came from.
            val paths = inputs.map { it.first.path }.toSet()
            result.documents.forEach { document ->
                val from =
                    document.locations.values
                        .map { it.file }
                        .filter { it in paths }
                        .distinct()
                        .sorted()
                write(document.value, document.write(), from.ifEmpty { listOf(inputs.first().first.path) })
            }
            val report = result.reportJson()
            report.getValue("ignored").jsonArray.forEach { item ->
                val entry = item.jsonObject
                val file = entry["file"]?.jsonPrimitive?.content
                ignored += JsonObject(entry + ("file" to JsonPrimitive(relative[file] ?: file)))
            }
            overridden += report.getValue("overridden").jsonArray.map { it.jsonObject }
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
        return ProfileApplyResult(files, conflicts, plan.problems.errors.drop(planned))
    }

    private fun array(items: List<JsonElement>) = buildJsonArray { items.forEach { add(it) } }

    private fun JsonObject.text(key: String) = (this[key] as? JsonPrimitive)?.content.orEmpty()

    private fun JsonObject.number(key: String) = (this[key] as? JsonPrimitive)?.int ?: 0
}
