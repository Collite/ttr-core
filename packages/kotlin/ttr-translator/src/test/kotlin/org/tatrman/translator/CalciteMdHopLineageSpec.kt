// SPDX-License-Identifier: Apache-2.0
package org.tatrman.translator

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.tatrman.translator.metadata.CalciteQueryLineage
import org.tatrman.ttr.metadata.source.PhysicalIndex
import org.tatrman.ttr.metadata.source.QueryLineage
import org.tatrman.ttr.metadata.source.QueryLineageContext
import org.tatrman.ttr.metadata.source.SyntacticQueryLineage
import org.tatrman.ttr.parser.loader.TtrLoader
import org.tatrman.ttr.semantics.*
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
    })
