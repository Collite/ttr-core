// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.lang

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.nio.file.Path

/**
 * S16 enforcement (the tripwire). [KeywordTable] is THE single source of TTR-P
 * keywords/operators; this spec asserts the set of single-quoted literal tokens in
 * `packages/grammar/src/TTRP.g4` equals the table's keyword/operator/punctuation
 * union. Add a bogus `'xor'` literal to the grammar and this spec fails — proving the
 * two can never silently diverge.
 *
 * The later fragment grammars (`TTRSql.g4`, `TTRPandas.g4`, `TTRB.g4`) get sibling
 * drift specs against this SAME [KeywordTable] in P6/P7 (C4-c synonym tables map
 * dialect spellings back to these ids) — T5-e's "one grammar, many skins".
 *
 * Union note: the grammar's port-kind and boolean-literal tokens (`out`/`err`,
 * `true`/`false`) are carried by [KeywordTable.reservedPorts]; `rejects` is a
 * reserved port NAME but never a lexer token (it is a plain identifier), so it is the
 * one reservedPorts entry excluded from the literal-parity union.
 */
class TtrpKeywordTableSpec :
    StringSpec({

        // Structural punctuation tokens — the non-keyword literals the grammar needs.
        val punctuation = setOf("(", ")", "{", "}", "[", "]", ",", ":", ".")

        // Single-literal LEXER rules only: `NAME : 'literal' ;`. This excludes STRING /
        // CHAR_STRING / TAGGED_BLOCK / comments / WS / NUMBER / IDENT (multi-part bodies).
        val singleLiteralRule =
            Regex("""^\s*[A-Z_][A-Z0-9_]*\s*:\s*'((?:\\.|[^'\\])*)'\s*;\s*$""", RegexOption.MULTILINE)

        fun grammarLiterals(): Set<String> {
            val grammar = locateGrammar()
            val text = Files.readString(grammar)
            return singleLiteralRule.findAll(text).map { it.groupValues[1] }.toSet()
        }

        "TTRP.g4 single-quoted literals equal the KeywordTable union (S16)" {
            val expected =
                KeywordTable.statementKeywords +
                    KeywordTable.recipeKeywords +
                    KeywordTable.conceptKeywords +
                    KeywordTable.durationUnits +
                    KeywordTable.expressionKeywords +
                    (KeywordTable.reservedPorts - "rejects") +
                    KeywordTable.operators.keys +
                    KeywordTable.rejectedSpellings.keys +
                    punctuation

            grammarLiterals() shouldBe expected
        }
    })

/** Resolves `packages/grammar/src/TTRP.g4` from the module test working directory (or by walking up). */
private fun locateGrammar(): Path {
    val candidates =
        listOf(
            Path.of("../../grammar/src/TTRP.g4"),
            Path.of("packages/grammar/src/TTRP.g4"),
        )
    candidates.firstOrNull { Files.exists(it) }?.let { return it }
    var dir: Path? = Path.of("").toAbsolutePath()
    while (dir != null) {
        val g = dir.resolve("packages/grammar/src/TTRP.g4")
        if (Files.exists(g)) return g
        dir = dir.parent
    }
    error("could not locate packages/grammar/src/TTRP.g4 from ${Path.of("").toAbsolutePath()}")
}
