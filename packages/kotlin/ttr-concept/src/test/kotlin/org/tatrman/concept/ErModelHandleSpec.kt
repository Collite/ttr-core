// SPDX-License-Identifier: Apache-2.0
package org.tatrman.concept

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.serialization.json.jsonPrimitive
import org.tatrman.plan.v1.QualifiedName
import org.tatrman.plan.v1.SchemaCode
import org.tatrman.translate.v1.Language
import org.tatrman.translate.v1.SqlDialect
import org.tatrman.translator.framework.EntityMapping
import org.tatrman.translator.framework.ModelHandle
import org.tatrman.translator.orchestrator.ParseResult
import org.tatrman.translator.orchestrator.Translator
import org.tatrman.translator.params.SqlParam

/**
 * CN-P3 S3.4 T1/T7 — the production [ErModelHandle] (a world's er + db tiers and their `er2db` mappings as the
 * translator's model, A-CN-46), proven against the goldens' oracle handle ([GoldenModelHandle]): every er golden's
 * expected SQL — and AU's islands — translates on the ER lane to the same plan bytes over both. Then every rendering
 * the renderer makes for an er golden translates to the db tier and unparses to PostgreSQL and MSSQL (S0.3's runner,
 * over the production handle); and the handle honours an explicit `er2db` mapping — another table, renamed columns.
 */
class ErModelHandleSpec :
    FunSpec({
        val texts =
            GoldenCases.cases
                .filter {
                    it.call == "definitionRender" &&
                        it.has("expected.sql") &&
                        it.text("expected.sql").isNotBlank()
                }.flatMap { c ->
                    listOf(c to "expected.sql") +
                        listOfNotNull(
                            c.oracle
                                ?.get("au")
                                ?.jsonPrimitive
                                ?.content
                                ?.let { c to it },
                        )
                }

        for ((c, file) in texts) {
            test("${c.id}/$file — ER-lane plan bytes equal over ErModelHandle and the oracle's handle") {
                val model = GoldenWorlds[c.world].cn.model
                val production = erPlan(ErModelHandle(model), c.text(file))
                val oracle = erPlan(GoldenModelHandle.of(model), c.text(file))
                production.toByteArray().contentEquals(oracle.toByteArray()) shouldBe true
            }
        }

        for (c in ConceptLiveSpec.renderings()) {
            test("${c.id} — the rendering translates to the db tier and unparses to PostgreSQL and MSSQL") {
                val model = GoldenWorlds[c.world].cn.model
                val text = ConceptLiveSpec.render(c).text
                for (dialect in listOf(SqlDialect.POSTGRESQL, SqlDialect.MSSQL)) {
                    withClue(dialect) { ErSql.unparse(model, text, dialect).first.isNotBlank() shouldBe true }
                }
            }
        }

        test("an explicit er2db mapping: another table, the renamed columns; an unbound entity maps nowhere") {
            val dir =
                DefinitionFixtures.repo.resolve(
                    "packages/kotlin/ttrp-cli/src/test/resources/fixtures/params-project/models/erp",
                )
            val model = DefinitionFixtures.load(DefinitionFixtures.dir(dir, "erp"))
            val h = ErModelHandle(model)
            val customer = qname(SchemaCode.ER, "entity", "customer")
            h.entityMapping(customer).shouldBeInstanceOf<EntityMapping.ToTable>().table shouldBe
                qname(SchemaCode.DB, "dbo", "customers")
            h.attributeColumnRenames(customer) shouldBe mapOf("customerType" to "customer_type")
            h.entityMapping(qname(SchemaCode.ER, "entity", "product")) shouldBe null
            h.entities(SchemaCode.ER, "entity").keys.map { it.name } shouldContainAll
                listOf("customer", "sales_txn", "product")
            h.namespaces(SchemaCode.ER) shouldBe setOf("entity")
        }
    }) {
    companion object {
        private val PARAMS =
            mapOf(
                "as_of" to SqlParam("as_of", "datetime", "2026-10-22 00:00:00"),
                "window_lo" to SqlParam("window_lo", "datetime", "2025-10-22 00:00:00"),
                "as_of_hi" to SqlParam("as_of_hi", "datetime", "2026-10-23 00:00:00"),
                "tolerance" to SqlParam("tolerance", "float", 0.01),
            )

        private fun erPlan(
            handle: ModelHandle,
            sql: String,
        ): org.tatrman.plan.v1.PlanNode {
            val text = sql.trim().replace(Regex("(?<![\\w:]):(\\w+)")) { "{${it.groupValues[1]}}" }
            val names =
                Regex("\\{(\\w+)}")
                    .findAll(text)
                    .map { it.groupValues[1] }
                    .distinct()
                    .toList()
            val result =
                Translator(handle).parseToRelNode(
                    text,
                    Language.SQL,
                    targetSchema = SchemaCode.ER,
                    sourceSchema = SchemaCode.ER,
                    parameters = names.map { PARAMS.getValue(it) },
                    strictLane = names.isEmpty(),
                    expandLogicalBodies = true,
                )
            return result.shouldBeInstanceOf<ParseResult.Success>().plan
        }

        private fun qname(
            code: SchemaCode,
            namespace: String,
            name: String,
        ): QualifiedName =
            QualifiedName
                .newBuilder()
                .setSchemaCode(code)
                .setNamespace(namespace)
                .setName(name)
                .build()
    }
}
