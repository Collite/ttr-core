// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.lang

/**
 * S16 — THE single source of TTR-P keywords and operators. The checker consumes it,
 * and `TtrpKeywordTableSpec` drift-tests it against `packages/grammar/src/TTRP.g4`
 * so the grammar's single-quoted literal tokens and this table can never diverge.
 *
 * The later fragment grammars (`TTRSql.g4`, `TTRPandas.g4`, `TTRB.g4`) skin THIS
 * one expression grammar (T5-e); in P6/P7 they get sibling drift specs against this
 * SAME object (C4-c synonym tables map dialect spellings back to these ids). That is
 * why the table lives here and not inline in the grammar.
 */
object KeywordTable {
    /** Words that open or structure a statement (S12/C3). */
    val statementKeywords =
        setOf(
            "uses",
            "world",
            "import",
            "program",
            "container",
            "def",
            "target",
            "control",
            "after",
            "with",
            "finishes",
            "group",
            "by",
            "relation",
            "schema",
            // PL-P2.S1 (F-4-i/F-4-iv/F-4-ii): runtime params + on-failure islands. `on` is a
            // soft keyword (also in `identifier` — `on:` join arg); the rest are hard keywords.
            "param",
            "on",
            "failure",
            "of",
            "retries",
            "absorbs",
            // AG B5: `from "<file>"` — a file-backed container body; soft (also in `identifier`).
            "from",
        )

    /** Words that appear only inside expressions (S9/B-T5). `null` is a literal-keyword; see [booleanLiterals]. */
    val expressionKeywords =
        setOf(
            "and",
            "or",
            "not",
            "is",
            "null",
            "in",
            "between",
            "case",
            "when",
            "then",
            "else",
            "end",
            "cast",
            "as",
            "distinct",
        )

    /** Reserved port names (S10). `true`/`false`/`else` overlap literal/expression keywords by design. */
    val reservedPorts = setOf("in", "out", "err", "rejects", "true", "false", "else")

    /**
     * Boolean literal keywords. Kept explicit (parallel to how `null` sits in
     * [expressionKeywords]) so the S16 drift union accounts for the grammar's
     * `TRUE`/`FALSE` literal tokens.
     */
    val booleanLiterals = setOf("true", "false")

    /** Symbol operators -> catalogue id. `->` is chain/wiring, not an expression op (id `null`). */
    val operators =
        mapOf(
            "=" to "op.eq",
            "<>" to "op.neq",
            "<" to "op.lt",
            "<=" to "op.lte",
            ">" to "op.gt",
            ">=" to "op.gte",
            "+" to "op.add",
            "-" to "op.sub",
            "*" to "op.mul",
            "/" to "op.div",
            "->" to null,
        )

    /** Spellings that are lexable but rejected, mapped to their diagnostic id (S9). */
    val rejectedSpellings = mapOf("==" to "TTRP-EQ-001")
}
