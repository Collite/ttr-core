// SPDX-License-Identifier: Apache-2.0
package org.tatrman.translator.codec.sql

/** The text preparation shared by translation and standalone schema detection. */
object PreParseRails {
    fun prepare(source: String): PreparedSource {
        // Calcite parses a single statement and rejects a trailing terminator (`Encountered ";"`).
        // Strip every trailing terminator; semicolons inside literals stay intact.
        val terminated = stripTerminators(source)
        // MSSQL pre-parse rails, BEFORE detection or validation parses the SQL:
        //  1. Bracket identifiers become double-quoted identifiers that Calcite accepts.
        //  2. TOP: `SELECT [DISTINCT] TOP n …` → `… FETCH FIRST n ROWS ONLY`. The limit flows
        //     through the existing Sort/LimitOffset path and the MSSQL dialect renders it back.
        //  3. Table hints: pull `WITH (NOLOCK)` out (a post-alias position the grammar can't read).
        //     `hintsByTable` rides through to PlanNodeEncoder, which stamps the hints onto the
        //     matching scan; MSSQL unparse re-emits them. CTEs / literals / identifiers are skipped.
        val hints = TableHintExtractor.extract(TopClauseExtractor.rewrite(BracketQuoteRewriter.rewrite(terminated)))
        // Hint removal can expose another trailing terminator; preparation stays idempotent.
        return PreparedSource(cleanedSql = stripTerminators(hints.cleanedSql), hintsByTable = hints.byTable)
    }

    private fun stripTerminators(source: String): String {
        var sql = source.trimEnd()
        while (sql.endsWith(';')) sql = sql.dropLast(1).trimEnd()
        return sql
    }
}

data class PreparedSource(
    val cleanedSql: String,
    val hintsByTable: Map<String, List<TableHintSpec>>,
)
