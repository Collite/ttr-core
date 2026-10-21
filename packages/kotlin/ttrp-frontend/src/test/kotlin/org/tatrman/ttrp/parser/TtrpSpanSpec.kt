// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.parser

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.tatrman.ttrp.ast.ContainerDecl
import org.tatrman.ttrp.ast.FragmentBody
import org.tatrman.ttrp.diagnostics.TtrpDiagnosticId
import org.tatrman.ttrp.expr.ColumnRef
import org.tatrman.ttrp.expr.FunctionCall

/**
 * review-119 L-1 — spans count code points, as ANTLR's columns and offsets do: an astral character on an earlier line
 * no longer ends a later span one column short, and a syntax error at the end of the input is an empty span (it used
 * to be `<EOF>` wide).
 */
class TtrpSpanSpec :
    StringSpec({
        "a span after an astral character on an earlier line ends where its token ends" {
            val sum = TtrpParser.parseExpression("a // 😀 note\n  + colour").expression
            val colour = sum.shouldBeInstanceOf<FunctionCall>().args[1].shouldBeInstanceOf<ColumnRef>()
            with(colour.location) {
                (line to column) shouldBe (2 to 4)
                (endLine to endColumn) shouldBe (2 to 10)
            }
        }

        "the same on the astral character's own line: columns count code points" {
            val sum = TtrpParser.parseExpression("'😀' + colour").expression
            val colour = sum.shouldBeInstanceOf<FunctionCall>().args[1].shouldBeInstanceOf<ColumnRef>()
            (colour.location.column to colour.location.endColumn) shouldBe (6 to 12)
        }

        "a fragment body with astral characters ends at its closing quotes (review-120 F15)" {
            val src =
                "uses world \"hartland.worlds.demo\"\n" +
                    "container a target erp_pg \"\"\"sql\n    -- 😀 a comment 😀😀\n    select 1\n\"\"\"\na -> display\n"
            val body =
                TtrpParser
                    .parseString(src, "frag.ttrp")
                    .document.statements
                    .firstNotNullOf { (it as? ContainerDecl)?.body as? FragmentBody }
            with(body.interiorLocation) { (endLine to endColumn) shouldBe (5 to 0) }
        }

        "a syntax error at the end of the input is an empty span" {
            val err = TtrpParser.parseExpression("a +").diagnostics.single()
            err.id shouldBe TtrpDiagnosticId.PRS_001
            err.location.endColumn shouldBe err.location.column
        }
    })
