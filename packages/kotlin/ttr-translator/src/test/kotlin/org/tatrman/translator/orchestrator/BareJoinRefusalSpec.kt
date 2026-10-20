// SPDX-License-Identifier: Apache-2.0
package org.tatrman.translator.orchestrator

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.tatrman.plan.v1.PlanNode
import org.tatrman.plan.v1.SchemaCode
import org.tatrman.translate.v1.Language
import org.tatrman.translator.framework.HartlandJoinModel
import org.tatrman.translator.framework.InMemoryModelHandle
import org.tatrman.translator.framework.ModelForeignKey
import org.tatrman.translator.framework.ModelHandle
import org.tatrman.translator.framework.ModelRelation
import org.tatrman.translator.joiner.JoinerMessages
import org.tatrman.translator.joiner.JoinerPlanWalker
import org.tatrman.translator.joiner.JoinerWarning

/**
 * ttr-server#232 — `refuseUnconditionedBareJoins`: a bare `JOIN` (no `ON`) that stays a Cartesian product is refused
 * as `join_unconditioned`, naming its sides. The SQL carrier marks each bare join it cannot condition
 * (`JoinNode.condition_required`); the refusal is decided per join where the physical stage runs: the single `DB`
 * call, or the REL_NODE re-entry that finishes an `ER` half. The comma list and `CROSS JOIN` keep their warning, and
 * so does a bare join the physical stage conditions from a table FK. Off by default: no mark, a warned Cartesian product.
 */
class BareJoinRefusalSpec :
    StringSpec({
        val j = HartlandJoinModel

        /** Hartland, with the given relations and FKs. */
        fun model(
            relations: List<ModelRelation> = j.relations,
            foreignKeys: List<ModelForeignKey> = emptyList(),
        ): ModelHandle =
            InMemoryModelHandle(
                tables = j.tables,
                foreignKeys = foreignKeys,
                entities = j.entities,
                relations = relations,
                entityMappings = j.entityMappings,
                attributeRenames = j.renames,
            )

        val saleProduct = { r: ModelRelation -> r.fromEntity == j.er("sale") && r.toEntity == j.er("product") }

        // `sale ↔ product` known to the database only: the relation is dropped and its FK
        // (store_sales.product_id → item.product_id) kept, so only the physical stage can condition the join.
        val fkOnly = model(j.relations.filterNot(saleProduct), listOf(j.saleProductFk))

        // A second FK between the same two tables: the physical stage cannot pick one.
        val secondSaleProductFk =
            ModelForeignKey(from = listOf(j.db("store_sales.promo_id")), to = listOf(j.db("item.product_id")))

        // Hartland's own shape: `sale → product` bound to its FK only, no `join:` pairs.
        fun withoutPairs(foreignKeys: List<ModelForeignKey>): ModelHandle =
            model(
                j.relations.filterNot(saleProduct) + ModelRelation(j.er("sale"), j.er("product"), emptyList()),
                foreignKeys,
            )

        fun parse(
            sql: String,
            target: SchemaCode = SchemaCode.DB,
            refuse: Boolean = true,
            model: ModelHandle = j.handle(),
        ): ParseResult =
            Translator(model).parseToRelNode(
                sql,
                Language.SQL,
                target,
                sourceSchema = SchemaCode.ER,
                refuseUnconditionedBareJoins = refuse,
            )

        /** The second call of the two-half pipeline, as ttr-server makes it: REL_NODE, no option. */
        fun dbHalf(
            erPlan: PlanNode,
            model: ModelHandle = j.handle(),
        ): ParseResult =
            Translator(model).parseToRelNode(String(erPlan.toByteArray(), Charsets.ISO_8859_1), Language.REL_NODE)

        fun refused(result: ParseResult): String {
            val failure = result.shouldBeInstanceOf<ParseResult.Failure>()
            failure.code shouldBe JoinerMessages.UNCONDITIONED
            return failure.message
        }

        fun marks(plan: PlanNode): Int {
            var n = 0
            JoinerPlanWalker.forEachNode(plan) { node ->
                if (node.nodeCase == PlanNode.NodeCase.JOIN && node.join.conditionRequired) n++
            }
            return n
        }

        val unrelated = "SELECT calendar.day_name, product.product_name FROM calendar JOIN product"
        val unrelatedRefusal =
            "a JOIN without ON that the model cannot condition: no relation or foreign key joins " +
                "entity.calendar and entity.product; write the ON, or CROSS JOIN if every pairing is meant"

        "a bare JOIN between unrelated entities is refused on the DB target, naming the sides" {
            refused(parse(unrelated)) shouldBe unrelatedRefusal
        }

        "on the ER target the join is marked, and the REL_NODE re-entry refuses it without being asked" {
            val erHalf = parse(unrelated, SchemaCode.ER).shouldBeInstanceOf<ParseResult.Success>()
            erHalf.warnings.single().shouldBeInstanceOf<JoinerWarning.NoRelation>()
            marks(erHalf.plan) shouldBe 1
            refused(dbHalf(erHalf.plan)) shouldBe unrelatedRefusal
        }

        "without the option it is a Cartesian product with its warning, unmarked, as before" {
            for (target in listOf(SchemaCode.DB, SchemaCode.ER)) {
                val result = parse(unrelated, target, refuse = false).shouldBeInstanceOf<ParseResult.Success>()
                result.warnings.single().shouldBeInstanceOf<JoinerWarning.NoRelation>()
                marks(result.plan) shouldBe 0
            }
        }

        "a CROSS JOIN and the comma list are the author's own: warned, never marked or refused" {
            for (from in listOf("calendar CROSS JOIN product", "calendar, product")) {
                for (target in listOf(SchemaCode.DB, SchemaCode.ER)) {
                    val result =
                        parse("SELECT calendar.day_name, product.product_name FROM $from", target)
                            .shouldBeInstanceOf<ParseResult.Success>()
                    result.warnings.single().shouldBeInstanceOf<JoinerWarning.NoRelation>()
                    marks(result.plan) shouldBe 0
                }
            }
        }

        "a bare JOIN the model conditions is untouched: the same plan as without the option" {
            for (target in listOf(SchemaCode.DB, SchemaCode.ER)) {
                val with = parse(HartlandJoinModel.H1_HERO, target).shouldBeInstanceOf<ParseResult.Success>()
                val without =
                    parse(HartlandJoinModel.H1_HERO, target, refuse = false).shouldBeInstanceOf<ParseResult.Success>()
                with.plan shouldBe without.plan
                with.warnings.shouldBeEmpty()
            }
        }

        "the model_joins SQL reads ON TRUE: the mark is not text" {
            val with = parse(unrelated, SchemaCode.ER).modelJoinsSql
            with shouldBe parse(unrelated, SchemaCode.ER, refuse = false).modelJoinsSql
            with!! shouldContain "ON TRUE"
        }

        "two relations and no ON: refused, listing them" {
            refused(parse("SELECT shopper.last_name, calendar.day_name FROM shopper JOIN calendar")) shouldContain
                "cannot condition: 2 relations could join entity.shopper and entity.calendar: "
        }

        "a self-join without ON is refused" {
            refused(parse("SELECT s1.revenue, s2.revenue FROM sale s1 JOIN sale s2")) shouldContain
                "cannot condition: entity entity.sale appears more than once in the join; write the ON"
        }

        "a chain names the join that failed, with the whole side it was decided against" {
            refused(
                parse("SELECT product.product_name, calendar.day_name FROM sale JOIN product JOIN calendar"),
            ) shouldContain "no relation or foreign key joins entity.sale, entity.product and entity.calendar"
        }

        "a join only a table FK conditions is not refused, and the mark is gone once it is conditioned" {
            val sql = "SELECT product.product_name, sale.revenue FROM sale JOIN product"
            val single = parse(sql, model = fkOnly).shouldBeInstanceOf<ParseResult.Success>()
            single.warnings.shouldBeEmpty()
            marks(single.plan) shouldBe 0
            // The ER half's verdict is unchanged: the logical stage warns, and the mark waits for the physical one.
            val erHalf = parse(sql, SchemaCode.ER, model = fkOnly).shouldBeInstanceOf<ParseResult.Success>()
            erHalf.warnings.single().shouldBeInstanceOf<JoinerWarning.NoRelation>()
            marks(erHalf.plan) shouldBe 1
            val finished = dbHalf(erHalf.plan, fkOnly).shouldBeInstanceOf<ParseResult.Success>()
            finished.warnings.shouldBeEmpty()
            finished.plan shouldBe single.plan
        }

        "a relation with no join pairs (Hartland's FK-only shape) whose FK conditions the join is not refused" {
            val sql = "SELECT product.product_name, sale.revenue FROM sale JOIN product"
            val result =
                parse(sql, model = withoutPairs(listOf(j.saleProductFk))).shouldBeInstanceOf<ParseResult.Success>()
            result.warnings.single().shouldBeInstanceOf<JoinerWarning.RelationWithoutJoinPairs>()
            marks(result.plan) shouldBe 0
        }

        "a relation with no join pairs whose FK cannot condition the join is refused, on both pipelines" {
            val sql = "SELECT product.product_name, sale.revenue FROM sale JOIN product"
            val m = withoutPairs(listOf(j.saleProductFk, secondSaleProductFk))
            val expected =
                "a JOIN without ON that the model cannot condition: relation sale → product declares no join pairs, " +
                    "and more than one foreign key could join dbo.store_sales and dbo.item; write the ON, " +
                    "or CROSS JOIN if every pairing is meant"
            refused(parse(sql, model = m)) shouldBe expected
            val erHalf = parse(sql, SchemaCode.ER, model = m).shouldBeInstanceOf<ParseResult.Success>()
            refused(dbHalf(erHalf.plan, m)) shouldBe expected
        }

        "no relation, and two FKs between the tables: the refusal says both" {
            val m = model(j.relations.filterNot(saleProduct), listOf(j.saleProductFk, secondSaleProductFk))
            refused(parse("SELECT product.product_name, sale.revenue FROM sale JOIN product", model = m)) shouldContain
                "cannot condition: no relation joins entity.sale and entity.product, and more than one foreign key " +
                "could join dbo.store_sales and dbo.item; write the ON"
        }

        "a bare JOIN with no entity on either side (two aggregating CTEs) is refused" {
            val sql =
                "WITH a AS (SELECT shopper_id, SUM(revenue) AS revenue FROM sale GROUP BY shopper_id), " +
                    "b AS (SELECT shopper_id, SUM(quantity) AS quantity FROM sale GROUP BY shopper_id) " +
                    "SELECT a.revenue, b.quantity FROM a JOIN b"
            refused(parse(sql)) shouldContain "no relation or foreign key joins a derived table and a derived table"
        }

        "the author's CROSS JOIN elsewhere in the statement refuses no bare join the physical stage conditioned" {
            // The bare join and the CROSS JOIN have the same entity sides (a derived table, product).
            val sql =
                "SELECT product.product_name, s.revenue " +
                    "FROM (SELECT product_id, revenue FROM sale) s JOIN product " +
                    "WHERE s.revenue > (SELECT MAX(t.total) FROM (SELECT SUM(revenue) AS total FROM sale) t " +
                    "CROSS JOIN product)"
            val result = parse(sql, model = fkOnly).shouldBeInstanceOf<ParseResult.Success>()
            val cartesian = result.warnings.single().shouldBeInstanceOf<JoinerWarning.NoRelation>()
            cartesian.rightEntities shouldBe listOf(j.er("product"))
        }

        "a bare JOIN inside a sub-query is marked and refused too" {
            refused(
                parse(
                    "SELECT product.product_name FROM product " +
                        "WHERE product.product_id IN (SELECT promo.product_id FROM promo JOIN calendar)",
                ),
            ) shouldContain "no relation or foreign key joins entity.promo and entity.calendar"
        }

        "a bare JOIN inside a correlated sub-query keeps its mark through decorrelation" {
            refused(
                parse(
                    "SELECT product.product_name FROM product WHERE EXISTS " +
                        "(SELECT 1 FROM promo JOIN calendar WHERE promo.product_id = product.product_id)",
                ),
            ) shouldContain "no relation or foreign key joins entity.promo and entity.calendar"
        }

        "decorrelation does not spread a mark: the author's CROSS JOIN below a marked join keeps its warning" {
            // The bare `… JOIN product` is marked and the FK conditions it; `calendar CROSS JOIN sale` is the author's.
            val sql =
                "SELECT shopper.last_name FROM shopper WHERE EXISTS (SELECT 1 FROM calendar CROSS JOIN sale " +
                    "JOIN product WHERE sale.shopper_id = shopper.shopper_id)"
            val result = parse(sql, model = fkOnly).shouldBeInstanceOf<ParseResult.Success>()
            val cartesian = result.warnings.single().shouldBeInstanceOf<JoinerWarning.NoRelation>()
            cartesian.leftEntities shouldBe listOf(j.er("calendar"))
            cartesian.rightEntities shouldBe listOf(j.er("sale"))
            marks(result.plan) shouldBe 0
        }

        "a derived table the model cannot see into (a CTE over a UNION) is refused on its bare JOIN" {
            val sql =
                "WITH u AS (SELECT product_id, revenue FROM sale UNION ALL SELECT product_id, quantity FROM sale) " +
                    "SELECT product.product_name, SUM(u.revenue) AS revenue FROM u JOIN product " +
                    "GROUP BY product.product_name"
            refused(parse(sql)) shouldContain "no relation or foreign key joins a derived table and entity.product"
        }

        "a derived table whose table an FK reaches is conditioned by the physical stage, and not refused" {
            val sql =
                "SELECT product.product_name, s.revenue " +
                    "FROM (SELECT product_id, revenue FROM sale) s JOIN product"
            parse(sql, model = fkOnly).shouldBeInstanceOf<ParseResult.Success>().warnings.shouldBeEmpty()
        }

        // A bare join its WHERE conditions is the author's join, written in the wrong clause: the condition moves into
        // its ON, and the plan is the one the ON form gives.
        fun sameAsOn(
            where: String,
            on: String,
            model: ModelHandle = j.handle(),
        ) {
            for (target in listOf(SchemaCode.DB, SchemaCode.ER)) {
                val result = parse(where, target, model = model).shouldBeInstanceOf<ParseResult.Success>()
                result.warnings.shouldBeEmpty()
                marks(result.plan) shouldBe 0
                result.plan shouldBe parse(on, target, model = model).shouldBeInstanceOf<ParseResult.Success>().plan
            }
        }

        "a bare JOIN its WHERE conditions is accepted: the condition moves into its ON, the rest stays in WHERE" {
            sameAsOn(
                "SELECT calendar.day_name, product.product_name FROM calendar JOIN product " +
                    "WHERE calendar.date_id = product.product_id AND product.product_name = 'Electronics'",
                "SELECT calendar.day_name, product.product_name FROM calendar JOIN product " +
                    "ON calendar.date_id = product.product_id WHERE product.product_name = 'Electronics'",
            )
        }

        "a chain: each WHERE condition goes to the join whose two sides it reads" {
            sameAsOn(
                "SELECT calendar.day_name, promo.promo_name FROM calendar JOIN household JOIN promo " +
                    "WHERE calendar.date_id = household.household_id AND promo.promo_id = household.household_id",
                "SELECT calendar.day_name, promo.promo_name FROM calendar " +
                    "JOIN household ON calendar.date_id = household.household_id " +
                    "JOIN promo ON promo.promo_id = household.household_id",
            )
        }

        "a marked join on the right of another takes its condition at its own offset" {
            sameAsOn(
                "SELECT product.product_name, calendar.day_name FROM product CROSS JOIN (calendar JOIN promo) " +
                    "WHERE calendar.date_id = promo.promo_id",
                "SELECT product.product_name, calendar.day_name FROM product " +
                    "CROSS JOIN (calendar JOIN promo ON calendar.date_id = promo.promo_id)",
            )
        }

        "a range condition in WHERE conditions the join too" {
            sameAsOn(
                "SELECT calendar.day_name, promo.promo_name FROM calendar JOIN promo " +
                    "WHERE promo.promo_id BETWEEN calendar.date_id AND calendar.date_id + 7",
                "SELECT calendar.day_name, promo.promo_name FROM calendar JOIN promo " +
                    "ON promo.promo_id BETWEEN calendar.date_id AND calendar.date_id + 7",
            )
        }

        "an FK-only relation conditioned in WHERE gets no foreign key stacked on the author's condition" {
            sameAsOn(
                "SELECT product.product_name, sale.revenue FROM sale JOIN product WHERE sale.promo_id = product.product_id",
                "SELECT product.product_name, sale.revenue FROM sale JOIN product ON sale.promo_id = product.product_id",
                withoutPairs(listOf(j.saleProductFk, secondSaleProductFk)),
            )
        }

        "a WHERE condition on one side only does not condition the join: refused" {
            refused(
                parse(
                    "SELECT calendar.day_name, product.product_name FROM calendar JOIN product " +
                        "WHERE product.product_name = 'Electronics'",
                ),
            ) shouldBe unrelatedRefusal
        }

        "a WHERE condition across a bare LEFT JOIN is refused: it would drop the rows the LEFT JOIN keeps" {
            refused(
                parse(
                    "SELECT calendar.day_name, product.product_name FROM calendar LEFT JOIN product " +
                        "WHERE calendar.date_id = product.product_id",
                ),
            ) shouldBe unrelatedRefusal
        }

        "a condition inside a WHERE sub-query does not condition the join: refused" {
            refused(
                parse(
                    "SELECT calendar.day_name, product.product_name FROM calendar JOIN product WHERE EXISTS " +
                        "(SELECT 1 FROM promo WHERE promo.product_id = product.product_id " +
                        "AND promo.promo_id = calendar.date_id)",
                ),
            ) shouldBe unrelatedRefusal
        }

        "a WHERE condition with a sub-query stays in WHERE, even across both sides: refused" {
            refused(
                parse(
                    "SELECT calendar.day_name, product.product_name FROM calendar JOIN product " +
                        "WHERE calendar.date_id + product.product_id IN " +
                        "(SELECT promo.promo_id FROM promo WHERE promo.product_id = product.product_id)",
                ),
            ) shouldBe unrelatedRefusal
        }

        "without the option the WHERE condition stays in WHERE, and the join keeps its warning, as before" {
            val result =
                parse(
                    "SELECT calendar.day_name, product.product_name FROM calendar JOIN product " +
                        "WHERE calendar.date_id = product.product_id",
                    refuse = false,
                ).shouldBeInstanceOf<ParseResult.Success>()
            result.warnings.single().shouldBeInstanceOf<JoinerWarning.NoRelation>()
            result.plan.project.input.nodeCase shouldBe PlanNode.NodeCase.FILTER
        }

        "translate refuses too" {
            val result =
                Translator(j.handle()).translate(
                    unrelated,
                    Language.SQL,
                    Language.SQL,
                    sourceSchema = SchemaCode.ER,
                    refuseUnconditionedBareJoins = true,
                )
            result.shouldBeInstanceOf<TranslateResult.Failure>().code shouldBe JoinerMessages.UNCONDITIONED
        }

        "explain takes the option, and reports the refusal the parse would give" {
            val explained = Translator(j.handle()).explain(unrelated, Language.SQL, refuseUnconditionedBareJoins = true)
            explained.finalError shouldBe unrelatedRefusal
            Translator(j.handle()).explain(unrelated, Language.SQL).finalError shouldBe null
        }

        "a REL_NODE re-entry of a plan parsed without the option refuses nothing, whatever it is asked" {
            val erPlan = parse(unrelated, SchemaCode.ER, refuse = false).shouldBeInstanceOf<ParseResult.Success>().plan
            Translator(j.handle())
                .parseToRelNode(
                    String(erPlan.toByteArray(), Charsets.ISO_8859_1),
                    Language.REL_NODE,
                    refuseUnconditionedBareJoins = true,
                ).shouldBeInstanceOf<ParseResult.Success>()
        }
    })
