// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.profile

import kotlinx.serialization.json.*
import org.tatrman.ttr.yaml.YamlData

/**
 * An immutable, safely decoded input. [file] is a path relative to the model root (file selectors and the package map
 * read it); [label] names the file in every span and diagnostic, and defaults to [file].
 */
class ForeignTree private constructor(
    internal val data: YamlData,
    val file: String,
    val label: String,
) {
    val root: ForeignNode get() = ForeignNode(this, data.value, "")

    companion object {
        fun read(
            source: String,
            file: String = "<yaml>",
        ): ForeignTree = read(source, file, file)

        /** MW S3.4: a loader labels spans with the storage path, as `.ttrm` diagnostics are. */
        fun read(
            source: String,
            file: String,
            label: String,
        ): ForeignTree = profileRead { ForeignTree(YamlData.read(source, label), file, label) }
    }
}

/** A selection keeps the authored path even when a value equals another value elsewhere. */
data class ForeignNode internal constructor(
    val tree: ForeignTree,
    val value: JsonElement,
    val path: String,
    val mappingKey: String? = null,
    internal val keySelection: Boolean = false,
) {
    fun span(key: Boolean = false): ProfileSpan = tree.data.span(path, key || keySelection)

    internal fun child(
        key: String,
        value: JsonElement,
    ) = ForeignNode(tree, value, "$path/${escapePointer(key)}", key)

    internal fun entries(): List<ForeignNode> =
        when (value) {
            is JsonObject -> value.map { (key, value) -> child(key, value) }
            is JsonArray -> value.mapIndexed { i, value -> ForeignNode(tree, value, "$path/$i") }
            else -> emptyList()
        }
}

internal fun escapePointer(key: String) = key.replace("~", "~0").replace("/", "~1")

/** RFC 6901, with the record's mapping entry name available only as `/$key`. */
class JsonPointer(
    pointer: String,
) {
    companion object {
        private val invalidEscape = Regex("~(?![01])")
        private val arrayIndex = Regex("[1-9][0-9]*")
    }

    private val parts: List<String>

    init {
        require(pointer.isEmpty() || pointer.startsWith('/')) { "Expected a JSON Pointer" }
        require(!invalidEscape.containsMatchIn(pointer)) { "Invalid JSON Pointer escape" }
        parts =
            if (pointer.isEmpty()) {
                emptyList()
            } else {
                pointer.drop(1).split('/').map {
                    it.replace("~1", "/").replace("~0", "~")
                }
            }
    }

    fun resolve(
        record: ForeignNode,
        includeNull: Boolean = false,
    ): ForeignNode? {
        if (parts == listOf("\$key")) {
            return record.mappingKey?.let {
                record.copy(value = JsonPrimitive(it), keySelection = true)
            }
        }
        var current = record
        for (part in parts) {
            val value =
                when (val parent = current.value) {
                    is JsonObject -> parent[part]
                    is JsonArray ->
                        if (part == "0" || part.matches(arrayIndex)) {
                            part.toIntOrNull()?.let(parent::getOrNull)
                        } else {
                            null
                        }
                    else -> null
                } ?: return null
            current = current.child(part, value)
        }
        return current.takeUnless { !includeNull && it.value == JsonNull }
    }
}
