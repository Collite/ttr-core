// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.diagnostics

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.tatrman.ttr.semantics.RemovedConstructs.Cut
import java.util.Locale

/**
 * The whole-catalogue id-uniqueness gate (Phase-1 DONE bar) + the contracts-§8
 * convention that every rejected form carries a non-blank suggested alternative.
 */
class TtrpDiagnosticIdSpec :
    StringSpec({

        "the diagnostics catalogue has no id-string collisions" {
            TtrpDiagnosticId.assertNoDuplicateIds()
        }

        "every resolution-tier id carries a non-blank suggested alternative" {
            val tiers = setOf("WLD", "RES", "SCH", "CFG", "MOV")
            val missing =
                TtrpDiagnosticId.entries
                    .filter { it.id.substringAfter("TTRP-").substringBefore('-') in tiers }
                    .filter { it.suggestedAlternative.isNullOrBlank() }
                    .map { it.id }
            missing shouldBe emptyList()
        }

        "a removed construct's TTRP-CUT id does not depend on the default locale's digits (D-142)" {
            val saved = Locale.getDefault()
            try {
                for (tag in listOf("ar-SA", "fa-IR", "hi-IN-u-nu-deva")) {
                    Locale.setDefault(Locale.forLanguageTag(tag))
                    Cut.PROCESS.ref shouldBe "CUT-001"
                    TtrpDiagnosticId.of(Cut.PROCESS) shouldBe TtrpDiagnosticId.CUT_001
                    Cut.entries.map { TtrpDiagnosticId.of(it).id } shouldBe Cut.entries.map { "TTRP-${it.ref}" }
                }
            } finally {
                Locale.setDefault(saved)
            }
        }
    })
