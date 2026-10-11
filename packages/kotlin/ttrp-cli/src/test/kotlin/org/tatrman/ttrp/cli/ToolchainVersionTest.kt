// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.cli

import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.testing.test
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path

/**
 * The toolchain version is the build's project version, stamped at build time — not the `0.0.0-dev`
 * `BundleAssembler` defaults to: `ttrp --version` prints it, and a bundle `ttrp build` writes records it in
 * `manifest.json` and the compile record (`toolchain: org.tatrman:ttrp:<version>`). The Gradle test task passes
 * the project version in as `ttrp.expectedVersion`.
 */
class ToolchainVersionTest :
    FunSpec({
        val expected: String = System.getProperty("ttrp.expectedVersion") ?: error("ttrp.expectedVersion not set")

        test("the stamped toolchain version is this build's project version") {
            ToolchainVersion.current shouldBe expected
            ToolchainVersion.current shouldNotBe "0.0.0-dev"
        }

        test("ttrp --version prints it") {
            val r = TtrpCommand().subcommands(BuildCommand(), CheckCommand()).test("--version")
            withClue(r.output) { r.stdout.trim() shouldBe "ttrp $expected" }
        }

        test("ttrp build records it in manifest.json and in the compile record") {
            val program =
                run {
                    val rel = Path.of("packages/kotlin/ttrp-frontend/src/test/resources/display/programs/notify.ttrp")
                    var dir: Path? = Path.of("").toAbsolutePath()
                    while (dir != null && !Files.isRegularFile(dir.resolve(rel))) dir = dir.parent
                    dir!!.resolve(rel)
                }
            val out = Files.createTempDirectory("ttrp-toolchain")
            val r = TtrpCommand().subcommands(BuildCommand()).test("build $program --out $out")
            withClue(r.output) { r.statusCode shouldBe 0 }

            fun toolchainOf(file: Path) =
                Json
                    .parseToJsonElement(Files.readString(file))
                    .jsonObject
                    .getValue("toolchain")
                    .jsonPrimitive.content
            toolchainOf(out.resolve("notify.bundle/manifest.json")) shouldBe "org.tatrman:ttrp:$expected"
            toolchainOf(out.resolve("notify.compile-record.json")) shouldBe "org.tatrman:ttrp:$expected"
        }
    })
