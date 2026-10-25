// SPDX-License-Identifier: Apache-2.0
package org.tatrman.report.cli

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.tatrman.ttr.metadata.MetadataLoader
import org.tatrman.ttr.metadata.source.FileBasedSource
import org.tatrman.ttr.metadata.source.LocalFsStorage
import java.nio.file.Files

class DateColumnSpec :
    StringSpec({
        for (lane in listOf("er", "db")) {
            "$lane declared dates survive aliases and saved-query passthrough without retyping expressions" {
                val root = Files.createTempDirectory("report-date-$lane")
                try {
                    Files.writeString(root.resolve("modeler.toml"), "[language]\npreferred = \"en\"\n")
                    val dir = Files.createDirectory(root.resolve("model"))
                    val subject = if (lane == "er") "shipment" else "db.dbo.shipment"
                    val definition =
                        if (lane == "er") {
                            """
                            model er
                            def entity shipment {
                                attributes: [
                                    def attribute shipped { type: date, optional: true }
                                    def attribute founded { type: date, optional: true }
                                    def attribute recorded { type: datetime, optional: true }
                                ]
                            }
                            """.trimIndent()
                        } else {
                            """
                            model db schema dbo
                            def table shipment {
                                columns: [
                                    def column shipped { type: date, optional: true }
                                    def column founded { type: date, optional: true }
                                    def column recorded { type: datetime, optional: true }
                                ]
                            }
                            """.trimIndent()
                        }
                    Files.writeString(dir.resolve("shipment.ttrm"), definition)
                    Files.writeString(
                        dir.resolve("queries.ttrm"),
                        """
                        model $lane ${if (lane == "db") "schema query" else ""}
                        def query direct { sourceText: "SELECT shipped AS shipped_on, recorded FROM $subject" }
                        def query wrapped { sourceText: "SELECT * FROM obj.query.direct" }
                        def query computed {
                            sourceText: "SELECT DATEADD(hour, 1, shipped) AS stamp, COALESCE(shipped, recorded) AS mixed, CAST(recorded AS DATE) AS cast_day FROM $subject"
                        }
                        def query grouped {
                            sourceText: "SELECT shipped AS shipped_on, MAX(shipped) AS latest FROM $subject GROUP BY shipped"
                        }
                        def query semi { sourceText: "SELECT a.shipped, a.recorded FROM $subject a WHERE EXISTS (SELECT 1 FROM $subject b WHERE a.recorded = b.recorded)" }
                        def query anti { sourceText: "SELECT a.shipped, a.recorded FROM $subject a WHERE NOT EXISTS (SELECT 1 FROM $subject b WHERE a.recorded = b.recorded)" }
                        def query multi_keys { sourceText: "SELECT recorded, shipped, COUNT(*) AS n FROM $subject GROUP BY shipped, recorded" }
                        def query join_grouped { sourceText: "SELECT a.recorded, b.founded, MAX(a.shipped) AS latest, COUNT(*) AS n FROM $subject a LEFT JOIN $subject b ON a.recorded = b.recorded GROUP BY b.founded, a.recorded" }
                        def query empty_plan { sourceText: "SELECT shipped FROM $subject WHERE 1 = 0" }
                        def query null_union { sourceText: "SELECT shipped AS d FROM $subject UNION ALL SELECT NULL AS d FROM $subject" }
                        def query left_side { sourceText: "SELECT a.shipped AS a, b.shipped AS b FROM $subject a LEFT JOIN $subject b ON a.recorded = b.recorded" }
                        def query union_now { sourceText: "SELECT shipped AS d FROM $subject UNION ALL SELECT CURRENT_TIMESTAMP AS d FROM $subject" }
                        def query union_lit { sourceText: "SELECT shipped AS d FROM $subject UNION ALL SELECT CAST('2026-10-01 14:37:12' AS TIMESTAMP) AS d FROM $subject" }
                        def query union_same { sourceText: "SELECT shipped AS d FROM $subject UNION SELECT shipped AS d FROM $subject WHERE recorded IS NULL" }
                        def query union_dates { sourceText: "SELECT shipped AS d FROM $subject UNION ALL SELECT founded AS d FROM $subject" }
                        def query minmax { sourceText: "SELECT MIN(shipped) AS lo, MAX(shipped) AS hi, COUNT(*) AS n FROM $subject" }
                        def query sub { sourceText: "SELECT x.shipped FROM (SELECT DISTINCT shipped FROM $subject WHERE recorded IS NOT NULL) x ORDER BY x.shipped" }
                        def query mixed_union {
                            sourceText: "SELECT shipped AS shipped_on FROM $subject UNION ALL SELECT recorded AS shipped_on FROM $subject"
                        }
                        """.trimIndent(),
                    )
                    val model =
                        requireNotNull(
                            MetadataLoader(FileBasedSource("dates", 100, LocalFsStorage("dates", dir))).load().model,
                        )
                    val lookup = SnapshotModelLookup(model)
                    lookup.outputTypes("$lane.query.direct") shouldBe
                        mapOf("shipped_on" to "DATE", "recorded" to "TIMESTAMP")
                    lookup.outputTypes("$lane.query.wrapped") shouldBe
                        mapOf("shipped_on" to "DATE", "recorded" to "TIMESTAMP")
                    lookup.outputTypes("$lane.query.computed") shouldBe
                        mapOf("stamp" to "TIMESTAMP", "mixed" to "TIMESTAMP", "cast_day" to "DATE")
                    lookup.outputTypes("$lane.query.mixed_union") shouldBe mapOf("shipped_on" to "TIMESTAMP")
                    lookup.outputTypes("$lane.query.grouped") shouldBe
                        mapOf("shipped_on" to "DATE", "latest" to "DATE")
                    lookup.outputTypes("$lane.query.left_side") shouldBe mapOf("a" to "DATE", "b" to "DATE")
                    lookup.outputTypes("$lane.query.union_now") shouldBe mapOf("d" to "TIMESTAMP")
                    lookup.outputTypes("$lane.query.union_lit") shouldBe mapOf("d" to "TIMESTAMP")
                    lookup.outputTypes("$lane.query.union_same") shouldBe mapOf("d" to "DATE")
                    lookup.outputTypes("$lane.query.union_dates") shouldBe mapOf("d" to "DATE")
                    lookup.outputTypes("$lane.query.minmax") shouldBe
                        mapOf("lo" to "DATE", "hi" to "DATE", "n" to "BIGINT")
                    lookup.outputTypes("$lane.query.sub") shouldBe mapOf("shipped" to "DATE")
                    lookup.outputTypes("$lane.query.semi") shouldBe
                        mapOf("shipped" to "DATE", "recorded" to "TIMESTAMP")
                    lookup.outputTypes("$lane.query.anti") shouldBe
                        mapOf("shipped" to "DATE", "recorded" to "TIMESTAMP")
                    lookup.outputTypes("$lane.query.multi_keys") shouldBe
                        mapOf("recorded" to "TIMESTAMP", "shipped" to "DATE", "n" to "BIGINT")
                    lookup.outputTypes("$lane.query.join_grouped") shouldBe
                        mapOf("recorded" to "TIMESTAMP", "founded" to "DATE", "latest" to "DATE", "n" to "BIGINT")
                    lookup.outputTypes("$lane.query.empty_plan") shouldBe mapOf("shipped" to "TIMESTAMP")
                    lookup.outputTypes("$lane.query.null_union") shouldBe mapOf("d" to "TIMESTAMP")
                    val timed =
                        runReportCommand(
                            listOf(
                                "new",
                                root.toString(),
                                "--from",
                                "$lane.query.union_now",
                                "--id",
                                "times",
                                "--title",
                                "Times",
                            ),
                        )
                    timed.exitCode shouldBe 0
                    val timedCsv = Files.writeString(root.resolve("times.csv"), "d\n2026-10-01\n2026-10-01T14:37:12Z\n")
                    val timedOut = root.resolve("times.xlsx")
                    runReportCommand(
                        listOf(
                            "render",
                            root.toString(),
                            "times",
                            "--rows",
                            "ROWS=$timedCsv",
                            "--out",
                            timedOut.toString(),
                        ),
                    ).exitCode shouldBe 0
                    XSSFWorkbook(Files.newInputStream(timedOut)).use { book ->
                        val formatter =
                            org.apache.poi.ss.usermodel
                                .DataFormatter(java.util.Locale.US)
                        formatter.formatCellValue(book.getSheetAt(0).getRow(4).getCell(0)) shouldBe
                            "2026-10-01 14:37:12"
                    }
                    val result =
                        runReportCommand(
                            listOf(
                                "new",
                                root.toString(),
                                "--from",
                                "$lane.query.wrapped",
                                "--id",
                                "dates",
                                "--title",
                                "Dates",
                            ),
                        )
                    result.exitCode shouldBe 0
                    XSSFWorkbook(Files.newInputStream(dir.resolve("reports/dates/template.xlsx"))).use { book ->
                        val marker = book.getSheetAt(0).getRow(3)
                        marker.getCell(0).cellStyle.dataFormatString shouldBe "yyyy-mm-dd"
                        marker.getCell(1).cellStyle.dataFormatString shouldBe "yyyy-mm-dd hh:mm:ss"
                    }
                    val csv =
                        Files.writeString(
                            root.resolve("rows.csv"),
                            "shipped_on,recorded\n2026-10-01,2026-10-01T13:14:15Z\n",
                        )
                    val out = root.resolve("dates.xlsx")
                    runReportCommand(
                        listOf("render", root.toString(), "dates", "--rows", "ROWS=$csv", "--out", out.toString()),
                    ).exitCode shouldBe 0
                    XSSFWorkbook(Files.newInputStream(out)).use { book ->
                        val row = book.getSheetAt(0).getRow(3)
                        val formatter =
                            org.apache.poi.ss.usermodel
                                .DataFormatter(java.util.Locale.US)
                        row.getCell(0).cellType shouldBe org.apache.poi.ss.usermodel.CellType.NUMERIC
                        formatter.formatCellValue(row.getCell(0)) shouldBe "2026-10-01"
                        formatter.formatCellValue(row.getCell(1)) shouldBe "2026-10-01 13:14:15"
                    }
                } finally {
                    root.toFile().deleteRecursively()
                }
            }
        }
    })
