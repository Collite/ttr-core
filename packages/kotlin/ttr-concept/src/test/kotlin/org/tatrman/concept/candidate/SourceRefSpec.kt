// SPDX-License-Identifier: Apache-2.0
package org.tatrman.concept.candidate

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe

/**
 * CN-P5 S5.2 T2 — the `source_ref` scheme (cn contracts §6, A-CN-82): a document (`doc://<system>/<document>`, an
 * optional sentence / row / span locator), a recipe sentence (`recipe://<package>/<recipe>#s<n>`), a question
 * (`question://<system>/<question>`). The scheme is what a promoted def's `sources:` line carries.
 */
class SourceRefSpec :
    FunSpec({
        test("a document, by sentence, row or code-point span — or whole") {
            SourceRefs.parse("doc://clinic/du-48213#s4") shouldBe
                SourceRef.Doc("clinic", listOf("du-48213"), Locator.Sentence(4))
            SourceRefs.parse("doc://hartland/price-list-2026#r17") shouldBe
                SourceRef.Doc("hartland", listOf("price-list-2026"), Locator.Row(17))
            SourceRefs.parse("doc://clinic/du-50077#c118-164") shouldBe
                SourceRef.Doc("clinic", listOf("du-50077"), Locator.Span(118, 164))
            SourceRefs.parse("doc://docwh/hartland-ops/1042/7") shouldBe
                SourceRef.Doc("docwh", listOf("hartland-ops", "1042", "7"), null)
        }

        test("a recipe sentence and a question") {
            SourceRefs.parse("recipe://hartland.orders/order-review#s2") shouldBe
                SourceRef.Recipe("hartland.orders", "order-review", 2)
            SourceRefs.parse("question://clinic/q-2031") shouldBe SourceRef.Question("clinic", listOf("q-2031"))
        }

        test("anything else is not a source ref") {
            for (bad in listOf(
                "https://hartland.example/handbook#s1",
                "doc://hartland",
                "doc://hartland/handbook#p3",
                "doc://hartland/handbook#s0",
                "doc://hartland/handbook#c10-10",
                "doc://hartland/handbook#c12-3",
                "recipe://hartland.orders/order-review",
                "recipe://hartland/orders/order-review#s1",
                "question://clinic/q-1#s1",
                "doc://hart land/handbook",
                "",
            )) {
                SourceRefs.parse(bad).shouldBeNull()
            }
        }

        test("the text of a parsed ref is the ref (one spelling, so a `sources:` line compares as text)") {
            for (ref in listOf(
                "doc://clinic/du-48213#s4",
                "doc://clinic/du-50077#c0-164",
                "doc://docwh/hartland-ops/1042/7",
                "recipe://hartland.orders/order-review#s2",
                "question://clinic/q-2031",
            )) {
                SourceRefs.parse(ref)!!.text shouldBe ref
            }
        }

        test("a locator the parser would refuse cannot be built (review-133 F11)") {
            for (bad in listOf(
                { Locator.Sentence(0) },
                { Locator.Row(0) },
                { Locator.Span(10, 10) },
                { Locator.Span(-1, 3) },
            )) {
                io.kotest.assertions.throwables
                    .shouldThrow<IllegalArgumentException> { bad() }
            }
            for (l in listOf(Locator.Sentence(1), Locator.Row(7), Locator.Span(0, 1))) {
                SourceRefs.parse(SourceRefs.fromDocwh("docwh://n/s/p", l)!!).shouldNotBeNull()
            }
        }

        test("Kleio's citation ref maps by one prefix swap: docwh://{notebook}/{source}/{part}") {
            SourceRefs.fromDocwh("docwh://hartland-ops/1042/7") shouldBe "doc://docwh/hartland-ops/1042/7"
            SourceRefs.fromDocwh("docwh://hartland-ops/1042/7", Locator.Sentence(3)) shouldBe
                "doc://docwh/hartland-ops/1042/7#s3"
            SourceRefs.parse(SourceRefs.fromDocwh("docwh://hartland-ops/1042/7")!!) shouldBe
                SourceRef.Doc("docwh", listOf("hartland-ops", "1042", "7"), null)
            SourceRefs.fromDocwh("docwh://hartland-ops/1042").shouldBeNull()
            SourceRefs.fromDocwh("doc://docwh/hartland-ops/1042/7").shouldBeNull()
        }

        test("the pre-rename kallimachos:// citation form is not read: nothing stored it, so there is no alias") {
            SourceRefs.fromDocwh("kallimachos://a/b/c").shouldBeNull()
        }
    })
