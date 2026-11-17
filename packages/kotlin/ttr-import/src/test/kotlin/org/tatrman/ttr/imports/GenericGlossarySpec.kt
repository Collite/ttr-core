// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.imports

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.tatrman.ttr.metadata.LoadOptions
import org.tatrman.ttr.metadata.MetadataLoader
import org.tatrman.ttr.metadata.source.BuiltinStockSource
import org.tatrman.ttr.metadata.source.FileBasedSource
import org.tatrman.ttr.metadata.source.LocalFsStorage
import org.tatrman.ttr.profile.ForeignTree
import org.tatrman.ttr.profile.ProfileEngine
import org.tatrman.ttr.profile.ProfileReader
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

/**
 * MW-P5 S5.2 review-350 F1/F4/F5/F6 (✅R350-A, ✅D-MW-87): the shipped generic profiles on ordinary input the committed
 * fixture does not carry. A generic reader skips what it does not understand and says so; it errors only on what the
 * profile's author got wrong.
 */
class GenericGlossarySpec :
    FunSpec({
        fun run(
            frontEnd: FrontEnd,
            name: String,
            text: String,
            profile: String,
        ): Pair<Path, GeneratedPackage> {
            val root = Files.createTempDirectory("s52-generic")
            val input = root.resolve("sources/$name").also { it.parent.createDirectories() }.apply { writeText(text) }
            val generated =
                Reader.run(
                    frontEnd,
                    requireNotNull(ImportProfile.shipped(profile)),
                    ReaderRequest(listOf(input), root.resolve("model"), "0.0.1-LOCAL"),
                )
            return root to generated
        }

        fun loadsStrict(
            root: Path,
            generated: GeneratedPackage,
        ) {
            val model = root.resolve("model")
            generated.files.forEach { (path, text) ->
                model.resolve(path).also { it.parent.createDirectories() }.writeText(text)
            }
            val loaded =
                MetadataLoader(
                    listOf(BuiltinStockSource(), FileBasedSource("glossary", 100, LocalFsStorage("glossary", model))),
                ).load(LoadOptions(strict = true))
            withClue(loaded.issues.joinToString("\n")) {
                loaded.report?.ok shouldBe true
                loaded.errors shouldBe emptyList()
            }
        }

        val prefixes =
            "@prefix s: <http://www.w3.org/2004/02/skos/core#> .\n" +
                "@prefix owl: <http://www.w3.org/2002/07/owl#> .\n" +
                "@prefix ex: <https://example.org/ext/> .\n" +
                "@prefix g: <https://example.org/g/> .\n"

        test("skos/1: collections, untyped and unmapped subjects are skipped into ignored[]; a mapped type wins") {
            val (root, generated) =
                run(
                    RdfFrontEnd.TURTLE,
                    "g.ttl",
                    prefixes +
                        "g:Coll a s:Collection .\n" +
                        "g:Ordered a s:OrderedCollection .\n" +
                        "g:Untyped s:prefLabel \"untyped\"@en .\n" +
                        "g:Party a s:Concept ; s:prefLabel \"party\"@en ; s:note [ s:prefLabel \"aside\" ] .\n" +
                        "g:Agent a s:Concept, owl:Class ; s:prefLabel \"agent\"@en .\n" +
                        "g:Term a ex:TradeTerm, s:Concept ; s:prefLabel \"term\"@en .\n" +
                        "g:Onto a owl:Ontology .\n" +
                        "g:Thing a ex:Thing, ex:Other .\n",
                    "skos/1",
                )
            val cn = generated.files.getValue("glossary/cn.ttrm")
            cn.lines().filter { it.startsWith("def ") }.map { it.split(' ')[2] } shouldBe
                listOf("Agent", "Party", "Term")
            generated.report.ignored
                .filter { it.reason?.startsWith("kind ") == true }
                .map { it.record to it.reason } shouldBe
                listOf(
                    // The blank note under Party: no type, no local name.
                    null to "kind missing (/rdf:type/*/@local)",
                    "Coll" to "kind mapping null (/rdf:type/*/@local = Collection)",
                    "Onto" to "kind unmapped (Ontology)",
                    "Ordered" to "kind mapping null (/rdf:type/*/@local = OrderedCollection)",
                    "Thing" to "kind unmapped (Other, Thing)",
                    "Untyped" to "kind missing (/rdf:type/*/@local)",
                )
            loadsStrict(root, generated)
        }

        test("skos/1: an untagged label is English with a warning; a repeated language keeps the bytewise-first") {
            val (root, generated) =
                run(
                    RdfFrontEnd.TURTLE,
                    "g.ttl",
                    prefixes +
                        "g:Party a s:Concept ; s:prefLabel \"party\" ; s:definition \"two\"@en, \"one\"@en, \"jedna\"@cs .\n",
                    "skos/1",
                )
            generated.files.getValue("glossary/cn.ttrm") shouldContain
                "def kind Party { description: { cs: \"jedna\", en: \"one\" }, label: { en: \"party\" }, abstract: true }"
            generated.report.warnings.map { it.substringBefore(" — ") } shouldBe
                listOf("Party: each key missing → en", "Party: each key repeated (en)")
            loadsStrict(root, generated)
        }

        test("skos/1: en-GB labels and synonyms load as the locale en_gb; an empty synonym is no form (F4, F5)") {
            val (root, generated) =
                run(
                    RdfFrontEnd.TURTLE,
                    "g.ttl",
                    prefixes +
                        "g:Lorry a s:Concept ; s:prefLabel \"lorry\"@en-GB, \"truck\"@en ; " +
                        "s:altLabel \"wagon\"@en-GB, \"\"@en, \"  \"@en, \"rig\"@en .\n",
                    "skos/1",
                )
            generated.files.getValue("glossary/cn.ttrm") shouldContain "label: { en: \"truck\", en_gb: \"lorry\" }"
            generated.files.getValue("glossary/lexicon.en_gb.ttrm").let {
                it shouldContain "model lexicon locale en_gb"
                it shouldContain "def term Lorry_en_gb { for: cn.Lorry, forms: [\"wagon\"] }"
            }
            generated.files.getValue("glossary/lexicon.en.ttrm") shouldContain
                "def term Lorry_en { for: cn.Lorry, forms: [\"rig\"] }"
            loadsStrict(root, generated)
        }

        test("om-glossary/1: an empty or unlisted glossaryStatus is skipped into ignored[]; Approved terms stay") {
            val (root, generated) =
                run(
                    CsvFrontEnd,
                    "terms.csv",
                    "name*,displayName,description,synonyms,glossaryStatus\n" +
                        "Customer,customer,A party that buys.,buyer;client,Approved\n" +
                        "Blank,blank,No state.,,\n" +
                        "Odd,odd,An unlisted state.,,Unprocessed\n",
                    "om-glossary/1",
                )
            generated.files
                .getValue("glossary/cn.ttrm")
                .lines()
                .filter { it.startsWith("def ") }
                .map { it.split(' ')[2] } shouldBe listOf("Customer")
            generated.report.ignored
                .filter { it.record != null }
                .map { Triple(it.record, it.line, it.reason) } shouldBe
                listOf(
                    Triple("Blank", 3, "kind missing (/glossaryStatus)"),
                    Triple("Odd", 4, "kind unmapped (Unprocessed)"),
                )
            loadsStrict(root, generated)
        }

        test("F6: a record's synonyms go to the definition it made, not to a namesake in another package") {
            val profile =
                ProfileReader.read(
                    "profile: two/1\ngrammar: '>=0.22 <1.0'\noperations:\n" +
                        "  - {op: package, mapping: {alfa: alfa, beta: beta}}\n" +
                        "  - {op: kinds, at: /rows, id: /name, mapping: {term: kind}}\n" +
                        "  - {op: ignore, in: kind, at: /synonyms}\n",
                    "two.yaml",
                )
            val trees =
                listOf(
                    "alfa.csv" to "name,synonyms\nCustomer,buyer\n",
                    "beta.csv" to "name,synonyms\nCustomer,client\n",
                ).map { (file, text) -> ForeignTree.read(CsvFrontEnd.tree(text, file), file) }
            val result = ProfileEngine.apply(trees, listOf(profile))
            val documents =
                Lexicon.documents(
                    Lexicon.terms(
                        trees,
                        result.origins,
                        listOf(LexiconRule("kind", "/rows", "/name", "/synonyms", split = ";", locale = "en")),
                        mutableListOf(),
                    ),
                    "0.22",
                )
            documents.keys.toList() shouldBe listOf("alfa/lexicon.en.ttrm", "beta/lexicon.en.ttrm")
            val forms = documents.mapValues { (_, document) -> document.value.toString() }
            forms.getValue("alfa/lexicon.en.ttrm").let {
                it shouldContain "\"buyer\""
                it shouldNotContain "\"client\""
            }
            forms.getValue("beta/lexicon.en.ttrm").let {
                it shouldContain "\"client\""
                it shouldNotContain "\"buyer\""
            }
            result.origins.map { it.pkg to it.file } shouldContain ("alfa" to "alfa.csv")
        }
    })
