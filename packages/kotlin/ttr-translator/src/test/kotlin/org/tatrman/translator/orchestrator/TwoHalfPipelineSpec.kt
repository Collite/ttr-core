// SPDX-License-Identifier: Apache-2.0
package org.tatrman.translator.orchestrator

import org.tatrman.plan.v1.PlanNode
import org.tatrman.plan.v1.QualifiedName
import org.tatrman.plan.v1.SchemaCode
import org.tatrman.translate.v1.Language
import org.tatrman.translate.v1.SqlDialect as SqlDialectProto
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.tatrman.translator.framework.EntityMapping
import org.tatrman.translator.framework.FixtureModel
import org.tatrman.translator.framework.InMemoryModelHandle
import org.tatrman.translator.framework.ModelAttribute
import org.tatrman.translator.framework.ModelEntity
import org.tatrman.translator.framework.SurfaceType

/**
 * Phase 08 B6 / DF-T08 — orchestrator-level integration of the C/D/E stages.
 *
 *   - `target=ER` keeps `Scan(ER, ...)` nodes in the output plan (no MAP_TO_PHYSICAL).
 *   - `target=DB` rewrites them to `TableScan(DB, ...)`.
 *   - REL_NODE re-entry from a previous `target=ER` plan with `target=DB` finishes the pipeline.
 *   - The two-half output equals the single-call `target=DB` output (modulo encoder noise).
 *   - `unparseFromRelNode` rejects pre-physical plans with `unparse_rejects_pre_physical`.
 */
class TwoHalfPipelineSpec :
    StringSpec({

        // -- Fixture: customer entity → a customer table ------------------------------------------

        val customerEntityQname =
            QualifiedName
                .newBuilder()
                .setSchemaCode(SchemaCode.ER)
                .setNamespace("entity")
                .setName("customer")
                .build()

        // The fixture's `customers` table happens to carry the same column names as our entity
        // (id, name) — exploit the v1 attribute-name = column-name assumption.
        val customerEntity =
            ModelEntity(
                qname = customerEntityQname,
                attributes =
                    listOf(
                        ModelAttribute("id", SurfaceType.INT, isKey = true, nullable = false),
                        ModelAttribute("name", SurfaceType.TEXT),
                    ),
            )

        val model =
            InMemoryModelHandle(
                tables = listOf(FixtureModel.customers),
                entities = listOf(customerEntity),
                entityMappings =
                    mapOf(
                        customerEntityQname to EntityMapping.ToTable(FixtureModel.customersQname),
                    ),
            )

        val translator = Translator(model)

        // -- Helpers ---------------------------------------------------------------------------

        fun containsErScan(plan: PlanNode): Boolean =
            when (plan.nodeCase) {
                PlanNode.NodeCase.SCAN -> plan.scan.getObject().schemaCode == SchemaCode.ER
                PlanNode.NodeCase.PROJECT -> containsErScan(plan.project.input)
                PlanNode.NodeCase.FILTER -> containsErScan(plan.filter.input)
                PlanNode.NodeCase.JOIN -> containsErScan(plan.join.left) || containsErScan(plan.join.right)
                PlanNode.NodeCase.AGGREGATE -> containsErScan(plan.aggregate.input)
                PlanNode.NodeCase.SORT -> containsErScan(plan.sort.input)
                PlanNode.NodeCase.LIMIT_OFFSET -> containsErScan(plan.limitOffset.input)
                PlanNode.NodeCase.SUBQUERY -> containsErScan(plan.subquery.subquery)
                PlanNode.NodeCase.UNION -> plan.union.inputsList.any { containsErScan(it) }
                else -> false
            }

        fun containsTableScan(
            plan: PlanNode,
            schema: SchemaCode,
        ): Boolean =
            when (plan.nodeCase) {
                PlanNode.NodeCase.TABLE_SCAN -> plan.tableScan.table.schemaCode == schema
                PlanNode.NodeCase.PROJECT -> containsTableScan(plan.project.input, schema)
                PlanNode.NodeCase.FILTER -> containsTableScan(plan.filter.input, schema)
                PlanNode.NodeCase.JOIN ->
                    containsTableScan(plan.join.left, schema) || containsTableScan(plan.join.right, schema)
                PlanNode.NodeCase.AGGREGATE -> containsTableScan(plan.aggregate.input, schema)
                PlanNode.NodeCase.SORT -> containsTableScan(plan.sort.input, schema)
                PlanNode.NodeCase.LIMIT_OFFSET -> containsTableScan(plan.limitOffset.input, schema)
                PlanNode.NodeCase.SUBQUERY -> containsTableScan(plan.subquery.subquery, schema)
                PlanNode.NodeCase.UNION -> plan.union.inputsList.any { containsTableScan(it, schema) }
                else -> false
            }

        // -- Tests -----------------------------------------------------------------------------

        "SQL → target=ER → Scan(ER, ...) leaves intact" {
            val r =
                translator.parseToRelNode(
                    source = "SELECT id, name FROM er.entity.customer",
                    sourceLanguage = Language.SQL,
                    targetSchema = SchemaCode.ER,
                )
            r.shouldBeInstanceOf<ParseResult.Success>()
            containsErScan(r.plan) shouldBe true
            containsTableScan(r.plan, SchemaCode.DB) shouldBe false
        }

        "SQL → target=DB → entity scans rewritten to TableScan(DB, ...)" {
            val r =
                translator.parseToRelNode(
                    source = "SELECT id, name FROM er.entity.customer",
                    sourceLanguage = Language.SQL,
                    targetSchema = SchemaCode.DB,
                )
            r.shouldBeInstanceOf<ParseResult.Success>()
            containsErScan(r.plan) shouldBe false
            containsTableScan(r.plan, SchemaCode.DB) shouldBe true
        }

        "REL_NODE source from target=ER → target=DB finishes the pipeline" {
            // Call 1: produce an ER tree.
            val call1 =
                translator.parseToRelNode(
                    source = "SELECT id, name FROM er.entity.customer",
                    sourceLanguage = Language.SQL,
                    targetSchema = SchemaCode.ER,
                )
            call1.shouldBeInstanceOf<ParseResult.Success>()

            // Call 2: round-trip through the REL_NODE bytes path with target=DB.
            val relNodeText = String(call1.plan.toByteArray(), Charsets.ISO_8859_1)
            val call2 =
                translator.parseToRelNode(
                    source = relNodeText,
                    sourceLanguage = Language.REL_NODE,
                    targetSchema = SchemaCode.DB,
                )
            call2.shouldBeInstanceOf<ParseResult.Success>()
            containsErScan(call2.plan) shouldBe false
            containsTableScan(call2.plan, SchemaCode.DB) shouldBe true
        }

        "two-half output equals single-call target=DB" {
            val singleCall =
                translator.parseToRelNode(
                    source = "SELECT id, name FROM er.entity.customer",
                    sourceLanguage = Language.SQL,
                    targetSchema = SchemaCode.DB,
                ) as ParseResult.Success
            val erIntermediate =
                translator.parseToRelNode(
                    source = "SELECT id, name FROM er.entity.customer",
                    sourceLanguage = Language.SQL,
                    targetSchema = SchemaCode.ER,
                ) as ParseResult.Success
            val twoHalf =
                translator.parseToRelNode(
                    source = String(erIntermediate.plan.toByteArray(), Charsets.ISO_8859_1),
                    sourceLanguage = Language.REL_NODE,
                    targetSchema = SchemaCode.DB,
                ) as ParseResult.Success
            twoHalf.plan shouldBe singleCall.plan
        }

        "unparse rejects a pre-physical plan with unparse_rejects_pre_physical" {
            val erPlan =
                (
                    translator.parseToRelNode(
                        source = "SELECT id, name FROM er.entity.customer",
                        sourceLanguage = Language.SQL,
                        targetSchema = SchemaCode.ER,
                    ) as ParseResult.Success
                ).plan
            val unparse =
                translator.unparseFromRelNode(
                    plan = erPlan,
                    targetLanguage = Language.SQL,
                    targetDialect = SqlDialectProto.MSSQL,
                )
            unparse.shouldBeInstanceOf<UnparseResult.Failure>()
            unparse.code shouldBe "unparse_rejects_pre_physical"
        }

        "unmapped entity → entity_unmapped failure on target=DB" {
            val unmappedQname =
                QualifiedName
                    .newBuilder()
                    .setSchemaCode(SchemaCode.ER)
                    .setNamespace("entity")
                    .setName("ghost")
                    .build()
            val ghostEntity =
                ModelEntity(
                    qname = unmappedQname,
                    attributes = listOf(ModelAttribute("id", SurfaceType.INT)),
                )
            val modelNoMapping =
                InMemoryModelHandle(
                    tables = listOf(FixtureModel.customers),
                    entities = listOf(ghostEntity),
                    entityMappings = emptyMap(),
                )
            val xlator = Translator(modelNoMapping)

            val r =
                xlator.parseToRelNode(
                    source = "SELECT id FROM er.entity.ghost",
                    sourceLanguage = Language.SQL,
                    targetSchema = SchemaCode.DB,
                )

            r.shouldBeInstanceOf<ParseResult.Failure>()
            r.code shouldBe "entity_unmapped"
        }

        "target=ER skips MAP_TO_PHYSICAL even for unmapped entities" {
            // The same unmapped entity passes through cleanly when target=ER (no physical stages).
            val unmappedQname =
                QualifiedName
                    .newBuilder()
                    .setSchemaCode(SchemaCode.ER)
                    .setNamespace("entity")
                    .setName("ghost")
                    .build()
            val ghostEntity =
                ModelEntity(
                    qname = unmappedQname,
                    attributes = listOf(ModelAttribute("id", SurfaceType.INT)),
                )
            val modelNoMapping =
                InMemoryModelHandle(
                    tables = listOf(FixtureModel.customers),
                    entities = listOf(ghostEntity),
                    entityMappings = emptyMap(),
                )
            val xlator = Translator(modelNoMapping)

            val r =
                xlator.parseToRelNode(
                    source = "SELECT id FROM er.entity.ghost",
                    sourceLanguage = Language.SQL,
                    targetSchema = SchemaCode.ER,
                )

            r.shouldBeInstanceOf<ParseResult.Success>()
            containsErScan(r.plan) shouldBe true
        }

        "unqualified ER SQL with no source_schema auto-detects the er catalog" {
            // Regression (GH schema-detect): a caller — e.g. golem's free-SQL gate — builds
            // ER-level SQL from entity names but leaves source_schema UNSPECIFIED. Previously the
            // catalog defaulted to db, so the unqualified `customer` resolved against db.dbo and
            // failed with "Object 'customer' not found — did you mean: customer?" (the suggester
            // finding the identical name over in the er schema). The translator now derives the
            // catalog from the query's own identifiers when source_schema is unset.
            val r =
                translator.parseToRelNode(
                    source = "SELECT id, name FROM customer",
                    sourceLanguage = Language.SQL,
                )
            r.shouldBeInstanceOf<ParseResult.Success>()
            // target defaults to DB ⇒ the auto-detected ER scan is mapped to its physical table.
            containsErScan(r.plan) shouldBe false
            containsTableScan(r.plan, SchemaCode.DB) shouldBe true
        }

        "pure-DB query is unaffected — only physical joiner has anything to do" {
            val r =
                translator.parseToRelNode(
                    source = "SELECT id, name FROM customers",
                    sourceLanguage = Language.SQL,
                    targetSchema = SchemaCode.DB,
                )
            r.shouldBeInstanceOf<ParseResult.Success>()
            containsErScan(r.plan) shouldBe false
            containsTableScan(r.plan, SchemaCode.DB) shouldBe true
            // Plan top should still carry the SELECT's Project.
            r.plan.hasProject() shouldNotBe false
        }

        // -- UNION branches (a set operation's inputs are children like any other) -----------------

        val purchaseEntityQname =
            QualifiedName
                .newBuilder()
                .setSchemaCode(SchemaCode.ER)
                .setNamespace("entity")
                .setName("purchase")
                .build()
        val unionModel =
            InMemoryModelHandle(
                tables = listOf(FixtureModel.customers, FixtureModel.orders),
                entities =
                    listOf(
                        customerEntity,
                        ModelEntity(
                            qname = purchaseEntityQname,
                            attributes =
                                listOf(
                                    ModelAttribute("id", SurfaceType.INT, isKey = true, nullable = false),
                                    ModelAttribute("customer_id", SurfaceType.INT, nullable = false),
                                ),
                        ),
                    ),
                entityMappings =
                    mapOf(
                        customerEntityQname to EntityMapping.ToTable(FixtureModel.customersQname),
                        purchaseEntityQname to EntityMapping.ToTable(FixtureModel.ordersQname),
                    ),
            )
        // The shape an LLM writes to compare two sources: one derived table over a UNION ALL.
        val unionSql =
            """
            SELECT src, COUNT(*) AS n FROM (
              SELECT 'customer' AS src, id FROM er.entity.customer
              UNION ALL
              SELECT 'purchase' AS src, customer_id AS id FROM er.entity.purchase
            ) AS t GROUP BY src
            """.trimIndent()

        "UNION ALL in a derived table → every branch's entity scan mapped to TableScan(DB, ...)" {
            val r =
                Translator(unionModel).parseToRelNode(
                    source = unionSql,
                    sourceLanguage = Language.SQL,
                    targetSchema = SchemaCode.DB,
                )
            r.shouldBeInstanceOf<ParseResult.Success>()
            containsErScan(r.plan) shouldBe false
            containsTableScan(r.plan, SchemaCode.DB) shouldBe true
        }

        "UNION ALL in a derived table → the engine SQL names the tables, never er.entity.*" {
            // Regression: the UNION fell through the plan walker as a leaf, so MAP_TO_PHYSICAL never
            // reached its branches and Postgres got `"er"."entity"."<name>"` ("cross-database
            // references are not implemented").
            val r =
                Translator(unionModel).translate(
                    source = unionSql,
                    sourceLanguage = Language.SQL,
                    targetLanguage = Language.SQL,
                    targetSchema = SchemaCode.DB,
                    targetDialect = SqlDialectProto.POSTGRESQL,
                )
            r.shouldBeInstanceOf<TranslateResult.Success>()
            r.output shouldNotContain "entity"
            r.output shouldContain "customers"
            r.output shouldContain "orders"
        }

        "unparse rejects a pre-physical UNION (an entity scan in any branch)" {
            val erPlan =
                (
                    Translator(unionModel).parseToRelNode(
                        source = unionSql,
                        sourceLanguage = Language.SQL,
                        targetSchema = SchemaCode.ER,
                    ) as ParseResult.Success
                ).plan
            val unparse =
                Translator(unionModel).unparseFromRelNode(
                    plan = erPlan,
                    targetLanguage = Language.SQL,
                    targetDialect = SqlDialectProto.POSTGRESQL,
                )
            unparse.shouldBeInstanceOf<UnparseResult.Failure>()
            unparse.code shouldBe "unparse_rejects_pre_physical"
        }
    })
