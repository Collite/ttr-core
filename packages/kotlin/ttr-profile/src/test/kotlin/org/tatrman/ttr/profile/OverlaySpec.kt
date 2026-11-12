// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.profile

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
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

        // review-251 F9 (✅R251-A): a sourceName key narrows by property, then prefers the record outside binding.
        fun fanout(overlay: String): ApplyResult {
            val child =
                ProfileReader.read(
                    "profile: demo-ov/1\ngrammar: '>=0.22 <1.0'\noverlay:\n  $overlay\noperations: []\n",
                    demoRoot.resolve("profiles/ov.yaml").toAbsolutePath().toString(),
                )
            val input = demoRoot.resolve("input/07-fanout.yaml")
            return ProfileEngine.apply(
                listOf(ForeignTree.read(input.readText(), input.name)),
                demoChain() + child,
                demoRoot,
            )
        }
        test("a sourceName key on a fan-out node picks the record that defines the property, else the primary one") {
            fanout("Odberatel: {description: X}").report.overridden shouldBe
                listOf(
                    Overridden(
                        "demo.er.entity.Odberatel",
                        "description",
                        JsonPrimitive("Odběratel"),
                        JsonPrimitive("X"),
                    ),
                )
            val filter = fanout("Odberatel: {whereFilter: {sql: X}}")
            filter.report.overridden
                .single()
                .qname shouldBe "demo.binding.er2db_entity.Odberatel"
            filter.documents
                .single { it.value.string("model") == "binding" }
                .value
                .getValue("defs")
                .jsonArray
                .first()
                .jsonObject
                .getValue("properties")
                .jsonObject
                .getValue("whereFilter") shouldBe Json.parseToJsonElement("""{"sql":"X"}""")
        }
        test("two primary records with one sourceName stay ambiguous, and the message names both qnames") {
            val chain =
                profile(
                    "\n  X: {description: Y}",
                    operations =
                        "- {op: package, mapping: {a: alfa, b: beta}, default: demo}\n" +
                            "- {op: kinds, at: /entity, mapping: {entita: entity}}\n",
                )
            val error =
                shouldThrow<ProfileException> {
                    ProfileEngine.apply(
                        listOf(
                            ForeignTree.read("entity:\n  X: {}\n", "a.yaml"),
                            ForeignTree.read("entity:\n  X: {}\n", "b.yaml"),
                        ),
                        listOf(chain),
                    )
                }
            error.code shouldBe "xm/link-ambiguous"
            error.detail shouldContain "alfa.er.entity.X, beta.er.entity.X"
        }

        test("a relative profile label bases a path overlay on the root, never the cwd (review-251 F2)") {
            val root = Files.createTempDirectory("s33-relative")
            root.resolve("profiles").createDirectories()
            root.resolve("profiles/ov.yaml").writeText("A:\n  description: From file\n")
            val relative = profile(" ./ov.yaml", "profiles/p.yaml")
            val inputs = listOf(ForeignTree.read(source, "one.yaml"))
            ProfileEngine.apply(inputs, listOf(relative), root).properties().string("description") shouldBe "From file"
            val noRoot = shouldThrow<ProfileException> { ProfileEngine.apply(inputs, listOf(relative)) }
            noRoot.code shouldBe "ys/profile-not-found"
            noRoot.detail shouldContain "needs a root or an absolute profile file"
        }

        test("renames.yaml is read as ttr-metadata reads it: empty is none, --- is fine, errors at their line (F5)") {
            fun orphan(ledger: String): ProfileException {
                val root = Files.createTempDirectory("s33-renames")
                root.resolve("renames.yaml").writeText(ledger)
                return shouldThrow<ProfileException> {
                    ProfileEngine.apply(
                        listOf(ForeignTree.read(source, "one.yaml")),
                        listOf(profile("\n  Klient: {description: X}")),
                        root,
                    )
                }
            }
            listOf("", "# nothing yet\n").forEach { ledger ->
                val error = orphan(ledger)
                error.code shouldBe "xm/overlay-orphan"
                error.suggestion.shouldBeNull()
            }
            orphan("---\n- {from: Klient, to: Zakaznik, since: 2026-11-01}\n").suggestion shouldBe
                Json.parseToJsonElement(
                    """{"delta":{"renamed":{"from":"Klient","to":"Zakaznik","since":"2026-11-01"}}}""",
                )
            val broken = orphan("- [Klient\n")
            broken.code shouldBe "ys/profile-invalid"
            broken.location.file shouldBe "renames.yaml"
            (broken.location.line in 1..2) shouldBe true
        }
    })
