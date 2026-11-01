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
import org.snakeyaml.engine.v2.exceptions.ReaderException
import org.snakeyaml.engine.v2.nodes.*
import org.snakeyaml.engine.v2.schema.CoreSchema

class CanonicalYamlException(
    val file: String,
    val line: Int,
    val column: Int,
    val detail: String,
) : IllegalArgumentException("$file:$line:$column: $detail")

internal class YamlNodes(
    private val file: String,
) {
    private val locations = mutableMapOf<String, Node>()
    private val keys = mutableMapOf<String, Node>()
    private val plNodes = mutableListOf<Node>()
    private var inputText = ""
    private val spellings = java.util.IdentityHashMap<JsonElement, String>()

    fun spelling(value: JsonElement): String? = spellings[value]

    fun numberText(path: String): String? =
        (node(path) as? ScalarNode)?.value?.takeIf {
            it.matches(ttrNumber)
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

    fun source(
        path: String,
        key: Boolean = false,
    ): org.tatrman.ttr.parser.model.SourceLocation {
        val node = if (key) keys[path] ?: node(path) else node(path)
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
                .setVersionFunction { version ->
                    if (version.major != 1 || version.minor != 2) {
                        val line = source.lineSequence().indexOfFirst { it.startsWith("%YAML") }.coerceAtLeast(0)
                        throw CanonicalYamlException(file, line + 1, 1, "Unsupported YAML version $version")
                    }
                    version
                }.setAllowDuplicateKeys(false)
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
            } catch (e: ReaderException) {
                val offset =
                    source.offsetByCodePoints(
                        0,
                        e.position.coerceAtMost(source.codePointCount(0, source.length)),
                    )
                val prefix = source.take(offset)
                throw CanonicalYamlException(
                    file,
                    prefix.count { it == '\n' } + 1,
                    prefix.length - prefix.lastIndexOf('\n'),
                    e.message ?: "Invalid YAML character",
                )
            } catch (e: YamlEngineException) {
                throw CanonicalYamlException(file, 1, 1, e.message ?: "Invalid YAML")
            }
        val value = convert(node, "") as? JsonObject ?: fail(node, "Expected a canonical document mapping")
        validate(value)
        return value
    }

    fun validate(
        value: JsonObject,
        report: (String, String) -> Nothing = { path, message -> fail(node(path), message) },
    ) {
        fun jsonNode(
            v: JsonElement,
            path: String,
        ): com.fasterxml.jackson.databind.JsonNode =
            when (v) {
                is JsonObject ->
                    mapper.createObjectNode().also { result ->
                        v.forEach { (key, child) ->
                            result.set<com.fasterxml.jackson.databind.JsonNode>(
                                key,
                                jsonNode(child, "$path/${pointerKey(key)}"),
                            )
                        }
                    }
                is JsonArray ->
                    mapper.createArrayNode().also { result ->
                        v.forEachIndexed { index, child -> result.add(jsonNode(child, "$path/$index")) }
                    }
                else ->
                    try {
                        val scalar = v.jsonPrimitive
                        when {
                            scalar == JsonNull -> mapper.nodeFactory.nullNode()
                            scalar.isString -> mapper.nodeFactory.textNode(scalar.content)
                            scalar.booleanOrNull != null -> mapper.nodeFactory.booleanNode(scalar.boolean)
                            else -> mapper.nodeFactory.numberNode(scalar.content.toBigDecimal())
                        }
                    } catch (e: RuntimeException) {
                        report(path, e.message ?: "Invalid canonical scalar")
                    }
            }
        val normalized = jsonNode(value, "")
        val errors =
            try {
                schema.validate(normalized)
            } catch (e: RuntimeException) {
                report(if (value["defs"] is JsonArray) "/defs/0" else "", e.message ?: "Invalid canonical value")
            }
        if (errors.isNotEmpty()) {
            val error = errors.maxBy { it.instanceLocation.toString().length }
            val path =
                error.instanceLocation.toString() +
                    if (error.type == "additionalProperties" &&
                        error.property != null
                    ) {
                        "/${pointerKey(error.property)}"
                    } else {
                        ""
                    }
            report(path, error.message)
        }
    }

    fun bind(
        value: JsonElement,
        path: String = "",
    ) {
        node(path)?.let { valueNodes.putIfAbsent(value, it) }
        when (value) {
            is JsonObject -> value.forEach { (key, child) -> bind(child, "$path/${pointerKey(key)}") }
            is JsonArray -> value.forEachIndexed { index, child -> bind(child, "$path/$index") }
            else -> Unit
        }
    }

    fun validateTags(embeddedPaths: Set<String>) {
        val allowed = embeddedPaths.mapNotNull(::node).toSet()
        plNodes.forEach { if (it !in allowed) fail(it, "Unsupported YAML tag !pl here") }
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
        if (node.tag.value == "!pl" && node is ScalarNode) {
            plNodes += node
            return obj("block" to text(node.value), "tag" to text("pl")).also { record ->
                record.values.forEach {
                    valueNodes[it] =
                        node
                }
            }
        }
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
                    val childPath = "$path/${pointerKey(key.value)}"
                    keys[childPath] = key
                    result[key.value] = convert(tuple.valueNode, childPath)
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
                        checkNumberLength(node.value) { fail(node, it) }
                        val raw = node.value
                        val decimalText =
                            try {
                                when {
                                    raw.startsWith("0x") -> raw.drop(2).toBigInteger(16).toString()
                                    raw.startsWith("0o") -> raw.drop(2).toBigInteger(8).toString()
                                    else -> raw
                                }
                            } catch (_: NumberFormatException) {
                                fail(node, "Expected a finite number")
                            }
                        number(decimalText) { fail(node, it) }.also {
                            if (ttrNumber.matches(node.value)) spellings[it] = node.value
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

    private fun scalar(v: JsonElement): String =
        if (v is JsonPrimitive &&
            v.isString
        ) {
            quoted(v.content)
        } else {
            v.toString()
        }

    private fun printable(c: Char): Boolean =
        c == '\t' ||
            c == '\n' ||
            c == '\r' ||
            c in ' '..'~' ||
            c == '\u0085' ||
            c in '\u00a0'..'\ud7ff' ||
            c in '\ue000'..'\ufffd'

    private fun printableText(value: String): Boolean =
        value.codePoints().allMatch {
            it > 0xffff ||
                printable(it.toChar())
        }

    private fun quoted(value: String): String {
        val json = text(value).toString()
        return buildString {
            var index = 0
            while (index < json.length) {
                val c = json[index++]
                if (c.isHighSurrogate() && index < json.length && json[index].isLowSurrogate()) {
                    append(c).append(json[index++])
                } else if (printable(c)) {
                    append(c)
                } else {
                    append("\\u").append(c.code.toString(16).padStart(4, '0'))
                }
            }
        }
    }

    private fun key(v: String) =
        if (v.matches(Regex("[A-Za-z_][A-Za-z0-9_]*")) &&
            v.lowercase() !in setOf("null", "true", "false")
        ) {
            v
        } else {
            quoted(v)
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
                printableText(value.content) &&
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
                lines.forEach {
                    if (it.isNotEmpty()) out.append(" ".repeat(indent + 2)).append(it)
                    out.append('\n')
                }
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
