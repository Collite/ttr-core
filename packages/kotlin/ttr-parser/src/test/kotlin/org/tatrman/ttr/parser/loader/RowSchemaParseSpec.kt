// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.parser.loader

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.tatrman.ttr.parser.model.SchemaDef
import org.tatrman.ttr.parser.model.WorldDef

/**
 * Grammar 0.14 — a top-level `def schema <name> { columns: [...] }` (a named row schema, the TTR-P
 * action-display shape) parses onto [SchemaDef]: columns in declared order, written exactly like a
 * table's columns (`type`, `optional`, `description`). The world-storage `def schema` is unchanged.
 */
class RowSchemaParseSpec :
    StringSpec({
        val example =
            """
            package shop.actions

            // Action row schemas — one per action kind.
            def schema notify {
                description: "Send a notification to a recipient",
                columns: [
                    def column recipient { type: text },
                    def column subject   { type: text },
                    def column order_id  { type: int },
                    def column amount    { type: decimal, optional: true },
                    def column due       { type: date, optional: true, description: "payment due date" },
                ]
            }

            def schema flag_order {
                columns: [
                    def column order_id { type: int },
                    def column reason   { type: text },
                ]
            }
            """.trimIndent()

        "def schema parses to a SchemaDef with ordered, typed, optional-marked columns" {
            val r = TtrLoader.parseString(example)
            r.errors shouldBe emptyList()
            val schemas = r.definitions.filterIsInstance<SchemaDef>()
            schemas.map { it.name } shouldContainExactly listOf("notify", "flag_order")
            val notify = schemas.first()
            notify.description shouldBe "Send a notification to a recipient"
            notify.columns.map { it.name } shouldContainExactly
                listOf("recipient", "subject", "order_id", "amount", "due")
            notify.columns.map { it.type?.name } shouldContainExactly listOf("text", "text", "int", "decimal", "date")
            notify.columns.map { it.optional } shouldContainExactly listOf(false, false, false, true, true)
            notify.columns.last().description shouldBe "payment due date"
        }

        "def schema sits beside a model directive and other defs" {
            val r =
                TtrLoader.parseString(
                    "model db\ndef table T { columns: [ def column a { type: int } ] }\n" +
                        "def schema s { tags: [\"action\"], columns: [ def column a { type: int } ] }",
                )
            r.errors shouldBe emptyList()
            val s = r.definitions.filterIsInstance<SchemaDef>().single()
            s.tags shouldBe listOf("action")
            s.columns.single().name shouldBe "a"
        }

        "an empty def schema parses (column validity is semantic)" {
            val r = TtrLoader.parseString("def schema empty { }")
            r.errors shouldBe emptyList()
            r.definitions
                .single()
                .shouldBeInstanceOf<SchemaDef>()
                .columns shouldBe emptyList()
        }

        "the world-storage `def schema` is unchanged (a separate production)" {
            val r =
                TtrLoader.parseString(
                    "model world\ndef world w { def storage files { type: local_dir, " +
                        "def schema sales { customer: string, amount: decimal } } }",
                )
            r.errors shouldBe emptyList()
            val w = r.definitions.single().shouldBeInstanceOf<WorldDef>()
            w.storages
                .single()
                .schemas
                .single()
                .fields
                .map { it.name } shouldContainExactly
                listOf("customer", "amount")
            r.definitions.filterIsInstance<SchemaDef>() shouldBe emptyList()
        }
    })
