// SPDX-License-Identifier: Apache-2.0
package org.tatrman.translator.dialects

import org.apache.calcite.sql.validate.SqlConformance
import org.apache.calcite.sql.validate.SqlDelegatingConformance

/**
 * TF-P2.S2 (G A8) — a dialect conformance that makes `RelToSqlConverter` write an ORDER BY key as its
 * expression, never as an ordinal.
 *
 * A query that orders by an expression outside its select list plans as
 * `Project(trim) ← Sort ← Project(select + key)`. With `isSortByOrdinal()` (true for SQL Server and
 * Postgres) the key renders as `ORDER BY 2`, and `SqlImplementor.Result.needNewSubQuery` then refuses to
 * merge the trimming Project into that SELECT ("cannot merge a Project that contains sort by ordinal
 * under it") — the output became `SELECT [ID] FROM (SELECT [ID], LEN([NAME]) … ORDER BY 2) AS [t]`,
 * which SQL Server rejects (ORDER BY in a derived table without TOP) and no engine has to keep in order.
 * With the expression form the Project merges: `SELECT [ID] FROM [dbo].[A] ORDER BY LEN([NAME])`.
 *
 * Only unparse reads a dialect's conformance; parsing and validation keep their own.
 */
internal class SortByExpressionConformance(
    delegate: SqlConformance,
) : SqlDelegatingConformance(delegate) {
    override fun isSortByOrdinal(): Boolean = false
}
