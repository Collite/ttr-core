// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.parser.loader

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveAtLeastSize
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.antlr.v4.runtime.BaseErrorListener
import org.antlr.v4.runtime.CharStreams
import org.antlr.v4.runtime.CommonTokenStream
import org.antlr.v4.runtime.RecognitionException
import org.antlr.v4.runtime.Recognizer
import org.tatrman.ttr.parser.generated.TTRLexer
import org.tatrman.ttr.parser.generated.TTRParser
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name

/**
 * The `.ttrg` reader (moved from ttr-designer-server's `TtrgGraphFile` in VL-P0, contracts V-14): its
 * original header/objects tests, and the layout-block reads over the SHARED vectors
 * (`packages/package-sdk/schemas/layout-vectors/`, read in place — never a copy) and the samples.
 */
class TtrgGraphReaderSpec :
    StringSpec({
        // the test working dir is the module dir (packages/kotlin/ttr-parser)
        val vectors = Path.of("../../package-sdk/schemas/layout-vectors")
        val cases = vectors.listDirectoryEntries().filter { it.isDirectory() }.sortedBy { it.name }

        // a case without layout.ttrg has no TTR form (A-VL-5)
        val ttrgCases = cases.filter { Files.exists(it.resolve("layout.ttrg")) }

        fun doc(case: Path) = Json.parseToJsonElement(Files.readString(case.resolve("document.json"))).jsonObject

        // ── relocated from ttr-designer-server's TtrgGraphFileSpec (unchanged) ──

        "parseHeader reads the graph name and model schema" {
            val h = TtrgGraphReader.parseHeader("graph all_er {\n  model: er,\n  objects: []\n}\n")
            h shouldBe TtrgGraphReader.Header(name = "all_er", schema = "er")
        }

        "parseHeader returns null for a non-graph file" {
            TtrgGraphReader.parseHeader("package p\nmodel db schema dbo\n") shouldBe null
        }

        "parseObjects splits and trims the bracket contents" {
            val objs = TtrgGraphReader.parseObjects("graph g {\n  objects: [a.b.c, a.b.d]\n}\n")
            objs shouldBe listOf("a.b.c", "a.b.d")
        }

        "parseObjects returns empty for an empty list" {
            TtrgGraphReader.parseObjects("graph g {\n  objects: []\n}\n") shouldBe emptyList()
        }

        // ── VL-P0: the in-file layout block ──

        "the shared vectors are there (≥ 8 cases)" {
            cases shouldHaveAtLeastSize 8
        }

        "parseLayoutBlock over every vector .ttrg equals document.json positions" {
            for (case in ttrgCases) {
                val expected =
                    doc(case)["positions"]!!.jsonObject.mapValues { (_, p) ->
                        p.jsonObject["x"]!!.jsonPrimitive.double to p.jsonObject["y"]!!.jsonPrimitive.double
                    }
                val actual = TtrgGraphReader.parseLayoutBlock(Files.readString(case.resolve("layout.ttrg")))
                withClue(case.name) { actual shouldBe expected }
            }
        }

        "parseObjectRefs over every vector .ttrg equals document.json objects" {
            for (case in ttrgCases) {
                val expected = doc(case)["objects"]!!.jsonArray.map { it.jsonPrimitive.content }
                withClue(case.name) {
                    TtrgGraphReader.parseObjectRefs(Files.readString(case.resolve("layout.ttrg"))) shouldBe
                        expected
                }
            }
        }

        "every vector .ttrg parses with TTR.g4 — the grammar judges the TTR form (A-VL-5)" {
            val files =
                Files.walk(vectors).use { s -> s.filter { it.name.endsWith(".ttrg") }.sorted().toList() }
            files shouldHaveAtLeastSize ttrgCases.size
            for (f in files) {
                withClue(vectors.relativize(f).toString()) {
                    grammarErrors(Files.readString(f)) shouldBe
                        emptyList()
                }
            }
        }

        "a quoted ref in objects is outside the grammar — why a writer refuses one (A-VL-5)" {
            grammarErrors(
                "graph g {\n  model: er,\n  objects: [\n    \"Customer Address\"\n  ]\n}\n",
            ).isEmpty() shouldBe
                false
            grammarErrors(
                "graph g {\n  model: er,\n  objects: [\n    hartland.er.entity.date\n  ]\n}\n",
            ).isEmpty() shouldBe
                false
        }

        "the sidecar-wins input carries its own in-file positions (the sidecar winning is the caller's merge)" {
            TtrgGraphReader.parseLayoutBlock(Files.readString(vectors.resolve("sidecar-wins/input.ttrg"))) shouldBe
                mapOf("hartland.er.entity.item" to (100.0 to 100.0), "hartland.er.entity.store" to (300.0 to 100.0))
        }

        "the stale-node sample parses: one position, for an object the graph does not list" {
            val text = Files.readString(Path.of("../../../samples/broken/v1.1/graph-layout-stale-node.ttrg"))
            TtrgGraphReader.parseLayoutBlock(text) shouldBe mapOf("er.entity.nonexistent" to (100.0 to 100.0))
            TtrgGraphReader.parseObjectRefs(text).size shouldBe 1
        }

        "no layout block reads as no positions; the samples' all_db has none" {
            TtrgGraphReader.parseLayoutBlock(
                Files.readString(Path.of("../../../samples/v1.1-mini/graphs/all_db.ttrg")),
            ) shouldBe
                emptyMap()
        }

        "bare or quoted keys, integer or decimal, the worker block shape (no commas, a viewport dropped)" {
            val worker =
                listOf(
                    "import hartland",
                    "graph g {",
                    "    model: er",
                    "    objects: [a.b, \"c d\"],",
                    "    layout: {",
                    "    viewport: { zoom: 1.5, panX: 10, panY: 20, displayMode: \"just-names\" }",
                    "    nodes: {",
                    "        a.b: { x: 320.5, y: -180 }",
                    "        \"c d\": { y: 2, x: 1 }",
                    "    }",
                    "    edges: {}",
                    "}",
                    "  }",
                ).joinToString("\n")
            TtrgGraphReader.parseLayoutBlock(worker) shouldBe mapOf("a.b" to (320.5 to -180.0), "c d" to (1.0 to 2.0))
            TtrgGraphReader.parseObjectRefs(worker) shouldBe listOf("a.b", "c d")
        }

        "comments and strings never fake a block" {
            val tricky =
                listOf(
                    "// graph decoy { objects: [nope] }",
                    "graph g {",
                    "  description: \"objects: [not, these] } {\",",
                    "  /* layout: { nodes: { nope: { x: 1, y: 1 } } } */",
                    "  model = er",
                    "  objects = [a.b, \"c.d\",]",
                    "  layout = { nodes = { \"a.b\" = { y = 2, x = 1 } } }",
                    "}",
                ).joinToString("\n")
            val s = TtrgGraphReader.scan(tricky)
            s.errors shouldBe emptyList()
            s.graph!!.objects shouldBe listOf("a.b", "c.d")
            s.graph!!.nodes shouldBe mapOf("a.b" to (1.0 to 2.0))
            s.warnings.map { it.path } shouldBe listOf("description")
        }

        "a malformed block is a TtrgParseException naming the line" {
            val e =
                shouldThrow<TtrgParseException> {
                    TtrgGraphReader.parseLayoutBlock(
                        "graph g {\n  model: er,\n  objects: [a],\n  layout: { nodes: { a: { x: 1 } } }\n}\n",
                    )
                }
            e.issue shouldBe TtrgIssue("positions.a.y", "missing y", 4)
            shouldThrow<TtrgParseException> { TtrgGraphReader.parseLayoutBlock("model er\n") }.issue.message shouldBe
                "no `graph <name> { … }` block"
            shouldThrow<TtrgParseException> {
                TtrgGraphReader.parseObjectRefs(
                    "graph g {\n  objects: [a b]\n}\n",
                )
            }.issue shouldBe
                TtrgIssue("objects", "expected `,` or `]` in objects", 2)
        }
    })

/** The syntax errors `TTR.g4`'s `document` rule reports for [text] — empty when it parses. */
private fun grammarErrors(text: String): List<String> {
    val errors = mutableListOf<String>()
    val listener =
        object : BaseErrorListener() {
            override fun syntaxError(
                recognizer: Recognizer<*, *>?,
                offendingSymbol: Any?,
                line: Int,
                charPositionInLine: Int,
                msg: String?,
                e: RecognitionException?,
            ) {
                errors += "$line:$charPositionInLine $msg"
            }
        }
    val lexer =
        TTRLexer(CharStreams.fromString(text)).apply {
            removeErrorListeners()
            addErrorListener(listener)
        }
    val parser =
        TTRParser(CommonTokenStream(lexer)).apply {
            removeErrorListeners()
            addErrorListener(listener)
        }
    parser.document()
    return errors
}
