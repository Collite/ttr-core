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

        // -- MJ-P2·S1: the wire carrier on JoinPolicy (contracts §2–§4, architecture §2.3) -------

        val dfp =
            org.tatrman.translator.framework.DfpJoinModel
                .handle()

        fun eqOperands(cond: Expression): Pair<String, String> {
            cond.function.operation shouldBe "eq"
            val l = cond.function.operandsList[0].columnRef
            val r = cond.function.operandsList[1].columnRef
            l.sourceAlias shouldBe Expressions.LEFT_INPUT_TAG
            r.sourceAlias shouldBe Expressions.RIGHT_INPUT_TAG
            return l.name to r.name
        }

        "chain ((kp ⋈ dm) ⋈ z): the second join is matched against the WHOLE left side (⚑MJ-2)" {
            val input =
                unconditionedJoin(
                    unconditionedJoin(erScan("kumulovaný_prodej"), erScan("dodací_místo")),
                    erScan("zákazník"),
                )
            val result = JoinerLogical.apply(input, dfp)
            result.warnings.shouldBeEmpty()
            eqOperands(result.plan.join.left.join.condition) shouldBe ("id_dodacího_místa" to "id_dodacího_místa")
            // dm ↔ z, not kp ↔ z (which has no relation)
            eqOperands(result.plan.join.condition) shouldBe ("id_subjektu" to "id_zákazníka")
        }

        "the hero chain on the wire (comma form of H1): three conditions, zero warnings" {
            val input =
                unconditionedJoin(
                    unconditionedJoin(
                        unconditionedJoin(erScan("kumulovaný_prodej"), erScan("dodací_místo")),
                        erScan("zákazník"),
                    ),
                    erScan("produkt"),
                )
            val result = JoinerLogical.apply(input, dfp)
            result.warnings.shouldBeEmpty()
            eqOperands(result.plan.join.condition) shouldBe ("id_produktu" to "id_produktu")
        }

        "a composite relation ANDs all pairs; a single pair stays a bare eq (⚑MJ-7, bytes as before)" {
            val composite = JoinerLogical.apply(unconditionedJoin(erScan("smlouva"), erScan("dodatek")), dfp)
            composite.warnings.shouldBeEmpty()
            val cond = composite.plan.join.condition
            cond.function.operation shouldBe "and"
            cond.function.operandsList shouldHaveSize 2
            eqOperands(cond.function.operandsList[0]) shouldBe ("číslo_smlouvy" to "číslo_smlouvy")
            eqOperands(cond.function.operandsList[1]) shouldBe ("rok" to "rok")

            val single = JoinerLogical.apply(unconditionedJoin(erScan("kumulovaný_prodej"), erScan("produkt")), dfp)
            single.plan.join.condition.function.operation shouldBe "eq"
            single.plan.join.condition.resultType shouldBe ""
        }

        "H6 on the wire: (kp ⋈ dm) ⋈ obchodní_kanál is ambiguous, sides carry the full entity lists (⚑MJ-8)" {
            val input =
                unconditionedJoin(
                    unconditionedJoin(erScan("kumulovaný_prodej"), erScan("dodací_místo")),
                    erScan("obchodní_kanál"),
                )
            val result = JoinerLogical.apply(input, dfp)
            result.plan.join.hasCondition() shouldBe false
            val warn = result.warnings.single().shouldBeInstanceOf<JoinerWarning.AmbiguousRelations>()
            warn.candidateRelations shouldHaveSize 2
            warn.repeated.shouldBeEmpty()
            warn.leftEntities.map { it.name } shouldBe listOf("kumulovaný_prodej", "dodací_místo")
            warn.rightEntities.map { it.name } shouldBe listOf("obchodní_kanál")
            warn.sideA.name shouldBe "kumulovaný_prodej"
            warn.sideB.name shouldBe "obchodní_kanál"
        }

        "H7 on the wire: no relation reports the full sides too" {
            val result = JoinerLogical.apply(unconditionedJoin(erScan("kumulovaný_prodej"), erScan("zákazník")), dfp)
            val warn = result.warnings.single().shouldBeInstanceOf<JoinerWarning.NoRelation>()
            warn.leftEntities.map { it.name } shouldBe listOf("kumulovaný_prodej")
            warn.rightEntities.map { it.name } shouldBe listOf("zákazník")
        }

        "H8 on the wire: the same entity on both sides steps aside with `repeated` (⚑MJ-6)" {
            val result = JoinerLogical.apply(unconditionedJoin(erScan("zákazník"), erScan("zákazník")), dfp)
            result.plan.join.hasCondition() shouldBe false
            val warn = result.warnings.single().shouldBeInstanceOf<JoinerWarning.AmbiguousRelations>()
            warn.repeated shouldBe setOf(erQname("zákazník"))
        }

        "key-name collision: the chosen attribute also lives on another entity of that side → KeyNameCollision (F3b)" {
            // A(k, x) → B(k, y) → C(k). (A ⋈ B) ⋈ C resolves B ↔ C on `k`, but `k` also lives on A — a
            // bare `$L.k` would resolve to A's column (the first match). Refuse rather than mis-join.
            fun e(
                name: String,
                vararg attrs: String,
            ) = ModelEntity(erQname(name), attrs.map { ModelAttribute(it, SurfaceType.INT) })
            val model =
                InMemoryModelHandle(
                    tables = emptyList(),
                    entities = listOf(e("a", "k", "x"), e("b", "k", "y"), e("c", "k")),
                    relations =
                        listOf(
                            ModelRelation(
                                erQname("a"),
                                erQname("b"),
                                listOf(attrQname("a", "k") to attrQname("b", "k")),
                            ),
                            ModelRelation(
                                erQname("b"),
                                erQname("c"),
                                listOf(attrQname("b", "k") to attrQname("c", "k")),
                            ),
                        ),
                )
            val input = unconditionedJoin(unconditionedJoin(erScan("a"), erScan("b")), erScan("c"))
            val result = JoinerLogical.apply(input, model)
            // inner a ⋈ b: the only entity on each side → fine
            result.plan.join.left.join
                .hasCondition() shouldBe true
            result.plan.join.hasCondition() shouldBe false
            val warn = result.warnings.single().shouldBeInstanceOf<JoinerWarning.KeyNameCollision>()
            warn.attribute shouldBe "k"
            warn.side shouldBe JoinerWarning.Side.LEFT
            warn.relation.fromEntity shouldBe erQname("b")
        }

        "narrowed descent: a Subquery/Aggregate/Project side → NoRelation with that side empty (review-097 R2)" {
            fun wrapAggregate(input: PlanNode): PlanNode =
                PlanNode
                    .newBuilder()
                    .setAggregate(
                        org.tatrman.plan.v1.AggregateNode
                            .newBuilder()
                            .setInput(input),
                    ).build()

            fun wrapSubquery(input: PlanNode): PlanNode =
                PlanNode
                    .newBuilder()
                    .setSubquery(
                        org.tatrman.plan.v1.SubqueryNode
                            .newBuilder()
                            .setSubquery(input),
                    ).build()

            fun wrapProject(input: PlanNode): PlanNode =
                PlanNode
                    .newBuilder()
                    .setProject(
                        org.tatrman.plan.v1.ProjectNode
                            .newBuilder()
                            .setInput(input),
                    ).build()
            for (wrap in listOf(::wrapAggregate, ::wrapSubquery, ::wrapProject)) {
                val input = unconditionedJoin(erScan("kumulovaný_prodej"), wrap(erScan("produkt")))
                val result = JoinerLogical.apply(input, dfp)
                result.plan.join.hasCondition() shouldBe false
                // Nothing visible on the right at all (a derived table) → the Cartesian product is reported,
                // the empty side carries no QualifiedName (contracts §1 row 5).
                val warn = result.warnings.single().shouldBeInstanceOf<JoinerWarning.NoRelation>()
                warn.leftEntities shouldBe listOf(erQname("kumulovaný_prodej"))
                warn.rightEntities.shouldBeEmpty()
                warn.sideA shouldBe erQname("kumulovaný_prodej")
                warn.sideB shouldBe QualifiedName.getDefaultInstance()
                JoinerMessages.text(warn) shouldBe
                    "no declared relation between entity.kumulovaný_prodej and a derived table; Cartesian product preserved"
            }
        }

        "descent through FILTER still finds the entity" {
            val filtered =
                PlanNode
                    .newBuilder()
                    .setFilter(
                        org.tatrman.plan.v1.FilterNode
                            .newBuilder()
                            .setInput(erScan("produkt")),
                    ).build()
            val result = JoinerLogical.apply(unconditionedJoin(erScan("kumulovaný_prodej"), filtered), dfp)
            result.warnings.shouldBeEmpty()
            result.plan.join.hasCondition() shouldBe true
        }
    })
