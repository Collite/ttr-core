// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.parser.loader

import org.antlr.v4.runtime.BaseErrorListener
import org.antlr.v4.runtime.CharStreams
import org.antlr.v4.runtime.CommonTokenStream
import org.antlr.v4.runtime.RecognitionException
import org.antlr.v4.runtime.Recognizer
import org.tatrman.ttr.parser.generated.TTRLexer
import org.tatrman.ttr.parser.generated.TTRParser
import org.tatrman.ttr.parser.model.SourceLocation
import org.tatrman.ttr.parser.model.TtrlCanvas
import org.tatrman.ttr.parser.model.TtrlDocument
import org.tatrman.ttr.parser.model.TtrlMode
import org.tatrman.ttr.parser.model.TtrlNodeEntry
import java.nio.file.Files
import java.nio.file.Path

/**
 * Parses a `.ttrl` view-state sidecar via the shared `TTR.g4` `ttrlDocument` entry rule
 * (grammar v4.3, C1-c-iii). File-kind dispatch is by the `.ttrl` extension — this is the
 * ONLY entry point that calls `parser.ttrlDocument()`; every other TTR document goes
 * through [TtrLoader].
 *
 * The grammar keeps property keys generic (`id`); the vocabulary + shape rules
 * (skin/mode/nodes/collapsed only; no `nodes` under `mode: auto`; no `viewport`) are
 * enforced HERE, as named parse errors — so a malformed sidecar is a visible diagnostic,
 * never silent decay (C1-c-i point 3).
 */
object TtrlLoader {
    fun parseString(
        content: String,
        fileLabel: String = "<inline>",
    ): TtrlParseResult {
        val errors = mutableListOf<ParseError>()
        val lines = content.lines()
        val errorListener =
            object : BaseErrorListener() {
                override fun syntaxError(
                    recognizer: Recognizer<*, *>?,
                    offendingSymbol: Any?,
                    line: Int,
                    charPositionInLine: Int,
                    msg: String?,
                    e: RecognitionException?,
                ) {
                    val lineContent = lines.getOrNull(line - 1)?.take(200) ?: "<unknown>"
                    errors += ParseError(fileLabel, line, charPositionInLine + 1, "$msg\n        at: $lineContent")
                }
            }
        val lexer = TTRLexer(CharStreams.fromString(content))
        lexer.removeErrorListeners()
        lexer.addErrorListener(errorListener)
        val parser = TTRParser(CommonTokenStream(lexer))
        parser.removeErrorListeners()
        parser.addErrorListener(errorListener)

        val tree = parser.ttrlDocument()
        if (errors.isNotEmpty()) return TtrlParseResult(null, errors, fileLabel)

        val version = tree.NUMBER_LITERAL()?.text?.toIntOrNull()
        if (version == null) {
            errors += ParseError(fileLabel, 1, 1, "`.ttrl` header must be `ttrl <version>`")
            return TtrlParseResult(null, errors, fileLabel)
        }

        val canvases =
            tree.ttrlCanvas().map { c ->
                val key = c.id().text
                var skin: String? = null
                var mode = TtrlMode.AUTO
                var modeSeen = false
                val nodes = mutableListOf<TtrlNodeEntry>()
                val collapsed = mutableListOf<String>()
                val chains = linkedMapOf<String, Int>()
                for (prop in c.ttrlProperty()) {
                    val name = prop.id().text
                    val value = prop.ttrlPropValue()
                    when (name) {
                        "skin" ->
                            skin = value.STRING_LITERAL()?.let { unquote(it.text) }
                                ?: run {
                                    errors += err(fileLabel, prop, "`skin` must be a string")
                                    null
                                }
                        "mode" -> {
                            modeSeen = true
                            when (value.id()?.text) {
                                "auto" -> mode = TtrlMode.AUTO
                                "manual" -> mode = TtrlMode.MANUAL
                                else -> errors += err(fileLabel, prop, "`mode` must be `auto` or `manual`")
                            }
                        }
                        "nodes" -> {
                            val map = value.ttrlNodeMap()
                            if (map == null) {
                                errors += err(fileLabel, prop, "`nodes` must be a `{ \"ζ\": { x, y } }` map")
                            } else {
                                for (entry in map.ttrlNodeEntry()) {
                                    val zeta = unquote(entry.STRING_LITERAL().text)
                                    var x = 0.0
                                    var y = 0.0
                                    for (coord in entry.ttrlCoord()) {
                                        val n = coord.NUMBER_LITERAL().text.toDoubleOrNull() ?: 0.0
                                        when (coord.id().text) {
                                            "x" -> x = n
                                            "y" -> y = n
                                            else -> errors += err(fileLabel, prop, "node coord must be `x`/`y`")
                                        }
                                    }
                                    nodes += TtrlNodeEntry(zeta, x, y)
                                }
                            }
                        }
                        "collapsed" -> {
                            val list = value.listOfStrings()
                            if (list == null) {
                                errors += err(fileLabel, prop, "`collapsed` must be a string list")
                            } else {
                                collapsed +=
                                    list.stringLiteralForm().mapNotNull { it.STRING_LITERAL()?.text?.let(::unquote) }
                            }
                        }
                        "chains" -> {
                            val map = value.ttrlIntMap()
                            if (map == null) {
                                errors += err(fileLabel, prop, "`chains` must be a `{ \"name\": <int> }` map")
                            } else {
                                for (entry in map.ttrlIntEntry()) {
                                    val n = entry.NUMBER_LITERAL().text.toIntOrNull()
                                    if (n == null) {
                                        errors += err(fileLabel, prop, "chain length must be an integer")
                                    } else {
                                        chains[unquote(entry.STRING_LITERAL().text)] = n
                                    }
                                }
                            }
                        }
                        "viewport" ->
                            errors += err(fileLabel, prop, "`viewport` is not part of `.ttrl` v1 (dropped, C1-c-iii)")
                        else -> errors += err(fileLabel, prop, "unknown canvas property `$name`")
                    }
                }
                if (mode == TtrlMode.AUTO && nodes.isNotEmpty() && modeSeen) {
                    errors += err(fileLabel, c, "`nodes` are not allowed on an auto-layout canvas")
                }
                TtrlCanvas(key, skin, mode, nodes, collapsed, chains, locationOf(fileLabel, c))
            }

        if (errors.isNotEmpty()) return TtrlParseResult(null, errors, fileLabel)
        return TtrlParseResult(TtrlDocument(version, canvases, fileLabel), emptyList(), fileLabel)
    }

    fun parseFile(path: Path): TtrlParseResult =
        try {
            parseString(Files.readString(path), path.toString())
        } catch (ex: Exception) {
            TtrlParseResult(
                null,
                listOf(ParseError(path.toString(), -1, -1, "could not read file: ${ex.message}")),
                path.toString(),
            )
        }

    private fun unquote(s: String): String =
        if (s.length >= 2 && s.startsWith("\"") && s.endsWith("\"")) {
            s.substring(1, s.length - 1).replace("\\\"", "\"").replace("\\\\", "\\")
        } else {
            s
        }

    private fun err(
        file: String,
        ctx: org.antlr.v4.runtime.ParserRuleContext,
        message: String,
    ): ParseError = ParseError(file, ctx.start.line, ctx.start.charPositionInLine + 1, message)

    private fun locationOf(
        file: String,
        ctx: org.antlr.v4.runtime.ParserRuleContext,
    ): SourceLocation {
        val start = ctx.start
        val stop = ctx.stop ?: start
        return SourceLocation(
            file = file,
            line = start.line,
            column = start.charPositionInLine,
            endLine = stop.line,
            endColumn = stop.charPositionInLine + (stop.text?.length ?: 0),
            offsetStart = start.startIndex,
            offsetEnd = stop.stopIndex + 1,
        )
    }
}

/** Result of one `.ttrl` parse: `document` non-null iff `errors` empty (no partial trees). */
data class TtrlParseResult(
    val document: TtrlDocument?,
    val errors: List<ParseError>,
    val sourceFile: String,
) {
    val ok: Boolean get() = errors.isEmpty()
}
