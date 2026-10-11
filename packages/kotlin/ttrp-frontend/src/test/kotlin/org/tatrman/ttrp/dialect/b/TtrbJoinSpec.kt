// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.dialect.b

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.tatrman.ttrp.ast.Assignment
import org.tatrman.ttrp.ast.ExprArg
import org.tatrman.ttrp.ast.OpCall
import org.tatrman.ttrp.ast.RelationArg
import org.tatrman.ttrp.expr.ColumnRef
import org.tatrman.ttrp.expr.Expression
import org.tatrman.ttrp.expr.FunctionCall
import org.tatrman.ttrp.expr.IsNull

/**
 * B7 joins: an OPTIONAL join (`volitelně` / `optionally` → `type: left`, unmatched right columns NULL);
 * keep the rows that HAVE / HAVE NO match in another table (semi / anti join of the current value;
 * `Remove … that have a match` is the anti join); a join ON A MODEL RELATION (`přes vazbu r` /
 * `on relation r` → `on: relation r`). `je prázdný` / `is empty` works inside a join condition.
 */
class TtrbJoinSpec :
    StringSpec({
        val en = TtrbCorpus.decompose("joins/joins.ttrb")
        val cs = TtrbCorpus.decompose("joins/joins.ttrb-cs")
        val joins =
            en.statements.filterIsInstance<Assignment>().filter {
                (it.chain.elements.singleOrNull() as? OpCall)?.name ==
                    "join"
            }

        fun op(a: Assignment) = a.chain.elements.single() as OpCall

        fun arg(
            a: Assignment,
            name: String,
        ): Any = op(a).args.single { it.name == name }.value

        fun ref(
            a: Assignment,
            name: String,
        ): String = ((arg(a, name) as ExprArg).expr as ColumnRef).column

        fun e(x: Expression): String =
            when (x) {
                is ColumnRef -> "col(${x.port?.plus(".") ?: ""}${x.column})"
                is FunctionCall -> "${x.function.value}(${x.args.joinToString(",") { e(it) }})"
                is IsNull -> "isnull${if (x.negated) "!n" else ""}(${e(x.expr)})"
                else -> x.toString()
            }

        "the join fixtures decompose clean; the Czech twin lowers identically" {
            en.diagnostics.shouldBeEmpty()
            cs.diagnostics.shouldBeEmpty()
            TtrbCorpus.canonical(cs.statements) shouldBe TtrbCorpus.canonical(en.statements)
        }

        "the join kinds, in sentence order" {
            joins.map { ref(it, "type") } shouldContainExactly listOf("left", "semi", "anti", "anti", "inner", "left")
        }

        "optional join: type left; `is not empty` on a right column inside the condition" {
            val all = joins.single { it.target == "all_orders" }
            ref(all, "left") shouldBe "o"
            ref(all, "right") shouldBe "sk"
            e((arg(all, "on") as ExprArg).expr) shouldBe
                "op.and(op.eq(col(item),col(right.item)),isnull!n(col(right.qty)))"
        }

        "match / no match: a semi / anti join of the CURRENT value, each the next current value" {
            val (semi, anti, removed) = joins.subList(1, 4)
            ref(semi, "left") shouldBe "all_orders"
            ref(semi, "right") shouldBe "sk"
            ref(anti, "left") shouldBe semi.target
            ref(anti, "right") shouldBe "returns"
            ref(removed, "left") shouldBe anti.target
            e((arg(semi, "on") as ExprArg).expr) shouldBe "op.eq(col(item),col(right.item))"
            en.derivedInPorts shouldContainExactly listOf("orders", "stock", "returns", "blocked")
        }

        "join on a model relation: `on: relation <r>`, inner or optional" {
            val linked = joins.single { it.target == "linked" }
            (arg(linked, "on") as RelationArg).qname.text shouldBe "order_stock"
            ref(linked, "type") shouldBe "inner"
            ref(joins.single { it.target == "maybe_linked" }, "type") shouldBe "left"
        }
    })
