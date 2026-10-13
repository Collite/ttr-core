// SPDX-License-Identifier: Apache-2.0
package org.tatrman.translator.codec.sql

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.apache.calcite.rel.RelNode
import org.apache.calcite.rel.rel2sql.RelToSqlConverter
import org.apache.calcite.sql.type.SqlTypeName
import org.tatrman.translate.v1.SqlDialect
import org.tatrman.translator.dialects.Dialects
import org.tatrman.translator.framework.FixtureModel
import org.tatrman.translator.framework.TranslatorFramework

/**
 * ttr-core#148 — [TypedEmptyValuesRelToSqlConverter] casts each NULL of an empty `Values` to its column's
 * type, in the dialect's spelling, and leaves a column with no cast (and every non-empty `Values`) as the
 * stock converter has them. The end-to-end shapes are in `EmptyValuesUnparseSpec`; this pins the column
 * types that fixture does not reach.
 */
class TypedEmptyValuesRelToSqlConverterSpec :
    StringSpec({
        fun emptyValues(vararg columns: Pair<String, SqlTypeName>): RelNode {
            val builder = TranslatorFramework(FixtureModel.handle()).newRelBuilder()
            val rowType =
                builder.typeFactory
                    .builder()
                    .apply { columns.forEach { (name, type) -> add(name, type).nullable(true) } }
                    .build()
            return builder.values(rowType).build()
        }

        val booleanAndDates =
            emptyValues(
                "is_open" to SqlTypeName.BOOLEAN,
                "opened_at" to SqlTypeName.TIMESTAMP,
                "opened_on" to SqlTypeName.DATE,
                "quantity" to SqlTypeName.INTEGER,
            )

        "SQL Server gets BIT, DATETIME2 and DATE" {
            RelToSqlUnparser.unparse(booleanAndDates, SqlDialect.MSSQL) shouldBe
                "SELECT *\n" +
                "FROM (VALUES (CAST(NULL AS BIT), CAST(NULL AS DATETIME2(0)), " +
                "CAST(NULL AS DATE), CAST(NULL AS INTEGER))) " +
                "AS [t] ([is_open], [opened_at], [opened_on], [quantity])\n" +
                "WHERE 1 = 0"
        }

        "PostgreSQL gets BOOLEAN, TIMESTAMP and DATE" {
            RelToSqlUnparser.unparse(booleanAndDates, SqlDialect.POSTGRESQL) shouldBe
                "SELECT *\n" +
                "FROM (VALUES (CAST(NULL AS BOOLEAN), CAST(NULL AS TIMESTAMP(0)), " +
                "CAST(NULL AS DATE), CAST(NULL AS INTEGER))) " +
                "AS \"t\" (\"is_open\", \"opened_at\", \"opened_on\", \"quantity\")\n" +
                "WHERE 1 = 0"
        }

        "MySQL has no CAST to BOOLEAN, so that NULL stays bare; the rest are typed" {
            RelToSqlUnparser.unparse(booleanAndDates, SqlDialect.MYSQL_MARIADB) shouldBe
                "SELECT *\n" +
                "FROM (SELECT NULL AS `is_open`, CAST(NULL AS DATETIME) AS `opened_at`, " +
                "CAST(NULL AS DATE) AS `opened_on`, " +
                "CAST(NULL AS SIGNED) AS `quantity`) AS `t`\n" +
                "WHERE 1 = 0"
        }

        "a column type with no cast (ANY) stays a bare NULL" {
            RelToSqlUnparser.unparse(emptyValues("payload" to SqlTypeName.ANY), SqlDialect.POSTGRESQL) shouldBe
                "SELECT *\n" +
                "FROM (VALUES (NULL)) AS \"t\" (\"payload\")\n" +
                "WHERE 1 = 0"
        }

        "a non-empty VALUES renders exactly as the stock converter renders it" {
            val builder = TranslatorFramework(FixtureModel.handle()).newRelBuilder()
            val rel = builder.values(arrayOf("id", "name"), 1, "Memphis DC", null, "Allentown DC").build()
            for (code in listOf(SqlDialect.MSSQL, SqlDialect.POSTGRESQL, SqlDialect.MYSQL_MARIADB, SqlDialect.DUCKDB)) {
                val dialect = Dialects.byCode(code)
                val stock =
                    RelToSqlConverter(dialect)
                        .visitRoot(rel)
                        .asStatement()
                        .toSqlString(dialect)
                        .sql
                val typed =
                    TypedEmptyValuesRelToSqlConverter(
                        dialect,
                    ).visitRoot(rel).asStatement().toSqlString(dialect).sql
                typed shouldBe stock
            }
        }
    })
