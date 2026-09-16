// SPDX-License-Identifier: Apache-2.0
package org.tatrman.translator.wire

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.comparables.shouldBeEqualComparingTo
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.apache.calcite.rex.RexLiteral
import org.tatrman.plan.v1.Literal
import org.tatrman.translator.framework.FixtureModel
import org.tatrman.translator.framework.TranslatorFramework
import java.math.BigDecimal

/**
 * TF-P1.S1 (G A1) — a DECIMAL literal's `RexLiteral.value2` is the *unscaled* value (`1.5` → `15`), so
 * encoding it made `x * 100.0` into `x * 1000` on the wire: silent wrong results in every percentage
 * pattern. The wire carries the numeric value; decode restores it exactly.
 */
class LiteralCodecSpec :
    StringSpec({
        val builder = TranslatorFramework(FixtureModel.handle()).newRelBuilder()
        val rexBuilder = builder.rexBuilder

        for (text in listOf("1.5", "0.25", "100.0", "12.75", "3.333", "1E-3")) {
            "DECIMAL literal $text encodes to its numeric value and decodes back to it" {
                val lit = rexBuilder.makeExactLiteral(BigDecimal(text))
                val encoded = Expressions.encode(lit)

                encoded.literal.valueCase shouldBe Literal.ValueCase.FLOAT_VALUE
                encoded.literal.floatValue shouldBe BigDecimal(text).toDouble()

                val decoded = Expressions.decode(builder, encoded)
                decoded.shouldBeInstanceOf<RexLiteral>()
                decoded.getValueAs(BigDecimal::class.java)!! shouldBeEqualComparingTo BigDecimal(text)
            }
        }

        "an integer literal still encodes as int_value" {
            val encoded = Expressions.encode(rexBuilder.makeExactLiteral(BigDecimal("7")))
            encoded.literal.valueCase shouldBe Literal.ValueCase.INT_VALUE
            encoded.literal.intValue shouldBe 7L
        }
    })
