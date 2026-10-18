// SPDX-License-Identifier: Apache-2.0
package org.tatrman.translator.lq

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.tatrman.plan.v1.QualifiedName
import org.tatrman.plan.v1.SchemaCode
import org.tatrman.translate.v1.Language
import org.tatrman.translator.orchestrator.Translator

/**
 * LQ-T4 (design §8, contract L-24) — `Translator.savedQueryReferences`: the saved queries a body reads as
 * `obj.<namespace>.<name>`, which a metadata worker needs before it parses anything (to parse a query after the
 * queries it reads). Pure: no model.
 */
class SavedQueryReferencesSpec :
    StringSpec({
        fun refs(sql: String): List<String> =
            Translator.savedQueryReferences(sql, Language.SQL).map {
                it.schemaCode shouldBe SchemaCode.OBJ
                "${it.namespace}.${it.name}"
            }

        "FROM and JOIN, aliased or not; first-occurrence order, each once" {
            refs(
                "SELECT r.a, s.b FROM obj.query.revenue r JOIN obj.dbo.store_totals AS s ON r.k = s.k " +
                    "JOIN obj.query.revenue r2 ON r2.k = r.k",
            ) shouldBe listOf("query.revenue", "dbo.store_totals")
        }

        "inside a CTE body, IN / EXISTS / scalar sub-queries, a derived table and a UNION branch" {
            refs(
                """
                WITH big AS (SELECT k FROM obj.query.big_ones)
                SELECT t.k FROM (SELECT k FROM obj.query.derived) t
                WHERE t.k IN (SELECT k FROM obj.query.in_list)
                  AND EXISTS (SELECT 1 FROM obj.query.exists_one e WHERE e.k = t.k)
                  AND t.k > (SELECT MAX(k) FROM obj.query.scalar_one)
                UNION ALL SELECT k FROM obj.query.union_branch
                """.trimIndent(),
            ) shouldBe
                listOf(
                    "query.big_ones",
                    "query.derived",
                    "query.in_list",
                    "query.exists_one",
                    "query.scalar_one",
                    "query.union_branch",
                )
        }

        "a CTE, an entity, a table or a two-part name is not a saved-query reference" {
            refs(
                "WITH obj AS (SELECT 1 AS k) SELECT obj.k FROM obj JOIN sale ON sale.k = obj.k " +
                    "JOIN db.dbo.store_sales ss ON ss.k = obj.k JOIN query.revenue q ON q.k = obj.k",
            ).shouldBeEmpty()
        }

        "the source as a worker holds it: a trailing ';', TOP, {placeholders}, case as written" {
            refs("SELECT TOP 5 r.k FROM OBJ.Query.Revenue r WHERE r.y = {year};") shouldBe listOf("Query.Revenue")
        }

        "a body that does not parse, or is not SQL, has no references (the parse reports its own error)" {
            refs("SELECT FROM WHERE obj.query.x").shouldBeEmpty()
            Translator.savedQueryReferences("table: x", Language.TRANSFORMATION_DSL).shouldBeEmpty()
        }

        "the qname is the OBJ address UNFOLD resolves" {
            Translator.savedQueryReferences("SELECT * FROM obj.query.revenue", Language.SQL) shouldBe
                listOf(
                    QualifiedName
                        .newBuilder()
                        .setSchemaCode(SchemaCode.OBJ)
                        .setNamespace("query")
                        .setName("revenue")
                        .build(),
                )
        }
    })
