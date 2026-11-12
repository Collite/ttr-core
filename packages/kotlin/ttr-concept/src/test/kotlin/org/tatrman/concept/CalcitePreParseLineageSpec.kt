// SPDX-License-Identifier: Apache-2.0
package org.tatrman.concept

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.tatrman.concept.metadata.CalciteQueryLineage
import org.tatrman.ttr.metadata.source.PhysicalIndex
import org.tatrman.ttr.metadata.source.QueryLineageContext
import org.tatrman.ttr.parser.loader.TtrLoader
import org.tatrman.ttr.semantics.Resolver
import org.tatrman.ttr.semantics.SemanticDocument
import org.tatrman.ttr.semantics.SymbolTable
import java.nio.file.Files
import java.nio.file.Path

class CalcitePreParseLineageSpec :
    StringSpec({
        fun context(sql: String): QueryLineageContext {
            val root = Path.of("../ttr-metadata/src/test/resources/md-hop")
            val table = SymbolTable()
            val docs =
                Files
                    .list(root)
                    .use { paths ->
                        paths.filter { it.toString().endsWith(".ttrm") }.sorted().toList()
                    }.map { path ->
                        var source = Files.readString(path)
                        if (path.fileName.toString() == "er.ttrm") {
                            val start = source.indexOf("SELECT 'store'")
                            val end = source.indexOf("\"\"\"", start)
                            source = source.replaceRange(start, end, "$sql\n")
                        }
                        val parsed = TtrLoader.parseString(source, path.toString())
                        parsed.errors shouldBe emptyList()
                        val schema = parsed.modelDirective?.modelCode.orEmpty()
                        val ns = parsed.modelDirective?.schema.orEmpty()
                        table.upsertDocument(path.toString(), parsed.definitions, schema, ns, "")
                        SemanticDocument(path.toString(), parsed.definitions, schema, ns, "", parsed.imports)
                    }
            return QueryLineageContext(PhysicalIndex(docs, Resolver(table)))
        }

        "bracket attributes and pre-parse rails preserve the plain query's physical origins" {
            val provider = CalciteQueryLineage()

            fun origins(sql: String) = provider.originsOf(context(sql), "er.query.sales_union", "customer", 0)
            val plain = origins("SELECT customer FROM store_sales")
            plain shouldBe setOf("db.dbo.table.store_sales.cust")
            origins("SELECT [customer] FROM store_sales") shouldBe plain
            origins("SELECT TOP 5 [customer] FROM [store_sales] WITH (NOLOCK);;") shouldBe plain
        }
    })
