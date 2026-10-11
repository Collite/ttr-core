// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.graph.identity

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.tatrman.ttrp.graph.GraphFixtures
import org.tatrman.ttrp.graph.explain.NormalizedGraphJson

/**
 * THE KEY GATE for TTR-B (T6.3.5): the hero island authored as a `"""ttrb` fragment and the SAME
 * island written in canonical TTR-P compile to BYTE-IDENTICAL normalized graphs (canonical
 * serialization, byte compare). Completes the P6/P7 gate to a third dialect once the two shared-infra
 * deltas were fixed in GraphBuilder — `refText` stringifies a literal load source, and the single
 * default DATA out auto-maps uniformly for a FlowBody and a fragment.
 */
class TtrbGraphIdentitySpec :
    StringSpec({
        fun norm(rel: String): String =
            NormalizedGraphJson.write(
                GraphFixtures.graphOf(
                    TtrbGraphIdentitySpec::class.java
                        .getResourceAsStream("/ttrb/$rel")!!
                        .bufferedReader()
                        .readText(),
                ),
            )

        "embedded \"\"\"ttrb ≡ canonical — the hero island, byte-identical normalized graphs" {
            norm("hero-embedded.ttrp") shouldBe norm("hero-canonical.ttrp")
        }

        "embedded \"\"\"ttrb-cs (the Czech skin) ≡ canonical — the same hero, byte-identical normalized graphs" {
            norm("hero-embedded-cs.ttrp") shouldBe norm("hero-canonical.ttrp")
        }

        "the normalized graph is non-vacuous (the real hero roster is present)" {
            val n = norm("hero-canonical.ttrp")
            listOf(
                "Join(type=INNER",
                "Aggregate(by=[region]",
                "agg.sum(col(amount))",
                "Sort([total])",
                "Limit(10)",
                "Load(source=data/sales.csv",
            ).all { n.contains(it) } shouldBe true
        }
    })
