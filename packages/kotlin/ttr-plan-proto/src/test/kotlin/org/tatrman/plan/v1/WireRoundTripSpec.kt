// SPDX-License-Identifier: Apache-2.0
package org.tatrman.plan.v1

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.tatrman.dfdsl.v1.FromOp
import org.tatrman.dfdsl.v1.LimitOp
import org.tatrman.dfdsl.v1.Operation
import org.tatrman.dfdsl.v1.Pipeline

/**
 * Wire round-trip + FQCN-stability guard for the transferred proto formats
 * (contracts §2). This is the safety net for the ownership transfer: the
 * generated classes must serialize/parse losslessly AND keep their exact
 * `org.tatrman.plan.v1.*` FQCNs so kantheon consumers compile unchanged (TR-3).
 */
class WireRoundTripSpec :
    FunSpec({
        test("PlanNode (scan → filter) round-trips through protobuf") {
            val scan =
                PlanNode
                    .newBuilder()
                    .setScan(
                        ScanNode
                            .newBuilder()
                            .setObject(
                                QualifiedName
                                    .newBuilder()
                                    .setSchemaCode(SchemaCode.DB)
                                    .setNamespace("dbo")
                                    .setName("customer"),
                            ).addOutputColumns(ColumnRef.newBuilder().setName("id")),
                    ).build()
            val root =
                PlanNode
                    .newBuilder()
                    .setFilter(
                        FilterNode
                            .newBuilder()
                            .setInput(scan)
                            .setCondition(
                                Expression.newBuilder().setFunction(
                                    FunctionCall
                                        .newBuilder()
                                        .setOperation("gt")
                                        .addOperands(
                                            Expression
                                                .newBuilder()
                                                .setColumnRef(ColumnRef.newBuilder().setName("id")),
                                        ).addOperands(
                                            Expression
                                                .newBuilder()
                                                .setLiteral(Literal.newBuilder().setIntValue(10).setType("int")),
                                        ),
                                ),
                            ),
                    ).build()

            PlanNode.parseFrom(root.toByteArray()) shouldBe root
        }

        test("RowWrites INSERT, guarded UPDATE and DELETE round-trip with stable field numbers") {
            val key = Expression.newBuilder().setParameter(ParameterRef.newBuilder().setName("k")).build()

            fun column(name: String) =
                Expression.newBuilder().setColumnRef(ColumnRef.newBuilder().setName(name)).build()

            fun call(
                op: String,
                vararg args: Expression,
            ) = Expression
                .newBuilder()
                .setFunction(
                    FunctionCall.newBuilder().setOperation(op).addAllOperands(args.toList()),
                ).build()
            val placed = Expression.newBuilder().setLiteral(Literal.newBuilder().setStringValue("placed")).build()
            val target =
                QualifiedName
                    .newBuilder()
                    .setSchemaCode(
                        SchemaCode.ER,
                    ).setNamespace("entity")
                    .setName("order")
                    .build()
            val root =
                PlanNode
                    .newBuilder()
                    .setRowWrites(
                        RowWrites
                            .newBuilder()
                            .addRows(
                                RowWrite
                                    .newBuilder()
                                    .setTarget(target)
                                    .setOp(RowWriteOp.INSERT)
                                    .setIfAbsent(true)
                                    .addValues(ColumnValue.newBuilder().setName("order_number").setValue(key)),
                            ).addRows(
                                RowWrite
                                    .newBuilder()
                                    .setTarget(target)
                                    .setOp(RowWriteOp.UPDATE)
                                    .addValues(ColumnValue.newBuilder().setName("state").setValue(placed))
                                    .setWhere(
                                        call(
                                            "and",
                                            call("eq", column("order_number"), key),
                                            call("in", column("state"), placed),
                                        ),
                                    ).addReadBefore("so_state")
                                    .addPriorKeys("state"),
                            ).addRows(
                                RowWrite
                                    .newBuilder()
                                    .setTarget(target)
                                    .setOp(RowWriteOp.DELETE)
                                    .setWhere(call("eq", column("order_number"), key)),
                            ),
                    ).build()
            PlanNode.parseFrom(root.toByteArray()) shouldBe root
            PlanNode.getDescriptor().findFieldByName("row_writes").number shouldBe 18
            RowWrite.getDescriptor().fields.map { it.number } shouldBe (1..7).toList()
            ColumnValue.getDescriptor().fields.map { it.number } shouldBe listOf(1, 2)
        }

        test("PipelineContext with a parameter round-trips") {
            val ctx =
                PipelineContext
                    .newBuilder()
                    .setCorrelationId("corr-1")
                    .setUserId("u-1")
                    .addParameters(
                        ParameterBinding
                            .newBuilder()
                            .setName("limit")
                            .setType("int")
                            .setValue(Value.newBuilder().setIntValue(100)),
                    ).addAuthRoles("analyst")
                    .build()

            PipelineContext.parseFrom(ctx.toByteArray()) shouldBe ctx
        }

        test("dfdsl Pipeline with two operations round-trips") {
            val pipeline =
                Pipeline
                    .newBuilder()
                    .addOps(Operation.newBuilder().setFrom(FromOp.newBuilder().setQueryRef("q1").setAlias("a")))
                    .addOps(Operation.newBuilder().setLimit(LimitOp.newBuilder().setN(10)))
                    .build()

            Pipeline.parseFrom(pipeline.toByteArray()) shouldBe pipeline
        }

        test("FQCN stability: generated class is exactly org.tatrman.plan.v1.PlanNode") {
            PlanNode::class.qualifiedName shouldBe "org.tatrman.plan.v1.PlanNode"
        }

        test("StoreNode (write plan, MD writeback) round-trips through protobuf") {
            val store =
                PlanNode
                    .newBuilder()
                    .setStore(
                        StoreNode
                            .newBuilder()
                            .setTarget(
                                QualifiedName
                                    .newBuilder()
                                    .setSchemaCode(SchemaCode.DB)
                                    .setNamespace("dbo")
                                    .setName("f_plan"),
                            ).setInput(
                                PlanNode.newBuilder().setValues(
                                    ValuesNode
                                        .newBuilder()
                                        .addOutputColumns(ColumnRef.newBuilder().setName("amount"))
                                        .addRows(
                                            Row.newBuilder().addCells(
                                                Literal.newBuilder().setFloatValue(1.0).setType("float"),
                                            ),
                                        ),
                                ),
                            ).setMode(WriteMode.INVALIDATE)
                            .addAllGrainKeyColumns(listOf("customer_name", "month_num", "measure_code"))
                            .setMeasureColumn("amount")
                            .setMerge(MergeMode.ASSIGN)
                            .setValidColumn("is_current"),
                    ).build()

            PlanNode.parseFrom(store.toByteArray()) shouldBe store
        }

        test("StoreNode spread fields (R21 proportional) round-trip through protobuf") {
            val store =
                PlanNode
                    .newBuilder()
                    .setStore(
                        StoreNode
                            .newBuilder()
                            .setTarget(QualifiedName.newBuilder().setSchemaCode(SchemaCode.DB).setName("f_sales"))
                            .setMode(WriteMode.OVERWRITE)
                            .addAllGrainKeyColumns(listOf("customer_name"))
                            .setMeasureColumn("net")
                            .setSpread(SpreadStrategy.SPREAD_PROPORTIONAL)
                            .addAllSpreadColumns(listOf("sale_date")),
                    ).build()

            PlanNode.parseFrom(store.toByteArray()) shouldBe store
        }

        test("FQCN stability: the write vocabulary keeps its org.tatrman.plan.v1.* FQCNs") {
            StoreNode::class.qualifiedName shouldBe "org.tatrman.plan.v1.StoreNode"
            WriteMode::class.qualifiedName shouldBe "org.tatrman.plan.v1.WriteMode"
            MergeMode::class.qualifiedName shouldBe "org.tatrman.plan.v1.MergeMode"
            SpreadStrategy::class.qualifiedName shouldBe "org.tatrman.plan.v1.SpreadStrategy"
        }
    })
