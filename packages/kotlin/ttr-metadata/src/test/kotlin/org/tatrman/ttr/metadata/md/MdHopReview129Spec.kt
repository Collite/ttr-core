// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.metadata.md

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.*
import org.tatrman.ttr.metadata.source.*
import java.nio.file.Files

class MdHopReview129Spec :
    StringSpec({
        val cases = Json.parseToJsonElement(Files.readString(MdHopFixture.root.resolve("review-129.json"))).jsonArray
        for (entry in cases) {
            val c = entry.jsonObject
            c.getValue("name").jsonPrimitive.content {
                val overrides = c.getValue("overrides").jsonObject.mapValues { it.value.jsonPrimitive.content }
                val (docs, resolver) = MdHopFixture.project(MdHopFixture.sources() + overrides)
                val hop = CompositeMdErHop(docs, resolver)
                val bearer = c["bearer"]?.jsonPrimitive?.content
                for ((md, er) in c.getValue("expected").jsonObject) {
                    hop.qualifiedTargetOf(md, bearer).erTarget shouldBe
                        er.jsonPrimitive.contentOrNull
                }
                for ((attr, cols) in c["origins"]?.jsonObject.orEmpty()) {
                    hop.derived.physical.erAttrToColumn[attr]
                        .orEmpty()
                        .sorted() shouldBe
                        cols.jsonArray.map { it.jsonPrimitive.content }
                }
                for ((md, message) in c["messages"]?.jsonObject.orEmpty()) {
                    hop.qualifiedTargetOf(md).message shouldContain
                        message.jsonPrimitive.content
                }
                for ((entity, tables) in c["tables"]?.jsonObject.orEmpty()) {
                    hop.derived.physical.entityToTable[entity]
                        .orEmpty()
                        .sorted() shouldBe
                        tables.jsonArray.map { it.jsonPrimitive.content }
                }
                c["opaque"]?.jsonPrimitive?.content?.let { attr ->
                    hop.derived.lineageIssues.any { it.element == attr } shouldBe
                        true
                }
                c["relation"]?.jsonPrimitive?.content?.let { key ->
                    hop.derived.physical.relations
                        .single { it.key == key }
                        .pairs shouldBe
                        listOf("er.entity.store_sales.customer" to "er.entity.customer.sk")
                }
                c["override"]?.jsonPrimitive?.content?.let { md ->
                    hop.issues(md).any { it.slug == "md/er-hop-override" } shouldBe
                        true
                }
                c["issue"]?.jsonPrimitive?.content?.let { slug ->
                    mdHopReport(docs, resolver).issues.any { it.slug == slug } shouldBe
                        true
                }
            }
        }
        "lineage providers cannot throw out of a model derivation" {
            val (docs, resolver) = MdHopFixture.project()
            val throwing =
                object : QueryLineage {
                    override fun originsOf(
                        context: QueryLineageContext,
                        query: String,
                        column: String,
                        ordinal: Int,
                    ): Set<String>? = error("broken provider")
                }
            DerivedMdErHop(docs, resolver, lineage = throwing).lineageIssues.size shouldBe 5
        }
    })
