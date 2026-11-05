// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.profile

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.*

class RecordEdgesSpec :
    FunSpec({
        test("nested IDs and whole-collection writes share the same canonical conflict ledger") {
            val nested =
                entityOps +
                    """
                    - {op: kinds, in: entity, at: /cols, id: /name, mapping: {a: attribute}}
                    - {op: rename, in: attribute, at: /typ, to: {kind: attribute, property: type}}
                    """.trimIndent()
            shouldThrow<ProfileException> {
                runOps("entity: {Customer: {cols: [{name: A}, {name: A}]}}", nested)
            }.code shouldBe "ys/profile-conflict"
            val overwrite =
                nested + "\n" +
                    "- {op: rename, in: entity, at: /other, to: {kind: entity, property: attributes}}"
            shouldThrow<ProfileException> {
                runOps("entity: {Customer: {cols: [{name: A}], other: []}}", overwrite)
            }.code shouldBe "ys/profile-conflict"
        }
        test("parent paths traverse nested canonical records and conflict with a nested writer") {
            val profile =
                entityOps +
                    """
                    - {op: kinds, in: entity, at: /cols, mapping: {a: attribute}}
                    - {op: nest, in: entity, at: /typ, to: {kind: entity, path: [attributes, type]}}
                    """.trimIndent()
            val result = runOps("entity: {Customer: {cols: {A: {}}, typ: string}}", profile)
            result
                .properties()
                .getValue("attributes")
                .jsonArray
                .first()
                .jsonObject
                .getValue("properties") shouldBe
                Json.parseToJsonElement("""{"type":"string"}""")
            shouldThrow<ProfileException> {
                runOps(
                    "entity: {Customer: {cols: {A: {typ: string}}, typ: string}}",
                    profile + "\n" +
                        "- {op: rename, in: attribute, at: /typ, to: {kind: attribute, property: type}}",
                )
            }.code shouldBe "ys/profile-conflict"
        }
        test("kinds refuses a scalar container and locates an explicit null discriminator") {
            val error = shouldThrow<ProfileException> { runOps("entity: scalar", entityOps) }
            error.code shouldBe "ys/profile-invalid"
            error.location.path shouldBe "/entity"
            shouldThrow<ProfileException> {
                runOps(
                    "items: [{kind: null, name: A}]",
                    "- {op: kinds, at: /items, kind: /kind, id: /name, mapping: {a: entity}}",
                )
            }.location.path shouldBe "/items/0/kind"
        }
        test("writing scope and structural record discovery do not depend on operation order") {
            val source = "entity: {Customer: {cols: {A: {typ: string}}}}"
            val lines =
                listOf(
                    "- {op: package, mapping: {}, default: demo}",
                    "- {op: kinds, at: /entity, mapping: {e: entity}}",
                    "- {op: kinds, in: entity, at: /cols, mapping: {a: attribute}}",
                    "- {op: rename, in: attribute, at: /typ, to: {kind: attribute, property: type}}",
                )
            runOps(source, lines.joinToString("\n")).documents.single().write() shouldBe
                runOps(source, lines.reversed().joinToString("\n")).documents.single().write()
        }
        test("canonical model grouping reuses semantics for CN and MD bindings and lexicon kinds") {
            val result =
                runOps(
                    "items: [{typ: a, name: A}, {typ: b, name: B}, {typ: c, name: C}, " +
                        "{typ: d, name: D}, {typ: e, name: E}, {typ: f, name: F}]",
                    "- {op: package, mapping: {}, default: demo}\n" +
                        "- {op: kinds, at: /items, kind: /typ, id: /name, mapping: " +
                        "{a: cn2er_kind, b: md2db_map, c: query, d: domain, e: term, f: variant}}",
                )
            result.documents.map { it.value.string("model") } shouldBe
                listOf("binding", "db", "md", "lexicon", "cn")
        }
        test("named and unnamed grammar members nest using their canonical envelopes") {
            val result =
                runOps(
                    "svety: {Demo: {engines: {Engine: {}}, grants: {access: {}}}}",
                    "- {op: package, mapping: {}, default: demo}\n" +
                        "- {op: kinds, at: /svety, mapping: {w: world}}\n" +
                        "- {op: kinds, in: world, at: /engines, mapping: {e: engine}}\n" +
                        "- {op: kinds, in: world, at: /grants, mapping: {g: grant}}",
                )
            val members =
                result
                    .properties()
                    .getValue("members")
                    .jsonArray
                    .map { it.jsonObject }
            members.first().string("id") shouldBe "Engine"
            members.last().string("kind") shouldBe "grant"
            ("id" in members.last()) shouldBe false
        }
    })
