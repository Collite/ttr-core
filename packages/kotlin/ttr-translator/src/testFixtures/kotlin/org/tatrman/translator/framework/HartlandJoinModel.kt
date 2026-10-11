// SPDX-License-Identifier: Apache-2.0
package org.tatrman.translator.framework

import org.tatrman.plan.v1.PlanNode
import org.tatrman.plan.v1.QualifiedName
import org.tatrman.plan.v1.SchemaCode

/**
 * MJ-P0·S1 — the "model joins" fixture: a slice of the ER model of the Hartland demo database (TPC-DS, the
 * US world), with logical entity names distinct from their TPC-DS tables and relations following the TPC-DS
 * foreign keys.
 *
 * Shape that matters for the acceptance corpus (project MJ `plan/plan.md §4`):
 *  - `sale → shopper`, `shopper → household`, `sale → product`: the hero chain `s JOIN sh JOIN h JOIN p`
 *    resolves only if the second join is matched against `shopper`, not `sale`;
 *  - `sale` has NO relation to `household` (H7 — no path inference in v1);
 *  - `shopper → calendar` twice (first purchase + first shipment date) — the ambiguous case (H4);
 *  - both `sale → address` and `shopper → address` (H6 — no proximity tie-break);
 *  - a composite relation `order_return → order_line` on `(order_number, product_id)` — a return names the
 *    order line it returns (H9);
 *  - every entity maps to a DB table named after its TPC-DS table; `household.household_id` is renamed on the
 *    way (`hd_demo_sk`) so the MAP_TO_PHYSICAL alias-at-boundary path is exercised by a join key.
 */
object HartlandJoinModel {
    fun er(name: String): QualifiedName =
        QualifiedName
            .newBuilder()
            .setSchemaCode(SchemaCode.ER)
            .setNamespace("entity")
            .setName(name)
            .build()

    fun attr(
        entity: String,
        attribute: String,
    ): QualifiedName =
        QualifiedName
            .newBuilder()
            .setSchemaCode(SchemaCode.ER)
            .setNamespace("attribute")
            .setName("$entity.$attribute")
            .build()

    fun db(name: String): QualifiedName =
        QualifiedName
            .newBuilder()
            .setSchemaCode(SchemaCode.DB)
            .setNamespace("dbo")
            .setName(name)
            .build()

    private fun int(
        name: String,
        key: Boolean = false,
    ) = ModelAttribute(name, SurfaceType.INT, nullable = !key, isKey = key)

    private fun text(name: String) = ModelAttribute(name, SurfaceType.TEXT)

    private fun num(name: String) = ModelAttribute(name, SurfaceType.FLOAT)

    val sale =
        ModelEntity(
            er("sale"),
            listOf(
                int("shopper_id"),
                int("address_id"),
                int("store_id"),
                int("product_id"),
                int("ticket_number"),
                int("promo_id"),
                text("sale_month"),
                num("revenue"),
                num("quantity"),
            ),
        )
    val shopper =
        ModelEntity(
            er("shopper"),
            listOf(
                int("shopper_id", key = true),
                text("last_name"),
                int("current_household"),
                int("address_id"),
                int("first_purchase_date"),
                int("first_shipment_date"),
                text("birth_country"),
            ),
        )
    val household =
        ModelEntity(
            er("household"),
            listOf(
                int("household_id", key = true),
                text("buy_potential"),
                text("description"),
            ),
        )
    val product =
        ModelEntity(
            er("product"),
            listOf(
                int("product_id", key = true),
                text("product_name"),
            ),
        )
    val address =
        ModelEntity(
            er("address"),
            listOf(
                int("address_id", key = true),
                text("city"),
                text("zip"),
            ),
        )
    val calendar =
        ModelEntity(
            er("calendar"),
            listOf(
                int("date_id", key = true),
                text("day_name"),
            ),
        )
    val promo =
        ModelEntity(
            er("promo"),
            listOf(
                int("promo_id", key = true),
                text("promo_name"),
                int("product_id"),
                text("purpose"),
            ),
        )

    /** Composite-key pair for H9. */
    val orderLine =
        ModelEntity(
            er("order_line"),
            listOf(int("order_number", key = true), int("product_id", key = true), text("ship_mode")),
        )
    val orderReturn =
        ModelEntity(
            er("order_return"),
            listOf(int("return_id", key = true), int("order_number"), int("product_id"), text("reason")),
        )

    val entities =
        listOf(sale, shopper, household, product, address, calendar, promo, orderLine, orderReturn)

    private fun rel(
        from: String,
        to: String,
        vararg pairs: Pair<String, String>,
    ) = ModelRelation(
        fromEntity = er(from),
        toEntity = er(to),
        joinPairs = pairs.map { (f, t) -> attr(from, f) to attr(to, t) },
    )

    val relations =
        listOf(
            // store_sales: ss_customer_sk, ss_addr_sk, ss_item_sk, ss_promo_sk
            rel("sale", "shopper", "shopper_id" to "shopper_id"),
            rel("sale", "address", "address_id" to "address_id"),
            rel("sale", "product", "product_id" to "product_id"),
            rel("sale", "promo", "promo_id" to "promo_id"),
            // customer: c_current_hdemo_sk, c_current_addr_sk, c_first_sales_date_sk, c_first_shipto_date_sk
            rel("shopper", "household", "current_household" to "household_id"),
            rel("shopper", "address", "address_id" to "address_id"),
            rel("shopper", "calendar", "first_purchase_date" to "date_id"),
            rel("shopper", "calendar", "first_shipment_date" to "date_id"),
            // promotion: p_item_sk
            rel("promo", "product", "product_id" to "product_id"),
            // catalog_returns → catalog_sales on (cr_order_number, cr_item_sk) — composite (H9)
            rel("order_return", "order_line", "order_number" to "order_number", "product_id" to "product_id"),
        )

    // ---- physical side: one table per entity, named after its TPC-DS table; same column names except the
    // ---- renamed household key ----

    private fun table(
        entity: ModelEntity,
        tableName: String,
        renames: Map<String, String> = emptyMap(),
    ): ModelTable =
        ModelTable(
            qname = db(tableName),
            columns =
                entity.attributes.map { a ->
                    ModelColumn(renames[a.name] ?: a.name, a.surfaceType, a.nullable)
                },
        )

    private val tableNames =
        mapOf(
            "sale" to "store_sales",
            "shopper" to "customer",
            "household" to "household_demographics",
            "product" to "item",
            "address" to "customer_address",
            "calendar" to "date_dim",
            "promo" to "promotion",
            "order_line" to "catalog_sales",
            "order_return" to "catalog_returns",
        )

    /** `household.household_id` is stored as `hd_demo_sk` — a join key that crosses a rename. */
    val renames: Map<QualifiedName, Map<String, String>> =
        mapOf(er("household") to mapOf("household_id" to "hd_demo_sk"))

    val tables = entities.map { e -> table(e, tableNames.getValue(e.qname.name), renames[e.qname] ?: emptyMap()) }

    val entityMappings: Map<QualifiedName, EntityMapping> =
        entities.associate { e -> e.qname to EntityMapping.ToTable(db(tableNames.getValue(e.qname.name))) }

    fun handle(): InMemoryModelHandle =
        InMemoryModelHandle(
            tables = tables,
            entities = entities,
            relations = relations,
            entityMappings = entityMappings,
            attributeRenames = renames,
        )

    // ---- the served-estate shape (MJ-P4·S2.6): query-backed entities + FKs derived from the relations ----

    /** FK `store_sales.product_id → item.product_id`, the way the metadata service derives it from the relation. */
    val saleProductFk =
        ModelForeignKey(
            from = listOf(db("store_sales.product_id")),
            to = listOf(db("item.product_id")),
        )

    private val productFilterQuery: QualifiedName =
        QualifiedName
            .newBuilder()
            .setNamespace("query")
            .setName("product__filter")
            .build()

    /**
     * `product` backed by a saved query `SELECT product_id, product_name FROM item WHERE product_id > 0` — MAP_TO_PHYSICAL
     * substitutes the body and wraps it in the alias-at-boundary Project, exactly as on a served estate whose
     * entities are `*__filter` queries (a host's row filters).
     */
    private fun productFilterBody(): PlanNode {
        fun col(name: String) =
            org.tatrman.plan.v1.ColumnRef
                .newBuilder()
                .setName(name)
                .build()

        fun colExpr(name: String) =
            org.tatrman.plan.v1.Expression
                .newBuilder()
                .setColumnRef(col(name))
                .build()
        val scan =
            PlanNode
                .newBuilder()
                .setTableScan(
                    org.tatrman.plan.v1.TableScanNode
                        .newBuilder()
                        .setTable(db("item"))
                        .addOutputColumns(col("product_id"))
                        .addOutputColumns(col("product_name")),
                ).build()
        val zero =
            org.tatrman.plan.v1.Expression
                .newBuilder()
                .setLiteral(
                    org.tatrman.plan.v1.Literal
                        .newBuilder()
                        .setIntValue(0),
                ).build()
        val filter =
            PlanNode
                .newBuilder()
                .setFilter(
                    org.tatrman.plan.v1.FilterNode
                        .newBuilder()
                        .setInput(scan)
                        .setCondition(
                            org.tatrman.plan.v1.Expression
                                .newBuilder()
                                .setFunction(
                                    org.tatrman.plan.v1.FunctionCall
                                        .newBuilder()
                                        .setOperation("gt")
                                        .addOperands(colExpr("product_id"))
                                        .addOperands(zero),
                                ),
                        ),
                ).build()
        val project =
            org.tatrman.plan.v1.ProjectNode
                .newBuilder()
                .setInput(filter)
        listOf("product_id", "product_name").forEach { c ->
            project.addExpressions(
                org.tatrman.plan.v1.NamedExpression
                    .newBuilder()
                    .setExpression(colExpr(c))
                    .setAlias(c),
            )
        }
        return PlanNode.newBuilder().setProject(project).build()
    }

    /**
     * The estate as a metadata service serves it: `product` query-backed, FKs derived from the relations, and —
     * [relations] defaulted to none — a snapshot whose relation detail never reached the translator
     * (the metadata gap fixed upstream in MJ-P4·S2.5), so every entity join must be filled from the FK.
     */
    fun handleQueryBacked(relations: List<ModelRelation> = emptyList()): InMemoryModelHandle =
        InMemoryModelHandle(
            tables = tables,
            foreignKeys = listOf(saleProductFk),
            entities = entities,
            relations = relations,
            entityMappings = entityMappings + (er("product") to EntityMapping.ToQuery(productFilterQuery)),
            savedQueries = listOf(ModelSavedQuery(productFilterQuery)),
            savedQueryBodies =
                mapOf(
                    productFilterQuery to SavedQueryBody(productFilterBody(), emptyList(), emptyList()),
                ),
            attributeRenames = renames,
        )

    // ---- the acceptance corpus (project MJ plan.md §4) ----

    /** H1 — the hero chain: four entities, three bare joins, revenue by household buy potential. */
    const val H1_HERO: String =
        "SELECT household.buy_potential, SUM(sale.revenue) AS revenue " +
            "FROM sale JOIN shopper JOIN household JOIN product " +
            "WHERE product.product_name LIKE '%able%' " +
            "GROUP BY household.buy_potential ORDER BY revenue DESC FETCH FIRST 10 ROWS ONLY"

    /** H2 — aliases. */
    const val H2_ALIASES: String =
        "SELECT h.buy_potential, SUM(s.revenue) AS t " +
            "FROM sale s JOIN shopper sh JOIN household h GROUP BY h.buy_potential"

    /** H3 — join type preserved. */
    const val H3_LEFT: String =
        "SELECT sale.sale_month, product.product_name FROM sale LEFT JOIN product"

    /** H10 — the comma form the joiner already handles today (bytes must not change). */
    const val H10_COMMA: String =
        "SELECT sale.sale_month, product.product_name FROM sale, product " +
            "WHERE product.product_name LIKE '%able%'"
}
