// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.dialect.b

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.tatrman.ttrp.diagnostics.TtrpDiagnosticId

/**
 * T7.1.6: each negative fixture produces EXACTLY its named diagnostic AND the suggestion
 * string read from the reject table (single source — the spec never duplicates the text).
 */
class TtrbRejectSpec :
    StringSpec({
        fun onlyDiag(rel: String) = TtrbCorpus.decompose(rel).diagnostics.single()

        fun suggest(id: String) = TtrB.rejects(TtrbSkin.EN).entry(id).suggest

        "reject-update.ttrb → TTRP-B-001 (no update — writes are Store)" {
            val d = onlyDiag("reject-update.ttrb")
            d.id shouldBe TtrpDiagnosticId.B_001
            d.suggestedAlternative shouldBe suggest("TTRP-B-001")
        }

        "reject-comment-style.ttrb → TTRP-B-005 (# comments, S19)" {
            val d = onlyDiag("reject-comment-style.ttrb")
            d.id shouldBe TtrpDiagnosticId.B_005
            d.suggestedAlternative shouldBe suggest("TTRP-B-005")
        }

        "reject-czech.ttrb → TTRP-B-006 (a Czech sentence in an English fragment)" {
            val d = onlyDiag("reject-czech.ttrb")
            d.id shouldBe TtrpDiagnosticId.B_006
            d.suggestedAlternative shouldBe suggest("TTRP-B-006")
        }

        "reject-double-eq.ttrb → TTRP-EQ-001 (S9: == is not equality)" {
            val d = onlyDiag("reject-double-eq.ttrb")
            d.id shouldBe TtrpDiagnosticId.EQ_001
            d.suggestedAlternative shouldBe suggest("TTRP-EQ-001")
        }

        "reject-unknown-verbose.ttrb → TTRP-B-007 (closed verbose table)" {
            val d = onlyDiag("reject-unknown-verbose.ttrb")
            d.id shouldBe TtrpDiagnosticId.B_007
            d.suggestedAlternative shouldBe suggest("TTRP-B-007")
        }
    })
