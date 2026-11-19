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
private fun pdObject(tag: String, key: String, code: String, body: String = "") =
    """<o:$tag Id="$key"><a:ObjectID>${pdId(key)}</a:ObjectID><a:Code>$code</a:Code>$body</o:$tag>"""
private fun pdModel(kind: String, code: String, body: String, key: String = "model_$code") = """
    <?xml version="1.0"?>
    <?PowerDesigner signature="${kind}_DATA_MODEL_XML" version="16.7"?>
    <Model xmlns:a="attribute" xmlns:c="collection" xmlns:o="object">
    ${pdObject("Model", key, code, body)}</Model>
    """.trimIndent().trimStart()
private fun pdRef(collection: String, tag: String, key: String) = """<c:$collection><o:$tag Ref="$key"/></c:$collection>"""
private fun pdRun(input: Path) = Reader.run(PdmFrontEnd, ImportProfile.resolve("pdm/16"),
    ReaderRequest(listOf(input), input.resolveSibling("model"), "0.0.1-LOCAL"))
private fun pdDir() = Files.createTempDirectory("s54c").resolve("sources").also { it.createDirectories() }

class PdmRealFilesSpec : FunSpec({
    test("C3 all three overlapping input shapes refuse duplicate labels and name both absolute files") {
        val root = pdDir()
        val a = root.resolve("dev").createDirectories().resolve("sales.pdm")
        val b = root.resolve("prod").createDirectories().resolve("sales.pdm")
        a.writeText(pdModel("PDM", "dev", ""))
        b.writeText(pdModel("PDM", "prod", ""))
        for (inputs in listOf(listOf(a,b), listOf(a.parent,b.parent), listOf(a,b.parent))) {
            val e = shouldThrow<ReaderInputException> { PdmFrontEnd.parse(inputs) }
            e.message!! shouldContain "two inputs are labelled sales.pdm"
            e.message!! shouldContain a.toAbsolutePath().toString()
            e.message!! shouldContain b.toAbsolutePath().toString()
        }
        PdmFrontEnd.parse(listOf(root)).map { it.label }.toSet() shouldBe setOf("dev/sales.pdm", "prod/sales.pdm")
    }
    test("C1 generation from a relationship with an external shortcut does not abort either input order") {
        val root = pdDir()
        val sourceBody = "<c:Entities>" + pdObject("Entity", "local", "local") + "</c:Entities>" +
            "<c:Shortcuts>" + pdObject("Shortcut", "external", "external", "<a:TargetID>${pdId("outside")}</a:TargetID>") + "</c:Shortcuts>" +
            "<c:Relationships>" + pdObject("Relationship", "rel", "rel", pdRef("Object1", "Entity", "local") + pdRef("Object2", "Shortcut", "external")) + "</c:Relationships>"
        val columns = pdObject("Column", "pk", "id") + pdObject("Column", "fk", "parent_id")
        val table = pdObject("Table", "t", "t", "<c:Columns>$columns</c:Columns>")
        val join = pdObject("ReferenceJoin", "j", "j", pdRef("Object1", "Column", "pk") + pdRef("Object2", "Column", "fk"))
        val target = pdObject("Reference", "target", "target", "<a:History>ORG {${pdId("rel")}}</a:History><c:Joins>$join</c:Joins>")
        val origin = pdObject("Shortcut", "origin", "origin", "<a:TargetID>${pdId("ldm")}</a:TargetID>")
        for ((ldm,pdm) in listOf("a.ldm" to "z.pdm", "z.ldm" to "a.pdm")) {
            Files.list(root).use { it.forEach(Files::delete) }
            root.resolve(ldm).writeText(pdModel("LDM", "logical", sourceBody, "ldm"))
            root.resolve(pdm).writeText(pdModel("PDM", "physical", "<c:GenerationOrigins>$origin</c:GenerationOrigins><c:Tables>$table</c:Tables><c:References>$target</c:References>"))
            val result = pdRun(root)
            result.files.getValue("logical/er.ttrm") shouldContain "def relation rel"
            result.files.getValue("physical/binding.ttrm") shouldContain "logical.er.relation.rel"
            result.report.warnings.joinToString() shouldContain "ys/key-missing"
        }
    }
    test("C2 CDM relationship with an external end stays a relation type") {
        val root = pdDir()
        val shortcut = pdObject("Shortcut", "sc", "outside", "<a:TargetID>${pdId("outside")}</a:TargetID>")
        val relation = pdObject("Relationship", "rel", "rel", pdRef("Object1", "Shortcut", "sc"))
        root.resolve("model.cdm").writeText(pdModel("CDM", "cn", "<c:Shortcuts>$shortcut</c:Shortcuts><c:Relationships>$relation</c:Relationships>"))
        pdRun(root).files.getValue("cn/cn.ttrm") shouldContain "def relation_type rel"
    }
    test("C4 case variants coexist across output models but refuse the same output file with both labels") {
        val root = pdDir()
        root.resolve("logical.ldm").writeText(pdModel("LDM", "Billing", "<c:Entities>${pdObject("Entity", "e", "Item")}</c:Entities>"))
        root.resolve("physical.pdm").writeText(pdModel("PDM", "BILLING", "<c:Tables>${pdObject("Table", "t", "Item")}</c:Tables>"))
        pdRun(root).files.keys.toSet() shouldBe setOf("billing/er.ttrm", "billing/db.ttrm")
        root.resolve("other.ldm").writeText(pdModel("LDM", "BILLING", "<c:Entities>${pdObject("Entity", "other", "Other")}</c:Entities>"))
        val e = shouldThrow<ProfileException> { pdRun(root) }
        e.code shouldBe "ys/package-invalid"
        e.message!! shouldContain "logical.ldm"
        e.message!! shouldContain "other.ldm"
    }
    test("duplicate relationship Codes rename by ObjectID order, preserve identity, and warn once") {
        val root = pdDir()
        val sorted = listOf("r1", "r2", "r3").sortedBy(::pdId)
        fun run(order: List<String>): GeneratedPackage {
            root.resolve("model.cdm").writeText(pdModel("CDM", "cn", "<c:Relationships>" + order.joinToString("\n") { pdObject("Relationship", it, "BELONGS_TO") } + "</c:Relationships>"))
            return pdRun(root)
        }
        val a = run(sorted.reversed())
        val text = a.files.getValue("cn/cn.ttrm").substringAfter('\n')
        text shouldContain "def relation_type BELONGS_TO {"
        for (key in sorted.drop(1)) text shouldContain "def relation_type BELONGS_TO__${pdId(key).replace("-", "").take(8)}"
        a.report.warnings.size shouldBe 2
        a.report.warnings.forEach { it shouldContain "model.cdm:"; it shouldContain "BELONGS_TO" }
        a.sidecars.values.single() shouldContain pdId(sorted[1])
        run(sorted).files.getValue("cn/cn.ttrm").substringAfter('\n') shouldBe text
        root.resolve("model.cdm").writeText(pdModel("CDM", "cn", "<c:Relationships>" + pdObject("Relationship", "r1", "DUP") + pdObject("Relationship", "r2", "DUP").replace(pdId("r2"), pdId("r1")) + "</c:Relationships>"))
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
    }
})
