// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.metadata.export

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlin.random.Random

class DeterminismSpec :
    StringSpec({
        "independent loads and shuffled files produce equal graphs" {
            val first = loadXmWorld()
            val second = loadXmWorld()

            fun build(files: List<org.tatrman.ttr.metadata.source.LoadedFile>) =
                DefinitionGraph.build(
                    DefinitionSet.from(files, first.model, "0.18", "fixture-1"),
                    "northline",
                )
            build(first.files) shouldBe build(second.files)
            build(first.files.shuffled(Random(71))) shouldBe build(first.files)
        }
    })
