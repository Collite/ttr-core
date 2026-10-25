// SPDX-License-Identifier: Apache-2.0
package org.tatrman.concept.extract

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.tatrman.concept.cli.runTtr
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.io.path.isRegularFile

/**
 * DX-P0 S0.3 T3/T4 — `ttr cn render-types`: the files of the pack, the index's hashes, two runs byte-identical, the
 * exit codes (2 for an unknown entity, naming it, and nothing written).
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

        fun render(vararg entities: String): Pair<Int, Path> {
            val out = Files.createTempDirectory("render-types")
            val code =
                runTtr(
                    listOf(
                        "cn",
                        "render-types",
                        "--model",
                        "${ExtractWorlds.dir}",
                        "--entities",
                        entities.joinToString(","),
                        "--out",
                        "$out",
                    ),
                )
            return code to out
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

        test("the index carries the snapshot hash and each file's sha256") {
            val (_, out) = render("review")
            val index = Json.parseToJsonElement(Files.readString(out.resolve("types/generated/index.json"))).jsonObject
            index["snapshotHash"]!!.jsonPrimitive.content shouldBe ExtractWorlds.model().snapshotHash
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
            val err = ByteArrayOutputStream()
            val saved = System.err
            System.setErr(PrintStream(err, true, Charsets.UTF_8))
            val (code, out) =
                try {
                    render("review", "refund")
                } finally {
                    System.setErr(saved)
                }
            code shouldBe 2
            err.toString(Charsets.UTF_8) shouldContain "unknown entity 'refund'"
            tree(out) shouldBe emptyMap()
        }

        test("usage errors exit 2") {
            runTtr(listOf("cn", "render-types", "--model", "${ExtractWorlds.dir}")) shouldBe 2
            runTtr(
                listOf("cn", "render-types", "--model", "/nonexistent", "--entities", "review", "--out", "/tmp/x"),
            ) shouldBe
                2
        }
    })
