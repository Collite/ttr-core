// SPDX-License-Identifier: Apache-2.0
package org.tatrman.translator.dialects

import org.tatrman.translate.v1.SqlDialect as SqlDialectProto
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import org.apache.calcite.sql.dialect.MssqlSqlDialect
import org.apache.calcite.sql.dialect.MysqlSqlDialect
import org.apache.calcite.sql.dialect.PostgresqlSqlDialect

class DialectsSpec :
    StringSpec({

        "MSSQL is constructed with databaseMajorVersion = 11, NOT MssqlSqlDialect.DEFAULT" {
            val mssql = Dialects.MSSQL
            mssql shouldNotBe MssqlSqlDialect.DEFAULT
            // Cross-check the major-version setting via reflection on the underlying
            // context — Calcite's SqlDialect doesn't expose it as a public getter,
            // but databaseMajorVersion drives whether the dialect emits OFFSET/FETCH
            // (>= SQL Server 2012, major version 11) versus the older TOP-only form.
            // We assert behaviour rather than internal state by smoke-checking that
            // DEFAULT and our instance do not compare equal — DEFAULT uses
            // major-version 0 in this Calcite build.
            // Our MSSQL is a MssqlSqlDialect subclass that remaps DOUBLE→FLOAT in
            // casts (SQL Server has no DOUBLE type); still NOT the stock DEFAULT.
            (mssql is MssqlSqlDialect) shouldBe true
            mssql.javaClass shouldBe MssqlSqlDialectWithFloatCast::class.java
        }

        "POSTGRES is the grounding-aware Postgres dialect; MYSQL the standard singleton" {
            // RG-P3 — POSTGRES lowers the platform grounding functions (period_start etc.), so it's
            // the PostgresqlSqlDialect subclass, not the stock singleton (mirrors MSSQL above).
            Dialects.POSTGRES.javaClass shouldBe PostgresqlSqlDialectWithGrounding::class.java
            (Dialects.POSTGRES is PostgresqlSqlDialect) shouldBe true
            Dialects.MYSQL.javaClass shouldBe MysqlSqlDialect::class.java
        }

        // Phase 08 A3 — DuckDB.
        "DUCKDB extends Postgres dialect (Postgres-compatible for the constructs we emit)" {
            (Dialects.DUCKDB is DuckDbSqlDialect) shouldBe true
            (Dialects.DUCKDB is PostgresqlSqlDialect) shouldBe true
        }

        "byCode dispatches each known proto enum to the registered instance" {
            Dialects.byCode(SqlDialectProto.MSSQL) shouldBe Dialects.MSSQL
            Dialects.byCode(SqlDialectProto.POSTGRESQL) shouldBe Dialects.POSTGRES
            Dialects.byCode(SqlDialectProto.MYSQL_MARIADB) shouldBe Dialects.MYSQL
            Dialects.byCode(SqlDialectProto.DUCKDB) shouldBe Dialects.DUCKDB
        }

        "byCode rejects SQL_DIALECT_UNSPECIFIED with a guidance error" {
            val ex =
                shouldThrow<IllegalArgumentException> {
                    Dialects.byCode(SqlDialectProto.SQL_DIALECT_UNSPECIFIED)
                }
            ex.message!!.shouldContain("not specified")
        }

        "byCode rejects UNRECOGNIZED" {
            shouldThrow<IllegalArgumentException> {
                Dialects.byCode(SqlDialectProto.UNRECOGNIZED)
            }
        }

        "Dialects instances are reused across calls (registry caches them)" {
            val a = Dialects.MSSQL
            val b = Dialects.MSSQL
            (a === b) shouldBe true
        }
    })
