// SPDX-License-Identifier: Apache-2.0
package org.tatrman.concept

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * CN-P3 S3.7 — each golden world loads, and its definition-level findings are exactly the pinned ones
 * (`worlds/<name>/definition-diagnostics.txt`, one `slug SEVERITY subject` per line): the hygiene rows that are model
 * facts (a definition with no md / er grounding for what it reads), the advisory granularity findings, and the one
 * deliberate error — Hartland's `LoopA` ⇄ `LoopB`, the definition-cycle refusal's model. Anything else is a broken
 * fixture. `-Pcn.goldens.regen` rewrites the lists.
 */
class GoldenWorldsSpec :
    FunSpec({
        val regen = System.getProperty("cn.goldens.regen") == "true"
        for (name in GoldenWorlds.names) {
            test("world `$name` loads with exactly its pinned definition findings") {
                val w = GoldenWorlds[name]
                val actual =
                    w.cn
                        .definitionDiagnostics()
                        .map { "${it.slug} ${it.severity} ${it.subject}" }
                        .sorted()
                        .joinToString("\n")
                val file = w.dir.resolve("definition-diagnostics.txt")
                if (regen) file.writeText(actual + "\n") else file.readText().trim() shouldBe actual
            }
        }
    })
