// SPDX-License-Identifier: Apache-2.0
package org.tatrman.translator.wire

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.tatrman.plan.v1.PlanNode
import org.tatrman.plan.v1.QualifiedName
import org.tatrman.plan.v1.SchemaCode
import org.tatrman.translate.v1.Language
import org.tatrman.translate.v1.SqlDialect
import org.tatrman.translator.framework.EntityMapping
import org.tatrman.translator.framework.FixtureModel
import org.tatrman.translator.framework.InMemoryModelHandle
import org.tatrman.translator.framework.ModelAttribute
import org.tatrman.translator.framework.ModelColumn
import org.tatrman.translator.framework.ModelEntity
import org.tatrman.translator.framework.ModelTable
import org.tatrman.translator.framework.SurfaceType
import org.tatrman.translator.framework.TranslatorFramework
import org.tatrman.translator.orchestrator.ParseResult
import org.tatrman.translator.orchestrator.Translator
import org.tatrman.translator.orchestrator.UnparseResult

/**
 * TF-P1.S2 (G A3, contracts §3.2) — a column reference above a join whose inputs share a column name.
 *
 * The encoder names such a ref from the `LogicalJoin` row type, which Calcite uniquifies (`ID0`,
 * `NAME0`); the decoder rebuilds the join with `RelBuilder.join`, whose frame keeps the duplicates
 * (`[ID, NAME, B_ID, ID, NAME]`), so `field("NAME0")` failed: `field [NAME0] not found`. That was 29 of
 * the 101 legacy templates (every `QHDOK JOIN QSUBJEKT` reading `s.IDSUBJEKT`), and most free-SQL
 * joins over the DF schema.
 */
class DuplicateNameJoinSpec :
    StringSpec({
        val translator = Translator(FixtureModel.tfHandle())

        fun plan(sql: String): PlanNode =
            translator.parseToRelNode(sql, Language.SQL).shouldBeInstanceOf<ParseResult.Success>().plan

        fun mssql(p: PlanNode): String =
            translator
                .unparseFromRelNode(p, Language.SQL, SqlDialect.MSSQL)
                .shouldBeInstanceOf<UnparseResult.Success>()
                .output
                .replace(Regex("""\s+"""), " ")

        val join = "FROM A a JOIN B b ON b.ID = a.B_ID"
        val shapes =
            listOf(
                Triple("q1 right-side dup in the select list", "SELECT a.ID, b.ID $join", "[B].[ID]"),
                Triple("q2 right-side dup in WHERE", "SELECT a.NAME $join WHERE b.ID = 5", "[B].[ID] = 5"),
                Triple(
                    "q3 right-side NAME in the select list",
                    "SELECT a.ID, b.NAME $join WHERE a.ID = 5",
                    "[B].[NAME]",
                ),
                Triple("q6 right-side NAME alone", "SELECT b.NAME $join", "[B].[NAME]"),
                Triple("r1 LEFT JOIN", "SELECT a.ID, b.NAME FROM A a LEFT JOIN B b ON b.ID = a.B_ID", "[B].[NAME]"),
                Triple(
                    "r3 three tables, B joined twice",
                    "SELECT a.ID, b.NAME, c.NAME AS CN $join JOIN B c ON c.ID = a.ID",
                    "[NAME] AS [CN]",
                ),
                Triple(
                    "r4 anti-join",
                    "SELECT a.ID FROM A a LEFT JOIN B b ON b.ID = a.B_ID WHERE b.ID IS NULL",
                    "[B].[ID] IS NULL",
                ),
                Triple(
                    "r39 UNION whose first branch has the dup ref",
                    "SELECT a.ID $join WHERE b.NAME = 'x' UNION SELECT b.ID FROM B b",
                    "[B].[NAME] = 'x'",
                ),
                Triple(
                    "aggregate grouped by the right-side dup",
                    "SELECT b.NAME, COUNT(*) AS N $join GROUP BY b.NAME",
                    "GROUP BY [B].[NAME]",
                ),
                Triple("sort by the right-side dup", "SELECT a.ID, b.NAME $join ORDER BY b.NAME", "[B].[NAME]"),
                // The legacy `podprodukty_pro_firmu` shape: the SECOND join's condition references the dup
                // column of the first join's right side — resolved through the `$L` arm, `field(2, 0, name)`.
                Triple(
                    "a later join condition on the earlier join's right-side dup",
                    "SELECT a.ID, c.NAME $join JOIN B c ON c.ID = b.ID",
                    "ON [B].[ID] = [B0].[ID]",
                ),
            )
        for ((label, sql, expected) in shapes) {
            "$label parses and unparses, referencing $expected" {
                mssql(plan(sql)) shouldContain expected
            }
        }

        // P1.2.5 — the fix is decoder-only: a re-encoded plan is byte-identical (no rename Project).
        for (sql in listOf("SELECT a.ID, b.ID $join", "SELECT b.NAME $join")) {
            "encode(decode(plan)) is byte-identical for <$sql>" {
                val p = plan(sql)
                val rel = PlanNodeDecoder.decode(p, TranslatorFramework(FixtureModel.tfHandle()))
                PlanNodeEncoder.encode(rel) shouldBe p
            }
        }

        // P1.2.6 — the cz engine failed this shape with `Malformed positional column ref '$f3'`; org handles it.
        "GROUP BY over an expression with HAVING round-trips (rezerva_trzby_podle_produktu shape)" {
            mssql(
                plan(
                    "SELECT a.B_ID, ROUND(SUM(a.ID) * 100.0 / SUM(a.B_ID), 1) AS P " +
                        "FROM A a GROUP BY a.B_ID HAVING SUM(a.ID) > 0",
                ),
            ) shouldContain "HAVING SUM([ID]) > 0"
        }

        // P1.2.2 — the ER lane: MAP_TO_PHYSICAL + the physical Joiner run before encode.
        "an ER join of two entities sharing `id` survives MAP_TO_PHYSICAL and unparses" {
            fun qn(
                code: SchemaCode,
                ns: String,
                name: String,
            ) = QualifiedName
                .newBuilder()
                .setSchemaCode(code)
                .setNamespace(ns)
                .setName(name)
                .build()
            val model =
                InMemoryModelHandle(
                    tables =
                        listOf(
                            ModelTable(
                                qn(SchemaCode.DB, "dbo", "customers"),
                                listOf(
                                    ModelColumn("id", SurfaceType.INT, false),
                                    ModelColumn("name", SurfaceType.TEXT),
                                ),
                                primaryKey = listOf("id"),
                            ),
                            ModelTable(
                                qn(SchemaCode.DB, "dbo", "orders"),
                                listOf(
                                    ModelColumn("id", SurfaceType.INT, false),
                                    ModelColumn("customer_id", SurfaceType.INT, false),
                                    ModelColumn("name", SurfaceType.TEXT),
                                ),
                                primaryKey = listOf("id"),
                            ),
                        ),
                    entities =
                        listOf(
                            ModelEntity(
                                qn(SchemaCode.ER, "entity", "customer"),
                                listOf(
                                    ModelAttribute("id", SurfaceType.INT, false, isKey = true),
                                    ModelAttribute("name", SurfaceType.TEXT),
                                ),
                            ),
                            ModelEntity(
                                qn(SchemaCode.ER, "entity", "order"),
                                listOf(
                                    ModelAttribute("id", SurfaceType.INT, false, isKey = true),
                                    ModelAttribute("customer_id", SurfaceType.INT, false),
                                    ModelAttribute("name", SurfaceType.TEXT),
                                ),
                            ),
                        ),
                    entityMappings =
                        mapOf(
                            qn(SchemaCode.ER, "entity", "customer") to
                                EntityMapping.ToTable(qn(SchemaCode.DB, "dbo", "customers")),
                            qn(SchemaCode.ER, "entity", "order") to
                                EntityMapping.ToTable(qn(SchemaCode.DB, "dbo", "orders")),
                        ),
                )
            val er = Translator(model)
            val parsed =
                er
                    .parseToRelNode(
                        source =
                            "SELECT c.id, o.id AS order_id, o.name " +
                                "FROM customer c JOIN \"order\" o ON o.customer_id = c.id",
                        sourceLanguage = Language.SQL,
                        targetSchema = SchemaCode.DB,
                        sourceSchema = SchemaCode.ER,
                    ).shouldBeInstanceOf<ParseResult.Success>()
            val sql =
                er
                    .unparseFromRelNode(parsed.plan, Language.SQL, SqlDialect.MSSQL)
                    .shouldBeInstanceOf<UnparseResult.Success>()
                    .output
            sql shouldContain "[orders]"
            sql shouldContain "[customers]"
        }
    })
