// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.parser.loader

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.tatrman.ttr.parser.model.LayoutDocument
import org.tatrman.ttr.parser.model.LayoutPoint
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name

/**
 * VL-P2 T2 — the JVM twin of the viewer's `mergeTtr` (`@tatrman/parser/layout`): a `.ttrg` and its optional
 * `.ttrl` sidecar → one layout document (contracts V-3, V-4, V-11, A-VL-2, A-VL-3), held to the SHARED
 * vectors (`packages/package-sdk/schemas/layout-vectors/`, read in place) like the TypeScript reader.
 */
class TtrgLayoutReaderSpec :
    StringSpec({
        // the test working dir is the module dir (packages/kotlin/ttr-parser)
        val vectors = Path.of("../../package-sdk/schemas/layout-vectors")
        val cases = vectors.listDirectoryEntries().filter { it.isDirectory() }.sortedBy { it.name }
        val ttrgCases = cases.filter { Files.exists(it.resolve("layout.ttrg")) }

        fun text(
            case: Path,
            file: String,
        ) = Files.readString(case.resolve(file))

        fun documentOf(o: JsonObject) =
            LayoutDocument(
                name = o["name"]!!.jsonPrimitive.content,
                model = o["model"]!!.jsonPrimitive.content,
                objects = o["objects"]!!.jsonArray.map { it.jsonPrimitive.content },
                positions =
                    o["positions"]?.jsonObject.orEmpty().mapValues { (_, p) ->
                        LayoutPoint(p.jsonObject["x"]!!.jsonPrimitive.double, p.jsonObject["y"]!!.jsonPrimitive.double)
                    },
                skin = o["skin"]?.jsonPrimitive?.content,
                collapsed = o["collapsed"]?.jsonArray.orEmpty().map { it.jsonPrimitive.content },
            )

        fun document(case: Path) = documentOf(Json.parseToJsonElement(text(case, "document.json")).jsonObject)

        fun ok(r: TtrgLayoutRead): TtrgLayoutRead.Ok {
            withClue(r.toString()) { r.shouldBeInstanceOf<TtrgLayoutRead.Ok>() }
            return r as TtrgLayoutRead.Ok
        }

        fun failed(r: TtrgLayoutRead): TtrgLayoutRead.Failed {
            withClue(r.toString()) { r.shouldBeInstanceOf<TtrgLayoutRead.Failed>() }
            return r as TtrgLayoutRead.Failed
        }

        "every vector layout.ttrg reads back as document.json on name, model, objects, positions" {
            ttrgCases shouldHaveSize 11
            for (case in ttrgCases) {
                val want = document(case)
                val r = ok(TtrgLayoutReader.read(text(case, "layout.ttrg"), fileName = "${want.name}.ttrg"))
                withClue(case.name) {
                    r.document shouldBe want.copy(skin = null, collapsed = emptyList())
                    r.positionsFrom shouldBe if (want.positions.isEmpty()) null else PositionsFrom.IN_FILE
                    r.warnings.shouldBeEmpty()
                }
            }
        }

        "sidecar-wins: the pair reads as document.json — the sidecar's positions and skin win" {
            val case = vectors.resolve("sidecar-wins")
            val r = ok(TtrgLayoutReader.read(text(case, "input.ttrg"), text(case, "input.ttrl"), "sidecar_wins.ttrg"))
            r.document shouldBe document(case)
            r.positionsFrom shouldBe PositionsFrom.SIDECAR
            r.warnings.shouldBeEmpty()
        }

        val ttrg =
            """
            graph g {
              model: er,
              objects: [ p.er.entity.a, p.er.entity.b ],
              layout: { nodes: { p.er.entity.a: { x: 1, y: 2 } } }
            }
            """.trimIndent()

        "A-VL-2: a sidecar without `canvas <graph name>` is read from its `canvas main`" {
            val ttrl = "ttrl 1\ncanvas main {\n  mode: manual\n  nodes: { \"p.er.entity.b\": { x: 30, y: 40 } }\n}\n"
            val r = ok(TtrgLayoutReader.read(ttrg, ttrl, "g.ttrg"))
            r.document.positions shouldBe mapOf("p.er.entity.b" to LayoutPoint(30.0, 40.0))
            r.positionsFrom shouldBe PositionsFrom.SIDECAR
        }

        "A-VL-2: the canvas named after the graph wins over `canvas main`" {
            val ttrl =
                "ttrl 1\ncanvas main {\n  nodes: { \"p.er.entity.b\": { x: 30, y: 40 } }\n}\n" +
                    "canvas g {\n  mode: manual\n  nodes: { \"p.er.entity.a\": { x: 5, y: 6 } }\n}\n"
            val r = ok(TtrgLayoutReader.read(ttrg, ttrl, "g.ttrg"))
            r.document.positions shouldBe mapOf("p.er.entity.a" to LayoutPoint(5.0, 6.0))
        }

        "A-VL-3 (a): a sidecar key the graph does not list is dropped with a warning" {
            val ttrl =
                "ttrl 1\ncanvas g {\n  mode: manual\n  nodes: {\n" +
                    "    \"p.er.entity.a\": { x: 7, y: 8 }\n    \"p.er.entity.gone\": { x: 9, y: 9 }\n  }\n}\n"
            val r = ok(TtrgLayoutReader.read(ttrg, ttrl, "g.ttrg"))
            r.document.positions shouldBe mapOf("p.er.entity.a" to LayoutPoint(7.0, 8.0))
            r.positionsFrom shouldBe PositionsFrom.SIDECAR
            r.warnings.map { it.path } shouldBe listOf("ttrl.nodes.p.er.entity.gone")
            r.warnings.single().message shouldContain "does not list"
        }

        "A-VL-3 (b): nodes under a stated `mode: auto` are dropped with a warning; in-file positions + skin stay" {
            val ttrl =
                "ttrl 1\ncanvas g {\n  skin: \"er.crow\"\n  mode: auto\n" +
                    "  nodes: { \"p.er.entity.b\": { x: 3, y: 4 } }\n}\n"
            val r = ok(TtrgLayoutReader.read(ttrg, ttrl, "g.ttrg"))
            r.document.positions shouldBe mapOf("p.er.entity.a" to LayoutPoint(1.0, 2.0))
            r.document.skin shouldBe "er.crow"
            r.positionsFrom shouldBe PositionsFrom.IN_FILE
            r.warnings.single().message shouldContain "mode: auto"
        }

        "a sidecar with no usable canvas leaves the in-file positions and warns" {
            val ttrl = "ttrl 1\ncanvas other {\n  nodes: { \"p.er.entity.b\": { x: 3, y: 4 } }\n}\n"
            val r = ok(TtrgLayoutReader.read(ttrg, ttrl, "g.ttrg"))
            r.positionsFrom shouldBe PositionsFrom.IN_FILE
            r.warnings.single().message shouldContain "no canvas g"
        }

        "a malformed sidecar fails the pair, naming the sidecar" {
            val r = failed(TtrgLayoutReader.read(ttrg, "ttrl 1\ncanvas g { wobble: \"x\" }\n", "g.ttrg"))
            r.errors.single().path shouldBe "ttrl"
            r.errors.single().message shouldContain "unknown canvas property"
        }

        "V-3: an in-file position whose key is not in objects is an error naming the key" {
            val bad =
                "graph g {\n  model: er,\n  objects: [ p.er.entity.a ],\n" +
                    "  layout: { nodes: { p.er.entity.z: { x: 1, y: 2 } } }\n}\n"
            val r = failed(TtrgLayoutReader.read(bad, fileName = "g.ttrg"))
            r.errors.single().path shouldBe "positions.p.er.entity.z"
            r.errors.single().message shouldContain "p.er.entity.z is not in objects"
            r.errors.single().line shouldBe 4
        }

        "a graph without `model:` is an error" {
            val r = failed(TtrgLayoutReader.read("graph g {\n  objects: [ a ]\n}\n"))
            r.errors.single().path shouldBe "model"
        }

        "an unbalanced block is an error" {
            failed(TtrgLayoutReader.read("graph g {\n  model: er,\n  objects: [ a, b \n}\n")).errors shouldHaveSize 1
        }

        "V-4: a file stem other than the name is a warning, never a rename" {
            val r = ok(TtrgLayoutReader.read(ttrg, fileName = "layouts/other.ttrg"))
            r.document.name shouldBe "g"
            r.warnings.single().message shouldBe "file name other.ttrg does not match the layout name g"
        }

        "a duplicate object is kept once, with a warning" {
            val dup = "graph g {\n  model: er,\n  objects: [ p.er.entity.a, p.er.entity.a ]\n}\n"
            val r = ok(TtrgLayoutReader.read(dup, fileName = "g.ttrg"))
            r.document.objects shouldBe listOf("p.er.entity.a")
            r.warnings.single().message shouldBe "duplicate object p.er.entity.a (kept once)"
        }
    })
