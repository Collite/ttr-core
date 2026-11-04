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

class ProfileException(
    val code: String,
    val location: ProfileSpan,
    val detail: String,
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
            )
        }
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
     * A scalar ttr (including null or the canonical version) deliberately means no profile header.
     * @param file absolute, or relative to the resolver's root; this label is also the base for relative references.
     */
    fun readFile(
        source: String,
        file: String = "<yaml>",
    ): ProfileDeclaration? =
        profileRead {
            val data = YamlData.read(source, file, tagScope = "/ttr")
            val header = data.value["ttr"]
            if (header is JsonPrimitive || header == null) return@profileRead null
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
            declaration(data, header, "/ttr")
        }

    /** @param file absolute, or relative to the resolver's root; this label is also the base for relative references. */
    fun select(
        source: String,
        file: String,
        tree: ProfileDeclaration?,
    ): ProfileDeclaration? = readFile(source, file) ?: tree

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
