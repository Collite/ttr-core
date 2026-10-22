// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.metadata.cli

import com.github.ajalt.clikt.testing.test
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.nio.file.Files
import java.nio.file.Path

class MdHopCliSpec :
    StringSpec({
        val fixture = Path.of("../ttr-metadata/src/test/resources/md-hop").toAbsolutePath().normalize()
        "md hop prints the shared report; --all changes finding scope, --fail-on controls exit" {
            mdCommand().test(listOf("hop", "$fixture", "--all", "--format", "json")).let {
                it.statusCode shouldBe 0
                it.stdout shouldBe Files.readString(Path.of("../../../tests/conformance/md-hop/expected.json"))
            }
            mdCommand().test(listOf("hop", "$fixture", "--all", "--fail-on", "warning")).statusCode shouldBe 1
            mdCommand().test(listOf("hop", "$fixture", "--fail-on", "warning")).statusCode shouldBe 0
            mdCommand().test(listOf("hop", "$fixture", "--all", "--fail-on", "error")).statusCode shouldBe 0
            mdCommand().test(listOf("hop", "$fixture", "--all")).stdout shouldBe
                Files.readString(Path.of("../../../tests/conformance/md-hop/expected.md"))
        }
        "--fail-on counts structural errors even when the dimension has an explicit landing" {
            val root = Files.createTempDirectory("md-hop-invalid")
            try {
                Files.writeString(
                    root.resolve("md.ttrm"),
                    "model md\ndef domain Code { type: string }\n" +
                        "def dimension D { key: code, attributes: [def attribute code { domain: md.Code }] }",
                )
                Files.writeString(
                    root.resolve("er.ttrm"),
                    "model er\ndef entity e { attributes: [def attribute sk { type: int }] }",
                )
                Files.writeString(
                    root.resolve("binding.ttrm"),
                    "model binding\ndef md2er_dimension invalid { dimension: md.D, entity: er.entity.e }",
                )
                for (threshold in listOf("error", "warning")) {
                    val result = mdCommand().test(listOf("hop", "$root", "--fail-on", threshold))
                    result.statusCode shouldBe 1
                    result.stderr shouldContain "md/md2er-dimension-key"
                    result.stdout shouldContain "er.entity.e"
                }
            } finally {
                root.toFile().deleteRecursively()
            }
        }

        "the ttr front door pins usage errors to exit 2" {
            mdCommand().test(listOf("hop", "$fixture/missing")).statusCode shouldBe 2
            // Clikt's invalid option is converted by runTtr (the cn CLI's existing exit contract).
            mdCommand().test(listOf("hop", "$fixture", "--format", "xml")).statusCode shouldBe 1
        }
    })
