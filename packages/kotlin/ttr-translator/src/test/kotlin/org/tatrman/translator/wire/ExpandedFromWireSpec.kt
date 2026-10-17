// SPDX-License-Identifier: Apache-2.0
package org.tatrman.translator.wire

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.tatrman.plan.v1.PlanNode
import org.tatrman.plan.v1.SchemaCode
import org.tatrman.translate.v1.Language
import org.tatrman.translate.v1.SqlDialect
import org.tatrman.translator.framework.HartlandJoinModel
import org.tatrman.translator.framework.HartlandStackModel
import org.tatrman.translator.framework.TranslatorFramework
import org.tatrman.translator.orchestrator.Translator
import org.tatrman.translator.orchestrator.UnparseResult

/**
 * LQ-T1·S2 — `ProjectNode.expanded_from` (plan.v1 field 3, contract L-11): the stamp the translator's
 * `EXPAND_LOGICAL_BODIES` stage leaves on the boundary Project of an inlined logical-query body, naming the
 * stacked entity.
 *
 * **Carry mechanism.** Proto bytes carry it (the plan a translate call returns, the bytes a cache holds, the
 * `REL_NODE` unparse). Through a Calcite round trip (REL_NODE re-entry) it rides the decoded `LogicalProject` as
 * a `RelHint` ([ExpandedFromHint]) and the encoder restores it. MAP_TO_PHYSICAL ends it (pinned in
 * `LogicalQuerySpec`), so no physical plan — and no SQL / TransDSL / DFDSL rendering of one — carries it; those
 * codecs have no place for it either (no other wire changes, rules §5).
 */
class ExpandedFromWireSpec :
    StringSpec({
        val model = HartlandJoinModel.handle()

        /** An ER plan with a Project on top, stamped as the boundary of `shopper_revenue`. */
        fun stamped(): PlanNode {
            val plain = HartlandStackModel.parseEr(model, "SELECT sale.revenue AS revenue FROM sale")
            plain.nodeCase shouldBe PlanNode.NodeCase.PROJECT
            return plain
                .toBuilder()
                .setProject(plain.project.toBuilder().setExpandedFrom(HartlandStackModel.shopperRevenue.qname))
                .build()
        }

        "the stamp round-trips through proto bytes, byte-stably" {
            val plan = stamped()
            val again = PlanNode.parseFrom(plan.toByteArray())
            again.project.expandedFrom shouldBe HartlandStackModel.shopperRevenue.qname
            again.toByteArray().contentEquals(plan.toByteArray()) shouldBe true
        }

        "an unstamped Project serializes exactly as before the field existed (absent, not empty)" {
            val plain = HartlandStackModel.parseEr(model, "SELECT sale.revenue AS revenue FROM sale")
            plain.project.hasExpandedFrom() shouldBe false
        }

        "the REL_NODE unparse carries the stamp (the bytes are the plan)" {
            val plan = stamped()
            val out =
                Translator(model)
                    .unparseFromRelNode(plan, Language.REL_NODE, SqlDialect.MSSQL)
                    .shouldBeInstanceOf<UnparseResult.Success>()
            PlanNode.parseFrom(out.output.toByteArray(Charsets.ISO_8859_1)).project.expandedFrom shouldBe
                HartlandStackModel.shopperRevenue.qname
        }

        "a Calcite round trip carries the stamp as a hint: decode → encode is byte-identical" {
            val plan = stamped()
            val rel = PlanNodeDecoder.decode(plan, TranslatorFramework(model, SchemaCode.ER, "entity"))
            rel.shouldBeInstanceOf<org.apache.calcite.rel.core.Project>().hints.map { it.hintName } shouldBe
                listOf(ExpandedFromHint.NAME)
            PlanNodeEncoder.encode(rel).toByteArray().contentEquals(plan.toByteArray()) shouldBe true
        }

        "a stamp whose schema code this reader does not know survives the Calcite round trip by its number" {
            val unknown =
                HartlandStackModel.shopperRevenue.qname
                    .toBuilder()
                    .setSchemaCodeValue(99)
                    .build()
            unknown.schemaCode shouldBe SchemaCode.UNRECOGNIZED
            val plan =
                stamped().let { p ->
                    p.toBuilder().setProject(p.project.toBuilder().setExpandedFrom(unknown)).build()
                }
            val rel = PlanNodeDecoder.decode(plan, TranslatorFramework(model, SchemaCode.ER, "entity"))
            val again = PlanNodeEncoder.encode(rel)
            again.project.expandedFrom.schemaCodeValue shouldBe 99
            again.toByteArray().contentEquals(plan.toByteArray()) shouldBe true
        }

        "an unstamped Project decodes with no hint (nothing new reaches Calcite)" {
            val plain = HartlandStackModel.parseEr(model, "SELECT sale.revenue AS revenue FROM sale")
            PlanNodeDecoder
                .decode(plain, TranslatorFramework(model, SchemaCode.ER, "entity"))
                .shouldBeInstanceOf<org.apache.calcite.rel.core.Project>()
                .hints shouldBe emptyList()
        }
    })
