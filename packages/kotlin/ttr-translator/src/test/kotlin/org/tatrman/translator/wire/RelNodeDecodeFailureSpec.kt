// SPDX-License-Identifier: Apache-2.0
package org.tatrman.translator.wire

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.tatrman.plan.v1.PlanNode
import org.tatrman.plan.v1.SchemaCode
import org.tatrman.translate.v1.Language
import org.tatrman.translator.framework.HartlandJoinModel
import org.tatrman.translator.framework.TranslatorFramework
import org.tatrman.translator.orchestrator.ParseResult
import org.tatrman.translator.orchestrator.Translator
import org.tatrman.translator.params.SqlParam

/**
 * AU-core S2.4c (ttr-core#407) T4 — a REL_NODE decode failure tells the truth. The code stays
 * `rel_node_schema_resolution_failed` (callers match on it); the message names the exception's class, its message and
 * the plan node that failed. [PlanNodeDecoder.decode] keeps throwing the original exception.
 */
class RelNodeDecodeFailureSpec :
    StringSpec({
        val model = HartlandJoinModel.handle()

        fun reenter(plan: PlanNode): ParseResult =
            Translator(model).parseToRelNode(
                String(plan.toByteArray(), Charsets.ISO_8859_1),
                Language.REL_NODE,
                targetSchema = SchemaCode.DB,
            )

        fun erPlan(
            sql: String,
            params: List<SqlParam> = emptyList(),
        ): PlanNode =
            Translator(model)
                .parseToRelNode(
                    sql,
                    Language.SQL,
                    targetSchema = SchemaCode.ER,
                    sourceSchema = SchemaCode.ER,
                    parameters = params,
                ).shouldBeInstanceOf<ParseResult.Success>()
                .plan

        /**
         * The ER plan master's ParameterTyper wrote for `'x ' || CAST({w} AS VARCHAR)` over an `int` w: the author's cast
         * stripped, the bare `int` parameter left inside the `||` (the plan the hartland `reject_late` port sent).
         */
        fun strippedCastPlan(): PlanNode {
            val plan = erPlan("SELECT 'x ' || CAST({w} AS VARCHAR) AS r FROM sale", listOf(SqlParam("w", "int", 30)))
            val b = plan.toBuilder()
            val concat =
                b.projectBuilder
                    .getExpressionsBuilder(0)
                    .expressionBuilder.functionBuilder
            val castAt = concat.operandsList.indexOfFirst { it.hasFunction() && it.function.operation == "cast" }
            concat.setOperands(castAt, concat.getOperands(castAt).function.getOperands(0))
            return b.build()
        }

        "F1 — an unknown Scan qname still yields the code, and the message names the qname" {
            val plan = erPlan("SELECT shopper_id FROM sale")
            val b = plan.toBuilder()
            b.projectBuilder.inputBuilder.scanBuilder.objectBuilder
                .setName("nowhere")
            val failure = reenter(b.build()).shouldBeInstanceOf<ParseResult.Failure>()
            failure.code shouldBe "rel_node_schema_resolution_failed"
            failure.message shouldContain "nowhere"
            failure.message shouldContain "at SCAN"
        }

        "F2 — #407's plan names the real exception and the node: IllegalArgumentException at PROJECT" {
            val failure = reenter(strippedCastPlan()).shouldBeInstanceOf<ParseResult.Failure>()
            failure.code shouldBe "rel_node_schema_resolution_failed"
            failure.message shouldBe "IllegalArgumentException: no message at PROJECT"
        }

        "F3 — PlanNodeDecoder.decode keeps throwing the original exception; decodeNamed names the node" {
            shouldThrow<IllegalArgumentException> {
                PlanNodeDecoder.decode(
                    strippedCastPlan(),
                    TranslatorFramework(model),
                )
            }
            val named =
                shouldThrow<PlanNodeDecoder.DecodeException> {
                    PlanNodeDecoder.decodeNamed(strippedCastPlan(), TranslatorFramework(model))
                }
            named.nodeCase shouldBe PlanNode.NodeCase.PROJECT
            named.cause.shouldBeInstanceOf<IllegalArgumentException>()
        }
    })
