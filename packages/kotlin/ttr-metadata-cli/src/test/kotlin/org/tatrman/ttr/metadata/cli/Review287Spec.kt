// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.metadata.cli

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldEndWith
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * MW S3.7 — review-287 R1 (✅R287-A): a generated file's header names its inputs between real paths, and the guard
 * resolves them from the file's real directory, so `profile apply` then `lint --strict` is green whatever symbolic link
 * sits on the way — a linked `--out` (✅R277-D allows it), a linked lint root prefix, a linked `--root`. The temp
 * directories are themselves below a link on macOS (`/var` → `/private/var`).
 */
class Review287Spec :
    FunSpec({
        val demoCs =
            Path
                .of("../../../tests/conformance/dialect/demo-cs/tree")
                .toAbsolutePath()
                .normalize()

        fun copy(
            from: Path,
            to: Path,
        ): Path {
            Files.walk(from).use { paths -> paths.filter { it.isRegularFile() }.toList() }.forEach {
                to.resolve(from.relativize(it).toString()).also { file -> file.parent.createDirectories() }.writeText(
                    it.readText(),
                )
            }
            return to
        }

        fun exec(
            cwd: Path,
            vararg args: String,
        ): CliOutcome {
            val binary = Path.of(requireNotNull(System.getProperty("metadata.installedBinary"))).toAbsolutePath()
            val process = ProcessBuilder(listOf("$binary") + args).directory(cwd.toFile()).start()
            val stdout = process.inputStream.bufferedReader().readText()
            val stderr = process.errorStream.bufferedReader().readText()
            return CliOutcome(process.waitFor(), stdout, stderr)
        }

        /** `lint --strict` on [root] (from [cwd]) is green: exit 0, no issue, the sidecar's header included. */
        fun green(
            cwd: Path,
            root: String,
        ) {
            val out = exec(cwd, "lint", "--root", root, "--strict")
            withClue(out.stdout + out.stderr) {
                out.exitCode shouldBe 0
                out.stdout shouldEndWith " 0 issues\n"
            }
        }

        test("V1: a linked --out at another depth than its target — lint of the target is green") {
            val tmp = Files.createTempDirectory("r287-v1")
            copy(demoCs, tmp.resolve("model"))
            val real = tmp.resolve("real").createDirectories()
            tmp.resolve("a/b").createDirectories()
            Files.createSymbolicLink(tmp.resolve("a/b/out"), real)
            exec(tmp, "profile", "apply", "--root", "model", "--out", "a/b/out").exitCode shouldBe 0
            real.resolve("demo.sources.json").isRegularFile() shouldBe true
            green(tmp, "real")
        }

        test("V2: lint through a linked prefix of the output — green") {
            val tmp = Files.createTempDirectory("r287-v2")
            copy(demoCs, tmp.resolve("model"))
            exec(tmp, "profile", "apply", "--root", "model", "--out", "deep/x/y/out").exitCode shouldBe 0
            Files.createSymbolicLink(tmp.resolve("sl"), tmp.resolve("deep/x/y"))
            green(tmp, "sl/out")
        }

        test("V3: a linked --root prefix — green (a path from a real directory resolves physically)") {
            val tmp = Files.createTempDirectory("r287-v3")
            copy(demoCs, tmp.resolve("model"))
            Files.createSymbolicLink(tmp.resolve("up"), tmp)
            exec(tmp, "profile", "apply", "--root", "up/model", "--out", "deep/x/y/o3").exitCode shouldBe 0
            green(tmp, "deep/x/y/o3")
        }
    })
