// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.yaml

import io.kotest.core.spec.style.FunSpec
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.tatrman.ttr.parser.loader.TtrLoader
import org.tatrman.ttr.parser.conformance.ConformanceDump
import java.nio.file.Files
import java.nio.file.Path

/** Exercises existing text coverage in memory; committed twins and all-roster gates belong to S2.3. */
class CanonicalYamlCorpusSpec :
    FunSpec({
        val root = Path.of("../../../tests/conformance/fixtures")
        val files = Files.walk(root).use { paths -> paths.filter { it.toString().endsWith(".ttrm") }.sorted().toList() }
        files.filter { TtrLoader.parseFile(it).ok }.forEach { file ->
            test("in-memory syntax fixed point ${root.relativize(file)}") {
                val original = CanonicalYaml.fromTtr(Files.readString(file), file.toString())
                val yaml = CanonicalYaml.write(original)
                val restored = CanonicalYaml.read(yaml, "$file.yaml")
                withClue(file.toString()) {
                    ConformanceDump.dumpTree(restored.ast) shouldBe ConformanceDump.dumpTree(original.ast)
                    restored.value shouldBe original.value
                    CanonicalYaml.write(restored) shouldBe yaml
                    CanonicalYaml.fromTtr(CanonicalYaml.toTtr(restored)).value shouldBe original.value
                }
            }
        }
    })
