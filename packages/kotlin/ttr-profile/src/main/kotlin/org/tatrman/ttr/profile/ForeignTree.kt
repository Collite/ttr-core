// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.profile

import kotlinx.serialization.json.*
import org.tatrman.ttr.yaml.MAX_YAML_CODE_POINTS
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

        /**
         * MW S3.4: a loader labels spans with the storage path, as `.ttrm` diagnostics are.
         *
         * MW S4.2a (✅D-MW-96): an input over [MAX_YAML_CODE_POINTS] code points is refused here, before it is decoded
         * and before any profile runs, with the canonical budget's code and message at 1:1 of [label]: its canonical
         * output is at least as large, so nothing that could pass is refused. The budget is per file, never a sum over
         * a run; a loader drops the file and runs the rest.
         */
        fun read(
            source: String,
            file: String,
            label: String,
        ): ForeignTree {
            if (source.codePointCount(0, source.length) > MAX_YAML_CODE_POINTS) {
                throw ProfileException(
                    "ys/profile-invalid",
                    ProfileSpan(label, 1, 1, 1, 1, ""),
                    "Canonical document $label exceeds $MAX_YAML_CODE_POINTS code points",
                )
            }
            return profileRead { ForeignTree(YamlData.read(source, label), file, label) }
        }
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

/**
 * The tree of [this] labelled [label] that holds [path] (a JSON Pointer), else the first so labelled. A front-end may read
 * one input as several trees under its one label (the `xmi` front-end's tree per UML package, MW-P5 S5.3a): their record
 * paths are disjoint, and a path they share sits on the same input line in each.
 */
fun List<ForeignTree>.holding(
    label: String,
    path: String,
): ForeignTree? {
    val labelled = filter { it.label == label }
    if (labelled.size < 2) return labelled.firstOrNull()
    val pointer = runCatching { JsonPointer(path) }.getOrNull() ?: return labelled.first()
    return labelled.firstOrNull { pointer.resolve(it.root, includeNull = true) != null } ?: labelled.first()
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
