// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.metadata.export

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.nio.file.Path

class DeltaSpec :
    StringSpec({
        "fixture rename is separate from removal and filtered by version" {
            val renames =
                Rename.read(
                    Files.readString(Path.of("src/testFixtures/resources/fixtures/xm-world/model/renames.yaml")),
                )
            val current = DefinitionGraph(listOf(Node("md.measure.revenue", kind = "measure")), emptyList())
            val previous = setOf("md.measure.turnover", "er.entity.retired")
            ExportDelta.compute(previous, current, renames, "fixture-0") shouldBe
                ExportDelta(
                    "fixture-0",
                    listOf(Rename("md.measure.turnover", "md.measure.revenue", "fixture-1")),
                    listOf("er.entity.retired"),
                )
            ExportDelta.compute(previous, current, renames, "fixture-2").removed shouldBe previous.sortedWith(bytewise)
        }
        "renames are deterministic and invalid yaml is rejected" {
            val graph = DefinitionGraph(emptyList(), emptyList())
            ExportDelta.compute(setOf("z", "a"), graph, emptyList(), "v1").removed shouldBe listOf("a", "z")
            io.kotest.assertions.throwables
                .shouldThrow<IllegalArgumentException> { Rename.read("- {from: old}") }
        }
    })
