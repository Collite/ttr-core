// SPDX-License-Identifier: Apache-2.0
package org.tatrman.report

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

class HtmlTableSpec :
    StringSpec({
        "HTML escapes every header and value, preserves column order and blanks nulls" {
            HtmlTable.render(Table(listOf("b<", "a&"), listOf(listOf("<script>\"'&", null)))) shouldBe
                "<table><thead><tr><th>b&lt;</th><th>a&amp;</th></tr></thead>" +
                "<tbody><tr><td>&lt;script&gt;&quot;&#39;&amp;</td><td></td></tr></tbody></table>"
        }
        "preview defaults to 500 rows and explains truncation without dropping the header" {
            val html = HtmlTable.render(Table(listOf("id"), (0..500).map { listOf("row-$it") }))
            Regex("<tbody><tr>|</tr><tr>").findAll(html).count() shouldBe 500
            html shouldContain "Showing 500 of 501 rows"
            html shouldNotContain "row-500"
            HtmlTable.render(Table(listOf("id"), emptyList())) shouldBe
                "<table><thead><tr><th>id</th></tr></thead><tbody></tbody></table>"
        }
        "an explicit smaller preview limit is honored" {
            HtmlTable.render(Table(listOf("id"), listOf(listOf("one"), listOf("two"))), 1) shouldContain
                "Showing 1 of 2 rows"
        }
    })
