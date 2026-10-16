// SPDX-License-Identifier: Apache-2.0
package org.tatrman.translator.lq

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
        ): ParseResult =
            Translator(base).parseToRelNode(
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
            )) {
                val plan = Corpus.er(stacked, sql, expand = true)
                Corpus.stamped(plan) shouldHaveSize 1
                Corpus.reenter(stacked, plan) shouldBeBytes Corpus.p(stacked, sql)
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

    fun clearStamps(plan: PlanNode): PlanNode =
        if (plan.nodeCase == PlanNode.NodeCase.PROJECT) {
            plan
                .toBuilder()
                .setProject(
                    plan.project
                        .toBuilder()
                        .clearExpandedFrom()
                        .setInput(clearStamps(plan.project.input)),
                ).build()
        } else {
            org.tatrman.translator.joiner.JoinerPlanWalker
                .rewriteChildren(plan, ::clearStamps)
        }

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
