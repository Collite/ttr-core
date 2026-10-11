// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.dialect.b

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe

/**
 * T7.1.1: every hero + roster sentence parses (C4-b roster, transcribed from
 * 12-nl-options.md), and the C4-b-ii = α synonym breadth (Keep/Take/Select,
 * Remove/Delete, where/which/that/with, verbose comparators) each parse clean.
 */
class TtrbParserSpec :
    StringSpec({
        "the bare hero island parses with no syntax errors" {
            TtrbCorpus.parseFixture("hero-sentences.ttrb").syntaxErrors.shouldBeEmpty()
        }

        "the roster fixture (one row per non-hero sentence) parses with no syntax errors" {
            TtrbCorpus.parseFixture("roster-sentences.ttrb").syntaxErrors.shouldBeEmpty()
        }

        // Per-row acceptance + synonym breadth. A `Load` precedes filters so refs resolve
        // lexically (parse-only here — resolution is a later stage).
        val rows =
            listOf(
                "Load from file \"d/x.csv\" as x." to "load-file",
                "Load erp.accounts as a." to "load-model",
                "Keep only the columns region, total." to "keep-columns",
                "Select the columns a as b, c." to "select-columns-rename",
                "Keep all columns except internal_id." to "keep-except",
                "Take all columns but internal_id." to "take-but-except",
                "Keep only the rows where amount is more than 0." to "keep-filter",
                "Take the rows where region is one of ('N', 'S')." to "take-filter-in",
                "Select rows which amount is bigger than 5." to "select-filter-which",
                "Filter for the rows where amount is not empty." to "filter-for",
                "Remove the rows where region is empty." to "remove-filter",
                "Delete rows where amount is less than 0." to "delete-filter",
                "Rename total to region_total." to "rename",
                "Rename the columns a as b, c as d." to "rename-list",
                "Convert region to string." to "convert",
                "Retype the type of amount to decimal(12,2)." to "retype",
                "Compute amount * 0.21 as vat." to "compute",
                "Create new column price - cost as margin." to "create",
                "Summarize sum of amount as total by region." to "summarize",
                "Summarize sum of a as s, count of b as n grouped by region, branch." to "summarize-multi",
                "Join accounts with sales on account_id is sales.account_id as joined." to "join",
                "Join that with sales on left is right." to "join-anaphora",
                "Sort that by total descending." to "sort",
                "Sort the rows by a, b ascending." to "sort-multi",
                "Keep only the first 10 rows." to "limit",
                "Combine that with archive_sales." to "combine",
                "Append it with more_sales." to "append",
                "Store that to file \"out/x.csv\"." to "store-file",
                "Store the result to erp.totals." to "store-model",
                "Show the result as region_totals." to "show",
                "Display me the result." to "display",
            )

        rows.forEach { (sentence, name) ->
            "roster row parses clean: $name" {
                val parsed = TtrbCorpus.parse(sentence)
                parsed.syntaxErrors.shouldBeEmpty()
                parsed.tree.item().size shouldBe 1
            }
        }

        "the three keep-verb synonyms all reach the same filter rule" {
            listOf("Keep", "Take", "Select").forEach { verb ->
                val p = TtrbCorpus.parse("$verb only the rows where amount is more than 0.")
                p.syntaxErrors.shouldBeEmpty()
                (
                    p.tree
                        .item(0)
                        .sentence()
                        .statement() is org.tatrman.ttrp.parser.generated.TTRBParser.FilterSentenceContext
                ) shouldBe
                    true
            }
        }
    })
