// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.profile

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.*

private fun profile220(
    identity: String = "demo-cs/1",
    extra: String = "",
) = "profile: $identity\ngrammar: '>=0.18 <1.0'\noperations: []\n$extra"

private inline fun root220(block: (Path) -> Unit) {
    Path.of("build").createDirectories()
    val root = Files.createTempDirectory(Path.of("build"), "review220-").toRealPath()
    try {
        block(root)
    } finally {
        root.toFile().deleteRecursively()
    }
}

class ProfileReview220Spec :
    FunSpec({
        test("header tag scope admits model pl sugar but refuses it in declarations") {
            val tree = DeclarationReader.readTree("profile: demo-cs/1\ngrammar: '>=0.18'\n")
            val sugar = Path.of("../../../tests/conformance/yaml-migration/pl-sugar.yaml").readText()
            DeclarationReader.readFile(sugar, "pl-sugar.yaml") shouldBe null
            DeclarationReader.select(sugar, "pl-sugar.yaml", tree) shouldBe tree
        }

        test("header tag scope permits model sugar with a header and refuses sugar in declarations") {
            val header = "ttr: {profile: demo-cs/1, grammar: '>=0.18'}\ndefs:\n  - definition: !pl x\n"
            DeclarationReader.readFile(header)!!.profile shouldBe "demo-cs/1"
            val error =
                shouldThrow<ProfileException> {
                    DeclarationReader.readFile("ttr: {profile: !pl demo-cs/1, grammar: '>=0.18'}\n", "header.yaml")
                }
            error.message shouldBe
                "header.yaml:1:16: ys/profile-invalid /ttr/profile: Unsupported YAML tag !pl here"
            listOf(
                "profile: !pl demo-cs/1\ngrammar: '>=0.18'\n",
                "profile: demo-cs/1\ngrammar: !pl '>=0.18'\n",
            ).forEach { shouldThrow<ProfileException> { DeclarationReader.readTree(it) } }
            // Other unsafe shapes outside the header remain whole-document failures.
            listOf("x: &a 1", "x: {<<: {v: 1}}", "x: {v: 1, v: 2}", "x: !!python/object foo").forEach {
                shouldThrow<ProfileException> { DeclarationReader.readFile("$header\n$it\n") }
            }
        }

        test("header type and unsafe alias keys have precise diagnostics") {
            shouldThrow<ProfileException> { DeclarationReader.readFile("ttr: [a]\n") }.detail shouldBe
                "ttr header must be a mapping {profile, grammar}"
            DeclarationReader.readFile("ttr:\n") shouldBe null
            val error =
                shouldThrow<ProfileException> {
                    ProfileReader.read(profile220(extra = "overlay: {x: {*a : 1}}\n"))
                }
            error.location.path shouldBe "/overlay/x"
        }

        test("header tag scope ends at pointer segment boundaries") {
            val header = "ttr: {profile: demo-cs/1, grammar: '>=0.18'}\n"
            listOf("ttrx", "\"ttr/x\"").forEach { key ->
                DeclarationReader.readFile(header + "$key: !pl a\n")!!.profile shouldBe "demo-cs/1"
            }
            shouldThrow<ProfileException> { DeclarationReader.readFile("ttr: !pl x\n", "header.yaml") }.also {
                it.location.path shouldBe "/ttr"
                it.detail shouldBe "Unsupported YAML tag !pl here"
            }
        }

        test("profile disk reads refuse malformed UTF-8 and enforce byte then code-point budgets") {
            root220 { root ->
                val file = root.resolve("profile.yaml")
                val resolver = ProfileResolver(root, mapOf("demo-cs/1" to Path.of("profile.yaml")))
                val prefix = profile220(extra = "overlay: {x: {v: café😀")
                file.writeBytes(prefix.toByteArray() + byteArrayOf(0xff.toByte()) + "}}\n".toByteArray())
                val error = shouldThrow<ProfileException> { resolver.resolve("demo-cs/1", Path.of("ttr.yaml")) }
                error.code shouldBe "ys/profile-invalid"
                error.location.line shouldBe 4
                error.location.column shouldBe 23
                error.location.path shouldBe ""
                listOf("\n", "\r\n", "\r").forEach { newline ->
                    file.writeBytes("😀${newline}é😀".toByteArray() + byteArrayOf(0xff.toByte()))
                    shouldThrow<ProfileException> { resolver.resolve("demo-cs/1", Path.of("ttr.yaml")) }.also {
                        it.location.line shouldBe 2
                        it.location.column shouldBe 3
                    }
                }
                listOf("\u0085", "\u2028", "\u2029").forEach { separator ->
                    file.writeBytes("ab${separator}cd".toByteArray() + byteArrayOf(0xff.toByte()))
                    shouldThrow<ProfileException> { resolver.resolve("demo-cs/1", Path.of("ttr.yaml")) }.also {
                        it.location.line shouldBe 1
                        it.location.column shouldBe 6
                    }
                }
                listOf("" to 1, "\uFEFF" to 1, "\uFEFFa" to 2).forEach { (prefix, column) ->
                    file.writeBytes(prefix.toByteArray() + byteArrayOf(0xff.toByte()))
                    shouldThrow<ProfileException> { resolver.resolve("demo-cs/1", Path.of("ttr.yaml")) }.also {
                        it.location.line shouldBe 1
                        it.location.column shouldBe column
                    }
                }
                file.writeBytes(ByteArray(12_000_001) { 'a'.code.toByte() })
                shouldThrow<ProfileException> { resolver.resolve("demo-cs/1", Path.of("ttr.yaml")) }.also {
                    it.code shouldBe "ys/profile-invalid"
                    it.location.line shouldBe 1
                    it.location.column shouldBe 1
                    it.detail shouldBe "Profile exceeds 12000000 bytes"
                }
                file.writeBytes(ByteArray(12_000_000) { 'a'.code.toByte() })
                shouldThrow<ProfileException> { resolver.resolve("demo-cs/1", Path.of("ttr.yaml")) }.detail shouldBe
                    "The incoming YAML document exceeds the limit: 3000000 code points."
                file.writeText(profile220(extra = "extends: ./bad.yaml\n"))
                root.resolve("bad.yaml").writeBytes(prefix.toByteArray() + byteArrayOf(0xff.toByte()))
                shouldThrow<ProfileException> { resolver.resolve("demo-cs/1", Path.of("ttr.yaml")) }
                    .location.file shouldBe root.resolve("bad.yaml").toString()
            }
        }

        test("catalog identities match names and numeric majors including inherited catalog references") {
            root220 { root ->
                val file = root.resolve("profile.yaml")
                val resolver = ProfileResolver(root, mapOf("demo-cs/1" to Path.of("profile.yaml")))
                listOf("p32/1", "demo-cs/2").forEach { identity ->
                    file.writeText(profile220(identity))
                    shouldThrow<ProfileException> { resolver.resolve("demo-cs/1", Path.of("ttr.yaml")) }.also {
                        it.code shouldBe "ys/profile-invalid"
                        it.location.path shouldBe "/profile"
                        it.location.line shouldBe 1
                        it.location.column shouldBe 10
                        it.detail shouldBe "Catalog entry demo-cs/1 loads $identity"
                    }
                }
                file.writeText(profile220("demo-cs/3"))
                ProfileResolver(root, mapOf("demo-cs" to Path.of("profile.yaml")))
                    .resolve("demo-cs", Path.of("ttr.yaml"))
                    .single()
                    .major
                    .toString() shouldBe "3"
                file.writeText(profile220())
                ProfileResolver(root, mapOf("demo-cs/01" to Path.of("profile.yaml")))
                    .resolve("demo-cs/01", Path.of("ttr.yaml"))
                    .single()
                    .name shouldBe "demo-cs"
                file.writeText(profile220(extra = "extends: base/1\n"))
                root.resolve("base.yaml").writeText(profile220("base/2"))
                val inherited = ProfileResolver(root, mapOf("base/1" to Path.of("base.yaml")))
                shouldThrow<ProfileException> {
                    inherited.resolve(
                        "./profile.yaml",
                        Path.of("ttr.yaml"),
                    )
                }.detail shouldBe
                    "Catalog entry base/1 loads base/2"
            }
        }

        test("declaring files are root relative or absolute and 33-profile cycles are too deep") {
            root220 { root ->
                root.resolve("sub").createDirectories()
                root.resolve("sub/x.yaml").writeText(profile220())
                val resolver = ProfileResolver(root, emptyMap())
                val relative = resolver.resolve("./x.yaml", Path.of("sub/ttr.yaml"))
                relative shouldBe resolver.resolve("./x.yaml", root.resolve("sub/ttr.yaml"))
                (1..33).forEach {
                    root.resolve("p$it.yaml").writeText(profile220("p$it/1", "extends: ./p${it % 33 + 1}.yaml\n"))
                }
                shouldThrow<ProfileException> { resolver.resolve("./p1.yaml", Path.of("ttr.yaml")) }.also {
                    it.code shouldBe "ys/profile-invalid"
                    it.detail shouldBe "Profile chain exceeds 32 profiles"
                }
            }
        }

        test("canonical numeric major one always uses the bundle before catalog lookup") {
            root220 { root ->
                // A catalog override must never be read, even when it declares canonical/1.
                root.resolve("override.yaml").writeText(profile220("canonical/1", "writable: true\n"))
                val resolver =
                    ProfileResolver(
                        root,
                        mapOf(
                            "canonical/01" to Path.of("override.yaml"),
                            "canonical/001" to Path.of("missing.yaml"),
                        ),
                    )
                val bundled = resolver.resolve("canonical", Path.of("ttr.yaml"))
                listOf("canonical/1", "canonical/01", "canonical/001").forEach { ref ->
                    resolver.resolve(ref, Path.of("ttr.yaml")) shouldBe bundled
                    ProfileResolver(root, emptyMap()).resolve(ref, Path.of("ttr.yaml")) shouldBe bundled
                }
                root.resolve("child.yaml").writeText(profile220(extra = "extends: canonical/01\n"))
                resolver.resolve("./child.yaml", Path.of("ttr.yaml")).first() shouldBe bundled.single()
                shouldThrow<ProfileException> { resolver.resolve("canonical/2", Path.of("ttr.yaml")) }
                    .detail shouldBe "No catalog entry for canonical/2"
                root.resolve("other.yaml").writeText(profile220("canonical/2"))
                ProfileResolver(root, mapOf("canonical/2" to Path.of("other.yaml")))
                    .resolve("canonical/2", Path.of("ttr.yaml"))
                    .single()
                    .major
                    .toString() shouldBe "2"
            }
        }

        test("operation location buckets partition all operation paths without changing keys") {
            val source =
                profile220().replace(
                    "operations: []",
                    "operations:\n" +
                        (0..11).joinToString("\n") { "  - op: ignore\n    at: /x$it\n    reason: fixture" },
                )
            val profile = ProfileReader.read(source)
            val expected = profile.locations.filterKeys { it.startsWith("/operations/") }
            profile.operations.flatMap { it.locations.entries }.associate { it.toPair() } shouldBe expected
            profile.operations.forEachIndexed { index, op ->
                op.locations shouldBe
                    expected.filterKeys { it == "/operations/$index" || it.startsWith("/operations/$index/") }
            }
        }

        test("grammar ranges and reader data classes have authored-text value equality") {
            val range = GrammarRange.parse(">=0.18 <1.0")
            range shouldBe GrammarRange.parse(">=0.18 <1.0")
            range.hashCode() shouldBe GrammarRange.parse(">=0.18 <1.0").hashCode()
            range.toString() shouldBe ">=0.18 <1.0"
            range shouldNotBe GrammarRange.parse(">=0.18 <2.0")
            range shouldNotBe GrammarRange.parse(">=0.18  <1.0")
            val tree = "profile: demo-cs/1\ngrammar: '>=0.18 <1.0'\n"
            DeclarationReader.readTree(tree) shouldBe DeclarationReader.readTree(tree)
            ProfileReader.read(profile220()) shouldBe ProfileReader.read(profile220())
        }
    })
