// SPDX-License-Identifier: Apache-2.0
package org.tatrman.concept

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlin.io.path.readLines

/**
 * CN-P3 S3.4 T2 (A-CN-12) — the er renderer quotes exactly the translator parser's reserved words. The set is pinned:
 * `resources/er/reserved-words.txt` lists it as of today's Calcite; a bump that reserves a word (or frees one) fails
 * here, loudly — a definition naming an attribute after it would otherwise stop translating (or start quoting) silently.
 * Regenerate the file from [ErNames.reserved] and review the diff when it does.
 */
class ErNamesSpec :
    FunSpec({
        test("the reserved set is the pinned one (a Calcite bump that moves it fails here)") {
            val pinned =
                DefinitionFixtures.repo
                    .resolve("packages/kotlin/ttr-concept/src/test/resources/er/reserved-words.txt")
                    .readLines()
                    .filter { it.isNotBlank() && !it.startsWith("#") }
            val now = ErNames.reserved.toList()
            withClue("added: ${now - pinned.toSet()}\nremoved: ${pinned - now.toSet()}") { now shouldBe pinned }
        }

        test(
            "a reserved word or a name no bare identifier spells is double-quoted; anything else is written as named",
        ) {
            listOf(
                "order",
                "condition",
                "at",
                "value",
                "Order",
                "period",
            ).forEach { ErNames.sql(it) shouldBe "\"$it\"" }
            listOf(
                "state",
                "status",
                "code",
                "sales_line",
                "on_hand",
                "e1",
                "rn",
            ).forEach { ErNames.sql(it) shouldBe it }
            ErNames.sql("line no") shouldBe "\"line no\""
            ErNames.sql("say\"hi") shouldBe "\"say\"\"hi\""
        }
    })
