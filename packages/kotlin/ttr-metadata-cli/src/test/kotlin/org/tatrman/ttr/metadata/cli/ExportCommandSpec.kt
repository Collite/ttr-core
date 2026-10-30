// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.metadata.cli

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.serialization.json.*
import org.tatrman.ttr.metadata.export.ExportGrammar
import java.nio.file.Files
import java.nio.file.Path

class ExportCommandSpec :
    StringSpec({
        val metadata = Path.of("../ttr-metadata")
        val root = metadata.resolve("src/testFixtures/resources/fixtures/xm-world").toAbsolutePath()

        fun run(
            out: Path,
            vararg args: String,
        ) = runMetadataCommand(
            listOf("export", root.toString(), "--out", out.toString()) + args,
        )

        fun golden(
            format: String,
            name: String,
        ) = metadata.resolve("src/test/resources/xm-goldens/$format/$name")

        fun normalized(
            bytes: ByteArray,
            snapshot: String,
        ) = bytes.decodeToString().replace(snapshot, "fixture-1")

        "real export command emits owl bytes and snapshot, with no archive by default" {
            val out = Files.createTempDirectory("xm-owl")
            run(out, "--model", "cn", "--format", "ttl", "--profile", "owl").exitCode shouldBe 0
            val version = Files.readString(out.resolve(".snapshot")).trim()
            normalized(Files.readAllBytes(out.resolve("cn.ttl")), version) shouldBe
                // The library golden uses a fixed grammar header; the real CLI stamps the current grammar.
                Files.readString(golden("ttl", "world-owl.ttl")).replace(
                    "owl:versionInfo \"0.18\"",
                    "owl:versionInfo \"${ExportGrammar.version}\"",
                )
            Files.list(out).use { it.count() } shouldBe 2
        }
        "graph since reads previous snapshot ids and authored renames" {
            val out = Files.createTempDirectory("xm-since")
            val previous = out.resolve("previous.json")
            Files.writeString(
                previous,
                """{"graph":"v1","snapshot":"fixture-0","nodes":[{"id":"md.measure.turnover"},{"id":"er.entity.retired"}],"edges":[]}""",
            )
            run(out, "--format", "graph.json", "--since", previous.toString()).exitCode shouldBe 0
            val delta =
                Json
                    .parseToJsonElement(
                        Files.readString(out.resolve("graph.json")),
                    ).jsonObject
                    .getValue("delta")
                    .jsonObject
            delta.getValue("since") shouldBe JsonPrimitive("fixture-0")
            delta.getValue("removed") shouldBe JsonArray(listOf(JsonPrimitive("er.entity.retired")))
            delta.getValue("renamed").jsonArray.size shouldBe 1
        }
        "cannot carry, unavailable since and invalid addressing have distinct exits" {
            val out = Files.createTempDirectory("xm-errors")
            val carry = run(out, "--model", "md", "--format", "ttl")
            carry.exitCode shouldBe 2
            carry.stderr shouldContain "xm/format-cannot-carry"
            run(out, "--format", "graph.json", "--since", out.resolve("missing").toString()).exitCode shouldBe 3
            for (args in listOf(
                listOf("--format", "nope"),
                listOf("--format", "ttl", "--profile", "nope"),
                listOf("--format", "graph.json", "--model", "nope"),
            )) {
                val usage = run(out, *args.toTypedArray())
                usage.exitCode shouldBe 64
                usage.stderr shouldContain "Usage:"
            }
            Files.exists(out.resolve(".snapshot")) shouldBe false
        }
        "defs carries only public connection descriptors, scopes and TTR bundles work" {
            val out = Files.createTempDirectory("xm-defs")
            run(out, "--format", "defs.json").exitCode shouldBe 0
            val doc = Json.parseToJsonElement(Files.readString(out.resolve("defs.json"))).jsonObject
            doc
                .getValue("connections")
                .jsonArray
                .single()
                .jsonObject
                .getValue("id") shouldBe JsonPrimitive("northline-pg")
            run(out, "--format", "graph.json", "--package", "cn").exitCode shouldBe 0
            val scope =
                Json
                    .parseToJsonElement(
                        Files.readString(out.resolve("graph.json")),
                    ).jsonObject
                    .getValue("scope")
                    .jsonObject
            scope.getValue("packages") shouldBe JsonArray(listOf(JsonPrimitive("cn")))
            run(out, "--format", "ttr", "--model", "cn", "--tar").exitCode shouldBe 0
            Files.exists(out.resolve("cn/concepts.ttrm")) shouldBe true
            Files.exists(out.resolve("model.tar.gz")) shouldBe true
        }
    })
