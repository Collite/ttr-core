// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.lsp.nav

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.tatrman.ttrp.ast.SourceLocation
import org.tatrman.ttrp.diagnostics.Severity
import org.tatrman.ttrp.diagnostics.TtrpDiagnostic
import org.tatrman.ttrp.diagnostics.TtrpDiagnosticId

/** A finding made inside a file-backed fragment is shown on the program's `from "<path>"` clause (AG B5). */
class FragmentLinksSpec :
    FunSpec({
        val program = "file:///project/release.ttrp"
        val clause = SourceLocation(program, 4, 10, 4, 34, 60, 84)
        val link = FragmentLinks.Link(clause, "file:///project/frag%20one.ttrb", "frag one.ttrb")

        fun finding(at: SourceLocation) = TtrpDiagnostic(TtrpDiagnosticId.REC_020, Severity.ERROR, "unbound", at)

        test("a fragment finding moves onto the clause, prefixed with its own position") {
            val d = finding(SourceLocation("frag one.ttrb", 3, 2, 3, 9, 20, 27))
            val shown = FragmentLinks.anchor(d, program, listOf(link))
            shown.location shouldBe clause
            shown.message shouldBe "frag one.ttrb:3:2: unbound"
        }

        test("a fragment finding with no position of its own still moves onto the clause, unprefixed") {
            val d = finding(SourceLocation("frag one.ttrb", -1, -1, -1, -1, -1, -1))
            val shown = FragmentLinks.anchor(d, program, listOf(link))
            shown.location shouldBe clause
            shown.message shouldBe "unbound"
        }

        test("a finding of the program itself is unchanged") {
            val d = finding(SourceLocation(program, 2, 0, 2, 5, 10, 15))
            FragmentLinks.anchor(d, program, listOf(link)) shouldBe d
        }
    })
