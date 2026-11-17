// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.imports

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.tatrman.ttr.metadata.LoadOptions
import org.tatrman.ttr.metadata.MetadataLoader
import org.tatrman.ttr.metadata.source.BuiltinStockSource
import org.tatrman.ttr.metadata.source.FileBasedSource
import org.tatrman.ttr.metadata.source.LocalFsStorage
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * MW-P5 S5.2 T1 — `import-glossary`'s reader on the committed glossary fixture (`tests/conformance/readers/glossary`:
 * `sources/trade.{ttl,rdf,csv}`, twelve invented trade concepts, cs + en; `authored/` the core package; `model/` the
 * committed output of `trade.ttl` through `skos/1`).
 */
class GlossarySpec :
    FunSpec({
        val fixture = Path.of("../../../tests/conformance/readers/glossary").toAbsolutePath().normalize()

        fun files(directory: Path): Map<String, String> =
            Files.walk(directory).use { paths -> paths.filter { it.isRegularFile() }.toList() }.associate {
                directory.relativize(it).joinToString("/") to it.readText()
            }

        /** A fresh copy of `sources/` under a temporary root; the run writes `<root>/model`. */
        fun workspace(): Path =
            Files.createTempDirectory("s52-glossary").also { root ->
                files(fixture.resolve("sources")).forEach { (path, text) ->
                    root.resolve("sources/$path").also { it.parent.createDirectories() }.writeText(text)
                }
            }

        fun run(
            root: Path,
            frontEnd: FrontEnd,
            input: String,
            profile: String,
            overlay: Path? = null,
        ) = Reader.run(
            frontEnd,
            requireNotNull(ImportProfile.shipped(profile)),
            ReaderRequest(
                listOf(root.resolve("sources/$input")),
                root.resolve("model"),
                "0.0.1-LOCAL",
                overlay = overlay,
            ),
        )

        /** A run's model files and sidecar entries without the header, which names the run's own snapshot. */
        fun bodies(generated: GeneratedPackage): Map<String, String> =
            (generated.files + generated.sidecars).mapValues { (_, text) ->
                text
                    .lines()
                    .filterNot {
                        it.startsWith(
                            "// generated-from:",
                        ) ||
                            "\"generated-from\"" in it
                    }.joinToString("\n")
            }

        test(
            "SKOS Turtle → a cn skeleton and one lexicon per locale, byte-equal to the committed expectation, strict",
        ) {
            val root = workspace()
            val generated = run(root, RdfFrontEnd.TURTLE, "trade.ttl", "skos/1")
            generated.outputs() shouldBe files(fixture.resolve("model"))
            run(workspace(), RdfFrontEnd.TURTLE, "trade.ttl", "skos/1").outputs() shouldBe generated.outputs()

            val cn = generated.files.getValue("glossary/cn.ttrm")
            cn.lines().count { it.startsWith("def kind ") } shouldBe 12
            cn.lines().filter { it.startsWith("def ") }.all { "abstract: true" in it } shouldBe true
            cn shouldContain "def kind Supplier { description: { cs: \"Organizace, od které nakupujeme.\", " +
                "en: \"An organisation we buy from.\" }, label: { cs: \"dodavatel\", en: \"supplier\" }, " +
                "is_a: [\"Organisation\"], abstract: true, relations: [{ to: \"Product\", type: \"related\" }] }"
            generated.files.getValue("glossary/lexicon.cs.ttrm") shouldContain "model lexicon locale cs"
            generated.files.getValue("glossary/lexicon.en.ttrm") shouldContain
                "def term Customer_en { for: cn.Customer, forms: [\"buyer\", \"client\"] }"
            generated.report.packages.map { it.file to it.definitions } shouldContainExactly
                listOf("glossary/cn.ttrm" to 12, "glossary/lexicon.cs.ttrm" to 7, "glossary/lexicon.en.ttrm" to 8)

            // The skeleton loads strict beside the authored core package that declares `relation_type related`.
            val model = root.resolve("model")
            generated.files.forEach { (path, text) ->
                model.resolve(path).also { it.parent.createDirectories() }.writeText(text)
            }
            model.resolve("glossary/core.ttrm").writeText(fixture.resolve("authored/glossary/core.ttrm").readText())
            val loaded =
                MetadataLoader(
                    listOf(BuiltinStockSource(), FileBasedSource("glossary", 100, LocalFsStorage("glossary", model))),
                ).load(LoadOptions(strict = true))
            withClue(loaded.issues.joinToString("\n")) {
                loaded.report?.ok shouldBe true
                loaded.errors shouldBe emptyList()
            }
        }

        test("the same glossary as RDF/XML yields the same packages, byte for byte below the header") {
            val turtle = run(workspace(), RdfFrontEnd.TURTLE, "trade.ttl", "skos/1")
            val xml = run(workspace(), RdfFrontEnd.RDFXML, "trade.rdf", "skos/1")
            bodies(xml) shouldBe bodies(turtle)
            xml.report.ignored.map { it.path to it.reason } shouldBe turtle.report.ignored.map { it.path to it.reason }
        }

        test(
            "CSV (om-glossary/1) = the same concepts in English; a term not Approved is skipped into ignored[]",
        ) {
            val csv = run(workspace(), CsvFrontEnd, "trade.csv", "om-glossary/1")
            val turtle = run(workspace(), RdfFrontEnd.TURTLE, "trade.ttl", "skos/1")

            // S5.2 Found (j): OM glossaries are monolingual and carry no is_a/relations until S5.5's adapter (Found (i)), so
            // the CSV skeleton is the Turtle one without the cs entries, is_a and relations; the en lexicon is the same.
            fun english(cn: String) =
                cn
                    .lines()
                    .filter { it.startsWith("def ") }
                    .map {
                        it
                            .replace(Regex("""cs: "[^"]*", """), "")
                            .replace(Regex("""is_a: \[[^\]]*], """), "")
                            .replace(Regex(""", relations: \[.*] }$"""), " }")
                    }
            english(csv.files.getValue("glossary/cn.ttrm")) shouldBe english(turtle.files.getValue("glossary/cn.ttrm"))
            bodies(csv).getValue("glossary/lexicon.en.ttrm") shouldBe
                bodies(turtle).getValue("glossary/lexicon.en.ttrm")
            csv.files.values.forEach { it shouldNotContain "Quotation" }
            csv.files
                .getValue("glossary/cn.ttrm")
                .lines()
                .count { it.startsWith("def kind ") } shouldBe 12
            csv.report.ignored
                .filter { it.record != null }
                .map { Triple(it.record, it.line, it.reason) } shouldBe
                listOf(Triple("Quotation", 12, "kind mapping null (/glossaryStatus = Draft)"))
            csv.files.getValue("glossary/lexicon.en.ttrm") shouldContain
                "def term Customer_en { for: cn.Customer, forms: [\"buyer\", \"client\"] }"

            val skos = turtle
            skos.report.ignored
                .filter { it.record != null }
                .map { it.record to it.reason } shouldBe
                listOf("Trade" to "kind mapping null (/rdf:type/0/@local = ConceptScheme)")
            skos.report.markdown() shouldContain
                "| `/subjects/*/skos:altLabel` | lexicon entries (the reader's lexicon key) | 9 |"
        }

        test("IRIs land in the sidecar (sourceName = IRI, locator = the local name), never in the model text") {
            val generated = run(workspace(), RdfFrontEnd.TURTLE, "trade.ttl", "skos/1")
            generated.files.values.forEach { it.lines().drop(1).joinToString("\n") shouldNotContain "example.org" }
            generated.sidecars.getValue("glossary.sources.json") shouldContain
                "{\"qname\":\"glossary.cn.kind.Customer\",\"kind\":\"kind\"," +
                "\"sourceName\":\"https://example.org/trade/Customer\",\"locator\":\"Customer\"}"
        }

        test("an overlay keyed by a term's IRI applies after the profile and reports overridden[]") {
            val root = workspace()
            val generated = run(root, RdfFrontEnd.TURTLE, "trade.ttl", "skos/1", fixture.resolve("overlay.yaml"))
            generated.files.getValue("glossary/cn.ttrm") shouldContain
                "def kind Customer { description: { cs: \"Strana, která od nás aspoň jednou nakoupila.\", " +
                "en: \"A party that has bought from us at least once.\" }"
            generated.report.overridden.map { Triple(it.qname, it.property, it.from) } shouldBe
                listOf(
                    Triple(
                        "glossary.cn.kind.Customer",
                        "description",
                        buildJsonObject {
                            put("cs", "Strana, která od nás nakupuje.")
                            put("en", "A party that buys from us.")
                        },
                    ),
                )
            generated.report.overridden
                .single()
                .to shouldBe
                buildJsonObject {
                    put("en", JsonPrimitive("A party that has bought from us at least once."))
                    put("cs", JsonPrimitive("Strana, která od nás aspoň jednou nakoupila."))
                }
        }
    })
