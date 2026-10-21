// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.emit.sql

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.tatrman.plan.v1.ColumnRef as PbColumnRef
import org.tatrman.plan.v1.PlanNode
import org.tatrman.plan.v1.QualifiedName
import org.tatrman.plan.v1.SchemaCode
import org.tatrman.plan.v1.TableScanNode
import org.tatrman.ttrp.ast.SourceLocation
import org.tatrman.ttrp.expr.AggregateCall
import org.tatrman.ttrp.expr.CatalogId
import org.tatrman.ttrp.expr.ColumnRef
import org.tatrman.ttrp.expr.FunctionCall
import org.tatrman.ttrp.expr.Literal
import org.tatrman.ttrp.expr.LiteralValue
import org.tatrman.ttrp.graph.model.Aggregate
import org.tatrman.ttrp.graph.model.Aggregation

/**
 * AU-core-P2 — two SQL-emit generalities the recipe compile needed:
 *  - a join's output names: a right column repeating a left name is made unique as Calcite names it (`q` → `q0`)
 *    instead of a second alias the first shadows, and a right equi-key a qualified reference reads is kept;
 *  - an aggregate over expressions (`SUM(quantity * unit_price)`, `COALESCE(SUM(…), 0)`): plan.v1's aggregate takes
 *    plain columns only, so a projection computes the arguments and one after it the bindings.
 */
class JoinAndAggregateSpec :
    StringSpec({
        val loc = SourceLocation.UNKNOWN

        "a right equi-key is dropped (`a.* + b.(non-key)`) — unless kept" {
            JoinDedup.survivors(listOf("a", "b"), listOf("a", "c"), setOf("a")) shouldContainExactly
                listOf(0 to "a", 1 to "b", 3 to "c")
            JoinDedup.survivors(listOf("a", "b"), listOf("a", "c"), setOf("a"), keep = setOf("a")) shouldContainExactly
                listOf(0 to "a", 1 to "b", 2 to "a0", 3 to "c")
        }

        "a right non-key repeating a left name is its own column, `<name>0` (then `<name>1`…)" {
            JoinDedup.survivors(listOf("k", "q"), listOf("k", "q"), setOf("k")) shouldContainExactly
                listOf(0 to "k", 1 to "q", 3 to "q0")
            JoinDedup.unique(listOf(0 to "q", 1 to "q0", 2 to "q")) shouldContainExactly
                listOf(0 to "q", 1 to "q0", 2 to "q1")
        }

        "an aggregate over expressions: a projection of the arguments, the aggregate, a projection of the bindings" {
            val scan =
                PlanNode
                    .newBuilder()
                    .setTableScan(
                        TableScanNode
                            .newBuilder()
                            .setTable(
                                QualifiedName
                                    .newBuilder()
                                    .setSchemaCode(SchemaCode.DB)
                                    .setNamespace("dbo")
                                    .setName("t"),
                            ).addOutputColumns(PbColumnRef.newBuilder().setName("customer"))
                            .addOutputColumns(PbColumnRef.newBuilder().setName("quantity"))
                            .addOutputColumns(PbColumnRef.newBuilder().setName("unit_price")),
                    ).build()
            val product =
                FunctionCall(
                    CatalogId.MUL,
                    listOf(ColumnRef(null, "quantity", loc), ColumnRef(null, "unit_price", loc)),
                    loc,
                )
            val sum = AggregateCall(CatalogId("agg.sum"), listOf(product), false, loc)
            val node =
                Aggregate(
                    "a",
                    "a",
                    loc,
                    groupBy = listOf("customer"),
                    aggregations =
                        listOf(
                            Aggregation("value", sum),
                            Aggregation(
                                "value_or_zero",
                                FunctionCall(
                                    CatalogId("fn.coalesce"),
                                    listOf(sum, Literal(LiteralValue.Num("0"), loc)),
                                    loc,
                                ),
                            ),
                        ),
                )
            val plan = PlanNodeBuilder().body(node, listOf(scan))
            // post-projection ← aggregate ← pre-projection ← the scan
            plan.nodeCase shouldBe PlanNode.NodeCase.PROJECT
            plan.project.expressionsList.map { it.alias } shouldContainExactly
                listOf("customer", "value", "value_or_zero")
            val agg = plan.project.input
            agg.nodeCase shouldBe PlanNode.NodeCase.AGGREGATE
            agg.aggregate.aggregatesList.map { it.argsList.single().name } shouldContainExactly
                listOf("_ttrp_a0", "_ttrp_a1")
            agg.aggregate.input.nodeCase shouldBe PlanNode.NodeCase.PROJECT
            agg.aggregate.input.project.expressionsList
                .map { it.alias } shouldContainExactly
                listOf("customer", "_ttrp_a0", "_ttrp_a1")
        }
    })
