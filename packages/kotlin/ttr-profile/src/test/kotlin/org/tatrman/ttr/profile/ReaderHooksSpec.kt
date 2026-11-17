// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.profile

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldEndWith
import kotlinx.serialization.json.*
import java.nio.file.Files
import kotlin.io.path.writeText

/**
 * review-330 (MW-P5 S5.1 repair): the two engine hooks a reader needs. ✅R330-A — `ProfileEngine.apply(…, sourceName)`
 * gives this run's records the pointer's string as `sourceName`, so links and overlay keys match it on the first
 * import. ✅R330-B — `ProfileResolver`'s shipped provider: a profile may extend one the caller ships.
 */
class ReaderHooksSpec :
    FunSpec({
        val ops =
            "- {op: package, mapping: {}, default: demo}\n" +
                "- {op: kinds, at: /entity, mapping: {entita: entity}}\n" +
                "- {op: rename, in: entity, at: /popis, to: {kind: entity, property: description}}\n" +
                "- {op: ignore, in: entity, at: /fqn, reason: the sidecar's sourceName}\n" +
                "- {op: kinds, at: /vztahy, mapping: {vztah: relation}}\n" +
                "- {op: link, in: relation, at: /od, to: {kind: relation, property: from}, by: sourceName}\n" +
                "- {op: link, in: relation, at: /do, to: {kind: relation, property: to}, by: sourceName}"
        val source = "entity:\n  A: {fqn: crm.A, popis: old}\n  B: {}\nvztahy:\n  R: {od: crm.A, do: B}\n"

        fun profile(overlay: String = "") =
            ProfileReader.read(
                "profile: demo-cs/1\ngrammar: '>=0.22 <1.0'\n${overlay}operations:\n$ops\n",
                "profile.yaml",
            )

        test("✅R330-A: the sourceName pointer names this run's records for links, overlay keys and the sidecar") {
            val result =
                ProfileEngine.apply(
                    listOf(ForeignTree.read(source, "one.yaml")),
                    listOf(profile("overlay: {crm.A: {description: curated}}\n")),
                    sourceName = "/fqn",
                )
            val relation =
                result.documents
                    .single()
                    .value
                    .getValue("defs")
                    .jsonArray
                    .single {
                        it.jsonObject
                            .getValue("id")
                            .jsonPrimitive.content == "R"
                    }.jsonObject
            relation.getValue("properties") shouldBe
                Json.parseToJsonElement("""{"from":"demo.er.entity.A","to":"demo.er.entity.B"}""")
            result.report.overridden.map { Triple(it.qname, it.property, it.to) } shouldBe
                listOf(Triple("demo.er.entity.A", "description", JsonPrimitive("curated")))
            // A record the pointer selects no string in keeps its id.
            result.sources.associate { it.qname to it.sourceName } shouldBe
                mapOf("demo.er.entity.A" to "crm.A", "demo.er.entity.B" to "B", "demo.er.relation.R" to "R")
        }

        test("✅R330-A: without the pointer the FQN names no record of the run") {
            shouldThrow<ProfileException> {
                ProfileEngine.apply(
                    listOf(ForeignTree.read(source, "one.yaml")),
                    listOf(profile()),
                )
            }.detail shouldContain "crm.A"
        }

        test("✅R330-B: a file profile extends a shipped one; a catalog entry of that name wins") {
            val root = Files.createTempDirectory("r330-shipped").toRealPath()
            val shipped =
                mapOf(
                    "base/1" to "profile: base/1\ngrammar: '>=0.22 <1.0'\noperations:\n$ops\n",
                    "loose/1" to "profile: loose/1\ngrammar: '>=0.22 <1.0'\nextends: ./local.yaml\noperations: []\n",
                    "liar/1" to "profile: other/1\ngrammar: '>=0.22 <1.0'\noperations: []\n",
                )
            val child = root.resolve("child.yaml")

            fun extending(parent: String) =
                child.writeText("profile: mine/1\ngrammar: '>=0.22 <1.0'\nextends: $parent\noperations: []\n")
            extending("base/1")
            val chain = ProfileResolver(root, emptyMap()) { shipped[it] }.resolve("./child.yaml", child)
            chain.map { "${it.name}/${it.major}" } shouldBe listOf("base/1", "mine/1")
            chain.first().file shouldBe "<shipped>/profiles/base/1.yaml"
            ProfileEngine
                .apply(listOf(ForeignTree.read(source, "one.yaml")), chain, sourceName = "/fqn")
                .documents
                .single()
                .value
                .getValue("package") shouldBe JsonPrimitive("demo")

            // Explicit project configuration first.
            root.resolve("cat.yaml").writeText("profile: base/1\ngrammar: '>=0.22 <1.0'\noperations: []\n")
            ProfileResolver(root, mapOf("base/1" to root.resolve("cat.yaml"))) { shipped[it] }
                .resolve("./child.yaml", child)
                .first()
                .file shouldEndWith "cat.yaml"

            // Without the provider: as before.
            shouldThrow<ProfileException> {
                ProfileResolver(root, emptyMap()).resolve("./child.yaml", child)
            }.detail shouldBe "No catalog entry for base/1"

            // A shipped profile extends shipped profiles only, and declares the identity it is shipped as.
            extending("loose/1")
            shouldThrow<ProfileException> {
                ProfileResolver(root, emptyMap()) { shipped[it] }.resolve("./child.yaml", child)
            }.let {
                it.code shouldBe "ys/profile-not-found"
                it.location.file shouldBe "<shipped>/profiles/loose/1.yaml"
                it.detail shouldContain "extends only shipped profiles"
            }
            extending("liar/1")
            shouldThrow<ProfileException> {
                ProfileResolver(root, emptyMap()) { shipped[it] }.resolve("./child.yaml", child)
            }.detail shouldBe "Shipped profile liar/1 loads other/1"
        }
    })
