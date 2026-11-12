// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.metadata.source

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.isDirectory
import kotlin.io.path.name

/**
 * MW S3.4 T5 — `tests/conformance/dialect/<case>/`: a dialect `tree/` (with its `ttr.yaml`) and its `canonical/` twin.
 * Both load through the `yaml` source to the same snapshot dump. A case without a twin is red.
 */
class DialectConformanceSpec :
    FunSpec({
        fun cases(root: Path): List<Path> {
            val cases = Files.list(root).use { paths -> paths.filter { it.isDirectory() }.sorted().toList() }
            check(cases.isNotEmpty()) { "Empty dialect conformance roster $root" }
            val twinless = cases.filter { !it.resolve("tree").isDirectory() || !it.resolve("canonical").isDirectory() }
            check(twinless.isEmpty()) {
                "Dialect fixtures without a tree/ and a canonical/ twin: ${twinless.joinToString { it.name }}"
            }
            return cases
        }

        val roster = cases(Path.of("../../../tests/conformance/dialect"))

        test("a dialect fixture without a canonical twin is refused") {
            val root = Files.createTempDirectory("s34-dialect")
            root.resolve("lonely/tree").createDirectories()
            shouldThrow<IllegalStateException> { cases(root) }.message!! shouldContain "lonely"
        }

        roster.forEach { case ->
            test("dialect ${case.name} loads to the snapshot of its canonical twin") {
                val tree = case.resolve("tree")
                val canonical = case.resolve("canonical")
                val dialect = SnapshotDump.load(YamlFileSource("t", 100, LocalFsStorage("t", tree)))
                withClue(dialect.report) {
                    dialect.report!!.droppedFiles shouldBe emptyList()
                    SnapshotDump.dump(dialect, tree) shouldBe
                        SnapshotDump.dump(
                            SnapshotDump.load(YamlFileSource("t", 100, LocalFsStorage("t", canonical))),
                            canonical,
                        )
                }
            }
        }
    })
