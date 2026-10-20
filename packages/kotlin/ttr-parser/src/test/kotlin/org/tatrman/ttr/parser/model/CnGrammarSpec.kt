// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.parser.model

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.tatrman.ttr.parser.diagnostics.DiagnosticCode
import org.tatrman.ttr.parser.loader.TtrLoader

/**
 * Grammar 0.17 — the conceptual model (CN-P1 S1.1). The cn fixtures (cn contracts §1, under
 * `resources/cn/`, mirrored as conformance fixtures 75–79) parse with zero errors; the parser-level
 * rejects live in `cn-negative/`; the one `def role` production keeps review-114 M5 outside `model cn`
 * (⚑CN-P6). Mirrors the TS `cn-grammar.test.ts` and the Python `test_cn.py`.
 */
class CnGrammarSpec :
    StringSpec({

        listOf(
            "75-cn-concepts.ttrm" to "cn",
            "76-cn-bindings.ttrm" to "binding",
            "77-cn-sugar.ttrm" to null,
            "78-cn-lexicon.ttrm" to "lexicon",
            "79-cn-nouns-as-ids.ttrm" to "db",
        ).forEach { (name, model) ->
            "parses $name with no errors" {
                val r = TtrLoader.parseString(cnResource("cn/$name"), name)
                r.errors shouldBe emptyList()
                r.modelDirective?.modelCode shouldBe model
            }
        }

        listOf(
            "neg-01-bare-definition.ttrm",
            "neg-02-involves-no-brackets.ttrm",
            "neg-03-bare-members.ttrm",
            "neg-04-unlabelled-as.ttrm",
        ).forEach { name ->
            "rejects $name" {
                TtrLoader.parseString(cnResource("cn-negative/$name")).ok shouldBe false
            }
        }

        val cnRole =
            "def role Customer { of: Party, event: Order, grounds: md.Customer, lexicon: { terms: [\"client\"] } }"

        "⚑CN-P6: a role in a `model cn` file takes the concept properties" {
            TtrLoader.parseString("model cn\n$cnRole").errors shouldBe emptyList()
        }

        listOf("cnc", "automation").forEach { model ->
            "review-114 M5: a role in a `model $model` file rejects the concept properties, one error each" {
                val r = TtrLoader.parseString("model $model\n$cnRole")
                r.errors.map { it.code } shouldContainExactly List(4) { DiagnosticCode.UnknownProperty }
                r.errors.map { Regex("'([^']+)'").find(it.message)?.groupValues?.get(1) } shouldContainExactly
                    listOf("of", "event", "grounds", "lexicon")
                r.errors.first().message shouldContain "model $model"
            }
        }

        "review-114 M5: a role property typo is a parse error with no model directive too" {
            TtrLoader.parseString("def role r { lable: { en: \"x\" } }").ok shouldBe false
        }
    })

private fun cnResource(path: String): String =
    object {}
        .javaClass.classLoader
        .getResource(path)
        ?.readText()
        ?: error("missing test resource: $path")
