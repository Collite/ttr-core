// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.writer

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveAtLeastSize
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.tatrman.ttr.parser.loader.TtrId
import org.tatrman.ttr.parser.model.LayoutDocument
import org.tatrman.ttr.parser.model.LayoutPoint
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name

/**
 * VL-P0 T8 — `TtrgLayoutWriter` reproduces every shared vector's `layout.ttrg` byte for byte
 * (`packages/package-sdk/schemas/layout-vectors/`, read in place — a vector changed on one side only
 * fails here or in the viewer's `vectors.test.ts`), and the canonical form it applies (V-5).
 */
class TtrgLayoutWriterSpec :
    StringSpec({
        // the test working dir is the module dir (packages/kotlin/ttr-writer)
        val vectors = Path.of("../../package-sdk/schemas/layout-vectors")
        val cases = vectors.listDirectoryEntries().filter { it.isDirectory() }.sortedBy { it.name }

        "the shared vectors are there (≥ 8 cases)" {
            cases shouldHaveAtLeastSize 8
        }

        "write(document.json) == layout.ttrg, byte for byte, for every vector" {
            for (case in cases) {
                val doc =
                    layoutDocumentOf(
                        Json.parseToJsonElement(Files.readString(case.resolve("document.json"))).jsonObject,
                    )
                withClue(
                    case.name,
                ) { TtrgLayoutWriter.write(doc) shouldBe Files.readString(case.resolve("layout.ttrg")) }
            }
        }

        "write canonicalizes: sorted alphabetically, de-duplicated, coordinates rounded halves up" {
            val messy =
                LayoutDocument(
                    name = "g",
                    model = "er",
                    objects = listOf("z", "č", "a", "z"),
                    positions = mapOf("č" to LayoutPoint(319.5, -319.5), "a" to LayoutPoint(-0.4, 10.49)),
                    skin = "er.crow",
                    collapsed = listOf("z"),
                )
            TtrgLayoutWriter.write(messy) shouldBe
                listOf(
                    "graph g {",
                    "  model: er,",
                    "  objects: [",
                    "    a,",
                    "    č,",
                    "    z",
                    "  ],",
                    "  layout: {",
                    "    nodes: {",
                    "      a: { x: 0, y: 10 },",
                    "      č: { x: 320, y: -319 }",
                    "    }",
                    "  }",
                    "}",
                    "",
                ).joinToString("\n")
        }

        "the alphabetical order matches the viewer's compareAlphabetical (A-VL-1)" {
            val order = LayoutDocument.ALPHABETICAL_ORDER
            listOf("zásoby", "zákazník", "čas", "daň", "adresa").sortedWith(order) shouldBe
                listOf("adresa", "čas", "daň", "zákazník", "zásoby")
            listOf("prodejna", "příjmové_pásmo").sortedWith(order) shouldBe listOf("příjmové_pásmo", "prodejna")
            listOf("item", "Item", "item_class", "Zeta", "alpha").sortedWith(order) shouldBe
                listOf("alpha", "Item", "item", "item_class", "Zeta")
            listOf("č", "c", "d").sortedWith(order) shouldBe listOf("c", "č", "d")
            listOf("cz", "čb").sortedWith(order) shouldBe listOf("čb", "cz")
            LayoutDocument.alphabeticalKey("C\u030Cas") shouldBe "cas"
        }

        "the code-point tie-break is not the UTF-16 code-unit order" {
            listOf("\uD83D\uDE00", "\uFF5E").sortedWith(LayoutDocument.CODE_POINT_ORDER) shouldBe
                listOf("\uFF5E", "\uD83D\uDE00")
        }

        "TtrId agrees with the viewer's isTtrId on the same examples (V-12)" {
            for (s in listOf(
                "item",
                "zákazník",
                "a.b.c",
                "er.entity.item",
                "db.dbo.table.item",
                "graph",
                "layout",
                "canvas",
                "_x9",
            )) {
                withClue(s) { TtrId.isId(s) shouldBe true }
            }
            for (s in listOf(
                "",
                "a..b",
                ".a",
                "a.",
                "9a",
                "a b",
                "a-b",
                "true",
                "x.null",
                "text",
                "description",
                "Ωmega",
                "a//b",
            )) {
                withClue(s) { TtrId.isId(s) shouldBe false }
            }
            TtrId.token("Customer Address") shouldBe "\"Customer Address\""
            TtrId.token("a.\"b\"\\c") shouldBe "\"a.\\\"b\\\"\\\\c\""
        }
    })

/** `document.json` → [LayoutDocument] (the vectors' JSON shape; `skin` / `collapsed` only when present). */
internal fun layoutDocumentOf(o: JsonObject): LayoutDocument =
    LayoutDocument(
        name = o["name"]!!.jsonPrimitive.content,
        model = o["model"]!!.jsonPrimitive.content,
        objects = o["objects"]!!.jsonArray.map { it.jsonPrimitive.content },
        positions =
            o["positions"]!!.jsonObject.mapValues { (_, p) ->
                LayoutPoint(p.jsonObject["x"]!!.jsonPrimitive.double, p.jsonObject["y"]!!.jsonPrimitive.double)
            },
        skin = o["skin"]?.jsonPrimitive?.content,
        collapsed = o["collapsed"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList(),
    )
