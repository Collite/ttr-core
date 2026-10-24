// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.graph.capability

import org.tatrman.ttr.metadata.model.WorldExecutor
import org.tatrman.ttr.metadata.source.FileBasedSource
import org.tatrman.ttr.metadata.source.ModelStorage
import org.tatrman.ttr.metadata.source.StorageFile
import org.tatrman.ttr.parser.model.PropertyValue

/** Recipe targets use the world loader, not a second ad-hoc TTR-M parser. Plugin text is the input. */
data class RecipeExecutorManifest(
    val executor: WorldExecutor,
) {
    val id: String get() = executor.qname.name
    private val fields get() = executor.manifest

    fun values(name: String): List<String> =
        (fields[name] as? PropertyValue.ListValue)?.items?.map { scalar(it) }.orEmpty()

    fun entries(name: String): Map<String, String> =
        (fields[name] as? PropertyValue.ObjectValue)?.entries?.mapValues { scalar(it.value) }.orEmpty()

    fun flag(name: String): Boolean = (fields[name] as? PropertyValue.BoolValue)?.raw == true

    fun value(name: String): String? = fields[name]?.let { scalar(it) }

    companion object {
        fun scalar(v: PropertyValue): String =
            when (v) {
                is PropertyValue.IdValue -> v.parts.joinToString(".")
                is PropertyValue.StringValue -> v.raw
                is PropertyValue.TripleStringValue -> v.raw
                is PropertyValue.NumberValue ->
                    java.math.BigDecimal
                        .valueOf(v.raw)
                        .stripTrailingZeros()
                        .toPlainString()
                is PropertyValue.BoolValue -> v.raw.toString()
                else -> throw ManifestFormatException("expected manifest scalar, got $v")
            }

        fun parse(text: String): RecipeExecutorManifest {
            val storage =
                object : ModelStorage {
                    override val id = "executor-manifest"

                    override fun fetchVersion() = "static"

                    override fun listFiles(
                        extensions: List<String>,
                        prefixes: List<String>,
                    ) = listOf(StorageFile("manifest.ttrm", text.toByteArray().size.toLong()))

                    override fun read(file: StorageFile) = text
                }
            val loaded = FileBasedSource(storage.id, 0, storage).load()
            require(loaded.errors.isEmpty()) { "invalid executor manifest: ${loaded.errors}" }
            val executor =
                loaded.worlds.values
                    .flatMap { it.executors }
                    .singleOrNull()
                    ?: throw ManifestFormatException("manifest must declare exactly one executor")
            val manifest = RecipeExecutorManifest(executor)
            require(manifest.values("modes").isNotEmpty()) { "${manifest.id}: modes must not be empty" }
            require(manifest.values("granularity").isNotEmpty()) { "${manifest.id}: granularity must not be empty" }
            require(manifest.entries("kinds").isNotEmpty()) { "${manifest.id}: kinds must not be empty" }
            val accepts = (executor.manifest["manifestSchema"] as? PropertyValue.ObjectValue)?.entries?.get("accepts")
            require((accepts as? PropertyValue.ListValue)?.items?.any { scalar(it) == "3" } == true) {
                "${manifest.id}: manifestSchema.accepts must include 3"
            }
            return manifest
        }
    }
}

/** No graph → plugin dependency: hosts feed the manifests supplied by their plugin loader. */
class RecipeManifestSource(
    texts: List<String>,
) {
    private val manifests = texts.map { RecipeExecutorManifest.parse(it) }

    init {
        require(manifests.map { it.id }.distinct().size == manifests.size) { "duplicate executor type manifest" }
    }

    fun load(id: String): RecipeExecutorManifest? = manifests.singleOrNull { it.id == id.replace('-', '_') }

    fun all(): List<RecipeExecutorManifest> = manifests.sortedBy { it.id }
}
