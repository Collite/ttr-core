// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.writer

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.tatrman.ttr.parser.loader.TtrLoader
import org.tatrman.ttr.parser.model.SchemaDef

/**
 * Grammar 0.14 — a `def schema` (named row schema) survives write → reparse: name, description, tags and
 * the ordered column list with each column's type and `optional` flag.
 */
class RowSchemaRoundTripSpec :
    StringSpec({
        val src =
            """
            package shop.actions
            def schema notify {
                description: "Send a notification",
                tags: ["action"],
                columns: [
                    def column recipient { type: text },
                    def column amount { type: decimal, optional: true },
                ]
            }
            """.trimIndent()

        "a def schema round-trips byte-stable and keeps its columns" {
            val r1 = TtrLoader.parseString(src)
            r1.ok shouldBe true
            val text1 = TtrRenderer.render(r1)
            text1 shouldContain "def schema notify {"
            text1 shouldContain "def column amount { type: decimal, optional: true, }"

            val r2 = TtrLoader.parseString(text1)
            r2.ok shouldBe true
            TtrRenderer.render(r2) shouldBe text1
            val s = r2.definitions.filterIsInstance<SchemaDef>().single()
            s.description shouldBe "Send a notification"
            s.tags shouldBe listOf("action")
            s.columns.map { Triple(it.name, it.type?.name, it.optional) } shouldBe
                listOf(Triple("recipient", "text", false), Triple("amount", "decimal", true))
        }
    })
