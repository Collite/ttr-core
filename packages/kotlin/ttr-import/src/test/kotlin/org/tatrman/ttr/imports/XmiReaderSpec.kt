// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.imports

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.tatrman.ttr.metadata.LoadOptions
import org.tatrman.ttr.metadata.MetadataLoader
import org.tatrman.ttr.metadata.export.DefinitionGraph
import org.tatrman.ttr.metadata.export.DefinitionSet
import org.tatrman.ttr.metadata.export.xmi.Uuid5
import org.tatrman.ttr.metadata.export.xmi.XmiWriter
import org.tatrman.ttr.metadata.source.BuiltinStockSource
import org.tatrman.ttr.metadata.source.FileBasedSource
import org.tatrman.ttr.metadata.source.LocalFsStorage
import org.tatrman.ttr.parser.conformance.ConformanceDump
import org.tatrman.ttr.profile.ProfileException
import org.tatrman.ttr.semantics.conformance.SemanticsConformanceDump
import org.tatrman.ttr.yaml.CanonicalYaml
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * MW-P5 S5.3 T1 — `import-xmi` on the committed fixtures (`tests/conformance/readers/xmi`, see its README): the invented
 * world `shop` through the XM-P1 writer and back through `ttr-mdg/1`, a plain EA ERD through `ea-erd/1`, and a second
 * snapshot with one class renamed in EA.
 */
class XmiReaderSpec :
    FunSpec({
        val fixture = Path.of("../../../tests/conformance/readers/xmi").toAbsolutePath().normalize()
        val golden = System.getenv("TTR_IMPORT_GOLDEN") == "1"

        fun files(directory: Path): Map<String, String> =
            Files.walk(directory).use { paths -> paths.filter { it.isRegularFile() }.toList() }.associate {
                directory.relativize(it).joinToString("/") to it.readText()
            }

        /** A fresh root with `sources/` (and, when asked, `renames.yaml`); runs write `<root>/<out>`. */
        fun workspace(renames: Boolean = false): Path =
            Files.createTempDirectory("s53-xmi").also { root ->
                Files.list(fixture.resolve("sources")).use { it.toList() }.forEach { file ->
                    Files.copy(file, root.resolve("sources").createDirectories().resolve(file.fileName))
                }
                if (renames) Files.copy(fixture.resolve("renames.yaml"), root.resolve("renames.yaml"))
            }

        fun run(
            root: Path,
            input: String,
            profile: String,
            pkg: String,
            out: String = "model",
            overlay: Path? = null,
        ) = Reader.run(
            XmiFrontEnd,
            ImportProfile.resolve(profile),
            ReaderRequest(listOf(root.resolve("sources/$input")), root.resolve(out), "0.0.1-LOCAL", root, overlay, pkg),
        )

        /** [generated] equals the committed `<fixture>/<dir>` (rewritten instead with TTR_IMPORT_GOLDEN=1). */
        fun matches(
            generated: GeneratedPackage,
            dir: String,
        ) {
            if (golden) {
                generated.outputs().forEach { (path, text) ->
                    fixture.resolve("$dir/$path").also { it.parent.createDirectories() }.writeText(text)
                }
            }
            generated.outputs() shouldBe files(fixture.resolve(dir))
        }

        fun write(
            root: Path,
            out: String,
            generated: GeneratedPackage,
        ) = generated.outputs().forEach { (path, text) ->
            root.resolve("$out/$path").also { it.parent.createDirectories() }.writeText(text)
        }

        /** A model directory loaded strict (the stock vocabulary beside it), with no error. */
        fun loadStrict(model: Path) =
            MetadataLoader(
                listOf(BuiltinStockSource(), FileBasedSource("m", 100, LocalFsStorage("m", model))),
            ).load(LoadOptions(strict = true)).also { loaded ->
                withClue(loaded.issues.joinToString("\n")) {
                    loaded.report?.ok shouldBe true
                    loaded.errors shouldBe emptyList()
                }
            }

        fun exportXmi(model: Path): ByteArray {
            val loaded = loadStrict(model)
            val set = DefinitionSet.from(loaded.files, loaded.model, "0.22", "s53")
            return XmiWriter.write(DefinitionGraph.build(set), set)
        }

        fun body(text: String) = text.lines().filterNot { it.startsWith("// generated-from:") }.joinToString("\n")

        fun report(generated: GeneratedPackage) = Json.parseToJsonElement(generated.report.json()).jsonObject

        /** The report's ignored[] as (path with record keys folded to `*`, reason) pairs. */
        fun reasons(generated: GeneratedPackage): Set<Pair<String, String>> =
            report(generated)
                .getValue("ignored")
                .jsonArray
                .map { it.jsonObject }
                .map {
                    it
                        .getValue("path")
                        .jsonPrimitive.content
                        .replace(Regex("^/elements/[^/]+"), "/elements/*")
                        .replace(Regex("/ownedAttribute/[0-9]+"), "/ownedAttribute/*") to
                        it.getValue("reason").jsonPrimitive.content
                }.toSet()

        test(
            "TTR → export xmi → import-xmi ttr-mdg/1 → the source world, dump-equal; the projection is a fixed point",
        ) {
            val world = fixture.resolve("world")
            val exported = exportXmi(world)
            if (golden) {
                Files.write(fixture.resolve("sources/shop.xmi"), exported)
                // Snapshot 2: the architect renames the class Product to Article in EA, which keeps its GUID.
                fixture.resolve("sources/shop-renamed.xmi").writeText(
                    String(exported, Charsets.UTF_8).replace("name=\"Product\"", "name=\"Article\""),
                )
            }
            String(exported, Charsets.UTF_8) shouldBe fixture.resolve("sources/shop.xmi").readText()

            val root = workspace()
            val generated = run(root, "shop.xmi", "ttr-mdg/1", "shop")
            matches(generated, "model")
            run(workspace(), "shop.xmi", "ttr-mdg/1", "shop").outputs() shouldBe generated.outputs()
            generated.files.keys shouldContainExactly listOf("shop/cn.ttrm", "shop/er.ttrm", "shop/md.ttrm")

            // AST and semantics dumps of the generated package equal the source world's, model by model.
            val models = listOf("cn", "er", "md")
            val source =
                models.associateWith {
                    CanonicalYaml.fromTtr(
                        world.resolve("shop/$it.ttrm").readText(),
                        "$it.ttrm",
                    )
                }
            val back =
                models.associateWith {
                    CanonicalYaml.fromTtr(
                        body(generated.files.getValue("shop/$it.ttrm")),
                        "$it.ttrm",
                    )
                }
            for (model in models) {
                withClue(model) {
                    ConformanceDump.dumpTree(back.getValue(model).ast) shouldBe
                        ConformanceDump.dumpTree(source.getValue(model).ast)
                }
            }
            SemanticsConformanceDump.dumpDocs(
                models.map { SemanticsConformanceDump.DocInput("shop/$it.ttrm", back.getValue(it).ast) },
            ) shouldBe
                SemanticsConformanceDump.dumpDocs(
                    models.map { SemanticsConformanceDump.DocInput("shop/$it.ttrm", source.getValue(it).ast) },
                )

            // The generated package (with the world's `def world`) loads strict and exports the same XMI bytes.
            write(root, "model", generated)
            root.resolve("model/world").createDirectories()
            Files.copy(world.resolve("world/world.ttrm"), root.resolve("model/world/world.ttrm"))
            String(exportXmi(root.resolve("model")), Charsets.UTF_8) shouldBe String(exported, Charsets.UTF_8)
        }

        test("tagged values survive; what the projection or the engine cannot carry is reported with a reason") {
            val generated = run(workspace(), "shop.xmi", "ttr-mdg/1", "shop")
            val cn = body(generated.files.getValue("shop/cn.ttrm"))
            val er = body(generated.files.getValue("shop/er.ttrm"))
            val md = body(generated.files.getValue("shop/md.ttrm"))
            cn shouldContain "def event Purchase { extent: instantaneous, involves: [Party as buyer, Product as item] }"
            cn shouldContain "def kind Prospect { is_a: [Party], abstract: true }"
            cn shouldContain "def kind Party { description: { en: \"A trading party\" } }"
            md shouldContain "def measure balance { class: semiAdditive, aggregation: sum, validBy: Calendar.day }"
            md shouldContain "def measure units { aggregation: count }"
            md shouldContain "def dimension Calendar { key: day,"
            er shouldContain "cardinality: { from: \"0..*\", to: \"1\" }"
            er shouldContain "def attribute name { type: text, optional: true, }"
            er shouldContain "def entity customer { description: \"A buyer of record\","
            val reasons = reasons(generated)
            reasons shouldContain ("/elements/*/@tags" to "the MDG bookkeeping (qname, tier)")
            reasons shouldContain ("/elements/*/@tags" to "the MDG bookkeeping (qname)")
            reasons.none { it.first == "/@diagrams" } shouldBe true
            // The involves associations are read through their event; a «concept» is made by its cnKind.
            report(generated).getValue("ignored").jsonArray.map {
                it.jsonObject["reason"]?.jsonPrimitive?.content
            } shouldContain "kind mapping null (/@stereotypes = involves)"
        }

        test("ea-erd/1: classes → er entities and attributes, associations → relations; is_a only with cn-from-uml") {
            val root = workspace()
            val plain = run(root, "orders-erd.xmi", "ea-erd/1", "orders", out = "erd")
            matches(plain, "erd")
            plain.files.keys shouldContainExactly listOf("orders/er.ttrm")
            val er = body(plain.files.getValue("orders/er.ttrm"))
            er shouldContain
                "def relation placed_by { description: \"Every order is placed by exactly one customer.\", " +
                "from: Order, to: Customer, cardinality: { from: \"0..*\", to: \"1\" },"
            er shouldContain
                "def attribute email { type: String, optional: true, description: \"Where order confirmations go.\", }"
            reasons(plain) shouldContain
                ("/elements/*/generalization" to "an er entity has no is_a (cn is_a only with cn-from-uml: true)")
            write(root, "erd", plain)
            loadStrict(root.resolve("erd"))

            val withCn =
                run(
                    root,
                    "orders-erd.xmi",
                    fixture.resolve("profiles/ea-erd-cn.yaml").toString(),
                    "orders",
                    out = "erd-cn",
                )
            matches(withCn, "erd-cn")
            withCn.files.keys shouldContainExactly listOf("orders/cn.ttrm", "orders/er.ttrm")
            body(withCn.files.getValue("orders/cn.ttrm")) shouldContain
                "def kind KeyAccount { is_a: [Customer], abstract: true }"
            body(withCn.files.getValue("orders/er.ttrm")) shouldBe er
            write(root, "erd-cn", withCn)
            loadStrict(root.resolve("erd-cn"))
        }

        test("GUIDs → UUIDv5 foreignIds; a renamed class → delta.renamed; the stale overlay key → xm/overlay-orphan") {
            val root = workspace(renames = true)
            val first = run(root, "shop.xmi", "ttr-mdg/1", "shop", overlay = fixture.resolve("overlay.yaml"))
            report(first)["delta"] shouldBe null
            report(
                first,
            ).getValue("overridden").jsonArray.single().jsonObject.getValue("qname").jsonPrimitive.content shouldBe
                "shop.cn.kind.Product"
            val entries =
                Json
                    .parseToJsonElement(
                        first.sidecars.getValue("shop.sources.json"),
                    ).jsonObject
                    .getValue("entries")
                    .jsonArray
            val product =
                entries.map { it.jsonObject }.single {
                    it.getValue("qname").jsonPrimitive.content ==
                        "shop.cn.kind.Product"
                }
            val guid = product.getValue("sourceName").jsonPrimitive.content
            product.getValue("foreignId").jsonPrimitive.content shouldBe
                Uuid5.of(XmiFrontEnd.NAMESPACE, guid).toString()
            entries.all { (it.jsonObject["foreignId"] as? JsonPrimitive)?.content?.length == 36 } shouldBe true
            write(root, "model", first)

            // Snapshot 2: the class renamed in EA (same GUID). The sidecar diff against the previous generation.
            val second = run(root, "shop-renamed.xmi", "ttr-mdg/1", "shop")
            val renamed =
                report(second)
                    .getValue("delta")
                    .jsonObject
                    .getValue("renamed")
                    .jsonArray
            renamed.map {
                it.jsonObject
                    .getValue("from")
                    .jsonPrimitive.content to
                    it.jsonObject
                        .getValue("to")
                        .jsonPrimitive.content
            } shouldBe
                listOf("shop.cn.kind.Product" to "shop.cn.kind.Article")
            renamed
                .single()
                .jsonObject
                .getValue("foreignId")
                .jsonPrimitive.content shouldBe
                Uuid5.of(XmiFrontEnd.NAMESPACE, guid).toString()
            second.report.markdown() shouldContain "| `shop.cn.kind.Product` | `shop.cn.kind.Article` |"
            body(second.files.getValue("shop/cn.ttrm")) shouldContain "involves: [Party as buyer, Article as item]"

            // The overlay still keyed by the old name is an orphan, with renames.yaml's suggestion.
            val orphan =
                shouldThrow<ProfileException> {
                    run(root, "shop-renamed.xmi", "ttr-mdg/1", "shop", overlay = fixture.resolve("overlay.yaml"))
                }
            orphan.code shouldBe "xm/overlay-orphan"
            orphan.suggestion.toString() shouldBe
                """{"delta":{"renamed":{"from":"shop.cn.kind.Product","to":"shop.cn.kind.Article","since":"shop-2"}}}"""
        }

        test("diagrams and the rest of xmi:Extension are not read; each diagram is a stub the report lists") {
            val bytes = Files.readAllBytes(fixture.resolve("sources/orders-erd.xmi"))
            val tree = Json.parseToJsonElement(XmiFrontEnd.tree(bytes, "orders-erd.xmi")).jsonObject
            val diagrams = tree.getValue("@diagrams").jsonArray
            diagrams.map {
                it.jsonObject
                    .getValue("name")
                    .jsonPrimitive.content
            } shouldBe listOf("Orders ERD")
            val text = tree.toString()
            text shouldNotContain "geometry"
            text shouldNotContain "BackColor"
            text shouldNotContain "ea_localid"
            text shouldNotContain "Analyst"
            // EA's primitive types live in the extension; references to them still carry the type's name.
            tree
                .getValue("elements")
                .jsonObject.values
                .first()
                .jsonObject
                .getValue("ownedAttribute")
                .jsonArray
                .first()
                .jsonObject
                .getValue("type")
                .jsonArray
                .single() shouldBe
                Json.parseToJsonElement("""{"${'$'}ref": "EAJava_int", "@name": "int"}""")
            val generated = run(workspace(), "orders-erd.xmi", "ea-erd/1", "orders")
            reasons(generated) shouldContain ("/@diagrams" to "diagrams are not model facts")
            generated.report.markdown() shouldContain "| `/@diagrams` | diagrams are not model facts | 1 |"
            (tree.getValue("elements") as JsonObject).size shouldBe 6
            (diagrams as JsonArray).size shouldBe 1
        }

        // ---- review-372 ----

        /** The AST dump of each generated model, and the semantics dump of all of them. */
        fun dumps(generated: GeneratedPackage): Pair<Map<String, Any>, Any> {
            val asts =
                generated.files.toSortedMap().mapValues { (path, text) ->
                    CanonicalYaml.fromTtr(body(text), path.substringAfterLast('/')).ast
                }
            return asts.mapValues { ConformanceDump.dumpTree(it.value) } to
                SemanticsConformanceDump.dumpDocs(asts.map { SemanticsConformanceDump.DocInput(it.key, it.value) })
        }

        test("F2 ✅R372-A: EA's child-form attribute types read as the attribute form, dump-equal") {
            val root = workspace()
            val text = root.resolve("sources/shop.xmi").readText()
            val attribute = Regex("""(<ownedAttribute [^>]*?) type="([^"]+)"/>""")
            attribute.findAll(text).count() shouldBe 9
            val child =
                attribute.replace(text) {
                    "${it.groupValues[1]}><type xmi:idref=\"${it.groupValues[2]}\"/></ownedAttribute>"
                }
            root.resolve("sources/child.xmi").writeText(child)
            val asAttributes = run(root, "shop.xmi", "ttr-mdg/1", "shop")
            val asChildren = run(root, "child.xmi", "ttr-mdg/1", "shop")
            dumps(asChildren) shouldBe dumps(asAttributes)
        }

        fun renames(generated: GeneratedPackage): List<Pair<String, String>> =
            report(generated)["delta"]
                ?.jsonObject
                ?.getValue("renamed")
                ?.jsonArray
                ?.map {
                    it.jsonObject
                        .getValue("from")
                        .jsonPrimitive.content to
                        it.jsonObject
                            .getValue("to")
                            .jsonPrimitive.content
                }.orEmpty()

        test("F3: the rename delta pairs qnames within a kind; a kind that appears or goes is no rename") {
            val root = workspace()
            val renamed =
                root
                    .resolve(
                        "sources/orders-erd.xmi",
                    ).readText(charset("windows-1252"))
                    .replace("name=\"Customer\"", "name=\"Client\"")
            root.resolve("sources/orders-client.xmi").writeText(renamed, charset("windows-1252"))
            // Run 1 with the cn skeleton (cn + er), run 2 without it (er only), one class renamed in between.
            write(
                root,
                "f",
                run(root, "orders-erd.xmi", fixture.resolve("profiles/ea-erd-cn.yaml").toString(), "orders", out = "f"),
            )
            renames(run(root, "orders-client.xmi", "ea-erd/1", "orders", out = "f")) shouldBe
                listOf(
                    "orders.er.entity.Customer" to "orders.er.entity.Client",
                    "orders.er.entity.Customer.email" to "orders.er.entity.Client.email",
                    "orders.er.entity.Customer.id" to "orders.er.entity.Client.id",
                )
        }

        test("F3: a foreign id with one qname on each side is a rename even when its kind changed") {
            val dir = Files.createTempDirectory("s53-delta")
            val previous = dir.resolve("p.sources.json")
            previous.writeText(
                """{"entries": [{"qname": "p.er.entity.X", "kind": "entity", "foreignId": "u1"}, """ +
                    """{"qname": "p.er.entity.Y", "kind": "entity", "foreignId": "u2"}, """ +
                    """{"qname": "p.cn.kind.Y", "kind": "kind", "foreignId": "u2"}]}""",
            )
            SidecarDelta.renamed(
                previous,
                listOf(
                    org.tatrman.ttr.profile
                        .SourceEntry("p.md.dimension.X", "dimension", "p", "u1", null, null),
                    org.tatrman.ttr.profile
                        .SourceEntry("p.md.dimension.Y", "dimension", "p", "u2", null, null),
                ),
            ) shouldBe listOf(ImportReport.Renamed("p.er.entity.X", "p.md.dimension.X", "u1"))
        }
    })
