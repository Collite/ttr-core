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

        // ---- S2: chains, aliases, nesting, non-entity sides ----

        "H1 — the hero chain: each join decided against the whole other side (⚑MJ-2)" {
            val out = rewrite(DfpJoinModel.H1_HERO)
            out shouldContain
                "FROM kumulovaný_prodej INNER JOIN dodací_místo " +
                "ON kumulovaný_prodej.id_dodacího_místa = dodací_místo.id_dodacího_místa " +
                "INNER JOIN zákazník ON dodací_místo.id_subjektu = zákazník.id_zákazníka " +
                "INNER JOIN produkt ON kumulovaný_prodej.id_produktu = produkt.id_produktu"
            out shouldNotContain "TRUE"
        }

        "H2 — aliases are used in the conditions" {
            rewrite(DfpJoinModel.H2_ALIASES) shouldContain
                "FROM kumulovaný_prodej AS kp INNER JOIN dodací_místo AS dm " +
                "ON kp.id_dodacího_místa = dm.id_dodacího_místa " +
                "INNER JOIN zákazník AS z ON dm.id_subjektu = z.id_zákazníka"
        }

        "a parenthesised right side: the inner join resolves first, the outer sees {dm, z}" {
            val out = rewrite("SELECT 1 FROM kumulovaný_prodej JOIN (dodací_místo JOIN zákazník)")
            out shouldContain "dodací_místo INNER JOIN zákazník ON dodací_místo.id_subjektu = zákazník.id_zákazníka"
            out shouldContain "ON kumulovaný_prodej.id_dodacího_místa = dodací_místo.id_dodacího_místa"
            out shouldNotContain "TRUE"
        }

        "H6 — both kp→ok and dm→ok exist: ambiguous, ON TRUE, no proximity tie-break (⚑MJ-8)" {
            val out = rewrite("SELECT 1 FROM kumulovaný_prodej JOIN dodací_místo JOIN obchodní_kanál")
            out shouldContain "ON kumulovaný_prodej.id_dodacího_místa = dodací_místo.id_dodacího_místa"
            out shouldContain "INNER JOIN obchodní_kanál ON TRUE"
        }

        "H8 — the same entity twice steps aside (⚑MJ-6)" {
            rewrite("SELECT 1 FROM zákazník z1 JOIN zákazník z2") shouldContain "INNER JOIN zákazník AS z2 ON TRUE"
        }

        "H9 — a composite relation ANDs every pair (⚑MJ-7)" {
            rewrite("SELECT 1 FROM smlouva JOIN dodatek") shouldContain
                "ON smlouva.číslo_smlouvy = dodatek.číslo_smlouvy AND smlouva.rok = dodatek.rok"
        }

        "H11 — a derived table on one side contributes no entity → ON TRUE" {
            val out =
                rewrite(
                    "SELECT 1 FROM kumulovaný_prodej JOIN (SELECT id_produktu FROM produkt WHERE id_produktu > 1) p",
                )
            out shouldContain ") AS p ON TRUE"
        }

        "a DB table on one side in an ER-catalog statement → ON TRUE" {
            rewrite("SELECT 1 FROM kumulovaný_prodej JOIN db.dbo.QPRODUKT") shouldContain "ON TRUE"
            rewrite("SELECT 1 FROM kumulovaný_prodej JOIN dbo.QPRODUKT") shouldContain "ON TRUE"
        }

        "a bare join inside a WHERE subquery is rewritten too" {
            val out =
                rewrite(
                    "SELECT 1 FROM produkt WHERE id_produktu IN " +
                        "(SELECT kumulovaný_prodej.id_produktu FROM kumulovaný_prodej JOIN dodací_místo)",
                )
            out shouldContain "ON kumulovaný_prodej.id_dodacího_místa = dodací_místo.id_dodacího_místa"
        }

        "bare joins inside a CTE body and both UNION branches are rewritten" {
            val out =
                rewrite(
                    "WITH x AS (SELECT 1 AS a FROM kumulovaný_prodej JOIN produkt) " +
                        "SELECT a FROM x UNION ALL SELECT 2 FROM dodací_místo JOIN zákazník",
                )
            out shouldContain "ON kumulovaný_prodej.id_produktu = produkt.id_produktu"
            out shouldContain "ON dodací_místo.id_subjektu = zákazník.id_zákazníka"
        }

        "qualified entity names (entity.x, er.entity.x) resolve; another namespace does not" {
            rewrite("SELECT 1 FROM entity.kumulovaný_prodej JOIN er.entity.produkt") shouldContain
                "ON kumulovaný_prodej.id_produktu = produkt.id_produktu"
            rewrite("SELECT 1 FROM other.kumulovaný_prodej JOIN produkt") shouldContain "ON TRUE"
        }

        "quoted identifiers resolve like bare ones (Lex.MYSQL_ANSI: double quotes; backticks are a lexical error)" {
            rewrite("SELECT 1 FROM \"kumulovaný_prodej\" JOIN \"produkt\"") shouldContain
                "ON kumulovaný_prodej.id_produktu = produkt.id_produktu"
            rewrite("SELECT 1 FROM KUMULOVANÝ_PRODEJ JOIN Produkt") shouldContain
                "ON KUMULOVANÝ_PRODEJ.id_produktu = Produkt.id_produktu"
        }

        "collectEntityRefs keeps FROM order (the ambiguity text lists entities in source order)" {
            val rewriter = ModelJoinRewriter(model, "entity")
            val from =
                (
                    parse(
                        "SELECT 1 FROM produkt p JOIN kumulovaný_prodej ON TRUE JOIN dodací_místo dm ON TRUE",
                    ) as org.apache.calcite.sql.SqlSelect
                ).from
            rewriter.collectEntityRefs(from).map { it.entity.name to it.handle } shouldBe
                listOf("produkt" to "p", "kumulovaný_prodej" to "kumulovaný_prodej", "dodací_místo" to "dm")
        }
    })
