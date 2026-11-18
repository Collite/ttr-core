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
            // An association is listed on its source (the first end's type) under its first stereotype.
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
    })
