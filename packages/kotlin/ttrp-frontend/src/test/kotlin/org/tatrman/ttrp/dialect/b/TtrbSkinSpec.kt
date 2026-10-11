// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.dialect.b

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.tatrman.ttrp.ast.Assignment
import org.tatrman.ttrp.ast.ExprArg
import org.tatrman.ttrp.ast.OpCall
import org.tatrman.ttrp.ast.SourceLocation
import org.tatrman.ttrp.diagnostics.TtrpDiagnosticId
import org.tatrman.ttrp.expr.ColumnRef

/**
 * B2 — the keyword tables (`roster.en.yaml`, `roster.cs.yaml`) are complete skins over the one
 * grammar: keywords match case- and diacritic-insensitively against the ACTIVE skin only;
 * identifiers (Latin letters incl. Czech) are matched exactly and never folded.
 */
class TtrbSkinSpec :
    StringSpec({
        fun decompose(
            src: String,
            skin: TtrbSkin,
        ) = TtrB.decompose(src, SourceLocation.UNKNOWN, null, skin)

        fun loadSource(
            src: String,
            skin: TtrbSkin,
        ): String {
            val d = decompose(src, skin)
            d.diagnostics.shouldBeEmpty()
            val load =
                d.statements
                    .filterIsInstance<Assignment>()
                    .single()
                    .chain.elements
                    .single() as OpCall
            return ((load.args.first().value as ExprArg).expr as ColumnRef).column
        }

        "the two skins load, with their language and fragment tag" {
            TtrbSkin.EN.lang shouldBe "en"
            TtrbSkin.CS.lang shouldBe "cs"
            TtrbSkin.forTag("ttrb") shouldBe TtrbSkin.EN
            TtrbSkin.forTag("ttrb-cs") shouldBe TtrbSkin.CS
            TtrbSkin.forTag("ttrb-de") shouldBe null
        }

        "every keyword token of the grammar is spelled by each skin, or declared absent by it" {
            for (skin in TtrbSkin.all) {
                val unspelled = TtrbSkin.KEYWORD_TOKENS.filter { it !in skin.spelledTokens && it !in skin.absent }
                withClue(skin.lang) { unspelled.shouldBeEmpty() }
            }
        }

        "a skin that spells one word for two keywords is refused at load" {
            val clash = "lang: xx\nkeywords:\n  LOAD: [nacti]\n  KEEP: [načti]\n"
            shouldThrow<IllegalArgumentException> { TtrbSkin.parse(clash, "ttrb-xx") }.message shouldContain "nacti"
        }

        "a skin naming a token the grammar does not have is refused at load" {
            val bad = "lang: xx\nkeywords:\n  FROBNICATE: [frob]\n"
            shouldThrow<IllegalArgumentException> { TtrbSkin.parse(bad, "ttrb-xx") }.message shouldContain "FROBNICATE"
        }

        "keywords are case- and diacritic-insensitive: Načti = nacti = NAČTI = NACTI" {
            listOf("Načti", "nacti", "NAČTI", "NACTI", "načti").forEach { verb ->
                loadSource("$verb objednávky jako o.", TtrbSkin.CS) shouldBe "objednávky"
            }
            listOf("Load", "LOAD", "load").forEach { verb ->
                loadSource("$verb orders as o.", TtrbSkin.EN) shouldBe "orders"
            }
        }

        "identifiers stay exact: case and diacritics are kept, never folded" {
            loadSource("Načti Objednávky jako o.", TtrbSkin.CS) shouldBe "Objednávky"
            loadSource("Načti objednavky jako o.", TtrbSkin.CS) shouldBe "objednavky"
            loadSource("Načti objednávky jako o.", TtrbSkin.CS) shouldBe "objednávky"
        }

        "Czech letters are legal in identifiers under the English skin too" {
            loadSource("Load objednávky as o.", TtrbSkin.EN) shouldBe "objednávky"
            val d = decompose("Load objednávky as o.\nKeep the rows where částka is more than 0.", TtrbSkin.EN)
            d.diagnostics.shouldBeEmpty()
        }

        "a word that only folds to a keyword in the OTHER skin is not a keyword here" {
            // `jako` is the Czech AS — under the English skin it is an ordinary identifier.
            loadSource("Load jako as o.", TtrbSkin.EN) shouldBe "jako"
        }

        "an English sentence in a Czech fragment names the skin mismatch (TTRP-B-006)" {
            decompose("Load orders as o.", TtrbSkin.CS).diagnostics.single().id shouldBe TtrpDiagnosticId.B_006
        }

        "a Czech sentence in an English fragment names the skin mismatch (TTRP-B-006)" {
            decompose("Načti objednávky jako o.", TtrbSkin.EN).diagnostics.single().id shouldBe TtrpDiagnosticId.B_006
        }

        "Czech aggregate names are skin aliases of the catalogue functions" {
            val stmts =
                decompose(
                    "Načti x jako t.\nShrň součet castka jako celkem, průměr castka jako prumer podle oblast.",
                    TtrbSkin.CS,
                )
            stmts.diagnostics.shouldBeEmpty()
            val en =
                decompose(
                    "Load x as t.\nSummarize sum of castka as celkem, avg of castka as prumer by oblast.",
                    TtrbSkin.EN,
                )
            TtrbCorpus.canonical(stmts.statements) shouldBe TtrbCorpus.canonical(en.statements)
        }

        "the hyphen joins a keyword word only where the skin spells it so — `a-b` stays arithmetic" {
            val d = decompose("Load x as a.\nCompute price-cost as margin.", TtrbSkin.EN)
            d.diagnostics.shouldBeEmpty()
            TtrbCorpus.canonical(d.statements) shouldContain "op.sub"
        }

        "a fixture's sentence kinds are read from the skin-classified parse tree" {
            TtrbCorpus.sentenceKinds("skins/hero.ttrb-cs") shouldContainExactly
                listOf("load", "load", "join", "filter", "summarize", "sort", "limit", "show")
        }
    })

private fun <T> withClue(
    clue: String,
    block: () -> T,
): T = io.kotest.assertions.withClue(clue, block)
