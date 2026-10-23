// SPDX-License-Identifier: Apache-2.0
package org.tatrman.concept

import org.apache.calcite.util.Util
import org.tatrman.concept.metadata.CalciteQueryLineage
import org.tatrman.ttr.metadata.source.PhysicalIndex
import org.tatrman.ttr.metadata.source.QueryLineageContext
import org.tatrman.ttr.metadata.source.QueryLineage
import org.tatrman.ttr.parser.loader.TtrLoader
import org.tatrman.ttr.semantics.SemanticDocument
import org.tatrman.ttr.semantics.Resolver
import org.tatrman.ttr.semantics.SymbolTable

/** A fresh JVM: provider use must be safe before any other Calcite entry point. */
object LineageCharsetFork {
    @JvmStatic
    fun main(args: Array<String>) {
        check(System.getProperty("calcite.default.charset") == null)
        val p =
            TtrLoader.parseString(
                """
                model er
                def entity src { attributes: [def attribute id { type: int }] }
                def query q { sourceText: "SELECT 'řč' AS text FROM src" }
                """.trimIndent(),
                "fork",
            )
        check(p.errors.isEmpty())
        val table = SymbolTable()
        table.upsertDocument("fork", p.definitions, "er", "", "")
        val context =
            QueryLineageContext(
                PhysicalIndex(
                    listOf(SemanticDocument("fork", p.definitions, "er", "", "", p.imports)),
                    Resolver(table),
                ),
            )
        val provider = QueryLineage.provider()
        check(provider is CalciteQueryLineage)
        check(provider.originsOf(context, "er.query.q", "text", 0) == emptySet<String>())
        check(Util.getDefaultCharset().name() == "UTF-8")
    }
}
