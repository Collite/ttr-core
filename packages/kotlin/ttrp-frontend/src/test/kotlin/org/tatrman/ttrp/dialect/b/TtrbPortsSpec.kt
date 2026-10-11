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
import org.tatrman.ttrp.diagnostics.TtrpDiagnosticId
import org.tatrman.ttrp.expr.ColumnRef

/**
 * A container's ports in sentences. IN ports are names: wherever a sentence names a table (Load,
 * Count, Attach, Join, Combine, …) an in-port is read as the port, never `load(<port>)` (which is no
 * model object — TTRP-RES-001). OUT ports are written by `Send that to output <port>.` /
 * `Pošli to na výstup <port>.`: the port carries the current value; a port the header does not
 * declare is TTRP-B-112.
 */
class TtrbPortsSpec :
    StringSpec({
        val ins = setOf("orders", "shortages")
        val outs = setOf("big", "rest")

        fun decompose(
            rel: String,
            outPorts: Set<String>? = outs,
        ) = TtrbCorpus.decompose(rel, skin = TtrbCorpus.skinOf(rel), inPorts = ins, outPorts = outPorts)

        fun ops(elems: List<ChainElem>): List<String> =
            elems.map { if (it is OpCall) it.name else "ref:" + (it as DottedRef).parts.joinToString(".") }

        fun stmt(
            d: org.tatrman.ttrp.ast.FragmentDecomposition,
            target: String,
        ): Assignment = d.statements.filterIsInstance<Assignment>().single { it.target == target }

        val en = decompose("ports/ports.ttrb")

        "the port fixtures decompose clean, the Czech exactly as the English" {
            en.diagnostics.shouldBeEmpty()
            val cs = decompose("ports/ports.ttrb-cs")
            cs.diagnostics.shouldBeEmpty()
            TtrbCorpus.canonical(cs.statements) shouldBe TtrbCorpus.canonical(en.statements)
        }

        "an in-port is never loaded: `Load orders.` reads the port; only the non-port table is loaded" {
            val loads =
                en.statements
                    .filterIsInstance<Assignment>()
                    .flatMap { it.chain.elements }
                    .filterIsInstance<OpCall>()
                    .filter { it.name == "load" }
            loads.map { ((it.args.single().value as ExprArg).expr as ColumnRef).column } shouldContainExactly
                listOf("limits")
            en.derivedInPorts shouldContainExactly listOf("limits")
        }

        "count over an in-port starts from the port; join / combine name it directly" {
            val counted = en.statements.filterIsInstance<Assignment>().first()
            ops(counted.chain.elements) shouldContainExactly listOf("ref:shortages", "calc", "aggregate")
            val join = (stmt(en, "matched").chain.elements.single() as OpCall)
            ((join.args.single { it.name == "right" }.value as ExprArg).expr as ColumnRef).column shouldBe "shortages"
            ops(stmt(en, "rest").chain.elements) shouldContainExactly listOf("ref:o", "union")
        }

        "`Load <port> as <name>` is a plain name for the port (no node)" {
            ops(stmt(en, "o").chain.elements) shouldContainExactly listOf("ref:orders")
        }

        "`Send that to output <port>` binds the declared OUT port to the current value" {
            ops(stmt(en, "big").chain.elements) shouldContainExactly listOf("ref:matched", "filter")
        }

        "an output with nothing pending names the current value" {
            val d =
                TtrB.decompose(
                    "Load orders.\nSend that to output big.\n",
                    SourceLocation.UNKNOWN,
                    null,
                    TtrbSkin.EN,
                    inPorts = ins,
                    outPorts = outs,
                )
            d.diagnostics.shouldBeEmpty()
            ops(stmt(d, "big").chain.elements) shouldContainExactly listOf("ref:orders")
        }

        "an output to a port the header does not declare is TTRP-B-112, at the port name" {
            val d =
                TtrB.decompose(
                    "Load orders.\nSend that to output elsewhere.\n",
                    SourceLocation("x.ttrb", 1, 0, 1, 0, 0, 0),
                    null,
                    TtrbSkin.EN,
                    inPorts = ins,
                    outPorts = outs,
                )
            val b112 = d.diagnostics.single()
            b112.id shouldBe TtrpDiagnosticId.B_112
            b112.message.contains("elsewhere") shouldBe true
            b112.location.line shouldBe 2
        }

        "a keyword as the port name (`výsledek` folds to the Czech RESULT) is TTRP-B-109, not a shape error" {
            val d =
                TtrB.decompose(
                    "Načti orders.\nPošli to na výstup vysledek.\n",
                    SourceLocation.UNKNOWN,
                    null,
                    TtrbSkin.CS,
                    inPorts = ins,
                    outPorts = setOf("vysledek"),
                )
            d.diagnostics.single().id shouldBe TtrpDiagnosticId.B_109
        }

        "without port knowledge (a corpus fragment) a table is still loaded as before" {
            val d = TtrbCorpus.decompose("ports/ports.ttrb", outPorts = null)
            d.diagnostics.shouldBeEmpty()
            d.derivedInPorts shouldContainExactly listOf("orders", "shortages", "limits")
        }
    })
