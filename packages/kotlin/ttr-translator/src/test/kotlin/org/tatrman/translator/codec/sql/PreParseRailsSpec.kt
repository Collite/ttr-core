// SPDX-License-Identifier: Apache-2.0
package org.tatrman.translator.codec.sql

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class PreParseRailsSpec :
    StringSpec({
        "strips the terminator before brackets and TOP in that order" {
            val result = PreParseRails.prepare("SELECT TOP 5 [x] FROM [t];\n ")
            result.cleanedSql shouldBe "SELECT   \"x\" FROM \"t\" FETCH FIRST 5 ROWS ONLY"
            result.hintsByTable shouldBe emptyMap()
        }

        "extracts a hint after normalizing a dotted bracket name" {
            val result = PreParseRails.prepare("SELECT [x] FROM [dbo].[t] WITH (NOLOCK);")
            result.cleanedSql shouldBe "SELECT \"x\" FROM \"dbo\".\"t\""
            result.hintsByTable shouldBe mapOf("t" to listOf(TableHintSpec("NOLOCK")))
        }

        "bracket TOP remains a column identifier" {
            PreParseRails.prepare("SELECT [TOP] FROM [t]").cleanedSql shouldBe "SELECT \"TOP\" FROM \"t\""
        }

        "quoted SQL keywords and parentheses cannot trigger later rails" {
            val result = PreParseRails.prepare("SELECT TOP 5 [SELECT TOP 9 )], [WITH (NOLOCK)] FROM [t]")
            result.cleanedSql shouldBe
                "SELECT   \"SELECT TOP 9 )\", \"WITH (NOLOCK)\" FROM \"t\" FETCH FIRST 5 ROWS ONLY"
            result.hintsByTable shouldBe emptyMap()
        }

        "leaves bracket patterns and semicolons inside literals intact" {
            PreParseRails.prepare("SELECT [x] FROM [t] WHERE [x] LIKE '[0-9]%;';").cleanedSql shouldBe
                "SELECT \"x\" FROM \"t\" WHERE \"x\" LIKE '[0-9]%;'"
        }

        val sources =
            listOf(
                "SELECT TOP 5 [x] FROM [t];",
                "SELECT [x] FROM [dbo].[t] WITH (NOLOCK);",
                "SELECT TOP 5 [x] FROM [dbo].[t] WITH (NOLOCK);",
                "SELECT TOP 5 [x] FROM [dbo].[t] WITH (NOLOCK) WHERE [x] > 0;",
                "SELECT [TOP], [a]]b], [a\"b] FROM [t]",
                "SELECT [a][b] FROM [t]",
                "SELECT 1;;",
                "SELECT 1 ; ;",
                "SELECT a FROM t; WITH (NOLOCK)",
            )
        for ((index, source) in sources.withIndex()) {
            "preparation is idempotent and hints are consumed only once $index" {
                val first = PreParseRails.prepare(source)
                val second = PreParseRails.prepare(first.cleanedSql)
                second.cleanedSql shouldBe first.cleanedSql
                second.hintsByTable shouldBe emptyMap()
            }
        }

        val hintKeys =
            listOf(
                "[order details]" to "order details",
                "[order details] d WITH (NOLOCK) JOIN [order] o ON d.a = o.a" to "order details",
                "[XX-PRODEJE]" to "xx-prodeje",
                "[q\"t]" to "q\"t",
                "[x]]]" to "x]",
                "[a.b]" to "b",
                "[a b] AS [c d]" to "a b",
                "t AS [a from b]" to "t",
                "[t] /* c */" to "t",
                "t -- c\n" to "t",
                "[dbo] . [t]" to "t",
                "[from] AS [AS]" to "from",
            )
        for ((index, pair) in hintKeys.withIndex()) {
            "hint keys retain decoded table names across aliases and comments $index" {
                val (reference, key) = pair
                val sql =
                    if (reference.contains(
                            "WITH",
                        )
                    ) {
                        "SELECT a FROM $reference"
                    } else {
                        "SELECT a FROM $reference WITH (NOLOCK)"
                    }
                PreParseRails.prepare(sql).hintsByTable shouldBe mapOf(key to listOf(TableHintSpec("NOLOCK")))
            }
        }

        "quoted hint arguments preserve punctuation and literals after splitting" {
            PreParseRails.prepare("SELECT a FROM t WITH (INDEX([I,X], [I)X], 'a,b', 'q\"t'))").hintsByTable shouldBe
                mapOf("t" to listOf(TableHintSpec("INDEX", listOf("[I,X]", "[I)X]", "'a,b'", "'q\"t'"))))
        }

        "a hint without a table token is removed without attaching it elsewhere" {
            val result = PreParseRails.prepare("SELECT 'literal' WITH (NOLOCK)")
            result.cleanedSql shouldBe "SELECT 'literal'"
            result.hintsByTable shouldBe emptyMap()
        }
    })
