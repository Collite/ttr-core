// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.expr

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.tatrman.ttrp.parser.TtrpParser

class TtrpConcatSpec :
    StringSpec({
        val checker = ExpressionTypechecker()
        "two strings concatenate with a string result including nested quarter labels" {
            for (source in listOf("concat('2026-', 'Q4')", "concat(concat('2026', '-Q'), '4')", "concat(null, 'x')")) {
                val parsed = TtrpParser.parseExpression(source)
                parsed.diagnostics shouldBe emptyList()
                val checked = checker.check(parsed.expression, emptyMap())
                checked.diagnostics shouldBe emptyList()
                checked.type shouldBe TtrpType.Str
            }
        }
        "concat has exactly two string operands without numeric coercion" {
            for ((source, diagnostic) in listOf(
                "concat('x')" to "TTRP-FN-002",
                "concat('x', 'y', 'z')" to "TTRP-FN-002",
                "concat(1, 'x')" to "TTRP-TYP-001",
                "concat('x', true)" to "TTRP-TYP-001",
            )) {
                checker
                    .check(TtrpParser.parseExpression(source).expression, emptyMap())
                    .diagnostics
                    .any { it.id.id == diagnostic } shouldBe true
            }
        }
    })
