// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.writer

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.tatrman.ttr.parser.loader.TtrLoader
import org.tatrman.ttr.parser.model.ConceptDef
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

class CnTimeRoundTripSpec :
    StringSpec({
        "all CN-P8 shapes survive writer round-trip and canonical re-render" {
            var dir: Path? = Paths.get("").toAbsolutePath()
            while (dir != null && !Files.isDirectory(dir.resolve("tests/conformance/fixtures"))) dir = dir.parent
            val fixture = requireNotNull(dir).resolve("tests/conformance/fixtures/115-cn-things-in-time.ttrm")
            val first = TtrLoader.parseFile(fixture)
            first.errors shouldBe emptyList()
            val text = TtrRenderer.render(first)
            val second = TtrLoader.parseString(text)
            second.errors shouldBe emptyList()

            fun shape(defs: List<org.tatrman.ttr.parser.model.Definition>): List<String> =
                defs
                    .filterIsInstance<ConceptDef>()
                    .map { def ->
                        def.stereotype + ":" + def.name + ":" +
                            def.body.properties
                                .map { prop ->
                                    prop
                                        .toString()
                                        .replace(Regex("(source|textSource)=[^,()\\[\\]]*:-?\\d+:-?\\d+"), "$1=_")
                                        .replace(Regex("lineOffsets=\\[[^]]*]"), "lineOffsets=_")
                                        .replace(Regex("indentWidth=\\d+"), "indentWidth=_")
                                }.sorted()
                                .joinToString("|")
                    }.sorted()
            shape(second.definitions) shouldBe shape(first.definitions)
            TtrRenderer.render(second) shouldBe text
        }
    })
