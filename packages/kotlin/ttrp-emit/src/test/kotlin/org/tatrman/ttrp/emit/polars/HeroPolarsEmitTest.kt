// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.emit.polars

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.string.shouldContain
import org.tatrman.ttr.metadata.fixtures.MetadataFixtures
import org.tatrman.ttrp.emit.GoldenSupport
import org.tatrman.ttrp.graph.TtrpPipeline
import org.tatrman.ttrp.project.TtrpManifest
import java.nio.file.Files
import java.nio.file.Paths

/**
 * T3.2.5 / RJ-P5 5.2.2 — the hero's Polars island (`crunch`) emitted from the real front-half →
 * graph → emit pipeline via [PolarsGraphEmitter]. Byte-pinned as `hero_crunch.py`.
 *
 * The hero now taps a **live** reject site: `checked = raw -> calc { customer_id = cast(customer as
 * int) }` with `rejects = checked.rejects`. RJ-P1 elaborates it (guard → branch → reject project)
 * and re-wires the reject producer onto a normal `.out`, so the island emits the canonical validity
 * mask, the reject frame, and a `counts.json` — the A5 flagship demonstrates rejects end-to-end.
 * The reject guard splits the raw sales BEFORE the join+aggregate, so the mainline is unchanged.
 */
class HeroPolarsEmitTest :
    FunSpec({
        val heroSource = Files.readString(Paths.get("src/test/resources/fixtures/hero.ttrp"))

        fun plan() =
            TtrpPipeline(
                TtrpManifest(world = "acme.worlds.dev", manifestDir = MetadataFixtures.erpProjectRoot()),
                MetadataFixtures.erpModelsRoot(),
            ).plan(heroSource, "hero.ttrp")

        test("hero crunch Polars island emits the join/aggregate/branch mainline") {
            val plan = plan()
            plan.ok.shouldBeTrue()
            val g = plan.graph!!
            val island = plan.exec!!.islands.single { it.engine == "polars" }
            val container = g.containers.getValue(island.id)
            val emitter = PolarsGraphEmitter(g, plan.bound!!)
            val steps = emitter.steps(container)
            val script = PolarsIslandEmitter().emit(island.name, steps, partitions = emitter.partitions(container)).text

            // Mainline shape (the A4 crunch): staged accounts read, CSV sales, filter, equi-join,
            // group-by aggregate, and the two branch-lowered filters + display/store sinks.
            script shouldContain "accounts = pl.read_ipc(\"staging/accounts.arrow\")"
            script shouldContain "pl.read_csv"
            script shouldContain ".join("
            script shouldContain "left_on="
            script shouldContain ".group_by("
            script shouldContain ".sum()).otherwise(None).alias(\"total\")"
            script shouldContain "out/main_result.arrow"

            // The hero's rejects wire is now LIVE (RJ-P5 re-author): the guard's canonical int64 mask,
            // the reject frame, and the rejects sink are all emitted — no longer a dead-wire skip.
            script shouldContain ".alias(\"_ttrp_v1\")"
            script shouldContain ".str.contains(r\"^[+-]?[0-9]+\$\")"
            script shouldContain ".alias(\"_ttrp_reject_code\")"
            script shouldContain "staging/rejects.arrow"
            script shouldContain "with open(\"counts.json\", \"w\")"
            // the rewired reject producer is mapped onto a `.out`, so it is NOT a dead wire anymore.
            val rejectsPort =
                container.portMapping.entries
                    .first { it.key == "rejects" }
                    .key
            emitter.isRejectsPort(container, rejectsPort).shouldBeFalse()

            GoldenSupport.assertMatchesGolden(script, "polars/hero_crunch.py")
        }
    })
