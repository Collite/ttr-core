// SPDX-License-Identifier: Apache-2.0
package org.tatrman.concept.extract

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldMatch
import io.kotest.matchers.string.shouldNotContain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.tatrman.concept.cli.ModelContentHash
import org.tatrman.concept.cli.runTtr
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.security.MessageDigest
import kotlin.io.path.isRegularFile

/**
 * DX-P0 S0.3 T3/T4 — `ttr cn render-types`: the files of the pack, the index's hashes, two runs byte-identical, the
 * exit codes (2 for an unknown entity, naming it, and nothing written). review-158: the index pins the model's content
 * hash, the same from any copy of the model (F2); a name two entities share exits 2 (F9); an entity named twice is
 * listed once, and a re-render leaves no schema file its index does not list (F12).
 */
class RenderTypesCliSpec :
    FunSpec({
        fun tree(root: Path): Map<String, String> =
            Files.walk(root).use { s ->
                s.filter { it.isRegularFile() }.sorted().toList().associate {
                    root.relativize(it).toString() to
                        Files.readString(it)
                }
            }

        fun run(
            entities: List<String>,
            model: Path = ExtractWorlds.dir,
            out: Path = Files.createTempDirectory("render-types"),
        ): Pair<Int, Path> {
            val code =
                runTtr(
                    listOf(
                        "cn",
                        "render-types",
                        "--model",
                        "$model",
                        "--entities",
                        entities.joinToString(","),
                        "--out",
                        "$out",
                    ),
                )
            return code to out
        }

        fun render(vararg entities: String): Pair<Int, Path> = run(entities.toList())

        fun index(out: Path): JsonObject =
            Json.parseToJsonElement(Files.readString(out.resolve("types/generated/index.json"))).jsonObject

        fun keys(out: Path): List<String> =
            index(out)["types"]!!.jsonArray.map { it.jsonObject["key"]!!.jsonPrimitive.content }

        fun stderrOf(block: () -> Pair<Int, Path>): Triple<Int, Path, String> {
            val err = ByteArrayOutputStream()
            val saved = System.err
            System.setErr(PrintStream(err, true, Charsets.UTF_8))
            val (code, out) =
                try {
                    block()
                } finally {
                    System.setErr(saved)
                }
            return Triple(code, out, err.toString(Charsets.UTF_8))
        }

        test("writes Statement and Record per entity plus the index, under types/generated/") {
            val (code, out) = render("review", "claim")
            code shouldBe 0
            tree(out).keys shouldContainExactly
                setOf(
                    "types/generated/Record.claim.schema.json",
                    "types/generated/Record.review.schema.json",
                    "types/generated/Statement.claim.schema.json",
                    "types/generated/Statement.review.schema.json",
                    "types/generated/index.json",
                )
            tree(out)["types/generated/Statement.review.schema.json"] shouldBe
                Files.readString(ExtractWorlds.dir.resolve("expected/Statement.review.schema.json"))
        }

        test("the index carries the model's content hash and each file's sha256") {
            val (_, out) = render("review")
            val index = index(out)
            index["snapshotHash"]!!.jsonPrimitive.content shouldMatch Regex("sha256:[0-9a-f]{64}")
            index["snapshotHash"]!!.jsonPrimitive.content shouldBe ModelContentHash.of(ExtractWorlds.dir)
            val types = index["types"]!!.jsonArray.map { it.jsonObject }
            types.map { it["key"]!!.jsonPrimitive.content } shouldBe listOf("Record.review", "Statement[review]")
            for (t in types) {
                val bytes = Files.readAllBytes(out.resolve("types/generated/${t["file"]!!.jsonPrimitive.content}"))
                val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
                t["sha256"]!!.jsonPrimitive.content shouldBe sha
            }
            types.map { it["feedsGold"]!!.jsonPrimitive.content } shouldBe listOf("true", "false")
        }

        test("running it twice yields identical files") {
            tree(render("review", "claim", "product_sheet").second) shouldBe
                tree(render("review", "claim", "product_sheet").second)
        }

        test("an unknown entity exits 2, names it, and writes nothing") {
            val (code, out, err) = stderrOf { render("review", "refund") }
            code shouldBe 2
            err shouldContain "unknown entity 'refund'"
            tree(out) shouldBe emptyMap()
        }

        test("F2: two copies of the model at other roots, one touched, give byte-identical indexes") {
            val a = ExtractWorlds.copy()
            val b = ExtractWorlds.copy()
            val touched = FileTime.fromMillis(86_400_000L)
            for (file in Files.list(b).use { it.toList() }) Files.setLastModifiedTime(file, touched)
            val (codeA, outA) = run(listOf("review", "claim"), model = a)
            val (codeB, outB) = run(listOf("review", "claim"), model = b)
            codeA shouldBe 0
            codeB shouldBe 0
            Files.readAllBytes(outB.resolve("types/generated/index.json")) shouldBe
                Files.readAllBytes(outA.resolve("types/generated/index.json"))
            tree(outB) shouldBe tree(render("review", "claim").second) // and the same as from the repo's own copy
        }

        test("F2: one byte changed in one model file changes the index's hash") {
            val a = ExtractWorlds.copy()
            val b = ExtractWorlds.copy()
            val file = b.resolve("er.ttrm")
            Files.writeString(file, Files.readString(file).replace("Stars, 1 to 5.", "Stars, 1 to 6."))
            index(run(listOf("review"), model = b).second)["snapshotHash"] shouldNotBe
                index(run(listOf("review"), model = a).second)["snapshotHash"]
        }

        test("F2: the index names no absolute path") {
            val model = ExtractWorlds.copy()
            val (_, out) = run(listOf("review", "claim"), model = model)
            val text = Files.readString(out.resolve("types/generated/index.json"))
            text shouldNotContain model.toString()
            text shouldNotContain out.toString()
            text shouldNotContain ExtractWorlds.dir.toString()
            text shouldNotContain "\"/"
        }

        test("F9: a name two er schemas share exits 2 with extract/ambiguous-entity, however it is qualified") {
            val model =
                ExtractWorlds.write(
                    mapOf(
                        "a.ttrm" to
                            "model er schema a\ndef entity review { attributes: [ def attribute id { type: int, isKey: true }, def attribute body { type: text } ] }",
                        "b.ttrm" to
                            "model er schema b\ndef entity review { attributes: [ def attribute id { type: int, isKey: true }, def attribute stars { type: int } ] }",
                    ),
                )
            for (ref in listOf("a.review", "review")) {
                val (code, out, err) = stderrOf { run(listOf(ref), model = model) }
                code shouldBe 2
                err shouldContain "extract/ambiguous-entity"
                tree(out) shouldBe emptyMap()
            }
        }

        test("F12: an entity named twice is listed once") {
            val (code, out) = render("review", "er.entity.review")
            code shouldBe 0
            keys(out) shouldBe listOf("Record.review", "Statement[review]")
        }

        test("F12: a re-render deletes the generated schemas its index does not list, and nothing else") {
            val out = Files.createTempDirectory("render-types")
            val handWritten =
                listOf(
                    "types/registry.yaml",
                    "types/generated/README.md",
                    "schemas/Mention.schema.json",
                    "Claim.schema.json",
                )
            for (rel in handWritten) {
                Files.createDirectories(out.resolve(rel).parent)
                Files.writeString(out.resolve(rel), "hand-written\n")
            }
            run(listOf("review", "claim"), out = out).first shouldBe 0
            tree(out).keys.filter { it.endsWith(".claim.schema.json") }.size shouldBe 2
            run(listOf("review"), out = out).first shouldBe 0
            tree(out).keys.filter { it.endsWith(".claim.schema.json") } shouldBe emptyList()
            keys(out) shouldBe listOf("Record.review", "Statement[review]")
            for (rel in handWritten) Files.readString(out.resolve(rel)) shouldBe "hand-written\n"
        }

        test("usage errors exit 2") {
            runTtr(listOf("cn", "render-types", "--model", "${ExtractWorlds.dir}")) shouldBe 2
            runTtr(
                listOf("cn", "render-types", "--model", "/nonexistent", "--entities", "review", "--out", "/tmp/x"),
            ) shouldBe
                2
        }
    })
