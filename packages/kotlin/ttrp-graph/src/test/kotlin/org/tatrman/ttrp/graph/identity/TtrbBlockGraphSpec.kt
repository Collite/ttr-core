// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.graph.identity

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.tatrman.ttr.metadata.fixtures.MetadataFixtures
import org.tatrman.ttrp.graph.GraphFixtures
import org.tatrman.ttrp.graph.TtrpPipeline
import org.tatrman.ttrp.graph.explain.NormalizedGraphJson
import org.tatrman.ttrp.project.TtrpManifest

/**
 * B3 — a TTR-B fragment with two overlapping `If`/`Když` blocks builds the graph a hand-written
 * canonical container gives with one `filter` per output (both over the same value), modulo the
 * names of the intermediate values: the same normalized graph AND the same `ttrp explain`.
 */
class TtrbBlockGraphSpec :
    StringSpec({
        fun src(rel: String): String =
            TtrbBlockGraphSpec::class.java
                .getResourceAsStream("/ttrb/$rel")!!
                .bufferedReader()
                .readText()

        fun graph(rel: String): ModuloNames.Normalized =
            ModuloNames.normalize(NormalizedGraphJson.write(GraphFixtures.graphOf(src(rel))))

        fun explain(rel: String): String {
            val out =
                TtrpPipeline(
                    TtrpManifest(world = "acme.worlds.dev", manifestDir = GraphFixtures.root),
                    MetadataFixtures.erpModelsRoot(),
                ).explain(src(rel), "blocks.ttrp")
            out.ok shouldBe true
            return graph(rel).apply(out.text)
        }

        "the English blocks ≡ the canonical one-filter-per-output container (graph, modulo names)" {
            graph("blocks-embedded.ttrp").graph shouldBe graph("blocks-canonical.ttrp").graph
        }

        "the Czech blocks ≡ the same canonical container (graph, modulo names)" {
            graph("blocks-embedded-cs.ttrp").graph shouldBe graph("blocks-canonical.ttrp").graph
        }

        "`ttrp explain` agrees too, modulo names" {
            explain("blocks-embedded.ttrp") shouldBe explain("blocks-canonical.ttrp")
            explain("blocks-embedded-cs.ttrp") shouldBe explain("blocks-canonical.ttrp")
        }

        "the comparison discriminates: chaining the second filter off the first (else-if style) is NOT equal" {
            (graph("blocks-chained.ttrp").graph == graph("blocks-canonical.ttrp").graph) shouldBe false
        }

        "non-vacuous: two filters, both fed by the one load" {
            val g = graph("blocks-embedded.ttrp").graph
            g shouldContain "Filter(op.eq(col(region),lit(Str(value=N))))"
            g shouldContain "Filter(op.or("
        }
    })
