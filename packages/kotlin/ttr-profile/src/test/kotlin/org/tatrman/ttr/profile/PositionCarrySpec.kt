// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.profile

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.*
import kotlin.io.path.*

/**
 * S3.3 T5 — the position-carry invariant (contracts §3): for every writing op family, a canonical-validation failure
 * (the P2 codec) on a transformed value names the dialect file, line and column of the key the author typed.
 */
class PositionCarrySpec :
    FunSpec({
        data class Case(
            val family: String,
            val source: String,
            val op: String,
            val path: String,
            val line: Int,
            val column: Int,
        )
        listOf(
            Case(
                "rename",
                "entity:\n  E:\n    popis: [wrong]\n",
                "{op: rename, in: entity, at: /popis, to: {kind: entity, property: description}}",
                "/entity/E/popis",
                3,
                5,
            ),
            Case(
                "nest",
                "entity:\n  E:\n    klice: 5\n",
                "{op: nest, in: entity, at: /klice, to: {kind: entity, path: [search, keywords]}}",
                "/entity/E/klice",
                3,
                5,
            ),
            Case(
                "unnest",
                "entity:\n  E:\n    obal:\n      popis: [wrong]\n",
                "{op: unnest, in: entity, at: /obal/popis, to: {kind: entity, property: description}}",
                "/entity/E/obal/popis",
                4,
                7,
            ),
            // search.method without search.searchable fails on the search object; the authored key that wrote it is named.
            Case(
                "values",
                "entity:\n  E:\n    hledani: presne\n",
                "{op: values, in: entity, at: /hledani, to: {kind: entity, path: [search, method]}, " +
                    "mapping: {presne: {name: EXACT}}}",
                "/entity/E/hledani",
                3,
                5,
            ),
            Case(
                "shape",
                "entity:\n  E:\n    vzor: {a: 1}\n",
                "{op: shape, in: entity, at: /vzor, to: {kind: entity, path: [search, patterns]}, template: [{input: ''}]}",
                "/entity/E/vzor",
                3,
                5,
            ),
            Case(
                "link",
                "entity:\n  E:\n    hledat: E\n",
                "{op: link, in: entity, at: /hledat, to: {kind: entity, path: [search, searchable]}, by: sourceName}",
                "/entity/E/hledat",
                3,
                5,
            ),
        ).forEach { case ->
            test("${case.family}: a canonical failure on the transformed value names the authored key") {
                val error =
                    shouldThrow<ProfileException> { runOps(case.source, entityOps + "- ${case.op}", "dialect.yaml") }
                withClue(error.message) {
                    error.code shouldBe "ys/profile-invalid"
                    error.location.file shouldBe "dialect.yaml"
                    error.location.path shouldBe case.path
                    error.location.line shouldBe case.line
                    error.location.column shouldBe case.column
                }
            }
        }
        test("an overlay value that fails canonical validation names the overlay's own position") {
            val profile =
                ProfileReader.read(
                    "profile: demo-ov/1\ngrammar: '>=0.22 <1.0'\noverlay:\n  E: {description: [wrong]}\noperations:\n" +
                        entityOps,
                    "profile.yaml",
                )
            val error =
                shouldThrow<ProfileException> {
                    ProfileEngine.apply(listOf(ForeignTree.read("entity:\n  E: {}\n", "dialect.yaml")), listOf(profile))
                }
            error.location.file shouldBe "profile.yaml"
            error.location.path shouldBe "/overlay/E/description"
            error.location.line shouldBe 4
        }
        test("the report JSON keeps its shape: documents[], ignored[], overridden[] (snapshots)") {
            val values = demoChain("values.yaml")
            val inputs =
                demoRoot
                    .resolve("input")
                    .listDirectoryEntries("*.yaml")
                    .sorted()
                    .map { ForeignTree.read(it.readText(), it.name) } + ForeignTree.read("x: 1", "layouts/view.yaml")
            ProfileEngine.apply(inputs, values, demoRoot).reportJson() shouldBe
                Json.parseToJsonElement(demoRoot.resolve("expected/report.json").readText())
            ProfileEngine.apply(linkInputs(), demoChain("overlay-inline.yaml"), demoRoot).reportJson() shouldBe
                Json.parseToJsonElement(demoRoot.resolve("links/expected/report.json").readText())
        }
    })
