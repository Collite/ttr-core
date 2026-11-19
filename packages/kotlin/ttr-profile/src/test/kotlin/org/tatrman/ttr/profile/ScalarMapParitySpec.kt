// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.profile

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Path
import kotlin.io.path.*

class ScalarMapParitySpec : FunSpec({
    listOf("numbers", "integer-keys").forEach { name ->
        test("shared $name canonical bytes") {
            val fixture = Path.of("../../../tests/conformance/dialect/$name")
            val root = fixture.resolve("tree")
            val chain = ProfileResolver(root, emptyMap()).resolve("./profiles/parity.yaml", root.resolve("ttr.yaml").toAbsolutePath())
            val input = root.resolve("$name.yaml")
            val result = ProfileEngine.apply(listOf(ForeignTree.read(input.readText(), input.name)), chain, root)
            result.documents.size shouldBe 1
            val bytes = result.documents.single().write()
            val expected = fixture.resolve("canonical/$name.yaml")
            bytes shouldBe expected.readText()
        }
    }
})
