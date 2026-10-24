// SPDX-License-Identifier: Apache-2.0
package org.tatrman.report

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe

internal fun example(): String =
    requireNotNull(ReportSpec::class.java.getResourceAsStream("/reports/returns_review_q.yaml")).reader().readText()

class ReportYamlSpec :
    StringSpec({
        val model =
            object : ModelLookup {
                override fun programExists(qname: String) = qname == "er.query.report_returns_review_q"

                override fun outputColumns(qname: String) = listOf("category", "requests", "received", "open", "amount")
            }

        "the demo contract validates and history defaults to 90d" {
            val spec = ReportYaml.read(example(), "returns_review_q")
            spec.title["cs"] shouldBe "Přehled reklamací, toto čtvrtletí"
            spec.history.keep shouldBe "90d"
            ReportValidator.validate(spec, model) shouldBe emptyList()
            ReportYaml.read(example().replace("history: { keep: 90d }\n", ""), "returns_review_q").history.keep shouldBe
                "90d"
        }

        "folder and preferred language are validated" {
            shouldThrow<ReportException> { ReportYaml.read(example(), "other") }.code shouldBe "id-mismatch"
            shouldThrow<ReportException> {
                ReportYaml.read(
                    example().replace("cs: \"Přehled reklamací, toto čtvrtletí\"", "de: Deutsch"),
                    "returns_review_q",
                    "cs",
                )
            }.code shouldBe "title-missing"
        }

        "unknown keys and invalid shapes at every nesting level are refused" {
            val bad =
                listOf(
                    example() + "typo: true\n",
                    example().replace("params: []", "params: [{name: x, kind: text, typo: true}]"),
                    example().replace("params: []", "params: [{name: x, kind: wrong}]"),
                    example().replace(
                        "params: []",
                        "params: [{name: x, kind: entity, picker: {program: db.query.x, typo: true}}]",
                    ),
                    example().replace("sheet: Summary", "sheet: Summary\n    typo: true"),
                    example().replace(
                        "columns: [category, requests, received, open, amount]",
                        "columns: [category], typo: true",
                    ),
                    example().replace("keep: 90d", "keep: 90d, typo: true"),
                    example().replace("keep: 90d", "keep: forever"),
                    example().replace("version: 1", "version: 2"),
                    example().replace("refresh: daily", "refresh: weekly"),
                    example().replace("columns: [category, requests, received, open, amount]", "columns: []"),
                    example().replace(
                        "columns: [category, requests, received, open, amount]",
                        "columns: [category], limit: 0",
                    ),
                    example().replace("id: returns_review_q", "id: Bad-ID"),
                    example().replace("program: er.query.report_returns_review_q", "program: er.entity.x"),
                    example().replace("params: []\n", ""),
                    example().replace("sheets:\n", "sheets: []\nignored:\n"),
                )
            bad.forEach { yaml ->
                shouldThrow<ReportException> { ReportYaml.read(yaml, "returns_review_q") }.code shouldBe
                    "report-invalid"
            }
        }

        "logical queries refuse params but physical queries allow every parameter kind" {
            val withParams = example().replace("params: []", "params: [{name: x, kind: text}]")
            shouldThrow<ReportException> { ReportYaml.read(withParams, "returns_review_q") }.code shouldBe
                "report-invalid"
            val db = withParams.replace("er.query.report_returns_review_q", "db.query.report_returns_review_q")
            listOf("text", "int", "float", "bool", "datetime", "entity").forEach { kind ->
                ReportYaml
                    .read(
                        db.replace("kind: text", "kind: $kind"),
                        "returns_review_q",
                    ).params
                    .single()
                    .kind.wire shouldBe
                    kind
            }
            val detailed =
                db.replace(
                    "kind: text",
                    "kind: int, required: true, default: 4, label: {en: Count}, " +
                        "picker: {program: db.query.options, label: name, value: id}",
                )
            ReportYaml
                .read(detailed, "returns_review_q")
                .params
                .single()
                .default shouldBe 4
        }

        "duplicate YAML keys, unsafe tags and multiple documents are refused" {
            listOf(
                example() + "id: other\n",
                example() + "---\nid: other\n",
                "!!java.net.URL [https://example.com]",
            ).forEach {
                shouldThrow<ReportException> { ReportYaml.read(it, "returns_review_q") }.code shouldBe "report-invalid"
            }
        }

        "model validation checks existence and column subsets by name" {
            val spec = ReportYaml.read(example(), "returns_review_q")
            ReportValidator.validate(spec.copy(program = "er.query.missing"), model).map { it.code } shouldContain
                "program-missing"
            val bad =
                spec.copy(
                    sheets = listOf(spec.sheets.single().copy(tables = mapOf("ROWS" to TableSpec(listOf("typo"))))),
                )
            ReportValidator.validate(bad, model).map { it.code } shouldContain "column-missing"
        }

        "datetime defaults keep their authored scalar text" {
            val yaml =
                example()
                    .replace("er.query.", "db.query.")
                    .replace("params: []", "params: [{name: as_of, kind: datetime, default: 2026-06-30T00:00:00Z}]")
            ReportYaml
                .read(yaml, "returns_review_q")
                .params
                .single()
                .default shouldBe "2026-06-30T00:00:00Z"
        }

        "out-of-range limits and nonscalar defaults are refused before typed conversion" {
            listOf(
                example().replace(
                    "columns: [category, requests, received, open, amount]",
                    "columns: [category], limit: 2147483648",
                ),
                example()
                    .replace(
                        "er.query.",
                        "db.query.",
                    ).replace("params: []", "params: [{name: x, kind: text, default: {nested: value}}]"),
            ).forEach { yaml ->
                shouldThrow<ReportException> { ReportYaml.read(yaml, "returns_review_q") }.code shouldBe
                    "report-invalid"
            }
        }

        "duplicate parameter and sheet names are refused" {
            val db = example().replace("er.query.", "db.query.")
            shouldThrow<ReportException> {
                ReportYaml.read(
                    db.replace("params: []", "params: [{name: x, kind: text}, {name: x, kind: int}]"),
                    "returns_review_q",
                )
            }.code shouldBe "report-invalid"
            shouldThrow<ReportException> {
                ReportYaml.read(
                    example().replace("history:", "  - sheet: Summary\n    tables: {}\nhistory:"),
                    "returns_review_q",
                )
            }.code shouldBe "report-invalid"
        }
    })
