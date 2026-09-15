// SPDX-License-Identifier: Apache-2.0
package org.tatrman.translator.joiner

import org.tatrman.plan.v1.Expression
import org.tatrman.plan.v1.JoinNode
import org.tatrman.plan.v1.JoinType
import org.tatrman.plan.v1.PlanNode
import org.tatrman.plan.v1.QualifiedName
import org.tatrman.plan.v1.ScanNode
import org.tatrman.plan.v1.SchemaCode
import org.tatrman.plan.v1.TableScanNode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.tatrman.translator.framework.InMemoryModelHandle
import org.tatrman.translator.framework.ModelAttribute
import org.tatrman.translator.framework.ModelEntity
import org.tatrman.translator.framework.ModelRelation
import org.tatrman.translator.framework.SurfaceType
import org.tatrman.translator.wire.Expressions

class JoinerLogicalSpec :
    StringSpec({

        // -- Fixture helpers ---------------------------------------------------------------------

        fun erQname(name: String): QualifiedName =
            QualifiedName
                .newBuilder()
                .setSchemaCode(SchemaCode.ER)
                .setNamespace("entity")
                .setName(name)
                .build()

        fun attrQname(
            entity: String,
            attr: String,
        ): QualifiedName =
            QualifiedName
                .newBuilder()
                .setSchemaCode(SchemaCode.ER)
                .setNamespace("attribute")
                .setName("$entity.$attr")
                .build()

        fun dbQname(table: String): QualifiedName =
            QualifiedName
                .newBuilder()
                .setSchemaCode(SchemaCode.DB)
                .setNamespace("dbo")
                .setName(table)
                .build()

        val customerEntity =
            ModelEntity(
                qname = erQname("customer"),
                attributes =
                    listOf(
                        ModelAttribute("id", SurfaceType.INT, isKey = true, nullable = false),
                        ModelAttribute("name", SurfaceType.TEXT),
                    ),
            )
        val orderEntity =
            ModelEntity(
                qname = erQname("order"),
                attributes =
                    listOf(
                        ModelAttribute("id", SurfaceType.INT, isKey = true, nullable = false),
                        ModelAttribute("customer_id", SurfaceType.INT),
                    ),
            )
        val customerOrderRelation =
            ModelRelation(
                fromEntity = erQname("customer"),
                toEntity = erQname("order"),
                joinPairs = listOf(attrQname("customer", "id") to attrQname("order", "customer_id")),
            )

        // Helpers to build proto-level test trees.
        fun erScan(entity: String): PlanNode =
            PlanNode
                .newBuilder()
                .setScan(ScanNode.newBuilder().setObject(erQname(entity)))
                .build()

        fun dbScan(table: String): PlanNode =
            PlanNode
                .newBuilder()
                .setTableScan(TableScanNode.newBuilder().setTable(dbQname(table)))
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

        // -- Tests -------------------------------------------------------------------------------

        "one relation between two entities → condition inserted" {
            val model =
                InMemoryModelHandle(
                    tables = emptyList(),
                    entities = listOf(customerEntity, orderEntity),
                    relations = listOf(customerOrderRelation),
                )
            val input = unconditionedJoin(erScan("customer"), erScan("order"))

            val result = JoinerLogical.apply(input, model)

            result.warnings.shouldBeEmpty()
            val joined = result.plan.join
            joined.hasCondition() shouldBe true
            val cond = joined.condition
            cond.exprCase shouldBe Expression.ExprCase.FUNCTION
            cond.function.operation shouldBe "eq"
            val leftOperand = cond.function.operandsList[0]
            val rightOperand = cond.function.operandsList[1]
            leftOperand.columnRef.name shouldBe "id"
            leftOperand.columnRef.sourceAlias shouldBe Expressions.LEFT_INPUT_TAG
            rightOperand.columnRef.name shouldBe "customer_id"
            rightOperand.columnRef.sourceAlias shouldBe Expressions.RIGHT_INPUT_TAG
        }

        "no relation between two entities → Cartesian preserved with NoRelation warning" {
            val model =
                InMemoryModelHandle(
                    tables = emptyList(),
                    entities = listOf(customerEntity, orderEntity),
                    relations = emptyList(),
                )
            val input = unconditionedJoin(erScan("customer"), erScan("order"))

            val result = JoinerLogical.apply(input, model)

            result.warnings shouldHaveSize 1
            val warn = result.warnings.single().shouldBeInstanceOf<JoinerWarning.NoRelation>()
            warn.sideA shouldBe erQname("customer")
            warn.sideB shouldBe erQname("order")
            result.plan.join.hasCondition() shouldBe false
        }

        "multiple relations between two entities → Cartesian + AmbiguousRelations warning" {
            val rel2 =
                ModelRelation(
                    fromEntity = erQname("customer"),
                    toEntity = erQname("order"),
                    joinPairs = listOf(attrQname("customer", "id") to attrQname("order", "id")),
                )
            val model =
                InMemoryModelHandle(
                    tables = emptyList(),
                    entities = listOf(customerEntity, orderEntity),
                    relations = listOf(customerOrderRelation, rel2),
                )
            val input = unconditionedJoin(erScan("customer"), erScan("order"))

            val result = JoinerLogical.apply(input, model)

            result.warnings shouldHaveSize 1
            val warn = result.warnings.single().shouldBeInstanceOf<JoinerWarning.AmbiguousRelations>()
            warn.candidateRelations shouldHaveSize 2
            result.plan.join.hasCondition() shouldBe false
        }

        "a relation with NO join pairs → left unconditioned with a warning, never a crash" {
            // A relation bound only to its FK (`binding: { fk: … }`, no `join:` list) carries no
            // attribute pairs, and `buildEqualityCondition` called `.first()` on them. Unconditioned is
            // the correct outcome here, not a Cartesian product: the physical Joiner fills the join from
            // that same FK once MAP_TO_PHYSICAL has run. The warning records why this Joiner stepped aside.
            val pairless = customerOrderRelation.copy(joinPairs = emptyList())
            val model =
                InMemoryModelHandle(
                    tables = emptyList(),
                    entities = listOf(customerEntity, orderEntity),
                    relations = listOf(pairless),
                )
            val input = unconditionedJoin(erScan("customer"), erScan("order"))

            val result = JoinerLogical.apply(input, model)

            result.plan.join.hasCondition() shouldBe false
            result.warnings shouldHaveSize 1
            result.warnings.single()::class.simpleName shouldBe "RelationWithoutJoinPairs"
        }

        "entity ↔ table → untouched (mixed-schema preservation)" {
            val model =
                InMemoryModelHandle(
                    tables = emptyList(),
                    entities = listOf(customerEntity),
                    relations = listOf(customerOrderRelation),
                )
            val input = unconditionedJoin(erScan("customer"), dbScan("orders"))

            val result = JoinerLogical.apply(input, model)

            result.warnings.shouldBeEmpty()
            result.plan.join.hasCondition() shouldBe false
        }

        "already-conditioned join → untouched (idempotent)" {
            val model =
                InMemoryModelHandle(
                    tables = emptyList(),
                    entities = listOf(customerEntity, orderEntity),
                    relations = listOf(customerOrderRelation),
                )
            val existingCondition =
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
                            .setLeft(erScan("customer"))
                            .setRight(erScan("order"))
                            .setJoinType(JoinType.INNER)
                            .setCondition(existingCondition),
                    ).build()

            val result = JoinerLogical.apply(input, model)

            result.warnings.shouldBeEmpty()
            result.plan shouldBe input
        }

        "idempotent — running twice on the same input produces the same tree" {
            val model =
                InMemoryModelHandle(
                    tables = emptyList(),
                    entities = listOf(customerEntity, orderEntity),
                    relations = listOf(customerOrderRelation),
                )
            val input = unconditionedJoin(erScan("customer"), erScan("order"))

            val first = JoinerLogical.apply(input, model).plan
            val second = JoinerLogical.apply(first, model).plan

            second shouldBe first
        }
    })
