// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.profile

import io.kotest.assertions.withClue
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.serialization.json.*
import org.tatrman.ttr.yaml.CanonicalYaml
import org.tatrman.ttr.yaml.CanonicalYamlException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.*

private fun definition(
    name: String = "demo-cs/1",
    grammar: String = ">=0.18 <1.0",
    extra: String = "",
) = "profile: $name\ngrammar: '$grammar'\noperations: []\n$extra"

private inline fun <T> withRoot(block: (Path) -> T): T {
    Path.of("build").createDirectories()
    val root = Files.createTempDirectory(Path.of("build"), "profiles-").toRealPath()
    return try {
        block(root)
    } finally {
        root.toFile().deleteRecursively()
    }
}

class ProfileLoadingSpec :
    FunSpec({
        test("definition and declaration branches preserve data and authored spans without executing operations") {
            val source =
                """
                profile: demo-cs/1
                grammar: '>=0.18 <1.0'
                writable: true
                overlay: {Customer: {description: zákazník}}
                operations:
                  - op: ignore
                    at: /poznámka
                    reason: fixture
                  - op: codes
                    mapping: {E_DEMO: ys/unknown-key}
                """.trimIndent()
            val profile = ProfileReader.read(source, "profile.yaml")
            profile.name shouldBe "demo-cs"
            profile.major.toString() shouldBe "1"
            profile.writable shouldBe true
            profile.overlay shouldBe Json.parseToJsonElement("""{"Customer":{"description":"zákazník"}}""")
            profile.operations.map {
                it.value
                    .getValue("op")
                    .jsonPrimitive.content
            } shouldBe listOf("ignore", "codes")
            profile.operations
                .first()
                .location.line shouldBe 6
            profile.locations.getValue("/operations/0/at").line shouldBe 7
            profile.locations.getValue("/operations/0/at").column shouldBe 9
            ProfileReader.read(definition()).writable shouldBe false
            ProfileReader.read(definition(extra = "overlay: ./overlay.yaml\n")).overlay shouldBe
                JsonPrimitive("./overlay.yaml")
            val tree = DeclarationReader.readTree("profile: demo-cs/1\ngrammar: '>=0.18 <1.0'\n", "ttr.yaml")
            tree.profile shouldBe "demo-cs/1"
            val header = "ttr: {profile: canonical/1, grammar: '>=0.18 <1.0'}\nentities: []\n"
            DeclarationReader.select(header, "one.yaml", tree)!!.profile shouldBe "canonical/1"
            DeclarationReader.select("entities: []\n", "one.yaml", tree) shouldBe tree
            DeclarationReader.select("entities: []\n", "one.yaml", null) shouldBe null
            DeclarationReader.readFile("ttr: '0.22'\nmodel: er\n", "canonical.yaml") shouldBe null
            shouldThrow<ProfileException> { ProfileReader.read("profile: demo-cs/1\ngrammar: '>=0.18'\n") }
                .code shouldBe "ys/profile-invalid"
            shouldThrow<ProfileException> { DeclarationReader.readTree(definition()) }
                .location.path shouldBe "/operations"
            shouldThrow<ProfileException> {
                DeclarationReader.readFile("entities: []\nttr: {profile: demo-cs/1, grammar: '>=0.18'}\n")
            }.location.path shouldBe "/ttr"
            shouldThrow<ProfileException> { ProfileReader.read(definition(extra = "surprise: 1\n")) }
                .location.path shouldBe "/surprise"
            shouldThrow<ProfileException> {
                ProfileReader.read(
                    definition().replace(
                        "operations: []",
                        "operations: [{op: rename, at: /x, to: {kind: entity, property: typo}}]",
                    ),
                )
            }.code shouldBe "ys/profile-invalid"
        }

        test("every YAML safety refusal has a file, one-based position and key path") {
            val cases =
                listOf(
                    "overlay: {x: {v: &a 1}}\n" to "/overlay/x/v",
                    "overlay: {x: {v: *missing}}\n" to "/overlay/x/v",
                    "overlay: {x: {<<: {v: 1}}}\n" to "/overlay/x/<<",
                    "overlay: {x: {v: !!python/object foo}}\n" to "/overlay/x/v",
                    "overlay: {x: {v: !pl hello}}\n" to "/overlay/x/v",
                    "overlay: {x: {v: .inf}}\n" to "/overlay/x/v",
                    "overlay: {x: {v: .nan}}\n" to "/overlay/x/v",
                    "overlay: {x: {v: 1, v: 2}}\n" to "/overlay/x/v",
                    "overlay: {x: {1: value}}\n" to "/overlay/x/1",
                    "overlay: {x: {v: [unterminated}}\n" to "/overlay/x/v",
                )
            cases.forEach { (extra, path) ->
                val error =
                    withClue(
                        extra,
                    ) { shouldThrow<ProfileException> { ProfileReader.read(definition(extra = extra), "unsafe.yaml") } }
                error.code shouldBe "ys/profile-invalid"
                error.location.file shouldBe "unsafe.yaml"
                error.location.line shouldBe 4
                (error.location.column > 0) shouldBe true
                error.location.path shouldBe path
            }
            listOf(
                "%YAML 1.1\n---\n${definition()}",
                definition() + "---\nprofile: other/1\n",
                definition(extra = "overlay: {x: {v: " + "[".repeat(65) + "0" + "]".repeat(65) + "}}\n"),
                definition(extra = "#" + "x".repeat(3_000_001)),
                definition(extra = "overlay: {x: {v: \u0000}}\n"),
            ).forEach { source ->
                val error =
                    withClue(
                        source.take(90),
                    ) { shouldThrow<ProfileException> { ProfileReader.read(source, "unsafe.yaml") } }
                error.code shouldBe "ys/profile-invalid"
                error.location.file shouldBe "unsafe.yaml"
                (error.location.line > 0 && error.location.column > 0) shouldBe true
            }
        }

        test("comparator sets and alternatives check declarations and every ancestor against packaged grammar") {
            listOf(
                ">=0.18 <1.0" to true,
                "<0.22" to false,
                "0.22" to true,
                "=0.22.0" to true,
                ">0.22" to false,
                "<=0.22" to true,
                ">=0.23 || >=0.18 <0.23" to true,
                ">=1.0 || <0.18" to false,
                ">= 0.18 < 1.0" to true,
                "0.22.1" to false,
            ).forEach { (range, accepted) -> GrammarRange.parse(range).admits("0.22") shouldBe accepted }
            listOf(
                "^0.22",
                "~0.22",
                "*",
                "0.22-rc1",
                "0.22 - 1.0",
                "",
                "0.22 ||",
                "0.22.0.1",
                "0",
                ">=0.22 junk",
            ).forEach { range ->
                shouldThrow<ProfileException> { ProfileReader.read(definition(grammar = range), "range.yaml") }
                    .also {
                        it.code shouldBe "ys/profile-invalid"
                        it.location.path shouldBe "/grammar"
                        it.location.line shouldBe 2
                    }
            }
            val metadata =
                Json
                    .parseToJsonElement(
                        ProfileReader::class.java
                            .getResourceAsStream(
                                "/schemas/canonical-metadata.json",
                            )!!
                            .bufferedReader()
                            .use {
                                it.readText()
                            },
                    ).jsonObject
            GrammarRange.runningGrammar shouldBe metadata.getValue("version").jsonPrimitive.content
            withRoot { root ->
                root.resolve("base.yaml").writeText(definition("base/1", "<0.18"))
                root.resolve("child.yaml").writeText(definition(extra = "extends: ./base.yaml\n"))
                val resolver = ProfileResolver(root, mapOf("demo-cs/1" to Path.of("child.yaml")))
                shouldThrow<ProfileException> { resolver.resolve("demo-cs/1", root.resolve("ttr.yaml")) }
                    .also {
                        it.code shouldBe "ys/profile-grammar-mismatch"
                        it.location.file shouldBe root.resolve("base.yaml").toString()
                        it.location.path shouldBe "/grammar"
                    }
                val declaration =
                    DeclarationReader.readTree(
                        "profile: canonical/1\ngrammar: '<0.18'\n",
                        root.resolve("ttr.yaml").toString(),
                    )
                shouldThrow<ProfileException> { resolver.resolve(declaration) }.code shouldBe
                    "ys/profile-grammar-mismatch"
            }
        }

        test("local resolution confines real paths, reports failures and returns isolated ancestor-to-child chains") {
            withRoot { root ->
                root.resolve("sub").createDirectories()
                root.resolve("base.yaml").writeText(definition("base/1", extra = "overlay: ./base-overlay.yaml\n"))
                root.resolve("sub/child.yaml").writeText(definition(extra = "extends: ../base.yaml\n"))
                val resolver = ProfileResolver(root, mapOf("demo-cs/1" to Path.of("sub/child.yaml")))
                val chain = resolver.resolve("demo-cs/1", root.resolve("ttr.yaml"))
                chain.map { it.name } shouldBe listOf("base", "demo-cs")
                chain.first().overlay shouldBe JsonPrimitive("./base-overlay.yaml")
                resolver.resolve("./child.yaml", root.resolve("sub/file.yaml")).map { it.name } shouldBe
                    listOf("base", "demo-cs")
                ProfileResolver(root, emptyMap()).resolve("canonical", root.resolve("ttr.yaml")).single().name shouldBe
                    "canonical"
                resolver.resolve(null).single().name shouldBe "canonical"
                listOf("missing/1", "./absent.yaml", "../escape.yaml").forEach { ref ->
                    shouldThrow<ProfileException> { resolver.resolve(ref, root.resolve("ttr.yaml")) }
                        .code shouldBe "ys/profile-not-found"
                }
                val outside = root.parent.resolve("outside-${root.fileName}.yaml")
                try {
                    outside.writeText(definition("outside/1"))
                    Files.createSymbolicLink(root.resolve("escape.yaml"), outside)
                    shouldThrow<ProfileException> { resolver.resolve("./escape.yaml", root.resolve("ttr.yaml")) }
                        .code shouldBe "ys/profile-not-found"
                    ProfileResolver(root, mapOf("demo-cs/1" to outside)).let { escaping ->
                        shouldThrow<ProfileException> { escaping.resolve("demo-cs/1", root.resolve("ttr.yaml")) }
                            .code shouldBe "ys/profile-not-found"
                    }
                } finally {
                    outside.deleteIfExists()
                }
                root.resolve("base.yaml").writeText(definition("base/1", extra = "extends: ./sub/child.yaml\n"))
                shouldThrow<ProfileException> { resolver.resolve("demo-cs/1", root.resolve("ttr.yaml")) }
                    .also {
                        it.code shouldBe "ys/profile-cycle"
                        it.location.path shouldBe "/extends"
                    }
                (1..33).forEach { i ->
                    root.resolve("p$i.yaml").writeText(
                        definition(
                            "p$i/1",
                            extra =
                                if (i <
                                    33
                                ) {
                                    "extends: ./p${i + 1}.yaml\n"
                                } else {
                                    ""
                                },
                        ),
                    )
                }
                shouldThrow<ProfileException> { resolver.resolve("./p1.yaml", root.resolve("ttr.yaml")) }
                    .code shouldBe "ys/profile-invalid"
                root.resolve("p32.yaml").writeText(definition("p32/1"))
                resolver.resolve("./p1.yaml", root.resolve("ttr.yaml")).size shouldBe 32
                ProfileResolver(root, mapOf("demo-cs/1" to Path.of("p32.yaml")))
                    .resolve("demo-cs/1", root.resolve("ttr.yaml"))
                    .single()
                    .name shouldBe "p32"
            }
        }

        test(
            "canonical identity delegates to the P2 codec, preserves twin bytes and leaves located failures unchanged",
        ) {
            val fixtures = Path.of("../../../tests/conformance/fixtures")
            val names = listOf("security", "graph", "localized", "import")
            val yamlFiles =
                Files.walk(fixtures).use { paths ->
                    paths.filter { it.extension == "yaml" }.toList().sorted()
                }
            yamlFiles.isEmpty() shouldBe false
            // The complete committed twin corpus also covers definitions, imports, localized values, security and graph.
            yamlFiles.forEach { path ->
                val input = path.readText()
                CanonicalIdentity.apply(input, path.toString()) shouldBe input
            }
            names.forEach { name -> yamlFiles.any { name in it.toString().lowercase() } shouldBe true }
            val bad = "ttr: '0.22'\nmodel: typo\n"
            val original = shouldThrow<CanonicalYamlException> { CanonicalYaml.read(bad, "broken.yaml") }
            val identity = shouldThrow<CanonicalYamlException> { CanonicalIdentity.apply(bad, "broken.yaml") }
            identity.message shouldBe original.message
            identity.line shouldBe 2
            identity.column shouldBe 8
            identity.detail shouldNotBe ""
        }
    })
