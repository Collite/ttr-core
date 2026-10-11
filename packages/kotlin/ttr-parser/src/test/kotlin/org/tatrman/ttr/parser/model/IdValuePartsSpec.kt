// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.parser.model

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.tatrman.ttr.parser.loader.TtrLoader

/**
 * PropertyValue.IdValue gains `parts: List<String>` (matches the TS IdValue.parts):
 * the walker splits the dotted reference on `.` when constructing it.
 */
class IdValuePartsSpec :
    StringSpec({

        "IdValue splits its dotted reference into parts" {
            val r =
                TtrLoader.parseString(
                    """
                    def relation R {
                        from: db.dbo.fk_item_category
                        to: db.dbo.Y
                    }
                    """.trimIndent(),
                )
            r.ok shouldBe true
            val rel = r.definitions[0] as RelationDef
            val id = rel.from as PropertyValue.IdValue

            id.ref.path shouldBe "db.dbo.fk_item_category"
            id.parts shouldBe listOf("db", "dbo", "fk_item_category")
        }
    })
