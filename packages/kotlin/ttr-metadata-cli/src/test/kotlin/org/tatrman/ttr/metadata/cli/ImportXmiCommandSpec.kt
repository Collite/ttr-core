// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.metadata.cli

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * MW-P5 S5.3 T4 — `import-xmi --from <file> --profile ttr-mdg/1|ea-erd/1 --package <name> --out <tmp>`: the committed
 * expectations (`tests/conformance/readers/xmi/model`, `erd`) byte-equal; usage errors exit 2; `--dry-run` writes nothing.
 */
class ImportXmiCommandSpec :
    FunSpec({
        val fixture = Path.of("../../../tests/conformance/readers/xmi").toAbsolutePath().normalize()

        fun files(directory: Path): Map<String, String> =
            Files.walk(directory).use { paths -> paths.filter { it.isRegularFile() }.toList() }.associate {
                directory.relativize(it).joinToString("/") to it.readText()
            }

        fun workspace(): Path =
            Files.createTempDirectory("s53-cli").also { root ->
                files(fixture.resolve("sources")).forEach { (path, text) ->
                    root.resolve("sources/$path").also { it.parent.createDirectories() }.writeText(text)
                }
            }

        fun import(
            root: Path,
            input: String,
            profile: String,
            pkg: String,
            out: String,
            vararg extra: String,
        ) = runMetadataCommand(
            listOf(
                "import-xmi",
                "--from",
                "${root.resolve("sources/$input")}",
                "--profile",
                profile,
                "--package",
                pkg,
                "--out",
                "${root.resolve(out)}",
            ) + extra,
        )

        test("ttr-mdg/1 and ea-erd/1 write the committed expectations byte-equal") {
            val root = workspace()
            val mdg = import(root, "shop.xmi", "ttr-mdg/1", "shop", "model")
            withClue(mdg.stderr) { mdg.exitCode shouldBe 0 }
            mdg.stdout shouldStartWith "### Import report — `ttr-mdg/1` (xmi front-end)"
            files(root.resolve("model")) shouldBe files(fixture.resolve("model"))

            val erd = import(root, "orders-erd.xmi", "ea-erd/1", "orders", "erd", "--format", "json")
            withClue(erd.stderr) { erd.exitCode shouldBe 0 }
            erd.stdout shouldBe fixture.resolve("erd/report.json").readText()
            files(root.resolve("erd")) shouldBe files(fixture.resolve("erd"))
        }

        test("a missing input or an unknown profile is a usage error (exit 2); --dry-run writes nothing") {
            val root = workspace()
            import(root, "missing.xmi", "ttr-mdg/1", "shop", "model").also {
                it.exitCode shouldBe 2
                it.stderr shouldContain "no input file"
            }
            import(root, "shop.xmi", "no-such/1", "shop", "model").also {
                it.exitCode shouldBe 2
                it.stderr shouldContain "neither a shipped profile nor a file"
            }
            import(root, "shop.xmi", "ttr-mdg/1", "shop", "dry", "--dry-run").also {
                withClue(it.stderr) { it.exitCode shouldBe 0 }
                it.stdout shouldContain "shop/cn.ttrm"
            }
            root.resolve("dry").exists() shouldBe false
        }
    })
