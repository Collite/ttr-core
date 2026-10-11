// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.dialect.b

import org.antlr.v4.runtime.Token
import org.tatrman.ttrp.dialect.sql.TtrSqlLoc
import org.tatrman.ttrp.diagnostics.Severity
import org.tatrman.ttrp.diagnostics.TtrpDiagnostic
import org.tatrman.ttrp.diagnostics.TtrpDiagnosticId
import org.tatrman.ttrp.parser.generated.TTRBLexer
import org.tatrman.ttrp.parser.generated.TTRBParser

/**
 * Names the curated TTR-B rejects from the skin-classified token stream BEFORE the parser can emit a
 * bare syntax error (C2-g: rejects are named grammar rejects), and gives a failed parse the most
 * specific help it can (AG B6). One primary diagnostic per fragment, first match in a deterministic
 * priority order (P2, no NLP). Messages and suggested sentences come from the skin's reject table
 * (`rejects.<lang>.yaml`, single source), never inlined.
 */
class TtrbRejectScanner(
    private val table: TtrbRejectTable,
    private val loc: TtrSqlLoc,
    private val skin: TtrbSkin = TtrbSkin.EN,
) {
    /** Default-channel tokens (`#` comments already off-channel; INDENT / DEDENT included). */
    fun scan(
        tokens: List<Token>,
        indentProblems: List<Token> = emptyList(),
    ): TtrpDiagnostic? {
        // 1. C-style comment lexis `//` / `/*` (S19) — highest priority (it hides the real sentence).
        (adjacent(tokens, TTRBLexer.SLASH, TTRBLexer.SLASH) ?: adjacent(tokens, TTRBLexer.SLASH, TTRBLexer.STAR))
            ?.let { return diag("TTRP-B-005", it) }
        // 2. A character no TTR-B token admits: the lexer's UNMATCHED catch-all.
        tokens.firstOrNull { it.type == TTRBLexer.UNMATCHED }?.let { return diag("TTRP-B-101", it, it.text) }
        // 3. `==` used as equality (S9) — the shared canonical diagnostic, not a new B id.
        tokens.firstOrNull { it.type == TTRBLexer.EQEQ }?.let { return diag("TTRP-EQ-001", it) }
        val starts = sentenceStarts(tokens)
        // 4. Sentence-initial words this skin's table names (update / insert / DDL / pivot).
        for (tok in starts) {
            if (tok.type != TTRBParser.IDENT) continue
            table.triggeredBy(tok.text)?.let { return diag(it.id, tok, tok.text) }
        }
        // 5. A sentence written in another skin's language: its first word is a sentence verb (or a
        //    named reject) there, but not a keyword here — the fragment's marker names the wrong language.
        otherSkinSentence(starts)?.let { return diag("TTRP-B-006", it, it.text) }
        // 6. Unknown verbose comparison: `is <word> <operand>` where <word> is not a table phrase.
        unknownVerbose(tokens)?.let { return diag("TTRP-B-007", it, it.text) }
        // 7. A block header not ending its line with `:` + an indented sentence (B3).
        malformedHeader(tokens, starts)?.let { return diag("TTRP-B-102", it) }
        // 8. A sentence whose indentation matches no open block (B3).
        indentProblems.firstOrNull()?.let { return diag("TTRP-B-103", it) }
        // 9. Catch-all: any sentence-initial off-roster word.
        starts.firstOrNull { it.type == TTRBParser.IDENT }?.let { return diag("TTRP-B-004", it, it.text) }
        return null
    }

    /**
     * The help for a failed parse (no curated reject matched): a malformed sentence of a known shape
     * names its shape (e-mail B-104, set B-105, manual task B-106, count B-107, attach B-108, block
     * header B-102); a keyword standing where a name belongs is B-109; anything else is B-004.
     */
    fun syntax(
        error: TtrbSyntax.SyntaxError,
        tokens: List<Token>,
    ): TtrpDiagnostic {
        val offending = error.token?.takeIf { it.type != Token.EOF } ?: tokens.lastOrNull()
        val at = offending ?: return generic(null)
        val start = sentenceStartOf(tokens, at)
        val token = error.token
        val keywordAsName =
            token != null && token.type in keywordTypes && error.expected?.contains(TTRBParser.IDENT) == true
        // A keyword standing as the sentence's LAST word where a name belongs (`jako a.`, `na výstup výsledek.`)
        // is the clearest help, before the sentence-shape one.
        if (keywordAsName && next(tokens, token!!)?.type == TTRBLexer.DOT) return diag("TTRP-B-109", token, token.text)
        val shape =
            when (start?.type) {
                TTRBParser.SEND -> if (next(tokens, start)?.type == TTRBParser.EMAIL) "TTRP-B-104" else "TTRP-B-111"
                TTRBParser.SET -> "TTRP-B-105"
                TTRBParser.CREATE -> if (next(tokens, start)?.type == TTRBParser.MANUAL_TASK) "TTRP-B-106" else null
                TTRBParser.COUNT -> "TTRP-B-107"
                TTRBParser.ATTACH -> "TTRP-B-108"
                TTRBParser.IF -> "TTRP-B-102"
                else -> null
            }
        if (shape != null) return diag(shape, start!!)
        if (keywordAsName) return diag("TTRP-B-109", token!!, token.text)
        return diag("TTRP-B-004", at, (start ?: at).text)
    }

    private fun generic(at: Token?): TtrpDiagnostic {
        val row = table.entry("TTRP-B-004")
        return TtrpDiagnostic(
            TtrpDiagnosticId.B_004,
            Severity.ERROR,
            row.message("?"),
            at?.let { loc.of(it) } ?: org.tatrman.ttrp.ast.SourceLocation.UNKNOWN,
            row.suggest,
        )
    }

    /**
     * Tokens in sentence-start position: the first token, or the first token on a line after a
     * sentence-terminating `.` or a block header's `:` (a `.` at line end — distinct from a dotted-ref
     * `.` like `sales.account_id`, which sits mid-line). INDENT / DEDENT are layout, never a sentence.
     */
    private fun sentenceStarts(tokens: List<Token>): List<Token> {
        val out = mutableListOf<Token>()
        var prev: Token? = null
        for (t in tokens) {
            if (t.type == Token.EOF) break
            if (t.type == TTRBParser.INDENT || t.type == TTRBParser.DEDENT) continue
            val p = prev
            if (p == null || ((p.type == TTRBLexer.DOT || p.type == TTRBParser.COLON) && t.line > p.line)) out += t
            prev = t
        }
        return out
    }

    private fun sentenceStartOf(
        tokens: List<Token>,
        at: Token,
    ): Token? = sentenceStarts(tokens).lastOrNull { it.startIndex <= at.startIndex }

    private fun next(
        tokens: List<Token>,
        t: Token,
    ): Token? = tokens.getOrNull(tokens.indexOf(t) + 1)

    /** A sentence-initial identifier another skin spells as a sentence verb, or names in its reject table. */
    private fun otherSkinSentence(starts: List<Token>): Token? {
        val others = TtrbSkin.all.filter { it !== skin }
        return starts.firstOrNull { tok ->
            tok.type == TTRBParser.IDENT &&
                others.any { other ->
                    other.typesOf(tok.text)?.firstOrNull()?.let { it in sentenceStarters } == true ||
                        TtrB.rejects(other).triggeredBy(tok.text) != null
                }
        }
    }

    /** An `If` header whose line does not end in `:` before the sentence ends, or whose `:` opens no block. */
    private fun malformedHeader(
        tokens: List<Token>,
        starts: List<Token>,
    ): Token? {
        for (header in starts.filter { it.type == TTRBParser.IF }) {
            val i = tokens.indexOf(header)
            val end =
                tokens
                    .drop(i + 1)
                    .firstOrNull { it.type == TTRBLexer.DOT || it.type == TTRBParser.COLON || it.type == Token.EOF }
                    ?: return header
            if (end.type != TTRBParser.COLON) return header
            if (next(tokens, end)?.type != TTRBParser.INDENT) return header
        }
        return null
    }

    private fun adjacent(
        tokens: List<Token>,
        a: Int,
        b: Int,
    ): Token? {
        for (i in 0 until tokens.size - 1) {
            if (tokens[i].type == a && tokens[i + 1].type == b) return tokens[i]
        }
        return null
    }

    private val operandStart = setOf(TTRBLexer.IDENT, TTRBLexer.NUMBER, TTRBLexer.STRING, TTRBLexer.CHAR_STRING)

    private fun unknownVerbose(tokens: List<Token>): Token? {
        for (i in 0 until tokens.size - 2) {
            if (tokens[i].type == TTRBParser.IS &&
                tokens[i + 1].type == TTRBLexer.IDENT &&
                tokens[i + 2].type in operandStart
            ) {
                return tokens[i + 1]
            }
        }
        return null
    }

    private fun diag(
        id: String,
        at: Token,
        word: String? = null,
    ): TtrpDiagnostic {
        val row = table.entry(id)
        return TtrpDiagnostic(
            id = TtrpDiagnosticId.entries.first { it.id == id },
            severity = Severity.ERROR,
            message = row.message(word),
            location = loc.of(at),
            suggestedAlternative = row.suggest,
        )
    }

    companion object {
        /** The token types a sentence can start with — the FIRST set of the grammar's `item` rule (incl. `If`). */
        val sentenceStarters: Set<Int> by lazy {
            val atn = TTRBParser._ATN
            atn.nextTokens(atn.ruleToStartState[TTRBParser.RULE_item]).toSet()
        }

        /** Every keyword token type (spelled by a skin). */
        val keywordTypes: Set<Int> by lazy {
            TtrbSkin.KEYWORD_TOKENS
                .mapNotNull { name ->
                    (1..TTRBParser.VOCABULARY.maxTokenType).firstOrNull {
                        TTRBParser.VOCABULARY.getSymbolicName(it) ==
                            name
                    }
                }.toSet()
        }
    }
}
