// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.imports

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.tatrman.ttr.metadata.LoadOptions
import org.tatrman.ttr.metadata.MetadataLoader
import org.tatrman.ttr.metadata.export.GeneratedHeader
import org.tatrman.ttr.metadata.export.Hashes
import org.tatrman.ttr.metadata.model.DbSchema
import org.tatrman.ttr.metadata.source.BuiltinStockSource
import org.tatrman.ttr.metadata.source.FileBasedSource
import org.tatrman.ttr.metadata.source.LocalFsStorage
import org.tatrman.ttr.profile.ProfileException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * MW-P5 S5.1 T1 — the reader harness on the committed OpenMetadata fixture (`tests/conformance/readers/om-tables`:
 * `sources/shop-tables.json`, invented names, and its committed output under `model/`). Each run works on a copy of the
 * fixture in a temporary root, so the header's relative path (`../../sources/shop-tables.json`) is the committed one.
 */
class ReaderSpec :
    FunSpec({
        val fixture = Path.of("../../../tests/conformance/readers/om-tables").toAbsolutePath().normalize()

        /** A fresh copy of `sources/` under a temporary root; the run writes `<root>/model`. */
        fun workspace(): Path {
            val root = Files.createTempDirectory("s51-reader")
            val from = fixture.resolve("sources")
            Files.walk(from).use { paths -> paths.filter { it.isRegularFile() }.toList() }.forEach {
                root
                    .resolve("sources")
                    .resolve(from.relativize(it).toString())
                    .also { file -> file.parent.createDirectories() }
                    .writeText(it.readText())
            }
            return root
        }

        fun run(
            root: Path,
            overlay: Path? = null,
            packageName: String? = null,
        ) = Reader.run(
            JsonFrontEnd,
            requireNotNull(ImportProfile.shipped("om-tables/1")),
            ReaderRequest(
                listOf(root.resolve("sources/shop-tables.json")),
                root.resolve("model"),
                "0.0.1-LOCAL",
                overlay = overlay,
                packageName = packageName,
            ),
        )

        test("deterministic: two runs are byte-equal, and equal to the committed expectation") {
            val root = workspace()
            val first = run(root).outputs()
            val second = run(workspace()).outputs()
            second shouldBe first
            val committed =
                Files.walk(fixture.resolve("model")).use { paths ->
                    paths.filter { it.isRegularFile() }.toList()
                }
            first.keys.toList() shouldContainExactly
                committed.map { fixture.resolve("model").relativize(it).joinToString("/") }.sorted()
            first.forEach { (path, text) ->
                withClue(path) { text shouldBe fixture.resolve("model").resolve(path).readText() }
            }
        }

        test("the .ttrm carries the P3 header and loads strict; the sidecar's sourceName is the OM FQN") {
            val root = workspace()
            val generated = run(root)
            val ttrm = generated.files.getValue("om/db.ttrm")
            val header = requireNotNull(GeneratedHeader.read(ttrm))
            header.profile shouldBe "om-tables/1"
            header.overlay shouldBe null
            header.snapshot.path shouldBe "../../sources/shop-tables.json"
            header.snapshot.sha256 shouldBe Hashes.of(root.resolve("sources/shop-tables.json"))

            generated.outputs().forEach { (path, text) ->
                root
                    .resolve("model")
                    .resolve(path)
                    .also { it.parent.createDirectories() }
                    .writeText(text)
            }
            val loaded =
                MetadataLoader(
                    listOf(
                        BuiltinStockSource(),
                        FileBasedSource("om", 100, LocalFsStorage("om", root.resolve("model"))),
                    ),
                ).load(LoadOptions(strict = true))
            withClue(loaded.issues.joinToString("\n")) {
                loaded.report?.ok shouldBe true
                loaded.errors shouldBe emptyList()
            }
            val tables =
                requireNotNull(loaded.model)
                    .schemas.values
                    .filterIsInstance<DbSchema>()
                    .flatMap { it.tables.values }
            tables.map { it.qname.name }.sorted() shouldBe listOf("customers", "order_lines", "orders")
            tables.sumOf { it.columns.size } shouldBe 13

            val entries =
                Json
                    .parseToJsonElement(generated.sidecars.getValue("om.sources.json"))
                    .jsonObject
                    .getValue("entries")
                    .jsonArray
                    .associate {
                        it.jsonObject
                            .getValue("qname")
                            .jsonPrimitive.content to
                            it.jsonObject
                                .getValue("sourceName")
                                .jsonPrimitive.content
                    }
            entries.size shouldBe 16
            entries["om.db.dbo.orders"] shouldBe "demo_pg.shop.public.orders"
            entries["om.db.dbo.orders.total"] shouldBe "demo_pg.shop.public.orders.total"
            // The sidecar is a generated file too: its generated-from is the header text, relative to its own directory.
            generated.sidecars.getValue("om.sources.json") shouldContain
                "\"generated-from: ../sources/shop-tables.json@${header.snapshot.sha256} profile om-tables/1"
        }

        test("skeleton: true marks every generated cn definition abstract, never a db/er one") {
            val root = Files.createTempDirectory("s51-skeleton")
            root.resolve("terms.json").writeText(
                """
                {"terms": [{"name": "Customer", "text": "Someone who buys."},
                           {"name": "Supplier", "text": "Someone who sells.", "abstract": false}],
                 "tables": [{"name": "customers", "text": "The customer table."}]}
                """.trimIndent(),
            )
            val profile = root.resolve("skeleton.yaml")
            profile.writeText(
                """
                profile: skeleton-test/1
                grammar: '>=0.22 <1.0'
                skeleton: true
                operations:
                  - {op: package, mapping: {}, default: demo}
                  - {op: kinds, at: /terms, id: /name, mapping: {term: kind}}
                  - {op: kinds, at: /tables, id: /name, mapping: {table: table}}
                  - {op: rename, in: kind, at: /text, to: {kind: kind, property: description}}
                  - {op: rename, in: kind, at: /abstract, to: {kind: kind, property: abstract}}
                  - {op: rename, in: table, at: /text, to: {kind: table, property: description}}
                """.trimIndent() + "\n",
            )
            val resolved = ImportProfile.resolve(profile.toString())
            resolved.options shouldBe ReaderOptions(skeleton = true)
            val generated =
                Reader.run(
                    JsonFrontEnd,
                    resolved,
                    ReaderRequest(listOf(root.resolve("terms.json")), root.resolve("model"), "0.0.1-LOCAL"),
                )
            generated.files.keys shouldBe setOf("demo/cn.ttrm", "demo/db.ttrm")
            val cn = generated.files.getValue("demo/cn.ttrm")
            withClue(cn) {
                Regex("def kind Customer \\{[^}]*abstract: true").containsMatchIn(cn) shouldBe true
                // A definition that states `abstract` keeps its own value.
                Regex("def kind Supplier \\{[^}]*abstract: false").containsMatchIn(cn) shouldBe true
            }
            generated.files.getValue("demo/db.ttrm") shouldNotContain "abstract"

            // Without the flag nothing is abstract.
            profile.writeText(profile.readText().replace("skeleton: true\n", ""))
            val plain =
                Reader.run(
                    JsonFrontEnd,
                    ImportProfile.resolve(profile.toString()),
                    ReaderRequest(listOf(root.resolve("terms.json")), root.resolve("model"), "0.0.1-LOCAL"),
                )
            plain.files.getValue("demo/cn.ttrm") shouldNotContain "abstract: true"
        }

        test("the report lists ignored[] with reasons and overridden[], rendered as the PR-body section") {
            val root = workspace()
            val overlay = root.resolve("sources/overlay.yaml")
            overlay.writeText("shop.db.dbo.orders:\n  description: The order header (curated).\n")
            val generated = run(root, overlay = overlay, packageName = "shop")
            generated.files.keys shouldBe setOf("shop/db.ttrm")
            generated.sidecars.keys shouldBe setOf("shop.sources.json")
            generated.files.getValue("shop/db.ttrm") shouldContain
                "def table orders { description: \"The order header (curated).\""
            // The authored overlay is a header input too.
            requireNotNull(GeneratedHeader.read(generated.files.getValue("shop/db.ttrm"))).overlay?.path shouldBe
                "../../sources/overlay.yaml"

            val report = generated.report
            report.overridden.map { it.qname to it.property } shouldBe listOf("shop.db.dbo.orders" to "description")
            report.ignored.size shouldBe 104
            report.ignored.all { it.reason != null } shouldBe true
            report.ignored.first { it.path == "/data/1/tableConstraints" }.let {
                it.file shouldBe "shop-tables.json"
                it.reason shouldContain "def fk"
            }
            val json = Json.parseToJsonElement(report.json()).jsonObject
            json.keys shouldBe setOf("frontEnd", "profile", "packages", "ignored", "overridden", "warnings")

            val markdown = report.markdown()
            markdown shouldContain "### Import report — `om-tables/1` (json front-end)"
            markdown shouldContain "| `shop/db.ttrm` | db | 3 |"
            markdown shouldContain "#### Ignored (104)"
            markdown shouldContain "| `/data/*/usageSummary` | usage statistics | 3 | `shop-tables.json:113:7` |"
            markdown shouldContain "#### Overridden (1)"
            markdown shouldContain
                "| `shop.db.dbo.orders` | description | `\"One checkout: the order header.\"` | " +
                "`\"The order header (curated).\"` |"
        }

        val shipped = Path.of("../../../profiles/om-tables/1.yaml").toAbsolutePath().normalize()

        fun runWith(
            profile: ImportProfile,
            root: Path,
            vararg inputs: Path = arrayOf(root.resolve("sources/shop-tables.json")),
            overlay: Path? = null,
            packageName: String? = null,
        ) = Reader.run(
            JsonFrontEnd,
            profile,
            ReaderRequest(
                inputs.toList(),
                root.resolve("model"),
                "0.0.1-LOCAL",
                overlay = overlay,
                packageName = packageName,
            ),
        )

        test("R1: --package replaces every file-scoped package operation, in the profile or its parent") {
            val root = workspace()
            val scoped =
                shipped.readText().replace(
                    "  - {op: package, mapping: {}, default: om}\n",
                    "  - {op: package, files: ['a*.json'], mapping: {}, default: pa}\n" +
                        "  - {op: package, files: ['shop*.json'], mapping: {}, default: pb}\n",
                )
            val parent =
                root.resolve("parent.yaml").also {
                    it.writeText(scoped.replace("profile: om-tables/1", "profile: scoped/1"))
                }
            ImportProfile.resolve("$parent").let { profile ->
                runWith(profile, root).files.keys shouldBe setOf("pb/db.ttrm")
                runWith(profile, root, packageName = "shop").let {
                    it.files.keys shouldBe setOf("shop/db.ttrm")
                    it.sidecars.keys shouldBe setOf("shop.sources.json")
                }
            }
            val child = root.resolve("child.yaml")
            child.writeText("profile: child/1\ngrammar: '>=0.22 <1.0'\nextends: ./parent.yaml\noperations: []\n")
            runWith(ImportProfile.resolve("$child"), root, packageName = "shop").files.keys shouldBe
                setOf("shop/db.ttrm")
        }

        test("R2 (✅R330-A): an overlay keyed by an OM FQN applies on the first import, with no model root") {
            val root = workspace()
            val overlay = root.resolve("fqn.yaml")
            overlay.writeText("demo_pg.shop.public.customers:\n  description: Curated customers.\n")
            val generated = runWith(requireNotNull(ImportProfile.shipped("om-tables/1")), root, overlay = overlay)
            generated.files.getValue("om/db.ttrm") shouldContain
                "def table customers { description: \"Curated customers.\""
            generated.report.overridden.map { it.qname to it.property } shouldBe
                listOf("om.db.dbo.customers" to "description")
        }

        test("R5 (✅R330-B): a user profile extends the shipped om-tables/1 and inherits its sourceName") {
            val root = workspace()
            val source = root.resolve("sources/shop-tables.json")
            source.writeText(
                source.readText().replace(
                    "\"tableType\": \"Regular\",",
                    "\"tableType\": \"Regular\", \"sourceHash\": \"x\",",
                ),
            )
            val child = root.resolve("mine.yaml")
            child.writeText(
                "profile: mine/1\ngrammar: '>=0.22 <1.0'\nextends: om-tables/1\noperations:\n" +
                    "  - {op: ignore, in: table, at: /sourceHash, reason: OM bookkeeping}\n",
            )
            val profile = ImportProfile.resolve("$child")
            profile.identity shouldBe "mine/1"
            profile.options shouldBe ReaderOptions(sourceName = "/fullyQualifiedName")
            val generated = runWith(profile, root)
            requireNotNull(GeneratedHeader.read(generated.files.getValue("om/db.ttrm"))).profile shouldBe "mine/1"
            generated.sidecars.getValue("om.sources.json") shouldContain "\"sourceName\":\"demo_pg.shop.public.orders\""
        }

        test("R9, R10: one input only; an input over the reader budget is refused before it is decoded") {
            val root = workspace()
            val other = root.resolve("sources/other.json").also { it.writeText("{\"data\": []}") }
            val profile = requireNotNull(ImportProfile.shipped("om-tables/1"))
            shouldThrow<ReaderInputException> {
                runWith(profile, root, root.resolve("sources/shop-tables.json"), other)
            }.message shouldContain "takes one input file; 2 given"

            val big = root.resolve("sources/big.json")
            Files.newOutputStream(big).use { out ->
                out.write("{\"data\": [], \"pad\": \"".toByteArray())
                val chunk = ByteArray(1 shl 20) { 'x'.code.toByte() }
                repeat(25) { out.write(chunk) }
                out.write("\"}".toByteArray())
            }
            shouldThrow<ProfileException> { runWith(profile, root, big) }.let {
                it.location.file shouldBe "big.json"
                it.detail shouldContain "Input exceeds"
            }
            val bad =
                root.resolve("sources/bad.json").also {
                    Files.write(it, "{\"data\": [],\n \"x\": \"\u00ff\"}".toByteArray(Charsets.ISO_8859_1))
                }
            shouldThrow<ProfileException> { runWith(profile, root, bad) }.let {
                it.location.file shouldBe "bad.json"
                it.location.line shouldBe 2
                it.detail shouldContain "Malformed UTF-8"
            }
        }
    })
