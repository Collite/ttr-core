// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.dialect.b

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotBeBlank
import org.tatrman.ttrp.diagnostics.TtrpDiagnosticId

/**
 * B6 — the reject tables are complete, versioned fixtures PER SKIN (`rejects.en.yaml`,
 * `rejects.cs.yaml`): the same ids in both, every TTRP-B diagnostic id plus the shared TTRP-EQ-001
 * has a row with a message in that language and a suggested correct sentence (the assist repair
 * vocabulary, C4-b-iii).
 */
class TtrbRejectTableSpec :
    StringSpec({
        val bIds = TtrpDiagnosticId.entries.map { it.id }.filter { it.startsWith("TTRP-B-") }

        for (skin in TtrbSkin.all) {
            "rejects.${skin.lang}.yaml: every TTRP-B id + TTRP-EQ-001 has a row with a message and a suggestion" {
                val table = TtrB.rejects(skin)
                table.lang shouldBe skin.lang
                for (id in bIds + "TTRP-EQ-001") {
                    val row = table.entry(id)
                    row.message.shouldNotBeBlank()
                    row.suggest.shouldNotBeBlank()
                }
            }

            "rejects.${skin.lang}.yaml: no row names an id the diagnostics catalogue lacks" {
                (TtrB.rejects(skin).ids() - (bIds + "TTRP-EQ-001").toSet()).shouldBeEmpty()
            }
        }

        "both skins carry the same ids, with their own wording" {
            TtrB.rejects(TtrbSkin.CS).ids() shouldBe TtrB.rejects(TtrbSkin.EN).ids()
            (
                TtrB.rejects(TtrbSkin.CS).entry("TTRP-B-104").suggest ==
                    TtrB.rejects(TtrbSkin.EN).entry("TTRP-B-104").suggest
            ) shouldBe
                false
        }

        "the shared TTRP-EQ-001 entry keeps its English suggestion (S9 repair vocabulary)" {
            TtrB.rejectTable.entry("TTRP-EQ-001").suggest shouldBe "use ="
        }

        "a trigger word belongs to one row of its table" {
            for (skin in TtrbSkin.all) {
                val triggers = TtrB.rejects(skin).rows.flatMap { r -> r.triggers.map { it to r.id } }
                triggers
                    .groupBy { it.first }
                    .filterValues { it.size > 1 }
                    .keys
                    .shouldBeEmpty()
            }
        }
    })
