// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.cli

import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.testing.test
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.nio.file.Files

class ReplayConformCliSpec :
    StringSpec({
        "seeds report exit zero without a token or Server and accept multiple cells" {
            val f = Files.createTempFile("seed-replay", ".yaml")
            Files.writeString(f, "recipe: release_order\nas_of: \"2026-11-02\"\nexpected: {verdicts: []}\n")
            val report = Files.createTempFile("seed-report", ".json")
            val r =
                TtrpCommand()
                    .subcommands(
                        ConformCommand(),
                    ).test(
                        listOf(
                            "conform",
                            "--replay",
                            f.toString(),
                            "--cells",
                            "native.batch",
                            "native.on-event",
                            "--out",
                            report.toString(),
                        ),
                    )
            r.statusCode shouldBe 0
            r.output shouldContain "native.batch: seed"
            r.output shouldContain "native.on-event: seed"
            Files.readString(report) shouldContain "\"seed\": true"
        }
        "malformed replay fixtures produce driver-error exit two" {
            val f = Files.createTempFile("bad-replay", ".yaml")
            Files.writeString(f, "recipe: release_order\nexpected: {verdicts: []}\n")
            val r =
                TtrpCommand()
                    .subcommands(
                        ConformCommand(),
                    ).test(listOf("conform", "--replay", f.toString(), "--cells", "native.batch"))
            r.statusCode shouldBe 2
            r.output shouldContain "as_of"
        }
    })
