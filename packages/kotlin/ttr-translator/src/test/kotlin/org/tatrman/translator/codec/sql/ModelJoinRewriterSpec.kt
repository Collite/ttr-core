// SPDX-License-Identifier: Apache-2.0
package org.tatrman.translator.codec.sql

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.apache.calcite.sql.SqlNode
import org.apache.calcite.sql.dialect.CalciteSqlDialect
import org.tatrman.translator.framework.HartlandJoinModel

/**
 * MJ-P1 — the SQL carrier: `ModelJoinRewriter` conditions bare joins between ER entities from the model's
 * relations, on the parsed `SqlNode` before validation (contracts §1, §3; architecture §2.2).
 *
 * Forms the rewriter does NOT touch: explicit `ON` / `USING`, `NATURAL`, the comma list and `CROSS JOIN`
 * (Calcite accepts those without a condition and the wire carrier `JoinerLogical` conditions them, so the
 * pre-MJ plan bytes for the comma form stay identical — P0·S1.3 golden), and the semi/anti join shapes.
 */
class ModelJoinRewriterSpec :
    StringSpec({
        val model = HartlandJoinModel.handle()

        fun parse(sql: String): SqlNode = SqlParser.parseQuery(sql).shouldBeInstanceOf<ParseResult.Success>().sqlNode

        /** Unparse in Calcite's own dialect, unquoted, single-spaced — the assertions read like SQL. */
        fun sql(node: SqlNode): String =
            node
                .toSqlString(CalciteSqlDialect.DEFAULT)
                .sql
                .replace("\"", "")
                .replace(Regex("\\s+"), " ")
                .trim()

        fun rewrite(sqlText: String): String {
            val rewriter = ModelJoinRewriter(model, "entity")
            return sql(parse(sqlText).accept(rewriter) ?: error("shuttle returned null"))
        }

        // ---- S1: one bare join ----

        "a bare JOIN between two related entities gets the model's ON, as INNER JOIN" {
            rewrite("SELECT sale.sale_month FROM sale JOIN product") shouldContain
                "FROM sale INNER JOIN product ON sale.product_id = product.product_id"
        }

        "the pair is oriented to the sides: the `from` entity on the RIGHT swaps the attributes" {
            rewrite("SELECT 1 FROM product JOIN sale") shouldContain
                "FROM product INNER JOIN sale ON product.product_id = sale.product_id"
        }

        "H3 — a bare LEFT JOIN keeps its join type" {
            rewrite(HartlandJoinModel.H3_LEFT) shouldContain
                "FROM sale LEFT JOIN product ON sale.product_id = product.product_id"
        }

        "RIGHT and FULL are preserved too" {
            rewrite("SELECT 1 FROM sale RIGHT JOIN product") shouldContain "RIGHT JOIN product ON"
            rewrite("SELECT 1 FROM sale FULL JOIN product") shouldContain "FULL JOIN product ON"
        }

        "H5 — an explicit ON is untouched" {
            val text =
                "SELECT 1 FROM shopper sh JOIN calendar c ON sh.first_purchase_date = c.date_id"
            rewrite(text) shouldBe sql(parse(text))
        }

        "USING is untouched" {
            val text = "SELECT 1 FROM sale JOIN product USING (product_id)"
            rewrite(text) shouldBe sql(parse(text))
        }

        "NATURAL JOIN is untouched" {
            val text = "SELECT 1 FROM sale NATURAL JOIN product"
            rewrite(text) shouldBe sql(parse(text))
        }

        "the comma form and CROSS JOIN are left to the wire carrier (H10 bytes)" {
            val comma = HartlandJoinModel.H10_COMMA
            rewrite(comma) shouldBe sql(parse(comma))
            val cross = "SELECT 1 FROM sale CROSS JOIN product"
            rewrite(cross) shouldBe sql(parse(cross))
        }

        "H7 — no relation → ON TRUE, never an error" {
            val out = rewrite("SELECT 1 FROM sale JOIN household")
            out shouldContain "INNER JOIN household ON TRUE"
            out shouldNotContain "="
        }

        "H4 — two relations (first purchase + first shipment) → ON TRUE" {
            rewrite("SELECT 1 FROM shopper JOIN calendar") shouldContain "INNER JOIN calendar ON TRUE"
        }

        "the join keyword's position survives the rebuild (validation errors still point into the source)" {
            val original = parse("SELECT 1 FROM sale JOIN product")
            val rewritten = original.accept(ModelJoinRewriter(model, "entity"))!!
            val originalJoin = (original as org.apache.calcite.sql.SqlSelect).from as org.apache.calcite.sql.SqlJoin
            val rewrittenJoin = (rewritten as org.apache.calcite.sql.SqlSelect).from as org.apache.calcite.sql.SqlJoin
            rewrittenJoin.parserPosition shouldBe originalJoin.parserPosition
            rewrittenJoin.condition!!.parserPosition shouldBe originalJoin.parserPosition
        }

        "a statement without joins comes back unchanged" {
            val text = "SELECT product_name FROM product WHERE product_id > 5"
            rewrite(text) shouldBe sql(parse(text))
        }

        "an unknown table on one side contributes no entity → ON TRUE" {
            rewrite("SELECT 1 FROM sale JOIN not_an_entity") shouldContain "ON TRUE"
        }

        // ---- S2: chains, aliases, nesting, non-entity sides ----

        "H1 — the hero chain: each join decided against the whole other side (⚑MJ-2)" {
            val out = rewrite(HartlandJoinModel.H1_HERO)
            out shouldContain
                "FROM sale INNER JOIN shopper " +
                "ON sale.shopper_id = shopper.shopper_id " +
                "INNER JOIN household ON shopper.current_household = household.household_id " +
                "INNER JOIN product ON sale.product_id = product.product_id"
            out shouldNotContain "TRUE"
        }

        "H2 — aliases are used in the conditions" {
            rewrite(HartlandJoinModel.H2_ALIASES) shouldContain
                "FROM sale AS s INNER JOIN shopper AS sh " +
                "ON s.shopper_id = sh.shopper_id " +
                "INNER JOIN household AS h ON sh.current_household = h.household_id"
        }

        "a parenthesised right side: the inner join resolves first, the outer sees {sh, h}" {
            val out = rewrite("SELECT 1 FROM sale JOIN (shopper JOIN household)")
            out shouldContain "shopper INNER JOIN household ON shopper.current_household = household.household_id"
            out shouldContain "ON sale.shopper_id = shopper.shopper_id"
            out shouldNotContain "TRUE"
        }

        "H6 — both s→address and sh→address exist: ambiguous, ON TRUE, no proximity tie-break (⚑MJ-8)" {
            val out = rewrite("SELECT 1 FROM sale JOIN shopper JOIN address")
            out shouldContain "ON sale.shopper_id = shopper.shopper_id"
            out shouldContain "INNER JOIN address ON TRUE"
        }

        "H8 — the same entity twice steps aside (⚑MJ-6)" {
            rewrite("SELECT 1 FROM shopper z1 JOIN shopper z2") shouldContain "INNER JOIN shopper AS z2 ON TRUE"
        }

        "H9 — a composite relation ANDs every pair (⚑MJ-7)" {
            rewrite("SELECT 1 FROM order_line JOIN order_return") shouldContain
                "ON order_line.order_number = order_return.order_number AND order_line.product_id = order_return.product_id"
        }

        "H11 — a derived table on one side contributes no entity → ON TRUE" {
            val out =
                rewrite(
                    "SELECT 1 FROM sale JOIN (SELECT product_id FROM product WHERE product_id > 1) p",
                )
            out shouldContain ") AS p ON TRUE"
        }

        "a DB table on one side in an ER-catalog statement → ON TRUE" {
            rewrite("SELECT 1 FROM sale JOIN db.dbo.item") shouldContain "ON TRUE"
            rewrite("SELECT 1 FROM sale JOIN dbo.item") shouldContain "ON TRUE"
        }

        "a bare join inside a WHERE subquery is rewritten too" {
            val out =
                rewrite(
                    "SELECT 1 FROM product WHERE product_id IN " +
                        "(SELECT sale.product_id FROM sale JOIN shopper)",
                )
            out shouldContain "ON sale.shopper_id = shopper.shopper_id"
        }

        "bare joins inside a CTE body and both UNION branches are rewritten" {
            val out =
                rewrite(
                    "WITH x AS (SELECT 1 AS a FROM sale JOIN product) " +
                        "SELECT a FROM x UNION ALL SELECT 2 FROM shopper JOIN household",
                )
            out shouldContain "ON sale.product_id = product.product_id"
            out shouldContain "ON shopper.current_household = household.household_id"
        }

        "qualified entity names (entity.x, er.entity.x) resolve; another namespace does not" {
            rewrite("SELECT 1 FROM entity.sale JOIN er.entity.product") shouldContain
                "ON sale.product_id = product.product_id"
            rewrite("SELECT 1 FROM other.sale JOIN product") shouldContain "ON TRUE"
        }

        "quoted identifiers resolve like bare ones (Lex.MYSQL_ANSI: double quotes; backticks are a lexical error)" {
            rewrite("SELECT 1 FROM \"sale\" JOIN \"product\"") shouldContain
                "ON sale.product_id = product.product_id"
            rewrite("SELECT 1 FROM SHOPPER JOIN Household") shouldContain
                "ON SHOPPER.current_household = Household.household_id"
        }

        "collectEntityRefs keeps FROM order (the ambiguity text lists entities in source order)" {
            val rewriter = ModelJoinRewriter(model, "entity")
            val from =
                (
                    parse(
                        "SELECT 1 FROM product p JOIN sale ON TRUE JOIN shopper sh ON TRUE",
                    ) as org.apache.calcite.sql.SqlSelect
                ).from
            rewriter.collectEntityRefs(from).map { it.entity.name to it.handle } shouldBe
                listOf("product" to "p", "sale" to "sale", "shopper" to "sh")
        }
    })
