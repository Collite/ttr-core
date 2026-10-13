// SPDX-License-Identifier: Apache-2.0
package org.tatrman.translator.codec.sql

import org.apache.calcite.rel.core.Values
import org.apache.calcite.rel.rel2sql.RelToSqlConverter
import org.apache.calcite.rel.rel2sql.SqlImplementor
import org.apache.calcite.rel.type.RelDataType
import org.apache.calcite.sql.SqlDialect
import org.apache.calcite.sql.SqlIdentifier
import org.apache.calcite.sql.SqlLiteral
import org.apache.calcite.sql.SqlNode
import org.apache.calcite.sql.SqlNodeList
import org.apache.calcite.sql.SqlSelect
import org.apache.calcite.sql.dialect.MysqlSqlDialect
import org.apache.calcite.sql.`fun`.SqlInternalOperators
import org.apache.calcite.sql.`fun`.SqlStdOperatorTable
import org.apache.calcite.sql.parser.SqlParserPos
import org.apache.calcite.sql.type.SqlTypeName

/**
 * ttr-core#148 — Calcite's [RelToSqlConverter], except that an empty `Values` keeps its column types.
 *
 * An empty `Values` is how Calcite writes a relation it has proved empty (`RelBuilder.filter` folds an
 * always-false filter such as `a = 'x' AND a = 'y'` into one). The stock converter renders it as
 *
 *     SELECT * FROM (VALUES (NULL, NULL)) AS t (a, b) WHERE 1 = 0
 *
 * and those bare NULLs carry no type, so the engine picks one: PostgreSQL reads them as `text` (a `SUM`
 * above fails with `function sum(text) does not exist`, a join on the column with `operator does not
 * exist: bigint = text`), DuckDB and SQL Server as an integer. Each NULL here is cast to its column's
 * type in the target dialect's spelling, the way the stock converter already treats a NULL in a SELECT
 * list (its `castNullType`):
 *
 *     SELECT * FROM (VALUES (CAST(NULL AS BIGINT), CAST(NULL AS VARCHAR))) AS t (a, b) WHERE 1 = 0
 *
 * The shape is otherwise the stock one, including the `SELECT CAST(NULL AS …) AS a, …` form for a dialect
 * without `VALUES` in FROM (MySQL). Non-empty `Values` are left to the stock converter.
 *
 * The stock converter skips the `AS t (a, b)` rename under a `TableModify`; the translator never unparses
 * one (DML is assembled around a SELECT by [StoreDmlUnparser]), so the rename always applies here.
 */
class TypedEmptyValuesRelToSqlConverter(
    dialect: SqlDialect,
) : RelToSqlConverter(dialect) {
    override fun visit(e: Values): SqlImplementor.Result {
        if (!Values.isEmpty(e)) return super.visit(e)
        val names = e.rowType.fieldNames
        val nulls = e.rowType.fieldList.map { typedNull(it.type) }
        val query =
            if (dialect.supportsAliasedValues()) {
                // SELECT * FROM (VALUES (CAST(NULL AS t1), …)) AS t (c1, …) WHERE 1 = 0
                val row = SqlInternalOperators.ANONYMOUS_ROW.createCall(POS, nulls)
                val values = SqlStdOperatorTable.VALUES.createCall(POS, row)
                select(SqlNodeList.SINGLETON_STAR, `as`(values, "t", *names.toTypedArray()), alwaysFalse())
            } else {
                val columns = SqlNodeList(nulls.zip(names) { value, name -> `as`(value, name) }, POS)
                val dual = dialect.singleRowTableName?.let { SqlIdentifier(it, POS) }
                if (dual != null) {
                    // SELECT CAST(NULL AS t1) AS c1, … FROM DUAL WHERE 1 = 0
                    select(columns, dual, alwaysFalse())
                } else {
                    // SELECT * FROM (SELECT CAST(NULL AS t1) AS c1, …) AS t WHERE 1 = 0
                    select(SqlNodeList.SINGLETON_STAR, `as`(select(columns, null, null), "t"), alwaysFalse())
                }
            }
        return result(query, listOf(SqlImplementor.Clause.SELECT), e, null)
    }

    /** `CAST(NULL AS <type>)` in this dialect's spelling, or a bare NULL where the dialect has no cast to [type]. */
    private fun typedNull(type: RelDataType): SqlNode {
        val nullLiteral = SqlLiteral.createNull(POS)
        if (type.sqlTypeName !in CASTABLE) return nullLiteral
        // MySQL has no CAST target for BOOLEAN (a BOOLEAN there is a TINYINT(1), and TINYINT is no CAST target).
        if (dialect is MysqlSqlDialect && type.sqlTypeName == SqlTypeName.BOOLEAN) return nullLiteral
        val spec = dialect.getCastSpec(type) ?: return nullLiteral
        return SqlStdOperatorTable.CAST.createCall(POS, nullLiteral, spec)
    }

    private fun select(
        selectList: SqlNodeList,
        from: SqlNode?,
        where: SqlNode?,
    ): SqlSelect = SqlSelect(POS, null, selectList, from, where, null, null, null, null, null, null, null, null)

    private companion object {
        val POS: SqlParserPos = SqlParserPos.ZERO

        /** `1 = 0` rather than `FALSE`, which SQL Server does not accept (the stock converter's choice too). */
        fun alwaysFalse(): SqlNode =
            SqlStdOperatorTable.EQUALS.createCall(
                POS,
                SqlLiteral.createExactNumeric("1", POS),
                SqlLiteral.createExactNumeric("0", POS),
            )

        /** The column types a `plan.v1` relation carries; anything else (ANY, NULL, …) has no cast and stays bare. */
        val CASTABLE: Set<SqlTypeName> =
            setOf(
                SqlTypeName.CHAR,
                SqlTypeName.VARCHAR,
                SqlTypeName.TINYINT,
                SqlTypeName.SMALLINT,
                SqlTypeName.INTEGER,
                SqlTypeName.BIGINT,
                SqlTypeName.DECIMAL,
                SqlTypeName.FLOAT,
                SqlTypeName.REAL,
                SqlTypeName.DOUBLE,
                SqlTypeName.BOOLEAN,
                SqlTypeName.DATE,
                SqlTypeName.TIME,
                SqlTypeName.TIMESTAMP,
            )
    }
}
