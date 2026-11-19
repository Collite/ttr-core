// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.imports

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.*
import org.tatrman.ttr.profile.JsonPointer
import org.tatrman.ttr.profile.ProfileException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.io.path.writeText

class PdmFrontEndSpec :
    FunSpec({
        val fixture = Path.of("../../../tests/conformance/readers/pdm/sources").toAbsolutePath().normalize()
        test("model kinds, XML ids and object refs are foreign data; each package is a view of one parse") {
            val trees = PdmFrontEnd.parse(listOf(fixture))
            trees.map { it.file } shouldBe
                listOf("sales/cn_sales", "sales/er_sales", "sales/db_sales", "sales/db_sales/audit")
            val db = trees.single { it.file == "sales/db_sales" }
            db.sharesInput(trees.last()) shouldBe true
            JsonPointer("/objects/o303/Joins/0/Object2/0/\$ref").resolve(db.root)!!.value shouldBe JsonPrimitive("o322")
            JsonPointer("/objects/o303/Joins/0/Object2/0/@path").resolve(db.root)!!.value shouldBe
                JsonPrimitive("db_sales/orders/customer_id")
            JsonPointer("/objects/o301/@pd/type").resolve(db.root)!!.value shouldBe JsonPrimitive("PDM.Table")
            val line =
                fixture
                    .resolve(
                        "sales.pdm",
                    ).readText()
                    .lineSequence()
                    .indexOfFirst { it.contains("<o:Table Id=\"o301\"") } +
                    1
            JsonPointer("/objects/o301").resolve(db.root)!!.span().line shouldBe line
            val columnLine =
                fixture
                    .resolve("sales.pdm")
                    .readText()
                    .lineSequence()
                    .indexOfFirst { it.contains("<o:Column Id=\"o312\"") } + 1
            JsonPointer("/objects/o301/Columns/1").resolve(db.root)!!.span().line shouldBe columnLine
        }
        test("malformed XML, duplicate local ids, missing version and DTDs are refused without resolving entities") {
            val dir = Files.createTempDirectory("s54-bad-pdm")
            val good = fixture.resolve("sales.pdm").readText()
            for ((text, code) in listOf(
                good.replace("</Model>", "") to "ys/profile-invalid",
                good.replace("Id=\"o301\"", "Id=\"o302\"") to "ys/profile-invalid",
                good.replace(Regex("<\\?PowerDesigner.*?\\?>"), "") to "xm/pdm-version",
                good.replace("<Model ", "<!DOCTYPE Model [<!ENTITY leak SYSTEM 'file:///nonexistent'>]><Model ") to
                    "ys/profile-invalid",
            )) {
                val input = dir.resolve("bad.pdm").also { it.writeText(text) }
                shouldThrow<ProfileException> { PdmFrontEnd.parse(listOf(input)) }.code shouldBe code
            }
        }
        test("an unsupplied generation origin is preserved as an ignored record rather than guessed") {
            val tree = PdmFrontEnd.parse(listOf(fixture.resolve("sales.pdm"))).first()
            tree.root.value.jsonObject
                .getValue(
                    "generationLinks",
                ).jsonObject.values
                .first()
                .jsonObject
                .getValue(
                    "@pd",
                ).jsonObject
                .getValue("type")
                .jsonPrimitive.content shouldBe
                "unknown.Object->PDM.Table"
        }
    })
