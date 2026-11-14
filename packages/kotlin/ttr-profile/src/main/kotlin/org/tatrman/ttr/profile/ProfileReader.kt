// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.profile

import kotlinx.serialization.json.*
import org.tatrman.ttr.yaml.CanonicalYamlException
import org.tatrman.ttr.yaml.YamlData
import java.math.BigInteger

/** Profile diagnostics use one-based lines AND columns; paths are escaped JSON Pointers. */
data class ProfileSpan(
    val file: String,
    val line: Int,
    val column: Int,
    val endLine: Int,
    val endColumn: Int,
    val path: String,
)

/** [suggestion] carries machine-readable help, e.g. `{delta: {renamed: {from, to, since}}}` on `xm/overlay-orphan`. */
class ProfileException(
    val code: String,
    val location: ProfileSpan,
    val detail: String,
    val suggestion: JsonObject? = null,
) : IllegalArgumentException("${location.file}:${location.line}:${location.column}: $code ${location.path}: $detail")

data class LocatedOp(
    val value: JsonObject,
    val location: ProfileSpan,
    val locations: Map<String, ProfileSpan>,
)

data class ProfileDefinition(
    val name: String,
    val major: BigInteger,
    val grammarRange: GrammarRange,
    val extends: String?,
    val overlay: JsonElement?,
    val writable: Boolean,
    val operations: List<LocatedOp>,
    val locations: Map<String, ProfileSpan>,
    /** The key spans of an inline `overlay:`'s entries, by path: an orphan is reported at its key (S3.6 Found (f)). */
    val keyLocations: Map<String, ProfileSpan> = emptyMap(),
) {
    val file: String get() = locations.getValue("").file
}

data class ProfileDeclaration(
    val profile: String,
    val grammarRange: GrammarRange,
    val location: ProfileSpan,
    val grammarLocation: ProfileSpan,
)

internal fun YamlData.span(
    path: String,
    key: Boolean = false,
): ProfileSpan {
    val source = source(path, key)
    return ProfileSpan(source.file, source.line, source.column + 1, source.endLine, source.endColumn + 1, path)
}

internal inline fun <T> profileRead(block: () -> T): T =
    try {
        block()
    } catch (e: CanonicalYamlException) {
        throw ProfileException(
            "ys/profile-invalid",
            ProfileSpan(e.file, e.line, e.column, e.line, e.column, e.keyPath),
            e.detail,
        )
    }

internal fun YamlData.range(
    value: String,
    path: String,
): GrammarRange =
    try {
        GrammarRange.parse(value)
    } catch (e: IllegalArgumentException) {
        throw ProfileException("ys/profile-invalid", span(path), e.message ?: "Invalid grammar range")
    }

object ProfileReader {
    fun read(
        source: String,
        file: String = "<profile>",
    ): ProfileDefinition =
        profileRead {
            val data = YamlData.read(source, file)
            data.validate("profile.schema.json", 1)
            val value = data.value
            value.getValue("operations").jsonArray.forEachIndexed { index, element ->
                val op = element.jsonObject
                val scope = op["in"]?.jsonPrimitive?.content
                val target =
                    op["to"]
                        ?.jsonObject
                        ?.get("kind")
                        ?.jsonPrimitive
                        ?.content
                if (target != null && scope != target) {
                    throw ProfileException(
                        "ys/profile-invalid",
                        data.span("/operations/$index/to/kind", key = true),
                        "to.kind must equal in ($scope)",
                    )
                }
                op["files"]?.jsonArray?.forEachIndexed { fileIndex, pattern ->
                    try {
                        FileSelector(listOf(pattern.jsonPrimitive.content))
                    } catch (e: IllegalArgumentException) {
                        throw ProfileException(
                            "ys/profile-invalid",
                            data.span("/operations/$index/files/$fileIndex"),
                            e.message ?: "Invalid file selector",
                        )
                    }
                }
            }
            val identity = value.getValue("profile").jsonPrimitive.content
            val locations = data.paths.associateWith { data.span(it) }
            val operationLocations = mutableMapOf<Int, MutableMap<String, ProfileSpan>>()
            locations.forEach { (path, span) ->
                if (path.startsWith("/operations/")) {
                    val index = path.removePrefix("/operations/").substringBefore('/').toInt()
                    operationLocations.getOrPut(index) { linkedMapOf() }[path] = span
                }
            }
            ProfileDefinition(
                identity.substringBeforeLast('/'),
                identity.substringAfterLast('/').toBigInteger(),
                data.range(value.getValue("grammar").jsonPrimitive.content, "/grammar"),
                value["extends"]?.jsonPrimitive?.content,
                value["overlay"],
                value["writable"]?.jsonPrimitive?.boolean ?: false,
                value.getValue("operations").jsonArray.mapIndexed { index, op ->
                    val path = "/operations/$index"
                    LocatedOp(
                        op.jsonObject,
                        data.span(path),
                        operationLocations[index] ?: emptyMap(),
                    )
                },
                locations,
                data.paths.filter { it.startsWith("/overlay/") }.associateWith { data.span(it, key = true) },
            )
        }
}

/** A content file's `ttr` header (contracts §4). */
sealed interface FileHeader {
    /** No `ttr` key, or a null one: the tree's declaration applies. */
    data object Absent : FileHeader

    /**
     * A scalar `ttr`, the canonical document's version header: the file is canonical whatever the tree's profile says,
     * never profiled (§4 (c)); the codec judges the version.
     */
    data class Canonical(
        val version: String,
    ) : FileHeader

    /** A `{profile, grammar}` header: this profile for this file, whatever the tree declares. */
    data class Declared(
        val declaration: ProfileDeclaration,
    ) : FileHeader
}

object DeclarationReader {
    /** @param file absolute, or relative to the resolver's root; this label is also the base for relative references. */
    fun readTree(
        source: String,
        file: String = "ttr.yaml",
    ): ProfileDeclaration =
        profileRead {
            val data = YamlData.read(source, file)
            declaration(data, data.value, "")
        }

    /**
     * The declaration a `{profile, grammar}` header makes; null for any other header ([header] tells a canonical
     * version from none).
     * @param file absolute, or relative to the resolver's root; this label is also the base for relative references.
     */
    fun readFile(
        source: String,
        file: String = "<yaml>",
    ): ProfileDeclaration? = (header(source, file) as? FileHeader.Declared)?.declaration

    /**
     * The file's `ttr` header: none (or null), a canonical version (a scalar), or a profile declaration.
     * @param file absolute, or relative to the resolver's root; this label is also the base for relative references.
     */
    fun header(
        source: String,
        file: String = "<yaml>",
    ): FileHeader =
        profileRead {
            val data = YamlData.read(source, file, tagScope = "/ttr")
            val header = data.value["ttr"]
            if (header == null || header is JsonNull) return@profileRead FileHeader.Absent
            if (header is JsonPrimitive) return@profileRead FileHeader.Canonical(header.content)
            if (data.value.keys.first() != "ttr") {
                throw ProfileException(
                    "ys/profile-invalid",
                    data.span("/ttr", key = true),
                    "ttr header must be the first mapping key",
                )
            }
            if (header !is JsonObject) {
                throw ProfileException(
                    "ys/profile-invalid",
                    data.span("/ttr", key = true),
                    "ttr header must be a mapping {profile, grammar}",
                )
            }
            FileHeader.Declared(declaration(data, header, "/ttr"))
        }

    /**
     * The declaration that applies to the file: its own header, else none for a canonical version, else [tree].
     * @param file absolute, or relative to the resolver's root; this label is also the base for relative references.
     */
    fun select(
        source: String,
        file: String,
        tree: ProfileDeclaration?,
    ): ProfileDeclaration? =
        when (val header = header(source, file)) {
            is FileHeader.Declared -> header.declaration
            is FileHeader.Canonical -> null
            FileHeader.Absent -> tree
        }

    private fun declaration(
        data: YamlData,
        value: JsonObject,
        prefix: String,
    ): ProfileDeclaration {
        data.validate("profile.schema.json", 0, value, prefix)
        return ProfileDeclaration(
            value.getValue("profile").jsonPrimitive.content,
            data.range(value.getValue("grammar").jsonPrimitive.content, "$prefix/grammar"),
            data.span("$prefix/profile"),
            data.span("$prefix/grammar"),
        )
    }
}
