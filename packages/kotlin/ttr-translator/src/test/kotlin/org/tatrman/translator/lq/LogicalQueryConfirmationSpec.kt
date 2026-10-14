// SPDX-License-Identifier: Apache-2.0
package org.tatrman.translator.lq

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.tatrman.plan.v1.PlanNode
import org.tatrman.plan.v1.QualifiedName
import org.tatrman.plan.v1.SchemaCode
import org.tatrman.translate.v1.Language
import org.tatrman.translate.v1.SqlDialect
import org.tatrman.translator.detect.SchemaDecision
import org.tatrman.translator.detect.SchemaDetector
import org.tatrman.translator.framework.EntityMapping
import org.tatrman.translator.framework.HartlandJoinModel
import org.tatrman.translator.framework.HartlandJoinModel.attr
import org.tatrman.translator.framework.HartlandJoinModel.db
import org.tatrman.translator.framework.HartlandJoinModel.er
import org.tatrman.translator.framework.InMemoryModelHandle
import org.tatrman.translator.framework.ModelAttribute
import org.tatrman.translator.framework.ModelColumn
import org.tatrman.translator.framework.ModelEntity
import org.tatrman.translator.framework.ModelHandle
import org.tatrman.translator.framework.ModelRelation
import org.tatrman.translator.framework.ModelSavedQuery
import org.tatrman.translator.framework.ModelTable
import org.tatrman.translator.framework.SavedQueryBody
import org.tatrman.translator.framework.SurfaceType
import org.tatrman.translator.joiner.JoinerMessages
import org.tatrman.translator.joiner.JoinerPlanWalker
import org.tatrman.translator.orchestrator.ParseResult
import org.tatrman.translator.orchestrator.TranslateResult
import org.tatrman.translator.orchestrator.Translator

/**
 * LQ (logical queries) — the confirmation tests C-1 and C-3 of project
 * `ttr-translator/logical-queries/design.md §5`, run against the shared core as it is today (no LQ code).
 *
 * The model is the MJ Hartland slice ([HartlandJoinModel], no foreign keys) plus one **stacked** entity,
 * `shopper_revenue`, backed by a logical query `er.query.shopper_revenue` written over entities:
 *
 * ```sql
 * SELECT shopper.shopper_id, shopper.last_name, shopper.current_household, SUM(sale.revenue) AS revenue
 * FROM sale JOIN shopper
 * GROUP BY shopper.shopper_id, shopper.last_name, shopper.current_household
 * ```
 *
 * The query body is produced the way design §3 step 2–3 proposes — `parseToRelNode(statedSchema = ER,
 * targetSchema = ER)`, entity scans kept — and handed to the model as the saved-query body. Every C-x
 * case then translates an ordinary query over the stacked entity to MSSQL. `LQ_DUMP_DIR=<dir>` writes the
 * generated SQL and plans for the record (`design.md §5` Result column).
 */
class LogicalQueryConfirmationSpec :
    StringSpec({
        val base = HartlandJoinModel.handle()

        "C-3a — a query over entities is CONFIRMED as ER when ER is stated (the ttr worker today states nothing)" {
            val stated = SchemaDetector.detect(Lq.REVENUE_SQL, Language.SQL, SchemaCode.ER, base)
            stated.decision shouldBe SchemaDecision.CONFIRMED
            stated.effectiveSchema shouldBe SchemaCode.ER

            // The ttr QueryParseWorker passes no stated schema: the lane is guessed (AUTODETECTED) …
            val unstated = SchemaDetector.detect(Lq.REVENUE_SQL, Language.SQL, SchemaCode.SCHEMA_CODE_UNSPECIFIED, base)
            unstated.decision shouldBe SchemaDecision.AUTODETECTED
            unstated.effectiveSchema shouldBe SchemaCode.ER
            // … a worker that forces DB gets a correction.
            SchemaDetector.detect(Lq.REVENUE_SQL, Language.SQL, SchemaCode.DB, base).decision shouldBe
                SchemaDecision.CORRECTED
            // LQ-2's refusal has its signal: entities and tables in one body are MIXED.
            SchemaDetector
                .detect(
                    "SELECT sale.revenue, item.product_name FROM sale JOIN item ON sale.product_id = item.product_id",
                    Language.SQL,
                    SchemaCode.ER,
                    base,
                ).decision shouldBe SchemaDecision.MIXED
        }

        "C-3b — parseToRelNode(stated ER, target ER) stores an ER-level plan: entity scans, the bare JOIN conditioned" {
            val parsed =
                Translator(base)
                    .parseToRelNode(
                        Lq.REVENUE_SQL,
                        Language.SQL,
                        targetSchema = SchemaCode.ER,
                        sourceSchema = SchemaCode.ER,
                    ).shouldBeInstanceOf<ParseResult.Success>()
            Lq.dump("c3-body-er-plan.txtpb", parsed.plan.toString())
            parsed.warnings.shouldBeEmpty()
            parsed.modelJoinsSql shouldNotBe null // the SQL carrier (ModelJoinRewriter) wrote the ON
            Lq.erScans(parsed.plan).map { it.name } shouldContainExactlyInAnyOrder listOf("sale", "shopper")
            Lq.tableScans(parsed.plan).shouldBeEmpty()
            Lq.outputNames(parsed.plan) shouldBe listOf("shopper_id", "last_name", "current_household", "revenue")
        }

        "C-3c — an entity stacked on the stored ER plan translates to physical SQL, columns aliased to attributes" {
            val model = Lq.stacked(base)
            val r =
                Lq
                    .mssql(
                        model,
                        "SELECT shopper_revenue.last_name, shopper_revenue.total_revenue FROM shopper_revenue " +
                            "WHERE shopper_revenue.total_revenue > 1000",
                    ).shouldBeInstanceOf<TranslateResult.Success>()
            Lq.dump("c3-stacked.sql", r.output)
            Lq.dump("c3-stacked-plan.txtpb", r.plan.toString())
            Lq.erScans(r.plan).shouldBeEmpty()
            Lq.tableScans(r.plan).map { it.name } shouldContainExactlyInAnyOrder listOf("store_sales", "customer")
            r.output shouldContain "[store_sales]"
            r.output shouldContain "[customer]"
            r.output shouldContain "SUM("
            r.output shouldContain "GROUP BY"
            // the attribute `total_revenue` is bound to the query's output column `revenue` (alias-at-boundary)
            r.output shouldContain "AS [total_revenue]"
            r.warnings.shouldBeEmpty()
        }

        "C-3d — stacks stack: a second logical query over the stacked entity, a third entity on it" {
            val level1 = Lq.stacked(base)
            val level2 = Lq.stackedTwice(level1)
            val r =
                Lq
                    .mssql(level2, "SELECT big_spender.last_name FROM big_spender")
                    .shouldBeInstanceOf<TranslateResult.Success>()
            Lq.dump("c3-stacked-twice.sql", r.output)
            Lq.erScans(r.plan).shouldBeEmpty()
            Lq.tableScans(r.plan).map { it.name } shouldContainExactlyInAnyOrder listOf("store_sales", "customer")
            r.output shouldContain "WHERE [t1].[total_revenue] > " // the level-2 filter, on the level-1 alias
        }

        "C-3e — the stored ER body follows a changed entity mapping without a re-parse" {
            // shopper moves to another table; the logical query body is NOT re-parsed.
            val moved =
                Lq.stacked(
                    base,
                    tables = HartlandJoinModel.tables + Lq.table("customer_v2", HartlandJoinModel.shopper),
                    mappings =
                        HartlandJoinModel.entityMappings + (er("shopper") to EntityMapping.ToTable(db("customer_v2"))),
                    bodyModel = base,
                )
            val r =
                Lq
                    .mssql(moved, "SELECT shopper_revenue.last_name FROM shopper_revenue")
                    .shouldBeInstanceOf<TranslateResult.Success>()
            r.output shouldContain "[customer_v2]"
            Lq.tableScans(r.plan).map { it.name } shouldContainExactlyInAnyOrder listOf("store_sales", "customer_v2")
        }

        "C-3f — an entity whose logical query scans the entity itself is entity_query_mapping_cycle" {
            val loop = er("loop")
            val loopQuery = Lq.erQuery("loop_q")
            val entity = ModelEntity(loop, listOf(ModelAttribute("shopper_id", SurfaceType.INT)))
            val withEntity =
                InMemoryModelHandle(
                    tables = HartlandJoinModel.tables,
                    entities = HartlandJoinModel.entities + entity,
                    relations = HartlandJoinModel.relations,
                    entityMappings = HartlandJoinModel.entityMappings + (loop to EntityMapping.ToQuery(loopQuery)),
                    attributeRenames = HartlandJoinModel.renames,
                )
            val body = Lq.parseEr(withEntity, "SELECT loop.shopper_id FROM loop")
            val model =
                InMemoryModelHandle(
                    tables = HartlandJoinModel.tables,
                    entities = HartlandJoinModel.entities + entity,
                    relations = HartlandJoinModel.relations,
                    entityMappings = HartlandJoinModel.entityMappings + (loop to EntityMapping.ToQuery(loopQuery)),
                    savedQueries = listOf(ModelSavedQuery(loopQuery)),
                    savedQueryBodies = mapOf(loopQuery to SavedQueryBody(body, emptyList(), emptyList())),
                    attributeRenames = HartlandJoinModel.renames,
                )
            val r = Lq.mssql(model, "SELECT loop.shopper_id FROM loop").shouldBeInstanceOf<TranslateResult.Failure>()
            r.code shouldBe "entity_query_mapping_cycle"
        }

        "C-3g — the stored body is found under the ER key, and under a schemaless key from an er.query reference" {
            val schemaless =
                Lq
                    .erQuery(
                        "shopper_revenue",
                    ).toBuilder()
                    .setSchemaCode(SchemaCode.SCHEMA_CODE_UNSPECIFIED)
                    .build()
            val model = Lq.stacked(base, storedUnder = schemaless)
            Lq
                .mssql(model, "SELECT shopper_revenue.last_name FROM shopper_revenue")
                .shouldBeInstanceOf<TranslateResult.Success>()
        }

        "C-3h — today a MIXED body with ER stated fails only as a Calcite lookup error (no lane code yet, design §4)" {
            val r =
                Translator(base).parseToRelNode(
                    "SELECT sale.revenue, item.product_name FROM sale JOIN item ON sale.product_id = item.product_id",
                    Language.SQL,
                    targetSchema = SchemaCode.ER,
                    sourceSchema = SchemaCode.ER,
                )
            val failure = r.shouldBeInstanceOf<ParseResult.Failure>()
            Lq.dump("c3-mixed-failure.txt", "${failure.code}: ${failure.message}")
            failure.message shouldContain "item"
        }

        // ---- C-1: a bare JOIN onto a query-backed entity, no FK anywhere in the model ----

        "C-1a — bare JOIN onto the stacked entity: the SQL carrier writes the ON from the relation's join pairs" {
            val model = Lq.stacked(base)
            model.foreignKeys().shouldBeEmpty()
            val parsed =
                Translator(model)
                    .parseToRelNode(
                        Lq.C1_BARE,
                        Language.SQL,
                        targetSchema = SchemaCode.DB,
                        sourceSchema = SchemaCode.ER,
                    ).shouldBeInstanceOf<ParseResult.Success>()
            parsed.modelJoinsSql shouldNotBe null
            parsed.modelJoinsSql!! shouldContain
                "ON \"shopper_revenue\".\"current_household\" = \"household\".\"household_id\""
            parsed.warnings.shouldBeEmpty()

            val r = Lq.mssql(model, Lq.C1_BARE).shouldBeInstanceOf<TranslateResult.Success>()
            Lq.dump("c1-bare-join.sql", r.output)
            r.warnings.shouldBeEmpty()
            Lq.tableScans(r.plan).map { it.name } shouldContainExactlyInAnyOrder
                listOf("store_sales", "customer", "household_demographics")
            r.output shouldContain "[household_demographics]"
            r.output shouldContain "[hd_demo_sk]" // the household key crosses its rename on the way
            r.output shouldContain "[current_household]"
            r.output.count { it == '=' } shouldBe 2 // the body's sale ⋈ shopper + the outer stacked ⋈ household
        }

        "C-1b — the comma form: the wire carrier (JoinerLogical) conditions it from the same pairs" {
            val r = Lq.mssql(Lq.stacked(base), Lq.C1_COMMA).shouldBeInstanceOf<TranslateResult.Success>()
            Lq.dump("c1-comma.sql", r.output)
            r.warnings.shouldBeEmpty()
            r.output.count { it == '=' } shouldBe 2
        }

        "C-1c — control: without the relation the same JOIN is a reported Cartesian product (no FK to fall back on)" {
            val model = Lq.stacked(base, relations = HartlandJoinModel.relations)
            val r = Lq.mssql(model, Lq.C1_COMMA).shouldBeInstanceOf<TranslateResult.Success>()
            r.warnings.map(JoinerMessages::code) shouldBe listOf(JoinerMessages.NO_RELATION)
            r.output.count { it == '=' } shouldBe 1 // only the body's own join
        }

        "C-1d — the same holds for an entity on a PHYSICAL saved query (today's entity-on-query shape)" {
            val physicalBody =
                (
                    Translator(base).parseToRelNode(
                        Lq.REVENUE_SQL_PHYSICAL,
                        Language.SQL,
                        targetSchema = SchemaCode.DB,
                        sourceSchema = SchemaCode.DB,
                    ) as ParseResult.Success
                ).plan
            Lq.erScans(physicalBody).shouldBeEmpty()
            val model = Lq.stacked(base, body = physicalBody)
            val r = Lq.mssql(model, Lq.C1_BARE).shouldBeInstanceOf<TranslateResult.Success>()
            Lq.dump("c1-physical-body.sql", r.output)
            r.warnings.shouldBeEmpty()
            r.output.count { it == '=' } shouldBe 2
        }
    })

private object Lq {
    const val REVENUE_SQL: String =
        "SELECT shopper.shopper_id, shopper.last_name, shopper.current_household, SUM(sale.revenue) AS revenue " +
            "FROM sale JOIN shopper " +
            "GROUP BY shopper.shopper_id, shopper.last_name, shopper.current_household"

    /** The same query written against the tables. */
    const val REVENUE_SQL_PHYSICAL: String =
        "SELECT customer.shopper_id, customer.last_name, customer.current_household, " +
            "SUM(store_sales.revenue) AS revenue " +
            "FROM store_sales JOIN customer ON store_sales.shopper_id = customer.shopper_id " +
            "GROUP BY customer.shopper_id, customer.last_name, customer.current_household"

    const val BIG_SPENDERS_SQL: String =
        "SELECT shopper_revenue.shopper_id, shopper_revenue.last_name, shopper_revenue.total_revenue " +
            "FROM shopper_revenue WHERE shopper_revenue.total_revenue > 1000"

    const val C1_BARE: String =
        "SELECT household.buy_potential, SUM(shopper_revenue.total_revenue) AS revenue " +
            "FROM shopper_revenue JOIN household GROUP BY household.buy_potential"

    const val C1_COMMA: String =
        "SELECT household.buy_potential, SUM(shopper_revenue.total_revenue) AS revenue " +
            "FROM shopper_revenue, household GROUP BY household.buy_potential"

    fun erQuery(name: String): QualifiedName =
        QualifiedName
            .newBuilder()
            .setSchemaCode(SchemaCode.ER)
            .setNamespace("query")
            .setName(name)
            .build()

    val revenueQuery = erQuery("shopper_revenue")
    val bigSpendersQuery = erQuery("big_spenders")

    /** Declared attributes (LQ-3); `total_revenue` is bound to the output column `revenue`. */
    val shopperRevenue =
        ModelEntity(
            er("shopper_revenue"),
            listOf(
                ModelAttribute("shopper_id", SurfaceType.INT, nullable = false, isKey = true),
                ModelAttribute("last_name", SurfaceType.TEXT),
                ModelAttribute("current_household", SurfaceType.INT),
                ModelAttribute("total_revenue", SurfaceType.FLOAT),
            ),
        )

    val bigSpender =
        ModelEntity(
            er("big_spender"),
            listOf(
                ModelAttribute("shopper_id", SurfaceType.INT, nullable = false, isKey = true),
                ModelAttribute("last_name", SurfaceType.TEXT),
                ModelAttribute("total_revenue", SurfaceType.FLOAT),
            ),
        )

    /** `join:` pairs only — no FK exists anywhere in the model (C-1). */
    val revenueHousehold =
        ModelRelation(
            fromEntity = er("shopper_revenue"),
            toEntity = er("household"),
            joinPairs = listOf(attr("shopper_revenue", "current_household") to attr("household", "household_id")),
        )

    fun table(
        name: String,
        entity: ModelEntity,
    ) = ModelTable(db(name), entity.attributes.map { ModelColumn(it.name, it.surfaceType, it.nullable) })

    fun parseEr(
        model: ModelHandle,
        sql: String,
    ): PlanNode {
        val parsed =
            Translator(
                model,
            ).parseToRelNode(sql, Language.SQL, targetSchema = SchemaCode.ER, sourceSchema = SchemaCode.ER)
        check(parsed is ParseResult.Success) { "logical query did not parse: $parsed" }
        // what a worker stores and a snapshot serves is bytes
        return PlanNode.parseFrom(parsed.plan.toByteArray())
    }

    /**
     * [base] + `shopper_revenue` stacked on `er.query.shopper_revenue`. The body is parsed against [bodyModel]
     * (default: the base model — the stacked entity does not exist yet when its own query is parsed).
     */
    fun stacked(
        base: InMemoryModelHandle,
        tables: List<ModelTable> = HartlandJoinModel.tables,
        mappings: Map<QualifiedName, EntityMapping> = HartlandJoinModel.entityMappings,
        relations: List<ModelRelation> = HartlandJoinModel.relations + revenueHousehold,
        bodyModel: ModelHandle = base,
        body: PlanNode = parseEr(bodyModel, REVENUE_SQL),
        storedUnder: QualifiedName = revenueQuery,
    ): InMemoryModelHandle =
        InMemoryModelHandle(
            tables = tables,
            entities = HartlandJoinModel.entities + shopperRevenue,
            relations = relations,
            entityMappings = mappings + (shopperRevenue.qname to EntityMapping.ToQuery(revenueQuery)),
            savedQueries = listOf(ModelSavedQuery(storedUnder)),
            savedQueryBodies = mapOf(storedUnder to SavedQueryBody(body, emptyList(), emptyList())),
            attributeRenames =
                HartlandJoinModel.renames + (
                    shopperRevenue.qname to
                        mapOf(
                            "total_revenue" to "revenue",
                        )
                ),
        )

    /** [level1] + `big_spender` stacked on `er.query.big_spenders`, a logical query over `shopper_revenue`. */
    fun stackedTwice(level1: InMemoryModelHandle): InMemoryModelHandle {
        val body = parseEr(level1, BIG_SPENDERS_SQL)
        check(erScans(body).map { it.name } == listOf("shopper_revenue")) { "level-2 body must keep the stacked scan" }
        return InMemoryModelHandle(
            tables = HartlandJoinModel.tables,
            entities = HartlandJoinModel.entities + shopperRevenue + bigSpender,
            relations = HartlandJoinModel.relations + revenueHousehold,
            entityMappings =
                HartlandJoinModel.entityMappings +
                    (shopperRevenue.qname to EntityMapping.ToQuery(revenueQuery)) +
                    (bigSpender.qname to EntityMapping.ToQuery(bigSpendersQuery)),
            savedQueries = listOf(ModelSavedQuery(revenueQuery), ModelSavedQuery(bigSpendersQuery)),
            savedQueryBodies =
                mapOf(
                    revenueQuery to level1.savedQueryBody(revenueQuery),
                    bigSpendersQuery to SavedQueryBody(body, emptyList(), emptyList()),
                ),
            attributeRenames =
                HartlandJoinModel.renames + (
                    shopperRevenue.qname to
                        mapOf(
                            "total_revenue" to "revenue",
                        )
                ),
        )
    }

    fun mssql(
        model: ModelHandle,
        sql: String,
    ): TranslateResult =
        Translator(model).translate(
            source = sql,
            sourceLanguage = Language.SQL,
            targetLanguage = Language.SQL,
            targetSchema = SchemaCode.DB,
            targetDialect = SqlDialect.MSSQL,
            sourceSchema = SchemaCode.ER,
        )

    fun nodes(plan: PlanNode): List<PlanNode> {
        val out = mutableListOf(plan)
        JoinerPlanWalker.rewriteChildren(plan) { child ->
            out += nodes(child)
            child
        }
        return out
    }

    fun erScans(plan: PlanNode): List<QualifiedName> =
        nodes(
            plan,
        ).filter { it.nodeCase == PlanNode.NodeCase.SCAN && it.scan.getObject().schemaCode == SchemaCode.ER }.map {
            it.scan.getObject()
        }

    fun tableScans(plan: PlanNode): List<QualifiedName> =
        nodes(plan).filter { it.nodeCase == PlanNode.NodeCase.TABLE_SCAN }.map { it.tableScan.table }

    fun outputNames(plan: PlanNode): List<String> =
        when (plan.nodeCase) {
            PlanNode.NodeCase.PROJECT -> plan.project.expressionsList.map { it.alias }
            PlanNode.NodeCase.AGGREGATE ->
                plan.aggregate.groupKeysList.map { it.alias.ifEmpty { it.name } } +
                    plan.aggregate.aggregatesList.map { it.alias }
            else -> error("unexpected root ${plan.nodeCase}")
        }

    fun dump(
        name: String,
        text: String,
    ) {
        val dir = System.getenv("LQ_DUMP_DIR")?.takeIf { it.isNotBlank() } ?: return
        java.io.File(dir).mkdirs()
        java.io.File(dir, name).writeText(text)
    }
}
