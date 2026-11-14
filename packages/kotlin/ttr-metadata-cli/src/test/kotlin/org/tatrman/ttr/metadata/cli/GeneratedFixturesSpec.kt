// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.metadata.cli

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Path

/** MW S3.6 DONE — `lint` on the four guard fixtures (`tests/conformance/guard/demo-cs-links`). */
class GeneratedFixturesSpec :
    FunSpec({
        val fixtures = Path.of("../../../tests/conformance/guard/demo-cs-links").toAbsolutePath().normalize()

        /** The error lines' (file under the case, line, col, code). */
        fun errors(case: String): Pair<Int, Set<List<String>>> {
            val out =
                runMetadataCommand(
                    listOf("lint", "--root", "${fixtures.resolve("$case/model")}", "--strict", "--format", "github"),
                )
            val pattern = Regex("""^::error file=(?:.*/$case/)?([^,]+),line=(\d+),col=(\d+)::(\S+) """)
            return out.exitCode to
                out.stdout
                    .lines()
                    .mapNotNull { pattern.find(it)?.destructured?.toList() }
                    .toSet()
        }

        test("pristine is green") {
            errors("pristine") shouldBe (0 to emptySet())
        }

        test("stale and edited are red with their codes at the file head") {
            errors("stale") shouldBe
                (
                    1 to
                        setOf(
                            listOf("model/demo/binding.yaml", "1", "1", "xm/generated-stale"),
                            listOf("model/demo/er.yaml", "1", "1", "xm/generated-stale"),
                            listOf("model/prodej/er.yaml", "1", "1", "xm/generated-stale"),
                        )
                )
            errors("edited") shouldBe (1 to setOf(listOf("model/demo/er.yaml", "1", "1", "xm/generated-edited")))
        }

        test("orphan-overlay is red with xm/overlay-orphan at the overlay entry") {
            val (exit, lines) = errors("orphan-overlay")
            exit shouldBe 1
            lines.map { it[3] }.toSet() shouldBe setOf("xm/generated-stale", "xm/overlay-orphan")
            lines.any {
                it[0] == "tree/profiles/overlay-inline.yaml" && it[1] == "5" && it[3] == "xm/overlay-orphan"
            } shouldBe
                true
        }
    })
