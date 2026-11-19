// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.profile

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import kotlin.io.path.*

class RenamesParitySpec :
    FunSpec({
        fun ledger(source: String): List<RenameEntry> {
            val root = Files.createTempDirectory("renames-parity")
            try {
                root.resolve("renames.yaml").writeText(source)
                return Renames.read(root)
            } finally {
                root.resolve("renames.yaml").deleteIfExists()
                root.deleteIfExists()
            }
        }
        test("C2 anchors and aliases are plain-load entries") {
            ledger("- &a {from: Old, to: New, since: v1}\n- *a") shouldBe
                listOf(RenameEntry("Old", "New", "v1"), RenameEntry("Old", "New", "v1"))
        }
        test("C2 YAML 1.1 directive retains the core scalar resolver") {
            ledger("%YAML 1.1\n---\n- {from: yes, to: New, since: 2026-11-17}") shouldBe
                listOf(RenameEntry("yes", "New", "2026-11-17"))
        }
        listOf(
            Triple("- {<<: {since: v1}, from: Old, to: New}", Pair(1, 1), "/0"),
            Triple("- {from: Old, from: Other, to: New, since: v1}", Pair(1, 15), ""),
            Triple("!unknown []", Pair(1, 1), ""),
            Triple("[]\n---\n[]", Pair(2, 1), ""),
            Triple("- {from: 1, to: New, since: v1}", Pair(1, 1), "/0/from"),
        ).forEachIndexed { i, (source, position, path) ->
            test("C2 refusal row $i is located in the ledger") {
                val error = shouldThrow<ProfileException> { ledger(source) }
                error.code shouldBe "ys/profile-invalid"
                error.location.file shouldBe "renames.yaml"
                Pair(error.location.line, error.location.column) shouldBe position
                error.location.path shouldBe path
            }
        }
    })
