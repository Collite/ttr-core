// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.parser.model

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.tatrman.ttr.parser.loader.TtrLoader

class CnTimeWalkerSpec :
    StringSpec({
        "bounds and projection references retain their exact spans" {
            val text =
                "model cn\ndef phase Open { of: Order, from: Placed, until: Delivered }\n" +
                    "def quality price { of: Item, valued_state: true, per: Channel, from: ListPrice.price }"
            val parsed = TtrLoader.parseString(text)
            parsed.errors shouldBe emptyList()
            val concepts = parsed.definitions.filterIsInstance<ConceptDef>()
            val open = concepts.first().body.properties
            open.map { it.key } shouldBe listOf("of", "from", "until")
            (open[1] as ConceptProperty.From).ref.path shouldBe "Placed"
            (open[2] as ConceptProperty.Until).ref.path shouldBe "Delivered"
            val quality = concepts.last().body.properties
            (quality[2] as ConceptProperty.Per).ref.path shouldBe "Channel"
            (quality[3] as ConceptProperty.From).ref.path shouldBe "ListPrice.price"
            (open + quality).forEach { prop ->
                val ref =
                    when (prop) {
                        is ConceptProperty.From -> prop.ref
                        is ConceptProperty.Until -> prop.ref
                        is ConceptProperty.Per -> prop.ref
                        else -> null
                    }
                if (ref != null) text.substring(ref.source.offsetStart, ref.source.offsetEnd) shouldBe ref.path
            }
        }

        "role lifetimes stay confined to model cn" {
            for (model in listOf("cnc", "automation")) {
                for (prop in listOf("involves: [Item]", "period: { start: since, end: until }")) {
                    TtrLoader.parseString("model $model\ndef role Reader { $prop }").errors.isNotEmpty() shouldBe true
                }
            }
        }
    })
