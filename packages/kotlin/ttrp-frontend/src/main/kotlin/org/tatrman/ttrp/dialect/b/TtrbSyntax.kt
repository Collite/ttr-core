// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.dialect.b

import org.antlr.v4.runtime.BaseErrorListener
import org.antlr.v4.runtime.CharStream
import org.antlr.v4.runtime.CharStreams
import org.antlr.v4.runtime.CommonToken
import org.antlr.v4.runtime.CommonTokenStream
import org.antlr.v4.runtime.ListTokenSource
import org.antlr.v4.runtime.RecognitionException
import org.antlr.v4.runtime.Recognizer
import org.antlr.v4.runtime.Token
import org.antlr.v4.runtime.TokenSource
import org.antlr.v4.runtime.misc.Pair
import org.tatrman.ttrp.parser.generated.TTRBLexer
import org.tatrman.ttrp.parser.generated.TTRBParser

/**
 * The skinned TTR-B token source + parse (AG B2). The generated lexer produces identifiers,
 * literals and punctuation only; [tokens] then re-types every run of words the active
 * [TtrbSkin] spells into its keyword token(s) — longest phrase first, case- and
 * diacritic-insensitive, a hyphenated word (`e-mail`) only where the skin spells it so (`a-b`
 * stays arithmetic). Every other word stays an `IDENT` with its exact text.
 */
object TtrbSyntax {
    data class SyntaxError(
        val token: Token?,
        val line: Int,
        val column: Int,
        val message: String,
        /** The token types the parser expected at the error (for the keyword-as-name help, B-109). */
        val expected: org.antlr.v4.runtime.misc.IntervalSet? = null,
    )

    data class Parsed(
        val tree: TTRBParser.FragmentProgramContext,
        /** The classified default-channel tokens (EOF excluded; INDENT / DEDENT included). */
        val tokens: List<Token>,
        val syntaxErrors: List<SyntaxError>,
        /** Sentence-start tokens whose indentation matches no enclosing block level (B3). */
        val indentProblems: List<Token> = emptyList(),
    )

    /** The classified token list of a source + the indentation problems found while laying out blocks. */
    data class Lexed(
        val tokens: List<Token>,
        val indentProblems: List<Token>,
    )

    /** All tokens of [source] (hidden channel included, EOF last) with the [skin]'s keywords typed. */
    fun tokens(
        source: String,
        skin: TtrbSkin,
    ): List<Token> = lex(source, skin).tokens

    /** [tokens] plus the block layout's indentation problems. */
    fun lex(
        source: String,
        skin: TtrbSkin,
    ): Lexed {
        val lexer = TTRBLexer(CharStreams.fromString(source))
        lexer.removeErrorListeners()
        val raw = mutableListOf<Token>()
        while (true) {
            val t = lexer.nextToken()
            raw += t
            if (t.type == Token.EOF) break
        }
        val sourcePair = Pair<TokenSource, CharStream>(lexer, lexer.inputStream)
        val problems = mutableListOf<Token>()
        val laidOut = layout(classify(raw, skin, sourcePair), sourcePair, problems)
        return Lexed(laidOut, problems)
    }

    /** Parses already-classified [tokens] (from [tokens]). */
    fun parse(
        tokens: List<Token>,
        indentProblems: List<Token> = emptyList(),
    ): Parsed {
        val stream = CommonTokenStream(ListTokenSource(tokens))
        val parser = TTRBParser(stream)
        parser.removeErrorListeners()
        val errors = mutableListOf<SyntaxError>()
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
                    val expected = runCatching { (r as? org.antlr.v4.runtime.Parser)?.expectedTokens }.getOrNull()
                    errors += SyntaxError(sym as? Token, line, col, msg, expected)
                }
            },
        )
        val tree = parser.fragmentProgram()
        val defaults = tokens.filter { it.channel == Token.DEFAULT_CHANNEL && it.type != Token.EOF }
        return Parsed(tree, defaults, errors, indentProblems)
    }

    fun parse(
        source: String,
        skin: TtrbSkin,
    ): Parsed = lex(source, skin).let { parse(it.tokens, it.indentProblems) }

    // ---- block layout (B3) ------------------------------------------------------------

    /**
     * Injects INDENT / DEDENT around block bodies. Only SENTENCE-START lines count — the first token
     * of the fragment, or a line's first token after a `.` / block-header `:` on an earlier line; a
     * continuation line (the sentence is still open) never changes the level. After a header, a
     * deeper sentence opens the block (INDENT); a shallower one closes blocks down to its level
     * (DEDENT each). Indentation is RELATIVE (the first sentence sets the base, so a uniformly
     * indented embedded fragment reads the same); a line below the base re-bases; a dedent to a
     * column matching no open level is recorded in [problems] (TTRP-B-103).
     */
    private fun layout(
        tokens: List<Token>,
        source: Pair<TokenSource, CharStream>,
        problems: MutableList<Token>,
    ): List<Token> {
        val out = ArrayList<Token>(tokens.size + 8)
        val levels = ArrayDeque<Int>()
        var prev: Token? = null
        for (t in tokens) {
            if (t.type == Token.EOF) {
                while (levels.size > 1) {
                    levels.removeLast()
                    out += marker(TTRBParser.DEDENT, after = prev, at = t, source = source)
                }
                out += t
                continue
            }
            if (t.channel != Token.DEFAULT_CHANNEL) {
                out += t
                continue
            }
            val p = prev
            val sentenceStart =
                p == null || ((p.type == TTRBParser.DOT || p.type == TTRBParser.COLON) && t.line > p.line)
            if (sentenceStart) {
                val col = t.charPositionInLine
                when {
                    levels.isEmpty() -> levels.addLast(col)
                    p?.type == TTRBParser.COLON && col > levels.last() -> {
                        levels.addLast(col)
                        out += marker(TTRBParser.INDENT, after = null, at = t, source = source)
                    }
                    col < levels.last() -> {
                        while (levels.size > 1 && col < levels.last()) {
                            levels.removeLast()
                            out += marker(TTRBParser.DEDENT, after = p, at = t, source = source)
                        }
                        if (col < levels.last()) {
                            levels[0] = col // below the base, outside every block: re-base
                        } else if (col > levels.last()) {
                            problems += t // between two levels — inconsistent dedent
                        }
                    }
                }
            }
            out += t
            prev = t
        }
        return out
    }

    /** A zero-width INDENT (at [at]) or DEDENT (right after [after], else at [at]) token. */
    private fun marker(
        type: Int,
        after: Token?,
        at: Token,
        source: Pair<TokenSource, CharStream>,
    ): Token {
        val start = if (after != null) after.stopIndex + 1 else at.startIndex
        return CommonToken(source, type, Token.DEFAULT_CHANNEL, start, start - 1).apply {
            text = ""
            if (after != null) {
                line = after.line
                charPositionInLine = after.charPositionInLine + (after.text?.length ?: 0)
            } else {
                line = at.line
                charPositionInLine = at.charPositionInLine
            }
        }
    }

    // ---- keyword classification ----------------------------------------------------

    /** One word of a candidate phrase: its folded text and the raw-token range it spans. */
    private class Word(
        val folded: String,
        val from: Int,
        val toExclusive: Int,
    )

    private class Match(
        val words: List<Word>,
        val types: IntArray,
    )

    private fun classify(
        raw: List<Token>,
        skin: TtrbSkin,
        source: Pair<TokenSource, CharStream>,
    ): List<Token> {
        val out = ArrayList<Token>(raw.size)
        var i = 0
        while (i < raw.size) {
            val t = raw[i]
            if (t.channel != Token.DEFAULT_CHANNEL || t.type != TTRBLexer.IDENT) {
                out += t
                i++
                continue
            }
            val m = longest(raw, i, skin.trie, emptyList())
            if (m == null) {
                out += t
                i++
                continue
            }
            out += keywordTokens(raw, m, source)
            i = m.words.last().toExclusive
        }
        return out
    }

    /** Longest phrase of the trie starting at raw index [i] (words are consecutive default-channel tokens). */
    private fun longest(
        raw: List<Token>,
        i: Int,
        node: TtrbSkin.Node,
        acc: List<Word>,
    ): Match? {
        var best: Match? = null
        for (w in wordsAt(raw, i)) {
            val child = node.children[w.folded] ?: continue
            val path = acc + w
            val here = child.types?.let { Match(path, it) }
            val deeper = longest(raw, w.toExclusive, child, path)
            val cand = listOfNotNull(here, deeper).maxByOrNull { it.words.last().toExclusive }
            if (cand != null &&
                (best == null || cand.words.last().toExclusive > best.words.last().toExclusive)
            ) {
                best = cand
            }
        }
        return best
    }

    /** The word readings at raw index [i]: the plain identifier, and each hyphenated run (`e-mail`). */
    private fun wordsAt(
        raw: List<Token>,
        i: Int,
    ): List<Word> {
        val first = raw.getOrNull(i) ?: return emptyList()
        if (first.channel != Token.DEFAULT_CHANNEL || first.type != TTRBLexer.IDENT) return emptyList()
        val words = mutableListOf(Word(TtrbSkin.fold(first.text), i, i + 1))
        var k = i
        val text = StringBuilder(first.text)
        while (true) {
            val dash = raw.getOrNull(k + 1) ?: break
            val next = raw.getOrNull(k + 2) ?: break
            if (dash.type != TTRBLexer.MINUS || next.type != TTRBLexer.IDENT) break
            if (dash.startIndex != raw[k].stopIndex + 1 || next.startIndex != dash.stopIndex + 1) break
            text.append('-').append(next.text)
            k += 2
            words += Word(TtrbSkin.fold(text.toString()), i, k + 1)
        }
        return words
    }

    /** The keyword token(s) replacing a matched phrase: one per word when the counts agree, else each spans it all. */
    private fun keywordTokens(
        raw: List<Token>,
        m: Match,
        source: Pair<TokenSource, CharStream>,
    ): List<Token> {
        val spans: List<kotlin.Pair<Token, Token>> =
            if (m.types.size == m.words.size) {
                m.words.map { raw[it.from] to raw[it.toExclusive - 1] }
            } else {
                List(m.types.size) { raw[m.words.first().from] to raw[m.words.last().toExclusive - 1] }
            }
        return m.types.mapIndexed { idx, type ->
            val (a, b) = spans[idx]
            CommonToken(source, type, Token.DEFAULT_CHANNEL, a.startIndex, b.stopIndex).apply {
                line = a.line
                charPositionInLine = a.charPositionInLine
            }
        }
    }
}
