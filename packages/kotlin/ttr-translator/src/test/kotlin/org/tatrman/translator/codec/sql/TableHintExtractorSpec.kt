// SPDX-License-Identifier: Apache-2.0
package org.tatrman.translator.codec.sql

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContainIgnoringCase

/**
 * NX-A.S4 — unit coverage for [TableHintExtractor]: the literal-aware, CTE-aware left-to-right scan
 * that pulls `WITH (NOLOCK)` table hints out of the SQL before Calcite parses it. Pure and fast —
 * no Calcite. (Ported from ai-platform's query-translator, decision D9.)
 */
class TableHintExtractorSpec :
    StringSpec({

        fun norm(s: String): String = s.replace(Regex("\\s+"), " ").trim()

        "FROM mu WITH (NOLOCK) — hint stripped, mu -> [NOLOCK]" {
            val r = TableHintExtractor.extract("FROM mu WITH (NOLOCK)")
            norm(r.cleanedSql) shouldBe "FROM mu"
            r.byTable shouldBe mapOf("mu" to listOf(TableHintSpec("NOLOCK")))
        }

        "FROM mu m WITH (NOLOCK) — alias after table, matched by table name" {
            val r = TableHintExtractor.extract("FROM mu m WITH (NOLOCK)")
            norm(r.cleanedSql) shouldBe "FROM mu m"
            r.byTable shouldBe mapOf("mu" to listOf(TableHintSpec("NOLOCK")))
        }

        "FROM dbo.mu AS m WITH (NOLOCK, ROWLOCK) — dotted table + AS alias + two hints" {
            val r = TableHintExtractor.extract("FROM dbo.mu AS m WITH (NOLOCK, ROWLOCK)")
            norm(r.cleanedSql) shouldBe "FROM dbo.mu AS m"
            r.byTable shouldBe mapOf("mu" to listOf(TableHintSpec("NOLOCK"), TableHintSpec("ROWLOCK")))
        }

        "string literal containing WITH (NOLOCK) is NOT matched" {
            val sql = "SELECT id FROM mu WHERE note = 'use WITH (NOLOCK)'"
            val r = TableHintExtractor.extract(sql)
            r.cleanedSql shouldBe sql
            r.byTable shouldBe emptyMap()
        }

        "CTE `WITH cte AS (...)` is NOT a table hint (identifier before the paren)" {
            val sql = "WITH cte AS (SELECT id FROM mu) SELECT id FROM cte"
            val r = TableHintExtractor.extract(sql)
            r.cleanedSql shouldBe sql
            r.byTable shouldBe emptyMap()
        }

        "doubled '' inside a literal stays inside — trailing WITH (NOLOCK) is still matched" {
            val r = TableHintExtractor.extract("SELECT 'a''b' AS x FROM mu WITH (NOLOCK)")
            r.byTable shouldBe mapOf("mu" to listOf(TableHintSpec("NOLOCK")))
            norm(r.cleanedSql) shouldBe "SELECT 'a''b' AS x FROM mu"
            r.cleanedSql.shouldNotContainIgnoringCase("NOLOCK")
        }

        "WITH( with no space still matches (whole-word WITH, paren is the boundary)" {
            val r = TableHintExtractor.extract("FROM mu WITH(NOLOCK)")
            r.byTable shouldBe mapOf("mu" to listOf(TableHintSpec("NOLOCK")))
        }

        "option-bearing hint INDEX(0) records name + options" {
            val r = TableHintExtractor.extract("FROM mu WITH (INDEX(0), NOLOCK)")
            r.byTable shouldBe
                mapOf("mu" to listOf(TableHintSpec("INDEX", listOf("0")), TableHintSpec("NOLOCK")))
        }

        "plain FROM mu — no WITH, nothing extracted" {
            val r = TableHintExtractor.extract("SELECT id FROM mu WHERE id > 5")
            r.cleanedSql shouldBe "SELECT id FROM mu WHERE id > 5"
            r.byTable shouldBe emptyMap()
        }

        "quoted table name — hint keyed on the unquoted name" {
            val r = TableHintExtractor.extract("""FROM "mu" WITH (NOLOCK)""")
            r.byTable shouldBe mapOf("mu" to listOf(TableHintSpec("NOLOCK")))
        }

        "quoted dotted table + quoted alias — keyed on the last unquoted segment" {
            val r = TableHintExtractor.extract("""FROM "dbo"."mu" "m" WITH (NOLOCK)""")
            r.byTable shouldBe mapOf("mu" to listOf(TableHintSpec("NOLOCK")))
        }

        "bracket-quoted table name — hint keyed on the unquoted name" {
            val r = TableHintExtractor.extract("FROM [dbo].[mu] AS m WITH (NOLOCK)")
            r.byTable shouldBe mapOf("mu" to listOf(TableHintSpec("NOLOCK")))
        }

        "WITH (NOLOCK) inside a line comment is NOT matched" {
            val sql = "SELECT id FROM mu -- legacy hint: WITH (NOLOCK)\nWHERE id > 5"
            val r = TableHintExtractor.extract(sql)
            r.cleanedSql shouldBe sql
            r.byTable shouldBe emptyMap()
        }

        "WITH (NOLOCK) inside a block comment is NOT matched" {
            val sql = "SELECT id FROM mu /* dropped: WITH (NOLOCK) */ WHERE id > 5"
            val r = TableHintExtractor.extract(sql)
            r.cleanedSql shouldBe sql
            r.byTable shouldBe emptyMap()
        }

        "a real hint after a comment is still matched" {
            val r = TableHintExtractor.extract("SELECT id /* note */ FROM mu WITH (NOLOCK)")
            r.byTable shouldBe mapOf("mu" to listOf(TableHintSpec("NOLOCK")))
            norm(r.cleanedSql) shouldBe "SELECT id /* note */ FROM mu"
        }

        "two different tables each carry their own hint" {
            val r =
                TableHintExtractor.extract(
                    "FROM mu a WITH (NOLOCK) JOIN payments p WITH (ROWLOCK) ON a.id = p.mid",
                )
            r.byTable shouldBe
                mapOf(
                    "mu" to listOf(TableHintSpec("NOLOCK")),
                    "payments" to listOf(TableHintSpec("ROWLOCK")),
                )
            r.cleanedSql.shouldNotContainIgnoringCase("WITH (")
        }
        "backtick table references remain accepted by direct extraction" {
            TableHintExtractor.extract("FROM `dbo`.`mu` AS `m` WITH (NOLOCK)").byTable shouldBe
                mapOf("mu" to listOf(TableHintSpec("NOLOCK")))
        }
    })
