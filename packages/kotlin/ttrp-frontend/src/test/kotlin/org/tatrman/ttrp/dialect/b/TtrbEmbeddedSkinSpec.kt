// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.dialect.b

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.tatrman.ttrp.ast.ContainerDecl
import org.tatrman.ttrp.ast.FragmentBody
import org.tatrman.ttrp.parser.TtrpParser

/**
 * B2 — the embedded marker: `"""ttrb-cs` is a Czech TTR-B fragment (`"""ttrb` stays English). The
 * tag picks the keyword skin; the interior stays byte-verbatim (C2-f). An unknown `ttrb-<lang>` is
 * the ordinary unknown-dialect diagnostic.
 */
class TtrbEmbeddedSkinSpec :
    StringSpec({
        fun body(src: String): FragmentBody =
            TtrpParser
                .parseString(src, "skin.ttrp")
                .document.statements
                .filterIsInstance<ContainerDecl>()
                .single()
                .body as FragmentBody

        val czech =
            "container c(out r) target t \"\"\"ttrb-cs\n" +
                "Načti objednávky jako o.\n" +
                "Ponech řádky, kde částka je větší než 100.\n" +
                "\"\"\"\n"

        "`\"\"\"ttrb-cs` decomposes with the Czech skin — clean, no unknown-dialect diagnostic" {
            val parsed = TtrpParser.parseString(czech, "skin.ttrp")
            parsed.diagnostics.shouldBeEmpty()
            val b = body(czech)
            b.tag shouldBe "ttrb-cs"
            b.decomposition shouldNotBe null
            b.sourceText shouldBe "Načti objednávky jako o.\nPonech řádky, kde částka je větší než 100.\n"
        }

        "the Czech fragment lowers exactly as its English twin" {
            val english =
                "container c(out r) target t \"\"\"ttrb\n" +
                    "Load objednávky as o.\n" +
                    "Keep the rows where částka is bigger than 100.\n" +
                    "\"\"\"\n"
            TtrbCorpus.canonical(body(czech).decomposition!!.statements) shouldBe
                TtrbCorpus.canonical(body(english).decomposition!!.statements)
        }

        "an unknown TTR-B language tag is TTRP-FRG-001" {
            val src = "container c(out r) target t \"\"\"ttrb-de\nLade a.\n\"\"\"\n"
            TtrpParser.parseString(src, "skin.ttrp").diagnostics.map { it.id.id } shouldContain "TTRP-FRG-001"
        }
    })
