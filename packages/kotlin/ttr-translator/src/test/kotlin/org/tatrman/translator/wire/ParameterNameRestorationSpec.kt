// SPDX-License-Identifier: Apache-2.0
package org.tatrman.translator.wire

import org.tatrman.plan.v1.PlanNode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.tatrman.translator.codec.sql.SqlValidator
import org.tatrman.translator.codec.sql.ValidateResult
import org.tatrman.translator.framework.FixtureModel
import org.tatrman.translator.framework.TranslatorFramework
import org.tatrman.translator.params.ParameterBridge
import org.tatrman.translator.params.ParameterTyper
import org.tatrman.translator.params.SqlParam

/**
 * Phase 08 A2 — verifies that [PlanNodeEncoder] restores the original `{name}` on
 * `RexDynamicParam` nodes when the orchestrator threads in the prepared `parameterNames` map,
 * and that the round-trip through encode → decode preserves both the type and the name.
 */
class ParameterNameRestorationSpec :
    StringSpec({

        fun parse(sql: String): org.apache.calcite.rel.RelNode {
            val fw = TranslatorFramework(FixtureModel.handle())
            val r = SqlValidator.validateAndConvert(fw.newPlanner(), sql)
            r.shouldBeInstanceOf<ValidateResult.Success>()
            return r.rel
        }

        "PlanNodeEncoder restores the original {name} on RexDynamicParam when parameterNames is supplied" {
            val prepared =
                ParameterBridge.prepareSqlForCalcite(
                    "SELECT id FROM customers WHERE id = {cid}",
                    listOf(SqlParam("cid", "int", 7)),
                )
            val rel = parse(prepared.sql)
            val parameterNames: Map<Int, String> =
                prepared.parameterOrder.mapIndexed { i, n -> i to n }.toMap()

            val plan = PlanNodeEncoder.encode(rel, parameterNames)
            // The plan is Project → Filter → TableScan. The Filter's condition is `eq(id, ?)`.
            val cond = plan.project.input.filter.condition.function
            val paramOperand = cond.operandsList.first { it.hasParameter() }.parameter
            paramOperand.name shouldBe "cid"
            paramOperand.positionalIndex shouldBe 0
        }

        "PlanNodeEncoder falls back to positional ?N name when parameterNames is empty (back-compat)" {
            val prepared =
                ParameterBridge.prepareSqlForCalcite(
                    "SELECT id FROM customers WHERE id = {cid}",
                    listOf(SqlParam("cid", "int", 7)),
                )
            val rel = parse(prepared.sql)
            val plan = PlanNodeEncoder.encode(rel) // single-arg → no name restoration
            val cond = plan.project.input.filter.condition.function
            val paramOperand = cond.operandsList.first { it.hasParameter() }.parameter
            paramOperand.name shouldBe "?0"
        }

        "after ParameterTyper.applyTypes + encode, the wire-format result_type carries the declared type" {
            val prepared =
                ParameterBridge.prepareSqlForCalcite(
                    "SELECT name FROM customers WHERE name = {n}",
                    listOf(SqlParam("n", "text", "Alice")),
                )
            val rel = parse(prepared.sql)
            val typeFactory = TranslatorFramework(FixtureModel.handle()).newRelBuilder().typeFactory
            val typed = ParameterTyper.applyTypes(rel, prepared, typeFactory)

            val parameterNames: Map<Int, String> =
                prepared.parameterOrder.mapIndexed { i, n -> i to n }.toMap()
            val plan = PlanNodeEncoder.encode(typed, parameterNames)

            val cond = plan.project.input.filter.condition.function
            val paramExpr = cond.operandsList.first { it.hasParameter() }
            paramExpr.resultType shouldBe "text"
            paramExpr.parameter.name shouldBe "n"
        }

        "round-trip via encode + decode preserves param type and name" {
            val prepared =
                ParameterBridge.prepareSqlForCalcite(
                    "SELECT name FROM customers WHERE name = {n}",
                    listOf(SqlParam("n", "text", "Alice")),
                )
            val rel = parse(prepared.sql)
            val srcFw = TranslatorFramework(FixtureModel.handle())
            val typed = ParameterTyper.applyTypes(rel, prepared, srcFw.newRelBuilder().typeFactory)

            val parameterNames: Map<Int, String> =
                prepared.parameterOrder.mapIndexed { i, n -> i to n }.toMap()
            val plan = PlanNodeEncoder.encode(typed, parameterNames)
            val bytes = plan.toByteArray()
            val decodedPlan = PlanNode.parseFrom(bytes)
            val freshFw = TranslatorFramework(FixtureModel.handle())
            val rebuilt = PlanNodeDecoder.decode(decodedPlan, freshFw)

            // Rebuilt rel still has the parameter; the name is on the wire format, not the
            // RexNode (Calcite's RexDynamicParam has no name field), so we re-inspect the wire.
            val rebuiltPlan = PlanNodeEncoder.encode(rebuilt, parameterNames)
            val paramExpr =
                rebuiltPlan.project.input.filter.condition.function.operandsList
                    .first { it.hasParameter() }
            paramExpr.parameter.name shouldBe "n"
            paramExpr.resultType shouldBe "text"
        }
    })
