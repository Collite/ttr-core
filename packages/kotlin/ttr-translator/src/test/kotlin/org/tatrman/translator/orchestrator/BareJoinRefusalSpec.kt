// SPDX-License-Identifier: Apache-2.0
package org.tatrman.translator.orchestrator

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.tatrman.plan.v1.SchemaCode
import org.tatrman.translate.v1.Language
import org.tatrman.translator.framework.HartlandJoinModel
import org.tatrman.translator.framework.InMemoryModelHandle
import org.tatrman.translator.framework.ModelHandle
import org.tatrman.translator.joiner.JoinerMessages
import org.tatrman.translator.joiner.JoinerWarning

/**
 * ttr-server#232 — `refuseUnconditionedBareJoins`: a bare `JOIN` (no `ON`) that stays a Cartesian product is refused
 * as `join_unconditioned`, naming its sides. The comma list and `CROSS JOIN` keep their warning, and so does a bare
 * join the physical stage conditions from a table FK. Off by default: the join stays a warned Cartesian product.
 */
class BareJoinRefusalSpec :
    StringSpec({
        val j = HartlandJoinModel

        // `sale ↔ product` known to the database only: the relation is dropped and its FK
        // (store_sales.product_id → item.product_id) kept, so only the physical stage can condition the join.
        val fkOnly: ModelHandle =
            InMemoryModelHandle(
                tables = j.tables,
                foreignKeys = listOf(j.saleProductFk),
                entities = j.entities,
                relations = j.relations.filterNot { it.fromEntity == j.er("sale") && it.toEntity == j.er("product") },
                entityMappings = j.entityMappings,
                attributeRenames = j.renames,
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

        fun refused(result: ParseResult): String {
            val failure = result.shouldBeInstanceOf<ParseResult.Failure>()
            failure.code shouldBe JoinerMessages.UNCONDITIONED
            return failure.message
        }

        val unrelated = "SELECT calendar.day_name, product.product_name FROM calendar JOIN product"

        "a bare JOIN between unrelated entities is refused, naming the sides — on the DB target and the ER target" {
            for (target in listOf(SchemaCode.DB, SchemaCode.ER)) {
                refused(parse(unrelated, target)) shouldBe
                    "a JOIN without ON that the model cannot condition: no relation or foreign key joins " +
                    "entity.calendar and entity.product; write the ON, or CROSS JOIN if every pairing is meant"
            }
        }

        "without the option it is a Cartesian product with its warning, as before" {
            val result = parse(unrelated, refuse = false).shouldBeInstanceOf<ParseResult.Success>()
            result.warnings.single().shouldBeInstanceOf<JoinerWarning.NoRelation>()
        }

        "a CROSS JOIN and the comma list are the author's own: warned, not refused" {
            for (from in listOf("calendar CROSS JOIN product", "calendar, product")) {
                val result =
                    parse("SELECT calendar.day_name, product.product_name FROM $from")
                        .shouldBeInstanceOf<ParseResult.Success>()
                result.warnings.single().shouldBeInstanceOf<JoinerWarning.NoRelation>()
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

        "two relations and no ON: refused, listing them" {
            refused(parse("SELECT shopper.last_name, calendar.day_name FROM shopper JOIN calendar")) shouldContain
                "2 relations could join entity.shopper and entity.calendar: "
        }

        "a self-join without ON is refused" {
            refused(parse("SELECT s1.revenue, s2.revenue FROM sale s1 JOIN sale s2")) shouldContain
                "entity.sale appears more than once in the join"
        }

        "a chain names the join that failed, with the whole side it was decided against" {
            refused(
                parse("SELECT product.product_name, calendar.day_name FROM sale JOIN product JOIN calendar"),
            ) shouldContain "no relation or foreign key joins entity.sale, entity.product and entity.calendar"
        }

        "a join only a table FK conditions is not refused: on the DB target, and on the ER target, where the " +
            "physical stage is run on the side to decide" {
                val sql = "SELECT product.product_name, sale.revenue FROM sale JOIN product"
                val single = parse(sql, SchemaCode.DB, model = fkOnly).shouldBeInstanceOf<ParseResult.Success>()
                single.warnings.shouldBeEmpty()
                val erHalf = parse(sql, SchemaCode.ER, model = fkOnly).shouldBeInstanceOf<ParseResult.Success>()
                // The ER half's own verdict is unchanged: the logical stage warns, the physical one conditions later.
                erHalf.warnings.single().shouldBeInstanceOf<JoinerWarning.NoRelation>()
                val dbHalf =
                    Translator(fkOnly)
                        .parseToRelNode(String(erHalf.plan.toByteArray(), Charsets.ISO_8859_1), Language.REL_NODE)
                        .shouldBeInstanceOf<ParseResult.Success>()
                dbHalf.warnings.shouldBeEmpty()
            }

        "a bare JOIN an FK conditions, beside the author's CROSS JOIN: the CROSS JOIN's warning refuses nothing" {
            val sql =
                "SELECT product.product_name, calendar.day_name FROM sale JOIN product CROSS JOIN calendar"
            val result = parse(sql, model = fkOnly).shouldBeInstanceOf<ParseResult.Success>()
            val cartesian = result.warnings.single().shouldBeInstanceOf<JoinerWarning.NoRelation>()
            cartesian.rightEntities shouldBe listOf(j.er("calendar"))
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

        "REL_NODE re-entry does not re-read the text, and refuses nothing" {
            val erPlan = parse(unrelated, SchemaCode.ER, refuse = false).shouldBeInstanceOf<ParseResult.Success>().plan
            Translator(j.handle())
                .parseToRelNode(
                    String(erPlan.toByteArray(), Charsets.ISO_8859_1),
                    Language.REL_NODE,
                    refuseUnconditionedBareJoins = true,
                ).shouldBeInstanceOf<ParseResult.Success>()
        }
    })
