// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.parser.loader

import org.antlr.v4.runtime.BaseErrorListener
import org.antlr.v4.runtime.CharStreams
import org.antlr.v4.runtime.CommonTokenStream
import org.antlr.v4.runtime.RecognitionException
import org.antlr.v4.runtime.Recognizer
import org.antlr.v4.runtime.Token
import org.tatrman.ttr.parser.generated.TTRLexer
import org.tatrman.ttr.parser.generated.TTRParser

/**
 * Whether a string is a grammar `id` — `idPart ( '.' idPart )*` — and so may be written BARE in a
 * `.ttrg` (VL contracts V-12); anything else is written as a string literal. Decided by running the
 * grammar's own `id` rule, so it can never drift from `TTR.g4` (the viewer's TypeScript twin keeps a
 * word list that a test recomputes from the grammar).
 */
object TtrId {
    fun isId(s: String): Boolean {
        if (s.isEmpty()) return false
        var bad = false
        val listener =
            object : BaseErrorListener() {
                override fun syntaxError(
                    recognizer: Recognizer<*, *>?,
                    offendingSymbol: Any?,
                    line: Int,
                    charPositionInLine: Int,
                    msg: String?,
                    e: RecognitionException?,
                ) {
                    bad = true
                }
            }
        val lexer = TTRLexer(CharStreams.fromString(s))
        lexer.removeErrorListeners()
        lexer.addErrorListener(listener)
        val tokens = CommonTokenStream(lexer)
        tokens.fill()
        // every character must sit in a default-channel token: no skipped whitespace, no comment
        val all = tokens.tokens.filter { it.type != Token.EOF }
        if (bad ||
            all.any { it.channel != Token.DEFAULT_CHANNEL } ||
            all.sumOf { it.text.length } != s.length
        ) {
            return false
        }
        val parser = TTRParser(tokens)
        parser.removeErrorListeners()
        parser.addErrorListener(listener)
        parser.id()
        return !bad && tokens.LA(1) == Token.EOF
    }

    /** One ref as the `.ttrg` writer spells it: bare when it is an `id`, else a string literal (V-12). */
    fun token(s: String): String = if (isId(s)) s else "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}
