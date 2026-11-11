// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.profile

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.*

/** S3.2b (✅D-MW-67 c′): one foreign node, one record per canonical kind. */
class RecordFanoutSpec :
    FunSpec({
        val fanout =
            "- {op: package, mapping: {}, default: demo}\n" +
                "- {op: kinds, at: /entity, mapping: {entita: entity}}\n" +
                "- {op: kinds, at: /entity, mapping: {entita: er2db_entity}}\n"

        fun ApplyResult.model(name: String) = documents.single { it.value.string("model") == name }

        fun EmittedDocument.defs() = value.getValue("defs").jsonArray.map { it.jsonObject }

        fun failure(
            source: String,
            ops: String,
        ) = shouldThrow<ProfileException> { runOps(source, ops) }

        test("(i) two kinds operations over one at yield two records per node with the same id and the node's span") {
            val result = runOps("entity:\n  A: {}\n", fanout)
            val entity = result.model("er")
            val binding = result.model("binding")
            entity.defs().single().string("kind") shouldBe "entity"
            binding.defs().single().string("kind") shouldBe "er2db_entity"
            entity.defs().single().string("id") shouldBe "A"
            binding.defs().single().string("id") shouldBe "A"
            entity.locations.getValue("/defs/0") shouldBe binding.locations.getValue("/defs/0")
            entity.locations.getValue("/defs/0").path shouldBe "/entity/A"
        }

        test("(ii) the entity goes to the er document and the er2db_entity to binding, each in author order") {
            val result = runOps("entity:\n  B: {}\n  A: {}\n  C: {}\n", fanout)
            result.documents.map { it.value.string("model") } shouldBe listOf("er", "binding")
            result.model("er").defs().map { it.string("id") } shouldBe listOf("B", "A", "C")
            result.model("binding").defs().map { it.string("id") } shouldBe listOf("B", "A", "C")
            result
                .model("binding")
                .defs()
                .map { it.string("kind") }
                .distinct() shouldBe listOf("er2db_entity")
        }

        test("(iii) two operations mapping one node to the same kind conflict at the record, as before") {
            val error = failure("entity:\n  A: {}\n", fanout + "- {op: kinds, at: /entity, mapping: {x: entity}}")
            error.code shouldBe "ys/profile-conflict"
            error.detail shouldBe "Two kinds operations select the same record"
            error.location.line shouldBe 2
            error.location.column shouldBe 3
        }

        test("(iv) a writer scoped to one kind writes only that sibling; both may read the same key, consumed") {
            val result =
                runOps(
                    "entity:\n  A: {popis: Odběratel, tab: T}\n",
                    fanout +
                        """
                        - {op: rename, in: entity, at: /popis, to: {kind: entity, property: description}}
                        - {op: rename, in: er2db_entity, at: /popis, to: {kind: er2db_entity, property: description}}
                        - {op: rename, in: er2db_entity, at: /${'$'}key, to: {kind: er2db_entity, property: entity}}
                        - {op: rename, in: er2db_entity, at: /tab, to: {kind: er2db_entity, property: target}}
                        """.trimIndent(),
                )
            result
                .model("er")
                .defs()
                .single()
                .getValue("properties") shouldBe
                buildJsonObject { put("description", "Odběratel") }
            result
                .model("binding")
                .defs()
                .single()
                .getValue("properties") shouldBe
                buildJsonObject {
                    put("description", "Odběratel")
                    put("entity", "A")
                    put("target", "T")
                }
            // Without the er2db_entity readers `tab` is left over: consumption is still checked per node.
            failure(
                "entity:\n  A: {popis: X, tab: T}\n",
                fanout + "- {op: rename, in: entity, at: /popis, to: {kind: entity, property: description}}",
            ).code shouldBe "ys/unknown-key"
        }

        test("(v) a nested kinds op finds only its scope's records; the sibling never parents; binding still refused") {
            val result =
                runOps(
                    "entity:\n  A: {sloupce: {kod: {}}}\n",
                    fanout + "- {op: kinds, in: entity, at: /sloupce, mapping: {sloupec: attribute}}",
                )
            val entity = result.model("er").defs().single()
            entity
                .getValue("properties")
                .jsonObject
                .getValue("attributes")
                .jsonArray
                .map { it.jsonObject.string("id") } shouldBe listOf("kod")
            // The er2db_entity sibling is neither nested under the entity nor a parent of the attribute.
            result
                .model("binding")
                .defs()
                .single()
                .getValue("properties") shouldBe JsonObject(emptyMap())
            result.defs().map { it.string("kind") } shouldBe listOf("entity", "er2db_entity")
            val nested =
                failure(
                    "entity:\n  A:\n    vazby: {x: {}}\n",
                    fanout + "- {op: kinds, in: entity, at: /vazby, mapping: {v: er2db_attribute}}",
                )
            nested.code shouldBe "ys/profile-invalid"
            nested.detail shouldBe "er2db_attribute cannot nest unambiguously under entity"
        }
    })
