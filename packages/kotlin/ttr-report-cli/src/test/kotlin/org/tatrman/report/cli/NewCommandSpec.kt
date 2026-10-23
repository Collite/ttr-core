// SPDX-License-Identifier: Apache-2.0
package org.tatrman.report.cli

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.nio.file.Files
import java.nio.file.Path

class NewCommandSpec :
    StringSpec({
        val world = Path.of(requireNotNull(javaClass.getResource("/worlds/reports/modeler.toml")).toURI()).parent

        fun args(out: Path) =
            listOf(
                "new",
                world.toString(),
                "--from",
                "er.query.report_returns_review_q",
                "--id",
                "returns_review_q",
                "--title",
                "Returns review, this quarter",
                "--title-cs",
                "Přehled reklamací, toto čtvrtletí",
                "--role",
                "kantheon-role-finance",
                "--out",
                out.toString(),
            )
        "new creates the exact generated fixture and refuses overwrite until force" {
            val root = Files.createTempDirectory("new-report")
            try {
                val out = root.resolve("returns_review_q")
                val result = runReportCommand(args(out))
                result.stderr shouldBe ""
                result.exitCode shouldBe 0
                val fixture = world.resolve("model/reports/returns_review_q")
                Files.readString(out.resolve("report.yaml")) shouldBe Files.readString(fixture.resolve("report.yaml"))
                Files.readAllBytes(out.resolve("template.xlsx")).toList() shouldBe
                    Files.readAllBytes(fixture.resolve("template.xlsx")).toList()
                Files.writeString(out.resolve("report.yaml"), "old")
                val refused = runReportCommand(args(out))
                refused.exitCode shouldBe 1
                refused.stderr shouldContain "output-exists"
                Files.readString(out.resolve("report.yaml")) shouldBe "old"
                runReportCommand(args(out) + "--force").exitCode shouldBe 0
                Files.readString(out.resolve("report.yaml")) shouldBe Files.readString(fixture.resolve("report.yaml"))
            } finally {
                root.toFile().deleteRecursively()
            }
        }
        "either existing file refuses without creating its missing partner" {
            val root = Files.createTempDirectory("new-existing")
            try {
                val target = Files.createDirectory(root.resolve("returns_review_q"))
                Files.writeString(target.resolve("template.xlsx"), "old")
                runReportCommand(args(target)).exitCode shouldBe 1
                Files.exists(root.resolve("returns_review_q/report.yaml")) shouldBe false
                Files.readString(root.resolve("returns_review_q/template.xlsx")) shouldBe "old"
            } finally {
                root.toFile().deleteRecursively()
            }
        }
        "generation refusal creates no output and leaves existing files intact" {
            val root = Files.createTempDirectory("new-refusal")
            try {
                val invalid = args(root.resolve("new")).toMutableList()
                invalid[invalid.indexOf("--from") + 1] = "er.query.absent"
                val result = runReportCommand(invalid + "--force")
                result.exitCode shouldBe 1
                result.stderr shouldContain "program-missing"
                Files.exists(root.resolve("new")) shouldBe false
            } finally {
                root.toFile().deleteRecursively()
            }
        }
        "default output uses the checkout and DB declarations become required typed inputs" {
            val root = Files.createTempDirectory("new-db")
            try {
                Files.writeString(root.resolve("modeler.toml"), "[language]\npreferred = \"en\"\n")
                Files.createDirectory(root.resolve("model"))
                Files.writeString(
                    root.resolve("model/table.ttrm"),
                    "model db schema dbo\ndef table item { columns: [def column name { type: text }] }",
                )
                Files.writeString(
                    root.resolve("model/query.ttrm"),
                    """
                    model db schema query
                    def query lookup_items {
                        parameters: [{ name: needle, type: text }, { name: count, type: int },
                            { name: amount, type: decimal }, { name: flag, type: bool },
                            { name: as_of, type: datetime }, { name: custom, type: exotic }]
                        sourceText: "SELECT name AS label FROM db.dbo.item WHERE name = {needle}"
                    }
                    """.trimIndent(),
                )
                val result =
                    runReportCommand(
                        listOf(
                            "new",
                            root.toString(),
                            "--from",
                            "db.query.lookup_items",
                            "--id",
                            "lookup_items",
                            "--title",
                            "Search",
                        ),
                    )
                result.exitCode shouldBe 0
                result.stderr shouldContain "parameter-type-unknown"
                val spec =
                    org.tatrman.report.ReportYaml.read(
                        Files.readString(root.resolve("model/reports/lookup_items/report.yaml")),
                        "lookup_items",
                    )
                spec.params.map { it.kind.wire } shouldBe listOf("text", "int", "float", "bool", "datetime", "text")
                spec.params.all { it.required } shouldBe true
                spec.roles shouldBe emptyList()
                Files.exists(root.resolve("model/reports/lookup_items/template.xlsx")) shouldBe true
                // The preferred locale is a generation check, before any output is touched.
                Files.writeString(root.resolve("modeler.toml"), "[language]\npreferred = \"cs\"\n")
                val refused =
                    runReportCommand(
                        listOf(
                            "new",
                            root.toString(),
                            "--from",
                            "db.query.lookup_items",
                            "--id",
                            "other",
                            "--title",
                            "Other",
                        ),
                    )
                refused.exitCode shouldBe 1
                refused.stderr shouldContain "title-missing"
                Files.exists(root.resolve("model/reports/other")) shouldBe false
            } finally {
                root.toFile().deleteRecursively()
            }
        }
        "ER parameter declarations refuse in the real snapshot before writing" {
            val root = Files.createTempDirectory("new-er-param")
            try {
                Files.writeString(root.resolve("modeler.toml"), "[language]\npreferred = \"en\"\n")
                Files.createDirectory(root.resolve("model"))
                Files.writeString(
                    root.resolve("model/q.ttrm"),
                    """
                    model er
                    def query lookup_items {
                        parameters: [{ name: needle, type: text }]
                        sourceText: "SELECT 1 AS label"
                    }
                    """.trimIndent(),
                )
                val result =
                    runReportCommand(
                        listOf(
                            "new",
                            root.toString(),
                            "--from",
                            "er.query.lookup_items",
                            "--id",
                            "lookup_items",
                            "--title",
                            "Search",
                        ),
                    )
                result.exitCode shouldBe 1
                result.stderr shouldContain "query-has-parameters"
                Files.exists(root.resolve("model/reports")) shouldBe false
            } finally {
                root.toFile().deleteRecursively()
            }
        }
        "force refuses a non-file partner before replacing either output" {
            val root = Files.createTempDirectory("new-invalid-output")
            try {
                val target = Files.createDirectory(root.resolve("returns_review_q"))
                Files.writeString(target.resolve("report.yaml"), "old")
                Files.createDirectory(target.resolve("template.xlsx"))
                val result = runReportCommand(args(target) + "--force")
                result.exitCode shouldBe 1
                result.stderr shouldContain "output-invalid"
                Files.readString(root.resolve("returns_review_q/report.yaml")) shouldBe "old"
            } finally {
                root.toFile().deleteRecursively()
            }
        }
        "new requires identity and title and supports help" {
            runReportCommand(listOf("new", "--help")).exitCode shouldBe 0
            runReportCommand(listOf("new", world.toString())).exitCode shouldBe 64
        }
        "output folder must match id and third language titles are supported" {
            val root = Files.createTempDirectory("new-locales")
            try {
                val copied = root.resolve("world")
                world.toFile().copyRecursively(copied.toFile())
                Files.writeString(copied.resolve("modeler.toml"), "[language]\npreferred = \"de\"\n")
                val out = root.resolve("returns_review_q")
                val input = args(out).toMutableList().apply { this[1] = copied.toString() }
                val result = runReportCommand(input + listOf("--title-lang", "de=Retouren"))
                result.stderr shouldBe ""
                result.exitCode shouldBe 0
                org.tatrman.report.ReportYaml
                    .read(
                        Files.readString(out.resolve("report.yaml")),
                        "returns_review_q",
                        "de",
                    ).title["de"] shouldBe
                    "Retouren"
                val bad = args(root.resolve("different"))
                val refused = runReportCommand(bad)
                refused.exitCode shouldBe 1
                refused.stderr shouldContain "id-mismatch"
                Files.exists(root.resolve("different")) shouldBe false
            } finally {
                root.toFile().deleteRecursively()
            }
        }
    })
