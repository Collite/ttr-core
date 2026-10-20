// SPDX-License-Identifier: Apache-2.0
package org.tatrman.translator.wire

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.apache.calcite.plan.RelOptUtil
import org.apache.calcite.rel.RelNode
import org.apache.calcite.rel.core.Join
import org.tatrman.plan.v1.PlanNode
import org.tatrman.plan.v1.SchemaCode
import org.tatrman.translate.v1.Language
import org.tatrman.translate.v1.SqlDialect
import org.tatrman.translator.framework.HartlandJoinModel
import org.tatrman.translator.framework.TranslatorFramework
import org.tatrman.translator.joiner.JoinerPlanWalker
import org.tatrman.translator.orchestrator.ParseResult
import org.tatrman.translator.orchestrator.Translator
import org.tatrman.translator.orchestrator.UnparseResult

/**
 * ttr-server#232 — `JoinNode.condition_required` (plan.v1 field 5): the SQL carrier's mark on a bare join it could not
 * condition, on a parse asked to refuse one that stays a Cartesian product.
 *
 * **Carry mechanism** ([ExpandedFromWireSpec]'s): proto bytes carry it (the ER plan of the two-half pipeline, a cache).
 * Through a Calcite round trip (REL_NODE re-entry) it rides the decoded Join as a `RelHint` ([ConditionRequiredHint])
 * and the encoder restores it. A conditioned join carries none, so no physical plan that is not refused does either.
 */
class ConditionRequiredWireSpec :
    StringSpec({
        val model = HartlandJoinModel.handle()
        val unrelated = "SELECT calendar.day_name, product.product_name FROM calendar JOIN product"

        fun erPlan(refuse: Boolean): PlanNode =
            Translator(model)
                .parseToRelNode(
                    unrelated,
                    Language.SQL,
                    SchemaCode.ER,
                    sourceSchema = SchemaCode.ER,
                    refuseUnconditionedBareJoins = refuse,
                ).shouldBeInstanceOf<ParseResult.Success>()
                .plan

        fun join(plan: PlanNode): PlanNode {
            var found: PlanNode? = null
            JoinerPlanWalker.forEachNode(plan) {
                if (it.nodeCase == PlanNode.NodeCase.JOIN) found = it
            }
            return found!!
        }

        fun join(rel: RelNode): Join = rel as? Join ?: join(rel.inputs.single())

        /** [plan] with every join's mark set to [on]. */
        fun marked(
            plan: PlanNode,
            on: Boolean,
        ): PlanNode {
            val p = JoinerPlanWalker.rewriteChildren(plan) { marked(it, on) }
            return if (p.nodeCase == PlanNode.NodeCase.JOIN) {
                p.toBuilder().setJoin(p.join.toBuilder().setConditionRequired(on)).build()
            } else {
                p
            }
        }

        "the mark round-trips through proto bytes, byte-stably" {
            val plan = erPlan(refuse = true)
            join(plan).join.conditionRequired shouldBe true
            val again = PlanNode.parseFrom(plan.toByteArray())
            again.toByteArray().contentEquals(plan.toByteArray()) shouldBe true
        }

        "an unmarked join serializes exactly as before the field existed, and the mark is the only difference" {
            val plain = erPlan(refuse = false)
            join(plain).join.conditionRequired shouldBe false
            marked(erPlan(refuse = true), on = false).toByteArray().contentEquals(plain.toByteArray()) shouldBe true
        }

        "a Calcite round trip carries the mark as a hint: decode → encode is byte-identical" {
            val plan = erPlan(refuse = true)
            val rel = PlanNodeDecoder.decode(plan, TranslatorFramework(model, SchemaCode.ER, "entity"))
            join(rel).hints.map { it.hintName } shouldBe listOf(ConditionRequiredHint.NAME)
            PlanNodeEncoder.encode(rel).toByteArray().contentEquals(plan.toByteArray()) shouldBe true
        }

        "Calcite's hint propagation (as a decorrelation runs it) keeps the mark on its own join" {
            // The outer bare join is marked; the author's CROSS JOIN below it is not, and must not inherit the mark.
            val plan =
                Translator(model)
                    .parseToRelNode(
                        "SELECT calendar.day_name FROM calendar CROSS JOIN promo JOIN household",
                        Language.SQL,
                        SchemaCode.ER,
                        sourceSchema = SchemaCode.ER,
                        refuseUnconditionedBareJoins = true,
                    ).shouldBeInstanceOf<ParseResult.Success>()
                    .plan
            val rel = PlanNodeDecoder.decode(plan, TranslatorFramework(model, SchemaCode.ER, "entity"))
            val outer = join(RelOptUtil.propagateRelHints(rel, true))
            outer.hints.map { it.hintName } shouldBe listOf(ConditionRequiredHint.NAME)
            outer.left.shouldBeInstanceOf<Join>().hints shouldBe emptyList()
        }

        "an unmarked join decodes with no hint (nothing new reaches Calcite)" {
            val rel =
                PlanNodeDecoder.decode(
                    erPlan(refuse = false),
                    TranslatorFramework(model, SchemaCode.ER, "entity"),
                )
            join(rel).hints shouldBe emptyList()
        }

        "the mark never reaches SQL: a marked DB plan unparses exactly as the unmarked one" {
            val db =
                Translator(model)
                    .parseToRelNode(
                        "SELECT calendar.day_name, product.product_name FROM calendar CROSS JOIN product",
                        Language.SQL,
                        sourceSchema = SchemaCode.ER,
                    ).shouldBeInstanceOf<ParseResult.Success>()
                    .plan
            val marked = marked(db, on = true)
            join(marked).join.conditionRequired shouldBe true

            fun sql(plan: PlanNode) =
                Translator(model)
                    .unparseFromRelNode(plan, Language.SQL, SqlDialect.MSSQL)
                    .shouldBeInstanceOf<UnparseResult.Success>()
                    .output
            sql(marked) shouldBe sql(db)
        }
    })
