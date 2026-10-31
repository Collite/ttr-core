// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.writer

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.tatrman.ttr.parser.loader.TtrLoader
import org.tatrman.ttr.parser.model.QueryDef
import org.tatrman.ttr.parser.model.LexiconEntryDef
import org.tatrman.ttr.parser.model.PropertyValue

class CanonicalCarriersSpec :
    StringSpec({
        "render and reparse retain explicit false, empty default and ordered bindings" {
            val text =
                """
                model db
                def query q {
                    skipSecurity: false,
                    parameters: [{name: p, type: text, defaultValue: "",
                        binds: [er.Customer.name, er.Customer.code]}]
                }
                """.trimIndent()
            val parsed = TtrLoader.parseString(text, "q.ttrm")
            parsed.errors shouldBe emptyList()
            val rendered = TtrRenderer.renderFile("db", null, parsed.definitions)
            val again = TtrLoader.parseString(rendered, "q.ttrm")
            again.errors shouldBe emptyList()
            val q = again.definitions.single() as QueryDef
            q.skipSecurity shouldBe false
            val p = (q.parameters.single() as PropertyValue.ObjectValue).entries
            (p.getValue("defaultValue") as PropertyValue.StringValue).raw shouldBe ""
            val binds = p.getValue("binds") as PropertyValue.ListValue
            binds.items.map { (it as PropertyValue.IdValue).ref.path } shouldBe
                listOf("er.Customer.name", "er.Customer.code")
        }
        "render and reparse retain lexical policy and paired question" {
            val parsed =
                TtrLoader.parseString(
                    "model lexicon def pattern pair { for: db.query.q, match: \"name (.*)\", text: \"Name Alice?\", indexSecurity: none }",
                    "lex.ttrm",
                )
            parsed.errors shouldBe emptyList()
            val again = TtrLoader.parseString(TtrRenderer.renderFile("lexicon", null, parsed.definitions), "lex.ttrm")
            again.errors shouldBe emptyList()
            val p = again.definitions.single() as LexiconEntryDef
            p.match shouldBe "name (.*)"
            p.text shouldBe "Name Alice?"
            p.indexSecurity shouldBe "none"
        }

        "canonical string escapes survive render and reparse" {
            val lex =
                TtrLoader.parseString(
                    """model lexicon def pattern p { for: db.query.q, match: "^\d{4}\s+(\w+)$", text: "a\tb", description: "say \"hi\"\nnext" }""",
                    "lex.ttrm",
                )
            lex.errors shouldBe emptyList()
            val again = TtrLoader.parseString(TtrRenderer.renderFile("lexicon", null, lex.definitions), "lex.ttrm")
            again.errors shouldBe emptyList()
            val p = again.definitions.single() as LexiconEntryDef
            p.match shouldBe "^\\d{4}\\s+(\\w+)$"
            p.text shouldBe "a\tb"
            p.description shouldBe "say \"hi\"\nnext"
            val query =
                TtrLoader.parseString(
                    """model db def query q { parameters: [{name: p, defaultValue: "C:\\new", label: "a\tb"}] }""",
                    "q.ttrm",
                )
            query.errors shouldBe emptyList()
            val q = TtrLoader.parseString(TtrRenderer.renderFile("db", null, query.definitions), "q.ttrm")
            q.errors shouldBe emptyList()
            val parameter =
                ((q.definitions.single() as QueryDef).parameters.single() as PropertyValue.ObjectValue)
                    .entries
            (parameter.getValue("defaultValue") as PropertyValue.StringValue).raw shouldBe "C:\\new"
            (parameter.getValue("label") as PropertyValue.StringValue).raw shouldBe "a\tb"
        }
        "the shared encoder preserves characters outside the five escapes" {
            val value = "\\d C:\\new \"hi\"\n\t\r\b\u000c\u0001😀"
            org.tatrman.ttr.parser.TtrStrings
                .decode(
                    org.tatrman.ttr.parser.TtrStrings
                        .encode(value),
                ) shouldBe value
        }
    })
