// SPDX-License-Identifier: Apache-2.0
package org.tatrman.translator.joiner

import org.tatrman.plan.v1.Expression
import org.tatrman.plan.v1.JoinNode
import org.tatrman.plan.v1.JoinType
import org.tatrman.plan.v1.PlanNode
import org.tatrman.plan.v1.QualifiedName
import org.tatrman.plan.v1.SchemaCode
import org.tatrman.plan.v1.TableScanNode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.tatrman.translator.framework.FixtureModel
import org.tatrman.translator.framework.InMemoryModelHandle
import org.tatrman.translator.framework.ModelForeignKey
import org.tatrman.translator.wire.Expressions

class JoinerPhysicalSpec :
    StringSpec({

        fun colQname(
            table: String,
            column: String,
        ): QualifiedName =
            QualifiedName
                .newBuilder()
                .setSchemaCode(SchemaCode.DB)
                .setNamespace("dbo")
                .setName("$table.$column")
                .build()

        fun dbScan(qname: QualifiedName): PlanNode =
            PlanNode
                .newBuilder()
                .setTableScan(TableScanNode.newBuilder().setTable(qname))
                .build()

        fun unconditionedJoin(
            left: PlanNode,
            right: PlanNode,
        ): PlanNode =
            PlanNode
                .newBuilder()
                .setJoin(
                    JoinNode
                        .newBuilder()
                        .setLeft(left)
                        .setRight(right)
                        .setJoinType(JoinType.INNER),
                ).build()

        // FK: orders.customer_id → customers.id
        val customerToOrdersFk =
            ModelForeignKey(
                from = listOf(colQname("orders", "customer_id")),
                to = listOf(colQname("customers", "id")),
            )

        "two tables, one FK → join condition inserted" {
            val model =
                InMemoryModelHandle(
                    tables = listOf(FixtureModel.customers, FixtureModel.orders),
                    foreignKeys = listOf(customerToOrdersFk),
                )
            val input =
                unconditionedJoin(
                    dbScan(FixtureModel.customersQname),
                    dbScan(FixtureModel.ordersQname),
                )

            val result = JoinerPhysical.apply(input, model)

            result.warnings.shouldBeEmpty()
            val joined = result.plan.join
            joined.hasCondition() shouldBe true
            val cond = joined.condition
            cond.function.operation shouldBe "eq"
            val leftOperand = cond.function.operandsList[0]
            val rightOperand = cond.function.operandsList[1]
            // FK is `orders.customer_id → customers.id`. customers is on the left of the join
            // so the left operand carries the `id` column with $L source-alias.
            leftOperand.columnRef.name shouldBe "id"
            leftOperand.columnRef.sourceAlias shouldBe Expressions.LEFT_INPUT_TAG
            rightOperand.columnRef.name shouldBe "customer_id"
            rightOperand.columnRef.sourceAlias shouldBe Expressions.RIGHT_INPUT_TAG
        }

        "two tables, no FK → Cartesian preserved with NoRelation warning" {
            val model =
                InMemoryModelHandle(
                    tables = listOf(FixtureModel.customers, FixtureModel.orders),
                    foreignKeys = emptyList(),
                )
            val input =
                unconditionedJoin(
                    dbScan(FixtureModel.customersQname),
                    dbScan(FixtureModel.ordersQname),
                )

            val result = JoinerPhysical.apply(input, model)

            result.warnings shouldHaveSize 1
            result.warnings.single().shouldBeInstanceOf<JoinerWarning.NoRelation>()
            result.plan.join.hasCondition() shouldBe false
        }

        "already-conditioned join → untouched (don't double-join)" {
            val model =
                InMemoryModelHandle(
                    tables = listOf(FixtureModel.customers, FixtureModel.orders),
                    foreignKeys = listOf(customerToOrdersFk),
                )
            val preset =
                Expression
                    .newBuilder()
                    .setLiteral(
                        org.tatrman.plan.v1.Literal
                            .newBuilder()
                            .setBoolValue(true),
                    ).build()
            val input =
                PlanNode
                    .newBuilder()
                    .setJoin(
                        JoinNode
                            .newBuilder()
                            .setLeft(dbScan(FixtureModel.customersQname))
                            .setRight(dbScan(FixtureModel.ordersQname))
                            .setJoinType(JoinType.INNER)
                            .setCondition(preset),
                    ).build()

            val result = JoinerPhysical.apply(input, model)

            result.plan shouldBe input
            result.warnings.shouldBeEmpty()
        }

        // ---- alias-at-boundary ------------------------------------------------------------------
        //
        // MAP_TO_PHYSICAL rebuilds a scan's output columns as `name = DB column, alias = ER attribute`
        // whenever the model renames an attribute, and above that scan only the ALIAS exists — its
        // own contract says every upstream reference, `Join.condition` included, resolves by
        // attribute name. A condition naming the physical column therefore failed at unparse with
        // `field [d_date_sk] not found; input fields are: [sk, …]`: the first ER join to reach
        // execution on an estate whose attribute names differ from its column names.

        fun tableQname(name: String): QualifiedName =
            QualifiedName
                .newBuilder()
                .setSchemaCode(SchemaCode.DB)
                .setNamespace("dbo")
                .setName(name)
                .build()

        /** A TableScan as MAP_TO_PHYSICAL leaves it: each output column `name` aliased to [columns]' second. */
        fun aliasedScan(
            table: QualifiedName,
            vararg columns: Pair<String, String>,
        ): PlanNode =
            PlanNode
                .newBuilder()
                .setTableScan(
                    TableScanNode
                        .newBuilder()
                        .setTable(table)
                        .addAllOutputColumns(
                            columns.map { (name, alias) ->
                                org.tatrman.plan.v1.ColumnRef
                                    .newBuilder()
                                    .setName(name)
                                    .setAlias(alias)
                                    .build()
                            },
                        ),
                ).build()

        // FK: catalog_sales.cs_sold_date_sk → date_dim.d_date_sk — the attribute names are `sold_date` / `sk`.
        val soldDateFk =
            ModelForeignKey(
                from = listOf(colQname("catalog_sales", "cs_sold_date_sk")),
                to = listOf(colQname("date_dim", "d_date_sk")),
            )

        "an FK over renamed columns joins on the scans' ALIASES, not on the physical names" {
            val model = InMemoryModelHandle(tables = emptyList(), foreignKeys = listOf(soldDateFk))
            val input =
                unconditionedJoin(
                    aliasedScan(tableQname("date_dim"), "d_date_sk" to "sk", "d_moy" to "month"),
                    aliasedScan(
                        tableQname("catalog_sales"),
                        "cs_sold_date_sk" to "sold_date",
                        "cs_ext_sales_price" to "ext_sales_price",
                    ),
                )

            val result = JoinerPhysical.apply(input, model)

            result.warnings.shouldBeEmpty()
            val cond = result.plan.join.condition
            cond.function.operation shouldBe "eq"
            cond.function.operandsList[0]
                .columnRef.name shouldBe "sk"
            cond.function.operandsList[0]
                .columnRef.sourceAlias shouldBe Expressions.LEFT_INPUT_TAG
            cond.function.operandsList[1]
                .columnRef.name shouldBe "sold_date"
            cond.function.operandsList[1]
                .columnRef.sourceAlias shouldBe Expressions.RIGHT_INPUT_TAG
        }

        "a column with no alias keeps its physical name — the name = name path is unchanged per side" {
            // One side renamed, the other not: each operand follows ITS OWN scan. An empty alias is
            // how MAP_TO_PHYSICAL marks a column whose attribute name already equals the column name.
            val model = InMemoryModelHandle(tables = emptyList(), foreignKeys = listOf(soldDateFk))
            val input =
                unconditionedJoin(
                    aliasedScan(tableQname("date_dim"), "d_date_sk" to ""),
                    aliasedScan(tableQname("catalog_sales"), "cs_sold_date_sk" to "sold_date"),
                )

            val cond =
                JoinerPhysical
                    .apply(input, model)
                    .plan.join.condition

            cond.function.operandsList[0]
                .columnRef.name shouldBe "d_date_sk"
            cond.function.operandsList[1]
                .columnRef.name shouldBe "sold_date"
        }

        "idempotent — running twice produces the same tree" {
            val model =
                InMemoryModelHandle(
                    tables = listOf(FixtureModel.customers, FixtureModel.orders),
                    foreignKeys = listOf(customerToOrdersFk),
                )
            val input =
                unconditionedJoin(
                    dbScan(FixtureModel.customersQname),
                    dbScan(FixtureModel.ordersQname),
                )

            val first = JoinerPhysical.apply(input, model).plan
            val second = JoinerPhysical.apply(first, model).plan

            second shouldBe first
        }
    })
