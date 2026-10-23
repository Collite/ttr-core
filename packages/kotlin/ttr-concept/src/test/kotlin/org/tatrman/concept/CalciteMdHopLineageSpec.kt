// SPDX-License-Identifier: Apache-2.0
package org.tatrman.concept

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.shouldBe
import org.tatrman.concept.metadata.CalciteQueryLineage
import org.tatrman.ttr.metadata.source.PhysicalIndex
import org.tatrman.ttr.metadata.source.QueryLineage
import org.tatrman.ttr.metadata.source.QueryLineageContext
import org.tatrman.ttr.metadata.source.SyntacticQueryLineage
import org.tatrman.ttr.parser.loader.TtrLoader
import org.tatrman.ttr.semantics.*
import kotlinx.serialization.json.*
import org.tatrman.ttr.metadata.source.*
import java.nio.file.Files
import java.nio.file.Path

class CalciteMdHopLineageSpec :
    StringSpec({
        "service provider and syntactic lineage agree on every Sales output; literals have no origin" {
            val root = Path.of("../ttr-metadata/src/test/resources/md-hop")
            val table = SymbolTable()
            val docs =
                Files
                    .list(root)
                    .use { paths ->
                        paths.filter { it.toString().endsWith(".ttrm") }.sorted().toList()
                    }.map { path ->
                        val p = TtrLoader.parseString(Files.readString(path), path.toString())
                        p.errors shouldBe emptyList()
                        val schema = p.modelDirective?.modelCode.orEmpty()
                        val ns = p.modelDirective?.schema.orEmpty()
                        table.upsertDocument(path.toString(), p.definitions, schema, ns, "")
                        SemanticDocument(path.toString(), p.definitions, schema, ns, "", p.imports)
                    }
            val context = QueryLineageContext(PhysicalIndex(docs, Resolver(table)))
            QueryLineage.provider()::class shouldBe CalciteQueryLineage::class
            val simple = SyntacticQueryLineage()
            val calcite = CalciteQueryLineage()
            for ((i, name) in listOf("channel", "customer", "item", "sold", "ext_sales_price").withIndex()) {
                val expected = simple.originsOf(context, "er.query.sales_union", name, i)
                calcite.originsOf(context, "er.query.sales_union", name, i) shouldBe expected
            }
            calcite.originsOf(context, "er.query.sales_union", "ext_sales_price", 4)?.size shouldBe 3
            calcite.originsOf(context, "er.query.sales_union", "channel", 0) shouldBe emptySet()
        }
        val root = Path.of("../ttr-metadata/src/test/resources/md-hop")
        val sources =
            Files.list(root).use { paths ->
                paths.filter { it.toString().endsWith(".ttrm") }.toList().associate {
                    it.fileName.toString() to
                        Files.readString(it)
                }
            }
        val cases = Json.parseToJsonElement(Files.readString(root.resolve("review-129.json"))).jsonArray
        for (value in cases) {
            val c = value.jsonObject
            ("Calcite provider: " + c.getValue("name").jsonPrimitive.content) {
                val table = SymbolTable()
                val all = sources + c.getValue("overrides").jsonObject.mapValues { it.value.jsonPrimitive.content }
                val docs =
                    all.map { (uri, text) ->
                        val p = TtrLoader.parseString(text, uri)
                        p.errors shouldBe emptyList()
                        val schema = p.modelDirective?.modelCode.orEmpty()
                        val ns = p.modelDirective?.schema.orEmpty()
                        val pkg = p.packageName.orEmpty()
                        table.upsertDocument(uri, p.definitions, schema, ns, pkg)
                        SemanticDocument(uri, p.definitions, schema, ns, pkg, p.imports)
                    }
                val resolver = Resolver(table)
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
                // Every source query column agrees with the conservative provider when it can derive it.
                val physical = PhysicalIndex(docs, resolver)
                val context = QueryLineageContext(physical)
                val simple = SyntacticQueryLineage()
                val calcite = CalciteQueryLineage()
                for ((entity, query) in physical.queryEntities) {
                    for ((i, attr) in physical.entities
                        .getValue(entity)
                        .second.attributes
                        .withIndex()) {
                        val column = physical.boundColumn("$entity.${attr.name}")
                        val expected = simple.originsOf(context, query, column, i)
                        calcite.originsOf(context, query, column, i) shouldBe expected
                    }
                }
            }
        }
    })
