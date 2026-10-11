// SPDX-License-Identifier: Apache-2.0
package org.tatrman.translator.schema

import org.tatrman.plan.v1.ColumnRef
import org.tatrman.plan.v1.PlanNode
import org.tatrman.plan.v1.QualifiedName
import org.tatrman.plan.v1.ScanNode
import org.tatrman.plan.v1.SchemaCode
import org.tatrman.plan.v1.TableScanNode
import org.tatrman.plan.v1.UnionNode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.tatrman.translator.framework.AttributeOrColumnRef
import org.tatrman.translator.framework.FixtureModel
import org.tatrman.translator.framework.InMemoryModelHandle
import org.tatrman.translator.framework.ModelSavedQuery
import org.tatrman.translator.framework.SavedQueryBody
import org.tatrman.translator.framework.TranslatorFramework
import org.tatrman.translator.wire.PlanNodeEncoder

/**
 * Phase 08 B1 — UNFOLD_QUERY_REFS tests.
 *
 * The tests build their input [RelNode] via [TranslatorFramework]'s `RelBuilder` rather than
 * through `PlanNodeDecoder.decode` of a hand-built proto, so the `obj.query.X` symbol resolves
 * via Calcite the same way it would in a real `parseToRelNode` call. The model fixture must
 * register the saved query (so `obj` namespace appears in the SchemaPlus tree) plus any tables
 * referenced by saved-query bodies (so body decoding succeeds at the final pass).
 */
class UnfoldSpec :
    StringSpec({

        // -- Fixture helpers --------------------------------------------------------------------

        fun objQname(name: String): QualifiedName =
            QualifiedName
                .newBuilder()
                .setSchemaCode(SchemaCode.OBJ)
                .setNamespace("query")
                .setName(name)
                .build()

        // A synthetic column qname used only to give SavedQueryCalciteTable a row type of the
        // right cardinality. The qname doesn't resolve to a real table; ModelHandle.columns
        // returns empty for it, so SavedQueryCalciteTable falls back to SurfaceType.TEXT.
        fun colRef(name: String): AttributeOrColumnRef.Col =
            AttributeOrColumnRef.Col(
                QualifiedName
                    .newBuilder()
                    .setSchemaCode(SchemaCode.DB)
                    .setNamespace("dbo")
                    .setName(name)
                    .build(),
            )

        // -- Tests ------------------------------------------------------------------------------

        "idempotent — a tree with no OBJ scans is returned unchanged" {
            val fw = TranslatorFramework(FixtureModel.handle())
            val rel = fw.newRelBuilder().scan("db", "dbo", "customers").build()
            val before = PlanNodeEncoder.encode(rel)

            val result = Unfold.apply(rel, fw, FixtureModel.handle())

            result.shouldBeInstanceOf<UnfoldResult.Success>()
            result.plan shouldBe before
        }

        "simple inline — Scan(OBJ, X) → body spliced into the outer tree" {
            val xQname = objQname("X")
            // Body: TableScan(customers) — the full customers row (id, name, signup).
            val bodyPlan =
                PlanNode
                    .newBuilder()
                    .setTableScan(
                        TableScanNode
                            .newBuilder()
                            .setTable(FixtureModel.customersQname)
                            .addOutputColumns(ColumnRef.newBuilder().setName("id"))
                            .addOutputColumns(ColumnRef.newBuilder().setName("name"))
                            .addOutputColumns(ColumnRef.newBuilder().setName("signup")),
                    ).build()
            val model =
                InMemoryModelHandle(
                    tables = listOf(FixtureModel.customers),
                    savedQueries = listOf(ModelSavedQuery(xQname)),
                    savedQueryBodies =
                        mapOf(
                            xQname to
                                SavedQueryBody(
                                    planNode = bodyPlan,
                                    parameters = emptyList(),
                                    outputColumns = listOf(colRef("id"), colRef("name"), colRef("signup")),
                                ),
                        ),
                )
            val fw = TranslatorFramework(model, schemaCode = SchemaCode.OBJ, namespace = "query")
            val outerRel = fw.newRelBuilder().scan("obj", "query", "X").build()

            val result = Unfold.apply(outerRel, fw, model)

            result.shouldBeInstanceOf<UnfoldResult.Success>()
            // After unfolding, the OBJ scan is gone; what remains is the body (TableScan of customers).
            findFirstScanOrTableScan(result.plan)?.nodeCase shouldBe PlanNode.NodeCase.TABLE_SCAN
            findFirstScanOrTableScan(result.plan)?.tableScan?.table shouldBe FixtureModel.customersQname
        }

        "cardinality mismatch — Scan declares 2 cols but body produces 1 → saved_query_output_mismatch" {
            val xQname = objQname("mismatched_query")
            // Body produces only 1 column.
            val bodyPlan =
                PlanNode
                    .newBuilder()
                    .setTableScan(
                        TableScanNode
                            .newBuilder()
                            .setTable(FixtureModel.customersQname)
                            .addOutputColumns(ColumnRef.newBuilder().setName("id")),
                    ).build()
            val model =
                InMemoryModelHandle(
                    tables = listOf(FixtureModel.customers),
                    savedQueries = listOf(ModelSavedQuery(xQname)),
                    savedQueryBodies =
                        mapOf(
                            // Declared outputColumns: 2 entries — SavedQueryCalciteTable will
                            // surface a 2-field row type to Calcite, so the outer Scan declares 2.
                            xQname to
                                SavedQueryBody(
                                    planNode = bodyPlan,
                                    parameters = emptyList(),
                                    outputColumns = listOf(colRef("a"), colRef("b")),
                                ),
                        ),
                )
            val fw = TranslatorFramework(model, schemaCode = SchemaCode.OBJ, namespace = "query")
            val outerRel = fw.newRelBuilder().scan("obj", "query", "mismatched_query").build()

            val result = Unfold.apply(outerRel, fw, model)

            result.shouldBeInstanceOf<UnfoldResult.Error>()
            result.code shouldBe "saved_query_output_mismatch"
            result.message shouldContain "obj.query.mismatched_query"
            result.message shouldContain "declares 2"
            result.message shouldContain "produces 1"
        }

        "a saved query whose body is a UNION → its shape is the first branch's, so it inlines" {
            val xQname = objQname("union_query")

            fun idScan(table: QualifiedName): PlanNode =
                PlanNode
                    .newBuilder()
                    .setTableScan(
                        TableScanNode
                            .newBuilder()
                            .setTable(table)
                            .addOutputColumns(ColumnRef.newBuilder().setName("id")),
                    ).build()
            val bodyPlan =
                PlanNode
                    .newBuilder()
                    .setUnion(
                        UnionNode
                            .newBuilder()
                            .setAll(true)
                            .addInputs(idScan(FixtureModel.customersQname))
                            .addInputs(idScan(FixtureModel.ordersQname)),
                    ).build()
            val model =
                InMemoryModelHandle(
                    tables = listOf(FixtureModel.customers, FixtureModel.orders),
                    savedQueries = listOf(ModelSavedQuery(xQname)),
                    savedQueryBodies =
                        mapOf(
                            xQname to
                                SavedQueryBody(
                                    planNode = bodyPlan,
                                    parameters = emptyList(),
                                    outputColumns = listOf(colRef("id")),
                                ),
                        ),
                )
            val fw = TranslatorFramework(model, schemaCode = SchemaCode.OBJ, namespace = "query")
            val outerRel = fw.newRelBuilder().scan("obj", "query", "union_query").build()

            val result = Unfold.apply(outerRel, fw, model)

            // Before: a UNION counted 0 output columns → saved_query_output_mismatch ("declares 1 … produces 0").
            result.shouldBeInstanceOf<UnfoldResult.Success>()
        }

        "cycle detection — X references Y references X → query_reference_cycle" {
            val xQname = objQname("X")
            val yQname = objQname("Y")

            // X's body references Y; Y's body references X. Each body is a single ScanNode
            // pointing at the other saved query, with output_columns sized to match.
            fun scanOfBody(target: QualifiedName): PlanNode =
                PlanNode
                    .newBuilder()
                    .setScan(
                        ScanNode
                            .newBuilder()
                            .setObject(target)
                            .addOutputColumns(ColumnRef.newBuilder().setName("a")),
                    ).build()
            val model =
                InMemoryModelHandle(
                    tables = listOf(FixtureModel.customers),
                    savedQueries = listOf(ModelSavedQuery(xQname), ModelSavedQuery(yQname)),
                    savedQueryBodies =
                        mapOf(
                            xQname to
                                SavedQueryBody(
                                    planNode = scanOfBody(yQname),
                                    parameters = emptyList(),
                                    outputColumns = listOf(colRef("a")),
                                ),
                            yQname to
                                SavedQueryBody(
                                    planNode = scanOfBody(xQname),
                                    parameters = emptyList(),
                                    outputColumns = listOf(colRef("a")),
                                ),
                        ),
                )
            val fw = TranslatorFramework(model, schemaCode = SchemaCode.OBJ, namespace = "query")
            val outerRel = fw.newRelBuilder().scan("obj", "query", "X").build()

            val result = Unfold.apply(outerRel, fw, model)

            result.shouldBeInstanceOf<UnfoldResult.Error>()
            result.code shouldBe "query_reference_cycle"
            // Path includes both qnames.
            result.message shouldContain "obj.query.X"
            result.message shouldContain "obj.query.Y"
        }
    })

// Convenience: walk the plan and return the first leaf TABLE_SCAN or SCAN node found.
private fun findFirstScanOrTableScan(plan: PlanNode): PlanNode? {
    if (plan.nodeCase == PlanNode.NodeCase.TABLE_SCAN || plan.nodeCase == PlanNode.NodeCase.SCAN) {
        return plan
    }
    return when (plan.nodeCase) {
        PlanNode.NodeCase.PROJECT -> findFirstScanOrTableScan(plan.project.input)
        PlanNode.NodeCase.FILTER -> findFirstScanOrTableScan(plan.filter.input)
        PlanNode.NodeCase.JOIN ->
            findFirstScanOrTableScan(plan.join.left) ?: findFirstScanOrTableScan(plan.join.right)
        PlanNode.NodeCase.AGGREGATE -> findFirstScanOrTableScan(plan.aggregate.input)
        PlanNode.NodeCase.SORT -> findFirstScanOrTableScan(plan.sort.input)
        PlanNode.NodeCase.LIMIT_OFFSET -> findFirstScanOrTableScan(plan.limitOffset.input)
        PlanNode.NodeCase.SUBQUERY -> findFirstScanOrTableScan(plan.subquery.subquery)
        else -> null
    }
}
