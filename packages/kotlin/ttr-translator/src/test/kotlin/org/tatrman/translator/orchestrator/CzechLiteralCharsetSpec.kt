// SPDX-License-Identifier: Apache-2.0
package org.tatrman.translator.orchestrator

import org.tatrman.translate.v1.Language
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.types.shouldBeInstanceOf
import org.tatrman.translator.framework.FixtureModel

/**
 * Regression for a hosted test estate's drill-down failure: a string literal carrying Czech letters
 * outside ISO-8859-1 (Calcite's historical default charset) — `š č ř ž ů ě …` — used to fail
 * SQL→Rel conversion with "Failed to encode 'Poštovné' in character set 'ISO-8859-1'", which
 * reached callers as a bare parse error. [org.tatrman.translator.framework.TranslatorFramework]
 * now promotes Calcite's default charset/collation to UTF-8 so Unicode literals convert.
 *
 * `customers.name` is a TEXT column in [FixtureModel]; comparing it to a literal exercises the
 * literal's charset, which is what blew up. The boundary is ISO-8859-1 encodability, so we
 * cover: pure ASCII, Latin-1-only accents (used to pass), and true non-Latin-1 Czech (used to
 * fail) — plus the original two-table drill-down shape.
 */
class CzechLiteralCharsetSpec :
    StringSpec({

        val translator = Translator(FixtureModel.handle())

        fun parseOk(sql: String) {
            val r = translator.parseToRelNode(sql, Language.SQL)
            withClue(sql) { r.shouldBeInstanceOf<ParseResult.Success>() }
        }

        "ASCII literal parses (control)" {
            parseOk("SELECT id FROM customers WHERE name = 'Postovne'")
        }

        "Latin-1-only accented literal parses (always worked)" {
            parseOk("SELECT id FROM customers WHERE name = 'éáíóú'")
        }

        "non-Latin-1 Czech literal parses — the regression ('š' = U+0161)" {
            parseOk("SELECT id FROM customers WHERE name = 'Poštovné'")
        }

        "full Czech alphabet outside Latin-1 parses" {
            parseOk("SELECT id FROM customers WHERE name = 'ščřžůěťďňcore'")
        }

        "Czech literal survives the join + projection drill-down shape" {
            parseOk(
                "SELECT o.id, o.total FROM orders o " +
                    "INNER JOIN customers c ON o.customer_id = c.id " +
                    "WHERE c.name = 'Poštovné'",
            )
        }

        "non-equality use (IN list) of Czech literals parses" {
            parseOk("SELECT id FROM customers WHERE name IN ('Poštovné', 'Příjmy', 'Výdaje')")
        }
    })
