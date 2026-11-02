// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.yaml

import kotlinx.serialization.json.*
import org.tatrman.ttr.parser.loader.ParseResult
import org.tatrman.ttr.parser.loader.TtrLoader

/** Lossless syntax tree plus the existing parser's typed projection. Neither resolves references. */
class CanonicalDocument internal constructor(
    val value: JsonObject,
    val ast: ParseResult,
    internal val spelling: (JsonElement) -> String? = { null },
    internal val invalid: (
        JsonElement,
        String,
    ) -> Nothing = { _, message -> throw CanonicalYamlException(ast.sourceFile, 1, 1, message) },
)

object CanonicalYaml {
    /**
     * Canonical collection depth (also checked on authored YAML/TTR), including document/definition containers.
     * About 19 nested automation objects fit; plain lists allow 64 minus the document/definition containers.
     * Native TTR accepts deeper input that therefore cannot be converted or migrated by this codec.
     * Full round trips with guards removed survived 200 levels and overflowed at 210 on a 1 MiB stack.
     * Refusal is at a node inside the over-deep structure, where the first guard fires; fixing it may surface another.
     */
    const val MAX_NESTING_DEPTH = 64

    init {
        GrammarCoverage.check()
    }

    fun read(
        source: String,
        file: String = "<yaml>",
    ): CanonicalDocument {
        val nodes = YamlNodes(file)
        val input = nodes.read(source)
        try {
            val value = CanonicalNormalization(input["model"]?.jsonPrimitive?.content).document(input)
            nodes.bind(value)
            checkCanonicalDepth(value) { _, node -> nodes.invalid(node, "nesting too deep (limit $MAX_NESTING_DEPTH)") }
            val writer = TtrText(projection = true, spelling = nodes::spelling)
            val ttr = writer.write(value)
            val syntax = TtrSyntax(file)
            try {
                syntax.read(ttr)
            } catch (e: CanonicalYamlException) {
                val offset = ttr.lineSequence().take(e.line - 1).sumOf { it.length + 1 } + e.column - 1
                val path =
                    syntax.spans
                        .filter { offset in it.start..it.end }
                        .minByOrNull { it.end - it.start }
                        ?.path
                val origin = path?.let { atPointer(value, it) } ?: writer.origin(ttr, e.line, e.column)
                nodes.invalid(origin ?: value, e.detail)
            }
            nodes.validateTags(syntax.embeddedPaths)
            // Schema validation precedes the syntax bridge; normalize mapping shorthand/order once.
            return CanonicalDocument(
                value,
                YamlLocations(syntax, nodes, ttr, value).remap(typed(ttr, file)),
                nodes::spelling,
                nodes::invalid,
            )
        } catch (e: CanonicalYamlException) {
            throw e
        } catch (e: CanonicalValueException) {
            nodes.invalid(e.value, e.message ?: "Invalid canonical value")
        } catch (e: RuntimeException) {
            nodes.invalid(input["defs"]?.jsonArray?.firstOrNull() ?: input, e.message ?: "Invalid canonical value")
        }
    }

    fun fromTtr(
        source: String,
        file: String = "<ttr>",
    ): CanonicalDocument {
        val syntax = TtrSyntax(file)
        val value = syntax.read(source)
        YamlNodes(file).validate(value) { path, message -> syntax.invalid(path, message) }
        val normalized = CanonicalNormalization(value["model"]?.jsonPrimitive?.content).document(value)
        checkCanonicalDepth(
            normalized,
        ) { path, _ -> syntax.invalid(path, "nesting too deep (limit $MAX_NESTING_DEPTH)") }
        return CanonicalDocument(
            normalized,
            typed(source, file),
            syntax::spelling,
            syntax::invalid,
        )
    }

    fun write(document: CanonicalDocument): String = YamlText.write(document.value, document.spelling)

    fun toTtr(document: CanonicalDocument): String =
        try {
            TtrText(spelling = document.spelling).write(document.value)
        } catch (e: CanonicalValueException) {
            document.invalid(e.value, e.message ?: "Cannot write TTR")
        }

    // Canonical property sequences/wrappers can add containers. Check the normalized tree as well
    // as authored syntax, so an accepted input never writes a document its own reader refuses.
    private fun checkCanonicalDepth(
        root: JsonElement,
        report: (String, JsonElement) -> Nothing,
    ) {
        data class Frame(
            val value: JsonElement,
            val path: String,
            val depth: Int,
        )
        val pending = ArrayDeque<Frame>()
        pending.add(Frame(root, "", 1))
        while (pending.isNotEmpty()) {
            val (value, path, depth) = pending.removeLast()
            if (value !is JsonObject && value !is JsonArray) continue
            if (depth > MAX_NESTING_DEPTH) report(path, value)
            when (value) {
                is JsonObject ->
                    value.entries.toList().asReversed().forEach { (key, child) ->
                        pending.add(Frame(child, "$path/${pointerKey(key)}", depth + 1))
                    }
                is JsonArray ->
                    value.indices.reversed().forEach { index ->
                        pending.add(Frame(value[index], "$path/$index", depth + 1))
                    }
                else -> Unit
            }
        }
    }

    private fun typed(
        source: String,
        file: String,
    ): ParseResult = TtrLoader.parseString(source, file)
}

internal class CanonicalValueException(
    val value: JsonElement,
    detail: String,
    val fatal: Boolean = true,
) : IllegalArgumentException(detail)
