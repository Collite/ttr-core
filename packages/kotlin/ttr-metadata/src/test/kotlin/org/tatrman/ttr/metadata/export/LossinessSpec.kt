// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.metadata.export

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class LossinessSpec :
    StringSpec({
        "literal cannot-carry matrix" {
            val models = listOf("world", "cn", "md", "er", "db", "bindings", "lexicon")
            for (format in listOf("graph.json", "defs.json", "ttr", "yaml", "ttl", "xmi", "dot")) {
                for (model in models) {
                    val expected =
                        when {
                            format == "ttl" && model !in setOf("cn", "world") -> "L-TTL-1"
                            format == "xmi" && model == "lexicon" -> "L-XMI-2"
                            format == "dot" && model != "world" -> "L-DOT-1"
                            else -> null
                        }
                    LossinessLedger.cannotCarry(model, format) shouldBe expected
                }
            }
            LossinessLedger.entriesFor("defs.json") shouldBe emptyList()
            LossinessLedger.entriesFor("graph.json").map { it.id } shouldBe (1..5).map { "L-GRAPH-$it" }
        }
    })
