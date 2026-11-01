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
    init {
        GrammarCoverage.check()
    }

    fun read(
        source: String,
        file: String = "<yaml>",
    ): CanonicalDocument {
        val nodes = YamlNodes(file)
        val input = nodes.read(source)
        val value = CanonicalNormalization(input["model"]?.jsonPrimitive?.content).document(input)
        val ttr =
            try {
                TtrText(projection = true, spelling = nodes::spelling).write(value)
            } catch (e: CanonicalValueException) {
                nodes.invalid(e.value, e.message ?: "Invalid canonical value")
            } catch (e: IllegalArgumentException) {
                throw CanonicalYamlException(file, 1, 1, e.message ?: "Invalid canonical value")
            }
        val syntax = TtrSyntax(file)
        syntax.read(ttr)
        // Schema validation precedes the syntax bridge; normalize mapping shorthand/order once.
        return CanonicalDocument(
            value,
            YamlLocations(syntax, nodes, ttr, value).remap(typed(ttr, file)),
            nodes::spelling,
            nodes::invalid,
        )
    }

    fun fromTtr(
        source: String,
        file: String = "<ttr>",
    ): CanonicalDocument {
        val syntax = TtrSyntax(file)
        val value = syntax.read(source)
        YamlNodes(file).validate(value)
        return CanonicalDocument(
            CanonicalNormalization(value["model"]?.jsonPrimitive?.content).document(value),
            typed(source, file),
            syntax::spelling,
        )
    }

    fun write(document: CanonicalDocument): String = YamlText.write(document.value, document.spelling)

    fun toTtr(document: CanonicalDocument): String =
        try {
            TtrText(spelling = document.spelling).write(document.value)
        } catch (e: CanonicalValueException) {
            document.invalid(e.value, e.message ?: "Cannot write TTR")
        }

    private fun typed(
        source: String,
        file: String,
    ): ParseResult = TtrLoader.parseString(source, file)
}

internal class CanonicalValueException(
    val value: JsonElement,
    detail: String,
) : IllegalArgumentException(detail)
