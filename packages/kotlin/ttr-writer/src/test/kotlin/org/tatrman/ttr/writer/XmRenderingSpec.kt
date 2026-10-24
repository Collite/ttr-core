// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.writer

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.tatrman.ttr.parser.loader.TtrLoader

class XmRenderingSpec :
    StringSpec({
        "MD logical, physical and vocabulary additions are render-parse fixed points" {
            val inputs =
                listOf(
                    """
                    model md
                    def domain Code { type: { type: varchar, length: 5 }, kind: calc, publish: members, restrict: { members: { "a": { en: "A" } } } }
                    def dimension Calendar { key: day, attributes: [def attribute day { domain: md.Code, aggregation: sum }], hierarchies: [md.Rollup] }
                    def map Month { from: [md.Code], to: [md.Code], cardinality: { from: N, to: N } }
                    def map Year { from: md.Code, to: md.Code, calc: fiscalYearOfDate(fiscalYearStartMonth: 4) }
                    def hierarchy Rollup { dimension: md.Calendar, levels: [day via md.Month] }
                    """.trimIndent(),
                    """
                    model binding
                    def md2db_domain code { domain: md.Code, source: { table: db.retail.codes, column: id } }
                    def md2db_map month { map: md.Month, target: db.retail.months, columns: { from: day, to: month } }
                    """.trimIndent(),
                    """
                    model lexicon locale cs
                    def term thing { for: cn.Thing, forms: ["věc"], default: true, sense_priority: 2 }
                    def pattern thing_pattern { for: cn.Thing, match: "věc.*" }
                    def example thing_example { for: cn.Thing, text: "Najdi věc" }
                    """.trimIndent(),
                    """def area retail { packages: [md], entities: [er.entity.sale] }""",
                )
            for (input in inputs) {
                val parsed = TtrLoader.parseString(input)
                parsed.errors shouldBe emptyList()
                val text = TtrRenderer.render(parsed)
                val reparsed = TtrLoader.parseString(text)
                reparsed.errors shouldBe emptyList()
                reparsed.definitions.map { it.name } shouldBe
                    parsed.definitions.map { it.name }.sortedWith(compareBy { name -> text.indexOf(" $name {") })
                TtrRenderer.render(reparsed) shouldBe text
                reparsed.modelDirective?.locale shouldBe parsed.modelDirective?.locale
            }
        }
    })
