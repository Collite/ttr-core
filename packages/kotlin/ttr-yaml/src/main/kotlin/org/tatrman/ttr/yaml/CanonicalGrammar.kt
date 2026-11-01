// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.yaml

import kotlinx.serialization.json.*
import org.tatrman.ttr.parser.serialization.DefinitionProperties
import org.snakeyaml.engine.v2.resolver.CoreScalarResolver

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

internal fun isGenericRecord(value: JsonObject): Boolean =
    (
        value.keys in setOf(setOf("ref"), setOf("triple")) &&
            value.values.single().let { it is JsonPrimitive && it.isString }
    ) ||
        (
            value.keys == setOf("call", "args") &&
                value["call"].let { it is JsonPrimitive && it.isString } &&
                value["args"] is JsonArray
        )

internal fun isEmbeddedRecord(value: JsonElement): Boolean =
    value is JsonObject &&
        value["block"].let { it is JsonPrimitive && it.isString } &&
        value.keys.all { it in setOf("block", "tag", "form") } &&
        (value["tag"] == null || value["tag"].let { it is JsonPrimitive && it.isString }) &&
        (value["form"] == null || value["form"]?.jsonPrimitive?.content in setOf("string", "tripleString")) &&
        !("tag" in value && "form" in value)

// Keep exponent notation compact through JSON validation; never allocate its plain decimal expansion.
internal const val MAX_NUMBER_LENGTH = 1000

/**
 * Avoid asking a full YAML/ANTLR scanner to materialize a huge simple numeric leaf.
 * This is only a fast path for unquoted mapping lines in simple documents. Multiline quoting,
 * block scalars, tags, aliases or comments send the document through the ordinary node/token guard,
 * so numeric-looking text never acquires a numeric limit. The guard in number()/convert remains
 * authoritative for flow syntax, compact native bodies and every other scalar form.
 */
internal fun checkSimpleNumericLines(
    source: String,
    file: String,
    yaml: Boolean,
) {
    if (source.length <= MAX_NUMBER_LENGTH) return
    val complex = if (yaml) charArrayOf('|', '>', '#', '!', '&', '*', '%', '[', ']', '{', '}') else charArrayOf('/')
    if (complex.any { source.indexOf(it) >= 0 }) return
    var offset = 0
    var line = 1
    while (offset < source.length) {
        val end = source.indexOf('\n', offset).let { if (it < 0) source.length else it }
        if (!balancedQuotes(source, offset, end)) return
        var colon = offset
        while (colon < end && source[colon] != ':') colon++
        if (colon in offset until end) {
            val key = source.substring(offset, colon).trim()
            if (key.isNotEmpty() && key.all { it.isLetterOrDigit() || it == '_' }) {
                var valueStart = colon + 1
                while (valueStart < end && source[valueStart].isWhitespace()) valueStart++
                var valueEnd = end
                while (valueEnd > valueStart && source[valueEnd - 1].isWhitespace()) valueEnd--
                if (valueEnd - valueStart > MAX_NUMBER_LENGTH) {
                    // A plain YAML scalar may continue on a more-indented line and become text.
                    if (yaml) {
                        var next = end + 1
                        var nextIndent = 0
                        while (next < source.length && source[next].isWhitespace()) {
                            if (source[next] == '\n') nextIndent = 0 else nextIndent++
                            next++
                        }
                        val indent = source.substring(offset, colon).takeWhile { it == ' ' }.length
                        if (next < source.length && nextIndent > indent) return
                    }
                    val raw = source.substring(valueStart, valueEnd)
                    val numeric =
                        if (yaml) {
                            CoreScalarResolver.INT.matcher(raw).matches() ||
                                CoreScalarResolver.FLOAT.matcher(raw).matches()
                        } else {
                            ttrNumber.matches(raw)
                        }
                    if (numeric) {
                        throw CanonicalYamlException(
                            file,
                            line,
                            valueStart - offset + 1,
                            "Number literal too long (maximum $MAX_NUMBER_LENGTH characters)",
                        )
                    }
                }
            }
        }
        offset = end + 1
        line++
    }
}

private fun balancedQuotes(
    source: String,
    start: Int,
    end: Int,
): Boolean {
    var quote: Char? = null
    var i = start
    while (i < end) {
        val c = source[i]
        when {
            quote == '"' && c == '\\' -> i++
            quote == '\'' && c == '\'' && i + 1 < end && source[i + 1] == '\'' -> i++
            c == quote -> quote = null
            quote == null && (c == '"' || c == '\'') -> quote = c
        }
        i++
    }
    return quote == null
}

internal fun checkNumberLength(
    raw: String,
    invalid: (String) -> Nothing,
) {
    if (raw.length > MAX_NUMBER_LENGTH) invalid("Number literal too long (maximum $MAX_NUMBER_LENGTH characters)")
}

internal fun number(
    raw: String,
    invalid: (String) -> Nothing,
): JsonElement {
    checkNumberLength(raw, invalid)
    val decimal =
        try {
            raw.toBigDecimal()
        } catch (_: NumberFormatException) {
            invalid("Expected a finite number")
        }
    if (!decimal.toDouble().isFinite()) invalid("Expected a finite number")
    return Json.parseToJsonElement(decimal.toString())
}

internal val ttrNumber = Regex("-?[0-9]+(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?")

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
