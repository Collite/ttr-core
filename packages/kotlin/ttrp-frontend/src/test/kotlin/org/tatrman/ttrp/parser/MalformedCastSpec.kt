// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.parser

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.tatrman.ttrp.expr.Cast
import org.tatrman.ttrp.expr.TtrpType

/**
 * `cast(x as <type>)` is the one cast spelling (B-T5). The comma form `cast(x, string)` used to throw a raw
 * `NullPointerException: typeName(...) must not be null` out of the walker (the parser recovered without a
 * type); it is now a TTRP-PRS-001 diagnostic with a suggestion, and the parse completes.
 */
class MalformedCastSpec :
    StringSpec({
        "cast(x as string) parses to a Cast with the target type" {
            val r = TtrpParser.parseExpression("cast(order_id as string)")
            r.diagnostics shouldBe emptyList()
            val c = r.expression.shouldBeInstanceOf<Cast>()
            c.target shouldBe TtrpType.Str
        }

        "cast(x, string) is a TTRP-PRS-001 diagnostic, not a NullPointerException" {
            val r =
                TtrpParser.parseString(
                    "container c target erp {\n    k = load(t) -> calc { k = cast(order_id, string) }\n}\n",
                )
            r.diagnostics.map { it.id.id } shouldContain "TTRP-PRS-001"
            val malformed = r.diagnostics.single { it.message.contains("malformed cast") }
            malformed.message shouldContain "cast(<expr> as <type>)"
            malformed.suggestedAlternative!! shouldContain "cast(x as string)"
        }

        "the comma form mid-chain (`calc { k = cast(x, string) } -> select(…)`) is a diagnostic too" {
            val r =
                TtrpParser.parseString(
                    "container c(out o) target erp {\n" +
                        "    o = load(t) -> calc { k = cast(order_id, string) } -> select(order_id, k)\n}\n",
                )
            r.diagnostics.any { it.message.contains("malformed cast") } shouldBe true
        }

        "cast(x) (no type at all) is also a diagnostic" {
            val r = TtrpParser.parseExpression("cast(order_id)")
            r.diagnostics.any { it.message.contains("malformed cast") } shouldBe true
        }
    })
