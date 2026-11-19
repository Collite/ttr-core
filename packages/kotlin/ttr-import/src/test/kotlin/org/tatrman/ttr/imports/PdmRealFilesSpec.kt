// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.imports

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText
import org.tatrman.ttr.profile.ProfileException

private fun pdId(key: String) = UUID.nameUUIDFromBytes(key.toByteArray()).toString()

private fun pdObject(
    tag: String,
    key: String,
    code: String,
    body: String = "",
) = """<o:$tag Id="$key"><a:ObjectID>${pdId(key)}</a:ObjectID><a:Code>$code</a:Code>$body</o:$tag>"""

private fun pdModel(
    kind: String,
    code: String,
    body: String,
    key: String = "model_$code",
) = """
    <?xml version="1.0"?>
    <?PowerDesigner signature="${kind}_DATA_MODEL_XML" version="16.7"?>
    <Model xmlns:a="attribute" xmlns:c="collection" xmlns:o="object">
    ${pdObject("Model", key, code, body)}</Model>
    """.trimIndent().trimStart()

private fun pdRef(
    collection: String,
    tag: String,
    key: String,
) = """<c:$collection><o:$tag Ref="$key"/></c:$collection>"""

private fun pdRun(input: Path) =
    Reader.run(
        PdmFrontEnd,
        ImportProfile.resolve("pdm/16"),
        ReaderRequest(listOf(input), input.resolveSibling("model"), "0.0.1-LOCAL"),
    )

private fun pdDir() = Files.createTempDirectory("s54c").resolve("sources").also { it.createDirectories() }

class PdmRealFilesSpec :
    FunSpec({
        test("C3 all three overlapping input shapes refuse duplicate labels and name both absolute files") {
            val root = pdDir()
            val a = root.resolve("dev").createDirectories().resolve("sales.pdm")
            val b = root.resolve("prod").createDirectories().resolve("sales.pdm")
            a.writeText(pdModel("PDM", "dev", ""))
            b.writeText(pdModel("PDM", "prod", ""))
            for (inputs in listOf(listOf(a, b), listOf(a.parent, b.parent), listOf(a, b.parent))) {
                val e = shouldThrow<ReaderInputException> { PdmFrontEnd.parse(inputs) }
                e.message!! shouldContain "two inputs are labelled sales.pdm"
                e.message!! shouldContain a.toAbsolutePath().toString()
                e.message!! shouldContain b.toAbsolutePath().toString()
            }
            PdmFrontEnd.parse(listOf(root)).map { it.label }.toSet() shouldBe setOf("dev/sales.pdm", "prod/sales.pdm")
        }
        test("C1 generation from a relationship with an external shortcut does not abort either input order") {
            val root = pdDir()
            val sourceBody =
                "<c:Entities>" + pdObject("Entity", "local", "local") + "</c:Entities>" +
                    "<c:Shortcuts>" +
                    pdObject("Shortcut", "external", "external", "<a:TargetID>${pdId("outside")}</a:TargetID>") +
                    "</c:Shortcuts>" +
                    "<c:Relationships>" +
                    pdObject(
                        "Relationship",
                        "rel",
                        "rel",
                        pdRef("Object1", "Entity", "local") + pdRef("Object2", "Shortcut", "external"),
                    ) +
                    "</c:Relationships>"
            val columns = pdObject("Column", "pk", "id") + pdObject("Column", "fk", "parent_id")
            val table = pdObject("Table", "t", "t", "<c:Columns>$columns</c:Columns>")
            val join =
                pdObject(
                    "ReferenceJoin",
                    "j",
                    "j",
                    pdRef("Object1", "Column", "pk") + pdRef("Object2", "Column", "fk"),
                )
            val target =
                pdObject(
                    "Reference",
                    "target",
                    "generated_fk",
                    "<a:History>ORG {${pdId("rel")}}</a:History><c:Joins>$join</c:Joins>",
                )
            val origin = pdObject("Shortcut", "origin", "origin", "<a:TargetID>${pdId("ldm")}</a:TargetID>")
            for ((ldm, pdm) in listOf("a.ldm" to "z.pdm", "z.ldm" to "a.pdm")) {
                Files.list(root).use { it.forEach(Files::delete) }
                root.resolve(ldm).writeText(pdModel("LDM", "logical", sourceBody, "ldm"))
                root
                    .resolve(
                        pdm,
                    ).writeText(
                        pdModel(
                            "PDM",
                            "physical",
                            "<c:GenerationOrigins>$origin</c:GenerationOrigins><c:Tables>$table</c:Tables><c:References>$target</c:References>",
                        ),
                    )
                val result = pdRun(root)
                result.files.getValue("logical/er.ttrm") shouldContain "def relation rel"
                result.files.getValue("physical/binding.ttrm") shouldContain "logical.er.relation.rel"
                result.report.warnings.joinToString() shouldContain "ys/key-missing"
                // An unusable FK target must not leave a binding to a record removed during projection.
                val file = root.resolve(pdm)
                file.writeText(file.readText().replace("<c:Joins>$join</c:Joins>", "<c:Joins/>"))
                val withoutTarget = pdRun(root)
                withoutTarget.files.keys.any { it.endsWith("binding.ttrm") } shouldBe false
                withoutTarget.report.ignored.any {
                    it.reason?.contains(
                        "source or target record was skipped",
                    ) == true
                } shouldBe
                    true
            }
        }
        test("C2 CDM relationship with an external end stays a relation type") {
            val root = pdDir()
            val shortcut = pdObject("Shortcut", "sc", "outside", "<a:TargetID>${pdId("outside")}</a:TargetID>")
            val relation = pdObject("Relationship", "rel", "rel", pdRef("Object1", "Shortcut", "sc"))
            root
                .resolve(
                    "model.cdm",
                ).writeText(
                    pdModel(
                        "CDM",
                        "cn",
                        "<c:Shortcuts>$shortcut</c:Shortcuts><c:Relationships>$relation</c:Relationships>",
                    ),
                )
            pdRun(root).files.getValue("cn/cn.ttrm") shouldContain "def relation_type rel"
        }
        test("C4 case variants coexist across output models but refuse the same output file with both labels") {
            val root = pdDir()
            root
                .resolve(
                    "logical.ldm",
                ).writeText(pdModel("LDM", "Billing", "<c:Entities>${pdObject("Entity", "e", "Item")}</c:Entities>"))
            root
                .resolve(
                    "physical.pdm",
                ).writeText(pdModel("PDM", "BILLING", "<c:Tables>${pdObject("Table", "t", "Item")}</c:Tables>"))
            pdRun(root).files.keys.toSet() shouldBe setOf("billing/er.ttrm", "billing/db.ttrm")
            root
                .resolve(
                    "other.ldm",
                ).writeText(
                    pdModel("LDM", "BILLING", "<c:Entities>${pdObject("Entity", "other", "Other")}</c:Entities>"),
                )
            val e = shouldThrow<ProfileException> { pdRun(root) }
            e.code shouldBe "ys/package-invalid"
            e.message!! shouldContain "logical.ldm"
            e.message!! shouldContain "other.ldm"
        }
        test("duplicate relationship Codes rename by ObjectID order, preserve identity, and warn once") {
            val root = pdDir()
            val sorted = listOf("r1", "r2", "r3").sortedBy(::pdId)

            fun run(order: List<String>): GeneratedPackage {
                root.resolve("model.cdm").writeText(
                    pdModel(
                        "CDM",
                        "cn",
                        "<c:Relationships>" + order.joinToString("\n") { pdObject("Relationship", it, "BELONGS_TO") } +
                            "</c:Relationships>",
                    ),
                )
                return pdRun(root)
            }
            val a = run(sorted.reversed())
            val text = a.files.getValue("cn/cn.ttrm").substringAfter('\n')
            text shouldContain "def relation_type BELONGS_TO {"
            for (key in sorted.drop(1)) {
                text shouldContain
                    "def relation_type BELONGS_TO__${pdId(key).replace("-", "").take(8)}"
            }
            a.report.warnings.size shouldBe 2
            a.report.warnings.forEach {
                it shouldContain "model.cdm:"
                it shouldContain "BELONGS_TO"
            }
            a.sidecars.values.single() shouldContain pdId(sorted[1])
            run(sorted).files.getValue("cn/cn.ttrm").substringAfter('\n') shouldBe text
            root.resolve("model.cdm").writeText(
                pdModel(
                    "CDM",
                    "cn",
                    "<c:Relationships>" + pdObject("Relationship", "r1", "DUP") +
                        pdObject("Relationship", "r2", "DUP").replace(pdId("r2"), pdId("r1")) +
                        "</c:Relationships>",
                ),
            )
            shouldThrow<ProfileException> { pdRun(root) }
        }
        test("unmodified public PD16 project.cdm imports end to end and reports duplicate relationship renames") {
            val root = pdDir()
            val bytes = javaClass.getResourceAsStream("/pdm-public/project.cdm")!!.use { it.readAllBytes() }
            root.resolve("project.cdm").toFile().writeBytes(bytes)
            val result = pdRun(root)
            val text = result.files.getValue("project/cn.ttrm")
            text shouldContain "def relation_type BELONGS_TO__"
            result.report.warnings.joinToString() shouldContain "BELONGS_TO"
            result.files.values.forEach { it shouldNotContain "grounds:" }
            pdRun(root).outputs() shouldBe result.outputs()
            val out = root.resolveSibling("strict-model")
            result.outputs().forEach { (path, text) ->
                out.resolve(path).also { it.parent.createDirectories() }.writeText(text)
            }
            val loaded =
                org.tatrman.ttr.metadata
                    .MetadataLoader(
                        listOf(
                            org.tatrman.ttr.metadata.source
                                .BuiltinStockSource(),
                            org.tatrman.ttr.metadata.source.FileBasedSource(
                                "public-pd",
                                100,
                                org.tatrman.ttr.metadata.source
                                    .LocalFsStorage("public-pd", out),
                            ),
                        ),
                    ).load(
                        org.tatrman.ttr.metadata
                            .LoadOptions(strict = true),
                    )
            loaded.errors shouldBe emptyList()
            loaded.report?.ok shouldBe true
        }
        test("C5 extended SQL spellings keep semantic sizes only on text and decimal") {
            data class Case(
                val raw: String,
                val type: String,
                val length: Int? = null,
                val precision: Int? = null,
            )
            val cases =
                listOf(
                    Case("double precision", "float"),
                    Case("timestamp with time zone", "datetime"),
                    Case("TIMESTAMP(6) WITH TIME ZONE", "datetime"),
                    Case("VARCHAR2(80 CHAR)", "text", 80),
                    Case("NUMBER(*,0)", "decimal", precision = 0),
                    Case("datetime2(7)", "datetime"),
                    Case("INT(11)", "int"),
                    Case("DECIMAL(10, 2)", "decimal", 10, 2),
                )
            val root = pdDir()
            for (kind in listOf("PDM", "LDM")) {
                val member = if (kind == "PDM") "Column" else "EntityAttribute"
                val parent = if (kind == "PDM") "Table" else "Entity"
                val collection = if (kind == "PDM") "Columns" else "Attributes"
                val members =
                    cases
                        .mapIndexed {
                            i,
                            c,
                            ->
                            pdObject(member, "c$i", "c$i", "<a:DataType>${c.raw}</a:DataType>")
                        }.joinToString()
                root.resolve("types.pdm").writeText(
                    pdModel(
                        kind,
                        "typed",
                        "<c:${parent}s>" + pdObject(parent, "t", "Sized", "<c:$collection>$members</c:$collection>") +
                            "</c:${parent}s>",
                    ),
                )
                val text = pdRun(root).files.values.single()
                cases.forEachIndexed { i, c ->
                    val body =
                        text
                            .substringAfter(
                                "def ${if (kind == "PDM") "column" else "attribute"} c$i {",
                            ).substringBefore("},")
                    body shouldContain "type: ${c.type}"
                    if (c.length == null) body shouldNotContain "length:" else body shouldContain "length: ${c.length}"
                    if (c.precision ==
                        null
                    ) {
                        body shouldNotContain "precision:"
                    } else {
                        body shouldContain "precision: ${c.precision}"
                    }
                }
            }
        }
        test("C6 emptied-package lexicons require this input's generated header, including linked paths") {
            val root = pdDir().parent
            val input = root.resolve("input.ttl").also { it.writeText("old") }
            val other = root.resolve("other.ttl").also { it.writeText("other") }
            val out = root.resolve("out").createDirectories()
            val owned =
                org.tatrman.ttr.metadata.export
                    .GeneratedInputs(input, null, "skos/1", "test", out)
            val foreign =
                org.tatrman.ttr.metadata.export
                    .GeneratedInputs(other, null, "skos/1", "test", out)
            val entry =
                org.tatrman.ttr.profile
                    .SourceEntry("glossary.cn.kind.Item", "kind", "", "item", "item", "Item")
            out
                .resolve(
                    "glossary.sources.json",
                ).writeText(
                    org.tatrman.ttr.profile.SourcesJsonWriter
                        .write(owned.header("glossary.sources.json").text, listOf(entry)),
                )

            fun lex(
                locale: String,
                header: String,
            ) {
                out
                    .resolve(
                        "glossary/lexicon.$locale.ttrm",
                    ).also { it.parent.createDirectories() }
                    .writeText(header + "body")
            }
            lex(
                "cs",
                owned
                    .header(
                        "glossary/lexicon.cs.ttrm",
                    ).line(org.tatrman.ttr.metadata.export.GeneratedHeader.Form.TTRM),
            )
            lex(
                "en",
                foreign
                    .header(
                        "glossary/lexicon.en.ttrm",
                    ).line(org.tatrman.ttr.metadata.export.GeneratedHeader.Form.TTRM),
            )
            lex("fr", "// hand-authored\n")
            lex("de", "// generated-from: invalid\n")
            input.writeText("new bytes; the snapshot hash must not be compared")
            val linked = root.resolve("linked-input.ttl")
            Files.createSymbolicLink(linked, input)
            SidecarDelta.of(out, linked, emptyList()).emptiedFiles.getValue("glossary") shouldBe
                listOf("glossary/cn.ttrm", "glossary/lexicon.cs.ttrm")
        }
        test("C7 and C9 zero-pair FK is skipped; missing ends and missing ObjectIDs get one readable entry each") {
            val root = pdDir()
            val missingId = "<o:Column Id=\"bad\"><a:Code>bad</a:Code></o:Column>"
            val cols = pdObject("Column", "pk", "pk") + missingId
            val absent = pdObject("ReferenceJoin", "absent", "absent", pdRef("Object1", "Column", "pk"))
            val noId =
                pdObject(
                    "ReferenceJoin",
                    "noId",
                    "noId",
                    pdRef("Object1", "Column", "pk") + pdRef("Object2", "Column", "bad"),
                )
            root.resolve("bad.pdm").writeText(
                pdModel(
                    "PDM",
                    "db",
                    "<c:Tables>" +
                        pdObject(
                            "Table",
                            "t",
                            "Items",
                            "<c:Columns>$cols</c:Columns>",
                        ) + "</c:Tables>\n<c:References>" +
                        pdObject("Reference", "ref", "broken_fk", "<c:Joins>\n$absent\n$noId\n</c:Joins>") +
                        "</c:References>",
                ),
            )
            val result = pdRun(root)
            result.files.values.single() shouldNotContain "def fk"
            val ignored = result.report.ignored.filter { it.reason?.startsWith("incomplete ReferenceJoin") == true }
            ignored.size shouldBe 2
            ignored[0].reason!! shouldContain "missing Object1 or Object2"
            ignored[1].reason!! shouldContain "no ObjectID"
            ignored[0].line + 1 shouldBe ignored[1].line
            result.report.ignored.count { it.reason?.contains("foreign key skipped") == true } shouldBe 1
            result.report.ignored.any { it.reason == "kind mapping null" } shouldBe false
        }
        test("C8 empty XMI retains the short diagnostic without a StAX prefix") {
            val file = pdDir().resolve("empty.xmi").also { it.writeText("") }
            val e = shouldThrow<ProfileException> { XmiFrontEnd.parse(listOf(file)) }
            e.message!! shouldNotContain "ParseError at"
            e.message!! shouldNotContain "Message:"
            e.location.line shouldBe 1
            e.location.column shouldBe 1
        }

        test("C10 negative provider coordinates are clamped through the PD parse error path") {
            val root = pdDir()
            root.resolve("negative.pdm").writeText(pdModel("PDM", "db", ""))
            val negative =
                object : javax.xml.stream.Location {
                    override fun getLineNumber() = -1

                    override fun getColumnNumber() = -1

                    override fun getCharacterOffset() = -1

                    override fun getPublicId(): String? = null

                    override fun getSystemId(): String? = null
                }
            val e =
                shouldThrow<ProfileException> {
                    PdmFrontEnd.parseReported(listOf(root)) { bytes, label ->
                        object : javax.xml.stream.util.StreamReaderDelegate(XmlInput.reader(bytes, label)) {
                            override fun next(): Int =
                                throw javax.xml.stream.XMLStreamException("provider test failure", negative)
                        }
                    }
                }
            e.location.file shouldBe "negative.pdm"
            e.location.line shouldBe 1
            e.location.column shouldBe 1
            e.message!! shouldContain "provider test failure"
            e.message!! shouldNotContain "ParseError at"
        }
    })
