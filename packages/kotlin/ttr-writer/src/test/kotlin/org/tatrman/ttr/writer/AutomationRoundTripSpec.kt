// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.writer

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.tatrman.ttr.parser.loader.ParseResult
import org.tatrman.ttr.parser.loader.TtrLoader
import org.tatrman.ttr.parser.model.AutoValue
import org.tatrman.ttr.parser.model.AutomationDef
import org.tatrman.ttr.parser.model.EntityDef
import org.tatrman.ttr.parser.model.RoleDef
import org.tatrman.ttr.parser.model.WorldDef
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Grammar 0.16 — the automation model survives write → reparse (AU-core-P0 S0.2 T7). Byte-stability
 * is render-idempotence (`render∘parse` is a fixed point) plus a structural check that every typed
 * property and free-form value form survives the trip, on the conformance fixtures and on design
 * `17` §1–§2 as written for 0.16.
 */
class AutomationRoundTripSpec :
    StringSpec({

        fun roundTrip(src: String): Pair<ParseResult, String> {
            val r1 = TtrLoader.parseString(src)
            r1.errors shouldBe emptyList()
            val text1 = TtrRenderer.render(r1)
            val r2 = TtrLoader.parseString(text1)
            r2.errors shouldBe emptyList()
            TtrRenderer.render(r2) shouldBe text1
            return r2 to text1
        }

        listOf("71-automation.ttrm", "72-automation-world.ttrm", "73-states.ttrm", "74-automation-nouns-as-ids.ttrm")
            .forEach { name ->
                "round-trips conformance fixture $name byte-stable" {
                    roundTrip(Files.readString(locate("tests/conformance/fixtures").resolve(name)))
                }
            }

        "17 §1 automation.ttrm keeps every typed property and value form" {
            val (r, _) = roundTrip(Files.readString(automation("automation.ttrm")))
            val autos = r.definitions.filterIsInstance<AutomationDef>()
            autos.map { it.kind }.distinct() shouldContainExactly listOf("parameter", "action", "approval")
            val hold = autos.single { it.name == "hold" }
            hold.body.transition?.to shouldBe "held"
            hold.body.transition?.from shouldContainExactly listOf("placed")
            hold.body.set?.column shouldBe "department"
            autos
                .single { it.name == "set_state" }
                .body.transition
                ?.toColumn shouldBe "to"
            val due = autos.single { it.name == "ask" }.body["due"]
            due.shouldBeInstanceOf<AutoValue.Duration>()
            due.unit shouldBe "working_days"
            autos
                .single { it.name == "tolerance" }
                .body.label
                ?.byLanguage
                ?.get("cs") shouldBe "Cenová tolerance"
            val sales = r.definitions.filterIsInstance<RoleDef>().single { it.name == "sales" }
            (sales.entries.first().value as AutoValue.Tagged).value shouldBe "hartland-sales"
        }

        "17 §2 demo-world.ttrm keeps the automation members" {
            val (r, text) = roundTrip(Files.readString(automation("demo-world.ttrm")))
            val world = r.definitions.filterIsInstance<WorldDef>().single()
            world.identities.single().name shouldBe "automation_decider"
            world.grants.size shouldBe 3
            world.grants
                .first()
                .body["program"]
                .shouldBeInstanceOf<AutoValue.Star>()
            world.deployments.size shouldBe 12
            (world.deployments.single { it.name == "release_native_morning" }.body["trigger"] as AutoValue.Tagged)
                .value shouldBe "06:00 Europe/Prague"
            world.processes
                .single()
                .steps
                .map { it.name } shouldContainExactly listOf("decide", "inbound", "settle")
            text.contains("def grant {") shouldBe true
        }

        "an attribute's states and transitions round-trip" {
            val (r, _) =
                roundTrip(
                    "model er\ndef entity order { attributes: [ def attribute state { type: text, " +
                        "states: [placed, held], transitions: { placed: [held] } } ] }",
                )
            val attr =
                r.definitions
                    .filterIsInstance<EntityDef>()
                    .single()
                    .attributes
                    .single()
            attr.states shouldContainExactly listOf("placed", "held")
            attr.transitions.single().to shouldContainExactly listOf("held")
        }
    })

private fun automation(name: String): Path =
    locate("packages/kotlin/ttr-parser/src/test/resources/automation").resolve(name)

private fun locate(rel: String): Path {
    var dir: Path? = Paths.get("").toAbsolutePath()
    while (dir != null) {
        val candidate = dir.resolve(rel)
        if (Files.isDirectory(candidate)) return candidate
        dir = dir.parent
    }
    error("could not locate $rel")
}
