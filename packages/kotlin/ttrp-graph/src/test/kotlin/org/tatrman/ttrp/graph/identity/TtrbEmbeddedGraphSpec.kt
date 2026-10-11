// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.graph.identity

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.tatrman.ttrp.graph.GraphFixtures
import org.tatrman.ttrp.graph.explain.NormalizedGraphJson

/**
 * T7.1.7 (embedded surface): a `"""ttrb` hero island decomposes (sentence→node, C2-a-β) and
 * flows through the SHARED `TtrpChecker` + `GraphBuilder` FlowBody paths to the standard node
 * set — the same decompose-to-canonical machinery the P6 SQL/pandas dialects use. Serializing
 * the normalized graph is deterministic (P2) and non-vacuous (the real roster is present).
 *
 * The byte-identical embedded ≡ canonical gate now holds too — see `TtrbGraphIdentitySpec` (the two
 * former shared-infra deltas — literal load source dropped by `refText`; fragment-vs-FlowBody
 * out-mapping — were fixed in GraphBuilder). The bare surface lands with the T6.3.3 wrapper synthesis.
 */
class TtrbEmbeddedGraphSpec :
    StringSpec({
        val src =
            TtrbEmbeddedGraphSpec::class.java
                .getResourceAsStream("/ttrb/hero-embedded.ttrp")!!
                .bufferedReader()
                .readText()

        "the embedded \"\"\"ttrb hero serializes deterministically" {
            NormalizedGraphJson.write(GraphFixtures.graphOf(src)) shouldBe
                NormalizedGraphJson.write(GraphFixtures.graphOf(src))
        }

        "the decomposed hero roster is present (non-vacuous): Load, Join, Filter, Aggregate, Sort, Limit, Display" {
            val norm = NormalizedGraphJson.write(GraphFixtures.graphOf(src))
            // `Load accounts as accounts.` names the container's IN port — read as the port, never loaded (B7).
            norm.contains("Load(source=accounts") shouldBe false
            listOf(
                "region_totals.accounts -DATA-> joined#1.left",
                "sales#1 = Load(source=data/sales.csv",
                "Join(type=INNER, on=op.eq(col(account_id),col(right.account_id))",
                "Filter(op.and(op.gt(col(amount)",
                "Aggregate(by=[region], aggs=total=agg.sum(col(amount))",
                "Sort([total])",
                "Limit(10)",
                "Display(name=region_totals)",
            ).all { norm.contains(it) } shouldBe true
        }
    })
