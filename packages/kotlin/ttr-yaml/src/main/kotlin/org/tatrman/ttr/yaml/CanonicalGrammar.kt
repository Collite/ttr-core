// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.yaml

import kotlinx.serialization.json.*
import org.tatrman.ttr.parser.serialization.DefinitionProperties

/** Immutable bundled metadata; no runtime grammar downloads or duplicated kind vocabulary. */
internal object CanonicalGrammar {
    fun resource(name: String): String =
        requireNotNull(javaClass.getResourceAsStream("/schemas/$name")) {
            "Missing canonical grammar resource $name"
        }.bufferedReader().use { it.readText() }

    val metadata = Json.parseToJsonElement(resource("canonical-metadata.json")).jsonObject
    val version = metadata.getValue("version").jsonPrimitive.content
    val bodies = metadata.getValue("bodies").jsonObject
    val rules = metadata.getValue("rules").jsonObject.mapValues { it.value.jsonPrimitive.content }
    val literals =
        metadata.getValue("literals").jsonObject.mapValues {
            it.value.jsonArray.map { x ->
                x.jsonPrimitive.content
            }
        }
    val kinds =
        metadata.getValue("kinds").jsonArray.associate {
            it.jsonObject.string("kind") to
                it.jsonObject.string("body")
        }

    fun body(name: String) = bodies.getValue(name).jsonObject

    fun properties(name: String) = body(name).getValue("properties").jsonArray.map { it.jsonObject }

    fun symbols(expr: JsonElement): List<String> {
        val o = expr.jsonObject
        return when {
            "symbol" in o -> listOf(o.string("symbol"))
            "node" in o -> symbols(o.getValue("node"))
            else -> (o["seq"] ?: o.getValue("alt")).jsonArray.flatMap(::symbols)
        }
    }

    fun ordered(
        name: String,
        model: String?,
    ) = body(name).getValue("ordered").jsonPrimitive.boolean && !(name == "roleDef" && model != "cn")

    fun entries(value: JsonElement): List<Pair<String, JsonElement>> =
        when (value) {
            is JsonObject -> value.entries.map { it.key to it.value }
            is JsonArray -> value.flatMap { it.jsonObject.entries.map { x -> x.key to x.value } }
            else -> error("Expected a property mapping or sequence")
        }

    fun serialize(
        name: String,
        entries: List<Pair<String, JsonElement>>,
        model: String?,
    ): JsonElement {
        val ordered = ordered(name, model)
        val order = properties(name).map { it.string("key") }
        val result = DefinitionProperties.serialize(entries, order, ordered) { _, value -> value }
        return if (ordered) {
            JsonArray(result.map { (k, v) -> obj(k to v) })
        } else {
            require(
                result.map { it.first }.distinct().size == result.size,
            ) { "Repeated property requires an ordered grammar body" }
            JsonObject(result.toMap())
        }
    }
}

internal fun JsonObject.string(key: String): String = getValue(key).jsonPrimitive.content

internal fun obj(vararg fields: Pair<String, JsonElement?>): JsonObject =
    JsonObject(
        fields.filter { it.second != null }.associate {
            it.first to
                it.second!!
        },
    )

internal fun text(value: String) = JsonPrimitive(value)

internal fun canonicalMap(
    entries: List<Pair<String, JsonElement>>,
    order: List<String> = emptyList(),
): JsonObject = JsonObject(DefinitionProperties.serialize(entries, order) { _, value -> value }.toMap())

internal fun pointerKey(key: String) = key.replace("~", "~0").replace("/", "~1")

internal fun atPointer(
    value: JsonElement,
    path: String,
): JsonElement? {
    var current = value
    if (path.isEmpty()) return current
    for (raw in path.split('/').drop(1)) {
        val key = raw.replace("~1", "/").replace("~0", "~")
        current =
            when (val parent = current) {
                is JsonObject -> parent[key] ?: return null
                is JsonArray -> key.toIntOrNull()?.let { parent.getOrNull(it) } ?: return null
                else -> return null
            }
    }
    return current
}
