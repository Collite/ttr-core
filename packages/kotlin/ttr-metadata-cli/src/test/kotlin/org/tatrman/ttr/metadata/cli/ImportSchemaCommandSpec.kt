// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.metadata.cli

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * MW-P5 S5.1 DONE — `import-schema --from file:<fixture> --profile om-tables/1 --out <tmp>` writes the committed
 * expectation (`tests/conformance/readers/om-tables/model`) byte-equal, strict-loadable through `lint --strict` (load +
 * the generated-file guard), with sidecar and header; `--dry-run` shows the diff and writes nothing; two runs are equal.
 */
class ImportSchemaCommandSpec :
    FunSpec({
        val fixture = Path.of("../../../tests/conformance/readers/om-tables").toAbsolutePath().normalize()

        fun files(directory: Path): Map<String, String> =
            Files.walk(directory).use { paths -> paths.filter { it.isRegularFile() }.toList() }.associate {
                directory.relativize(it).joinToString("/") to it.readText()
            }

        /** A copy of `sources/` in a temporary root, so the header's relative input path is the committed one. */
        fun workspace(): Path {
            val root = Files.createTempDirectory("s51-cli")
            files(fixture.resolve("sources")).forEach { (path, text) ->
                root.resolve("sources/$path").also { it.parent.createDirectories() }.writeText(text)
            }
            return root
        }

        fun import(
            root: Path,
            vararg extra: String,
        ) = runMetadataCommand(
            listOf(
                "import-schema",
                "--from",
                "file:${root.resolve("sources/shop-tables.json")}",
                "--profile",
                "om-tables/1",
                "--out",
                "${root.resolve("model")}",
            ) + extra,
        )

        test("writes the committed expectation byte-equal, two runs equal, and the package lints strict") {
            val root = workspace()
            val out = import(root)
            withClue(out.stderr) { out.exitCode shouldBe 0 }
            out.stdout shouldStartWith "### Import report — `om-tables/1` (json front-end)"
            val written = files(root.resolve("model"))
            written shouldBe files(fixture.resolve("model"))
            written.getValue("om/db.ttrm") shouldStartWith "// generated-from: ../../sources/shop-tables.json@"

            val again = workspace()
            import(again).exitCode shouldBe 0
            files(again.resolve("model")) shouldBe written

            val lint = runMetadataCommand(listOf("lint", "--root", "${root.resolve("model")}", "--strict"))
            withClue(lint.stdout + lint.stderr) {
                lint.exitCode shouldBe 0
                lint.stdout shouldContain "0 issues"
            }
            // The header guards the package: a changed snapshot makes it stale.
            root
                .resolve(
                    "sources/shop-tables.json",
                ).writeText(root.resolve("sources/shop-tables.json").readText() + " ")
            runMetadataCommand(listOf("lint", "--root", "${root.resolve("model")}", "--strict")).let {
                it.exitCode shouldBe 1
                it.stdout shouldContain "xm/generated-stale"
            }
        }

        test("--dry-run prints each changed file's diff and writes nothing") {
            val root = workspace()
            import(root).exitCode shouldBe 0
            val before = files(root.resolve("model"))
            import(root, "--dry-run").let {
                it.exitCode shouldBe 0
                it.stdout shouldStartWith "No changes under "
            }
            val source = root.resolve("sources/shop-tables.json")
            source.writeText(source.readText().replace("One checkout: the order header.", "One checkout (header)."))
            val dry = import(root, "--dry-run")
            dry.exitCode shouldBe 0
            dry.stdout shouldContain "--- a/om/db.ttrm\n+++ b/om/db.ttrm\n"
            dry.stdout shouldContain
                "-def table orders { description: \"One checkout: the order header.\",\n" +
                "+def table orders { description: \"One checkout (header).\",\n"
            dry.stdout shouldContain "### Import report"
            files(root.resolve("model")) shouldBe before

            // A dry run into a directory that does not exist creates nothing.
            val fresh = root.resolve("elsewhere")
            runMetadataCommand(
                listOf(
                    "import-schema",
                    "--from",
                    "file:$source",
                    "--profile",
                    "om-tables/1",
                    "--out",
                    "$fresh",
                    "--dry-run",
                ),
            ).stdout shouldContain "--- /dev/null\n+++ b/om/db.ttrm\n"
            fresh.exists() shouldBe false
        }

        test("--format json prints the report; usage and profile errors exit 2 and 1 and write nothing") {
            val root = workspace()
            val json = import(root, "--format", "json")
            json.exitCode shouldBe 0
            Json.parseToJsonElement(json.stdout).jsonObject.keys shouldBe
                setOf("frontEnd", "profile", "packages", "ignored", "overridden", "warnings")

            val empty = workspace()

            fun usage(vararg args: String) = runMetadataCommand(listOf("import-schema") + args)
            usage("--from", "openmetadata", "--profile", "om-tables/1", "--out", "${empty.resolve("model")}").let {
                it.exitCode shouldBe 2
                it.stderr shouldContain "MW-P5 S5.5"
            }
            usage("--from", "db:x", "--profile", "om-tables/1", "--out", "${empty.resolve("model")}").exitCode shouldBe
                2
            usage(
                "--from",
                "file:${empty.resolve("nope.json")}",
                "--profile",
                "om-tables/1",
                "--out",
                "${empty.resolve("model")}",
            ).exitCode shouldBe 2
            usage(
                "--from",
                "file:${empty.resolve("sources/shop-tables.json")}",
                "--profile",
                "om-tables/9",
                "--out",
                "${empty.resolve("model")}",
            ).exitCode shouldBe 2
            usage("--profile", "om-tables/1").exitCode shouldBe 2

            // A key the profile does not know is the engine's located error, exit 1.
            val source = empty.resolve("sources/shop-tables.json")
            source.writeText(
                source.readText().replaceFirst(
                    "\"tableType\": \"Regular\",",
                    "\"tableType\": \"Regular\", \"retention\": 7,",
                ),
            )
            val unknown = import(empty)
            unknown.exitCode shouldBe 1
            unknown.stderr shouldContain "shop-tables.json:12:"
            unknown.stderr shouldContain "ys/unknown-key /data/0/retention"
            empty.resolve("model").exists() shouldBe false
        }
    })
