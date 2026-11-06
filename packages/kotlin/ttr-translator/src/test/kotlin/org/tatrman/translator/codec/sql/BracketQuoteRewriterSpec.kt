// SPDX-License-Identifier: Apache-2.0
package org.tatrman.translator.codec.sql

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class BracketQuoteRewriterSpec :
    StringSpec({
        val names =
            mapOf(
                "[name]" to "\"name\"",
                "[a b]" to "\"a b\"",
                "[dbo].[XXPRODEJE]" to "\"dbo\".\"XXPRODEJE\"",
                "[SUBJADR_POZN_místa]" to "\"SUBJADR_POZN_místa\"",
                "[potenciál tržeb]" to "\"potenciál tržeb\"",
                "[a]]b]" to "\"a]b\"",
                "[a\"b]" to "\"a\"\"b\"",
                "[]" to "\"\"",
            )
        for ((source, expected) in names) {
            "rewrites $source without changing the identifier" {
                BracketQuoteRewriter.rewrite(source) shouldBe expected
            }
        }

        val protected =
            listOf(
                "SELECT '[0-9]%'",
                "SELECT 'it''s [not an identifier]'",
                "-- [comment]\n",
                "/* [comment] */",
                "\"already [quoted]\"",
                "\"a\"\"[b]\"",
                "'unterminated [literal]",
                "/* unterminated [comment]",
                "\"unterminated [identifier]",
            )
        for ((index, source) in protected.withIndex()) {
            "preserves literal comment or quoted identifier $index and resumes afterwards" {
                BracketQuoteRewriter.rewrite(source) shouldBe source
                if (!source.contains("unterminated")) {
                    BracketQuoteRewriter.rewrite("$source [x]") shouldBe "$source \"x\""
                }
            }
        }

        for (source in listOf("[", "[unfinished", "[a]]", "[a [b")) {
            "leaves unterminated $source for the parser to reject" {
                BracketQuoteRewriter.rewrite(source) shouldBe source
            }
        }

        "is idempotent with escaped quotes literals comments and adjacent names" {
            val source = "SELECT [a\"b], [a]]b], '[0-9]%' FROM [dbo].[t] -- [comment]\n"
            val once = BracketQuoteRewriter.rewrite(source)
            BracketQuoteRewriter.rewrite(once) shouldBe once
        }

        for (source in listOf("[a][b]", "[a]\"b\"", "\"a\"[b]")) {
            "separates adjacent identifiers on both sides: $source" {
                val once = BracketQuoteRewriter.rewrite(source)
                once shouldBe "\"a\" \"b\""
                BracketQuoteRewriter.rewrite(once) shouldBe once
            }
        }

        "a bracket after a bare column is a T-SQL alias rather than array indexing" {
            BracketQuoteRewriter.rewrite("a[1]") shouldBe "a\"1\""
        }
    })
