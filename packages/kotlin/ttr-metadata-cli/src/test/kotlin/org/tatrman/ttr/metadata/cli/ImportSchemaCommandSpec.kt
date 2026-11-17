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

        fun importArgs(
            root: Path,
            out: Path,
            vararg extra: String,
        ) = runMetadataCommand(
            listOf(
                "import-schema",
                "--from",
                "file:${root.resolve("sources/shop-tables.json")}",
                "--profile",
                "om-tables/1",
                "--out",
                "$out",
            ) + extra,
        )

        test("R3: --root does not confine the --overlay the user names; its positions name the overlay file") {
            val root = workspace()
            val model = root.resolve("model")
            import(root).exitCode shouldBe 0
            val overlay = root.resolve("src/o.yaml").also { it.parent.createDirectories() }
            overlay.writeText("demo_pg.shop.public.orders:\n  description: Curated orders.\n")
            importArgs(root, model, "--root", "$model", "--overlay", "$overlay").let {
                withClue(it.stderr) { it.exitCode shouldBe 0 }
                model.resolve("om/db.ttrm").readText() shouldContain
                    "def table orders { description: \"Curated orders.\""
            }
            val orphan = root.resolve("src/orphan.yaml")
            orphan.writeText("# curated\nom.db.dbo.nothing:\n  description: x\n")
            importArgs(root, model, "--root", "$model", "--overlay", "$orphan").let {
                it.exitCode shouldBe 1
                it.stderr shouldStartWith "$orphan:2:1: error xm/overlay-orphan /om.db.dbo.nothing:"
            }

            // A file profile's own path overlay keeps the root confinement.
            val profile = root.resolve("profiles/own.yaml").also { it.parent.createDirectories() }
            profile.writeText(
                fixture
                    .resolve("../../../../profiles/om-tables/1.yaml")
                    .normalize()
                    .readText()
                    .replace("profile: om-tables/1\n", "profile: own/1\noverlay: ../src/o.yaml\n"),
            )
            runMetadataCommand(
                listOf(
                    "import-schema",
                    "--from",
                    "file:${root.resolve("sources/shop-tables.json")}",
                    "--profile",
                    "$profile",
                    "--out",
                    "$model",
                    "--root",
                    "$model",
                ),
            ).let {
                it.exitCode shouldBe 1
                it.stderr shouldContain "error ys/profile-not-found /overlay: Overlay is outside root"
            }
        }

        test("R4: --dry-run --format json keeps stdout one JSON value; the diff goes to stderr") {
            val root = workspace()
            import(root).exitCode shouldBe 0
            val source = root.resolve("sources/shop-tables.json")
            source.writeText(source.readText().replace("One checkout: the order header.", "One checkout (header)."))
            val dry = import(root, "--dry-run", "--format", "json")
            dry.exitCode shouldBe 0
            Json.parseToJsonElement(dry.stdout).jsonObject.keys shouldBe
                setOf("frontEnd", "profile", "packages", "ignored", "overridden", "warnings")
            dry.stderr shouldContain "+++ b/om/db.ttrm\n"
            dry.stderr shouldContain "+def table orders { description: \"One checkout (header).\",\n"
        }

        test("R6 (✅R330-E): --overlay with a profile that declares an overlay is a usage error naming both") {
            val root = workspace()
            val profile = root.resolve("profiles/curated.yaml").also { it.parent.createDirectories() }
            root.resolve("profiles/curated-overlay.yaml").writeText("om.db.dbo.orders:\n  description: Curated.\n")
            profile.writeText(
                "profile: curated/1\ngrammar: '>=0.22 <1.0'\nextends: om-tables/1\noverlay: ./curated-overlay.yaml\noperations: []\n",
            )
            val cli = root.resolve("oq.yaml").also { it.writeText("om.db.dbo.customers:\n  description: CLI.\n") }
            val out = root.resolve("fresh")

            fun run(vararg extra: String) =
                runMetadataCommand(
                    listOf(
                        "import-schema",
                        "--from",
                        "file:${root.resolve("sources/shop-tables.json")}",
                        "--profile",
                        "$profile",
                        "--out",
                        "$out",
                    ) + extra,
                )
            run("--overlay", "$cli").let {
                it.exitCode shouldBe 2
                it.stderr shouldContain "--overlay $cli cannot be combined with the overlay"
                it.stderr shouldContain "${root.toRealPath().resolve("profiles/curated-overlay.yaml")}"
            }
            out.exists() shouldBe false
            // The profile's own overlay alone, and --overlay with om-tables/1 (none declared), both work.
            run().exitCode shouldBe 0
            out.resolve("om/db.ttrm").readText() shouldContain "def table orders { description: \"Curated.\""
            import(root, "--overlay", "$cli").exitCode shouldBe 0
        }

        test("R7: an unwritable --out is a usage error and writes nothing (directories are made before any file)") {
            val root = workspace()
            val out = root.resolve("o9")
            out.resolve("om").also { it.parent.createDirectories() }.writeText("a file where a directory goes")
            importArgs(root, out).let {
                it.exitCode shouldBe 2
                it.stderr shouldStartWith
                    "ttr-metadata: --out $out: cannot write om/db.ttrm (FileAlreadyExistsException"
            }
            out.resolve("om.sources.json").exists() shouldBe false
            out.resolve("report.json").exists() shouldBe false
        }
    })
