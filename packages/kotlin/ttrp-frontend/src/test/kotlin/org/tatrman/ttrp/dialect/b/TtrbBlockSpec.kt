// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.dialect.b

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.tatrman.ttrp.ast.Assignment
import org.tatrman.ttrp.ast.ChainElem
import org.tatrman.ttrp.ast.DottedRef
import org.tatrman.ttrp.ast.ExprArg
import org.tatrman.ttrp.ast.OpCall
import org.tatrman.ttrp.ast.SourceLocation
import org.tatrman.ttrp.diagnostics.Severity
import org.tatrman.ttrp.diagnostics.TtrpDiagnosticId
import org.tatrman.ttrp.expr.FunctionCall

/**
 * B3 — block sentences. `If <pred>:` / `Když <pred>:` opens an indented block; each block is ONE
 * output: `filter(<current value>, <pred>)`, the block's sentences running on it. Blocks over the
 * same value OVERLAP (a row may satisfy several) — they are not else-if. A block inside a block is
 * TTRP-B-110.
 */
class TtrbBlockSpec :
    StringSpec({
        fun head(elems: List<ChainElem>): String = (elems.first() as DottedRef).parts.joinToString(".")

        fun ops(elems: List<ChainElem>): List<String> = elems.filterIsInstance<OpCall>().map { it.name }

        "If / Když blocks parse under their skins — sentences nest inside the block" {
            val kinds =
                listOf(
                    "load",
                    "block",
                    "keep-columns",
                    "show",
                    "end-block",
                    "block",
                    "compute",
                    "show",
                    "end-block",
                    "sort",
                )
            TtrbCorpus.parseFixture("blocks/two-blocks.ttrb").syntaxErrors.shouldBeEmpty()
            TtrbCorpus.sentenceKinds("blocks/two-blocks.ttrb") shouldContainExactly kinds
            TtrbCorpus.sentenceKinds("blocks/two-blocks.ttrb-cs") shouldContainExactly kinds
        }

        "two blocks ⇒ two outputs: one filter per block, BOTH over the same value (overlap, not else-if)" {
            val d = TtrbCorpus.decompose("blocks/two-blocks.ttrb")
            d.diagnostics.shouldBeEmpty()
            val filters =
                d.statements.filterIsInstance<Assignment>().filter { ops(it.chain.elements) == listOf("filter") }
            filters.size shouldBe 2
            filters.map { head(it.chain.elements) } shouldContainExactly listOf("t", "t")
            // the second block's predicate is its own — not conjoined with / negating the first
            val second = ((filters[1].chain.elements[1] as OpCall).args.single().value as ExprArg).expr as FunctionCall
            second.function.value shouldBe "op.or"
        }

        "sentences inside a block bind to that block's output" {
            val d = TtrbCorpus.decompose("blocks/two-blocks.ttrb")
            val stmts = d.statements.filterIsInstance<Assignment>()
            val blockNames = stmts.filter { ops(it.chain.elements) == listOf("filter") }.map { it.target }
            val north = stmts.single { it.target == "north_accounts" }
            val open = stmts.single { it.target == "open_accounts" }
            head(north.chain.elements) shouldBe blockNames[0]
            ops(north.chain.elements) shouldContainExactly listOf("project", "display")
            head(open.chain.elements) shouldBe blockNames[1]
            ops(open.chain.elements) shouldContainExactly listOf("calc", "display")
        }

        "a sentence after the blocks continues the main line, not the last block" {
            val d = TtrbCorpus.decompose("blocks/two-blocks.ttrb", outPort = "out")
            val tail = d.statements.filterIsInstance<Assignment>().single { it.target == "out" }
            head(tail.chain.elements) shouldBe "t"
            ops(tail.chain.elements) shouldContainExactly listOf("sort")
        }

        "the Czech blocks lower exactly as the English ones" {
            TtrbCorpus.canonical(TtrbCorpus.decompose("blocks/two-blocks.ttrb-cs").statements) shouldBe
                TtrbCorpus.canonical(TtrbCorpus.decompose("blocks/two-blocks.ttrb").statements)
        }

        "a block inside a block is TTRP-B-110, reported at the inner header" {
            val d = TtrbCorpus.decompose("blocks/nested.ttrb")
            val b110 = d.diagnostics.single { it.id == TtrpDiagnosticId.B_110 }
            b110.severity shouldBe Severity.ERROR
            b110.location.line shouldBe 3
            b110.suggestedAlternative shouldBe TtrB.rejectTable.entry("TTRP-B-110").suggest
        }

        "indentation is relative and continuation lines stay in their sentence" {
            val d = TtrbCorpus.decompose("blocks/continued.ttrb")
            d.diagnostics.shouldBeEmpty()
            val stmts = d.statements.filterIsInstance<Assignment>()
            val block = stmts.single { ops(it.chain.elements) == listOf("filter") }
            val shown = stmts.single { it.target == "north_south" }
            head(shown.chain.elements) shouldBe block.target
            ops(shown.chain.elements) shouldContainExactly listOf("project", "display")
        }

        "a block's filter carries the predicate's own span (the header), not the block's" {
            val src = "Load accounts as a.\nIf region is \"N\":\n  Show the result as n.\n"
            val d = TtrB.decompose(src, SourceLocation("b.ttrb", 1, 0, 1, 0, 0, src.length), null)
            val filter =
                d.statements
                    .filterIsInstance<Assignment>()
                    .single { ops(it.chain.elements) == listOf("filter") }
                    .chain.elements[1] as OpCall
            filter.location.line shouldBe 2
            filter.location.endLine shouldBe 2
        }
    })
