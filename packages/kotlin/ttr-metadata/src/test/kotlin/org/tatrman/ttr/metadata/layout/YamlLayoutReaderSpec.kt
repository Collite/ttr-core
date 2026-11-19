// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.metadata.layout

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.serialization.json.*
import org.tatrman.ttr.parser.loader.TtrgLayoutRead
import org.tatrman.ttr.parser.loader.TtrgLayoutReader
import org.tatrman.ttr.parser.model.LayoutDocument
import org.tatrman.ttr.parser.model.LayoutPoint
import org.tatrman.ttr.yaml.CanonicalYamlException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name

class YamlLayoutReaderSpec :
    StringSpec({
        val vectors = Path.of("../../package-sdk/schemas/layout-vectors")
        for (case in vectors.listDirectoryEntries().filter { it.isDirectory() }.sortedBy { it.name }) {
            "published VL vector ${case.name} reads to its document and TTR twin" {
                val expected = Json.parseToJsonElement(Files.readString(case.resolve("document.json"))).jsonObject

                fun strings(key: String) = expected[key]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()
                val document =
                    LayoutDocument(
                        expected.getValue("name").jsonPrimitive.content,
                        expected.getValue("model").jsonPrimitive.content,
                        strings("objects"),
                        expected["positions"]
                            ?.jsonObject
                            ?.mapValues { (_, point) ->
                                LayoutPoint(
                                    point.jsonObject
                                        .getValue("x")
                                        .jsonPrimitive.double,
                                    point.jsonObject
                                        .getValue("y")
                                        .jsonPrimitive.double,
                                )
                            }.orEmpty(),
                        expected["skin"]?.jsonPrimitive?.content,
                        strings("collapsed"),
                    )
                val yaml = YamlLayoutReader.read(Files.readString(case.resolve("layout.yaml")))
                yaml shouldBe document
                if (Files.exists(case.resolve("layout.ttrg"))) {
                    val twin = TtrgLayoutReader.read(Files.readString(case.resolve("layout.ttrg"))) as TtrgLayoutRead.Ok
                    yaml.copy(skin = null, collapsed = emptyList()) shouldBe twin.document
                }
            }
        }
        val valid = "layout: 1\nname: invented\nmodel: er\nobjects: [demo.er.entity.widget]\n"
        "flow style and hexadecimal coordinates use the same located YAML values as validation" {
            val input =
                "{objects: [demo.er.entity.widget], model: er, layout: 1, name: invented, " +
                    "positions: {demo.er.entity.widget: {x: 0x20, y: -1.5}}}"
            YamlLayoutReader.read(input).positions shouldBe mapOf("demo.er.entity.widget" to LayoutPoint(32.0, -1.0))
        }
        val invalid =
            listOf(
                valid + "title: unsupported\n" to "title",
                valid.replace("layout: 1", "layout: 2") to "newer than this reader",
                valid.replace("name: invented", "name: bad-name") to "pattern",
                valid.replace("model: er", "model: xyz") to "enum",
                valid.replace("objects: [demo.er.entity.widget]", "objects: [1]") to "string",
                valid + "positions: {absent: {x: 1, y: 2}}\n" to "not in objects",
                valid + "positions: {demo.er.entity.widget: {x: 1, y: 2, z: 3}}\n" to "z",
                valid + "positions: {demo.er.entity.widget: {x: 1}}\n" to "y",
                valid + "name: duplicate\n" to "Duplicate",
                valid + "positions: &p {}\n" to "Anchors",
                valid + "---\n{}\n" to "document",
                valid + "positions: {demo.er.entity.widget: {x: .inf, y: 2}}\n" to "finite",
                valid + "positions: {demo.er.entity.widget: {x: 1e1000, y: 2}}\n" to "finite",
            )
        invalid.forEachIndexed { i, (input, message) ->
            "invalid layout $i is refused with a located diagnostic" {
                val e = shouldThrow<CanonicalYamlException> { YamlLayoutReader.read(input, "layouts/invented.yaml") }
                e.file shouldBe "layouts/invented.yaml"
                (e.line > 0 && e.column > 0) shouldBe true
                e.detail.lowercase() shouldContain message.lowercase()
            }
        }
        "an unknown key reports its exact authored key span" {
            val e =
                shouldThrow<CanonicalYamlException> { YamlLayoutReader.read(valid + "title: unsupported\n", "x.yaml") }
            e.line shouldBe 5
            e.column shouldBe 1
            e.keyPath shouldBe "/title"
        }
    })
