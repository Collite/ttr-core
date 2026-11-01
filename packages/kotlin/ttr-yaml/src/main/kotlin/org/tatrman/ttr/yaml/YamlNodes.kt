// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.yaml

import com.fasterxml.jackson.databind.ObjectMapper
import com.networknt.schema.JsonSchemaFactory
import com.networknt.schema.SpecVersion
import com.networknt.schema.SchemaValidatorsConfig
import com.networknt.schema.PathType
import kotlinx.serialization.json.*
import org.snakeyaml.engine.v2.api.LoadSettings
import org.snakeyaml.engine.v2.api.lowlevel.Compose
import org.snakeyaml.engine.v2.exceptions.MarkedYamlEngineException
import org.snakeyaml.engine.v2.exceptions.YamlEngineException
import org.snakeyaml.engine.v2.nodes.*
import org.snakeyaml.engine.v2.schema.CoreSchema
import java.math.BigDecimal

class CanonicalYamlException(
    val file: String,
    val line: Int,
    val column: Int,
    detail: String,
) : IllegalArgumentException("$file:$line:$column: $detail")

internal class YamlNodes(
    private val file: String,
) {
    private val locations = mutableMapOf<String, Node>()
    private var inputText = ""
    private val spellings = java.util.IdentityHashMap<JsonElement, String>()

    fun spelling(value: JsonElement): String? = spellings[value]

    fun numberText(path: String): String? =
        (node(path) as? ScalarNode)?.value?.takeIf {
            it.matches(Regex("-?[0-9]+(?:\\.[0-9]+)?"))
        }

    private fun node(path: String): Node? {
        var current = path
        while (current !in locations && current.isNotEmpty()) {
            val segments = current.split('/').drop(1)
            var original = ""
            var shorthand = ""
            segments.forEach { segment ->
                if (!(segment.toIntOrNull() != null && locations[original] is MappingNode)) {
                    original += "/$segment"
                    shorthand += "/$segment"
                }
            }
            if (shorthand in locations) {
                current = shorthand
                break
            }
            current = current.substringBeforeLast('/', "")
        }
        return locations[current]
    }

    fun source(path: String): org.tatrman.ttr.parser.model.SourceLocation {
        val node = node(path)
        val a = node?.startMark?.orElse(null)
        val b = node?.endMark?.orElse(a)
        return org.tatrman.ttr.parser.model.SourceLocation(
            file,
            (a?.line ?: 0) + 1,
            a?.column ?: 0,
            (b?.line ?: 0) + 1,
            b?.column ?: 0,
            a?.index ?: 0,
            b?.index ?: 0,
        )
    }

    data class Content(
        val source: org.tatrman.ttr.parser.model.SourceLocation,
        val lines: List<Int>,
        val indent: Int,
    )

    fun content(
        path: String,
        decoded: String,
    ): Content {
        val n = node(path)
        val span = source(path)
        val lines = inputText.split('\n')
        val rawOffsets = lines.runningFold(0) { offset, line -> offset + line.length + 1 }
        val literal = n is ScalarNode && n.scalarStyle == org.snakeyaml.engine.v2.common.ScalarStyle.LITERAL
        if (literal) {
            val decodedLines = decoded.split('\n')
            val first = decodedLines.indexOfFirst { it.isNotBlank() }.coerceAtLeast(0)
            val physical = lines.getOrElse(span.line + first) { "" }
            val logical = decodedLines.getOrElse(first) { "" }
            val indent =
                (physical.takeWhile { it == ' ' }.length - logical.takeWhile { it == ' ' }.length)
                    .coerceAtLeast(
                        0,
                    )
            val offsets =
                decodedLines.indices.map { index ->
                    rawOffsets.getOrElse(span.line + index) { span.offsetEnd } +
                        indent
                }
            return Content(
                span.copy(line = span.line + 1, column = indent, offsetStart = offsets.first()),
                offsets,
                indent,
            )
        }
        return Content(
            span,
            decoded
                .split('\n')
                .runningFold(span.offsetStart) { offset, line ->
                    offset + line.length +
                        1
                }.dropLast(1),
            span.column,
        )
    }

    private fun fail(
        node: Node?,
        message: String,
    ): Nothing {
        val mark = node?.startMark?.orElse(null)
        throw CanonicalYamlException(file, (mark?.line ?: 0) + 1, (mark?.column ?: 0) + 1, message)
    }

    fun read(source: String): JsonObject {
        inputText = source
        val settings =
            LoadSettings
                .builder()
                .setLabel(file)
                .setSchema(CoreSchema())
                .setAllowDuplicateKeys(false)
                .setMaxAliasesForCollections(0)
                .setCodePointLimit(3_000_000)
                .build()
        val node =
            try {
                Compose(settings).composeString(source).orElseThrow {
                    CanonicalYamlException(file, 1, 1, "Expected one canonical document")
                }
            } catch (e: MarkedYamlEngineException) {
                val mark = e.problemMark.orElse(null)
                throw CanonicalYamlException(
                    file,
                    (mark?.line ?: 0) + 1,
                    (mark?.column ?: 0) + 1,
                    e.problem ?: "Invalid YAML",
                )
            } catch (e: YamlEngineException) {
                throw CanonicalYamlException(file, 1, 1, e.message ?: "Invalid YAML")
            }
        val value = convert(node, "") as? JsonObject ?: fail(node, "Expected a canonical document mapping")
        validate(value)
        return value
    }

    fun validate(value: JsonObject) {
        val errors = schema.validate(mapper.readTree(value.toString()))
        if (errors.isNotEmpty()) {
            val error = errors.maxBy { it.instanceLocation.toString().length }
            var path = error.instanceLocation.toString()
            while (path !in locations && path.isNotEmpty()) path = path.substringBeforeLast('/', "")
            fail(locations[path], error.message)
        }
    }

    private val valueNodes = java.util.IdentityHashMap<JsonElement, Node>()

    fun invalid(
        value: JsonElement,
        message: String,
    ): Nothing = fail(valueNodes[value], message)

    private fun convert(
        node: Node,
        path: String,
    ): JsonElement = decode(node, path).also { valueNodes[it] = node }

    private fun decode(
        node: Node,
        path: String,
    ): JsonElement {
        locations[path] = node
        if (node.anchor.isPresent) fail(node, "Anchors and aliases are not supported")
        if (node.tag.value == "!pl" && node is ScalarNode) return obj("block" to text(node.value), "tag" to text("pl"))
        return when (node) {
            is MappingNode -> {
                if (node.tag != Tag.MAP) fail(node, "Unsupported YAML tag ${node.tag}")
                val result = linkedMapOf<String, JsonElement>()
                node.value.forEach { tuple ->
                    val key = tuple.keyNode as? ScalarNode ?: fail(tuple.keyNode, "Mapping keys must be strings")
                    if (key.tag != Tag.STR ||
                        key.value == "<<"
                    ) {
                        fail(key, "Mapping keys must be strings; merge keys are not supported")
                    }
                    if (key.value in result) fail(key, "Duplicate mapping key ${key.value}")
                    result[key.value] = convert(tuple.valueNode, "$path/${pointerKey(key.value)}")
                }
                JsonObject(result)
            }
            is SequenceNode -> {
                if (node.tag != Tag.SEQ) fail(node, "Unsupported YAML tag ${node.tag}")
                JsonArray(node.value.mapIndexed { i, child -> convert(child, "$path/$i") })
            }
            is ScalarNode ->
                when (node.tag) {
                    Tag.STR -> text(node.value)
                    Tag.NULL -> JsonNull
                    Tag.BOOL -> JsonPrimitive(node.value.lowercase() == "true")
                    Tag.INT, Tag.FLOAT -> {
                        val raw = node.value.replace("_", "")
                        val decimal =
                            try {
                                when {
                                    raw.startsWith("0x") -> BigDecimal(raw.drop(2).toBigInteger(16))
                                    raw.startsWith("0o") -> BigDecimal(raw.drop(2).toBigInteger(8))
                                    else -> BigDecimal(raw)
                                }
                            } catch (_: NumberFormatException) {
                                fail(node, "Expected a finite number")
                            }
                        Json.parseToJsonElement(decimal.toPlainString()).also { number ->
                            if (!decimal.toDouble().isFinite()) fail(node, "Expected a finite number")
                            if (raw.matches(Regex("-?[0-9]+(?:\\.[0-9]+)?"))) spellings[number] = raw
                        }
                    }
                    else -> fail(node, "Unsupported YAML tag ${node.tag}")
                }
            else -> fail(node, "Unsupported YAML node")
        }
    }

    companion object {
        private val mapper = ObjectMapper()
        private val schema =
            JsonSchemaFactory
                .getInstance(SpecVersion.VersionFlag.V202012)
                .getSchema(
                    CanonicalGrammar.resource("model-yaml.schema.json"),
                    SchemaValidatorsConfig.builder().pathType(PathType.JSON_POINTER).build(),
                )
    }
}

/** Emits only canonical data: no constructors, anchors, aliases, or implicit string resolution. */
internal object YamlText {
    fun write(
        value: JsonObject,
        spelling: (JsonElement) -> String? = { null },
    ): String = buildString { emit(value, 0, this, spelling) }

    private fun scalar(v: JsonElement): String = if (v is JsonPrimitive && v.isString) v.toString() else v.toString()

    private fun key(v: String) =
        if (v.matches(Regex("[A-Za-z_][A-Za-z0-9_]*")) &&
            v.lowercase() !in setOf("null", "true", "false")
        ) {
            v
        } else {
            text(v).toString()
        }

    private fun leaf(v: JsonElement) =
        v is JsonPrimitive || (v is JsonArray && v.isEmpty()) || (v is JsonObject && v.isEmpty())

    private fun emit(
        v: JsonElement,
        indent: Int,
        out: StringBuilder,
        spelling: (JsonElement) -> String?,
    ) {
        val pad = " ".repeat(indent)

        fun field(
            prefix: String,
            value: JsonElement,
        ) {
            if (value is JsonPrimitive &&
                value.isString &&
                '\n' in value.content &&
                '\r' !in value.content &&
                value.content.any { !it.isWhitespace() }
            ) {
                val trailing = value.content.takeLastWhile { it == '\n' }.length
                val marker =
                    if (trailing == 0) {
                        "|-"
                    } else if (trailing == 1) {
                        "|"
                    } else {
                        "|+"
                    }
                // Explicit indentation prevents leading content spaces being stripped.
                out.append(prefix).append(marker.replace("|", "|2")).append('\n')
                val lines = value.content.split('\n').let { if (trailing > 0) it.dropLast(1) else it }
                lines.forEach { out.append(" ".repeat(indent + 2)).append(it).append('\n') }
            } else if (leaf(value)) {
                out.append(prefix).append(spelling(value) ?: scalar(value)).append('\n')
            } else {
                out.append(prefix.trimEnd()).append('\n')
                emit(value, indent + 2, out, spelling)
            }
        }
        when (v) {
            is JsonObject -> v.forEach { (k, x) -> field("$pad${key(k)}: ", x) }
            is JsonArray -> v.forEach { x -> field("$pad- ", x) }
            else -> out.append(pad).append(scalar(v)).append('\n')
        }
    }
}
