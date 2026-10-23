// SPDX-License-Identifier: Apache-2.0
package org.tatrman.report.cli

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class RenderCommandSpec :
    StringSpec({
        val manifest = requireNotNull(javaClass.getResource("/worlds/reports/modeler.toml"))
        val world = Path.of(manifest.toURI()).parent
        val clock = Clock.fixed(Instant.parse("2026-10-23T12:00:00Z"), ZoneOffset.UTC)

        fun args() =
            listOf("render", world.toString(), "returns_review_q", "--rows", "ROWS=${world.resolve("rows.csv")}")

        "CLI loads the non-strict snapshot and renders the exact HTML fixture" {
            val result = runReportCommand(args() + "--html", clock)
            result.stderr shouldBe ""
            result.exitCode shouldBe 0
            result.stdout shouldBe Files.readString(world.resolve("expected.html"))
        }
        "CLI workbook equals the golden bytes and repeat render is identical" {
            val out = Files.createTempFile("report-cli", ".xlsx")
            try {
                val result = runReportCommand(args() + listOf("--out", out.toString()), clock)
                result.stderr shouldBe ""
                result.exitCode shouldBe 0
                XSSFWorkbook(ByteArrayInputStream(Files.readAllBytes(out))).use { book ->
                    val sheet = book.getSheetAt(0)
                    sheet.getRow(0).getCell(0).stringCellValue shouldBe "Returns review, this quarter"
                    sheet.getRow(3).getCell(1).cellType shouldBe org.apache.poi.ss.usermodel.CellType.NUMERIC
                    sheet.getRow(3).getCell(4).numericCellValue shouldBe 1.5
                    val amount = sheet.getRow(4).getCell(4)
                    org.apache.poi.ss.format.CellFormat
                        .getInstance(
                            java.util.Locale.US,
                            amount.cellStyle.dataFormatString,
                        ).apply(amount.numericCellValue)
                        .text shouldBe "20.0"
                    for (row in sheet) {
                        (row as org.apache.poi.xssf.usermodel.XSSFRow).ctRow.isSetHt shouldBe false
                        row.zeroHeight shouldBe false
                    }
                }
                val bytes = Files.readAllBytes(out).toList()
                bytes shouldBe Files.readAllBytes(world.resolve("expected.xlsx")).toList()
                runReportCommand(args() + listOf("--out", out.toString()), clock).exitCode shouldBe 0
                Files.readAllBytes(out).toList() shouldBe bytes
            } finally {
                Files.delete(out)
            }
        }
        "usage errors are 64 and help is 0" {
            runReportCommand(listOf("--help")).exitCode shouldBe 0
            runReportCommand(listOf("render", "--help")).exitCode shouldBe 0
            listOf(
                args() + "--htlm",
                args() + listOf("--door", "localhost:7262"),
                args() + listOf("--rows", "ROWS=again.csv"),
                args() + listOf("--param", "broken"),
                args() + listOf("--html", "--out", "out.xlsx"),
                listOf("render", world.toString(), "returns_review_q"),
            ).forEach { runReportCommand(it).exitCode shouldBe 64 }
        }
        "a render refusal leaves existing output intact" {
            val out = Files.createTempFile("report-existing", ".xlsx")
            Files.writeString(out, "old workbook")
            try {
                val result = runReportCommand(args() + listOf("--param", "unknown=x", "--out", out.toString()), clock)
                result.exitCode shouldBe 1
                result.stderr shouldContain "parameter-invalid"
                Files.readString(out) shouldBe "old workbook"
            } finally {
                Files.delete(out)
            }
        }
        "missing manifest has a useful coded failure" {
            val empty = Files.createTempDirectory("report-empty")
            try {
                val result = runReportCommand(listOf("render", empty.toString(), "x", "--rows", "ROWS=x.csv", "--html"))
                result.exitCode shouldBe 1
                result.stderr shouldContain "manifest-missing"
            } finally {
                Files.delete(empty)
            }
        }
        "render preserves shared file permissions" {
            val root = Files.createTempDirectory("report-permissions")
            try {
                if (Files.getFileStore(root).supportsFileAttributeView("posix")) {
                    val target = Files.writeString(root.resolve("shared.xlsx"), "old")
                    val mode =
                        java.nio.file.attribute.PosixFilePermissions
                            .fromString("rw-rw-r--")
                    Files.setPosixFilePermissions(target, mode)
                    runReportCommand(args() + listOf("--out", target.toString()), clock).exitCode shouldBe 0
                    Files.getPosixFilePermissions(target) shouldBe mode
                    val fresh = root.resolve("fresh.xlsx")
                    runReportCommand(args() + listOf("--out", fresh.toString()), clock).exitCode shouldBe 0
                    // Compare to an ordinary new file under the process's actual umask.
                    val normal = Files.writeString(root.resolve("normal.txt"), "new")
                    Files.getPosixFilePermissions(fresh) shouldBe Files.getPosixFilePermissions(normal)
                }
            } finally {
                root.toFile().deleteRecursively()
            }
        }
    })
