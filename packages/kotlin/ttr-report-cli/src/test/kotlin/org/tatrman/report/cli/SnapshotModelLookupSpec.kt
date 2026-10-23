// SPDX-License-Identifier: Apache-2.0
package org.tatrman.report.cli

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.tatrman.report.ReportException
import org.tatrman.ttr.metadata.MetadataLoader
import org.tatrman.ttr.metadata.source.FileBasedSource
import org.tatrman.ttr.metadata.source.LocalFsStorage
import java.nio.file.Files

class SnapshotModelLookupSpec :
    StringSpec({
        fun lookup(
            er: String,
            db: String = "",
        ): SnapshotModelLookup {
            val root = Files.createTempDirectory("report-model")
            try {
                Files.writeString(
                    root.resolve("er.ttrm"),
                    """
                    package hartland
                    model er
                    def entity item {
                        attributes: [
                            def attribute id { type: int }
                            def attribute name { type: text }
                            def attribute amount { type: decimal }
                        ]
                    }
                    $er
                    """.trimIndent(),
                )
                if (db.isNotEmpty()) {
                    Files.writeString(root.resolve("db.ttrm"), db)
                    Files.writeString(
                        root.resolve("table.ttrm"),
                        "model db schema dbo\ndef table item { columns: [def column name { type: text }] }",
                    )
                }
                val model =
                    requireNotNull(
                        MetadataLoader(FileBasedSource("test", 100, LocalFsStorage("test", root))).load().model,
                    )
                return SnapshotModelLookup(model)
            } finally {
                root.toFile().deleteRecursively()
            }
        }
        "aliases aggregates stars and hidden sort keys use the compiler output shape" {
            val model =
                lookup(
                    """
                    def query renamed {
                        sourceText: "SELECT name AS category, SUM(amount) AS total FROM item GROUP BY name ORDER BY LENGTH(name)"
                    }
                    def query all_items { sourceText: "SELECT * FROM item" }
                    """.trimIndent(),
                )
            model.outputColumns("er.query.renamed") shouldBe listOf("category", "total")
            model.outputColumns("er.query.all_items") shouldBe listOf("id", "name", "amount")
            model.programExists("er.query.absent") shouldBe false
        }
        "saved-query references compile recursively from the same captured snapshot" {
            val model =
                lookup(
                    """
                    def query base { sourceText: "SELECT name AS category, amount FROM item" }
                    def query wrapped { sourceText: "SELECT category FROM obj.query.base" }
                    """.trimIndent(),
                )
            model.outputColumns("er.query.wrapped") shouldBe listOf("category")
        }
        "physical queries bind declared params at compile time without running SQL" {
            val model =
                lookup(
                    "",
                    """
                    model db schema query
                    def query search_items {
                        parameters: [{ name: needle, type: text }]
                        sourceText: "SELECT name AS label FROM db.dbo.item WHERE name = {needle}"
                    }
                    """.trimIndent(),
                )
            model.programExists("db.query.search_items") shouldBe true
            model.outputColumns("db.query.search_items") shouldBe listOf("label")
        }
        "invalid SQL or unknown columns refuse rather than guessing an output list" {
            val model = lookup("def query bad { sourceText: \"SELECT absent FROM item\" }")
            shouldThrow<ReportException> { model.outputColumns("er.query.bad") }.code shouldBe "program-invalid"
        }
        "cyclic saved-query references fail with a coded diagnostic" {
            val model =
                lookup(
                    """
                    def query a { sourceText: "SELECT * FROM obj.query.b" }
                    def query b { sourceText: "SELECT * FROM obj.query.a" }
                    """.trimIndent(),
                )
            shouldThrow<ReportException> { model.outputColumns("er.query.a") }.code shouldBe "program-invalid"
        }
    })
