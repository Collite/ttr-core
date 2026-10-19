// SPDX-License-Identifier: Apache-2.0
package org.tatrman.translator.lq

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
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
import org.tatrman.translator.framework.ModelForeignKey
import org.tatrman.translator.framework.ModelHandle
import org.tatrman.translator.framework.ModelRelation
import org.tatrman.translator.framework.ModelSavedQuery
import org.tatrman.translator.framework.SavedQueryBody
import org.tatrman.translator.framework.SurfaceType
import org.tatrman.translator.joiner.JoinerWarning
import org.tatrman.translator.orchestrator.ParseResult
import org.tatrman.translator.orchestrator.TranslateResult
import org.tatrman.translator.orchestrator.Translator

/**
 * LQ-T6 (design §9 ruling 5, contract L-30) — a logical query read by another one (`FROM obj.query.x r`) is a
 * **temporary entity**: its columns are the query's output columns, and a column that passes an entity attribute
 * through (as is, renamed, or as a `GROUP BY` key) keeps that attribute's relations. A bare `JOIN` to it is
 * conditioned from them, as between two entities.
 *
 * The model is Hartland with every relation bound to its FK only (no `join:` pairs), which is how the demo estate
 * declares them; the logical queries are served under OBJ the way a metadata worker serves them: the stored ER plan,
 * no declared output columns.
 */
class TemporaryEntitySpec :
    StringSpec({
        fun parse(
            sql: String,
            model: ModelHandle = FkOnlyStack.model,
        ): ParseResult.Success =
            Translator(model)
                .parseToRelNode(sql, Language.SQL, targetSchema = SchemaCode.ER, sourceSchema = SchemaCode.ER)
                .shouldBeInstanceOf<ParseResult.Success>()

        fun translate(
            sql: String,
            model: ModelHandle = FkOnlyStack.model,
        ): String =
            Translator(model)
                .translate(sql, Language.SQL, Language.SQL, sourceSchema = SchemaCode.ER)
                .shouldBeInstanceOf<TranslateResult.Success>()
                .output

        /** The statement the validator saw after the bare joins were decided, quotes dropped. */
        fun joined(result: ParseResult.Success): String =
            result.modelJoinsSql
                .orEmpty()
                .replace("\"", "")
                .replace("`", "")

        "an FK-only relation is inherited through a passed-through GROUP BY key: the bare JOIN is conditioned" {
            val result = parse("SELECT shopper.last_name, r.revenue FROM obj.query.shopper_spend AS r JOIN shopper")
            joined(result) shouldContain "ON r.shopper_id = shopper.shopper_id"
            result.warnings.shouldBeEmpty()
        }

        "the same reader translates to SQL that joins on the FK's columns, not a Cartesian product" {
            val sql = translate("SELECT shopper.last_name, r.revenue FROM obj.query.shopper_spend AS r JOIN shopper")
            sql shouldContain "store_sales"
            sql shouldContain "customer"
            sql.replace("\"", "").replace("[", "").replace("]", "") shouldContain "shopper_id = "
            sql.uppercase() shouldNotContain "CROSS JOIN"
        }

        "a renamed column keeps its attribute's relations" {
            val result = parse("SELECT shopper.last_name, r.revenue FROM obj.query.buyer_spend AS r JOIN shopper")
            joined(result) shouldContain "ON r.buyer = shopper.shopper_id"
            result.warnings.shouldBeEmpty()
        }

        "an aggregate column carries no relation: the join stays JOIN_NO_RELATION" {
            val result = parse("SELECT shopper.last_name, r.revenue FROM obj.query.top_buyer AS r JOIN shopper")
            result.warnings shouldHaveSize 1
            result.warnings.single().shouldBeInstanceOf<JoinerWarning.NoRelation>()
        }

        "two levels: a logical query over a logical query still passes the attribute through" {
            val result = parse("SELECT shopper.last_name, b.revenue FROM obj.query.big_spenders AS b JOIN shopper")
            joined(result) shouldContain "ON b.shopper_id = shopper.shopper_id"
            result.warnings.shouldBeEmpty()
        }

        // The SQL carrier finds two relations and refuses to pick one; the wire carrier, which cannot see a temporary
        // entity, then reports the join on a derived table (⚑T6-2). Either way it is a warning, never a guess.
        "two inherited relations to one entity: not conditioned on a guess, and warned" {
            val result = parse("SELECT address.city, t.revenue FROM obj.query.two_addresses AS t JOIN address")
            joined(result) shouldContain "ON TRUE"
            result.warnings shouldHaveSize 1
            result.warnings.single().shouldBeInstanceOf<JoinerWarning.NoRelation>()
        }

        "⚑T6-1 — a temporary entity that passes an entity's key through relates to that entity on it" {
            val result = parse("SELECT shopper.birth_country FROM obj.query.local_shoppers AS t JOIN shopper")
            joined(result) shouldContain "ON t.shopper_id = shopper.shopper_id"
            result.warnings.shouldBeEmpty()
        }

        "the passed-through key also inherits the relations that point at it (sale → shopper)" {
            val result =
                parse(
                    "SELECT t.last_name, SUM(sale.revenue) AS revenue FROM obj.query.local_shoppers AS t " +
                        "JOIN sale GROUP BY t.last_name",
                )
            joined(result) shouldContain "ON t.shopper_id = sale.shopper_id"
            result.warnings.shouldBeEmpty()
        }

        "#204 — an FK-only join to an entity stacked on a GROUP BY query is conditioned, not a Cartesian product" {
            val sql =
                translate(
                    "SELECT shopper_agg.last_name, SUM(sale.revenue) AS revenue FROM sale JOIN shopper_agg " +
                        "GROUP BY shopper_agg.last_name",
                    FkOnlyStack.withShopperAgg(FkOnlyStack.SHOPPER_AGG_SQL),
                )
            sql.uppercase() shouldNotContain "CROSS JOIN"
            sql.replace("\"", "").replace("[", "").replace("]", "") shouldContain "shopper_id = "
        }

        "#204 — a side the physical joiner cannot see into is JOIN_NO_RELATION at WARNING, never silent" {
            // `shopper_id` is MAX(...) here, not a group key: no pairs can be derived, and the side is an Aggregate.
            val result =
                Translator(FkOnlyStack.withShopperAgg(FkOnlyStack.SHOPPER_AGG_BY_NAME_SQL)).parseToRelNode(
                    "SELECT shopper_agg.last_name, SUM(sale.revenue) AS revenue FROM sale JOIN shopper_agg " +
                        "GROUP BY shopper_agg.last_name",
                    Language.SQL,
                    targetSchema = SchemaCode.DB,
                    sourceSchema = SchemaCode.ER,
                )
            val warnings = result.shouldBeInstanceOf<ParseResult.Success>().warnings
            withClue(warnings) { warnings.any { it is JoinerWarning.NoRelation } shouldBe true }
        }

        "byte identity — an FK-only join between two table-bound entities is decided exactly as before" {
            val sql =
                "SELECT shopper.last_name, SUM(sale.revenue) AS revenue FROM sale JOIN shopper " +
                    "GROUP BY shopper.last_name"
            translate(sql) shouldBe FkOnlyStack.SALE_SHOPPER_GOLDEN
            // Still left to the FK by the ER half (INFO), and conditioned only after MAP_TO_PHYSICAL.
            val er = parse(sql)
            joined(er) shouldContain "ON TRUE"
            er.warnings.single().shouldBeInstanceOf<JoinerWarning.RelationWithoutJoinPairs>()
        }
    })

/**
 * Hartland with every relation bound to its FK only, and logical queries served under `obj.query` as a metadata worker
 * serves them (stored ER plan, no declared output columns).
 */
private object FkOnlyStack {
    private val j = HartlandJoinModel

    private fun column(attribute: QualifiedName): QualifiedName {
        val (entity, name) = attribute.name.split('.')
        val table = (j.entityMappings.getValue(j.er(entity)) as EntityMapping.ToTable).table
        return j.db("${table.name}.${j.renames[j.er(entity)]?.get(name) ?: name}")
    }

    /** The FKs the metadata service derives from the relations (one per relation, composite kept). */
    val foreignKeys: List<ModelForeignKey> =
        j.relations.map { r ->
            ModelForeignKey(r.joinPairs.map { column(it.first) }, r.joinPairs.map { column(it.second) })
        }

    val relations: List<ModelRelation> = j.relations.map { it.copy(joinPairs = emptyList()) }

    val base: InMemoryModelHandle =
        InMemoryModelHandle(
            tables = j.tables,
            foreignKeys = foreignKeys,
            entities = j.entities,
            relations = relations,
            entityMappings = j.entityMappings,
            attributeRenames = j.renames,
        )

    fun obj(name: String): QualifiedName =
        QualifiedName
            .newBuilder()
            .setSchemaCode(SchemaCode.OBJ)
            .setNamespace("query")
            .setName(name)
            .build()

    private fun serving(
        queries: Map<String, PlanNode>,
        entities: List<ModelEntity> = j.entities,
        extraRelations: List<ModelRelation> = emptyList(),
        mappings: Map<QualifiedName, EntityMapping> = j.entityMappings,
    ): InMemoryModelHandle =
        InMemoryModelHandle(
            tables = j.tables,
            foreignKeys = foreignKeys,
            entities = entities,
            relations = relations + extraRelations,
            entityMappings = mappings,
            savedQueries = queries.keys.map { ModelSavedQuery(obj(it)) },
            savedQueryBodies =
                queries
                    .mapKeys {
                        obj(
                            it.key,
                        )
                    }.mapValues { SavedQueryBody(it.value, emptyList(), emptyList()) },
            attributeRenames = j.renames,
        )

    private val level1: Map<String, PlanNode> =
        mapOf(
            "shopper_spend" to
                "SELECT sale.shopper_id, SUM(sale.revenue) AS revenue FROM sale GROUP BY sale.shopper_id",
            "buyer_spend" to
                "SELECT sale.shopper_id AS buyer, SUM(sale.revenue) AS revenue FROM sale GROUP BY sale.shopper_id",
            "top_buyer" to "SELECT MAX(sale.shopper_id) AS shopper_id, SUM(sale.revenue) AS revenue FROM sale",
            "two_addresses" to
                "SELECT sale.address_id AS sale_address, shopper.address_id AS home_address, " +
                "SUM(sale.revenue) AS revenue FROM sale JOIN shopper ON sale.shopper_id = shopper.shopper_id " +
                "GROUP BY sale.address_id, shopper.address_id",
            "local_shoppers" to
                "SELECT shopper.shopper_id, shopper.last_name FROM shopper WHERE shopper.birth_country = 'CZ'",
        ).mapValues { HartlandStackModel.parseEr(base, it.value) }

    private val level2: Map<String, PlanNode> =
        mapOf(
            "big_spenders" to
                HartlandStackModel.parseEr(
                    serving(level1),
                    "SELECT r.shopper_id, r.revenue FROM obj.query.shopper_spend AS r WHERE r.revenue > 1000",
                ),
        )

    val model: InMemoryModelHandle = serving(level1 + level2)

    /** ttr-core#204's shape: an entity stacked on a db query grouped per customer. */
    const val SHOPPER_AGG_SQL: String =
        "SELECT shopper_id, MAX(last_name) AS last_name FROM customer GROUP BY shopper_id"

    /** The same, but grouped by name: `shopper_id` is an aggregate, so nothing can be derived from it. */
    const val SHOPPER_AGG_BY_NAME_SQL: String =
        "SELECT MAX(shopper_id) AS shopper_id, last_name FROM customer GROUP BY last_name"

    private val shopperAggQuery: QualifiedName =
        QualifiedName
            .newBuilder()
            .setNamespace("query")
            .setName("shopper_agg_q")
            .build()

    fun withShopperAgg(body: String): InMemoryModelHandle {
        val plan =
            (
                Translator(base).parseToRelNode(
                    body,
                    Language.SQL,
                    targetSchema = SchemaCode.DB,
                    sourceSchema = SchemaCode.DB,
                ) as ParseResult.Success
            ).plan
        val shopperAgg =
            ModelEntity(
                j.er("shopper_agg"),
                listOf(
                    ModelAttribute("shopper_id", SurfaceType.INT, nullable = false, isKey = true),
                    ModelAttribute("last_name", SurfaceType.TEXT),
                ),
            )
        return InMemoryModelHandle(
            tables = j.tables,
            foreignKeys = foreignKeys,
            entities = j.entities + shopperAgg,
            relations = relations + ModelRelation(j.er("sale"), shopperAgg.qname, emptyList()),
            entityMappings = j.entityMappings + (shopperAgg.qname to EntityMapping.ToQuery(shopperAggQuery)),
            savedQueries = listOf(ModelSavedQuery(shopperAggQuery)),
            savedQueryBodies = mapOf(shopperAggQuery to SavedQueryBody(plan, emptyList(), emptyList())),
            attributeRenames = j.renames,
        )
    }

    /** `sale JOIN shopper` (FK-only) as translator 0.12.3 renders it: conditioned by JoinerPhysical, unchanged by T6. */
    const val SALE_SHOPPER_GOLDEN: String =
        "SELECT [customer].[last_name], SUM([store_sales].[revenue]) AS [revenue]\n" +
            "FROM [dbo].[store_sales]\n" +
            "    INNER JOIN [dbo].[customer] ON [store_sales].[shopper_id] = [customer].[shopper_id]\n" +
            "GROUP BY [customer].[last_name]"
}
