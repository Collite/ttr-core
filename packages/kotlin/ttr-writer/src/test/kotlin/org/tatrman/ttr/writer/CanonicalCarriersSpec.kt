// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.writer

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.tatrman.ttr.parser.loader.TtrLoader
import org.tatrman.ttr.parser.model.QueryDef
import org.tatrman.ttr.parser.model.LexiconEntryDef
import org.tatrman.ttr.parser.model.PropertyValue

class CanonicalCarriersSpec : StringSpec({
    "render and reparse retain explicit false, empty default and ordered bindings" {
        val text = "model db def query q { skipSecurity: false, parameters: [{name: p, type: text, defaultValue: \"\", binds: [er.Customer.name, er.Customer.code]}] }"
        val parsed = TtrLoader.parseString(text, "q.ttrm")
        parsed.errors shouldBe emptyList()
        val rendered = TtrRenderer.renderFile("db", null, parsed.definitions)
        val again = TtrLoader.parseString(rendered, "q.ttrm")
        again.errors shouldBe emptyList()
        val q = again.definitions.single() as QueryDef
        q.skipSecurity shouldBe false
        val p = (q.parameters.single() as PropertyValue.ObjectValue).entries
        (p.getValue("defaultValue") as PropertyValue.StringValue).raw shouldBe ""
        (p.getValue("binds") as PropertyValue.ListValue).items.map { (it as PropertyValue.IdValue).ref.path } shouldBe listOf("er.Customer.name", "er.Customer.code")
    }
    "render and reparse retain lexical policy and paired question" {
        val parsed = TtrLoader.parseString("model lexicon def pattern pair { for: db.query.q, match: \"name (.*)\", text: \"Name Alice?\", indexSecurity: none }", "lex.ttrm")
        parsed.errors shouldBe emptyList()
        val again = TtrLoader.parseString(TtrRenderer.renderFile("lexicon", null, parsed.definitions), "lex.ttrm")
        again.errors shouldBe emptyList()
        val p = again.definitions.single() as LexiconEntryDef
        p.match shouldBe "name (.*)"
        p.text shouldBe "Name Alice?"
        p.indexSecurity shouldBe "none"
    }
})
