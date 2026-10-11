// SPDX-License-Identifier: Apache-2.0
package org.tatrman.translator.schema

import org.tatrman.plan.v1.ColumnRef
import org.tatrman.plan.v1.Expression
import org.tatrman.plan.v1.FunctionCall
import org.tatrman.plan.v1.Literal
import org.tatrman.plan.v1.PlanNode
import org.tatrman.plan.v1.ProjectNode
import org.tatrman.plan.v1.QualifiedName
import org.tatrman.plan.v1.ScanNode
import org.tatrman.plan.v1.SchemaCode
import org.tatrman.plan.v1.TableScanNode
import org.tatrman.plan.v1.UnionNode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.tatrman.translator.framework.EntityMapping
import org.tatrman.translator.framework.FixtureModel
import org.tatrman.translator.framework.InMemoryModelHandle
import org.tatrman.translator.framework.ModelAttribute
import org.tatrman.translator.framework.ModelEntity
import org.tatrman.translator.framework.SurfaceType

class MapToPhysicalSpec :
    StringSpec({

        fun erQname(name: String): QualifiedName =
            QualifiedName
                .newBuilder()
                .setSchemaCode(SchemaCode.ER)
                .setNamespace("entity")
                .setName(name)
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

        fun erScan(
            entity: String,
            outputCols: List<String> = listOf("id", "name"),
        ): PlanNode =
            PlanNode
                .newBuilder()
                .setScan(
                    ScanNode
                        .newBuilder()
                        .setObject(erQname(entity))
                        .apply {
                            outputCols.forEach { addOutputColumns(ColumnRef.newBuilder().setName(it)) }
                        },
                ).build()

        "entity → table, no WHERE → Scan rewritten to TableScan, no extra nodes" {
            val model =
                InMemoryModelHandle(
                    tables = listOf(FixtureModel.customers),
                    entities = listOf(customerEntity),
                    entityMappings =
                        mapOf(
                            erQname("customer") to EntityMapping.ToTable(FixtureModel.customersQname),
                        ),
                )
            val input = erScan("customer")

            val result = MapToPhysical.apply(input, model)

            result.shouldBeInstanceOf<MapToPhysicalResult.Success>()
            result.plan.nodeCase shouldBe PlanNode.NodeCase.TABLE_SCAN
            result.plan.tableScan.table shouldBe FixtureModel.customersQname
        }

        "all-physical plan (TableScan, no ER Scan) → identity, returned unchanged" {
            // Guards the DB single-pass guarantee: MapToPhysical only rewrites ER ScanNodes, so an
            // already-physical plan must pass through byte-for-byte (no entity→table mapping needed).
            val model =
                InMemoryModelHandle(
                    tables = listOf(FixtureModel.customers),
                    entities = listOf(customerEntity),
                )
            val physicalInput =
                PlanNode
                    .newBuilder()
                    .setTableScan(
                        TableScanNode
                            .newBuilder()
                            .setTable(FixtureModel.customersQname)
                            .addOutputColumns(ColumnRef.newBuilder().setName("id"))
                            .addOutputColumns(ColumnRef.newBuilder().setName("name")),
                    ).build()

            val result = MapToPhysical.apply(physicalInput, model)

            result.shouldBeInstanceOf<MapToPhysicalResult.Success>()
            result.plan shouldBe physicalInput
        }

        "entity → table with WHERE → Filter wraps TableScan with the mapping's condition" {
            val whereFilter =
                Expression
                    .newBuilder()
                    .setFunction(
                        FunctionCall
                            .newBuilder()
                            .setOperation("eq")
                            .addOperands(
                                Expression
                                    .newBuilder()
                                    .setColumnRef(ColumnRef.newBuilder().setName("active")),
                            ).addOperands(
                                Expression
                                    .newBuilder()
                                    .setLiteral(Literal.newBuilder().setIntValue(1)),
                            ),
                    ).build()
            val model =
                InMemoryModelHandle(
                    tables = listOf(FixtureModel.customers),
                    entities = listOf(customerEntity),
                    entityMappings =
                        mapOf(
                            erQname("customer") to
                                EntityMapping.ToTable(FixtureModel.customersQname, whereFilter),
                        ),
                )
            val input = erScan("customer")

            val result = MapToPhysical.apply(input, model)

            result.shouldBeInstanceOf<MapToPhysicalResult.Success>()
            result.plan.nodeCase shouldBe PlanNode.NodeCase.FILTER
            result.plan.filter.condition shouldBe whereFilter
            result.plan.filter.input.nodeCase shouldBe PlanNode.NodeCase.TABLE_SCAN
            result.plan.filter.input.tableScan.table shouldBe FixtureModel.customersQname
        }

        "entity nested inside a Project / Filter tree → rewritten in place" {
            val model =
                InMemoryModelHandle(
                    tables = listOf(FixtureModel.customers),
                    entities = listOf(customerEntity),
                    entityMappings =
                        mapOf(
                            erQname("customer") to EntityMapping.ToTable(FixtureModel.customersQname),
                        ),
                )
            val input =
                PlanNode
                    .newBuilder()
                    .setProject(
                        ProjectNode
                            .newBuilder()
                            .setInput(erScan("customer")),
                    ).build()

            val result = MapToPhysical.apply(input, model)

            result.shouldBeInstanceOf<MapToPhysicalResult.Success>()
            // Project preserved on top; entity Scan inside is now a TableScan.
            result.plan.nodeCase shouldBe PlanNode.NodeCase.PROJECT
            result.plan.project.input.nodeCase shouldBe PlanNode.NodeCase.TABLE_SCAN
        }

        // ---- entity → query (ToQuery) substitution ------------------------------------------

        // A DB table whose physical column names differ from the ER attribute names, so the
        // attribute → backing-column projection is actually exercised.
        val custTableQn =
            QualifiedName
                .newBuilder()
                .setSchemaCode(SchemaCode.DB)
                .setNamespace("dbo")
                .setName("CUST_T")
                .build()
        val custTable =
            org.tatrman.translator.framework.ModelTable(
                qname = custTableQn,
                columns =
                    listOf(
                        org.tatrman.translator.framework
                            .ModelColumn("CUST_ID", SurfaceType.INT, nullable = false),
                        org.tatrman.translator.framework
                            .ModelColumn("CUST_NAME", SurfaceType.TEXT),
                        org.tatrman.translator.framework
                            .ModelColumn("ACTIVE", SurfaceType.INT),
                    ),
            )

        fun colRef(name: String): ColumnRef = ColumnRef.newBuilder().setName(name).build()

        fun colExpr(name: String): Expression = Expression.newBuilder().setColumnRef(colRef(name)).build()

        // Physical body of a synthetic filter query: SELECT * FROM CUST_T WHERE ACTIVE = 1, with
        // the `*` expanded to explicit columns named by the DB columns (matches what the metadata
        // service stores as the query's canonical_form).
        fun filterBody(): PlanNode {
            val tableScan =
                PlanNode
                    .newBuilder()
                    .setTableScan(
                        TableScanNode
                            .newBuilder()
                            .setTable(custTableQn)
                            .addOutputColumns(colRef("CUST_ID"))
                            .addOutputColumns(colRef("CUST_NAME"))
                            .addOutputColumns(colRef("ACTIVE")),
                    ).build()
            val one = Expression.newBuilder().setLiteral(Literal.newBuilder().setIntValue(1)).build()
            val condition =
                Expression
                    .newBuilder()
                    .setFunction(
                        FunctionCall
                            .newBuilder()
                            .setOperation("eq")
                            .addOperands(colExpr("ACTIVE"))
                            .addOperands(one),
                    ).build()
            val filter =
                PlanNode
                    .newBuilder()
                    .setFilter(
                        org.tatrman.plan.v1.FilterNode
                            .newBuilder()
                            .setInput(tableScan)
                            .setCondition(condition),
                    ).build()
            val proj = ProjectNode.newBuilder().setInput(filter)
            listOf("CUST_ID", "CUST_NAME", "ACTIVE").forEach { col ->
                proj.addExpressions(
                    org.tatrman.plan.v1.NamedExpression
                        .newBuilder()
                        .setExpression(colExpr(col))
                        .setAlias(col),
                )
            }
            return PlanNode.newBuilder().setProject(proj).build()
        }

        // Saved queries are keyed schema-less in the snapshot (the `query` namespace, UNSPECIFIED
        // schema). The er2db mapping references it with a `db.` schema token — so resolution must
        // tolerate the schema-code mismatch.
        val filterQueryStoredQn =
            QualifiedName
                .newBuilder()
                .setNamespace("query")
                .setName("customer__filter")
                .build()
        val filterQueryRefQn =
            QualifiedName
                .newBuilder()
                .setSchemaCode(SchemaCode.DB)
                .setNamespace("query")
                .setName("customer__filter")
                .build()

        fun queryBackedModel(
            bodies: Map<QualifiedName, org.tatrman.translator.framework.SavedQueryBody> =
                mapOf(
                    filterQueryStoredQn to
                        org.tatrman.translator.framework
                            .SavedQueryBody(filterBody(), emptyList(), emptyList()),
                ),
            ref: QualifiedName = filterQueryRefQn,
        ): InMemoryModelHandle =
            InMemoryModelHandle(
                tables = listOf(custTable),
                entities = listOf(customerEntity),
                entityMappings = mapOf(erQname("customer") to EntityMapping.ToQuery(ref)),
                savedQueries =
                    listOf(
                        org.tatrman.translator.framework
                            .ModelSavedQuery(filterQueryStoredQn),
                    ),
                savedQueryBodies = bodies,
                attributeRenames = mapOf(erQname("customer") to mapOf("id" to "CUST_ID", "name" to "CUST_NAME")),
            )

        "entity → query → backing body substituted + projected onto attributes (alias-at-boundary)" {
            val result = MapToPhysical.apply(erScan("customer"), queryBackedModel())

            result.shouldBeInstanceOf<MapToPhysicalResult.Success>()
            val plan = result.plan
            // Top is the boundary Project: each declared ER attribute selects its backing column,
            // aliased back to the attribute name so the upstream tree keeps resolving by attr name.
            plan.nodeCase shouldBe PlanNode.NodeCase.PROJECT
            plan.project.expressionsList.map { it.alias } shouldBe listOf("id", "name")
            plan.project.expressionsList.map { it.expression.columnRef.name } shouldBe listOf("CUST_ID", "CUST_NAME")
            // The backing query's physical body is spliced underneath unchanged (no ER scan left).
            plan.project.input shouldBe filterBody()
        }

        "entity → query → resolves even when the mapping ref carries no schema token" {
            // Exact-qname path (ref == stored, both schema-less).
            val result = MapToPhysical.apply(erScan("customer"), queryBackedModel(ref = filterQueryStoredQn))
            result.shouldBeInstanceOf<MapToPhysicalResult.Success>()
            result.plan.nodeCase shouldBe PlanNode.NodeCase.PROJECT
        }

        "entity → query nested inside a Project → substituted in place" {
            val input =
                PlanNode
                    .newBuilder()
                    .setProject(ProjectNode.newBuilder().setInput(erScan("customer")))
                    .build()
            val result = MapToPhysical.apply(input, queryBackedModel())
            result.shouldBeInstanceOf<MapToPhysicalResult.Success>()
            // Outer Project preserved; its input is now the boundary Project over the spliced body.
            result.plan.nodeCase shouldBe PlanNode.NodeCase.PROJECT
            result.plan.project.input.nodeCase shouldBe PlanNode.NodeCase.PROJECT
        }

        "entity → query, backing query not compiled → entity_query_mapping_unresolved" {
            val result = MapToPhysical.apply(erScan("customer"), queryBackedModel(bodies = emptyMap()))
            result.shouldBeInstanceOf<MapToPhysicalResult.Failure>()
            result.code shouldBe "entity_query_mapping_unresolved"
            result.message shouldContain "er.entity.customer"
            result.message shouldContain "customer__filter"
        }

        "entity → query whose body re-scans the same entity → entity_query_mapping_cycle" {
            // Backing query body is itself a Scan(ER, customer) → would recurse forever.
            val cyclicBodies =
                mapOf(
                    filterQueryStoredQn to
                        org.tatrman.translator.framework
                            .SavedQueryBody(erScan("customer"), emptyList(), emptyList()),
                )
            val result = MapToPhysical.apply(erScan("customer"), queryBackedModel(bodies = cyclicBodies))
            result.shouldBeInstanceOf<MapToPhysicalResult.Failure>()
            result.code shouldBe "entity_query_mapping_cycle"
            result.message shouldContain "er.entity.customer"
        }

        "unmapped entity → entity_unmapped failure" {
            val model =
                InMemoryModelHandle(
                    tables = emptyList(),
                    entities = listOf(customerEntity),
                    entityMappings = emptyMap(),
                )
            val input = erScan("customer")

            val result = MapToPhysical.apply(input, model)

            result.shouldBeInstanceOf<MapToPhysicalResult.Failure>()
            result.code shouldBe "entity_unmapped"
            result.message shouldContain "er.entity.customer"
        }

        "attribute renames → TableScan output_columns: name=DB col, alias=ER attr, type from DB column" {
            // v1.x — alias-at-boundary: TableScan owns the rename, upstream tree untouched.
            // Use a custom DB table whose columns are actually named like the renames so the
            // type lookup against ModelHandle.columns hits.
            val tableQn =
                QualifiedName
                    .newBuilder()
                    .setSchemaCode(SchemaCode.DB)
                    .setNamespace("dbo")
                    .setName("CUSTOMER_T")
                    .build()
            val tableForRenames =
                org.tatrman.translator.framework.ModelTable(
                    qname = tableQn,
                    columns =
                        listOf(
                            org.tatrman.translator.framework
                                .ModelColumn("ID_COL", SurfaceType.INT, nullable = false),
                            org.tatrman.translator.framework
                                .ModelColumn("NAME_COL", SurfaceType.TEXT),
                        ),
                )
            val renames = mapOf("id" to "ID_COL", "name" to "NAME_COL")
            val model =
                InMemoryModelHandle(
                    tables = listOf(tableForRenames),
                    entities = listOf(customerEntity),
                    entityMappings =
                        mapOf(
                            erQname("customer") to EntityMapping.ToTable(tableQn),
                        ),
                    attributeRenames = mapOf(erQname("customer") to renames),
                )
            // Project(name as alias_name) Filter(id = 1) Scan(customer, [id, name])
            val filterCondition =
                Expression
                    .newBuilder()
                    .setFunction(
                        FunctionCall
                            .newBuilder()
                            .setOperation("eq")
                            .addOperands(
                                Expression
                                    .newBuilder()
                                    .setColumnRef(ColumnRef.newBuilder().setName("id")),
                            ).addOperands(
                                Expression
                                    .newBuilder()
                                    .setLiteral(Literal.newBuilder().setIntValue(1)),
                            ),
                    ).build()
            val projectExpr =
                org.tatrman.plan.v1.NamedExpression
                    .newBuilder()
                    .setExpression(
                        Expression
                            .newBuilder()
                            .setColumnRef(ColumnRef.newBuilder().setName("name"))
                            .build(),
                    ).setAlias("alias_name")
                    .build()
            val input =
                PlanNode
                    .newBuilder()
                    .setProject(
                        ProjectNode
                            .newBuilder()
                            .setInput(
                                PlanNode
                                    .newBuilder()
                                    .setFilter(
                                        org.tatrman.plan.v1.FilterNode
                                            .newBuilder()
                                            .setInput(erScan("customer"))
                                            .setCondition(filterCondition),
                                    ).build(),
                            ).addExpressions(projectExpr),
                    ).build()

            val result = MapToPhysical.apply(input, model)

            result.shouldBeInstanceOf<MapToPhysicalResult.Success>()
            val plan = result.plan
            // Upstream tree untouched — Project / Filter ColumnRefs still in ER vocabulary.
            plan.project.expressionsList
                .single()
                .expression.columnRef.name shouldBe "name"
            plan.project.expressionsList
                .single()
                .alias shouldBe "alias_name"
            plan.project.input.filter.condition.function
                .operandsList[0]
                .columnRef.name shouldBe "id"
            // TableScan output_columns carry the rename: name=DB col, alias=ER attr, type from DB.
            val tableScan = plan.project.input.filter.input
            tableScan.nodeCase shouldBe PlanNode.NodeCase.TABLE_SCAN
            val byAlias = tableScan.tableScan.outputColumnsList.associateBy { it.alias }
            (byAlias["id"]?.name) shouldBe "ID_COL"
            (byAlias["id"]?.type) shouldBe "int" // from FixtureModel.customers.id (BIGINT → INT)
            (byAlias["name"]?.name) shouldBe "NAME_COL"
            (byAlias["name"]?.type) shouldBe "text"
        }

        "no renames declared → TableScan.output_columns keep ER names, alias empty, type from DB column" {
            // Backward-compat: when attributeColumnRenames is empty, output_columns still pick
            // up the real DB-column type (so downstream consumers see a typed plan), but no
            // alias is added and `name` stays as the ER attribute (v1.0 "name = name" assumption).
            val model =
                InMemoryModelHandle(
                    tables = listOf(FixtureModel.customers),
                    entities = listOf(customerEntity),
                    entityMappings =
                        mapOf(
                            erQname("customer") to EntityMapping.ToTable(FixtureModel.customersQname),
                        ),
                )
            val input = erScan("customer")
            val result = MapToPhysical.apply(input, model)

            result.shouldBeInstanceOf<MapToPhysicalResult.Success>()
            result.plan.tableScan.outputColumnsList
                .map { it.name } shouldBe listOf("id", "name")
            result.plan.tableScan.outputColumnsList
                .all { it.alias.isEmpty() } shouldBe true
        }

        "idempotent — running twice on the same input produces the same tree" {
            val model =
                InMemoryModelHandle(
                    tables = listOf(FixtureModel.customers),
                    entities = listOf(customerEntity),
                    entityMappings =
                        mapOf(
                            erQname("customer") to EntityMapping.ToTable(FixtureModel.customersQname),
                        ),
                )
            val input = erScan("customer")

            val first = (MapToPhysical.apply(input, model) as MapToPhysicalResult.Success).plan
            val second = (MapToPhysical.apply(first, model) as MapToPhysicalResult.Success).plan

            second shouldBe first
        }

        "entity scans in every UNION branch → each rewritten to TableScan" {
            val model =
                InMemoryModelHandle(
                    tables = listOf(FixtureModel.customers),
                    entities = listOf(customerEntity),
                    entityMappings =
                        mapOf(
                            erQname("customer") to EntityMapping.ToTable(FixtureModel.customersQname),
                        ),
                )
            val branch =
                PlanNode
                    .newBuilder()
                    .setProject(ProjectNode.newBuilder().setInput(erScan("customer")))
                    .build()
            val input =
                PlanNode
                    .newBuilder()
                    .setUnion(
                        UnionNode
                            .newBuilder()
                            .setAll(true)
                            .addInputs(branch)
                            .addInputs(erScan("customer")),
                    ).build()

            val result = MapToPhysical.apply(input, model)

            result.shouldBeInstanceOf<MapToPhysicalResult.Success>()
            result.plan.nodeCase shouldBe PlanNode.NodeCase.UNION
            result.plan.union.all shouldBe true
            result.plan.union.inputsList[0]
                .project.input.nodeCase shouldBe PlanNode.NodeCase.TABLE_SCAN
            result.plan.union.inputsList[1]
                .tableScan.table shouldBe FixtureModel.customersQname
        }

        "a UNION with an unmapped entity in its second branch → entity_unmapped (not passed through)" {
            val model =
                InMemoryModelHandle(
                    tables = listOf(FixtureModel.customers),
                    entities = listOf(customerEntity),
                    entityMappings =
                        mapOf(
                            erQname("customer") to EntityMapping.ToTable(FixtureModel.customersQname),
                        ),
                )
            val input =
                PlanNode
                    .newBuilder()
                    .setUnion(
                        UnionNode
                            .newBuilder()
                            .addInputs(erScan("customer"))
                            .addInputs(erScan("ghost")),
                    ).build()

            val result = MapToPhysical.apply(input, model)

            result.shouldBeInstanceOf<MapToPhysicalResult.Failure>()
            result.code shouldBe "entity_unmapped"
        }
    })
