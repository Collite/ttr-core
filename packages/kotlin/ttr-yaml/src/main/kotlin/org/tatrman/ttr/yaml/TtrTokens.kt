// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.yaml

import org.antlr.v4.runtime.CommonTokenStream
import org.antlr.v4.runtime.Token
import org.tatrman.ttr.parser.generated.TTRLexer

/**
 * Every recursive cycle in TTR.g4 passes through a matching [], {} or () pair.
 * Check token types on the default channel before every codec-owned TTRParser:
 * mismatched closers must not cancel openers and expose ANTLR error recovery to unbounded recursion.
 * Valid grammar input always closes the same kind it opens; strings/comments are not bracket tokens.
 */
internal object TtrTokens {
    fun checkNesting(
        tokens: CommonTokenStream,
        invalid: (Token, String) -> Nothing,
    ) {
        tokens.fill()
        val openers = ArrayDeque<Int>()
        for (token in tokens.tokens) {
            if (token.channel != Token.DEFAULT_CHANNEL) continue
            when (token.type) {
                TTRLexer.LBRACK, TTRLexer.LBRACE, TTRLexer.LPAREN -> {
                    openers.addLast(token.type)
                    if (openers.size > CanonicalYaml.MAX_NESTING_DEPTH) {
                        invalid(token, "nesting too deep (limit ${CanonicalYaml.MAX_NESTING_DEPTH})")
                    }
                }
                TTRLexer.RBRACK, TTRLexer.RBRACE, TTRLexer.RPAREN -> {
                    val expected =
                        when (token.type) {
                            TTRLexer.RBRACK -> TTRLexer.LBRACK
                            TTRLexer.RBRACE -> TTRLexer.LBRACE
                            else -> TTRLexer.LPAREN
                        }
                    if (openers.lastOrNull() != expected) invalid(token, "unmatched '${token.text}'")
                    openers.removeLast()
                }
            }
        }
    }
}
