// SPDX-License-Identifier: Apache-2.0
package org.tatrman.report

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files

class ProgramRunnerSpec :
    StringSpec({
        fun csv(text: String) = Files.createTempFile("report-rows", ".csv").also { Files.writeString(it, text) }

        "CSV preserves quoted delimiters newlines escaped quotes leading zeroes and BOM" {
            val path = csv("\uFEFFcode,note\r\n001,\"a,b\"\r\n002,\"line1\nline2 \"\"ok\"\"\"\r\n003,\r\n")
            try {
                RowsProgramRunner(mapOf("ROWS" to path)).run("er.query.x", emptyMap(), null) shouldBe
                    Table(
                        listOf("code", "note"),
                        listOf(listOf("001", "a,b"), listOf("002", "line1\nline2 \"ok\""), listOf("003", "")),
                    )
            } finally {
                Files.delete(path)
            }
        }
        "each anchor selects its own file and header-only is an empty table" {
            val a = csv("id\n1\n")
            val b = csv("other\n")
            try {
                val runner = RowsProgramRunner(mapOf("A" to a, "B" to b))
                runner.forAnchor("A").run("er.query.x", emptyMap(), null).rows shouldBe listOf(listOf("1"))
                runner.forAnchor("B").run("er.query.x", emptyMap(), null) shouldBe Table(listOf("other"), emptyList())
                shouldThrow<ReportException> { runner.forAnchor("C").run("er.query.x", emptyMap(), null) }.code shouldBe
                    "rows-missing"
            } finally {
                Files.delete(a)
                Files.delete(b)
            }
        }
        "malformed CSV and invalid table shapes are coded refusals" {
            listOf("", "a,a\nx,y\n", "a,b\n1\n", "a\n\"unclosed", "a\nx\"y", "a\n\"x\"junk").forEach { text ->
                val p = csv(text)
                try {
                    shouldThrow<ReportException> {
                        RowsProgramRunner(
                            mapOf("A" to p),
                        ).run("er.query.x", emptyMap(), null)
                    }.code shouldBe
                        "rows-invalid"
                } finally {
                    Files.delete(p)
                }
            }
        }
    })
