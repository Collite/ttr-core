// SPDX-License-Identifier: Apache-2.0
package org.tatrman.translator.codec.sql

/** The text preparation shared by translation and standalone schema detection. */
object PreParseRails {
    fun prepare(source: String): PreparedSource {
        // Calcite parses a single statement and rejects a trailing terminator (`Encountered ";"`).
        // Strip one final non-whitespace `;`; semicolons inside literals stay intact.
        val terminated = source.trimEnd().removeSuffix(";").trimEnd()
        // MSSQL pre-parse rails, BEFORE detection or validation parses the SQL:
        //  1. Bracket identifiers become double-quoted identifiers that Calcite accepts.
        //  2. TOP: `SELECT [DISTINCT] TOP n …` → `… FETCH FIRST n ROWS ONLY`. The limit flows
        //     through the existing Sort/LimitOffset path and the MSSQL dialect renders it back.
        //  3. Table hints: pull `WITH (NOLOCK)` out (a post-alias position the grammar can't read).
        //     `hintsByTable` rides through to PlanNodeEncoder, which stamps the hints onto the
        //     matching scan; MSSQL unparse re-emits them. CTEs / literals / identifiers are skipped.
        val hints = TableHintExtractor.extract(TopClauseExtractor.rewrite(BracketQuoteRewriter.rewrite(terminated)))
        // Hint removal can leave trailing whitespace; normalize it here so preparation is idempotent.
        return PreparedSource(cleanedSql = hints.cleanedSql.trimEnd(), hintsByTable = hints.byTable)
    }
}

data class PreparedSource(
    val cleanedSql: String,
    val hintsByTable: Map<String, List<TableHintSpec>>,
)
