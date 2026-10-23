// SPDX-License-Identifier: Apache-2.0
package org.tatrman.report

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.apache.poi.xssf.usermodel.XSSFRichTextString
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.util.zip.ZipInputStream

class ExitReviewSpec :
    StringSpec({
        "inline strings fill in the persisted XML as well as the POI view" {
            val input =
                workbook {
                    createSheet("Summary").apply {
                        createRow(0).createCell(0).apply {
                            ctCell.t = org.openxmlformats.schemas.spreadsheetml.x2006.main.STCellType.INLINE_STR
                            ctCell.addNewIs().t = "{{title}}"
                        }
                        createRow(1).createCell(0).apply {
                            ctCell.t = org.openxmlformats.schemas.spreadsheetml.x2006.main.STCellType.INLINE_STR
                            ctCell.addNewIs().t = "{{table:ROWS|a}}"
                        }
                    }
                }
            val output =
                XlsxFill.fill(
                    input,
                    ReportData(
                        mapOf("title" to "Done"),
                        mapOf(
                            "ROWS" to Table(listOf("a"), listOf(listOf("filled"))),
                        ),
                    ),
                )
            XSSFWorkbook(ByteArrayInputStream(output)).use { book ->
                book
                    .getSheetAt(0)
                    .getRow(0)
                    .getCell(0)
                    .stringCellValue shouldBe "Done"
                book
                    .getSheetAt(0)
                    .getRow(1)
                    .getCell(0)
                    .stringCellValue shouldBe "filled"
            }
            ZipInputStream(ByteArrayInputStream(output)).use { zip ->
                while (true) {
                    val part = zip.nextEntry ?: break
                    if (part.name.startsWith("xl/worksheets/")) {
                        zip.readBytes().toString(Charsets.UTF_8).contains("{{") shouldBe
                            false
                    }
                }
            }
        }
        "automatic row height stays automatic and explicit height is inherited" {
            for (mode in listOf("auto", "stored-auto", "stored-false", "manual")) {
                val fixed = mode == "manual"
                val input =
                    workbook {
                        createSheet("Summary").createRow(0).apply {
                            when (mode) {
                                "manual" -> heightInPoints = 32f
                                "stored-auto" -> ctRow.ht = 45.0
                                "stored-false" -> {
                                    ctRow.ht = 45.0
                                    ctRow.customHeight = false
                                }
                            }
                            createCell(0).setCellValue("{{table:ROWS}}")
                        }
                    }
                val output =
                    XlsxFill.fill(
                        input,
                        ReportData(
                            tables =
                                mapOf(
                                    "ROWS" to Table(listOf("a"), listOf(listOf("a\nb"), listOf("c"))),
                                ),
                        ),
                    )
                XSSFWorkbook(ByteArrayInputStream(output)).use { book ->
                    for (row in book.getSheetAt(0)) {
                        val stored = (row as org.apache.poi.xssf.usermodel.XSSFRow).ctRow
                        stored.isSetHt shouldBe fixed
                        stored.isSetCustomHeight shouldBe fixed
                        if (fixed) stored.customHeight shouldBe true
                        if (fixed) row.heightInPoints shouldBe 32f
                        row.zeroHeight shouldBe false
                    }
                }
            }
        }
        "untouched rich text retains its runs and fonts" {
            val input =
                workbook {
                    val rich = XSSFRichTextString("Red and bold")
                    rich.applyFont(0, 3, createFont().apply { color = 10 })
                    rich.applyFont(8, 12, createFont().apply { bold = true })
                    createSheet("Summary").createRow(0).createCell(0).setCellValue(rich)
                }
            XSSFWorkbook(ByteArrayInputStream(XlsxFill.fill(input, ReportData()))).use { book ->
                val rich =
                    book
                        .getSheetAt(0)
                        .getRow(0)
                        .getCell(0)
                        .richStringCellValue
                rich.numFormattingRuns() shouldBe 3
                rich.getFontOfFormattingRun(0).color shouldBe 10.toShort()
                rich.getFontOfFormattingRun(2).bold shouldBe true
            }
        }
        "YAML 1.2 plain scalars retain the same values as TypeScript" {
            val base = example().replace("er.query.", "db.query.")
            for ((source, expected) in listOf("NO" to "NO", "0017" to 17, "12:30" to "12:30")) {
                val kind = if (expected is Int) "int" else "text"
                val spec =
                    ReportYaml.read(
                        base.replace("params: []", "params: [{name: x, kind: $kind, default: $source}]"),
                        "returns_review_q",
                    )
                spec.params.single().default shouldBe expected
            }
            for ((kind, value) in listOf(
                "int" to "nope",
                "bool" to "NO",
                "float" to "NaN",
                "datetime" to "12:30",
                "text" to "true",
            )) {
                shouldThrow<ReportException> {
                    ReportYaml.read(
                        base.replace("params: []", "params: [{name: x, kind: $kind, default: $value}]"),
                        "returns_review_q",
                    )
                }.code shouldBe "parameter-invalid"
            }
        }
        "CSV skips physical blank lines but preserves quoted empty fields and embedded newlines" {
            val file = Files.createTempFile("blank-lines", ".csv")
            try {
                Files.writeString(file, "\na\n\r\n\"\"\n\"one\n\ntwo\"\n\n")
                RowsProgramRunner(mapOf("ROWS" to file)).run("db.query.q", emptyMap(), null).rows shouldBe
                    listOf(listOf(""), listOf("one\n\ntwo"))
            } finally {
                Files.deleteIfExists(file)
            }
        }
        "an oversized value has a data error rather than a corrupt-template error" {
            val input = workbook { createSheet("Summary").createRow(0).createCell(0).setCellValue("{{table:ROWS}}") }
            shouldThrow<ReportException> {
                XlsxFill.fill(
                    input,
                    ReportData(
                        tables =
                            mapOf(
                                "ROWS" to Table(listOf("a"), listOf(listOf("x".repeat(32768)))),
                            ),
                    ),
                )
            }.code shouldBe
                "value-too-long"
        }
        "generated YAML escapes every forbidden control character and preserves its text" {
            val title =
                "a" + (listOf(0x7f, 0x80, 0x85, 0x9f, 0xfffe, 0xffff).map { it.toChar() }.joinToString("")) + "z"
            val pair = TemplateGenerator.generate("er.query.q", listOf("a"), id = "q", title = mapOf("en" to title))
            ReportYaml.read(pair.yaml, "q").title["en"] shouldBe title
        }
        "generated typed columns remain numbers while text identifiers keep leading zeroes" {
            val pair =
                TemplateGenerator.generate(
                    "er.query.q",
                    listOf("code", "count", "amount"),
                    id = "q",
                    title =
                        mapOf(
                            "en" to "Q",
                        ),
                    columnTypes = mapOf("code" to "text", "count" to "int", "amount" to "number"),
                )
            val filled =
                XlsxFill.fill(
                    pair.xlsx,
                    ReportData(
                        mapOf("title" to "Q", "as_of" to "2026-10-23"),
                        mapOf(
                            "ROWS" to
                                Table(
                                    listOf("code", "count", "amount"),
                                    listOf(listOf("0017", "12", "1.5"), listOf("0001", "3", "2.5")),
                                ),
                        ),
                    ),
                )
            XSSFWorkbook(ByteArrayInputStream(filled)).use { book ->
                val sheet = book.getSheetAt(0)
                sheet.getRow(3).getCell(0).stringCellValue shouldBe "0017"
                sheet.getRow(3).getCell(1).cellType shouldBe org.apache.poi.ss.usermodel.CellType.NUMERIC
                sheet.getRow(3).getCell(2).numericCellValue shouldBe 1.5
                val sum = sheet.createRow(6).createCell(2).apply { cellFormula = "SUM(C4:C5)" }
                book.creationHelper
                    .createFormulaEvaluator()
                    .evaluate(sum)
                    .numberValue shouldBe 4.0
            }
        }
        "parameter aliases cover number timestamptz smalldatetime and time" {
            val params =
                listOf("number", "timestamptz", "smalldatetime", "time").mapIndexed {
                    i,
                    type,
                    ->
                    QueryParameter("p$i", type)
                }
            val pair = TemplateGenerator.generate("db.query.q", listOf("a"), params, "q", mapOf("en" to "Q"))
            pair.warnings shouldBe emptyList()
            ReportYaml.read(pair.yaml, "q").params.map { it.kind } shouldBe
                listOf(ParamKind.FLOAT, ParamKind.DATETIME, ParamKind.DATETIME, ParamKind.TEXT)
        }
        "DB report parameters match the captured query declarations" {
            val model =
                object : QueryModelLookup {
                    override fun programExists(qname: String) = true

                    override fun outputColumns(qname: String) =
                        listOf("category", "requests", "received", "open", "amount")

                    override fun declaredParameters(qname: String) = listOf(QueryParameter("needle", "text"))
                }
            val yaml =
                example()
                    .replace(
                        "er.query.",
                        "db.query.",
                    ).replace("params: []", "params: [{name: needle, kind: text}]")
            ReportValidator.validate(ReportYaml.read(yaml, "returns_review_q"), model) shouldBe emptyList()
            ReportValidator
                .validate(ReportYaml.read(yaml.replace("needle", "neddle"), "returns_review_q"), model)
                .map {
                    it.code
                }.toSet() shouldBe
                setOf("parameter-invalid", "parameter-missing")
        }
        "generated decimal and date formats match Excel display semantics" {
            val columns = listOf("amount", "day", "stamp")
            val pair =
                TemplateGenerator.generate(
                    "er.query.q",
                    columns,
                    id = "q",
                    title = mapOf("en" to "Q"),
                    columnTypes = mapOf("amount" to "DECIMAL", "day" to " DATE ", "stamp" to "TIMESTAMP"),
                )
            val values = listOf("20", "3", "0", "-20", "1.5", "1.2345")
            val data =
                ReportData(
                    mapOf("title" to "Q", "as_of" to "2026-10-23"),
                    mapOf(
                        "ROWS" to
                            Table(columns, values.map { listOf(it, "2026-10-01", "2026-10-01T10:20:30Z") }),
                    ),
                )
            XSSFWorkbook(ByteArrayInputStream(XlsxFill.fill(pair.xlsx, data))).use { book ->
                val sheet = book.getSheetAt(0)
                val displayed =
                    values.indices.map { i ->
                        val cell = sheet.getRow(3 + i).getCell(0)
                        org.apache.poi.ss.format.CellFormat
                            .getInstance(
                                java.util.Locale.US,
                                cell.cellStyle.dataFormatString,
                            ).apply(cell.numericCellValue)
                            .text
                    }
                displayed shouldBe listOf("20.0", "3.0", "0.0", "-20.0", "1.5", "1.2345")
                val day = sheet.getRow(3).getCell(1)
                val stamp = sheet.getRow(3).getCell(2)
                day.cellStyle.dataFormatString shouldBe "yyyy-mm-dd"
                stamp.cellStyle.dataFormatString shouldBe "yyyy-mm-dd hh:mm:ss"
                val formatter =
                    org.apache.poi.ss.usermodel
                        .DataFormatter(java.util.Locale.US)
                formatter.formatCellValue(day) shouldBe "2026-10-01"
                formatter.formatCellValue(stamp) shouldBe "2026-10-01 10:20:30"
            }
        }
    })
