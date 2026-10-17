// SPDX-License-Identifier: Apache-2.0
package org.tatrman.translator.framework

import org.tatrman.plan.v1.PlanNode
import org.tatrman.plan.v1.QualifiedName
import org.tatrman.plan.v1.SchemaCode
import org.tatrman.translate.v1.Language
import org.tatrman.translator.framework.HartlandJoinModel.attr
import org.tatrman.translator.framework.HartlandJoinModel.er
import org.tatrman.translator.joiner.JoinerPlanWalker
import org.tatrman.translator.orchestrator.ParseResult
import org.tatrman.translator.orchestrator.Translator

/**
 * LQ (logical queries, project `ttr-translator/logical-queries`) — the [HartlandJoinModel] slice plus entities
 * **stacked** on logical queries: saved queries written over entities, stored at the ER level (contract L-3),
 * with an entity mapped `ToQuery` onto each.
 *
 *  - `shopper_revenue` on `er.query.shopper_revenue` = [REVENUE_SQL] (`sale JOIN shopper`, grouped per
 *    shopper). Its attribute `total_revenue` binds to the query's output column `revenue`; the others keep
 *    their names. It relates to `household` through `join:` pairs only ([revenueHousehold]) — no FK anywhere.
 *  - `big_spender` on `er.query.big_spenders` = [BIG_SPENDERS_SQL], a logical query over `shopper_revenue`
 *    itself — the two-level stack.
 *
 * The bodies are parsed the way a metadata worker does it (stated ER, target ER) and handed to the model as
 * bytes — what a snapshot serves. Published with the test fixtures so consumers (tatrman-server's validate /
 * query / metadata specs) build the same estate.
 */
object HartlandStackModel {
    /** Revenue per shopper, written over entities — the body of `er.query.shopper_revenue`. */
    const val REVENUE_SQL: String =
        "SELECT shopper.shopper_id, shopper.last_name, shopper.current_household, SUM(sale.revenue) AS revenue " +
            "FROM sale JOIN shopper " +
            "GROUP BY shopper.shopper_id, shopper.last_name, shopper.current_household"

    /** The same query written against the tables (a physical body). */
    const val REVENUE_SQL_PHYSICAL: String =
        "SELECT customer.shopper_id, customer.last_name, customer.current_household, " +
            "SUM(store_sales.revenue) AS revenue " +
            "FROM store_sales JOIN customer ON store_sales.shopper_id = customer.shopper_id " +
            "GROUP BY customer.shopper_id, customer.last_name, customer.current_household"

    /** The level-2 logical query, over the stacked entity — the body of `er.query.big_spenders`. */
    const val BIG_SPENDERS_SQL: String =
        "SELECT shopper_revenue.shopper_id, shopper_revenue.last_name, shopper_revenue.total_revenue " +
            "FROM shopper_revenue WHERE shopper_revenue.total_revenue > 1000"

    /**
     * A level-2 body with no literal: shoppers with a household. (A literal compared with a FLOAT attribute —
     * [BIG_SPENDERS_SQL] — re-encodes differently after a Calcite round trip, `CAST(int)` → FLOAT literal: a
     * pre-existing encoder trait outside LQ, recorded in the CHANGELOG; byte-identity cases avoid it.)
     */
    const val HOUSEHOLDERS_SQL: String =
        "SELECT shopper_revenue.shopper_id, shopper_revenue.last_name, shopper_revenue.total_revenue " +
            "FROM shopper_revenue WHERE shopper_revenue.current_household IS NOT NULL"

    /** `er.query.<name>` — the ER-lane saved-query qname (L-1). */
    fun erQuery(name: String): QualifiedName =
        QualifiedName
            .newBuilder()
            .setSchemaCode(SchemaCode.ER)
            .setNamespace("query")
            .setName(name)
            .build()

    val revenueQuery: QualifiedName = erQuery("shopper_revenue")
    val bigSpendersQuery: QualifiedName = erQuery("big_spenders")

    /** Declared attributes (LQ-3); `total_revenue` is bound to the output column `revenue`. */
    val shopperRevenue: ModelEntity =
        ModelEntity(
            er("shopper_revenue"),
            listOf(
                ModelAttribute("shopper_id", SurfaceType.INT, nullable = false, isKey = true),
                ModelAttribute("last_name", SurfaceType.TEXT),
                ModelAttribute("current_household", SurfaceType.INT),
                ModelAttribute("total_revenue", SurfaceType.FLOAT),
            ),
        )

    val bigSpender: ModelEntity =
        ModelEntity(
            er("big_spender"),
            listOf(
                ModelAttribute("shopper_id", SurfaceType.INT, nullable = false, isKey = true),
                ModelAttribute("last_name", SurfaceType.TEXT),
                ModelAttribute("total_revenue", SurfaceType.FLOAT),
            ),
        )

    /** `join:` pairs only — no FK exists anywhere in the model (C-1). */
    val revenueHousehold: ModelRelation =
        ModelRelation(
            fromEntity = er("shopper_revenue"),
            toEntity = er("household"),
            joinPairs = listOf(attr("shopper_revenue", "current_household") to attr("household", "household_id")),
        )

    /** The stacked entities' attribute → output-column bindings (only the renamed one). */
    val stackRenames: Map<QualifiedName, Map<String, String>> =
        mapOf(shopperRevenue.qname to mapOf("total_revenue" to "revenue"))

    /**
     * A logical query's body as a metadata worker stores it: parsed with stated ER / target ER, entity scans
     * kept, bare joins conditioned — then serialized, since a snapshot serves bytes.
     */
    fun parseEr(
        model: ModelHandle,
        sql: String,
    ): PlanNode {
        val parsed =
            Translator(
                model,
            ).parseToRelNode(sql, Language.SQL, targetSchema = SchemaCode.ER, sourceSchema = SchemaCode.ER)
        check(parsed is ParseResult.Success) { "logical query did not parse: $parsed" }
        return PlanNode.parseFrom(parsed.plan.toByteArray())
    }

    /**
     * [base] + `shopper_revenue` stacked on `er.query.shopper_revenue`. The body is parsed against [bodyModel]
     * (default: the base model — the stacked entity does not exist yet when its own query is parsed).
     */
    fun stacked(
        base: InMemoryModelHandle = HartlandJoinModel.handle(),
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
            attributeRenames = HartlandJoinModel.renames + stackRenames,
        )

    /**
     * [level1] + `big_spender` stacked on `er.query.big_spenders`, a logical query over `shopper_revenue` —
     * [BIG_SPENDERS_SQL] unless [level2Sql] says otherwise.
     */
    fun stackedTwice(
        level1: InMemoryModelHandle = stacked(),
        level2Sql: String = BIG_SPENDERS_SQL,
    ): InMemoryModelHandle {
        val body = parseEr(level1, level2Sql)
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
            attributeRenames = HartlandJoinModel.renames + stackRenames,
        )
    }

    /** Every node of [plan], pre-order — plan children and expression subqueries alike. */
    fun nodes(plan: PlanNode): List<PlanNode> {
        val out = mutableListOf<PlanNode>()
        JoinerPlanWalker.forEachNode(plan) { out += it }
        return out
    }

    fun erScans(plan: PlanNode): List<QualifiedName> =
        nodes(plan)
            .filter { it.nodeCase == PlanNode.NodeCase.SCAN && it.scan.getObject().schemaCode == SchemaCode.ER }
            .map { it.scan.getObject() }

    fun tableScans(plan: PlanNode): List<QualifiedName> =
        nodes(plan).filter { it.nodeCase == PlanNode.NodeCase.TABLE_SCAN }.map { it.tableScan.table }
}
