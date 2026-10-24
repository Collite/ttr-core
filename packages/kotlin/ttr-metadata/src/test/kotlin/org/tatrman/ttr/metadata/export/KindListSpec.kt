// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.metadata.export

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.nio.file.Path

class KindListSpec :
    StringSpec({
        "every generated grammar kind has an explicit export decision" {
            val map = Files.readString(Path.of("../../grammar/src/generated/property-map.ts"))
            val kinds = Regex("^  (\\w+): \\[", RegexOption.MULTILINE).findAll(map).map { it.groupValues[1] }.toSet()
            (kinds - ExportKinds.astKinds.keys) shouldBe emptySet()
            (ExportKinds.astKinds.keys - kinds) shouldBe emptySet()
            (
                ExportKinds.astKinds.values
                    .flatten()
                    .toSet() - ExportKinds.excluded - ExportKinds.bindings -
                    ExportKinds.lexicon
            ) shouldBe
                ExportKinds.nodes
            // The property-map generator itself must include MD, not just its old advertised subset.
            kinds.containsAll(setOf("mdDomain", "dimension", "mdMap", "measure", "area", "world")) shouldBe true
        }
    })
