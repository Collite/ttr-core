// SPDX-License-Identifier: Apache-2.0
package org.tatrman.translator.codec.sql

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.apache.calcite.sql.SqlNode
import org.apache.calcite.sql.dialect.CalciteSqlDialect
import org.tatrman.translator.framework.DfpJoinModel

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
        val model = DfpJoinModel.handle()

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
            rewrite("SELECT kumulovaný_prodej.období FROM kumulovaný_prodej JOIN produkt") shouldContain
                "FROM kumulovaný_prodej INNER JOIN produkt ON kumulovaný_prodej.id_produktu = produkt.id_produktu"
        }

        "the pair is oriented to the sides: the `from` entity on the RIGHT swaps the attributes" {
            rewrite("SELECT 1 FROM produkt JOIN kumulovaný_prodej") shouldContain
                "FROM produkt INNER JOIN kumulovaný_prodej ON produkt.id_produktu = kumulovaný_prodej.id_produktu"
        }

        "H3 — a bare LEFT JOIN keeps its join type" {
            rewrite(DfpJoinModel.H3_LEFT) shouldContain
                "FROM kumulovaný_prodej LEFT JOIN produkt ON kumulovaný_prodej.id_produktu = produkt.id_produktu"
        }

        "RIGHT and FULL are preserved too" {
            rewrite("SELECT 1 FROM kumulovaný_prodej RIGHT JOIN produkt") shouldContain "RIGHT JOIN produkt ON"
            rewrite("SELECT 1 FROM kumulovaný_prodej FULL JOIN produkt") shouldContain "FULL JOIN produkt ON"
        }

        "H5 — an explicit ON is untouched" {
            val text =
                "SELECT 1 FROM dodací_místo dm JOIN uživatel u ON dm.obchodní_zástupce = u.id_uživatele"
            rewrite(text) shouldBe sql(parse(text))
        }

        "USING is untouched" {
            val text = "SELECT 1 FROM kumulovaný_prodej JOIN produkt USING (id_produktu)"
            rewrite(text) shouldBe sql(parse(text))
        }

        "NATURAL JOIN is untouched" {
            val text = "SELECT 1 FROM kumulovaný_prodej NATURAL JOIN produkt"
            rewrite(text) shouldBe sql(parse(text))
        }

        "the comma form and CROSS JOIN are left to the wire carrier (H10 bytes)" {
            val comma = DfpJoinModel.H10_COMMA
            rewrite(comma) shouldBe sql(parse(comma))
            val cross = "SELECT 1 FROM kumulovaný_prodej CROSS JOIN produkt"
            rewrite(cross) shouldBe sql(parse(cross))
        }

        "H7 — no relation → ON TRUE, never an error" {
            val out = rewrite("SELECT 1 FROM kumulovaný_prodej JOIN zákazník")
            out shouldContain "INNER JOIN zákazník ON TRUE"
            out shouldNotContain "="
        }

        "H4 — two relations (OZ + VOT) → ON TRUE" {
            rewrite("SELECT 1 FROM dodací_místo JOIN uživatel") shouldContain "INNER JOIN uživatel ON TRUE"
        }

        "the join keyword's position survives the rebuild (validation errors still point into the source)" {
            val original = parse("SELECT 1 FROM kumulovaný_prodej JOIN produkt")
            val rewritten = original.accept(ModelJoinRewriter(model, "entity"))!!
            val originalJoin = (original as org.apache.calcite.sql.SqlSelect).from as org.apache.calcite.sql.SqlJoin
            val rewrittenJoin = (rewritten as org.apache.calcite.sql.SqlSelect).from as org.apache.calcite.sql.SqlJoin
            rewrittenJoin.parserPosition shouldBe originalJoin.parserPosition
            rewrittenJoin.condition!!.parserPosition shouldBe originalJoin.parserPosition
        }

        "a statement without joins comes back unchanged" {
            val text = "SELECT název_produktu FROM produkt WHERE id_produktu > 5"
            rewrite(text) shouldBe sql(parse(text))
        }

        "an unknown table on one side contributes no entity → ON TRUE" {
            rewrite("SELECT 1 FROM kumulovaný_prodej JOIN not_an_entity") shouldContain "ON TRUE"
        }
    })
