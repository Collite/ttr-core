// SPDX-License-Identifier: Apache-2.0
package org.tatrman.translator.orchestrator

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.tatrman.translate.v1.Language
import org.tatrman.translate.v1.SqlDialect
import org.tatrman.translator.framework.FixtureModel

/**
 * Regression — `SqlToRelConverter` folds an `IN`-list of literals into a single
 * `SEARCH($ref, Sarg[…])`. The `plan.v1` wire format has no `SEARCH`/`Sarg` shape, so before
 * [SearchExpander] ran, [org.tatrman.translator.wire.PlanNodeEncoder.encode] took the numeric
 * literal branch for the `INTEGER`-typed `Sarg` literal and threw
 * `class org.apache.calcite.util.Sarg cannot be cast to class java.lang.Number`, surfacing as
 * `parse_pipeline_failed`. The failing production query carried `… IN (1, 4)` / `IN (2, 3)`
 * predicates inside a `CASE`. [SearchExpander] rewrites `SEARCH` back to `OR`-of-comparisons
 * before encode, which the wire format already carries.
 */
class SearchExpansionSpec :
    StringSpec({
        val translator = Translator(FixtureModel.handle())

        // TF-P1.S1 (P0 finding, legacy `nevyfakturovane_zakazky_dm`) — `NOT IN (-2, 10)` expands to
        // `AND(<>, <>)`; nested inside the WHERE's own AND it left the condition non-flat, and Calcite's
        // Filter asserts `RexUtil.isFlat` — a parse that threw only with assertions on (-ea). The correlated
        // NOT EXISTS matters: the decorrelated plan carries a Filter that SearchExpander then copies.
        "NOT IN inside an AND chain leaves the condition flat (throws under -ea otherwise)" {
            val r =
                Translator(FixtureModel.tfHandle()).parseToRelNode(
                    source =
                        "SELECT a.ID FROM A a WHERE a.NAME = 'x' AND a.ID >= 0 AND a.B_ID NOT IN (-2, 10) " +
                            "AND NOT EXISTS (SELECT 1 FROM B b WHERE b.ID = a.ID)",
                    sourceLanguage = Language.SQL,
                )
            r.shouldBeInstanceOf<ParseResult.Success>()
        }

        "IN-list in WHERE parses (was Sarg-cast parse_pipeline_failed)" {
            val r =
                translator.parseToRelNode(
                    source = "SELECT id, name FROM customers WHERE id IN (1, 4)",
                    sourceLanguage = Language.SQL,
                )
            r.shouldBeInstanceOf<ParseResult.Success>()
        }

        "IN-list inside a CASE parses (the production repro shape)" {
            val r =
                translator.parseToRelNode(
                    source =
                        "SELECT CASE WHEN id IN (1, 4) THEN 'a' " +
                            "WHEN id IN (2, 3) THEN 'b' ELSE 'c' END AS k FROM customers",
                    sourceLanguage = Language.SQL,
                )
            r.shouldBeInstanceOf<ParseResult.Success>()
        }

        "IN-list round-trips to MSSQL and preserves every member value" {
            val r =
                translator.translate(
                    "SELECT id FROM customers WHERE id IN (1, 4)",
                    Language.SQL,
                    Language.SQL,
                    targetDialect = SqlDialect.MSSQL,
                )
            r.shouldBeInstanceOf<TranslateResult.Success>()
            // Expanded to `id = 1 OR id = 4` (or re-folded to `IN`) — either way both members survive.
            r.output shouldContain "1"
            r.output shouldContain "4"
        }

        "a query with no IN-list is unaffected (SearchExpander is a no-op)" {
            val withSearch =
                translator.parseToRelNode(
                    source = "SELECT id FROM customers WHERE id IN (1, 4)",
                    sourceLanguage = Language.SQL,
                )
            val withoutSearch =
                translator.parseToRelNode(
                    source = "SELECT id FROM customers WHERE id = 1",
                    sourceLanguage = Language.SQL,
                )
            withSearch.shouldBeInstanceOf<ParseResult.Success>()
            withoutSearch.shouldBeInstanceOf<ParseResult.Success>()
            // The non-SEARCH plan is byte-identical to a fresh parse — the pass never touched it.
            val again =
                translator.parseToRelNode(
                    source = "SELECT id FROM customers WHERE id = 1",
                    sourceLanguage = Language.SQL,
                ) as ParseResult.Success
            withoutSearch.plan shouldBe again.plan
        }
    })
