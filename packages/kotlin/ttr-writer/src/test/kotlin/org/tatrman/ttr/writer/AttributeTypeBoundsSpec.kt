// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.writer

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.tatrman.ttr.parser.loader.TtrLoader
import org.tatrman.ttr.parser.model.AutomationDef
import org.tatrman.ttr.parser.model.DataType
import org.tatrman.ttr.parser.model.EntityDef

class AttributeTypeBoundsSpec :
    StringSpec({
        "attribute rendering retains structured decimal and text declarations after parsing" {
            val parsed =
                TtrLoader.parseString(
                    """
                    model er
                    def entity proposal {
                        attributes: [
                            def attribute id { type: int, isKey: true },
                            def attribute amount { type: { type: decimal, length: 12, precision: 2 } },
                            def attribute integral { type: { type: decimal, length: 8, precision: 0 } },
                            def attribute code { type: { type: text, length: 40 }, optional: true },
                            def attribute bare { type: decimal },
                            def attribute partial { type: { type: decimal, precision: 3 } }
                        ]
                    }
                    """.trimIndent(),
                )
            parsed.errors shouldBe emptyList()
            val rendered = TtrRenderer.render(parsed)
            val reloaded = TtrLoader.parseString(rendered)
            reloaded.errors shouldBe emptyList()
            val types =
                reloaded.definitions
                    .filterIsInstance<EntityDef>()
                    .single()
                    .attributes
                    .map { it.type }
            types shouldBe
                listOf(
                    DataType("int"),
                    DataType("decimal", 12, 2),
                    DataType("decimal", 8, 0),
                    DataType("text", 40),
                    DataType("decimal"),
                    DataType("decimal", precision = 3),
                )
            TtrRenderer.render(reloaded) shouldBe rendered
        }
        "automation parameter rendering retains structured bounds and bare types" {
            val parsed =
                TtrLoader.parseString(
                    """
                    model automation
                    def parameter max_amount { type: { type: decimal, length: 12, precision: 2 }, value: 100 }
                    def parameter integral { type: { type: decimal, length: 8, precision: 0 }, value: 2 }
                    def parameter partial { type: { type: decimal, precision: 3 }, value: 1 }
                    def parameter count { type: int, value: 2 }
                    """.trimIndent(),
                )
            parsed.errors shouldBe emptyList()
            val rendered = TtrRenderer.render(parsed)
            val reloaded = TtrLoader.parseString(rendered)
            reloaded.errors shouldBe emptyList()
            reloaded.definitions.filterIsInstance<AutomationDef>().associate { it.name to it.body.type } shouldBe
                mapOf(
                    "max_amount" to DataType("decimal", 12, 2),
                    "integral" to DataType("decimal", 8, 0),
                    "partial" to DataType("decimal", precision = 3),
                    "count" to DataType("int"),
                )
            TtrRenderer.render(reloaded) shouldBe rendered
        }
    })
