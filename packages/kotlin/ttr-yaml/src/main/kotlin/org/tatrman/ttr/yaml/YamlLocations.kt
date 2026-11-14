// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.yaml

import org.tatrman.ttr.parser.loader.ParseResult
import org.tatrman.ttr.parser.loader.ParseWarning
import org.tatrman.ttr.parser.model.*
import org.tatrman.ttr.parser.walker.resolveTag
import kotlinx.serialization.json.*
import java.util.IdentityHashMap
import kotlin.reflect.KProperty1
import kotlin.reflect.full.memberProperties
import kotlin.reflect.full.primaryConstructor

/** Copies immutable parser data classes, replacing bridge spans with representation-node spans. */
internal class YamlLocations(
    private val syntax: TtrSyntax,
    private val nodes: YamlNodes,
    private val generated: String,
    private val canonical: JsonObject,
    private val relocation: Relocation? = null,
) {
    private val copies = IdentityHashMap<Any, Any>()

    private fun located(
        path: String,
        key: Boolean = false,
    ): SourceLocation = relocation?.source(path) ?: nodes.source(path, key)

    /**
     * A block's text span, line starts and indent from one file (review-260 F8): the authored scalar when the
     * relocation holds the block, else all three from the generated text.
     */
    private fun block(
        pointer: String,
        text: String,
    ): BlockSpan =
        relocation?.block(pointer, text)
            ?: nodes.content(pointer, text).let { BlockSpan(it.source, it.lines, it.indent) }

    private fun path(
        start: Int,
        end: Int,
    ): String =
        syntax.spans
            .filter { it.start <= start && it.end >= end }
            .minByOrNull { it.end - it.start }
            ?.path ?: ""

    private fun span(source: SourceLocation) = located(path(source.offsetStart, source.offsetEnd))

    private fun at(
        line: Int,
        column: Int,
    ): SourceLocation {
        val offset =
            generated.lines().take((line - 1).coerceAtLeast(0)).sumOf { it.length + 1 } + (column - 1).coerceAtLeast(0)
        return located(path(offset, offset + 1))
    }

    fun remap(result: ParseResult): ParseResult =
        (copy(result) as ParseResult).copy(
            errors =
                result.errors.map {
                    val source = at(it.line, it.column)
                    it.copy(file = source.file, line = source.line, column = source.column + 1)
                },
            warnings =
                result.warnings.map {
                    val offset =
                        generated.lines().take((it.line - 1).coerceAtLeast(0)).sumOf { line -> line.length + 1 } +
                            (it.column - 1).coerceAtLeast(0)
                    val source = located(path(offset, offset + 1))
                    ParseWarning(source.file, source.line, source.column + 1, it.message)
                },
        )

    private fun literal(source: SourceLocation): Pair<String, JsonObject>? {
        var p = path(source.offsetStart, source.offsetEnd)
        val direct = atPointer(canonical, p)
        if (direct is JsonObject &&
            direct.keys == setOf("triple") &&
            (p in syntax.genericPaths || p in syntax.embeddedPaths)
        ) {
            return p to direct
        }
        while (p.isNotEmpty()) {
            val value = atPointer(canonical, p)
            if (p in syntax.embeddedPaths && value is JsonObject && "block" in value) return p to value
            p = p.substringBeforeLast('/', "")
        }
        return null
    }

    internal fun copy(value: Any?): Any? {
        if (value == null) return null
        if (value is WorldSchemaField) {
            return value.copy(source = located(path(value.source.offsetStart, value.source.offsetEnd), key = true))
        }
        if (value is SourceLocation) return span(value)
        if (value is PropertyValue.StringValue) {
            literal(value.source)?.let { (pointer, syntax) ->
                val source = span(value.source)
                if ("triple" in syntax) return PropertyValue.TripleStringValue(syntax.string("triple"), source)
                if (syntax["form"]?.jsonPrimitive?.content != "string") {
                    val tag = syntax["tag"]?.jsonPrimitive?.content
                    val entry = tag?.let(::resolveTag)
                    if (entry !=
                        null
                    ) {
                        // One coordinate system for span and indent, as for a parsed block (re-review RR1-2).
                        val block = block("$pointer/block", syntax.string("block"))
                        return TaggedBlockValue(
                            tag,
                            entry.language,
                            entry.dialect,
                            syntax.string("block"),
                            located("$pointer/tag"),
                            block.source,
                            block.indentWidth,
                            source,
                        )
                    }
                    return PropertyValue.TripleStringValue(syntax.string("block"), source)
                }
            }
        }
        if (value is AutoValue.Num) {
            return value.copy(
                source = span(value.source),
                text =
                    nodes.numberText(path(value.source.offsetStart, value.source.offsetEnd)) ?: value.text,
            )
        }
        if (value is AutoValue.Str) {
            literal(value.source)?.let { (_, syntax) ->
                if ("triple" in
                    syntax
                ) {
                    return value.copy(raw = syntax.string("triple"), triple = true, source = span(value.source))
                }
            }
        }
        if (value is TaggedBlockValue) {
            literal(value.source)?.let { (pointer, syntax) ->
                if ("block" in syntax) {
                    val block = block("$pointer/block", syntax.string("block"))
                    return value.copy(
                        source = span(value.source),
                        valueSource = block.source,
                        indentWidth = block.indentWidth,
                        tagSource = located("$pointer/tag"),
                    )
                }
            }
        }
        if (value is EmbeddedBlock) {
            literal(value.source)?.let { (pointer, syntax) ->
                val contentKey = if ("triple" in syntax) "triple" else "block"
                val block = block("$pointer/$contentKey", syntax.string(contentKey))
                val form = if ("tag" in syntax) "tagged" else syntax["form"]?.jsonPrimitive?.content ?: "tripleString"
                return value.copy(
                    text = syntax.string(contentKey),
                    form = form,
                    language =
                        syntax["tag"]?.jsonPrimitive?.content ?: "pl",
                    source = span(value.source),
                    textSource = block.source,
                    lineOffsets = block.lineOffsets,
                    indentWidth = block.indentWidth,
                    escapes = emptyList(),
                )
            }
        }
        copies[value]?.let { return it }
        val result: Any =
            when (value) {
                is List<*> -> value.map(::copy)
                is Map<*, *> -> value.mapValues { copy(it.value) }
                else -> {
                    val type = value::class
                    if (!type.isData || !value.javaClass.packageName.startsWith("org.tatrman.ttr.parser")) return value
                    type.objectInstance?.let { return it }
                    val constructor = requireNotNull(type.primaryConstructor)
                    val properties = type.memberProperties.associateBy { it.name }
                    constructor.callBy(
                        constructor.parameters.associateWith { p ->
                            @Suppress("UNCHECKED_CAST")
                            val property = properties.getValue(requireNotNull(p.name)) as KProperty1<Any, *>
                            copy(property.get(value))
                        },
                    )
                }
            }
        copies[value] = result
        return result
    }
}
