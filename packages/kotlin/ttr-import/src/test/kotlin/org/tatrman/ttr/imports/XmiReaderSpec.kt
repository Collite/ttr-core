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
import kotlin.io.path.deleteExisting
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

        /** [input] under `<root>/sources` re-exported: its bytes replaced by [snapshot]'s (from [from]'s `sources/`). */
        fun reexport(
            root: Path,
            input: String,
            snapshot: String,
            from: Path = root,
        ) {
            Files.copy(
                from.resolve("sources/$snapshot"),
                root.resolve("sources/$input"),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
            )
        }

        fun run(
            root: Path,
            input: String,
            profile: String,
            pkg: String?,
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
                "from: orders.er.entity.Order, to: orders.er.entity.Customer, cardinality: { from: \"0..*\", to: \"1\" },"
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

            // Snapshot 2: the class renamed in EA (same GUID), re-exported to the same path (✅R378-A: the delta reads this
            // input's previous generation). The sidecar diff against it.
            reexport(root, "shop.xmi", "shop-renamed.xmi")
            val second = run(root, "shop.xmi", "ttr-mdg/1", "shop")
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

        /** A sidecar generated from `in.x` beside it (any hash), holding [entries]. */
        fun sidecar(entries: String) =
            """{"generated-from": "generated-from: in.x@${"0".repeat(64)} profile p/1 generator g — do not edit", """ +
                """"entries": [$entries]}"""

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
            // Run 1 with the cn skeleton (cn + er), run 2 without it (er only), one class renamed in between (the same
            // input path, re-exported).
            write(
                root,
                "f",
                run(root, "orders-erd.xmi", fixture.resolve("profiles/ea-erd-cn.yaml").toString(), "orders", out = "f"),
            )
            root.resolve("sources/orders-erd.xmi").writeText(renamed, charset("windows-1252"))
            renames(run(root, "orders-erd.xmi", "ea-erd/1", "orders", out = "f")) shouldBe
                listOf(
                    "orders.er.entity.Customer" to "orders.er.entity.Client",
                    "orders.er.entity.Customer.email" to "orders.er.entity.Client.email",
                    "orders.er.entity.Customer.id" to "orders.er.entity.Client.id",
                )
        }

        test(
            "S5.3a T4b: the delta pairs across packages — a moved class names both packages; an emptied package's rest is removed",
        ) {
            val root = workspace()
            write(root, "m", run(root, "sales-billing.xmi", "ea-erd/1", null, out = "m"))
            // Another input's import in the same --out is not this input's generation (✅R378-A).
            write(root, "m", run(root, "shop.xmi", "ttr-mdg/1", "shop", out = "m"))
            // Snapshot 3, re-exported to the same path: Invoice moved sales/billing → sales, billed_to deleted
            // (sales.billing emptied), Order renamed. The hash differs; the path pairs it (✅R378-A).
            reexport(root, "sales-billing.xmi", "sales-billing-moved.xmi")
            val moved = run(root, "sales-billing.xmi", "ea-erd/1", null, out = "m")
            moved.files.keys shouldContainExactly listOf("sales/er.ttrm")
            // review-378 F3: the emptied package is named, with its two paths; shop is not.
            report(moved).getValue("delta").jsonObject.getValue("emptied") shouldBe
                Json.parseToJsonElement("""["sales.billing"]""")
            moved.report.markdown() shouldContain "#### Emptied since the last generation (1)"
            moved.report.markdown() shouldContain "- `sales.billing`: `sales.billing.sources.json` and `sales/billing/`"
            moved.report.markdown() shouldNotContain "shop."
            val delta = report(moved).getValue("delta").jsonObject
            delta.getValue("renamed").jsonArray.map { it.jsonObject.toString() } shouldBe
                listOf(
                    """{"from":"sales.billing.er.entity.Invoice","to":"sales.er.entity.Invoice",""" +
                        """"foreignId":"5d2a62bd-8b3f-5be6-9a3e-190df23181ae","package":{"from":"sales.billing","to":"sales"}}""",
                    """{"from":"sales.billing.er.entity.Invoice.total","to":"sales.er.entity.Invoice.total",""" +
                        """"foreignId":"f2077dc4-7025-58ff-be14-ed4f7faad021","package":{"from":"sales.billing","to":"sales"}}""",
                    """{"from":"sales.er.entity.Order","to":"sales.er.entity.PurchaseOrder",""" +
                        """"foreignId":"${Uuid5.of(
                            XmiFrontEnd.NAMESPACE,
                            "EAID_7B1C2D3E_4F5A_4B6C_8D7E_9F0A1B2C3D12",
                        )}"}""",
                    """{"from":"sales.er.entity.Order.order_no","to":"sales.er.entity.PurchaseOrder.order_no",""" +
                        """"foreignId":"${Uuid5.of(
                            XmiFrontEnd.NAMESPACE,
                            "EAID_7B1C2D3E_4F5A_4B6C_8D7E_9F0A1B2C3D22",
                        )}"}""",
                )
            delta.getValue("removed") shouldBe
                Json.parseToJsonElement(
                    """[{"qname":"sales.billing.er.relation.billed_to","foreignId":"5aae9e62-40b3-5c5c-b12f-3412dc018732"}]""",
                )
            moved.report.markdown() shouldContain "#### Removed since the last generation (1)"
            moved.report.markdown() shouldContain
                "- `sales.billing.er.relation.billed_to` (`5aae9e62-40b3-5c5c-b12f-3412dc018732`)"
            // The stale files deleted as the report says, the move back is a rename again (sales → sales.billing).
            write(root, "m", moved)
            root.resolve("m/sales.billing.sources.json").deleteExisting()
            root.resolve("m/sales/billing/er.ttrm").deleteExisting()
            reexport(root, "sales-billing.xmi", "sales-billing.xmi", fixture)
            val back = report(run(root, "sales-billing.xmi", "ea-erd/1", null, out = "m")).getValue("delta").jsonObject
            back.keys shouldBe setOf("renamed")
            back
                .getValue("renamed")
                .jsonArray
                .first()
                .jsonObject
                .getValue("package") shouldBe
                Json.parseToJsonElement("""{"from":"sales","to":"sales.billing"}""")
            // Only an emptied package's entries are removed (with or without a foreign id); what left a package the run
            // still emits is not (the delta stays the rename half there).
            val dir = Files.createTempDirectory("s53a-delta")
            dir.resolve("a.sources.json").writeText(
                sidecar(
                    """{"qname": "a.er.entity.X", "kind": "entity", "foreignId": "u1"}, """ +
                        """{"qname": "a.er.entity.Y", "kind": "entity", "foreignId": "u2"}""",
                ),
            )
            dir.resolve("b.sources.json").writeText(
                sidecar(
                    """{"qname": "b.er.entity.Z", "kind": "entity", "foreignId": "u3"}, """ +
                        """{"qname": "b.er.entity.W", "kind": "entity"}""",
                ),
            )
            // No header, or another input's: not this input's generation (✅R378-A), so neither is emptied.
            dir.resolve("d.sources.json").writeText(
                """{"entries": [{"qname": "d.er.entity.U", "kind": "entity", "foreignId": "u5"}]}""",
            )
            dir.resolve("e.sources.json").writeText(
                sidecar(
                    """{"qname": "e.er.entity.T", "kind": "entity", "foreignId": "u6"}""",
                ).replace("in.x@", "other.x@"),
            )
            // c's only entry moved to a: c is emptied all the same (review-378 F3), with nothing removed.
            dir.resolve("c.sources.json").writeText(
                sidecar("""{"qname": "c.er.entity.V", "kind": "entity", "foreignId": "u4"}"""),
            )
            SidecarDelta.of(
                dir,
                dir.resolve("in.x"),
                listOf(
                    org.tatrman.ttr.profile
                        .SourceEntry("a.er.entity.X", "entity", "a", "u1", null, null),
                    org.tatrman.ttr.profile
                        .SourceEntry("a.er.entity.V", "entity", "a", "u4", null, null),
                ),
            ) shouldBe
                SidecarDelta.Delta(
                    listOf(ImportReport.Renamed("c.er.entity.V", "a.er.entity.V", "u4", ImportReport.Moved("c", "a"))),
                    listOf(ImportReport.Removed("b.er.entity.Z", "u3"), ImportReport.Removed("b.er.entity.W", null)),
                    listOf("b", "c"),
                )
        }

        test("review-378 F4: a package mapping keys on the input's stem; a files: glob sees <stem>/<package path>") {
            val root = workspace()
            val profile =
                root.resolve("f4.yaml").also {
                    it.writeText(
                        "profile: f4/1\ngrammar: '>=0.22 <1.0'\nunknown: ignore\noperations:\n" +
                            "  - {op: package, mapping: {sales-billing: shop}}\n" +
                            "  - {op: kinds, at: /elements, id: /name, kind: /@xmi/type, unmapped: skip, " +
                            "mapping: {'uml:Class': entity}}\n" +
                            "  - {op: rename, in: entity, at: /@properties/documentation, " +
                            "to: {kind: entity, property: description}, files: ['sales-billing/sales/billing']}\n",
                    )
                }
            val generated = run(root, "sales-billing.xmi", profile.toString(), null, out = "f4")
            generated.files.keys shouldContainExactly listOf("shop/er.ttrm")
            val er = body(generated.files.getValue("shop/er.ttrm"))
            er shouldContain "def entity Customer {"
            er shouldContain "def entity Invoice { description: \"What a customer owes for an order.\""
            // The rename is scoped to the billing tree: Customer's documentation (in sales) is not read.
            er shouldNotContain "A buyer of record."
        }

        test("✅R378-A: another input's sidecars under the same --out are no previous generation (review-378 F1)") {
            val root = workspace()
            write(root, "p1", run(root, "shop.xmi", "ttr-mdg/1", "shop", out = "p1"))
            val orders = run(root, "orders-erd.xmi", "ea-erd/1", "orders", out = "p1")
            report(orders)["delta"] shouldBe null
            orders.report.markdown() shouldNotContain "Removed since"
            orders.report.markdown() shouldNotContain "Emptied since"
        }

        test("T4c ✅D-MW-98: an association without EA's connector ends is a relation with no ends and two warnings") {
            val root = workspace()
            val erd = root.resolve("sources/orders-erd.xmi").readText(charset("windows-1252"))
            val endless =
                erd
                    .replace(Regex("<source xmi:idref=\"[^\"]+\">"), "<source>")
                    .replace(Regex("<target xmi:idref=\"[^\"]+\">"), "<target>")
            root.resolve("sources/endless.xmi").writeText(endless, charset("windows-1252"))
            val generated = run(root, "endless.xmi", "ea-erd/1", "orders", out = "e")
            body(generated.files.getValue("orders/er.ttrm")) shouldContain "def relation placed_by { description:"
            body(generated.files.getValue("orders/er.ttrm")) shouldNotContain "from: orders"
            val warnings = report(generated).getValue("warnings").jsonArray.map { it.jsonPrimitive.content }
            warnings.filter { "ys/key-missing" in it }.map { it.substringBefore(" — ") }.sorted() shouldBe
                listOf(
                    "ys/key-missing: lists has no /@source/\$ref, so relation.from is not written",
                    "ys/key-missing: lists has no /@target/\$ref, so relation.to is not written",
                    "ys/key-missing: placed_by has no /@source/\$ref, so relation.from is not written",
                    "ys/key-missing: placed_by has no /@target/\$ref, so relation.to is not written",
                )
            warnings.first { "placed_by has no" in it } shouldContain " — endless.xmi /elements/"
        }

        test("F3: a foreign id with one qname on each side is a rename even when its kind changed") {
            val dir = Files.createTempDirectory("s53-delta")
            val previous = dir.resolve("p.sources.json")
            previous.writeText(
                sidecar(
                    """{"qname": "p.er.entity.X", "kind": "entity", "foreignId": "u1"}, """ +
                        """{"qname": "p.er.entity.Y", "kind": "entity", "foreignId": "u2"}, """ +
                        """{"qname": "p.cn.kind.Y", "kind": "kind", "foreignId": "u2"}""",
                ),
            )
            SidecarDelta
                .of(
                    dir,
                    dir.resolve("in.x"),
                    listOf(
                        org.tatrman.ttr.profile
                            .SourceEntry("p.md.dimension.X", "dimension", "p", "u1", null, null),
                        org.tatrman.ttr.profile
                            .SourceEntry("p.md.dimension.Y", "dimension", "p", "u2", null, null),
                    ),
                ).renamed shouldBe listOf(ImportReport.Renamed("p.er.entity.X", "p.md.dimension.X", "u1"))
        }

        test("F5 ✅R372-C: a child's own cn operations under an inherited cn-from-uml: false are a located error") {
            val root = workspace()

            fun child(flag: String) =
                root.resolve("child-${flag.ifEmpty { "none" }.replace(": ", "")}.yaml").also {
                    it.writeText(
                        "profile: erd-described/1\ngrammar: '>=0.22 <1.0'\nextends: ea-erd/1\n$flag\noperations:\n" +
                            "  - {op: shape, in: kind, to: {kind: kind, property: description}, at: /@properties, " +
                            "template: {en: {input: /documentation}}}\n",
                    )
                }
            val inherited = child("")
            val refused =
                shouldThrow<ProfileException> { run(root, "orders-erd.xmi", inherited.toString(), "orders", out = "a") }
            refused.code shouldBe "ys/profile-invalid"
            refused.location.file shouldBe inherited.toRealPath().toString()
            refused.location.line shouldBe 6
            refused.message.orEmpty() shouldContain "cn operations need `cn-from-uml: true`"
            refused.message.orEmpty() shouldContain "ea-erd/1"
            val opted = run(root, "orders-erd.xmi", child("cn-from-uml: true").toString(), "orders", out = "b")
            body(opted.files.getValue("orders/cn.ttrm")) shouldContain
                "def kind Customer { description: { en: \"A person or company that places orders.\" }, abstract: true }"
        }

        test(
            "N1 ✅R372-D: a child restating cn-from-uml: false drops its ancestors' cn operations; only a profile below errs",
        ) {
            val root = workspace()

            fun profile(
                name: String,
                extends: String,
                flag: String,
                operations: String = " []",
            ) = root.resolve("$name.yaml").also {
                it.writeText(
                    "profile: $name/1\ngrammar: '>=0.22 <1.0'\nextends: $extends\n$flag\noperations:$operations\n",
                )
            }

            fun bodies(generated: GeneratedPackage) = generated.files.mapValues { body(it.value) }
            val restated = profile("restated", "ea-erd/1", "cn-from-uml: false")
            val plain = run(root, "orders-erd.xmi", "ea-erd/1", "orders", out = "a")
            bodies(run(root, "orders-erd.xmi", restated.toString(), "orders", out = "b")) shouldBe bodies(plain)

            val described =
                "\n  - {op: shape, in: kind, to: {kind: kind, property: description}, at: /@properties, " +
                    "template: {en: {input: /documentation}}}"
            val grandchild = profile("grandchild", "./restated.yaml", "", described)
            val refused =
                shouldThrow<ProfileException> {
                    run(root, "orders-erd.xmi", grandchild.toString(), "orders", out = "c")
                }
            refused.code shouldBe "ys/profile-invalid"
            refused.location.file shouldBe grandchild.toRealPath().toString()
            refused.location.line shouldBe 6
            refused.message.orEmpty() shouldContain "the chain inherits false from restated/1"

            val reopened = profile("reopened", "./restated.yaml", "cn-from-uml: true", described)
            body(
                run(root, "orders-erd.xmi", reopened.toString(), "orders", out = "d").files.getValue("orders/cn.ttrm"),
            ) shouldContain
                "def kind Customer { description: { en: \"A person or company that places orders.\" }, abstract: true }"
        }

        test(
            "S5.3a: one TTR package per UML package (verbatim); a cross-package end is a qualified reference, no import",
        ) {
            val root = workspace()
            val generated = run(root, "sales-billing.xmi", "ea-erd/1", null, out = "multi")
            matches(generated, "multi")
            generated.files.keys shouldContainExactly listOf("sales/billing/er.ttrm", "sales/er.ttrm")
            val billing = body(generated.files.getValue("sales/billing/er.ttrm"))
            billing shouldContain "package sales.billing"
            billing shouldContain "def entity Invoice {"
            billing shouldNotContain "def entity Customer"
            billing shouldContain "from: sales.billing.er.entity.Invoice, to: sales.er.entity.Customer,"
            billing shouldNotContain "import"
            val sales = body(generated.files.getValue("sales/er.ttrm"))
            sales shouldContain "package sales"
            sales shouldContain "from: sales.er.entity.Order, to: sales.er.entity.Customer,"
            sales shouldNotContain "import"
            sales shouldNotContain "Invoice"
            report(generated).getValue("packages").jsonArray.map {
                it.jsonObject
                    .getValue("package")
                    .jsonPrimitive.content
            } shouldBe listOf("sales.billing", "sales") // the report lists packages by file path
            // ✅D-MW-101: billed_to's end Customer (package sales) resolves in sales.billing; the world loads strict.
            write(root, "multi", generated)
            loadStrict(root.resolve("multi"))
            // One input, several trees under its one label: an error in the first package's tree names its own XMI line
            // (placed_by, line 20), not a line of the last tree read.
            root.resolve("sources/dated.xmi").writeText(
                root.resolve("sources/sales-billing.xmi").readText().replace("name=\"Order\"", "name=\"date\""),
            )
            val dated = shouldThrow<ProfileException> { run(root, "dated.xmi", "ea-erd/1", null, out = "dated") }
            dated.code shouldBe "ys/profile-invalid"
            dated.location.file shouldBe "dated.xmi"
            dated.location.line shouldBe 20
            // --package still overrides every tree: one package, as before.
            run(root, "sales-billing.xmi", "ea-erd/1", "orders", out = "one").files.keys shouldContainExactly
                listOf("orders/er.ttrm")
            // EA's own root package name (`Orders`) is no TTR package path: located at the first element.
            val refused =
                shouldThrow<ProfileException> { run(root, "orders-erd.xmi", "ea-erd/1", null, out = "two") }
            refused.code shouldBe "ys/package-invalid"
            refused.location.file shouldBe "orders-erd.xmi"
            refused.location.line shouldBe 6
            refused.message.orEmpty() shouldContain "\"Orders\""
        }

        test("S5.3a: the shop round trip names its package by the UML package, as --package shop did") {
            val root = workspace()
            run(root, "shop.xmi", "ttr-mdg/1", null).outputs() shouldBe
                run(root, "shop.xmi", "ttr-mdg/1", "shop").outputs()
        }
    })
