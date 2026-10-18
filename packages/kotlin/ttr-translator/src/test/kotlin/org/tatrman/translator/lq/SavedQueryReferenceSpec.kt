// SPDX-License-Identifier: Apache-2.0
package org.tatrman.translator.lq

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.apache.calcite.sql.type.SqlTypeFamily
import org.tatrman.plan.v1.PlanNode
import org.tatrman.plan.v1.QualifiedName
import org.tatrman.plan.v1.SchemaCode
import org.tatrman.translate.v1.Language
import org.tatrman.translator.framework.HartlandJoinModel
import org.tatrman.translator.framework.HartlandStackModel
import org.tatrman.translator.framework.InMemoryModelHandle
import org.tatrman.translator.framework.ModelSavedQuery
import org.tatrman.translator.framework.ParamSpec
import org.tatrman.translator.framework.SavedQueryBody
import org.tatrman.translator.framework.SurfaceType
import org.tatrman.translator.framework.TranslatorFramework
import org.tatrman.translator.orchestrator.ParseResult
import org.tatrman.translator.orchestrator.Translator
import org.tatrman.translator.wire.PlanNodeDecoder

/**
 * LQ-T4 (design §8, contracts L-23, L-25) — one saved query reading another as `obj.query.<name>`, served the way a
 * metadata worker serves it: the referenced query's stored plan as its body and **no declared output columns** (a
 * worker has the plan, not a list of model refs for a computed column such as `SUM(…) AS revenue`).
 */
class SavedQueryReferenceSpec :
    StringSpec({
        "L-23 — an OBJ saved query with no declared output columns takes its row type from its stored plan" {
            val parsed =
                Translator(Served.model).parseToRelNode(
                    "SELECT r.shopper_id, SUM(r.revenue) AS total FROM obj.query.shopper_revenue r " +
                        "WHERE r.revenue > 100 GROUP BY r.shopper_id",
                    Language.SQL,
                    targetSchema = SchemaCode.ER,
                    sourceSchema = SchemaCode.ER,
                    strictLane = true,
                )
            val plan = parsed.shouldBeInstanceOf<ParseResult.Success>().plan
            HartlandStackModel.erScans(plan).map { it.name } shouldContainExactlyInAnyOrder listOf("sale", "shopper")
            val rowType = PlanNodeDecoder.decode(plan, TranslatorFramework(Served.model)).rowType
            rowType.fieldNames shouldBe listOf("shopper_id", "total")
            rowType
                .getField("total", true, false)!!
                .type.sqlTypeName.family shouldBe SqlTypeFamily.NUMERIC
        }

        "L-25 — reading a saved query that declares parameters is refused: saved_query_parameters" {
            val failure =
                Translator(Served.model)
                    .parseToRelNode(
                        "SELECT r.shopper_id FROM obj.query.revenue_over r",
                        Language.SQL,
                        targetSchema = SchemaCode.DB,
                        sourceSchema = SchemaCode.DB,
                        strictLane = true,
                    ).shouldBeInstanceOf<ParseResult.Failure>()
            failure.code shouldBe "saved_query_parameters"
            failure.message shouldContain "revenue_over"
            failure.message shouldContain "min_revenue"
        }
    })

/**
 * The Hartland model plus saved queries as a worker would serve them under OBJ: `shopper_revenue` (the logical body,
 * an ER stored plan) and `revenue_over` (a db body that declares a parameter), neither with declared output columns.
 */
private object Served {
    private fun obj(name: String): QualifiedName =
        QualifiedName
            .newBuilder()
            .setSchemaCode(SchemaCode.OBJ)
            .setNamespace("query")
            .setName(name)
            .build()

    private val base = HartlandJoinModel.handle()

    private val dbBody: PlanNode =
        (
            Translator(base).parseToRelNode(
                HartlandStackModel.REVENUE_SQL_PHYSICAL,
                Language.SQL,
                targetSchema = SchemaCode.DB,
                sourceSchema = SchemaCode.DB,
            ) as ParseResult.Success
        ).plan

    val model: InMemoryModelHandle =
        InMemoryModelHandle(
            tables = HartlandJoinModel.tables,
            entities = HartlandJoinModel.entities,
            relations = HartlandJoinModel.relations,
            entityMappings = HartlandJoinModel.entityMappings,
            savedQueries = listOf(ModelSavedQuery(obj("shopper_revenue")), ModelSavedQuery(obj("revenue_over"))),
            savedQueryBodies =
                mapOf(
                    obj("shopper_revenue") to
                        SavedQueryBody(
                            HartlandStackModel.parseEr(base, HartlandStackModel.REVENUE_SQL),
                            emptyList(),
                            emptyList(),
                        ),
                    obj("revenue_over") to
                        SavedQueryBody(dbBody, listOf(ParamSpec("min_revenue", SurfaceType.FLOAT)), emptyList()),
                ),
            attributeRenames = HartlandJoinModel.renames,
        )
}
