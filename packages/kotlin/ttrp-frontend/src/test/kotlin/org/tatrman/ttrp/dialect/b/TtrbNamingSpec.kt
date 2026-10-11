// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.dialect.b

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.tatrman.ttrp.ast.Assignment
import org.tatrman.ttrp.ast.ChainElem
import org.tatrman.ttrp.ast.DottedRef
import org.tatrman.ttrp.ast.ExprArg
import org.tatrman.ttrp.ast.OpCall
import org.tatrman.ttrp.diagnostics.Severity
import org.tatrman.ttrp.diagnostics.TtrpDiagnosticId
import org.tatrman.ttrp.expr.CaseWhen
import org.tatrman.ttrp.expr.ColumnRef
import org.tatrman.ttrp.expr.Expression
import org.tatrman.ttrp.expr.FunctionCall
import org.tatrman.ttrp.expr.IsNull
import org.tatrman.ttrp.expr.Literal

/**
 * B8 — (1) parentheses group any sub-condition — in filters, join conditions, `If` / `Když` headers and
 * conditional values; without them `and` binds tighter than `or`. (2) `Call that <name>.` /
 * `Pojmenuj to jako <jméno>.` names the CURRENT value (it stays current); `Load <bound name>.` makes a
 * named value current again. (3) A value a sentence produces that nothing downstream reads — no later
 * sentence, port or display — is TTRP-B-113 at that sentence.
 */
class TtrbNamingSpec :
    StringSpec({
        fun e(x: Expression?): String =
            when (x) {
                null -> "-"
                is ColumnRef -> "col(${x.port?.plus(".") ?: ""}${x.column})"
                is Literal -> x.value.toString()
                is FunctionCall -> "${x.function.value}(${x.args.joinToString(",") { e(it) }})"
                is IsNull -> "isnull${if (x.negated) "!n" else ""}(${e(x.expr)})"
                is CaseWhen -> "case(" + x.branches.joinToString(";") { "${e(it.first)}=>${e(it.second)}" } + ")"
                else -> x.toString()
            }

        fun ops(elems: List<ChainElem>): List<String> =
            elems.map { if (it is OpCall) it.name else "ref:" + (it as DottedRef).parts.joinToString(".") }

        fun firstArg(op: OpCall): Expression = (op.args.first().value as ExprArg).expr

        // ---- (1) grouping ----
        val groups = TtrbCorpus.decompose("naming/groups.ttrb")

        "grouped conditions decompose clean in both skins, the Czech exactly as the English" {
            groups.diagnostics.shouldBeEmpty()
            val cs = TtrbCorpus.decompose("naming/groups.ttrb-cs")
            cs.diagnostics.shouldBeEmpty()
            TtrbCorpus.canonical(cs.statements) shouldBe TtrbCorpus.canonical(groups.statements)
        }

        "a grouped filter keeps its grouping; without parentheses `and` binds tighter than `or`" {
            val y = groups.statements.filterIsInstance<Assignment>().single { it.target == "y" }
            // y = join(left: _b, …) where _b = o -> filter -> filter -> filter -> calc
            val chain =
                groups.statements.filterIsInstance<Assignment>().single {
                    ops(it.chain.elements).count { o ->
                        o ==
                            "filter"
                    } ==
                        3
                }
            val filters =
                chain.chain.elements.filterIsInstance<OpCall>().filter { it.name == "filter" }.map {
                    e(
                        firstArg(it),
                    )
                }
            filters[0] shouldBe
                "op.and(op.or(op.lt(col(aa),col(bb)),isnull(col(cc))),op.or(op.eq(col(dd),col(ee)),isnull(col(ff))))"
            filters[1] shouldBe "op.or(op.lt(col(aa),col(bb)),op.and(isnull(col(cc)),op.eq(col(dd),col(ee))))"
            filters[2] shouldBe "op.gt(op.mul(op.add(col(amount),col(fee)),Num(raw=2)),Num(raw=100))"
            val calc =
                chain.chain.elements
                    .filterIsInstance<OpCall>()
                    .single { it.name == "calc" }
            e((calc.config!!.entries.single() as org.tatrman.ttrp.ast.AssignEntry).value) shouldContain
                "op.or(op.and(op.gt(col(amount),Num(raw=5000)),isnull!n(col(note))),op.eq(col(state),Str(value=urgent)))"
            val on = (y.chain.elements.single() as OpCall).args.single { it.name == "on" }
            e((on.value as ExprArg).expr) shouldBe
                "op.and(op.eq(col(kk),col(right.kk2)),op.or(op.lt(col(aa),col(right.bb)),op.gt(col(cc),col(right.dd))))"
        }

        "a grouped `If` header" {
            val block =
                groups.statements.filterIsInstance<Assignment>().last {
                    ops(it.chain.elements).last() ==
                        "filter"
                }
            e(firstArg(block.chain.elements.last() as OpCall)) shouldBe
                "op.and(op.or(op.eq(col(prio),Num(raw=1)),op.gt(col(amount),Num(raw=10000))),op.eq(col(state),Str(value=open)))"
        }

        // ---- (2) naming ----
        val naming = TtrbCorpus.decompose("naming/naming.ttrb")

        "naming decomposes clean in both skins, the Czech exactly as the English" {
            naming.diagnostics.shouldBeEmpty()
            val cs = TtrbCorpus.decompose("naming/naming.ttrb-cs")
            cs.diagnostics.shouldBeEmpty()
            TtrbCorpus.canonical(cs.statements) shouldBe TtrbCorpus.canonical(naming.statements)
        }

        "`Call that <name>` binds the current value (a pending chain) — the join then reads the FILTERED value" {
            val stmts = naming.statements.filterIsInstance<Assignment>()
            val z2 = stmts.filter { it.target == "z2" }
            ops(z2[1].chain.elements) shouldContainExactly listOf("ref:z2", "filter")
            val j =
                stmts
                    .single { it.target == "j" }
                    .chain.elements
                    .single() as OpCall
            ((j.args.single { it.name == "right" }.value as ExprArg).expr as ColumnRef).column shouldBe "z2"
            ops(stmts.single { it.target == "candidates" }.chain.elements) shouldContainExactly
                listOf("ref:j", "filter")
        }

        "`Load <bound name>` makes it current again — no new load; the match reads the named value" {
            val loads =
                naming.statements
                    .flatMap { (it as? Assignment)?.chain?.elements.orEmpty() }
                    .filterIsInstance<OpCall>()
                    .filter {
                        it.name ==
                            "load"
                    }
            loads.size shouldBe 2
            val semi =
                naming.statements.filterIsInstance<Assignment>().single {
                    ops(it.chain.elements) ==
                        listOf("join") &&
                        it.target.startsWith("_b")
                }
            val join = semi.chain.elements.single() as OpCall
            ((join.args.single { it.name == "left" }.value as ExprArg).expr as ColumnRef).column shouldBe "pol"
            ((join.args.single { it.name == "right" }.value as ExprArg).expr as ColumnRef).column shouldBe "candidates"
        }

        // ---- (3) unused results ----
        for (rel in listOf("naming/unused.ttrb", "naming/unused.ttrb-cs")) {
            "$rel: a filter whose result nothing reads is TTRP-B-113 (error) at that sentence" {
                val d = TtrbCorpus.decompose(rel, outPorts = setOf("seznam")).diagnostics.single()
                d.id shouldBe TtrpDiagnosticId.B_113
                d.severity shouldBe Severity.ERROR
                d.location.line shouldBe 4
                d.message shouldContain (if (rel.endsWith("-cs")) "`Ponech`" else "`Keep`")
                d.suggestedAlternative!! shouldContain (if (rel.endsWith("-cs")) "Pojmenuj to jako" else "Call that")
            }
        }

        "a block whose filter result no sentence inside reads is TTRP-B-113 at the header" {
            val d = TtrbCorpus.decompose("naming/unused-block.ttrb").diagnostics.single()
            d.id shouldBe TtrpDiagnosticId.B_113
            d.location.line shouldBe 2
            d.message shouldContain "`If`"
        }

        "a load nothing reads is TTRP-B-113" {
            val d = TtrbCorpus.decompose("naming/unused-load.ttrb").diagnostics.single()
            d.id shouldBe TtrpDiagnosticId.B_113
            d.location.line shouldBe 1
        }

        "a trailing transform is read by the container's default OUT port — unless the container has none" {
            fun decompose(outs: Set<String>) =
                TtrB.decompose(
                    "Load orders.\nKeep the rows where a is more than 1.\n",
                    org.tatrman.ttrp.ast.SourceLocation.UNKNOWN,
                    outs.firstOrNull(),
                    TtrbSkin.EN,
                    inPorts = setOf("orders"),
                    outPorts = outs,
                )
            decompose(setOf("late")).diagnostics.shouldBeEmpty()
            decompose(emptySet()).diagnostics.single().id shouldBe TtrpDiagnosticId.B_113
        }
    })
