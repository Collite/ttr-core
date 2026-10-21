// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.parser

import org.antlr.v4.runtime.BaseErrorListener
import org.antlr.v4.runtime.CharStreams
import org.antlr.v4.runtime.CommonTokenStream
import org.antlr.v4.runtime.RecognitionException
import org.antlr.v4.runtime.Recognizer
import org.antlr.v4.runtime.Token
import org.antlr.v4.runtime.TokenStream
import org.tatrman.ttrp.ast.SourceLocation
import org.tatrman.ttrp.ast.TtrpDocument
import org.tatrman.ttrp.diagnostics.Severity
import org.tatrman.ttrp.diagnostics.TtrpDiagnostic
import org.tatrman.ttrp.diagnostics.TtrpDiagnosticId
import org.tatrman.ttrp.expr.catalog.BuiltinCatalog
import org.tatrman.ttrp.expr.catalog.CompositeCatalog
import org.tatrman.ttrp.expr.catalog.FunctionCatalog
import org.tatrman.ttrp.parser.generated.TTRPLexer
import org.tatrman.ttrp.parser.generated.TTRPParser
import java.nio.file.Files
import java.nio.file.Path

/** Parse output: the AST, all diagnostics (syntax + Stage-1.1 named rejects), and the verbatim source. */
data class TtrpParseResult(
    val document: TtrpDocument,
    val diagnostics: List<TtrpDiagnostic>,
    val source: String,
)

/** Standalone-expression parse output ([TtrpParser.parseExpression]). */
data class ExprParseResult(
    val expression: org.tatrman.ttrp.expr.Expression,
    val diagnostics: List<TtrpDiagnostic>,
    val source: String,
)

/**
 * The TTR-P parser front door. Mirrors the TTR-M `parseString`/`parseFile` shape.
 * ANTLR syntax errors surface as `TTRP-PRS-001` with an accurate [SourceLocation]
 * (collected via a [BaseErrorListener] — never printed to stderr). The default
 * error strategy stays on so a bad statement does not swallow the rest of the
 * document (Stage 1.1 error-recovery baseline).
 */
object TtrpParser {
    /** The function catalogue used for aggregate classification + the Stage 1.2 checks (T5-c-β; md-catalog slot deferred, D-h). */
    val catalog: FunctionCatalog = CompositeCatalog(listOf(BuiltinCatalog))

    fun parseString(
        source: String,
        fileName: String = "<memory>",
    ): TtrpParseResult {
        val syntax = mutableListOf<TtrpDiagnostic>()
        val listener = SyntaxErrorListener(fileName, syntax)

        val lexer = TTRPLexer(CharStreams.fromString(source))
        lexer.removeErrorListeners()
        lexer.addErrorListener(listener)
        val tokens = CommonTokenStream(lexer)
        val parser = TTRPParser(tokens)
        parser.removeErrorListeners()
        parser.addErrorListener(listener)

        val tree = parser.document()
        val walker = TtrpWalker(fileName, source, tokens, catalog)
        // A syntactically broken document can leave ANTLR error-recovery holes the walker does not expect
        // (a missing sub-rule → a Kotlin null-check failure). Those programs already carry their PRS-001
        // syntax errors; never let the walk crash on top of them — report the syntax errors over an empty
        // document instead. A walk failure on a CLEAN parse is a real bug and still propagates.
        val walked =
            try {
                walker.walk(tree)
            } catch (e: RuntimeException) {
                if (syntax.isEmpty()) throw e
                TtrpDocument(statements = emptyList(), location = SourceLocation(fileName, 1, 0, 1, 0, 0, 0))
            }
        // Phase 6: lower fragment interiors (`"""sql`/`"""pandas`) to canonical AST (C2-a-β).
        val decomposed =
            org.tatrman.ttrp.dialect.FragmentDecomposer
                .run(walked)
        val document = decomposed.document
        val diagnostics =
            syntax + walker.diagnostics + decomposed.diagnostics + TtrpChecks.run(document) +
                TtrpChecks.refuseConceptForms(walker.conceptForms)
        return TtrpParseResult(document = document, diagnostics = diagnostics, source = source)
    }

    fun parseFile(path: Path): TtrpParseResult = parseString(Files.readString(path), path.toString())

    /**
     * Parses a single standalone expression (the `expr` start rule) to the IR — the
     * expression-corpus entry point (the `golden.exprs` corpus and the single-line
     * `expr/negative` fixtures) and the parser of a cn `definition:` / `members:` / `filter:`
     * block (CN-D29). The `==` reject (EQ-001) and any syntax errors surface in
     * [ExprParseResult.diagnostics]. The whole input is one expression: a tail after it is a
     * `TTRP-PRS-001` (`standaloneExpr` anchors EOF — ttr-core#202; it used to be dropped silently,
     * so `code in descendants(Kind, 'X')` "parsed" as `code`).
     */
    fun parseExpression(
        source: String,
        fileName: String = "<expr>",
    ): ExprParseResult {
        val syntax = mutableListOf<TtrpDiagnostic>()
        val listener = SyntaxErrorListener(fileName, syntax)

        val lexer = TTRPLexer(CharStreams.fromString(source))
        lexer.removeErrorListeners()
        lexer.addErrorListener(listener)
        val tokens = CommonTokenStream(lexer)
        val parser = TTRPParser(tokens)
        parser.removeErrorListeners()
        parser.addErrorListener(listener)

        // 0.5: a standalone expression reads every cn form — the temporal infix even before a `:` (TTRP.g4
        // `temporalInfix()`; a document has routes there).
        parser.cnDefinition = true
        val tree = parser.standaloneExpr().expr()
        val walker = TtrpWalker(fileName, source, tokens, catalog)
        // As in [parseString]: a broken parse can leave recovery holes the walker does not expect — keep the
        // syntax errors and fold to a NULL literal rather than crash on top of them.
        val expr =
            try {
                walker.foldExpr(tree)
            } catch (e: RuntimeException) {
                if (syntax.isEmpty()) throw e
                org.tatrman.ttrp.expr.Literal(
                    org.tatrman.ttrp.expr.LiteralValue.Null,
                    SourceLocation(fileName, 1, 0, 1, 0, 0, 0),
                )
            }
        return ExprParseResult(expression = expr, diagnostics = syntax + walker.diagnostics, source = source)
    }

    private class SyntaxErrorListener(
        private val fileName: String,
        private val out: MutableList<TtrpDiagnostic>,
    ) : BaseErrorListener() {
        override fun syntaxError(
            recognizer: Recognizer<*, *>?,
            offendingSymbol: Any?,
            line: Int,
            charPositionInLine: Int,
            msg: String,
            e: RecognitionException?,
        ) {
            val token = offendingSymbol as? Token
            val location =
                if (token != null) {
                    // At EOF the span is empty (its text is `<EOF>`); a token's width counts code points, as the
                    // column does (review-119 L-1).
                    val width =
                        if (token.type == Token.EOF) 0 else token.text?.let { it.codePointCount(0, it.length) } ?: 1
                    SourceLocation(
                        file = fileName,
                        line = line,
                        column = charPositionInLine,
                        endLine = line,
                        endColumn = charPositionInLine + width,
                        offsetStart = token.startIndex,
                        offsetEnd = token.stopIndex + 1,
                    )
                } else {
                    SourceLocation(fileName, line, charPositionInLine, line, charPositionInLine + 1, -1, -1)
                }
            // a document only: in a cn definition the comparison is the language (review-120 F16)
            val hint =
                (recognizer as? TTRPParser)?.takeUnless { it.cnDefinition }?.let { temporalHint(it.inputStream, token) }
            out +=
                TtrpDiagnostic(
                    id = TtrpDiagnosticId.PRS_001,
                    severity = Severity.ERROR,
                    message = if (hint == null) msg else "$msg — $hint",
                    location = location,
                )
        }

        /**
         * 0.5 (review-119 M-2): in a document a temporal word followed by `:` is a route or a config key, never the cn
         * infix (TTRP.g4 `temporalInfix()`), so `x before :as_of` in a program fails to parse — say what it was. Only
         * where the `:` is written as a binding (`:as_of`, no space after it); `before: 1`, a key, gets no hint
         * (review-120 F16).
         */
        private fun temporalHint(
            stream: TokenStream,
            token: Token?,
        ): String? {
            if (token == null) return null

            fun near(
                from: Int,
                step: Int,
            ): Token? {
                var i = from + step
                while (i >= 0 && i < stream.size()) {
                    val t = stream.get(i)
                    if (t.channel == Token.DEFAULT_CHANNEL) return t
                    i += step
                }
                return null
            }
            val colon =
                when (token.type) {
                    in TEMPORAL_WORDS -> near(token.tokenIndex, 1)?.takeIf { it.type == TTRPLexer.COLON }
                    TTRPLexer.COLON -> token
                    else -> null
                } ?: return null
            val word = near(colon.tokenIndex, -1)?.takeIf { it.type in TEMPORAL_WORDS } ?: return null
            // `:as_of` — a binding: the name follows the colon with nothing between
            val bound = near(colon.tokenIndex, 1)?.takeIf { it.startIndex == colon.stopIndex + 1 } ?: return null
            if (bound.text.firstOrNull()?.let { it.isLetter() || it == '_' } != true) return null
            return "`${word.text} :…` is a cn definition's comparison (TTRP-CN-001); a TTR-P program compares the " +
                "instants (`<`, `<=`)"
        }
    }

    private val TEMPORAL_WORDS =
        setOf(TTRPLexer.AT_OR_BEFORE, TTRPLexer.BEFORE, TTRPLexer.CONTAINS, TTRPLexer.ENDED_BEFORE, TTRPLexer.OVERLAPS)
}
