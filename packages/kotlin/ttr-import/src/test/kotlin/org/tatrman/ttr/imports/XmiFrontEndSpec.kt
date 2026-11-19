// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.imports

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.tatrman.ttr.profile.ForeignTree
import org.tatrman.ttr.profile.JsonPointer
import org.tatrman.ttr.profile.ProfileException
import java.nio.file.Files
import java.nio.file.Path

/** MW-P5 S5.3 T2 — the `xmi` front-end on small hand-written documents. */
class XmiFrontEndSpec :
    FunSpec({
        val head =
            """<?xml version="1.0" encoding="UTF-8"?>
<xmi:XMI xmi:version="2.1" xmlns:xmi="http://schema.omg.org/spec/XMI/2.1" xmlns:uml="http://schema.omg.org/spec/UML/2.1" xmlns:p="urn:p">
"""

        fun tree(text: String): JsonObject =
            Json.parseToJsonElement(XmiFrontEnd.tree(text.toByteArray(), "m.xmi")).jsonObject

        fun refused(bytes: ByteArray): ProfileException =
            shouldThrow<ProfileException> { XmiFrontEnd.tree(bytes, "m.xmi") }.also {
                it.code shouldBe
                    "ys/profile-invalid"
            }

        val model =
            head +
                """  <uml:Model xmi:id="M" name="world">
    <packagedElement xmi:type="uml:Package" xmi:id="P1" name="db.retail">
      <packagedElement xmi:type="uml:Package" xmi:id="P2" name="sub">
        <packagedElement xmi:type="uml:Class" xmi:id="A" name="Alpha">
          <ownedAttribute xmi:type="uml:Property" xmi:id="A1" name="x" type="T"/>
        </packagedElement>
      </packagedElement>
      <packagedElement xmi:type="uml:Class" xmi:id="B" name="Beta">
        <generalization xmi:type="uml:Generalization" xmi:id="G" general="A"/>
      </packagedElement>
      <packagedElement xmi:type="uml:Association" xmi:id="R" name="rel">
        <memberEnd xmi:idref="E1"/>
        <memberEnd xmi:idref="E2"/>
        <ownedEnd xmi:type="uml:Property" xmi:id="E1" name="beta" type="B" association="R"/>
        <ownedEnd xmi:type="uml:Property" xmi:id="E2" name="alpha" type="A" association="R"/>
      </packagedElement>
      <packagedElement xmi:type="uml:PrimitiveType" xmi:id="T" name="int"/>
    </packagedElement>
  </uml:Model>
  <p:thing xmi:id="S1" base_Class="A" colour="red" empty=""/>
  <p:link xmi:id="S2" base_Association="R" label="has"/>
  <xmi:Extension extender="Enterprise Architect" extenderID="6.5">
    <elements>
      <element xmi:idref="A" name="Alpha">
        <properties documentation="" stereotype="thing" alias="AL"/>
        <tags><tag name="colour" value="blue"/><tag name="size" value="L"/><tag name="none" value=""/></tags>
        <style appearance="BackColor=-1"/>
      </element>
    </elements>
    <connectors>
      <connector xmi:idref="R"><source xmi:idref="B"/><target xmi:idref="A"/></connector>
    </connectors>
  </xmi:Extension>
</xmi:XMI>
"""

        test("packaged elements by xmi:id with their package path; stereotypes, tags and EA properties folded in") {
            val elements = tree(model).getValue("elements").jsonObject
            elements.keys.toList() shouldBe listOf("A", "B", "R", "T")
            val alpha = elements.getValue("A").jsonObject
            alpha
                .getValue("@xmi")
                .jsonObject
                .getValue("package")
                .jsonPrimitive.content shouldBe "db.retail/sub"
            alpha.getValue("@stereotypes").toString() shouldBe """["thing"]"""
            // The application's value wins over EA's tag; an empty value is no value.
            alpha.getValue("@tags").toString() shouldBe """{"colour":"red","size":"L"}"""
            alpha.getValue("@properties").toString() shouldBe """{"stereotype":"thing","alias":"AL"}"""
            alpha.toString() shouldNotContain "BackColor"
            // References carry the target's name; an xmi:idref child is a $ref node.
            alpha
                .getValue("ownedAttribute")
                .jsonArray[0]
                .jsonObject
                .getValue("type")
                .toString() shouldBe
                """{"${'$'}ref":"T","@name":"int"}"""
            elements
                .getValue("B")
                .jsonObject
                .getValue("generalization")
                .jsonArray[0]
                .jsonObject
                .getValue("general")
                .toString() shouldBe """{"${'$'}ref":"A","@name":"Alpha"}"""
            elements
                .getValue("R")
                .jsonObject
                .getValue("memberEnd")
                .toString() shouldBe
                """[{"${'$'}ref":"E1","@name":"beta"},{"${'$'}ref":"E2","@name":"alpha"}]"""
            // An association is listed on its source (EA's connector source) under its first stereotype.
            elements
                .getValue("B")
                .jsonObject
                .getValue("@outgoing")
                .toString() shouldBe
                """{"link":[{"${'$'}ref":"R","@name":"rel","@tags":{"label":"has"},"@target":{"${'$'}ref":"A","@name":"Alpha"}}]}"""
            tree(model) shouldBe tree(model)
        }

        test("each element's object starts on its XML line, so a diagnostic names the line the author sees") {
            val read = ForeignTree.read(XmiFrontEnd.tree(model.toByteArray(), "m.xmi"), "m.xmi")
            JsonPointer("/elements/A").resolve(read.root)!!.span().line shouldBe 6
            JsonPointer("/elements/A/ownedAttribute/0").resolve(read.root)!!.span().line shouldBe 7
            JsonPointer("/elements/B/generalization/0").resolve(read.root)!!.span().line shouldBe 11
            JsonPointer("/elements/T").resolve(read.root)!!.span().line shouldBe 19
        }

        test("the declared encoding is honoured (EA writes windows-1252)") {
            val text = model.replace("UTF-8", "windows-1252").replace("name=\"Beta\"", "name=\"Café\"")
            val tree = Json.parseToJsonElement(XmiFrontEnd.tree(text.toByteArray(charset("windows-1252")), "m.xmi"))
            tree.jsonObject
                .getValue(
                    "elements",
                ).jsonObject
                .getValue("B")
                .jsonObject
                .getValue("name")
                .jsonPrimitive.content shouldBe
                "Café"
        }

        test("no DTD and no external entities: a document that needs one is refused, nothing is read") {
            val secret = Files.createTempFile("s53-secret", ".txt").also { Files.writeString(it, "TOP-SECRET") }
            val xxe =
                """<?xml version="1.0"?>
<!DOCTYPE x [<!ENTITY e SYSTEM "${secret.toUri()}">]>
<xmi:XMI xmlns:xmi="http://schema.omg.org/spec/XMI/2.1"><uml:Model xmlns:uml="u" name="m">
<packagedElement xmi:type="uml:Class" xmi:id="C" name="c">&e;</packagedElement></uml:Model></xmi:XMI>
"""
            val e = refused(xxe.toByteArray())
            e.message.orEmpty() shouldNotContain "TOP-SECRET"
        }

        test("malformed XML, a root that is not xmi:XMI and an xmi:id declared twice are located errors") {
            refused((head + "  <uml:Model name=\"m\">\n").toByteArray()).location.line shouldBe 4
            refused("<?xml version=\"1.0\"?>\n<model/>\n".toByteArray()).message.orEmpty() shouldContain "not xmi:XMI"
            val twice =
                head +
                    "  <uml:Model xmi:id=\"M\">\n" +
                    "    <packagedElement xmi:type=\"uml:Class\" xmi:id=\"M\" name=\"x\"/>\n" +
                    "  </uml:Model>\n</xmi:XMI>\n"
            refused(twice.toByteArray()).also {
                it.message.orEmpty() shouldContain "xmi:id M is declared twice"
                it.location.line shouldBe 4
            }
        }

        // ---- review-372 ----

        val erd =
            String(
                Files.readAllBytes(
                    Path
                        .of(
                            "../../../tests/conformance/readers/xmi/sources/orders-erd.xmi",
                        ).toAbsolutePath()
                        .normalize(),
                ),
                charset("windows-1252"),
            )

        fun erdTree(text: String): JsonObject =
            Json
                .parseToJsonElement(
                    XmiFrontEnd.tree(text.toByteArray(charset("windows-1252")), "orders-erd.xmi"),
                ).jsonObject

        test("F1 ✅R372-B: an association EA's extension does not cover has no ends and is on no @outgoing") {
            // The hand-written ends carry type="…" attributes: no fallback reads them (EA writes the destination first).
            val bare = tree(model.replace(Regex("(?s)\\s*<connectors>.*</connectors>"), ""))
            val elements = bare.getValue("elements").jsonObject
            val rel = elements.getValue("R").jsonObject
            rel.containsKey("@source") shouldBe false
            rel.containsKey("@target") shouldBe false
            elements.values.none { it.jsonObject.containsKey("@outgoing") } shouldBe true
            elements.getValue("B").jsonObject.containsKey("@outgoing") shouldBe false
            // EA's own export without its extension: the same.
            val erdElements =
                erdTree(
                    erd.replace(Regex("(?s)\\s*<xmi:Extension.*</xmi:Extension>"), ""),
                ).getValue("elements").jsonObject
            erdElements.values.none {
                it.jsonObject.containsKey(
                    "@outgoing",
                ) ||
                    it.jsonObject.containsKey("@source")
            } shouldBe
                true
            // With the extension, EA's connector ends are read as before.
            tree(model)
                .getValue("elements")
                .jsonObject
                .getValue("R")
                .jsonObject
                .getValue("@source")
                .toString() shouldBe
                """{"${'$'}ref":"B","@name":"Beta"}"""
        }

        test("F2: a name written both as an attribute and as a child element is a located error, not a duplicate key") {
            val both =
                model.replace(
                    """<ownedAttribute xmi:type="uml:Property" xmi:id="A1" name="x" type="T"/>""",
                    """<ownedAttribute xmi:type="uml:Property" xmi:id="A1" name="x" type="T"><type xmi:idref="T"/></ownedAttribute>""",
                )
            refused(both.toByteArray()).also {
                it.message.orEmpty() shouldContain "type is written both as an attribute and as a child"
                it.location.line shouldBe 7
            }
        }

        test(
            "F4: a packagedElement without xmi:id, or an href proxy, is a located error; an EA end without idref is no end",
        ) {
            val proxy =
                model.replace(
                    """      <packagedElement xmi:type="uml:PrimitiveType" xmi:id="T" name="int"/>""",
                    """      <packagedElement xmi:type="uml:PrimitiveType" xmi:id="T" name="int"/>
      <packagedElement xmi:type="uml:Class" href="other.xmi#EAID_X"/>""",
                )
            refused(proxy.toByteArray()).also {
                it.message.orEmpty() shouldContain "cross-file references (href) are not read"
                it.location.line shouldBe 20
                it.location.column shouldBe 70
            }
            val anonymous = proxy.replace(""" href="other.xmi#EAID_X"""", """ name="Gamma"""")
            refused(anonymous.toByteArray()).also {
                it.message.orEmpty() shouldContain "packagedElement has no xmi:id"
                it.location.line shouldBe 20
            }
            val endless = erd.replace(Regex("<source xmi:idref=\"[^\"]+\">"), "<source>")
            val placed =
                erdTree(endless)
                    .getValue("elements")
                    .jsonObject
                    .getValue("EAID_5A4D7B6E_BC8F_4E5A_8B6C_7D8E9FA0B105")
                    .jsonObject
            placed.containsKey("@source") shouldBe false
            placed.containsKey("@target") shouldBe true
        }

        test("F7: a stereotype application wins over EA's tag wherever xmi:Extension sits among the root's children") {
            val applied =
                model
                    .replace(
                        """<p:thing xmi:id="S1" base_Class="A" colour="red" empty=""/>""",
                        """<p:thing xmi:id="S1" base_Class="A" colour="" empty=""/>""",
                    )
            val early =
                applied.replace(Regex("(?s)(  <p:thing.*?)(  <xmi:Extension.*</xmi:Extension>\n)"), "$2$1")
            (early != applied) shouldBe true
            val usual =
                tree(applied)
                    .getValue("elements")
                    .jsonObject
                    .getValue("A")
                    .jsonObject
            usual.getValue("@tags").toString() shouldBe """{"size":"L"}"""
            tree(early).getValue("elements") shouldBe tree(applied).getValue("elements")
        }

        test("review-378 F2: a 2000-package input is parsed once; every package tree is a view of that one parse") {
            val packages =
                (1..2000).joinToString("\n") {
                    """    <packagedElement xmi:type="uml:Package" xmi:id="P$it" name="p$it">""" +
                        """<packagedElement xmi:type="uml:Class" xmi:id="C$it" name="C$it"/></packagedElement>"""
                }
            val input =
                java.nio.file.Files.createTempDirectory("s53a-f2").resolve("many.xmi").also {
                    it.toFile().writeText(
                        head + "  <uml:Model xmi:id=\"M\" name=\"world\">\n$packages\n  </uml:Model>\n</xmi:XMI>\n",
                    )
                }
            val trees = XmiFrontEnd.parse(listOf(input))
            trees.size shouldBe 2000
            trees.all { it.sharesInput(trees.first()) } shouldBe true
            trees.last().file shouldBe "many/p2000"
            JsonPointer("/elements/C2000").resolve(trees.last().root)!!.span().line shouldBe 2003
            JsonPointer("/elements/C1").resolve(trees.last().root) shouldBe null
        }

        test(
            "review-378 F5: a UML package above an element with an empty name or one containing '/' is a located error",
        ) {
            listOf("", "db/retail").forEach { name ->
                refused(model.replace("""name="db.retail"""", """name="$name"""").toByteArray()).also {
                    it.message.orEmpty() shouldContain "uml:Package P1 has no usable name (empty or containing '/')"
                    it.location.line shouldBe 4
                }
            }
        }

        test("S5.3a: one tree per UML package that holds an element, model.package its path, its own elements only") {
            val source = Path.of("../../../tests/conformance/readers/xmi/sources/sales-billing.xmi").toAbsolutePath()
            val trees = XmiFrontEnd.parse(listOf(source))
            trees.map { it.file to it.label } shouldBe
                listOf(
                    "sales-billing/sales" to "sales-billing.xmi",
                    "sales-billing/sales/billing" to "sales-billing.xmi",
                )
            val names =
                trees.map { tree ->
                    val root = tree.root.value.jsonObject
                    root
                        .getValue("model")
                        .jsonObject["package"]
                        ?.jsonPrimitive
                        ?.content to
                        root.getValue("elements").jsonObject.values.map {
                            it.jsonObject
                                .getValue("name")
                                .jsonPrimitive.content
                        }
                }
            names shouldBe
                listOf(
                    "sales" to listOf("Customer", "Order", "placed_by"),
                    "sales/billing" to listOf("Invoice", "billed_to"),
                )
            // A cross-package reference still names its target, and an element keeps its XML line.
            val billed =
                trees[1]
                    .root.value.jsonObject
                    .getValue("elements")
                    .jsonObject.values
                    .last()
                    .jsonObject
            billed
                .getValue("@target")
                .jsonObject
                .getValue("@name")
                .jsonPrimitive.content shouldBe "Customer"
            JsonPointer("/elements/EAID_7B1C2D3E_4F5A_4B6C_8D7E_9F0A1B2C3D13")
                .resolve(trees[1].root)!!
                .span()
                .line shouldBe 31
            // Package paths in document order, a dotted name one segment; a document with no element is one tree.
            XmiFrontEnd.trees(model.toByteArray(), "m.xmi").map { it.first } shouldBe
                listOf("db.retail/sub", "db.retail")
            XmiFrontEnd
                .trees((head + "  <uml:Model xmi:id=\"M\" name=\"w\"/>\n</xmi:XMI>\n").toByteArray(), "m.xmi")
                .map { it.first } shouldBe listOf(null)
        }
    })
