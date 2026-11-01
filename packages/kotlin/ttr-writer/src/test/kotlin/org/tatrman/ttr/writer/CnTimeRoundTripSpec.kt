// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.writer

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
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

        "variant: each author's word survives the round trip; a def that records none is written variant (S8.6)" {
            var dir: Path? = Paths.get("").toAbsolutePath()
            while (dir != null && !Files.isDirectory(dir.resolve("tests/conformance/fixtures"))) dir = dir.parent
            val fixture = requireNotNull(dir).resolve("tests/conformance/fixtures/122-cn-variant.ttrm")
            val parsed = TtrLoader.parseFile(fixture)
            parsed.errors shouldBe emptyList()
            val text = TtrRenderer.render(parsed)
            val heads = HEAD.findAll(text).map { "${it.groupValues[2]}:${it.groupValues[1]}" }
            // The writer orders defs by name within a class; the word is what matters here.
            heads.toList().sorted() shouldBe
                listOf(
                    "Bundle:variant",
                    "CodeRange:variant",
                    "Invoice:variant",
                    "MissingBearer:variant",
                    "StoredSale:subkind",
                    "WebSale:variant",
                )
            TtrRenderer.render(TtrLoader.parseString(text)) shouldBe text

            val synthesized = ConceptDef("subkind", "WebSale", parsed.definitions.first().source)
            val written = TtrRenderer.render(TtrLoader.parseString("model cn").copy(definitions = listOf(synthesized)))
            written shouldContain "def variant WebSale"
        }
    })

private val HEAD = Regex("(?m)^def (variant|subkind) (\\w+)")
