// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.dialect.b

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldMatch
import org.tatrman.ttrp.diagnostics.Severity

/**
 * B6 — every reject row of every skin has a fixture that triggers it: `ttrb/rejects/<lang>/<id>.ttrb`
 * (`.ttrb-cs` for Czech) decomposes to EXACTLY that one diagnostic, carrying the row's message (in the
 * skin's language; `{word}` = the offending word) and the row's suggested sentence.
 */
class TtrbRejectFixturesSpec :
    StringSpec({
        for (skin in TtrbSkin.all) {
            val ext = if (skin === TtrbSkin.CS) "ttrb-cs" else "ttrb"
            for (row in TtrB.rejects(skin).rows) {
                "${skin.lang}: ${row.id} — its fixture triggers exactly it, with the row's message and suggestion" {
                    val rel = "rejects/${skin.lang}/${row.id}.$ext"
                    // A first line `# out: a, b` declares the container's OUT ports for the fixture.
                    val outs =
                        TtrbCorpus
                            .read(rel)
                            .lineSequence()
                            .first()
                            .takeIf { it.startsWith("# out:") }
                            ?.substringAfter(":")
                            ?.split(",")
                            ?.map { it.trim() }
                            ?.toSet()
                    val d = TtrbCorpus.decompose(rel, outPorts = outs).diagnostics.single()
                    d.id.id shouldBe row.id
                    d.severity shouldBe Severity.ERROR
                    d.suggestedAlternative shouldBe row.suggest
                    val pattern = row.message.split("{word}").joinToString(".+") { Regex.escape(it) }
                    d.message shouldMatch Regex(pattern, RegexOption.DOT_MATCHES_ALL)
                }
            }
        }
    })
