// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.dialect.sql

import org.antlr.v4.runtime.BaseErrorListener
import org.antlr.v4.runtime.CharStreams
import org.antlr.v4.runtime.CommonTokenStream
import org.antlr.v4.runtime.RecognitionException
import org.antlr.v4.runtime.Recognizer
import org.antlr.v4.runtime.Token
import org.tatrman.ttrp.ast.Chain
import org.tatrman.ttrp.ast.FragmentDecomposition
import org.tatrman.ttrp.ast.OpCall
import org.tatrman.ttrp.ast.SourceLocation
import org.tatrman.ttrp.ast.Statement
import org.tatrman.ttrp.dialect.RejectTable
import org.tatrman.ttrp.diagnostics.Severity
import org.tatrman.ttrp.diagnostics.TtrpDiagnostic
import org.tatrman.ttrp.diagnostics.TtrpDiagnosticId
import org.tatrman.ttrp.parser.TtrpParser
import org.tatrman.ttrp.parser.generated.TTRSqlLexer
import org.tatrman.ttrp.parser.generated.TTRSqlParser

/**
 * The TTR-SQL fragment front door (T6.1.7). Peeled `"""sql` interior + host offset →
 * canonical AST + host-mapped diagnostics. A curated reject (T6.1.1 table) is named
 * BEFORE any bare syntax error (C2-g); a clean parse decomposes clause-wise (C2-a-β)
 * and runs the S15 ordered-LIMIT check (`TTRP-SQL-014`). The interior is never rewritten
 * (C2-f) — this parse is derived from the verbatim `sourceText`.
 */
object TtrSql {
    val rejectTable: RejectTable = RejectTable.load("/rejects/ttr-sql.rejects.toml")

    fun decompose(
        sourceText: String,
        interior: SourceLocation,
        outPort: String?,
        schemas: Map<String, List<String>> = emptyMap(),
    ): FragmentDecomposition {
        val loc = TtrSqlLoc(interior)
        val diags = mutableListOf<TtrpDiagnostic>()

        val lexer = TTRSqlLexer(CharStreams.fromString(sourceText))
        lexer.removeErrorListeners()
        // A character the lexer cannot read used to be DROPPED silently — `:tolerance` read as a column
        // `tolerance`. It is now the generic reject at its position (AU-core-P0).
        var lexError: SourceLocation? = null
        lexer.addErrorListener(
            object : BaseErrorListener() {
                override fun syntaxError(
                    r: Recognizer<*, *>?,
                    sym: Any?,
                    line: Int,
                    col: Int,
                    msg: String,
                    e: RecognitionException?,
                ) {
                    val index = (e as? org.antlr.v4.runtime.LexerNoViableAltException)?.startIndex ?: 0
                    if (lexError == null) lexError = loc.at(line, col, index)
                }
            },
        )
        val tokenStream = CommonTokenStream(lexer)
        tokenStream.fill()
        val defaultTokens = tokenStream.tokens.filter { it.channel == Token.DEFAULT_CHANNEL }

        // 1) Curated reject scan (named grammar rejects) — one primary diagnostic, wins over syntax noise.
        val reject = TtrSqlRejectScanner(rejectTable, loc).scan(defaultTokens)
        if (reject != null) return FragmentDecomposition(emptyList(), listOf(reject), emptyList())

        lexError?.let {
            return FragmentDecomposition(emptyList(), listOf(TtrSqlRejectScanner.generic(rejectTable, it)), emptyList())
        }

        // 2) Parse.
        val parser = TTRSqlParser(tokenStream)
        parser.removeErrorListeners()
        var syntaxError: SourceLocation? = null
        parser.addErrorListener(
            object : BaseErrorListener() {
                override fun syntaxError(
                    r: Recognizer<*, *>?,
                    sym: Any?,
                    line: Int,
                    col: Int,
                    msg: String,
                    e: RecognitionException?,
                ) {
                    if (syntaxError == null) {
                        syntaxError = (sym as? Token)?.let { loc.of(it) } ?: interior
                    }
                }
            },
        )
        val tree = parser.fragmentProgram()
        if (syntaxError != null) {
            return FragmentDecomposition(
                emptyList(),
                listOf(TtrSqlRejectScanner.generic(rejectTable, syntaxError!!)),
                emptyList(),
            )
        }

        // 3) Decompose.
        val result = TtrSqlDecomposer(loc, TtrpParser.catalog, schemas).decompose(tree, outPort)
        diags += orderedLimitCheck(result.statements)
        return FragmentDecomposition(result.statements, diags, result.derivedInPorts, refOperands = result.refOperands)
    }

    /**
     * S15 → `TTRP-SQL-014`: a `limit` with no `sort` governing its chain is non-deterministic.
     * A graph-level check on the decomposed statements (surface-independent — the pandas
     * dialect reuses it). Within one chain a `sort` before the `limit` satisfies it.
     */
    private fun orderedLimitCheck(statements: List<Statement>): List<TtrpDiagnostic> {
        val out = mutableListOf<TtrpDiagnostic>()

        fun scan(chain: Chain) {
            var sawSort = false
            for (elem in chain.elements) {
                if (elem is OpCall && elem.name == "sort") sawSort = true
                if (elem is OpCall && elem.name == "limit" && !sawSort) {
                    val entry = rejectTable.entry("TTRP-SQL-014")
                    out +=
                        TtrpDiagnostic(
                            id = TtrpDiagnosticId.SQL_014,
                            severity = Severity.ERROR,
                            message = entry.message,
                            location = elem.location,
                            suggestedAlternative = entry.suggest,
                        )
                }
            }
        }
        for (s in statements) {
            when (s) {
                is org.tatrman.ttrp.ast.Assignment -> scan(s.chain)
                is org.tatrman.ttrp.ast.ChainStmt -> scan(s.chain)
                else -> Unit
            }
        }
        return out
    }
}
