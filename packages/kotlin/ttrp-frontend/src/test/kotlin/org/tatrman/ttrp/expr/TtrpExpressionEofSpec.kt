// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.expr

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import org.tatrman.ttrp.diagnostics.TtrpDiagnosticId
import org.tatrman.ttrp.parser.TtrpParser

/**
 * ttr-core#202 (CN-P2 S2.6 T2): `parseExpression` reads the WHOLE input as one expression. A tail after the first
 * complete expression is a `TTRP-PRS-001` at the tail — it used to be dropped with no diagnostic, so `a b` "parsed"
 * as `a` (the S0.3 spike's false OKs).
 */
class TtrpExpressionEofSpec :
    StringSpec({
        "a trailing tail is a syntax error at the tail, not ignored" {
            listOf(
                "a b" to 2,
                "x = 1 )" to 6,
                "f(x) y" to 5,
                "1 2" to 2,
                "a and b c" to 8,
                "status = 'ACTIVE' where" to 18,
            ).forEach { (source, column) ->
                val errors = TtrpParser.parseExpression(source).diagnostics
                errors.shouldNotBeEmpty()
                errors.first().id shouldBe TtrpDiagnosticId.PRS_001
                errors.first().location.column shouldBe column
            }
        }

        "a whole expression parses clean, with or without surrounding whitespace and comments" {
            listOf(
                "a",
                "  a = 1  ",
                "(a + b) * 2 // a comment",
                "x between 1 and 3\n",
                "coalesce(a, b) is not null /* c */",
            ).forEach { TtrpParser.parseExpression(it).diagnostics.shouldBeEmpty() }
        }

        "an empty input is a syntax error, never a crash" {
            TtrpParser
                .parseExpression("")
                .diagnostics
                .first()
                .id shouldBe TtrpDiagnosticId.PRS_001
            TtrpParser
                .parseExpression("   ")
                .diagnostics
                .first()
                .id shouldBe TtrpDiagnosticId.PRS_001
        }
    })
