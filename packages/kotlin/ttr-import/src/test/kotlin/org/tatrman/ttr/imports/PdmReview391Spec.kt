// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.imports

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.tatrman.ttr.profile.ProfileException
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText

class PdmReview391Spec :
    FunSpec({
        val fixture = Path.of("../../../tests/conformance/readers/pdm/sources").toAbsolutePath().normalize()

        fun dir() = Files.createTempDirectory("r391").resolve("sources").also { it.createDirectories() }

        fun run(
            dir: Path,
            pkg: String? = null,
        ) = Reader.run(
            PdmFrontEnd,
            ImportProfile.resolve("pdm/16"),
            ReaderRequest(listOf(dir), dir.resolveSibling("model"), "0.0.1-LOCAL", packageName = pkg),
        )

        fun id(key: String) = UUID.nameUUIDFromBytes(key.toByteArray()).toString()

        fun obj(
            tag: String,
            key: String,
            code: String,
            body: String = "",
        ) = """<o:$tag Id="$key"><a:ObjectID>${id(key)}</a:ObjectID><a:Code>$code</a:Code>$body</o:$tag>"""

        fun model(
            code: String,
            body: String,
        ) = """
            <?xml version="1.0"?>
            <?PowerDesigner signature="PDM_DATA_MODEL_XML" version="16.7"?>
            <Model xmlns:a="attribute" xmlns:c="collection" xmlns:o="object">
            ${obj("Model", "model_$code", code, body)}</Model>
            """.trimIndent().trimStart()

        fun column(
            key: String,
            code: String,
            type: String = "int",
        ) = obj("Column", key, code, "<a:DataType>$type</a:DataType>")

        fun table(
            key: String,
            code: String,
            cols: String,
        ) = obj("Table", key, code, "<c:Columns>$cols</c:Columns>")

        fun join(
            key: String,
            parent: String?,
            child: String?,
        ) = obj(
            "ReferenceJoin",
            key,
            key,
            (parent?.let { """<c:Object1><o:Column Ref="$it"/></c:Object1>""" } ?: "") +
                (child?.let { """<c:Object2><o:Column Ref="$it"/></c:Object2>""" } ?: ""),
        )

        fun reference(
            key: String,
            joins: String,
            ends: String = "",
        ) = obj("Reference", key, key, "$ends<c:Joins>$joins</c:Joins>")

        fun secondPdm() =
            Regex("00000000-0000-4000-8000-0000000003([0-9]{2})")
                .replace(
                    fixture.resolve("sales.pdm").readText(),
                ) { "00000000-0000-4000-8000-0000000004${it.groupValues[1]}" }
                .replace("<a:Code>db_sales</a:Code>", "<a:Code>db_other</a:Code>")

        fun store(
            out: Path,
            generated: GeneratedPackage,
        ) = generated.outputs().forEach { (path, text) ->
            out.resolve(path).also { it.parent.createDirectories() }.writeText(text)
        }

        fun loads(generated: GeneratedPackage) {
            val out = Files.createTempDirectory("r391-load")
            store(out, generated)
            val loaded =
                org.tatrman.ttr.metadata
                    .MetadataLoader(
                        listOf(
                            org.tatrman.ttr.metadata.source
                                .BuiltinStockSource(),
                            org.tatrman.ttr.metadata.source.FileBasedSource(
                                "pd",
                                100,
                                org.tatrman.ttr.metadata.source
                                    .LocalFsStorage("pd", out),
                            ),
                        ),
                    ).load(
                        org.tatrman.ttr.metadata
                            .LoadOptions(strict = true),
                    )
            withClue(loaded.issues.joinToString("\n")) {
                loaded.report?.ok shouldBe true
                loaded.errors shouldBe emptyList()
            }
        }

        test("F1 mixed-case Model and Package Codes lower-case without changing definition Codes") {
            val input = dir()
            val file = input.resolve("sales.pdm")
            val original = fixture.resolve("sales.pdm").readText()
            file.writeText(original)
            val lower = run(input).files.mapValues { it.value.substringAfter('\n') }
            file.writeText(
                original
                    .replace("<a:Code>db_sales</a:Code>", "<a:Code>DB_Sales</a:Code>")
                    .replace("<a:Code>audit</a:Code>", "<a:Code>Audit</a:Code>"),
            )
            run(input).files.mapValues { it.value.substringAfter('\n') } shouldBe lower
            file.writeText(
                model(
                    "ER_Sales",
                    "<c:Packages>" +
                        obj(
                            "Package",
                            "billing",
                            "Billing",
                            "<c:Tables>" + table("upper", "LOG", column("c", "ID")) + "</c:Tables>",
                        ) + "</c:Packages>",
                ),
            )
            run(input).files.getValue("er_sales/billing/db.ttrm") shouldContain "def table LOG"
        }
        test("F1 case-fold collisions name both raw paths at the second package") {
            val input = dir()
            val xml =
                model(
                    "db",
                    "<c:Packages>\n" + obj("Package", "a", "Sales") + "\n" +
                        obj("Package", "b", "SALES") + "\n</c:Packages>",
                )
            input.resolve("collision.pdm").writeText(xml)
            val e = shouldThrow<ProfileException> { run(input) }
            e.code shouldBe "ys/package-invalid"
            e.message!! shouldContain "'db/Sales' and 'db/SALES' both map to package 'db/sales'"
            e.location.line shouldBe xml.lineSequence().indexOfFirst { "Id=\"b\"" in it } + 1
        }
        test("F2 two packages with LOG and ORDERS link their own columns by ObjectID") {
            fun pkg(name: String): String =
                obj(
                    "Package",
                    name,
                    name,
                    "<c:Tables>" + table("${name}log", "LOG", column("${name}fk", "order_id")) +
                        table("${name}orders", "ORDERS", column("${name}pk", "id")) + "</c:Tables><c:References>" +
                        reference("${name}ref", join("${name}join", "${name}pk", "${name}fk")) + "</c:References>",
                )
            val input = dir()
            input
                .resolve(
                    "packages.pdm",
                ).writeText(model("db", "<c:Packages>${pkg("sales")}${pkg("audit")}</c:Packages>"))
            val result = run(input)
            loads(result)
            for (pkg in listOf("sales", "audit")) {
                val text = result.files.getValue("db/$pkg/db.ttrm")
                text shouldContain "db.$pkg.db.dbo.LOG.order_id"
                text shouldContain "db.$pkg.db.dbo.ORDERS.id"
            }
        }
        test("F2 one LDM generates two PDMs sharing table Codes without ambiguous bindings") {
            val input = dir()
            input.resolve("sales.ldm").writeText(fixture.resolve("sales.ldm").readText())
            input.resolve("sales.pdm").writeText(fixture.resolve("sales.pdm").readText())
            input.resolve("other.pdm").writeText(secondPdm())
            val result = run(input)
            loads(result)
            for (pkg in listOf("db_sales", "db_other")) {
                val text = result.files.getValue("$pkg/binding.ttrm")
                text shouldContain "$pkg.db.dbo.customers"
                text shouldContain "er_sales.er.entity.Customer"
                Regex("def er2db_").findAll(text).count() shouldBe 8
            }
        }
        test("F3 PD logical and SQL spellings preserve type, length and precision") {
            data class Case(
                val raw: String,
                val type: String,
                val length: Int? = null,
                val precision: Int? = null,
            )
            val cases =
                listOf(
                    Case("MN8,2", "decimal", 8, 2),
                    Case("VARCHAR2(80)", "text", 80),
                    Case("datetime2(7)", "datetime"),
                    Case("INT4", "int"),
                    Case("float8", "float"),
                    Case("BINARY_DOUBLE", "float"),
                    Case("NUMBER(10,2)", "decimal", 10, 2),
                    Case("A12", "text", 12),
                ) +
                    mapOf(
                        "int" to "SI BT NO INT2 SERIAL",
                        "bigint" to "INT8 BIGSERIAL",
                        "decimal" to "DC MN",
                        "float" to "SF LF BINARY_FLOAT FLOAT4 FLOAT8",
                        "datetime" to "TS DATETIME2 SMALLDATETIME",
                        "text" to "LA LVA TXT MBT VMBT BIN LBIN PIC NVARCHAR NCHAR BLOB VARBINARY BYTEA UNKNOWN_TYPE",
                    ).flatMap { (type, names) -> names.split(' ').map { Case(it, type) } }
            val input = dir()
            val cols = cases.mapIndexed { i, c -> column("c$i", "c$i", c.raw) }.joinToString("\n")
            input.resolve("types.pdm").writeText(model("db", "<c:Tables>${table("t", "types", cols)}</c:Tables>"))
            val text = run(input).files.getValue("db/db.ttrm")
            cases.forEachIndexed { i, c ->
                withClue(c.raw) {
                    val clause = text.substringAfter("def column c$i {").substringBefore("},")
                    clause shouldContain "type: ${if (c.length == null) c.type else "{ type: ${c.type}"}"
                    if (c.length != null) clause shouldContain "length: ${c.length}"
                    if (c.precision != null) clause shouldContain "precision: ${c.precision}"
                }
            }
        }
        test("F5 incomplete middle join is skipped whole and reported at the join") {
            val input = dir()
            val cols = listOf("p1", "p3", "c1", "c2", "c3").joinToString("\n") { column(it, it) }
            val joins = join("j1", "p1", "c1") + "\n" + join("broken", null, "c2") + "\n" + join("j3", "p3", "c3")
            val xml =
                model(
                    "db",
                    "<c:Tables>${table("t", "t", cols)}</c:Tables>\n<c:References>" +
                        reference("fk", joins) + "</c:References>",
                )
            input.resolve("joins.pdm").writeText(xml)
            val result = run(input)
            val text =
                result.files
                    .getValue("db/db.ttrm")
                    .substringAfter("def fk fk")
                    .substringBefore("def table")
            text shouldContain "from: [db.db.dbo.t.c1, db.db.dbo.t.c3]"
            text shouldContain "to: [db.db.dbo.t.p1, db.db.dbo.t.p3]"
            text shouldNotContain "t.c2"
            val ignored = result.report.ignored.single { it.reason?.contains("incomplete ReferenceJoin") == true }
            ignored.file shouldBe "joins.pdm"
            ignored.line shouldBe xml.lineSequence().indexOfFirst { "Id=\"broken\"" in it } + 1
        }
        test("F6 shortcut resolves only to supplied models; external FK is skipped and reported") {
            val input = dir()
            val source = model("source", "<c:Tables>${table("parent", "parent", column("pk", "id"))}</c:Tables>")
            val shortcut = obj("Shortcut", "sc", "parent", "<a:TargetID>${id("parent")}</a:TargetID>")
            val child = table("child", "child", column("fkcol", "parent_id"))
            val colShortcut = obj("Shortcut", "pkshortcut", "id", "<a:TargetID>${id("pk")}</a:TargetID>")
            val ends = """<c:ParentTable><o:Shortcut Ref="sc"/></c:ParentTable>"""
            input.resolve("child.pdm").writeText(
                model(
                    "child",
                    "<c:Shortcuts>$shortcut$colShortcut</c:Shortcuts>" +
                        "<c:Tables>$child</c:Tables><c:References>" +
                        reference("fk", join("j", "pkshortcut", "fkcol"), ends) +
                        "</c:References>",
                ),
            )
            input.resolve("parent.pdm").writeText(source)
            run(input).files.getValue("child/db.ttrm") shouldContain "to: [source.db.dbo.parent.id]"
            Files.delete(input.resolve("parent.pdm"))
            val result = run(input)
            result.files.getValue("child/db.ttrm") shouldNotContain "def fk"
            result.report.markdown() shouldContain "shortcut to ${id("parent")} outside the supplied models"
        }
        test("F8 directory-relative labels allow same file names and diagnose duplicate origins by both paths") {
            val input = dir()
            input
                .resolve("dev")
                .createDirectories()
                .resolve("sales.pdm")
                .writeText(fixture.resolve("sales.pdm").readText())
            input
                .resolve("prod")
                .createDirectories()
                .resolve("sales.pdm")
                .writeText(secondPdm())
            val result = run(input)
            result.files.keys.containsAll(listOf("db_sales/db.ttrm", "db_other/db.ttrm")) shouldBe true
            result.report.ignored
                .map { it.file }
                .toSet() shouldBe setOf("dev/sales.pdm", "prod/sales.pdm")
            val trees = PdmFrontEnd.parse(listOf(input))
            trees.map { it.label }.toSet() shouldBe setOf("dev/sales.pdm", "prod/sales.pdm")
            input.resolve("sales.ldm").writeText(fixture.resolve("sales.ldm").readText())
            input
                .resolve(
                    "archive",
                ).createDirectories()
                .resolve("sales_v1.ldm")
                .writeText(fixture.resolve("sales.ldm").readText())
            val e = shouldThrow<ProfileException> { run(input) }
            e.message!! shouldContain "archive/sales_v1.ldm"
            e.message!! shouldContain "sales.ldm"
        }
        test("F9 truncated XML and provider negative positions always produce positive coordinates") {
            val input = dir().resolve("truncated.pdm")
            input.writeText(fixture.resolve("sales.pdm").readText().substringBefore("</o:Column>"))
            val e = shouldThrow<ProfileException> { PdmFrontEnd.parse(listOf(input)) }
            e.location.line shouldBeGreaterThanOrEqual 1
            e.location.column shouldBeGreaterThanOrEqual 1
            XmlInput.span("negative.pdm", -1, -1).line shouldBe 1
            XmlInput.span("negative.pdm", -1, -1).column shouldBe 1
        }
        test("F4 emptied glossary lists existing own lexicons beside a live child package") {
            val root = dir().parent
            val input = root.resolve("sources/trade.ttl")
            input.writeText(Path.of("../../../tests/conformance/readers/glossary/sources/trade.ttl").readText())
            val out = root.resolve("model")

            fun glossary(pkg: String) =
                Reader.run(
                    RdfFrontEnd.TURTLE,
                    ImportProfile.resolve("skos/1"),
                    ReaderRequest(listOf(input), out, "0.0.1-LOCAL", packageName = pkg),
                )
            store(out, glossary("glossary"))
            val child = glossary("glossary.sub")
            store(out, child)
            val text =
                child.report
                    .markdown()
                    .substringAfter("#### Emptied")
                    .substringBefore("#### Removed")
            for (file in listOf("cn.ttrm", "lexicon.cs.ttrm", "lexicon.en.ttrm")) text shouldContain "`glossary/$file`"
            text shouldNotContain "glossary/sub/"
            text shouldNotContain "`glossary/`"
        }
        test("F7 emptied root package lists relative model paths") {
            val input = dir()
            val out = input.resolveSibling("model").also { it.createDirectories() }
            val header =
                org.tatrman.ttr.metadata.export
                    .GeneratedInputs(input, null, "pdm/16", "0.0.1-LOCAL", out)
            val entry =
                org.tatrman.ttr.profile
                    .SourceEntry("er.entity.Item", "entity", "", "item", "item", "Item")
            out.resolve(".sources.json").writeText(
                org.tatrman.ttr.profile.SourcesJsonWriter.write(
                    header.header(".sources.json").text,
                    listOf(entry),
                ),
            )
            val delta = SidecarDelta.of(out, input, emptyList())
            delta.emptied shouldBe listOf("")
            delta.emptiedFiles.getValue("") shouldBe listOf("er.ttrm")
            val report =
                ImportReport(
                    "pdm",
                    "pdm/16",
                    emptyList(),
                    emptyList(),
                    emptyList(),
                    emptied = delta.emptied,
                    emptiedFiles = delta.emptiedFiles,
                )
            report.markdown() shouldContain "`.sources.json` and `er.ttrm`"
            report.markdown() shouldNotContain "`/er.ttrm`"
        }
    })
