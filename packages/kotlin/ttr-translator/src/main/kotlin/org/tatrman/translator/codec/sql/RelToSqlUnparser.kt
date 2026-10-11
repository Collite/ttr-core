// SPDX-License-Identifier: Apache-2.0
package org.tatrman.translator.codec.sql

import org.tatrman.translate.v1.SqlDialect as SqlDialectProto
import org.apache.calcite.rel.RelNode
import org.apache.calcite.rel.rel2sql.RelToSqlConverter
import org.apache.calcite.sql.SqlDialect
import org.apache.calcite.sql.SqlIdentifier
import org.apache.calcite.sql.SqlNode
import org.apache.calcite.sql.dialect.MssqlSqlDialect
import org.apache.calcite.sql.dialect.PostgresqlSqlDialect
import org.apache.calcite.sql.util.SqlShuttle
import org.tatrman.translator.dialects.Dialects

/**
 * Unparse a [RelNode] back to dialect-specific SQL.
 *
 * Calcite engagement rule #1: never reach for `MssqlSqlDialect.DEFAULT`
 * directly — go through the [Dialects] registry which constructs the dialect
 * with `databaseMajorVersion = 11` so OFFSET/FETCH stays enabled.
 *
 * Calcite engagement rule #8 (acceptable noise): for sort keys with explicit
 * `NULLS FIRST` / `NULLS LAST`, Calcite expands the directive into a
 * `CASE WHEN ... IS NULL THEN 0 ELSE 1 END, <key>` pair. The generated SQL
 * is correct and produces identical row ordering; v1 accepts the visual
 * noise rather than fighting Calcite for a stylistic difference.
 */
object RelToSqlUnparser {
    /**
     * Unparsed SQL plus the order its `?` placeholders appear in.
     *
     * [dynamicParamOrder] is `SqlString.getDynamicParameters()` — the `RexDynamicParam` indices in
     * left-to-right `?`-appearance order (the order a JDBC `PreparedStatement` binds positions).
     * Empty when the SQL carries no dynamic parameters. Feeds
     * [org.tatrman.translator.params.PositionalParameters.positional].
     */
    data class UnparsedSql(
        val sql: String,
        val dynamicParamOrder: List<Int>,
    )

    fun unparse(
        rel: RelNode,
        dialectCode: SqlDialectProto,
    ): String = unparse(rel, Dialects.byCode(dialectCode))

    fun unparse(
        rel: RelNode,
        dialect: SqlDialect,
    ): String = unparseWithParams(rel, dialect).sql

    fun unparseWithParams(
        rel: RelNode,
        dialectCode: SqlDialectProto,
    ): UnparsedSql = unparseWithParams(rel, Dialects.byCode(dialectCode))

    fun unparseWithParams(
        rel: RelNode,
        dialect: SqlDialect,
    ): UnparsedSql {
        val converter = RelToSqlConverter(dialect)
        val restored = BetweenRestoration.apply(rel)
        // SQL Server reads a VARCHAR cast without a length as VARCHAR(30) — drop the redundant ones Calcite
        // wraps around VARCHAR operands (COALESCE's CASE, implicit coercion); see VarcharCastElision.
        val prepared = if (dialect is MssqlSqlDialect) VarcharCastElision.apply(restored) else restored
        val sqlNode = converter.visitRoot(prepared).asStatement()
        // Postgres/DuckDB resolve unqualified names via search_path, so the v1 model's logical
        // namespace (the `dbo` default token) must NOT be emitted as a physical schema — the
        // physical schema is the connection's default (e.g. `public`). MSSQL keeps `<namespace>`.
        val stripper = VirtualSchemaPrefixStripper(dropNamespace = dialect is PostgresqlSqlDialect)
        val stripped = sqlNode.accept(stripper) ?: sqlNode
        val sqlString = stripped.toSqlString { config -> config.withDialect(dialect) }
        return UnparsedSql(sqlString.sql, sqlString.dynamicParameters?.toList() ?: emptyList())
    }

    /**
     * Issue #57, Phase A — drop the leading `db` Calcite virtual schema-code segment from
     * every compound [SqlIdentifier] in the tree.
     *
     * After `MapToPhysical` rewrites `Scan(ER, …)` → `TableScan(DB, …)`, the SchemaPlus tree
     * resolves the table via `root.db.<namespace>.<table>` (the `db` segment is the
     * `SchemaCode` token, added by `TranslatorFramework.rootSchema`). Calcite would then
     * emit the full 3-part path, e.g. `[db].[<namespace>].[<table>]` — MSSQL would
     * interpret the leftmost segment as a database name and fail with
     * `Invalid object name 'db.<namespace>.<table>'`.
     *
     * `db` is a Calcite virtual prefix, never a real engine identifier. We rewrite the
     * `SqlNode` tree (not the rendered SQL string) so string literals containing the text
     * `[db].` survive untouched — only identifier nodes are affected.
     *
     * [dropNamespace] extends the strip for search-path dialects (Postgres/DuckDB): the v1 model's
     * logical namespace (the `dbo` default token) is not a physical Postgres schema, so it is
     * dropped as well — `[db].[dbo].[store_sales]` → `store_sales`, which resolves against the
     * connection's `search_path` (its default schema, e.g. `public`). MSSQL keeps `[dbo].[store_sales]`.
     * Only identifiers led by the virtual `db` code are touched, so alias-qualified column refs
     * (`d.d_year`) are never affected.
     */
    private class VirtualSchemaPrefixStripper(
        private val dropNamespace: Boolean,
    ) : SqlShuttle() {
        override fun visit(id: SqlIdentifier): SqlNode =
            if (id.names.size > 1 && id.names[0] == "db") {
                // Drop just the `db` code (MSSQL), or `db` + namespace down to the bare table name
                // (search-path dialects). getComponent(from, to) is [from, to).
                val from = if (dropNamespace) id.names.size - 1 else 1
                id.getComponent(from, id.names.size)
            } else {
                id
            }
    }
}
