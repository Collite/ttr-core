// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.profile

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.*
import java.nio.file.Files
import kotlin.io.path.*

/** S3.3 T1: the overlay input (contracts §3; XM D-9/D-10). */
class OverlaySpec :
    FunSpec({
        fun profile(
            overlay: String,
            file: String = "profile.yaml",
            name: String = "demo-ov",
            operations: String =
                entityOps + "- {op: rename, in: entity, at: /popis, to: {kind: entity, property: description}}\n",
        ) = ProfileReader.read(
            "profile: $name/1\ngrammar: '>=0.22 <1.0'\noverlay:$overlay\noperations:${if (operations.isEmpty()) " []" else "\n$operations"}\n",
            file,
        )
        val source = "entity:\n  A: {popis: Áčko}\n  B: {}\n"

        fun apply(vararg chain: ProfileDefinition) =
            ProfileEngine.apply(listOf(ForeignTree.read(source, "one.yaml")), chain.toList())

        test("inline overlay by qname or sourceName wins; overridden[] lists only properties both sides supplied") {
            val result =
                apply(
                    profile(
                        "\n  demo.er.entity.A: {description: Overlay A, tags: [x]}\n  B: {description: Overlay B}",
                    ),
                )
            result.defs().map { it.getValue("properties") } shouldBe
                listOf(
                    Json.parseToJsonElement("""{"description":"Overlay A","tags":["x"]}"""),
                    Json.parseToJsonElement("""{"description":"Overlay B"}"""),
                )
            result.report.overridden shouldBe
                listOf(Overridden("demo.er.entity.A", "description", JsonPrimitive("Áčko"), JsonPrimitive("Overlay A")))
        }
        test("a path overlay resolves relative to its profile; ancestor first, so a child entry wins") {
            val root = Files.createTempDirectory("s33-overlay")
            root.resolve("profiles").createDirectories()
            root.resolve("profiles/ov.yaml").writeText("A:\n  description: From file\n")
            val parent = profile(" ./ov.yaml", root.resolve("profiles/parent.yaml").toString(), "demo-parent")
            val child = profile("\n  A: {description: From child}", "child.yaml", operations = "")
            val result = ProfileEngine.apply(listOf(ForeignTree.read(source, "one.yaml")), listOf(parent, child), root)
            result.properties().string("description") shouldBe "From child"
            result.report.overridden.map { it.from to it.to } shouldBe
                listOf(
                    JsonPrimitive("Áčko") to JsonPrimitive("From file"),
                    JsonPrimitive("From file") to JsonPrimitive("From child"),
                )
            val elsewhere = Files.createTempDirectory("s33-outside").resolve("ov.yaml").apply { writeText("A: {}\n") }
            val outside =
                shouldThrow<ProfileException> {
                    ProfileEngine.apply(
                        emptyList(),
                        listOf(profile(" '$elsewhere'", root.resolve("profiles/p.yaml").toString())),
                        root,
                    )
                }
            outside.code shouldBe "ys/profile-not-found"
        }
        test("an orphan is xm/overlay-orphan at the authored key, with delta.renamed when renames.yaml names it") {
            val error =
                shouldThrow<ProfileException> {
                    ProfileEngine.apply(linkInputs(), demoChain("overlay-file.yaml"), demoRoot)
                }
            error.code shouldBe "xm/overlay-orphan"
            error.location.file shouldBe demoRoot.resolve("profiles/overlay.yaml").toRealPath().toString()
            error.location.path shouldBe "/Klient"
            error.location.line shouldBe 4
            error.location.column shouldBe 1
            error.suggestion shouldBe
                Json.parseToJsonElement(
                    """{"delta":{"renamed":{"from":"Klient","to":"Zakaznik","since":"2026-11-01"}}}""",
                )
            val bare = shouldThrow<ProfileException> { apply(profile("\n  Nikdo: {description: X}")) }
            bare.code shouldBe "xm/overlay-orphan"
            bare.suggestion.shouldBeNull()
        }
    })
