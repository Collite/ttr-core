// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.parser.loader

import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.tatrman.ttr.parser.model.TtrlMode
import io.kotest.core.spec.style.StringSpec

/**
 * `.ttrl` sidecar parsing via the shared `TTR.g4` `ttrlDocument` entry rule (v4.3,
 * C1-c-iii): header version, canvases, auto/manual mode, ζ-keyed node positions, and
 * the negative shape rules (nodes-under-auto, viewport, unknown key) as named errors.
 */
class TtrlDocumentSpec :
    StringSpec({

        val hero =
            """
            ttrl 1

            canvas program {
                skin: "alteryx-knime"
                mode: manual
                nodes: {
                    "db_prep":          { x: 120, y: 80 }
                    "crunch":           { x: 420, y: 80 }
                    "big_customers~1":  { x: 700, y: 40 }
                }
                collapsed: []
            }

            canvas crunch {
                skin: "enso"
                mode: auto
            }
            """.trimIndent()

        "parses the hero sidecar: version, two canvases, manual positions, auto canvas" {
            val r = TtrlLoader.parseString(hero, "hero.ttrl")
            r.ok shouldBe true
            val doc = r.document.shouldNotBeNull()
            doc.version shouldBe 1
            doc.canvases shouldHaveSize 2

            val program = doc.canvases.first { it.key == "program" }
            program.skin shouldBe "alteryx-knime"
            program.mode shouldBe TtrlMode.MANUAL
            program.nodes shouldHaveSize 3
            program.nodes.first { it.zeta == "db_prep" }.x shouldBe 120.0
            program.nodes.first { it.zeta == "big_customers~1" }.y shouldBe 40.0
            program.collapsed shouldHaveSize 0

            val crunch = doc.canvases.first { it.key == "crunch" }
            crunch.skin shouldBe "enso"
            crunch.mode shouldBe TtrlMode.AUTO
            crunch.nodes shouldHaveSize 0
        }

        "rejects a nodes block under mode: auto" {
            val bad =
                """
                ttrl 1
                canvas program {
                    mode: auto
                    nodes: { "a": { x: 1, y: 2 } }
                }
                """.trimIndent()
            val r = TtrlLoader.parseString(bad, "bad.ttrl")
            r.ok shouldBe false
            r.errors.joinToString(" ") { it.message } shouldContain "auto"
        }

        "rejects a viewport key (dropped, C1-c-iii)" {
            val bad =
                """
                ttrl 1
                canvas program {
                    mode: manual
                    viewport: "x"
                }
                """.trimIndent()
            val r = TtrlLoader.parseString(bad, "bad.ttrl")
            r.ok shouldBe false
            r.errors.joinToString(" ") { it.message } shouldContain "viewport"
        }

        "rejects an unknown canvas property" {
            val bad =
                """
                ttrl 1
                canvas program { wobble: "x" }
                """.trimIndent()
            val r = TtrlLoader.parseString(bad, "bad.ttrl")
            r.ok shouldBe false
            r.errors.joinToString(" ") { it.message } shouldContain "unknown canvas property"
        }
    })
