// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.lsp.format

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.tatrman.ttrp.parser.TtrpParser

/**
 * TTRP 0.5 (CN-P2 S2.6): the formatter's expression renderer lays the conceptual-model forms out as written, so a
 * formatted document re-parses to the same tree; and the inequality renders `<>` — TTR-P's only inequality (it was
 * rendered `!=`, which does not lex).
 */
class ConceptFormsRenderSpec :
    StringSpec({
        fun render(source: String): String {
            val parsed = TtrpParser.parseExpression(source)
            parsed.diagnostics.shouldBeEmpty()
            return ExprRenderer.render(parsed.expression)
        }

        "each form renders as written and re-renders to itself" {
            listOf(
                "exists Invoicing involving this as customer where OverdueInvoice",
                "not exists OrderLine involving this.request.order, this.item",
                "exists Encounter e1, Encounter e2 involving this as patient where e1 <> e2",
                "count(StoreVisit involving this) >= 4",
                "latest(Inventory involving this.item, this.warehouse, by: as_of_date).quantity_on_hand",
                "code not in descendants(Diagnosis, \"I21\")",
                "e.period contains :as_of",
                "this.period.at - this.from.period.end",
                "(exists A involving this where x = 1) or y = 2",
            ).forEach { source ->
                val rendered = render(source)
                rendered shouldBe source
                render(rendered) shouldBe rendered
            }
        }

        "the inequality renders `<>`, which re-parses" {
            render("a <> b") shouldBe "a <> b"
        }
    })
