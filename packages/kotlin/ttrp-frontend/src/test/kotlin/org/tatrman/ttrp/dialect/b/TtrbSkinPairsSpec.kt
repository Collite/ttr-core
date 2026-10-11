// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.dialect.b

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe

/**
 * B2 — ONE grammar, two keyword skins. Every Czech fixture under `ttrb/skins/` has an English
 * twin; both parse clean under their own skin and lower to the SAME canonical tree: the same
 * sentence kinds, the same canonical statements (source spans aside) and the same derived
 * in-ports. Identifiers are written identically in both twins — they are never translated.
 */
class TtrbSkinPairsSpec :
    StringSpec({
        val pairs = listOf("hero", "roster", "predicates")

        for (name in pairs) {
            "skins/$name: the Czech fixture and its English twin both parse clean" {
                TtrbCorpus.parseFixture("skins/$name.ttrb").syntaxErrors.shouldBeEmpty()
                TtrbCorpus.parseFixture("skins/$name.ttrb-cs").syntaxErrors.shouldBeEmpty()
            }

            "skins/$name: the twins read as the same sentence kinds" {
                TtrbCorpus.sentenceKinds("skins/$name.ttrb-cs") shouldBe TtrbCorpus.sentenceKinds("skins/$name.ttrb")
            }

            "skins/$name: the twins lower to the same canonical statements" {
                val en = TtrbCorpus.decompose("skins/$name.ttrb")
                val cs = TtrbCorpus.decompose("skins/$name.ttrb-cs")
                en.diagnostics.shouldBeEmpty()
                cs.diagnostics.shouldBeEmpty()
                TtrbCorpus.canonical(cs.statements) shouldBe TtrbCorpus.canonical(en.statements)
                cs.derivedInPorts shouldBe en.derivedInPorts
            }
        }
    })
