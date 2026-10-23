// SPDX-License-Identifier: Apache-2.0
package org.tatrman.report

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.apache.poi.ss.usermodel.CellType
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.zip.ZipInputStream

internal fun workbook(block: XSSFWorkbook.() -> Unit): ByteArray =
    XSSFWorkbook().use { wb ->
        wb.apply(block)
        ByteArrayOutputStream().also(wb::write).toByteArray()
    }

internal fun ByteArray.sha256() =
    MessageDigest.getInstance("SHA-256").digest(this).joinToString("") {
        "%02x".format(it)
    }

class XlsxFillSpec :
    StringSpec({
        "scalars substitute; table projection expands in order, preserving headers, styles and footers" {
            val bytes =
                workbook {
                    val sheet = createSheet("Summary")
                    sheet.createRow(0).createCell(0).setCellValue("Report: {{title}} / {{unknown}}")
                    sheet.createRow(1).apply {
                        createCell(0).setCellValue("Amount")
                        createCell(1).setCellValue("Category")
                    }
                    val style = createCellStyle().apply { dataFormat = createDataFormat().getFormat("0.00") }
                    sheet.createRow(2).apply {
                        createCell(0).apply {
                            setCellValue("{{table:ROWS|amount,category}}")
                            cellStyle = style
                        }
                        createCell(1).apply { cellStyle = createCellStyle().apply { wrapText = true } }
                    }
                    sheet.createRow(3).createCell(0).setCellValue("End {{title}}")
                }
            val result =
                XlsxFill.fill(
                    bytes,
                    ReportData(
                        mapOf("title" to "Returns"),
                        mapOf(
                            "ROWS" to
                                Table(listOf("category", "amount"), listOf(listOf("A", "12.50"), listOf("B", "2"))),
                        ),
                    ),
                )
            XSSFWorkbook(ByteArrayInputStream(result)).use { wb ->
                val s = wb.getSheetAt(0)
                s.getRow(0).getCell(0).stringCellValue shouldBe "Report: Returns / "
                s.getRow(1).getCell(0).stringCellValue shouldBe "Amount"
                s.getRow(2).getCell(0).numericCellValue shouldBe 12.5
                s.getRow(3).getCell(0).numericCellValue shouldBe 2.0
                s
                    .getRow(3)
                    .getCell(0)
                    .cellStyle.index shouldBe
                    s
                        .getRow(2)
                        .getCell(0)
                        .cellStyle.index
                s
                    .getRow(3)
                    .getCell(1)
                    .cellStyle.wrapText shouldBe true
                s.getRow(3).getCell(1).stringCellValue shouldBe "B"
                s.getRow(4).getCell(0).stringCellValue shouldBe "End Returns"
            }
        }

        "a missing anchor is explicit, including an unknown table marker" {
            val bytes = workbook { createSheet("Summary").createRow(0).createCell(0).setCellValue("Plain") }
            shouldThrow<ReportException> {
                XlsxFill.fill(
                    bytes,
                    ReportData(
                        tables =
                            mapOf(
                                "ROWS" to Table(listOf("a"), emptyList()),
                            ),
                    ),
                )
            }.code shouldBe
                "anchor-missing"
            shouldThrow<ReportException> {
                XlsxFill.fill(
                    bytes,
                    ReportData(scalars = mapOf("title" to "X")),
                )
            }.code shouldBe
                "anchor-missing"
            val marker =
                workbook { createSheet("Summary").createRow(0).createCell(0).setCellValue("{{table:UNKNOWN|a}}") }
            shouldThrow<ReportException> { XlsxFill.fill(marker, ReportData()) }.code shouldBe "anchor-missing"
        }

        "unknown projected columns refuse even an empty table" {
            val bytes =
                workbook { createSheet("Summary").createRow(0).createCell(0).setCellValue("{{table:ROWS|missing}}") }
            shouldThrow<ReportException> {
                XlsxFill.fill(
                    bytes,
                    ReportData(
                        tables =
                            mapOf(
                                "ROWS" to Table(listOf("a"), emptyList()),
                            ),
                    ),
                )
            }.code shouldBe
                "column-missing"
        }

        "empty tables clear the marker and leave the authored header and footer" {
            val bytes =
                workbook {
                    createSheet("Summary").apply {
                        createRow(0).createCell(0).setCellValue("A")
                        createRow(1).createCell(0).setCellValue("{{table:ROWS|a}}")
                        createRow(2).createCell(0).setCellValue("Footer")
                    }
                }
            XSSFWorkbook(
                ByteArrayInputStream(
                    XlsxFill.fill(
                        bytes,
                        ReportData(
                            tables =
                                mapOf(
                                    "ROWS" to Table(listOf("a"), emptyList()),
                                ),
                        ),
                    ),
                ),
            ).use {
                it
                    .getSheetAt(0)
                    .getRow(0)
                    .getCell(0)
                    .stringCellValue shouldBe "A"
                it
                    .getSheetAt(0)
                    .getRow(1)
                    .getCell(0)
                    .cellType shouldBe CellType.BLANK
                it
                    .getSheetAt(0)
                    .getRow(2)
                    .getCell(0)
                    .stringCellValue shouldBe "Footer"
            }
        }

        "stacked tables shift together; parallel tables on one row are refused" {
            val bytes =
                workbook {
                    createSheet("Summary").apply {
                        createRow(0).createCell(0).setCellValue("{{table:A}}")
                        createRow(1).createCell(0).setCellValue("{{table:B}}")
                    }
                }
            val data =
                ReportData(
                    tables =
                        mapOf(
                            "A" to Table(listOf("a"), listOf(listOf("1"), listOf("2"))),
                            "B" to Table(listOf("b"), listOf(listOf("3"), listOf("4"))),
                        ),
                )
            XSSFWorkbook(ByteArrayInputStream(XlsxFill.fill(bytes, data))).use {
                (0..3).map { r ->
                    it
                        .getSheetAt(0)
                        .getRow(r)
                        .getCell(0)
                        .stringCellValue
                } shouldBe
                    listOf("1", "2", "3", "4")
            }
            val parallel =
                workbook {
                    createSheet("Summary").createRow(0).apply {
                        createCell(0).setCellValue("{{table:A}}")
                        createCell(2).setCellValue("{{table:B}}")
                    }
                }
            shouldThrow<ReportException> { XlsxFill.fill(parallel, data) }.code shouldBe "template-invalid"
        }

        "untyped numeric-looking strings and formulas from data remain text; null becomes blank" {
            val bytes = workbook { createSheet("Summary").createRow(0).createCell(0).setCellValue("{{table:ROWS}}") }
            XSSFWorkbook(
                ByteArrayInputStream(
                    XlsxFill.fill(
                        bytes,
                        ReportData(
                            tables =
                                mapOf(
                                    "ROWS" to Table(listOf("a", "b", "c"), listOf(listOf("0012", "=SUM(A1)", null))),
                                ),
                        ),
                    ),
                ),
            ).use {
                val row = it.getSheetAt(0).getRow(0)
                row.getCell(0).stringCellValue shouldBe "0012"
                row.getCell(1).cellType shouldBe CellType.STRING
                row.getCell(2).cellType shouldBe CellType.BLANK
            }
        }

        "date columns are typed while a missing styled cell stays text" {
            val bytes =
                workbook {
                    createSheet("Summary").createRow(0).apply {
                        createCell(0).apply {
                            setCellValue("{{table:ROWS}}")
                            cellStyle =
                                createCellStyle().apply { dataFormat = createDataFormat().getFormat("yyyy-mm-dd") }
                        }
                    }
                }
            val data =
                ReportData(
                    tables =
                        mapOf(
                            "ROWS" to Table(listOf("date", "code"), listOf(listOf("2026-06-30", "0012"))),
                        ),
                )
            XSSFWorkbook(ByteArrayInputStream(XlsxFill.fill(bytes, data))).use {
                val row = it.getSheetAt(0).getRow(0)
                row.getCell(0).localDateTimeCellValue.toString() shouldBe "2026-06-30T00:00"
                row.getCell(1).stringCellValue shouldBe "0012"
            }
        }

        "raw cell data is not interpreted as a second placeholder pass" {
            val bytes = workbook { createSheet("Summary").createRow(0).createCell(0).setCellValue("{{table:ROWS}}") }
            val data = ReportData(tables = mapOf("ROWS" to Table(listOf("text"), listOf(listOf("{{unknown}}")))))
            XSSFWorkbook(ByteArrayInputStream(XlsxFill.fill(bytes, data))).use {
                it
                    .getSheetAt(0)
                    .getRow(0)
                    .getCell(0)
                    .stringCellValue shouldBe "{{unknown}}"
            }
        }

        "independent sheets fill and malformed partial markers refuse" {
            val bytes =
                workbook {
                    createSheet("One").createRow(0).createCell(0).setCellValue("{{table:ROWS}}")
                    createSheet("Two").createRow(0).createCell(0).setCellValue("{{table:ROWS}}")
                }
            val data = ReportData(tables = mapOf("ROWS" to Table(listOf("a"), listOf(listOf("value")))))
            XSSFWorkbook(ByteArrayInputStream(XlsxFill.fill(bytes, data))).use {
                it
                    .getSheetAt(0)
                    .getRow(0)
                    .getCell(0)
                    .stringCellValue shouldBe "value"
                it
                    .getSheetAt(1)
                    .getRow(0)
                    .getCell(0)
                    .stringCellValue shouldBe "value"
            }
            val bad = workbook { createSheet("Bad").createRow(0).createCell(0).setCellValue("prefix {{table:ROWS}}") }
            shouldThrow<ReportException> { XlsxFill.fill(bad, data) }.code shouldBe "template-invalid"
        }

        "ZIP bytes are independent of the host timezone and volatile input properties" {
            val original = java.util.TimeZone.getDefault()
            try {
                val hashes =
                    listOf("UTC", "Pacific/Honolulu", "Asia/Tokyo").mapIndexed { index, zone ->
                        java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone(zone))
                        val bytes =
                            workbook {
                                properties.coreProperties.apply {
                                    creator = "runner-$zone"
                                    setCreated(java.util.Optional.of(java.util.Date(index * 86_400_000L)))
                                    setModified(java.util.Optional.of(java.util.Date(index * 86_400_000L)))
                                }
                                createSheet("Summary").createRow(0).createCell(0).setCellValue("{{title}}")
                            }
                        val filled = XlsxFill.fill(bytes, ReportData(scalars = mapOf("title" to "Returns")))
                        filled.sha256()
                    }
                hashes.distinct().size shouldBe 1
            } finally {
                java.util.TimeZone.setDefault(original)
            }
        }

        "fill is byte deterministic, strips volatile properties and fixes ZIP order and time" {
            val bytes =
                workbook {
                    properties.coreProperties.creator = "runner"
                    properties.extendedProperties.underlyingProperties.application = "runner"
                    createSheet("Summary").createRow(0).createCell(0).setCellValue("{{title}}")
                }
            val data = ReportData(scalars = mapOf("title" to "Returns"))
            val first = XlsxFill.fill(bytes, data)
            first.sha256() shouldBe XlsxFill.fill(bytes, data).sha256()
            first.sha256() shouldBe "03f110ebee4f96e1fe036e188147d1f290859f370de40c57df2c065c0f9af1c0"
            val entries = linkedMapOf<String, Pair<Long, String>>()
            ZipInputStream(ByteArrayInputStream(first)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    (entry.extra?.size ?: 0) shouldBe 0
                    entries[entry.name] = entry.time to zip.readBytes().toString(Charsets.UTF_8)
                }
            }
            entries.keys.toList() shouldBe entries.keys.sorted()
            entries.values
                .map { it.first }
                .distinct()
                .size shouldBe 1
            entries.getValue("docProps/core.xml").second.apply {
                shouldNotContain("created")
                shouldNotContain("modified")
                shouldNotContain("runner")
            }
            entries.getValue("docProps/app.xml").second shouldNotContain "runner"
        }
    })
