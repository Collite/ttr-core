// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.metadata.cli

import com.github.ajalt.clikt.testing.test
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
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
        "the ttr front door pins usage errors to exit 2" {
            mdCommand().test(listOf("hop", "$fixture/missing")).statusCode shouldBe 2
            // Clikt's invalid option is converted by runTtr (the cn CLI's existing exit contract).
            mdCommand().test(listOf("hop", "$fixture", "--format", "xml")).statusCode shouldBe 1
        }
    })
