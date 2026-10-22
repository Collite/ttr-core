// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.writer

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.tatrman.ttr.parser.loader.TtrLoader
import org.tatrman.ttr.parser.model.WorldDef
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * v4.1 — world-model round-trip (ttr-metadata M0). Byte-stability is expressed as
 * render-idempotence (`render∘parse` is a fixed point) plus re-parse success and a
 * structural check that the world roster survives the trip.
 */
class WorldRoundTripSpec :
    StringSpec({

        listOf(
            "def world w { tags: [\"world\"] }",
            "def world w { def engine e { tags: [\"engine\"], type: postgres } }",
            "def world w { def storage s { tags: [\"storage\"], type: filesystem } }",
        ).forEach { source ->
            "R4: pre-existing world/member tags reparse without duplicate commas: $source" {
                val parsed = TtrLoader.parseString(source)
                parsed.errors shouldBe emptyList()
                val once = TtrRenderer.render(parsed)
                TtrLoader.parseString(once).errors shouldBe emptyList()
                TtrRenderer.render(TtrLoader.parseString(once)) shouldBe once
            }
        }

        listOf("57-world.ttrm", "58-world-extends.ttrm").forEach { name ->
            "round-trips $name byte-stable and structurally" {
                val src = Files.readString(fixture(name))
                val r1 = TtrLoader.parseString(src)
                r1.ok shouldBe true

                val text1 = TtrRenderer.render(r1)
                val r2 = TtrLoader.parseString(text1)
                r2.ok shouldBe true
                val text2 = TtrRenderer.render(r2)

                // (b) render-twice byte-equality.
                text2 shouldBe text1

                // (a) structural equality across the trip (roster preserved).
                val w1 = r1.definitions.filterIsInstance<WorldDef>().single()
                val w2 = r2.definitions.filterIsInstance<WorldDef>().single()
                w2.name shouldBe w1.name
                w2.engines.map { it.name } shouldBe w1.engines.map { it.name }
                w2.executors.map { it.name } shouldBe w1.executors.map { it.name }
                w2.storages.map { it.name } shouldBe w1.storages.map { it.name }
                w2.storages.flatMap { it.schemas.flatMap { s -> s.fields.map { f -> f.name } } } shouldBe
                    w1.storages.flatMap { it.schemas.flatMap { s -> s.fields.map { f -> f.name } } }
            }
        }
    })

private fun fixture(name: String): Path {
    var dir: Path? = Paths.get("").toAbsolutePath()
    while (dir != null) {
        val candidate = dir.resolve("tests/conformance/fixtures/$name")
        if (Files.isRegularFile(candidate)) return candidate
        dir = dir.parent
    }
    error("could not locate tests/conformance/fixtures/$name")
}
