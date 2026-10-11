// SPDX-License-Identifier: Apache-2.0
package org.tatrman.translator.codec.sql

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.tatrman.plan.v1.QualifiedName
import org.tatrman.plan.v1.SchemaCode
import org.tatrman.translate.v1.Language
import org.tatrman.translate.v1.SqlDialect
import org.tatrman.translator.framework.FixtureModel
import org.tatrman.translator.framework.InMemoryModelHandle
import org.tatrman.translator.framework.ModelColumn
import org.tatrman.translator.framework.ModelTable
import org.tatrman.translator.framework.PhysicalType
import org.tatrman.translator.framework.SurfaceType
import org.tatrman.translator.orchestrator.ParseResult
import org.tatrman.translator.orchestrator.Translator
import org.tatrman.translator.orchestrator.UnparseResult
import org.tatrman.translator.tf.Outcome
import org.tatrman.translator.tf.roundTrip

/**
 * A VARCHAR cast without a length never reaches SQL Server bare. T-SQL reads `CAST(x AS VARCHAR)` as
 * `VARCHAR(30)` and cuts every longer value without an error, and the MSSQL unparse used to write exactly
 * that: around the column branch of the CASE that `COALESCE` expands to, for implicit casts, and for an
 * authored `CAST(x AS varchar)`.
 *
 * * An unbounded VARCHAR is spelled `VARCHAR(MAX)` (`MssqlSqlDialectWithFloatCast.getCastSpec`).
 * * The cast Calcite puts around a VARCHAR operand of another call (no length, or the operand's own) is
 *   dropped (`VarcharCastElision`), so `COALESCE(col, 'x')` comes back as a plain CASE.
 * * Every explicit length stays as written; `nvarchar` without a length is T-SQL's own `CAST` default,
 *   30, set at parse time (`TsqlDataTypes`).
 *
 * Postgres reads a bare VARCHAR as unbounded, so it keeps its casts; only `varchar(max)` changes there,
 * from the capped `VARCHAR(65536)` to `VARCHAR`.
 */
class MssqlUnboundedVarcharSpec :
    StringSpec({
        val tf = Translator(FixtureModel.tfHandle())

        // `email` has no declared length (an unbounded VARCHAR, like a surface `text` column);
        // `alt_email` is an nvarchar(320) and `code` a varchar(20) — physical types with a length.
        val contact =
            Translator(
                InMemoryModelHandle(
                    listOf(
                        ModelTable(
                            QualifiedName
                                .newBuilder()
                                .setSchemaCode(SchemaCode.DB)
                                .setNamespace("dbo")
                                .setName("contact")
                                .build(),
                            listOf(
                                ModelColumn("id", SurfaceType.INT, nullable = false),
                                ModelColumn("email", SurfaceType.TEXT),
                                ModelColumn(
                                    "alt_email",
                                    SurfaceType.TEXT,
                                    physicalType = PhysicalType(PhysicalType.Kind.NVARCHAR, 320),
                                ),
                                ModelColumn(
                                    "code",
                                    SurfaceType.TEXT,
                                    physicalType = PhysicalType(PhysicalType.Kind.VARCHAR, 20),
                                ),
                            ),
                        ),
                    ),
                ),
            )

        fun mssql(
            translator: Translator,
            sql: String,
        ): Outcome = roundTrip(translator, sql, emptyList())

        fun unparse(
            translator: Translator,
            sql: String,
            dialect: SqlDialect,
            optimize: Boolean,
        ): String {
            val parsed = translator.parseToRelNode(sql, Language.SQL)
            parsed.shouldBeInstanceOf<ParseResult.Success>()
            val u = translator.unparseFromRelNode(parsed.plan, Language.SQL, dialect, optimize)
            u.shouldBeInstanceOf<UnparseResult.Success>()
            return u.output.replace(Regex("""\s+"""), " ").trim()
        }

        // ── the reported statement ───────────────────────────────────────────────────────────────────

        val reported =
            """SELECT CAST(COALESCE("t4"."email", 'dept:sales') AS VARCHAR(MAX)) AS "recipient" FROM "contact" "t4""""

        for (optimize in listOf(true, false)) {
            "the reported COALESCE … AS VARCHAR(MAX) keeps the column whole (optimize = $optimize)" {
                unparse(contact, reported, SqlDialect.MSSQL, optimize) shouldBe
                    "SELECT CAST(CASE WHEN [email] IS NOT NULL THEN [email] ELSE 'dept:sales' END AS VARCHAR(MAX)) " +
                    "AS [recipient] FROM [dbo].[contact]"
            }
        }

        // ── explicit casts ───────────────────────────────────────────────────────────────────────────

        "an authored CAST(x AS VARCHAR) of a number is spelled VARCHAR(MAX)" {
            mssql(tf, "SELECT CAST(a.ID AS VARCHAR) AS X FROM A a") shouldBe
                Outcome.Unparsed("SELECT CAST([ID] AS VARCHAR(MAX)) AS [X] FROM [dbo].[A]")
        }

        "an authored CAST(x AS VARCHAR) of a VARCHAR column is no cast at all (Calcite drops it at parse)" {
            mssql(tf, "SELECT CAST(a.NAME AS VARCHAR) AS X FROM A a") shouldBe
                Outcome.Unparsed("SELECT [NAME] AS [X] FROM [dbo].[A]")
        }

        "VARCHAR(50) stays VARCHAR(50) — on a number and on a text column" {
            mssql(tf, "SELECT CAST(a.ID AS VARCHAR(50)) AS X, CAST(a.NAME AS VARCHAR(50)) AS Y FROM A a") shouldBe
                Outcome.Unparsed(
                    "SELECT CAST([ID] AS VARCHAR(50)) AS [X], CAST([NAME] AS VARCHAR(50)) AS [Y] FROM [dbo].[A]",
                )
        }

        "VARCHAR(MAX) stays VARCHAR(MAX), also nested where T-SQL needs it (CONCAT of non-MAX strings stops at 8000)" {
            mssql(contact, "SELECT CONCAT(CAST(c.alt_email AS VARCHAR(MAX)), c.code) AS X FROM contact c") shouldBe
                Outcome.Unparsed("SELECT CONCAT(CAST([alt_email] AS VARCHAR(MAX)), [code]) AS [X] FROM [dbo].[contact]")
        }

        "a top-level unbounded cast is kept, as VARCHAR(MAX); the length it wraps stays" {
            mssql(tf, "SELECT COALESCE(CAST(a.ID AS VARCHAR(10)), a.NAME) AS X FROM A a") shouldBe
                Outcome.Unparsed("SELECT CAST(CAST([ID] AS VARCHAR(10)) AS VARCHAR(MAX)) AS [X] FROM [dbo].[A]")
        }

        // ── NVARCHAR (Calcite types it as VARCHAR; the spelling does not survive validation, ⚑TF-6) ──────

        "nvarchar(max) → VARCHAR(MAX), nvarchar(50) → VARCHAR(50), bare nvarchar → T-SQL's CAST default VARCHAR(30)" {
            mssql(
                tf,
                "SELECT CAST(a.ID AS nvarchar(max)) AS X, CAST(a.ID AS nvarchar(50)) AS Y, CAST(a.ID AS nvarchar) AS Z FROM A a",
            ) shouldBe
                Outcome.Unparsed(
                    "SELECT CAST([ID] AS VARCHAR(MAX)) AS [X], CAST([ID] AS VARCHAR(50)) AS [Y], " +
                        "CAST([ID] AS VARCHAR(30)) AS [Z] FROM [dbo].[A]",
                )
        }

        "COALESCE over an nvarchar(320) column does not down-convert it with CAST(… AS VARCHAR(320))" {
            mssql(contact, "SELECT COALESCE(c.alt_email, 'dept:sales') AS X FROM contact c") shouldBe
                Outcome.Unparsed(
                    "SELECT CASE WHEN [alt_email] IS NOT NULL THEN [alt_email] ELSE 'dept:sales' END AS [X] FROM [dbo].[contact]",
                )
        }

        // ── COALESCE / NULLIF expansions ─────────────────────────────────────────────────────────────

        "COALESCE(col, literal) is a CASE with no cast" {
            mssql(tf, "SELECT COALESCE(a.NAME, 'n/a') AS X FROM A a") shouldBe
                Outcome.Unparsed("SELECT CASE WHEN [NAME] IS NOT NULL THEN [NAME] ELSE 'n/a' END AS [X] FROM [dbo].[A]")
        }

        "a three-way COALESCE over two tables casts no branch" {
            mssql(tf, "SELECT COALESCE(a.NAME, b.NAME, 'z') AS X FROM A a JOIN B b ON a.B_ID = b.ID") shouldBe
                Outcome.Unparsed(
                    "SELECT CASE WHEN [A].[NAME] IS NOT NULL THEN [A].[NAME] " +
                        "WHEN [B].[NAME] IS NOT NULL THEN [B].[NAME] ELSE 'z' END AS [X] " +
                        "FROM [dbo].[A] INNER JOIN [dbo].[B] ON [A].[B_ID] = [B].[ID]",
                )
        }

        "COALESCE in a predicate loses its cast too" {
            mssql(tf, "SELECT a.ID FROM A a WHERE COALESCE(a.NAME, '') LIKE 'x%'") shouldBe
                Outcome.Unparsed("SELECT [ID] FROM [dbo].[A] WHERE [NAME] LIKE 'x%'")
        }

        "NULLIF(text, '') expands to a CASE with no cast" {
            mssql(contact, "SELECT NULLIF(c.email, '') AS X FROM contact c") shouldBe
                Outcome.Unparsed("SELECT CASE WHEN [email] = '' THEN NULL ELSE [email] END AS [X] FROM [dbo].[contact]")
        }

        "NULLIF over a number cast to VARCHAR spells the cast VARCHAR(MAX)" {
            mssql(tf, "SELECT NULLIF(CAST(a.ID AS VARCHAR), '0') AS X FROM A a") shouldBe
                Outcome.Unparsed(
                    "SELECT CASE WHEN CAST([ID] AS VARCHAR(MAX)) = '0' THEN NULL ELSE CAST([ID] AS VARCHAR(MAX)) END " +
                        "AS [X] FROM [dbo].[A]",
                )
        }

        // ── CASE with string branches ────────────────────────────────────────────────────────────────

        "a CASE over a text column, a literal and a number cast" {
            mssql(
                tf,
                "SELECT CASE WHEN a.ID > 0 THEN a.NAME WHEN a.ID < 0 THEN 'negative' ELSE CAST(a.ID AS VARCHAR) END AS X FROM A a",
            ) shouldBe
                Outcome.Unparsed(
                    "SELECT CASE WHEN [ID] > 0 THEN [NAME] WHEN [ID] < 0 THEN 'negative' " +
                        "ELSE CAST([ID] AS VARCHAR(MAX)) END AS [X] FROM [dbo].[A]",
                )
        }

        "a CASE over a bounded and an unbounded column casts neither" {
            mssql(contact, "SELECT CASE WHEN c.id > 0 THEN c.code ELSE c.email END AS X FROM contact c") shouldBe
                Outcome.Unparsed("SELECT CASE WHEN [id] > 0 THEN [code] ELSE [email] END AS [X] FROM [dbo].[contact]")
        }

        "a CASE over varchar(20) and nvarchar(320) keeps the widening to the explicit length 320" {
            mssql(contact, "SELECT CASE WHEN c.id > 0 THEN c.code ELSE c.alt_email END AS X FROM contact c") shouldBe
                Outcome.Unparsed(
                    "SELECT CASE WHEN [id] > 0 THEN CAST([code] AS VARCHAR(320)) ELSE [alt_email] END AS [X] FROM [dbo].[contact]",
                )
        }

        // ── implicit casts ───────────────────────────────────────────────────────────────────────────

        "CONCAT's implicit number cast is spelled VARCHAR(MAX)" {
            mssql(tf, "SELECT CONCAT('x', a.ID) AS X FROM A a") shouldBe
                Outcome.Unparsed("SELECT CONCAT('x', CAST([ID] AS VARCHAR(MAX))) AS [X] FROM [dbo].[A]")
        }

        // ── the invariant, over every shape above ────────────────────────────────────────────────────

        "no MSSQL render carries a bare VARCHAR cast" {
            val statements =
                listOf(
                    tf to "SELECT CAST(a.ID AS VARCHAR) AS X FROM A a",
                    tf to "SELECT COALESCE(a.NAME, 'n/a') AS X FROM A a",
                    tf to "SELECT COALESCE(CAST(a.ID AS VARCHAR(10)), a.NAME) AS X FROM A a",
                    tf to "SELECT NULLIF(CAST(a.ID AS VARCHAR), '0') AS X FROM A a",
                    tf to "SELECT CONCAT('x', a.ID) AS X FROM A a",
                    tf to "SELECT 'x' + a.ID AS X FROM A a",
                    tf to "SELECT a.ID FROM A a WHERE COALESCE(a.NAME, '') LIKE 'x%'",
                    contact to reported,
                    contact to "SELECT COALESCE(c.email, c.code, 'none') AS X FROM contact c",
                )
            for ((translator, sql) in statements) {
                for (optimize in listOf(true, false)) {
                    unparse(translator, sql, SqlDialect.MSSQL, optimize) shouldNotContain "AS VARCHAR)"
                }
            }
        }

        // ── Postgres ─────────────────────────────────────────────────────────────────────────────────

        "Postgres: varchar(max) is an unbounded VARCHAR, not the capped VARCHAR(65536); lengths stay" {
            val sql = "SELECT CAST(a.ID AS VARCHAR(MAX)) AS X, CAST(a.ID AS VARCHAR(50)) AS Y FROM A a"
            unparse(tf, sql, SqlDialect.POSTGRESQL, true) shouldBe
                "SELECT CAST(\"ID\" AS VARCHAR) AS \"X\", CAST(\"ID\" AS VARCHAR(50)) AS \"Y\" FROM \"A\""
        }

        "Postgres: a bare VARCHAR cast is unbounded there and is left as it was" {
            unparse(tf, "SELECT COALESCE(a.NAME, 'n/a') AS X FROM A a", SqlDialect.POSTGRESQL, true) shouldBe
                "SELECT CASE WHEN \"NAME\" IS NOT NULL THEN CAST(\"NAME\" AS VARCHAR) ELSE 'n/a' END AS \"X\" FROM \"A\""
        }
    })
