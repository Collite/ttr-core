// SPDX-License-Identifier: Apache-2.0
package org.tatrman.translator.orchestrator

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.tatrman.plan.v1.PlanNode
import org.tatrman.plan.v1.SchemaCode
import org.tatrman.translate.v1.Language
import org.tatrman.translate.v1.SqlDialect
import org.tatrman.translator.framework.HartlandJoinModel
import org.tatrman.translator.framework.EntityMapping
import org.tatrman.translator.framework.FixtureModel
import org.tatrman.translator.framework.InMemoryModelHandle
import org.tatrman.translator.framework.ModelAttribute
import org.tatrman.translator.framework.ModelColumn
import org.tatrman.translator.framework.ModelEntity
import org.tatrman.translator.framework.ModelTable
import org.tatrman.translator.framework.SurfaceType

/**
 * Regression — `SqlToRelConverter` folds an `IN`-list of literals into a single
 * `SEARCH($ref, Sarg[…])`. The `plan.v1` wire format has no `SEARCH`/`Sarg` shape, so before the
 * expansion existed [org.tatrman.translator.wire.PlanNodeEncoder.encode] took the numeric literal
 * branch for the `INTEGER`-typed `Sarg` literal and threw
 * `class org.apache.calcite.util.Sarg cannot be cast to class java.lang.Number`, surfacing as
 * `parse_pipeline_failed`. The failing production query carried `… IN (1, 4)` / `IN (2, 3)`
 * predicates inside a `CASE`. [org.tatrman.translator.wire.Expressions.encode] rewrites `SEARCH`
 * back to `OR`-of-comparisons, which the wire format already carries.
 */
class SearchExpansionSpec :
    StringSpec({
        val translator = Translator(FixtureModel.handle())

        // TF-P1.S1 (P0 finding, a legacy uninvoiced-orders template) — `NOT IN (-2, 10)` expands to
        // `AND(<>, <>)`; nested inside the WHERE's own AND it left the condition non-flat, and Calcite's
        // Filter asserts `RexUtil.isFlat` — a parse that threw only with assertions on (-ea). The correlated
        // NOT EXISTS matters: the decorrelated plan carries a Filter that the old rel-level pass then copied.
        "NOT IN inside an AND chain leaves the condition flat (throws under -ea otherwise)" {
            val r =
                Translator(FixtureModel.tfHandle()).parseToRelNode(
                    source =
                        "SELECT a.ID FROM A a WHERE a.NAME = 'x' AND a.ID >= 0 AND a.B_ID NOT IN (-2, 10) " +
                            "AND NOT EXISTS (SELECT 1 FROM B b WHERE b.ID = a.ID)",
                    sourceLanguage = Language.SQL,
                )
            r.shouldBeInstanceOf<ParseResult.Success>()
        }

        "IN-list in WHERE parses (was Sarg-cast parse_pipeline_failed)" {
            val r =
                translator.parseToRelNode(
                    source = "SELECT id, name FROM customers WHERE id IN (1, 4)",
                    sourceLanguage = Language.SQL,
                )
            r.shouldBeInstanceOf<ParseResult.Success>()
        }

        "IN-list inside a CASE parses (the production repro shape)" {
            val r =
                translator.parseToRelNode(
                    source =
                        "SELECT CASE WHEN id IN (1, 4) THEN 'a' " +
                            "WHEN id IN (2, 3) THEN 'b' ELSE 'c' END AS k FROM customers",
                    sourceLanguage = Language.SQL,
                )
            r.shouldBeInstanceOf<ParseResult.Success>()
        }

        "IN-list round-trips to MSSQL and preserves every member value" {
            val r =
                translator.translate(
                    "SELECT id FROM customers WHERE id IN (1, 4)",
                    Language.SQL,
                    Language.SQL,
                    targetDialect = SqlDialect.MSSQL,
                )
            r.shouldBeInstanceOf<TranslateResult.Success>()
            // Expanded to `id = 1 OR id = 4` (or re-folded to `IN`) — either way both members survive.
            r.output shouldContain "1"
            r.output shouldContain "4"
        }

        "a query with no IN-list is unaffected (the expansion is a no-op)" {
            val withSearch =
                translator.parseToRelNode(
                    source = "SELECT id FROM customers WHERE id IN (1, 4)",
                    sourceLanguage = Language.SQL,
                )
            val withoutSearch =
                translator.parseToRelNode(
                    source = "SELECT id FROM customers WHERE id = 1",
                    sourceLanguage = Language.SQL,
                )
            withSearch.shouldBeInstanceOf<ParseResult.Success>()
            withoutSearch.shouldBeInstanceOf<ParseResult.Success>()
            // The non-SEARCH plan is byte-identical to a fresh parse — the pass never touched it.
            val again =
                translator.parseToRelNode(
                    source = "SELECT id FROM customers WHERE id = 1",
                    sourceLanguage = Language.SQL,
                ) as ParseResult.Success
            withoutSearch.plan shouldBe again.plan
        }

        // ttr-core#159 — on REL_NODE re-entry `PlanNodeDecoder` rebuilds every Filter through
        // `RelBuilder.filter`, which folds a two-sided range / BETWEEN / IN-list into `SEARCH`. Inside an
        // IN / NOT IN sub-query that SEARCH lives in `RexSubQuery.rel`, which the old rel-level pass did not
        // enter, so the encoder met the Sarg literal: an AssertionError ("cannot convert SARG literal to
        // class TimestampString" / "… BigDecimal") that escaped `catch (Exception)`, a `Sarg cannot be cast
        // to Number` parse_pipeline_failed for an INTEGER column — or, for a VARCHAR column, no error at
        // all: the Sarg's digest rode as a string literal under a `search` operator, and the plan failed
        // only on its next decode.
        val place = HartlandJoinModel.er("place")
        val visit = HartlandJoinModel.er("visit")
        val placeTable = HartlandJoinModel.db("place")
        val visitTable = HartlandJoinModel.db("visit")
        val visitAttributes =
            listOf(
                ModelAttribute("id", SurfaceType.INT, nullable = false, isKey = true),
                ModelAttribute("place_name", SurfaceType.TEXT),
                ModelAttribute("visit_date", SurfaceType.DATETIME),
                ModelAttribute("qty", SurfaceType.FLOAT),
                ModelAttribute("n", SurfaceType.INT),
            )
        val visits =
            Translator(
                InMemoryModelHandle(
                    tables =
                        listOf(
                            ModelTable(
                                placeTable,
                                listOf(ModelColumn("name", SurfaceType.TEXT, nullable = false)),
                                listOf("name"),
                            ),
                            ModelTable(
                                visitTable,
                                visitAttributes.map { ModelColumn(it.name, it.surfaceType, it.nullable) },
                                listOf("id"),
                            ),
                        ),
                    entities =
                        listOf(
                            ModelEntity(
                                place,
                                listOf(ModelAttribute("name", SurfaceType.TEXT, nullable = false, isKey = true)),
                            ),
                            ModelEntity(visit, visitAttributes),
                        ),
                    entityMappings =
                        mapOf(
                            place to EntityMapping.ToTable(placeTable),
                            visit to EntityMapping.ToTable(visitTable),
                        ),
                ),
            )
        // shape → (sub-query predicate, the MSSQL its re-entered plan must unparse to — copied from the engine).
        val subquerySearchCases =
            mapOf(
                "NOT IN, two-sided datetime range" to
                    (
                        "d.name NOT IN (SELECT v.place_name FROM er.entity.visit v " +
                            "WHERE v.visit_date >= '2026-06-01' AND v.visit_date < '2026-10-10' AND v.place_name IS NOT NULL)"
                    ).to(
                        "WHERE [name] NOT IN (SELECT [place_name] FROM [dbo].[visit] " +
                            "WHERE [visit_date] >= '2026-06-01 00:00:00' AND [visit_date] < '2026-10-10 00:00:00' " +
                            "AND [place_name] IS NOT NULL)",
                    ),
                "IN, datetime BETWEEN" to
                    (
                        "d.name IN (SELECT v.place_name FROM er.entity.visit v " +
                            "WHERE v.visit_date BETWEEN '2026-06-01' AND '2026-10-10')"
                    ).to(
                        "WHERE [name] IN (SELECT [place_name] FROM [dbo].[visit] " +
                            "WHERE [visit_date] >= '2026-06-01 00:00:00' AND [visit_date] <= '2026-10-10 00:00:00')",
                    ),
                "IN, two-sided float range" to
                    "d.name IN (SELECT v.place_name FROM er.entity.visit v WHERE v.qty >= 1 AND v.qty < 10)".to(
                        "WHERE [name] IN (SELECT [place_name] FROM [dbo].[visit] WHERE [qty] >= 1.0 AND [qty] < 10.0)",
                    ),
                "IN, two-sided int range" to
                    "d.name IN (SELECT v.place_name FROM er.entity.visit v WHERE v.n >= 1 AND v.n < 10)".to(
                        "WHERE [name] IN (SELECT [place_name] FROM [dbo].[visit] WHERE [n] >= 1 AND [n] < 10)",
                    ),
                "IN, IN-list" to
                    "d.name IN (SELECT v.place_name FROM er.entity.visit v WHERE v.n IN (1, 4))".to(
                        "WHERE [name] IN (SELECT [place_name] FROM [dbo].[visit] WHERE [n] IN (1, 4))",
                    ),
                "IN, VARCHAR IN-list" to
                    "d.name IN (SELECT v.place_name FROM er.entity.visit v WHERE v.place_name IN ('a', 'b'))".to(
                        "WHERE [name] IN (SELECT [place_name] FROM [dbo].[visit] WHERE [place_name] IN ('a', 'b'))",
                    ),
                "IN nested two sub-queries deep" to
                    (
                        "d.name IN (SELECT v.place_name FROM er.entity.visit v WHERE v.id IN " +
                            "(SELECT w.id FROM er.entity.visit w WHERE w.visit_date >= '2026-06-01' AND w.visit_date < '2026-10-10'))"
                    ).to(
                        "WHERE [name] IN (SELECT [place_name] FROM [dbo].[visit] " +
                            "WHERE [id] IN (SELECT [id] FROM [dbo].[visit] " +
                            "WHERE [visit_date] >= '2026-06-01 00:00:00' AND [visit_date] < '2026-10-10 00:00:00'))",
                    ),
            )
        subquerySearchCases.forEach { (shape, case) ->
            val (predicate, expectedWhere) = case
            "REL_NODE re-entry ER → DB with a SEARCH inside a sub-query — $shape" {
                val erPlan =
                    visits.parseToRelNode(
                        "SELECT d.name FROM er.entity.place d WHERE $predicate",
                        Language.SQL,
                        targetSchema = SchemaCode.ER,
                    )
                erPlan.shouldBeInstanceOf<ParseResult.Success>()
                val dbPlan =
                    visits.parseToRelNode(
                        String(erPlan.plan.toByteArray(), Charsets.ISO_8859_1),
                        Language.REL_NODE,
                        targetSchema = SchemaCode.DB,
                    )
                dbPlan.shouldBeInstanceOf<ParseResult.Success>()
                dbPlan.plan.wireText() shouldNotContain SEARCH_ON_WIRE
                // Not byte-equal to the single-call plan: the re-decode folds `CAST('2026-06-01' AS datetime2)`
                // into a TIMESTAMP literal, exactly as it does for the same range at the top level. Pin the SQL.
                visits.mssql(dbPlan.plan) shouldContain expectedWhere
            }
        }

        // A SEARCH in every other place a sub-query body can sit, through both the single call (SQL → DB) and
        // the runner's two steps (SQL → ER, REL_NODE → DB). On master each failed on one path or both: the
        // HAVING case already on the single call (the converter folds `COUNT(*) BETWEEN 1 AND 5` itself).
        // shape → (statement, the MSSQL fragment both paths must unparse to — copied from the engine).
        val positionCases =
            mapOf(
                "scalar sub-query in the select list" to
                    (
                        "SELECT d.name, (SELECT MAX(v.n) FROM er.entity.visit v WHERE v.n BETWEEN 1 AND 5) AS m " +
                            "FROM er.entity.place d"
                    ).to("(SELECT MAX([n]) FROM [dbo].[visit] WHERE [n] >= 1 AND [n] <= 5)"),
                "uncorrelated EXISTS" to
                    (
                        "SELECT d.name FROM er.entity.place d " +
                            "WHERE EXISTS (SELECT 1 FROM er.entity.visit v WHERE v.n BETWEEN 1 AND 5)"
                    ).to("EXISTS (SELECT * FROM [dbo].[visit] WHERE [n] >= 1 AND [n] <= 5)"),
                "IN sub-query inside a CASE" to
                    (
                        "SELECT CASE WHEN d.name IN (SELECT v.place_name FROM er.entity.visit v WHERE v.n IN (1, 4)) " +
                            "THEN 'a' ELSE 'b' END AS k FROM er.entity.place d"
                    ).to("[name] IN (SELECT [place_name] FROM [dbo].[visit] WHERE [n] IN (1, 4))"),
                "IN sub-query in a join condition" to
                    (
                        "SELECT d.name FROM er.entity.place d JOIN er.entity.visit v " +
                            "ON v.place_name = d.name AND v.id IN (SELECT w.id FROM er.entity.visit w WHERE w.n BETWEEN 1 AND 5)"
                    ).to("IN (SELECT [id] FROM [dbo].[visit] WHERE [n] >= 1 AND [n] <= 5)"),
                "HAVING BETWEEN inside an IN sub-query" to
                    (
                        "SELECT d.name FROM er.entity.place d WHERE d.name IN " +
                            "(SELECT v.place_name FROM er.entity.visit v GROUP BY v.place_name HAVING COUNT(*) BETWEEN 1 AND 5)"
                    ).to("HAVING COUNT(*) >= 1 AND COUNT(*) <= 5"),
            )
        positionCases.forEach { (shape, case) ->
            val (statement, expectedSql) = case
            "single call SQL → DB with a SEARCH inside a sub-query — $shape" {
                val dbPlan = visits.parseToRelNode(statement, Language.SQL, targetSchema = SchemaCode.DB)
                dbPlan.shouldBeInstanceOf<ParseResult.Success>()
                dbPlan.plan.wireText() shouldNotContain SEARCH_ON_WIRE
                visits.mssql(dbPlan.plan) shouldContain expectedSql
            }
            "REL_NODE re-entry ER → DB with a SEARCH inside a sub-query — $shape" {
                val erPlan = visits.parseToRelNode(statement, Language.SQL, targetSchema = SchemaCode.ER)
                erPlan.shouldBeInstanceOf<ParseResult.Success>()
                val dbPlan =
                    visits.parseToRelNode(
                        String(erPlan.plan.toByteArray(), Charsets.ISO_8859_1),
                        Language.REL_NODE,
                        targetSchema = SchemaCode.DB,
                    )
                dbPlan.shouldBeInstanceOf<ParseResult.Success>()
                dbPlan.plan.wireText() shouldNotContain SEARCH_ON_WIRE
                visits.mssql(dbPlan.plan) shouldContain expectedSql
            }
        }
    })

/** A `search` call on the wire: `plan.v1` has no such operator, so a plan carrying one cannot be decoded. */
private const val SEARCH_ON_WIRE = "operation: \"search\""

private fun PlanNode.wireText(): String = toString()

private fun Translator.mssql(plan: PlanNode): String {
    val sql = unparseFromRelNode(plan, Language.SQL, SqlDialect.MSSQL)
    sql.shouldBeInstanceOf<UnparseResult.Success>()
    return sql.output.replace(Regex("\\s+"), " ")
}
