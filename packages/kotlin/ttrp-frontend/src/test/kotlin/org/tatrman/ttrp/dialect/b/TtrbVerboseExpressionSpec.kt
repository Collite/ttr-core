// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.dialect.b

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.tatrman.ttrp.ast.SourceLocation
import org.tatrman.ttrp.dialect.sql.TtrSqlLoc
import org.tatrman.ttrp.expr.Expression
import org.tatrman.ttrp.expr.FunctionCall
import org.tatrman.ttrp.expr.IsNull
import org.tatrman.ttrp.expr.InList
import org.tatrman.ttrp.parser.TtrpParser
import org.tatrman.ttrp.parser.generated.TTRBParser
import org.tomlj.Toml

/**
 * T7.1.4: the verbose comparison skin lifts to the ONE PL expression IR (S16 / T5-e) — every
 * verbose form folds to the SAME [org.tatrman.ttrp.expr.CatalogId] as its canonical operator.
 * Table-driven from `ttr-b.synonyms.toml` (the single source, C4-c); canonical + verbose forms
 * may mix in one predicate; 3VL (`is [not] empty`) folds to IsNull.
 */
class TtrbVerboseExpressionSpec :
    StringSpec({
        val folder = TtrbExpr(TtrSqlLoc(zero()), TtrpParser.catalog)

        fun pred(cond: String): Expression {
            val parsed = TtrbCorpus.parse("Keep rows where $cond.")
            parsed.syntaxErrors shouldBe emptyList()
            val filter =
                parsed.tree
                    .item(0)
                    .sentence()
                    .statement() as TTRBParser.FilterSentenceContext
            val bool = (filter.filterStmt() as TTRBParser.KeepFilterContext).boolExpr()
            return folder.foldBool(bool)
        }

        // Symbol operator → the op.* catalogue id every verbose synonym for it must fold to.
        val opFor =
            mapOf(">" to "op.gt", "<" to "op.lt", "<=" to "op.lte", ">=" to "op.gte", "=" to "op.eq", "<>" to "op.neq")

        // Read the closed synonym table (single source) and assert each comparator row.
        val synonyms =
            TtrbExpr::class.java
                .getResourceAsStream(
                    "/verbose/ttr-b.synonyms.toml",
                )!!
                .bufferedReader()
                .readText()
        val toml = Toml.parse(synonyms)
        val entries = toml.getArray("entry")!!

        for (i in 0 until entries.size()) {
            val entry = entries.getTable(i)
            val canonical = entry.getString("canonical")!!
            val op = opFor[canonical] ?: continue // phrasal predicates (in / is null / between) tested below
            val phrases = entry.getArray("verbose")!!
            for (j in 0 until phrases.size()) {
                val phrase = phrases.getString(j)
                "verbose `$phrase` folds to $op (== canonical `$canonical`)" {
                    (pred("amount $phrase 5") as FunctionCall).function.value shouldBe op
                }
            }
        }

        "canonical `>` folds to the same op.gt tree as the verbose forms" {
            (pred("amount > 5") as FunctionCall).function.value shouldBe "op.gt"
        }

        "verbose + canonical mix in one predicate → one op.and tree" {
            val e = pred("amount is more than 0 and status = 'open'") as FunctionCall
            e.function.value shouldBe "op.and"
            (e.args[0] as FunctionCall).function.value shouldBe "op.gt"
            (e.args[1] as FunctionCall).function.value shouldBe "op.eq"
        }

        "3VL: `is empty` / `is not empty` fold to IsNull (matching typing)" {
            (pred("customer is empty") as IsNull).negated shouldBe false
            (pred("customer is not empty") as IsNull).negated shouldBe true
        }

        "`is one of (…)` folds to InList" {
            val e = pred("region is one of ('N', 'S')") as InList
            e.items.size shouldBe 2
        }
    })

private fun zero(): SourceLocation =
    SourceLocation(file = "<verbose>", line = 1, column = 0, endLine = 1, endColumn = 0, offsetStart = 0, offsetEnd = 0)
