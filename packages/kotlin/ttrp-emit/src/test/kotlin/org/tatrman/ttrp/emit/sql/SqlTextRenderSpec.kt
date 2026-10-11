// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.emit.sql

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.tatrman.ttrp.emit.TtrpEmitException

/** AG-P0 — the pure text steps of a `sql-text` statement: named placeholders and inline sources. */
class SqlTextRenderSpec :
    StringSpec({
        "positional `?` become `:name` in appearance order, repeats included" {
            SqlTextRender.namePlaceholders("WHERE [a] = ? AND [b] < ? OR [a] = ?", listOf("x", "dnes", "x")) shouldBe
                "WHERE [a] = :x AND [b] < :dnes OR [a] = :x"
        }

        "a `?` inside a string literal or a quoted identifier is left alone" {
            SqlTextRender.namePlaceholders("SELECT 'is it?', [col?], \"q?\" FROM t WHERE c = ?", listOf("p")) shouldBe
                "SELECT 'is it?', [col?], \"q?\" FROM t WHERE c = :p"
            SqlTextRender.namePlaceholders("SELECT 'it''s?' WHERE c = ?", listOf("p")) shouldBe
                "SELECT 'it''s?' WHERE c = :p"
        }

        "no reported params ⇒ the text is untouched; more `?` than names is an emit error" {
            SqlTextRender.namePlaceholders("SELECT 1", emptyList()) shouldBe "SELECT 1"
            shouldThrow<TtrpEmitException> { SqlTextRender.namePlaceholders("? ?", listOf("a")) }
        }

        "an inline source placeholder becomes its SQL as a derived table named after the source" {
            val out =
                SqlTextRender.inlineSources(
                    "SELECT [x] FROM [_ttrp_inline].[catalog_order__filter] WHERE [x] = 1",
                    "_ttrp_inline",
                    mapOf("catalog_order__filter" to "SELECT * FROM T WHERE KIND='EXPRESS'"),
                )
            out shouldBe "SELECT [x] FROM (\n    SELECT * FROM T WHERE KIND='EXPRESS'\n) AS [catalog_order__filter] WHERE [x] = 1"
        }

        "door dialect: tables lose the sentinel qualifier; brackets become double quotes outside literals" {
            SqlTextRender.unqualify("FROM [_ttrp_table].[catalog_sales] AS [t] JOIN [x].[y]", "_ttrp_table") shouldBe
                "FROM [catalog_sales] AS [t] JOIN [x].[y]"
            SqlTextRender.doubleQuoted("SELECT [a] AS [b c], 'it''s [x]', [q\"r], [s]]t] FROM [T]") shouldBe
                "SELECT \"a\" AS \"b c\", 'it''s [x]', \"q\"\"r\", \"s]t\" FROM \"T\""
        }

        "an aliased inline source keeps the alias Calcite gave it" {
            SqlTextRender.inlineSources(
                "FROM [_ttrp_inline].[s] AS [t3] JOIN [dbo].[s] AS [t4]",
                "_ttrp_inline",
                mapOf("s" to "SELECT 1 AS a"),
            ) shouldBe "FROM (\n    SELECT 1 AS a\n) AS [t3] JOIN [dbo].[s] AS [t4]"
        }
    })
