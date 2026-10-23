// SPDX-License-Identifier: Apache-2.0
package org.tatrman.report

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

class TemplateGeneratorSpec :
    StringSpec({
        val columns = listOf("category", "requests", "received", "open", "amount")
        val title = mapOf("en" to "Returns review, this quarter", "cs" to "Přehled reklamací, toto čtvrtletí")

        fun demo() =
            TemplateGenerator.generate(
                "er.query.report_returns_review_q",
                columns,
                id = "returns_review_q",
                title = title,
                roles = listOf("kantheon-role-finance"),
            )

        "the C-8 demo declaration is an exact byte golden and template is deterministic" {
            val pair = demo()
            pair.yaml shouldBe requireNotNull(javaClass.getResource("/generator/report.yaml")).readText()
            pair.warnings shouldBe emptyList()
            pair.xlsx.toList() shouldBe demo().xlsx.toList()
            pair.xlsx.sha256() shouldBe "72eb9de80820196aedd4e0a5c68f469b8accb06b837128b873537e3e7003129d"
            ReportYaml
                .read(pair.yaml, "returns_review_q")
                .sheets
                .single()
                .tables["ROWS"]!!
                .columns shouldBe columns
            XSSFWorkbook(ByteArrayInputStream(pair.xlsx)).use { wb ->
                wb.numberOfSheets shouldBe 1
                val sheet = wb.getSheet("Summary")
                sheet.getRow(0).getCell(0).stringCellValue shouldBe "{{title}}"
                sheet.getRow(1).getCell(0).stringCellValue shouldBe "{{as_of}}"
                sheet.getRow(3).getCell(0).stringCellValue shouldBe
                    "{{table:ROWS|category,requests,received,open,amount}}"
                val font =
                    wb.getFontAt(
                        sheet
                            .getRow(0)
                            .getCell(0)
                            .cellStyle.fontIndex,
                    )
                font.bold shouldBe true
                font.fontHeightInPoints shouldBe 14
                wb.getFontAt(0).fontName shouldBe "Calibri"
                wb.getFontAt(0).fontHeightInPoints shouldBe 11
                sheet.getColumnWidth(0) shouldBe 10 * 256
            }
            ZipInputStream(ByteArrayInputStream(pair.xlsx)).use { zip ->
                while (true) {
                    val part = zip.nextEntry ?: break
                    if (part.name == "docProps/core.xml") {
                        val xml = zip.readBytes().toString(Charsets.UTF_8)
                        xml.contains("dcterms:created") shouldBe false
                        xml.contains("dcterms:modified") shouldBe false
                    }
                }
            }
        }
        "DB declared types map to report kinds and unknown types emit a warning" {
            val params =
                listOf("text", "int", "decimal(18,2)", "boolean", "timestamp", "strange").mapIndexed { i, type ->
                    QueryParameter("p$i", type)
                }
            val pair =
                TemplateGenerator.generate(
                    "db.query.search",
                    listOf("label"),
                    params,
                    "search",
                    mapOf(
                        "en" to "Search",
                    ),
                )
            val spec = ReportYaml.read(pair.yaml, "search")
            spec.params.map { it.kind } shouldBe
                listOf(
                    ParamKind.TEXT,
                    ParamKind.INT,
                    ParamKind.FLOAT,
                    ParamKind.BOOL,
                    ParamKind.DATETIME,
                    ParamKind.TEXT,
                )
            spec.params.all { it.required } shouldBe true
            spec.roles shouldBe emptyList()
            pair.warnings.map { it.code } shouldBe listOf("parameter-type-unknown")
        }
        "ER parameters refuse before asking for query output" {
            val model =
                object : QueryModelLookup {
                    override fun programExists(qname: String) = true

                    override fun outputColumns(qname: String): List<String> = error("must not compile")

                    override fun declaredParameters(qname: String) = listOf(QueryParameter("x", "text"))
                }
            shouldThrow<ReportException> {
                TemplateGenerator.fromQuery(model, "er.query.x", "x", mapOf("en" to "X"))
            }.code shouldBe "query-has-parameters"
        }
        "snapshot and Veles adapters use the same public generation entry point" {
            val model =
                object : QueryModelLookup {
                    override fun programExists(qname: String) = qname == "db.query.x"

                    override fun outputColumns(qname: String) = listOf("label")

                    override fun declaredParameters(qname: String) = listOf(QueryParameter("needle", "text"))
                }
            val pair = TemplateGenerator.fromQuery(model, "db.query.x", "x", mapOf("en" to "X"))
            ReportYaml
                .read(pair.yaml, "x")
                .params
                .single()
                .name shouldBe "needle"
            shouldThrow<ReportException> {
                TemplateGenerator.fromQuery(
                    model,
                    "db.query.absent",
                    "x",
                    mapOf("en" to "X"),
                )
            }.code shouldBe "program-missing"
        }
        "quoted titles and unusual YAML column names round trip without injection" {
            val values = mapOf("cs" to "Přehled", "en" to "A \"quote\"\n\tline \\ path")
            val pair =
                TemplateGenerator.generate(
                    "db.query.x",
                    listOf("yes", "2026-10-23", "a: b"),
                    id = "x",
                    title = values,
                )
            val spec = ReportYaml.read(pair.yaml, "x")
            spec.title shouldBe values
            spec.sheets
                .single()
                .tables["ROWS"]!!
                .columns shouldBe listOf("yes", "2026-10-23", "a: b")
            pair.yaml shouldBe
                TemplateGenerator
                    .generate(
                        "db.query.x",
                        listOf("yes", "2026-10-23", "a: b"),
                        id = "x",
                        title = values.entries.reversed().associate { it.toPair() },
                    ).yaml
        }
        "invalid ids empty or duplicate columns and unrepresentable anchors refuse" {
            for (cols in listOf(
                emptyList(),
                listOf("x", "x"),
                listOf("a,b"),
                listOf(" x"),
                listOf("{{x}}"),
                listOf("x".repeat(32768)),
            )) {
                shouldThrow<ReportException> { TemplateGenerator.generate("er.query.x", cols, id = "x", title = title) }
                    .code shouldBe "generator-invalid"
            }
            shouldThrow<ReportException> {
                TemplateGenerator.generate(
                    "er.query.x",
                    columns,
                    id = "../x",
                    title = title,
                )
            }.code shouldBe "report-invalid"
        }
    })
