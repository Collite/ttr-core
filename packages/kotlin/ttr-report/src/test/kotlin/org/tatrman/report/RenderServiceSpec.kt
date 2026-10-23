// SPDX-License-Identifier: Apache-2.0
package org.tatrman.report

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class RenderServiceSpec :
    StringSpec({
        val clock = Clock.fixed(Instant.parse("2026-10-23T12:00:00Z"), ZoneOffset.UTC)
        val model =
            object : ModelLookup {
                override fun programExists(qname: String) =
                    qname == "er.query.report_returns_review_q" || qname == "db.query.report_returns_review_q"

                override fun outputColumns(qname: String) = listOf("category", "requests", "received", "open", "amount")
            }

        fun world(
            yaml: String = example(),
            second: Boolean = false,
        ): Path {
            val root = Files.createTempDirectory("report-world")
            val dir = Files.createDirectories(root.resolve("model/reports/returns_review_q"))
            Files.writeString(dir.resolve("report.yaml"), yaml)
            XSSFWorkbook().use { book ->
                for (name in if (second) listOf("Summary", "Second") else listOf("Summary")) {
                    val s = book.createSheet(name)
                    s.createRow(0).createCell(0).setCellValue("{{title}}")
                    s.createRow(1).createCell(0).setCellValue("{{as_of}}")
                    s.createRow(3).createCell(0).setCellValue("{{table:ROWS|category,requests,received,open,amount}}")
                }
                Files.write(dir.resolve("template.xlsx"), ByteArrayOutputStream().also(book::write).toByteArray())
            }
            return root
        }
        val table = Table(model.outputColumns(""), listOf(listOf("<Parts>", "3", "2", "1", "001.50")))
        val runner = ProgramRunner { _, _, _ -> table }

        fun cleanup(root: Path) {
            root.toFile().deleteRecursively()
        }

        "render validates named program and returns a filled deterministic workbook" {
            val root = world()
            try {
                val service = RenderService(model, clock = clock)
                val bytes = service.render(root, "returns_review_q", emptyMap(), runner)
                bytes.toList() shouldBe service.render(root, "returns_review_q", emptyMap(), runner).toList()
                XSSFWorkbook(ByteArrayInputStream(bytes)).use { book ->
                    val s = book.getSheet("Summary")
                    s.getRow(0).getCell(0).stringCellValue shouldBe "Returns review, this quarter"
                    s.getRow(1).getCell(0).stringCellValue shouldBe "2026-10-23"
                    s.getRow(3).getCell(0).stringCellValue shouldBe "<Parts>"
                }
            } finally {
                cleanup(root)
            }
        }
        "typed parameters defaults ISO datetime and entity text reach the runner and scalar expressions" {
            val yaml =
                example()
                    .replace("er.query.", "db.query.")
                    .replace(
                        "params: []",
                        """params:
  - { name: when, kind: datetime, required: true }
  - { name: customer, kind: entity, required: true }
  - { name: count, kind: int, default: 5 }
  - { name: ratio, kind: float, default: 1.25 }
  - { name: enabled, kind: bool, default: false }
  - { name: note, kind: text }
""",
                    ).replace("\"{{title}}\"", "\"{{param.customer}} / {{param.count}}\"")
            val root = world(yaml)
            try {
                var seen = emptyMap<String, Any?>()
                val caller = Caller("alice", listOf("finance"))
                val spy =
                    ProgramRunner { program, params, who ->
                        program shouldBe "db.query.report_returns_review_q"
                        who shouldBe caller
                        seen = params
                        table
                    }
                val declared =
                    object : ModelLookup by model {
                        override fun declaredParameters(qname: String) =
                            listOf(
                                "when",
                                "customer",
                                "count",
                                "ratio",
                                "enabled",
                                "note",
                            ).map { QueryParameter(it, "text") }
                    }
                val bytes =
                    RenderService(declared, clock = clock).render(
                        root,
                        "returns_review_q",
                        mapOf(
                            "when" to "2026-10-23T14:00:00+02:00",
                            "customer" to "001",
                        ),
                        spy,
                        caller,
                    )
                seen shouldBe
                    mapOf(
                        "when" to Instant.parse("2026-10-23T12:00:00Z"),
                        "customer" to "001",
                        "count" to 5L,
                        "ratio" to 1.25,
                        "enabled" to false,
                    )
                XSSFWorkbook(ByteArrayInputStream(bytes)).use {
                    it
                        .getSheetAt(0)
                        .getRow(0)
                        .getCell(0)
                        .stringCellValue shouldBe
                        "001 / 5"
                }
            } finally {
                cleanup(root)
            }
        }
        "bad required unknown and mistyped params refuse before executing" {
            val root =
                world(
                    example()
                        .replace(
                            "er.query.",
                            "db.query.",
                        ).replace("params: []", "params: [{name: n, kind: int, required: true}]"),
                )
            try {
                val never = ProgramRunner { _, _, _ -> error("must not execute") }
                for (params in listOf(emptyMap(), mapOf("n" to "1.2"), mapOf("n" to "1", "typo" to "x"))) {
                    shouldThrow<ReportException> {
                        RenderService(
                            model,
                        ).render(root, "returns_review_q", params, never)
                    }.code shouldBe
                        "parameter-invalid"
                }
            } finally {
                cleanup(root)
            }
        }
        "v2 table program override has its specific refusal" {
            val root =
                world(
                    example().replace(
                        "columns: [category, requests, received, open, amount]",
                        "program: er.query.other, columns: [category]",
                    ),
                )
            try {
                shouldThrow<ReportException> {
                    RenderService(
                        model,
                    ).render(root, "returns_review_q", emptyMap(), runner)
                }.code shouldBe
                    "anchor-program-unsupported"
            } finally {
                cleanup(root)
            }
        }
        "model validation occurs before any runner call" {
            val root = world(example().replace("er.query.report_returns_review_q", "er.query.absent"))
            try {
                shouldThrow<ReportException> {
                    RenderService(model).render(
                        root,
                        "returns_review_q",
                        emptyMap(),
                        ProgramRunner {
                            _,
                            _,
                            _,
                            ->
                            error("must not run")
                        },
                    )
                }.code shouldBe
                    "program-missing"
            } finally {
                cleanup(root)
            }
        }
        "HTML projects and caps the first table and does not require a workbook" {
            val root =
                world(
                    example().replace(
                        "columns: [category, requests, received, open, amount]",
                        "columns: [amount, category], limit: 1",
                    ),
                )
            Files.delete(root.resolve("model/reports/returns_review_q/template.xlsx"))
            try {
                val bytes = RenderService(model).render(root, "returns_review_q", emptyMap(), runner, html = true)
                String(bytes) shouldBe
                    HtmlTable.render(Table(listOf("amount", "category"), listOf(listOf("001.50", "<Parts>"))))
            } finally {
                cleanup(root)
            }
        }
        "same-named anchors in different sheets share one execution of the program" {
            val extra = """  - sheet: Second
    scalars: { title: second, as_of: fixed }
    tables: { ROWS: { columns: [category, requests, received, open, amount], limit: 1 } }
"""
            val root = world(example().replace("history:", extra + "history:"), true)
            try {
                var calls = 0
                val bytes =
                    RenderService(model, clock = clock).render(
                        root,
                        "returns_review_q",
                        emptyMap(),
                        ProgramRunner {
                            _,
                            _,
                            _,
                            ->
                            calls++
                            table
                        },
                    )
                calls shouldBe 1
                XSSFWorkbook(ByteArrayInputStream(bytes)).use { book ->
                    book
                        .getSheet("Summary")
                        .getRow(0)
                        .getCell(0)
                        .stringCellValue shouldBe "Returns review, this quarter"
                    book
                        .getSheet("Second")
                        .getRow(0)
                        .getCell(0)
                        .stringCellValue shouldBe "second"
                }
            } finally {
                cleanup(root)
            }
        }
        "corrupt workbooks and missing declared sheets have coded refusals" {
            val root = world()
            val template = root.resolve("model/reports/returns_review_q/template.xlsx")
            try {
                val original = Files.readAllBytes(template)
                Files.writeString(template, "not a workbook")
                shouldThrow<ReportException> {
                    RenderService(model).render(root, "returns_review_q", emptyMap(), runner)
                }.code shouldBe "template-invalid"
                XSSFWorkbook(ByteArrayInputStream(original)).use { book ->
                    book.setSheetName(0, "Wrong")
                    Files.write(template, ByteArrayOutputStream().also(book::write).toByteArray())
                }
                shouldThrow<ReportException> {
                    RenderService(model).render(root, "returns_review_q", emptyMap(), runner)
                }.code shouldBe "anchor-missing"
            } finally {
                cleanup(root)
            }
        }
        "IDs cannot escape the report directory" {
            shouldThrow<ReportException> {
                RenderService(
                    model,
                ).render(Path.of("."), "../other", emptyMap(), runner)
            }.code shouldBe
                "report-invalid"
        }
        "HTML without a table refuses without reading the workbook or executing" {
            val root =
                world(
                    example().replace(
                        "tables:  { ROWS: { columns: [category, requests, received, open, amount] } }",
                        "tables: {}",
                    ),
                )
            try {
                Files.delete(root.resolve("model/reports/returns_review_q/template.xlsx"))
                shouldThrow<ReportException> {
                    RenderService(model).render(
                        root,
                        "returns_review_q",
                        emptyMap(),
                        ProgramRunner {
                            _,
                            _,
                            _,
                            ->
                            error("must not run")
                        },
                        html = true,
                    )
                }.code shouldBe "table-missing"
            } finally {
                cleanup(root)
            }
        }
        "missing files and unreadable paths have distinct codes" {
            val root = world()
            try {
                val yaml = root.resolve("model/reports/returns_review_q/report.yaml")
                Files.delete(yaml)
                shouldThrow<ReportException> {
                    RenderService(
                        model,
                    ).render(root, "returns_review_q", emptyMap(), runner)
                }.code shouldBe
                    "report-missing"
                Files.createDirectory(yaml)
                shouldThrow<ReportException> {
                    RenderService(
                        model,
                    ).render(root, "returns_review_q", emptyMap(), runner)
                }.code shouldBe
                    "io-error"
            } finally {
                cleanup(root)
            }
        }
    })
