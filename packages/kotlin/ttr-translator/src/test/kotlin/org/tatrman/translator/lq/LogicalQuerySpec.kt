// SPDX-License-Identifier: Apache-2.0
package org.tatrman.translator.lq

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.tatrman.plan.v1.PlanNode
import org.tatrman.plan.v1.QualifiedName
import org.tatrman.plan.v1.SchemaCode
import org.tatrman.translate.v1.Language
import org.tatrman.translator.framework.AttributeOrColumnRef
import org.tatrman.translator.framework.EntityMapping
import org.tatrman.translator.framework.HartlandJoinModel
import org.tatrman.translator.framework.HartlandStackModel
import org.tatrman.translator.framework.InMemoryModelHandle
import org.tatrman.translator.framework.ModelAttribute
import org.tatrman.translator.framework.ModelEntity
import org.tatrman.translator.framework.ModelHandle
import org.tatrman.translator.framework.ModelSavedQuery
import org.tatrman.translator.framework.SavedQueryBody
import org.tatrman.translator.framework.SurfaceType
import org.tatrman.translator.orchestrator.ParseResult
import org.tatrman.translator.orchestrator.Translator
import org.tatrman.translator.params.SqlParam
import org.tatrman.translator.schema.ExpandLogicalBodies
import org.tatrman.translator.schema.ExpandLogicalBodiesResult

/**
 * LQ (logical queries) — the acceptance corpus of project `ttr-translator/logical-queries/plan/plan.md §4`,
 * H1–H12, on [HartlandStackModel]. Contracts L-2/L-3 (the strict lane), L-7 (`strictLane`), L-8…L-12
 * (`EXPAND_LOGICAL_BODIES`, the stamped boundary, byte-identical physical plans).
 */
class LogicalQuerySpec :
    StringSpec({
        val base = HartlandJoinModel.handle()

        fun strict(
            sql: String,
            stated: SchemaCode,
            parameters: List<SqlParam> = emptyList(),
            model: ModelHandle = base,
        ): ParseResult =
            Translator(model).parseToRelNode(
                sql,
                Language.SQL,
                targetSchema = SchemaCode.ER,
                sourceSchema = stated,
                parameters = parameters,
                strictLane = true,
            )

        // ---- S1: the strict lane (L-2, L-7) ----

        "H1 — a logical query parsed strict with ER stated: Success, entity scans kept, the bare JOIN conditioned" {
            val parsed = strict(HartlandStackModel.REVENUE_SQL, SchemaCode.ER).shouldBeInstanceOf<ParseResult.Success>()
            HartlandStackModel.erScans(parsed.plan).map { it.name } shouldContainExactlyInAnyOrder
                listOf("sale", "shopper")
            HartlandStackModel.tableScans(parsed.plan).shouldBeEmpty()
            parsed.warnings.shouldBeEmpty()
            parsed.modelJoinsSql!! shouldContain "ON \"sale\".\"shopper_id\" = \"shopper\".\"shopper_id\""
        }

        "H1 — strict ER is byte-identical to the lenient stated-ER parse it replaces" {
            val strictPlan = (strict(HartlandStackModel.REVENUE_SQL, SchemaCode.ER) as ParseResult.Success).plan
            val lenient = HartlandStackModel.parseEr(base, HartlandStackModel.REVENUE_SQL)
            strictPlan.toByteArray().contentEquals(lenient.toByteArray()) shouldBe true
        }

        "H2 — the same body with DB stated, strict: lane_mismatch naming the entities" {
            val failure =
                strict(HartlandStackModel.REVENUE_SQL, SchemaCode.DB).shouldBeInstanceOf<ParseResult.Failure>()
            failure.code shouldBe "lane_mismatch"
            failure.message shouldContain "sale"
            failure.message shouldContain "shopper"
        }

        "H3 — a body mixing entities and tables, strict: mixed_lanes naming both sets" {
            val failure =
                strict(
                    "SELECT sale.revenue, item.product_name FROM sale JOIN item ON sale.product_id = item.product_id",
                    SchemaCode.ER,
                ).shouldBeInstanceOf<ParseResult.Failure>()
            failure.code shouldBe "mixed_lanes"
            failure.message shouldContain "sale"
            failure.message shouldContain "item"
        }

        "H3b — an unknown name, strict: lane_mismatch naming it" {
            val failure =
                strict("SELECT nothing.x FROM nothing", SchemaCode.ER).shouldBeInstanceOf<ParseResult.Failure>()
            failure.code shouldBe "lane_mismatch"
            failure.message shouldContain "nothing"
        }

        "H3c — strict, no stated lane: lane_mismatch (a strict parse needs the lane the query sits in)" {
            strict(HartlandStackModel.REVENUE_SQL, SchemaCode.SCHEMA_CODE_UNSPECIFIED)
                .shouldBeInstanceOf<ParseResult.Failure>()
                .code shouldBe "lane_mismatch"
        }

        "H3d — strict, a body that does not parse: the parser's own error, not a lane verdict" {
            val failure =
                strict(
                    "SELEKT sale.revenue FROM sale",
                    SchemaCode.ER,
                ).shouldBeInstanceOf<ParseResult.Failure>()
            failure.code.shouldNotContain("lane")
        }

        "H3e — the other lane inside a sub-query (WHERE, select list), strict: mixed_lanes" {
            for (sql in listOf(
                "SELECT sale.revenue FROM sale WHERE sale.store_id IN (SELECT s.store_id FROM db.dbo.store_sales s)",
                "SELECT sale.revenue, (SELECT MAX(c.shopper_id) FROM db.dbo.customer c) AS m FROM sale",
            )) {
                val failure = strict(sql, SchemaCode.ER).shouldBeInstanceOf<ParseResult.Failure>()
                failure.code shouldBe "mixed_lanes"
                failure.message shouldContain "sale"
            }
        }

        "H3f — a db query reading an entity in a sub-query, strict: mixed_lanes" {
            val failure =
                strict(
                    "SELECT store_sales.revenue FROM store_sales " +
                        "WHERE store_sales.shopper_id IN (SELECT e.shopper_id FROM er.entity.shopper e)",
                    SchemaCode.DB,
                ).shouldBeInstanceOf<ParseResult.Failure>()
            failure.code shouldBe "mixed_lanes"
            failure.message shouldContain "shopper"
            failure.message shouldContain "store_sales"
        }

        "H3g — a saved-query reference (`obj.query.<name>`) counts by what it unfolds to, strict" {
            // a db query reading a db saved query, a logical query reading a logical saved query: Success
            val dbRead =
                strict(
                    "SELECT r.shopper_id, r.revenue FROM obj.query.store_revenue r",
                    SchemaCode.DB,
                    model = SavedRefs.model,
                ).shouldBeInstanceOf<ParseResult.Success>()
            HartlandStackModel.tableScans(dbRead.plan).map { it.name } shouldContainExactlyInAnyOrder
                listOf("store_sales", "customer")
            val erRead =
                strict(
                    "SELECT r.shopper_id, r.revenue FROM obj.query.shopper_revenue r",
                    SchemaCode.ER,
                    model = SavedRefs.model,
                ).shouldBeInstanceOf<ParseResult.Success>()
            HartlandStackModel.erScans(erRead.plan).map { it.name } shouldContainExactlyInAnyOrder
                listOf("sale", "shopper")
            // … beside a name of its own lane in the same FROM, too
            strict(
                "SELECT h.buy_potential, r.revenue FROM household h, obj.query.shopper_revenue r " +
                    "WHERE h.buy_potential = r.last_name",
                SchemaCode.ER,
                model = SavedRefs.model,
            ).shouldBeInstanceOf<ParseResult.Success>()
            // a logical query reading only a db saved query reads tables, a db query reading only a logical one reads
            // entities: lane_mismatch naming them; beside a name of the declared lane: mixed_lanes
            for ((sql, stated, names) in listOf(
                Triple("SELECT r.shopper_id FROM obj.query.store_revenue r", SchemaCode.ER, listOf("store_sales")),
                Triple(
                    "SELECT r.shopper_id FROM obj.query.shopper_revenue r",
                    SchemaCode.DB,
                    listOf("sale", "shopper"),
                ),
            )) {
                withClue("$stated: $sql") {
                    val failure =
                        strict(sql, stated, model = SavedRefs.model).shouldBeInstanceOf<ParseResult.Failure>()
                    failure.code shouldBe "lane_mismatch"
                    names.forEach { failure.message shouldContain it }
                }
            }
            strict(
                "SELECT h.buy_potential FROM household h, obj.query.store_revenue r WHERE h.household_id > 0",
                SchemaCode.ER,
                model = SavedRefs.model,
            ).shouldBeInstanceOf<ParseResult.Failure>().code shouldBe "mixed_lanes"
        }

        "H3h — the lane read only in a sub-query (no FROM of its own), strict: Success" {
            strict("SELECT (SELECT SUM(sale.revenue) FROM sale) AS total", SchemaCode.ER)
                .shouldBeInstanceOf<ParseResult.Success>()
            strict("SELECT (SELECT COUNT(*) FROM store_sales) AS n", SchemaCode.DB)
                .shouldBeInstanceOf<ParseResult.Success>()
            // the other lane only in the sub-query is still the other lane
            strict("SELECT (SELECT COUNT(*) FROM db.dbo.store_sales) AS n", SchemaCode.ER)
                .shouldBeInstanceOf<ParseResult.Failure>()
                .code shouldBe "lane_mismatch"
        }

        "H3i — a body that reads nothing, strict: lane_mismatch (a saved query reads its lane)" {
            for (stated in listOf(SchemaCode.ER, SchemaCode.DB)) {
                val failure = strict("SELECT 1 AS n", stated).shouldBeInstanceOf<ParseResult.Failure>()
                failure.code shouldBe "lane_mismatch"
                failure.message shouldContain "names no"
            }
        }

        "H4 — a logical query with a parameter: logical_query_parameters" {
            strict(
                "SELECT sale.revenue FROM sale WHERE sale.store_id = {store}",
                SchemaCode.ER,
                parameters = listOf(SqlParam(name = "store", type = "int", value = "5")),
            ).shouldBeInstanceOf<ParseResult.Failure>().code shouldBe "logical_query_parameters"
        }

        "H4b — lenient callers are untouched: the same mixed body without strictLane still fails as a lookup error" {
            val r =
                Translator(base).parseToRelNode(
                    "SELECT sale.revenue, item.product_name FROM sale JOIN item ON sale.product_id = item.product_id",
                    Language.SQL,
                    targetSchema = SchemaCode.ER,
                    sourceSchema = SchemaCode.ER,
                )
            r.shouldBeInstanceOf<ParseResult.Failure>().code shouldBe "validation_failed"
        }

        // ---- S3: EXPAND_LOGICAL_BODIES (L-8…L-10, L-12) ----
        // P = the physical plan the ttr query service produces today: the two-half pipeline (ER, then REL_NODE
        // re-entry to DB) with the option off. L-10: the same pipeline with the option on yields P byte-for-byte.

        "H5 — option off: the physical plan P; the single-call DB path gives the same bytes here" {
            val stacked = HartlandStackModel.stacked()
            val p = Corpus.p(stacked, Corpus.STACKED_QUERY)
            HartlandStackModel.erScans(p).shouldBeEmpty()
            HartlandStackModel.tableScans(p).map { it.name } shouldContainExactlyInAnyOrder
                listOf("store_sales", "customer")
            Corpus.stamped(p).shouldBeEmpty()
            Corpus.physical(stacked, Corpus.STACKED_QUERY) shouldBeBytes p
        }

        "H6 — option on, ER target: the body expanded in place under a boundary Project stamped with the entity" {
            val stacked = HartlandStackModel.stacked()
            val plan = Corpus.er(stacked, Corpus.STACKED_QUERY, expand = true)
            HartlandStackModel.tableScans(plan).shouldBeEmpty()
            HartlandStackModel.erScans(plan).map { it.name } shouldContainExactlyInAnyOrder listOf("sale", "shopper")
            val boundary = Corpus.stamped(plan).single().project
            boundary.expandedFrom shouldBe HartlandStackModel.shopperRevenue.qname
            // alias-at-boundary: each attribute of the stacked entity ← the body's output column it binds
            boundary.expressionsList.map { it.alias } shouldBe
                listOf("shopper_id", "last_name", "current_household", "total_revenue")
            boundary.expressionsList.map { it.expression.columnRef.name } shouldBe
                listOf("shopper_id", "last_name", "current_household", "revenue")
            // the boundary's input is the stored body, verbatim
            boundary.input shouldBeBytes
                HartlandStackModel.stacked().savedQueryBody(HartlandStackModel.revenueQuery).planNode
        }

        "H7 — H6 re-entered REL_NODE → DB: the physical plan is byte-identical to P (L-10)" {
            val stacked = HartlandStackModel.stacked()
            val p = Corpus.p(stacked, Corpus.STACKED_QUERY)
            val viaExpansion = Corpus.reenter(stacked, Corpus.er(stacked, Corpus.STACKED_QUERY, expand = true))
            Corpus.writeGolden("h7-physical-plan.txtpb", p)
            viaExpansion shouldBeBytes p
            Corpus.stamped(viaExpansion).shouldBeEmpty()
            Corpus.sha256(viaExpansion) shouldBe Corpus.goldenSha("h7-physical-plan.txtpb")
        }

        "H7b — option on with a DB target is a no-op (the physical half inlines the bodies itself)" {
            val stacked = HartlandStackModel.stacked()
            Corpus.physical(stacked, Corpus.STACKED_QUERY, expand = true) shouldBeBytes
                Corpus.physical(stacked, Corpus.STACKED_QUERY)
        }

        "H8 — a two-level stack, option on: both boundaries stamped, the inner one inside the outer; L-10 holds" {
            val twice = HartlandStackModel.stackedTwice(level2Sql = HartlandStackModel.HOUSEHOLDERS_SQL)
            val plan = Corpus.er(twice, Corpus.TWICE_QUERY, expand = true)
            HartlandStackModel.tableScans(plan).shouldBeEmpty()
            HartlandStackModel.erScans(plan).map { it.name } shouldContainExactlyInAnyOrder listOf("sale", "shopper")
            Corpus.stamped(plan).map { it.project.expandedFrom.name } shouldBe listOf("big_spender", "shopper_revenue")
            Corpus.reenter(twice, plan) shouldBeBytes Corpus.p(twice, Corpus.TWICE_QUERY)
        }

        "H8b — a join onto the stacked entity: conditioned from its join pairs before expansion; L-10 holds" {
            val stacked = HartlandStackModel.stacked()
            val plan = Corpus.er(stacked, Corpus.JOIN_QUERY, expand = true)
            HartlandStackModel.erScans(plan).map { it.name } shouldContainExactlyInAnyOrder
                listOf("sale", "shopper", "household")
            Corpus.stamped(plan) shouldHaveSize 1
            Corpus.reenter(stacked, plan) shouldBeBytes Corpus.p(stacked, Corpus.JOIN_QUERY)
        }

        "H8c — an aggregate straight over the stacked entity, and a projection straight over it: L-10 holds" {
            val stacked = HartlandStackModel.stacked()
            for (sql in listOf(
                "SELECT SUM(shopper_revenue.total_revenue) AS t FROM shopper_revenue",
                "SELECT shopper_revenue.last_name FROM shopper_revenue",
                "SELECT shopper_revenue.last_name, shopper_revenue.total_revenue * 2 AS doubled FROM shopper_revenue",
                "SELECT COUNT(*) AS n FROM shopper_revenue",
                "SELECT DISTINCT shopper_revenue.shopper_id, shopper_revenue.last_name, " +
                    "shopper_revenue.current_household, shopper_revenue.total_revenue FROM shopper_revenue",
                "SELECT household.buy_potential, (SELECT COUNT(*) FROM shopper_revenue) AS n FROM household",
            )) {
                withClue(sql) {
                    val plan = Corpus.er(stacked, sql, expand = true)
                    Corpus.stamped(plan) shouldHaveSize 1
                    Corpus.reenter(stacked, plan) shouldBeBytes Corpus.p(stacked, sql)
                }
            }
        }

        "H8d — COUNT(*) straight over a two-level stack: both stamps kept on ER → ER re-entry; L-10 holds" {
            val twice = HartlandStackModel.stackedTwice(level2Sql = HartlandStackModel.HOUSEHOLDERS_SQL)
            val sql = "SELECT COUNT(*) AS n FROM big_spender"
            val plan = Corpus.er(twice, sql, expand = true)
            Corpus.stamped(plan).map { it.project.expandedFrom.name } shouldBe listOf("big_spender", "shopper_revenue")
            Corpus
                .stamped(Corpus.reenter(twice, plan, target = SchemaCode.ER, expand = true))
                .map { it.project.expandedFrom.name } shouldBe listOf("big_spender", "shopper_revenue")
            Corpus.reenter(twice, plan) shouldBeBytes Corpus.p(twice, sql)
        }

        "H8e — expressions in the select list straight over the stacked entity: L-10 holds" {
            // the Project above a boundary is simplified and aliased like the one the option-off path builds
            // over the entity scan (RelBuilder, merging off)
            val stacked = HartlandStackModel.stacked()
            val r = "shopper_revenue"
            for (expr in listOf(
                "CASE WHEN $r.shopper_id BETWEEN 1 AND 10 THEN 1 ELSE 0 END",
                "CASE WHEN $r.shopper_id IN (1, 2, 3) THEN 1 ELSE 0 END",
                "CASE WHEN 1 = 1 THEN $r.last_name ELSE 'x' END",
                "COALESCE($r.last_name, $r.last_name)",
                "CAST($r.shopper_id AS INT)",
                "$r.shopper_id + CAST(1 AS INT)",
                "CASE WHEN NOT (NOT ($r.shopper_id > 1)) THEN 1 ELSE 0 END",
                "CASE WHEN $r.shopper_id > 1 AND $r.shopper_id > 1 THEN 1 ELSE 0 END",
                "CASE WHEN $r.shopper_id >= 1 AND $r.shopper_id <= 10 THEN 1 ELSE 0 END",
                "CASE WHEN $r.last_name IS NULL OR $r.last_name = 'a' OR $r.last_name = 'b' THEN 1 ELSE 0 END",
            )) {
                val sql = "SELECT $r.last_name, $expr AS b FROM $r"
                withClue(sql) {
                    val plan = Corpus.er(stacked, sql, expand = true)
                    Corpus.stamped(plan) shouldHaveSize 1
                    Corpus.reenter(stacked, plan) shouldBeBytes Corpus.p(stacked, sql)
                }
            }
        }

        "H8f — a limit straight over a stacked entity whose body ends in ORDER BY stays above the boundary: L-10" {
            // SQL keeps a Project between the two (`SELECT *`); a REL_NODE / TransDSL plan need not — RelBuilder
            // would push such a limit into the body's Sort
            val ordered =
                HartlandStackModel.stacked(
                    body =
                        HartlandStackModel.parseEr(
                            HartlandJoinModel.handle(),
                            HartlandStackModel.REVENUE_SQL + " ORDER BY SUM(sale.revenue) DESC",
                        ),
                )
            val sql = "SELECT TOP 5 * FROM shopper_revenue"

            fun limitOverInput(
                plan: PlanNode,
                offset: Long?,
                limit: Long?,
            ): PlanNode {
                plan.nodeCase shouldBe PlanNode.NodeCase.LIMIT_OFFSET
                val lo = plan.limitOffset.toBuilder().setInput(plan.limitOffset.input.project.input)
                lo.clearOffset().clearLimit()
                offset?.let { lo.setOffset(it) }
                limit?.let { lo.setLimit(it) }
                return plan.toBuilder().setLimitOffset(lo).build()
            }
            for ((offset, limit) in listOf(null to 5L, 2L to 5L, 2L to null)) {
                withClue("offset=$offset limit=$limit") {
                    val on = limitOverInput(Corpus.er(ordered, sql, expand = true), offset, limit)
                    on.limitOffset.input.project
                        .hasExpandedFrom() shouldBe true
                    val off = limitOverInput(Corpus.er(ordered, sql, expand = false), offset, limit)
                    off.limitOffset.input.nodeCase shouldBe PlanNode.NodeCase.SCAN
                    val physical = Corpus.reenter(ordered, on)
                    physical.nodeCase shouldBe PlanNode.NodeCase.LIMIT_OFFSET
                    physical shouldBeBytes Corpus.reenter(ordered, off)
                }
            }
        }

        "H9 — an entity on a DB-lane query (the __filter shape), option on: not expanded in the ER half" {
            val queryBacked = HartlandJoinModel.handleQueryBacked(relations = HartlandJoinModel.relations)
            val sql = "SELECT product.product_name FROM product"
            val plan = Corpus.er(queryBacked, sql, expand = true)
            HartlandStackModel.erScans(plan).map { it.name } shouldBe listOf("product")
            HartlandStackModel.tableScans(plan).shouldBeEmpty()
            Corpus.stamped(plan).shouldBeEmpty()
            plan shouldBeBytes Corpus.er(queryBacked, sql, expand = false)
            // … and inlined at MAP_TO_PHYSICAL, exactly as with the option off
            Corpus.reenter(queryBacked, plan) shouldBeBytes Corpus.p(queryBacked, sql)
        }

        "H10 — a self-referencing stack, option on: entity_query_mapping_cycle" {
            val loop = HartlandJoinModel.er("loop")
            val loopQuery = HartlandStackModel.erQuery("loop_q")
            val entity = ModelEntity(loop, listOf(ModelAttribute("shopper_id", SurfaceType.INT)))

            fun model(bodies: Map<QualifiedName, SavedQueryBody>) =
                InMemoryModelHandle(
                    tables = HartlandJoinModel.tables,
                    entities = HartlandJoinModel.entities + entity,
                    relations = HartlandJoinModel.relations,
                    entityMappings = HartlandJoinModel.entityMappings + (loop to EntityMapping.ToQuery(loopQuery)),
                    savedQueries = listOf(ModelSavedQuery(loopQuery)),
                    savedQueryBodies = bodies,
                    attributeRenames = HartlandJoinModel.renames,
                )
            val body = HartlandStackModel.parseEr(model(emptyMap()), "SELECT loop.shopper_id FROM loop")
            val looped = model(mapOf(loopQuery to SavedQueryBody(body, emptyList(), emptyList())))
            Translator(looped)
                .parseToRelNode(
                    "SELECT loop.shopper_id FROM loop",
                    Language.SQL,
                    targetSchema = SchemaCode.ER,
                    sourceSchema = SchemaCode.ER,
                    expandLogicalBodies = true,
                ).shouldBeInstanceOf<ParseResult.Failure>()
                .code shouldBe "entity_query_mapping_cycle"
        }

        "H10b — an indirect stack loop (a on b, b on a), option on: entity_query_mapping_cycle" {
            val a = HartlandJoinModel.er("loop_a")
            val b = HartlandJoinModel.er("loop_b")
            val qa = HartlandStackModel.erQuery("loop_a_q")
            val qb = HartlandStackModel.erQuery("loop_b_q")
            val attrs = listOf(ModelAttribute("shopper_id", SurfaceType.INT))

            fun model(bodies: Map<QualifiedName, SavedQueryBody>) =
                InMemoryModelHandle(
                    tables = HartlandJoinModel.tables,
                    entities = HartlandJoinModel.entities + ModelEntity(a, attrs) + ModelEntity(b, attrs),
                    relations = HartlandJoinModel.relations,
                    entityMappings =
                        HartlandJoinModel.entityMappings + (a to EntityMapping.ToQuery(qa)) +
                            (b to EntityMapping.ToQuery(qb)),
                    savedQueries = listOf(ModelSavedQuery(qa), ModelSavedQuery(qb)),
                    savedQueryBodies = bodies,
                    attributeRenames = HartlandJoinModel.renames,
                )
            val bodyA = HartlandStackModel.parseEr(model(emptyMap()), "SELECT loop_b.shopper_id FROM loop_b")
            val bodyB = HartlandStackModel.parseEr(model(emptyMap()), "SELECT loop_a.shopper_id FROM loop_a")
            val looped =
                model(
                    mapOf(
                        qa to SavedQueryBody(bodyA, emptyList(), emptyList()),
                        qb to SavedQueryBody(bodyB, emptyList(), emptyList()),
                    ),
                )
            Translator(looped)
                .parseToRelNode(
                    "SELECT loop_a.shopper_id FROM loop_a",
                    Language.SQL,
                    targetSchema = SchemaCode.ER,
                    sourceSchema = SchemaCode.ER,
                    expandLogicalBodies = true,
                ).shouldBeInstanceOf<ParseResult.Failure>()
                .code shouldBe "entity_query_mapping_cycle"
        }

        "H12 — option on, a plan with no stacked entity: byte-identical to option off" {
            val stacked = HartlandStackModel.stacked()
            val sql =
                "SELECT household.buy_potential, SUM(sale.revenue) AS r FROM sale JOIN shopper JOIN household " +
                    "GROUP BY household.buy_potential"
            Corpus.er(stacked, sql, expand = true) shouldBeBytes Corpus.er(stacked, sql, expand = false)
        }

        "H12b — the stage is idempotent: re-applied to an expanded plan it changes nothing" {
            val stacked = HartlandStackModel.stacked()
            val expanded = Corpus.er(stacked, Corpus.STACKED_QUERY, expand = true)
            ExpandLogicalBodies
                .apply(expanded, stacked)
                .shouldBeInstanceOf<ExpandLogicalBodiesResult.Success>()
                .plan shouldBeBytes expanded
        }

        "H12c — an ER → ER re-entry keeps the stamp (carried through Calcite as a hint)" {
            val stacked = HartlandStackModel.stacked()
            val expanded = Corpus.er(stacked, Corpus.STACKED_QUERY, expand = true)
            val again = Corpus.reenter(stacked, expanded, target = SchemaCode.ER, expand = true)
            Corpus.stamped(again).map { it.project.expandedFrom } shouldBe
                listOf(HartlandStackModel.shopperRevenue.qname)
            HartlandStackModel.erScans(again).map { it.name } shouldContainExactlyInAnyOrder listOf("sale", "shopper")
        }

        "H12d — a stacked scan that declares no columns is refused, not passed on unexpanded" {
            // left as Scan(ER, stacked), the entities its body reads would escape the validator's ER pass
            val bare =
                PlanNode
                    .newBuilder()
                    .setScan(
                        org.tatrman.plan.v1.ScanNode
                            .newBuilder()
                            .setObject(HartlandStackModel.shopperRevenue.qname),
                    ).build()
            ExpandLogicalBodies
                .apply(bare, HartlandStackModel.stacked())
                .shouldBeInstanceOf<ExpandLogicalBodiesResult.Failure>()
                .code shouldBe "entity_query_no_columns"
        }

        "outputColumnNames — the stored body's output names (what a stacked entity's bindings are checked against)" {
            val body = HartlandStackModel.parseEr(base, HartlandStackModel.REVENUE_SQL)
            Translator.outputColumnNames(body) shouldBe
                listOf("shopper_id", "last_name", "current_household", "revenue")
        }
    })

private infix fun PlanNode.shouldBeBytes(expected: PlanNode) {
    if (!toByteArray().contentEquals(expected.toByteArray())) {
        System.getenv("LQ_DUMP_DIR")?.takeIf { it.isNotBlank() }?.let { dir ->
            java.io.File(dir).mkdirs()
            val n =
                java.io
                    .File(dir)
                    .list()!!
                    .size / 2
            java.io.File(dir, "$n-actual.txtpb").writeText(toString())
            java.io.File(dir, "$n-expected.txtpb").writeText(expected.toString())
        }
        // the text forms make the difference readable
        toString() shouldBe expected.toString()
        error("plans differ in bytes but not in text")
    }
}

/**
 * H3g — the Hartland model plus two saved queries reachable as `obj.query.<name>` (an OBJ-schema saved query is
 * what the SQL surface resolves): `store_revenue`, a db body over the tables, and `shopper_revenue`, a logical body
 * over the entities ([HartlandStackModel.REVENUE_SQL], as a metadata worker stores it).
 */
private object SavedRefs {
    private fun obj(name: String): QualifiedName =
        QualifiedName
            .newBuilder()
            .setSchemaCode(SchemaCode.OBJ)
            .setNamespace("query")
            .setName(name)
            .build()

    private fun outputs(vararg names: String): List<AttributeOrColumnRef> =
        names.map { n ->
            AttributeOrColumnRef.Col(
                QualifiedName
                    .newBuilder()
                    .setSchemaCode(SchemaCode.DB)
                    .setNamespace("dbo")
                    .setName(n)
                    .build(),
            )
        }

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
            savedQueries = listOf(ModelSavedQuery(obj("store_revenue")), ModelSavedQuery(obj("shopper_revenue"))),
            savedQueryBodies =
                mapOf(
                    obj("store_revenue") to
                        SavedQueryBody(
                            dbBody,
                            emptyList(),
                            outputs("shopper_id", "last_name", "current_household", "revenue"),
                        ),
                    obj("shopper_revenue") to
                        SavedQueryBody(
                            HartlandStackModel.parseEr(base, HartlandStackModel.REVENUE_SQL),
                            emptyList(),
                            outputs("shopper_id", "last_name", "current_household", "revenue"),
                        ),
                ),
            attributeRenames = HartlandJoinModel.renames,
        )
}

private object Corpus {
    /** An ordinary question over the stacked entity (C-3c's shape, the filter on an INT attribute). */
    const val STACKED_QUERY: String =
        "SELECT shopper_revenue.last_name, shopper_revenue.total_revenue FROM shopper_revenue " +
            "WHERE shopper_revenue.shopper_id > 1000"

    /** A question over the level-2 stacked entity. */
    const val TWICE_QUERY: String = "SELECT big_spender.last_name FROM big_spender"

    /** A bare JOIN onto the stacked entity, conditioned from its join pairs (C-1a). */
    const val JOIN_QUERY: String =
        "SELECT household.buy_potential, SUM(shopper_revenue.total_revenue) AS revenue " +
            "FROM shopper_revenue JOIN household GROUP BY household.buy_potential"

    fun er(
        model: ModelHandle,
        sql: String,
        expand: Boolean,
    ): PlanNode =
        Translator(model)
            .parseToRelNode(
                sql,
                Language.SQL,
                targetSchema = SchemaCode.ER,
                sourceSchema = SchemaCode.ER,
                expandLogicalBodies = expand,
            ).let {
                check(it is ParseResult.Success) { "ER parse failed: $it" }
                it.plan
            }

    fun physical(
        model: ModelHandle,
        sql: String,
        expand: Boolean = false,
    ): PlanNode =
        Translator(model)
            .parseToRelNode(
                sql,
                Language.SQL,
                targetSchema = SchemaCode.DB,
                sourceSchema = SchemaCode.ER,
                expandLogicalBodies = expand,
            ).let {
                check(it is ParseResult.Success) { "DB parse failed: $it" }
                it.plan
            }

    /** P — the physical plan of the two-half pipeline with the option off (what the ttr query service runs). */
    fun p(
        model: ModelHandle,
        sql: String,
    ): PlanNode = reenter(model, er(model, sql, expand = false))

    /** The two-half pipeline's second call: REL_NODE re-entry of an ER plan. */
    fun reenter(
        model: ModelHandle,
        erPlan: PlanNode,
        target: SchemaCode = SchemaCode.DB,
        expand: Boolean = false,
    ): PlanNode =
        Translator(model)
            .parseToRelNode(
                String(erPlan.toByteArray(), Charsets.ISO_8859_1),
                Language.REL_NODE,
                targetSchema = target,
                expandLogicalBodies = expand,
            ).let {
                check(it is ParseResult.Success) { "REL_NODE re-entry failed: $it" }
                it.plan
            }

    /** Every stamped boundary Project, pre-order (outer first). */
    fun stamped(plan: PlanNode): List<PlanNode> =
        HartlandStackModel
            .nodes(
                plan,
            ).filter { it.nodeCase == PlanNode.NodeCase.PROJECT && it.project.hasExpandedFrom() }

    fun sha256(plan: PlanNode): String =
        java.security.MessageDigest
            .getInstance("SHA-256")
            .digest(plan.toByteArray())
            .joinToString("") { "%02x".format(it) }

    /** `LQ_WRITE_GOLDEN=<dir>` writes [plan]'s text form there — to be read by hand before it is checked in. */
    fun writeGolden(
        name: String,
        plan: PlanNode,
    ) {
        val dir = System.getenv("LQ_WRITE_GOLDEN")?.takeIf { it.isNotBlank() } ?: return
        java.io.File(dir).mkdirs()
        java.io.File(dir, name).writeText(plan.toString())
    }

    /**
     * The sha256 of a golden plan's bytes. The golden is the text form (`*.txtpb`, reviewable by hand); the sha
     * is over the binary form it parses to. `LQ_WRITE_GOLDEN=<dir>` regenerates — never blindly (rules §6).
     */
    fun goldenSha(name: String): String {
        val text =
            checkNotNull(Corpus::class.java.getResource("/lq/$name")) { "golden /lq/$name missing" }.readText()
        val builder = PlanNode.newBuilder()
        com.google.protobuf.TextFormat
            .getParser()
            .merge(text, builder)
        return sha256(builder.build())
    }
}
