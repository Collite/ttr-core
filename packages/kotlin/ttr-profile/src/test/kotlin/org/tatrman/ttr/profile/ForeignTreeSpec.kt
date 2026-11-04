// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.profile

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.JsonPrimitive

class ForeignTreeSpec : FunSpec({
    test("pointers retain authored spans, escaped keys, array indexes and record-relative mapping keys") {
        val tree = ForeignTree.read("records:\n  odd/key:\n    a~b: [value, null]\n", "located.yaml")
        val record = JsonPointer("/records/odd~1key").resolve(tree.root)!!
        val selected = JsonPointer("/a~0b/0").resolve(record)!!
        selected.value shouldBe JsonPrimitive("value")
        selected.span().line shouldBe 3
        selected.span().column shouldBe 11
        JsonPointer("/\$key").resolve(record)!!.value shouldBe JsonPrimitive("odd/key")
        JsonPointer("/\$key").resolve(record)!!.span().column shouldBe 3
        JsonPointer("/a~0b/1").resolve(record) shouldBe null
        JsonPointer("/a~0b/01").resolve(record) shouldBe null
        JsonPointer("/missing").resolve(record) shouldBe null
        shouldThrow<IllegalArgumentException> { JsonPointer("/bad~2") }
        shouldThrow<IllegalArgumentException> { JsonPointer("bad") }
    }
    test("file selectors reproduce three shared MF vectors without the metadata dependency") {
        // model-files-vectors.yaml: folder line, rooted path, bare name (MF F-13), vocabulary invented here.
        val vectors = listOf(
            Triple("layouts/", listOf("layouts/a.yaml", "layouts/agent/b.yaml", "x/layouts/c.yml", "entities/a.yaml", "layouts.yaml"), listOf(true, true, true, false, false)),
            Triple("entities/old.yaml", listOf("entities/old.yaml", "sub/entities/old.yaml", "entities/new.yaml"), listOf(true, false, false)),
            Triple("b.yaml", listOf("b.yaml", "a/b.yaml", "a/c/b.yaml", "a/bb.yaml"), listOf(true, true, true, false)),
        )
        vectors.forEach { (pattern, paths, expected) -> paths.map { FileSelector(listOf(pattern)).matches(it) } shouldBe expected }
    }
    test("gitignore wildcard, anchored name, folder ancestors and case rules") {
        listOf(
            Triple("/b.yaml", "a/b.yaml", false), Triple("/b.yaml", "b.yaml", true),
            Triple("entities/*.yaml", "entities/sub/b.yaml", false),
            Triple("**/draft?.[yY]ml", "a/drafts/draft1.yml", true),
            Triple("**/draft?.[yY]ml", "draft1.Yml", true),
            Triple("drafts", "x/drafts/a.yaml", true),
            Triple("c.yaml/", "c.yaml", false), Triple("c.yaml/", "d/c.yaml/e.yaml", true),
            Triple("*.yaml", "a.yml", false), Triple("a[!x].yaml", "ab.yaml", true),
        ).forEach { (pattern, path, expected) -> FileSelector(listOf(pattern)).matches(path) shouldBe expected }
        shouldThrow<IllegalArgumentException> { FileSelector(listOf("!x")) }
        shouldThrow<IllegalArgumentException> { FileSelector(listOf("[x")) }
    }
})
