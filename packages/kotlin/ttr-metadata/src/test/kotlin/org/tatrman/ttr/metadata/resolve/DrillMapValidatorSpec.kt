// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.metadata.resolve

import org.tatrman.ttr.metadata.model.QualifiedName
import org.tatrman.ttr.metadata.model.SchemaCode
import org.tatrman.ttr.metadata.model.Binding
import org.tatrman.ttr.metadata.model.CncSchema
import org.tatrman.ttr.metadata.model.DbSchema
import org.tatrman.ttr.metadata.model.DrillMap
import org.tatrman.ttr.metadata.model.ErSchema
import org.tatrman.ttr.metadata.model.LocalizedText
import org.tatrman.ttr.metadata.model.Model
import org.tatrman.ttr.metadata.model.ModelDescriptor
import org.tatrman.ttr.metadata.model.ModelVersion
import org.tatrman.ttr.metadata.model.Query
import org.tatrman.ttr.metadata.model.QueryParameterDef
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.time.Instant

/**
 * Stage 03 task 3-5 — DrillMapValidator.
 *
 * Each case builds a tiny [Model] containing two pattern queries + one drill
 * map and asserts the expected diagnostic surfaces (or not).
 */
class DrillMapValidatorSpec :
    StringSpec({

        "happy path — to/from resolve, args match parameters, value present in source SQL" {
            val model =
                modelWith(
                    queries =
                        listOf(
                            patternQuery(
                                "returns_by_reason",
                                params = emptyList(),
                                sql = "SELECT r.r_reason_desc, SUM(cr.cr_return_amount) AS returned_amount, cr.cr_order_number FROM catalog_returns cr",
                            ),
                            patternQuery(
                                "return_detail",
                                params = listOf(QueryParameterDef("order_number", "int")),
                                sql = "SELECT * FROM catalog_returns WHERE cr_order_number = :order_number",
                            ),
                        ),
                    drillMaps =
                        listOf(
                            drill(
                                name = "reason_to_return_detail",
                                from = "returns_by_reason",
                                to = "return_detail",
                                args = mapOf("order_number" to "cr_order_number"),
                            ),
                        ),
                )

            DrillMapValidator().validate(model).errors.shouldBeEmpty()
        }

        "DRILL_MAP_UNKNOWN_TARGET — to references a non-existent pattern" {
            val model =
                modelWith(
                    queries =
                        listOf(
                            patternQuery("returns_by_reason", emptyList(), "SELECT cr_order_number"),
                        ),
                    drillMaps =
                        listOf(
                            drill(
                                name = "reason_to_return_detail",
                                from = "returns_by_reason",
                                to = "return_detail",
                                args = mapOf("order_number" to "cr_order_number"),
                            ),
                        ),
                )

            val errors = DrillMapValidator().validate(model).errors
            errors.size shouldBe 1
            errors[0].message shouldContain "DRILL_MAP_UNKNOWN_TARGET"
        }

        "DRILL_MAP_UNKNOWN_PARAM — args key is not a parameter on to" {
            val model =
                modelWith(
                    queries =
                        listOf(
                            patternQuery("a", emptyList(), "SELECT 1"),
                            patternQuery(
                                "b",
                                params = listOf(QueryParameterDef("order_number", "int")),
                                sql = "SELECT * WHERE cr_order_number = :order_number",
                            ),
                        ),
                    drillMaps =
                        listOf(
                            drill(
                                name = "x",
                                from = "a",
                                to = "b",
                                args = mapOf("not_a_param" to "1", "order_number" to "1"),
                            ),
                        ),
                )

            val errors = DrillMapValidator().validate(model).errors
            errors.any { it.message.contains("DRILL_MAP_UNKNOWN_PARAM") && it.message.contains("not_a_param") } shouldBe
                true
        }

        "DRILL_MAP_PARAM_NOT_IN_FROM_SQL — args value (bare id) is not found in from.sourceText" {
            val model =
                modelWith(
                    queries =
                        listOf(
                            patternQuery(
                                "a",
                                emptyList(),
                                sql = "SELECT r_reason_desc, cr_return_amount FROM catalog_returns",
                            ),
                            patternQuery(
                                "b",
                                params = listOf(QueryParameterDef("order_number", "int")),
                                sql = "SELECT 1",
                            ),
                        ),
                    drillMaps =
                        listOf(
                            drill(
                                name = "x",
                                from = "a",
                                to = "b",
                                // cr_order_number isn't in the from SQL → typo
                                args = mapOf("order_number" to "cr_order_number"),
                            ),
                        ),
                )

            val errors = DrillMapValidator().validate(model).errors
            errors.any { it.message.contains("DRILL_MAP_PARAM_NOT_IN_FROM_SQL") } shouldBe true
        }

        "DRILL_MAP_MISSING_PARAM — a required parameter on to has no args entry" {
            val model =
                modelWith(
                    queries =
                        listOf(
                            patternQuery("a", emptyList(), "SELECT cr_order_number"),
                            patternQuery(
                                "b",
                                params =
                                    listOf(
                                        QueryParameterDef("order_number", "int"),
                                        QueryParameterDef("year", "int"),
                                    ),
                                sql = "SELECT 1",
                            ),
                        ),
                    drillMaps =
                        listOf(
                            drill(
                                name = "x",
                                from = "a",
                                to = "b",
                                args = mapOf("order_number" to "cr_order_number"),
                            ),
                        ),
                )

            val errors = DrillMapValidator().validate(model).errors
            errors.any { it.message.contains("DRILL_MAP_MISSING_PARAM") && it.message.contains("year") } shouldBe true
        }

        "string literal in args value — bypasses the from-SQL substring check" {
            val model =
                modelWith(
                    queries =
                        listOf(
                            patternQuery("a", emptyList(), "SELECT r_reason_desc FROM x"),
                            patternQuery(
                                "b",
                                params = listOf(QueryParameterDef("mode", "string")),
                                sql = "SELECT 1",
                            ),
                        ),
                    drillMaps =
                        listOf(
                            drill(
                                name = "x",
                                from = "a",
                                to = "b",
                                args = mapOf("mode" to "'detail'"),
                            ),
                        ),
                )

            DrillMapValidator().validate(model).errors.shouldBeEmpty()
        }
    })

private fun qn(
    schemaCode: String,
    namespace: String,
    name: String,
): QualifiedName {
    // SchemaCode proto enum only covers DB/ER/CNC/WS/OBJ — the map/drill kinds fall
    // through to UNSPECIFIED, matching the production loader's behaviour in
    // Source.qname(). (Queries are db-schema objects and live under db.dbo.)
    val code =
        try {
            SchemaCode.valueOf(schemaCode.uppercase())
        } catch (_: IllegalArgumentException) {
            SchemaCode.UNSPECIFIED
        }
    return QualifiedName(schemaCode = code, namespace = namespace, name = name)
}

private fun patternQuery(
    name: String,
    params: List<QueryParameterDef>,
    sql: String,
): Query {
    val q = qn("db", "dbo", name)
    return Query(
        internalId = "query:db.dbo.$name",
        qname = q,
        sourceLanguage = "SQL",
        sourceText = sql,
        parameters = params,
    )
}

private fun drill(
    name: String,
    from: String,
    to: String,
    args: Map<String, String>,
): DrillMap {
    val q = qn("query", "drill", name)
    return DrillMap(
        internalId = "query.drill_map:query.drill.$name",
        qname = q,
        sourceFile = "test.ttr",
        fromPattern = qn("db", "dbo", from),
        toPattern = qn("db", "dbo", to),
        argMapping = args,
        display = LocalizedText(byLanguage = mapOf("cs" to "Detail")),
        binding = Binding.BoundReal,
    )
}

private fun modelWith(
    queries: List<Query>,
    drillMaps: List<DrillMap>,
): Model =
    Model(
        descriptor = ModelDescriptor(id = "test", name = "test"),
        version = ModelVersion(value = "1", swappedAt = Instant.now()),
        schemas =
            mapOf(
                "db" to DbSchema(),
                "er" to ErSchema(),
                "cnc" to CncSchema(),
            ),
        mappings = emptyList(),
        queries = queries.associateBy { it.qname },
        drillMaps = drillMaps.associateBy { it.qname },
    )
