// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.graph.identity

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.tatrman.ttrp.graph.TtrpPipeline
import org.tatrman.ttrp.graph.explain.NormalizedGraphJson
import org.tatrman.ttrp.project.TtrpManifestReader
import java.nio.file.Files
import java.nio.file.Path

/**
 * B4 — THE GATE for rules authoring: a decision written 100 % in Czech (and the same in English)
 * sentences — overlapping blocks, a count, attach, e-mail / set-field / manual-task actions with
 * column, department and fallback recipients — compiles to the SAME graph as the canonical TTR-P
 * program (a container whose outputs are one filter per block, each followed by the action's
 * calc/select, each wired to display(<kind>)): the normalized graph and `ttrp explain` are equal
 * modulo generated names.
 */
class TtrbRulesGraphSpec :
    StringSpec({
        val project = locate("packages/kotlin/ttrp-frontend/src/test/resources/ttrb-rules")
        val manifest = TtrpManifestReader.resolve(project).manifest

        fun plan(rel: String): TtrpPipeline.PlanResult {
            val file = project.resolve("programs/$rel")
            val p = TtrpPipeline(manifest, manifest.modelsRoot()).plan(Files.readString(file), file.toString())
            p.ok shouldBe true
            return p
        }

        fun graph(rel: String): ModuloNames.Normalized =
            ModuloNames.normalize(NormalizedGraphJson.write(plan(rel).graph!!))

        /** `ttrp explain` text modulo names; the header line (it names the program file) is dropped. */
        fun explain(rel: String): String {
            val file = project.resolve("programs/$rel")
            val out = TtrpPipeline(manifest, manifest.modelsRoot()).explain(Files.readString(file), file.toString())
            out.ok shouldBe true
            return graph(rel).apply(
                out.text
                    .lines()
                    .drop(1)
                    .joinToString("\n"),
            )
        }

        val scenarios =
            mapOf(
                "rozhodnuti" to listOf("cs", "en"),
                "sklad" to listOf("cs", "en"),
                // in-ports as names + `Pošli to na výstup <port>` outputs, embedded and file-backed
                "predani" to listOf("cs", "en", "soubor"),
                // optional join, have / have no match (semi / anti), `is [not] empty` in a join condition
                "odklad" to listOf("cs", "en"),
                // joins on a modelled relation (inner and optional)
                "vazba" to listOf("cs", "en"),
                // a conditional value (case when … then … else … end), `je prázdný` in its condition
                "priorita" to listOf("cs", "en"),
                // grouped conditions + named intermediate values (`Pojmenuj to jako …`, `Načti <jméno>`)
                "nazvy" to listOf("cs", "en"),
            )
        for ((scenario, langs) in scenarios) {
            for (lang in langs) {
                "$scenario-$lang.ttrp ≡ $scenario-canonical.ttrp — normalized graph, modulo names" {
                    graph("$scenario-$lang.ttrp").graph shouldBe graph("$scenario-canonical.ttrp").graph
                }

                "$scenario-$lang.ttrp ≡ $scenario-canonical.ttrp — `ttrp explain`, modulo names" {
                    explain("$scenario-$lang.ttrp") shouldBe explain("$scenario-canonical.ttrp")
                }
            }
        }

        "B5: file-backed ≡ embedded — the SAME normalized graph (no renaming needed) and explain" {
            NormalizedGraphJson.write(plan("rozhodnuti-soubor.ttrp").graph!!) shouldBe
                NormalizedGraphJson.write(plan("rozhodnuti-cs.ttrp").graph!!)
            explain("rozhodnuti-soubor.ttrp") shouldBe explain("rozhodnuti-cs.ttrp")
        }

        "B5: file-backed ≡ canonical, modulo names" {
            graph("rozhodnuti-soubor.ttrp").graph shouldBe graph("rozhodnuti-canonical.ttrp").graph
            explain("rozhodnuti-soubor.ttrp") shouldBe explain("rozhodnuti-canonical.ttrp")
        }

        "non-vacuous: the decision graph carries the actions, the count and the routing" {
            val g = graph("rozhodnuti-cs.ttrp").graph
            g shouldContain "Display(name=send_email)"
            g shouldContain "Display(name=update_field)"
            g shouldContain "Join(type=CROSS"
            g shouldContain "agg.count(col(počet_reklamací))"
            g shouldContain "fn.coalesce(col(email_zástupce),lit(Str(value=oddělení:obchod)))"
            g shouldContain "rozhodnuti target=erp"
            explain("rozhodnuti-cs.ttrp") shouldContain
                "payload=sql:rozhodnuti.send_email,sql:rozhodnuti.update_field"
        }
    }) {
    companion object {
        fun locate(rel: String): Path {
            var dir: Path? = Path.of("").toAbsolutePath()
            while (dir != null) {
                if (Files.isDirectory(dir.resolve(rel))) return dir.resolve(rel)
                dir = dir.parent
            }
            error("could not locate $rel")
        }
    }
}
