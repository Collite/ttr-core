// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.imports

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import org.tatrman.ttr.profile.JsonPointer
import org.tatrman.ttr.profile.ProfileException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText

/** MW-P5 S5.2 T2 — the `csv` front-end: header row → keys, one mapping per row, located errors. */
class CsvFrontEndSpec :
    FunSpec({
        fun input(
            text: String,
            name: String = "terms.csv",
        ): Path = Files.createTempDirectory("s52-csv").resolve(name).also { it.writeText(text) }

        fun json(text: String): JsonElement = Json.parseToJsonElement(text)

        test("rows become mappings keyed by the header, in header order, every cell a string as written") {
            val csv =
                "name,description,status\r\n" +
                    "customer,\"A party that buys, or may buy\",Approved\r\n" +
                    "order,\"Says \"\"yes\"\"\nacross two lines\",Draft\r\n" +
                    "  padded , 42,\r\n"
            val tree = CsvFrontEnd.parse(listOf(input(csv))).single()
            tree.label shouldBe "terms.csv"
            tree.root.value shouldBe
                json(
                    """
                    {"rows": [
                      {"name": "customer", "description": "A party that buys, or may buy", "status": "Approved"},
                      {"name": "order", "description": "Says \"yes\"\nacross two lines", "status": "Draft"},
                      {"name": "  padded ", "description": " 42"}
                    ]}
                    """,
                )
            (requireNotNull(JsonPointer("/rows/0").resolve(tree.root)).value as JsonObject).keys.toList() shouldBe
                listOf("name", "description", "status")
        }

        test("each row's span names the CSV line its record starts on; empty lines are no rows") {
            val csv = "name,label\n\na,A\nb,\"two\nlines\"\nc,C"
            val root = CsvFrontEnd.parse(listOf(input(csv))).single().root
            listOf(0, 1, 2).map { requireNotNull(JsonPointer("/rows/$it").resolve(root)).span().line } shouldBe
                listOf(3, 4, 6)
            requireNotNull(JsonPointer("/rows/1/label").resolve(root)).value shouldBe
                kotlinx.serialization.json.JsonPrimitive("two\nlines")
        }

        test("a header alone, or an empty file, is a tree with no rows") {
            CsvFrontEnd
                .parse(listOf(input("name,label\n")))
                .single()
                .root.value shouldBe json("{\"rows\": []}")
            CsvFrontEnd
                .parse(listOf(input("")))
                .single()
                .root.value shouldBe json("{\"rows\": []}")
        }

        test("malformed CSV is ys/profile-invalid at its line and column, labelled by the file name") {
            fun refused(csv: String) = shouldThrow<ProfileException> { CsvFrontEnd.parse(listOf(input(csv))) }

            refused("name,label\na,\"open\nb,B\n").let {
                it.code shouldBe "ys/profile-invalid"
                it.location.file shouldBe "terms.csv"
                (it.location.line to it.location.column) shouldBe (2 to 3)
                it.detail shouldContain "Unterminated"
            }
            refused("name,label\na,A\nb,B,extra\n").let {
                it.location.line shouldBe 3
                it.detail shouldContain "3 cells; the header names 2 columns"
            }
            refused("name,label\na,A\"x\n").let {
                (it.location.line to it.location.column) shouldBe (2 to 4)
                it.detail shouldContain "quote inside an unquoted cell"
            }
            refused("name,label\na,\"A\"x\n").let {
                (it.location.line to it.location.column) shouldBe (2 to 6)
                it.detail shouldContain "after a closing quote"
            }
            refused("name,name\n").detail shouldContain "column name twice"
            refused("name,,label\n").detail shouldContain "column 2 has no name"
        }

        test("two inputs with one file name are refused, as for the json front-end") {
            val a = input("name\nx\n")
            val b = input("name\ny\n")
            shouldThrow<ReaderInputException> { CsvFrontEnd.parse(listOf(a, b)) }.message shouldContain
                "two inputs are named terms.csv"
        }
    })
