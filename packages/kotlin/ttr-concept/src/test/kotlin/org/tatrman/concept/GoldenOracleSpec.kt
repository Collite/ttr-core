// SPDX-License-Identifier: Apache-2.0
package org.tatrman.concept

import com.google.protobuf.util.JsonFormat
import io.kotest.assertions.fail
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.tatrman.plan.v1.AggregateNode
import org.tatrman.plan.v1.ColumnRef
import org.tatrman.plan.v1.Expression
import org.tatrman.plan.v1.FilterNode
import org.tatrman.plan.v1.NamedExpression
import org.tatrman.plan.v1.PlanNode
import org.tatrman.plan.v1.ProjectNode
import org.tatrman.plan.v1.SchemaCode
import org.tatrman.plan.v1.SubqueryExpression
import org.tatrman.translate.v1.Language
import org.tatrman.translator.orchestrator.ParseResult
import org.tatrman.translator.orchestrator.Translator
import org.tatrman.translator.params.SqlParam
import org.tatrman.ttr.md.resolve.CanonicalRenderer
import org.tatrman.ttr.md.resolve.DefaultMdPathResolver
import org.tatrman.ttr.md.resolve.PathShape
import org.tatrman.ttr.md.resolve.PathText
import org.tatrman.ttr.md.resolve.ResolutionOutcome
import org.tatrman.ttr.md.resolve.Selector
import org.tatrman.ttrp.emit.sql.MdPathLowering
import java.time.Instant
import kotlin.io.path.writeText

/**
 * CN-P3 S3.7 — the goldens' ORACLES, independent of the code under test and green from the day the goldens land:
 *
 *  - an md case's expected dot-path and plan are what the md chain (`DefaultMdPathResolver` + `MdPathLowering`) makes
 *    of its `oracle.mdPath` text — or, for ActiveCustomer (⚑CN-P9), of two such reads composed as a grain-key
 *    semi-join; `-Pcn.goldens.regen` rewrites them (reviewed by hand, committed). A read's free (group-by)
 *    coordinates are the `*`s its text writes (`code.*`); a grain dimension the text leaves out is aggregated over and
 *    left out of the expected dot-path — the statement's shape, A-CN-39 (CN-P3 S3.3);
 *  - an er case's expected SQL translates (`ttr-translator`, the world's er tier as [GoldenModelHandle]) — on the strict
 *    ER lane when it reads no parameter, lenient otherwise;
 *  - an H-1 context's expected SQL translates to the SAME ER plan bytes as AU's hand-written island (`au.sql`, A-H-2) —
 *    `overdue` and `shortfall`; `price-differences` is row-set equal on live PG instead (A-CN-15, its case is `live`);
 *  - a materialized query's body translates on the strict lane (LQ refuses parameters — A-CN-16).
 */
class GoldenOracleSpec :
    FunSpec({
        val regen = System.getProperty("cn.goldens.regen") == "true"

        for (c in GoldenCases.cases) {
            val oracle = c.oracle
            when {
                oracle?.get("mdPath") != null || oracle?.get("semiJoin") != null ->
                    test("${c.id} — md chain makes the expected dot-path and plan") {
                        val (dotPath, plan) = mdOracle(c, oracle)
                        val planJson = JsonFormat.printer().print(plan) + "\n"
                        if (regen) {
                            c.file("expected.dotpath.txt").writeText(dotPath + "\n")
                            c.file("expected.plan.json").writeText(planJson)
                        } else {
                            c.text("expected.dotpath.txt").trim() shouldBe dotPath
                            Json.parseToJsonElement(planJson) shouldBe c.jsonFile("expected.plan.json")
                        }
                    }
            }
            if (c.call == "statementLower" && c.has("expected.sql") && c.text("expected.sql").isNotBlank()) {
                test("${c.id} — the er-lane statement's SQL translates") { translate(c.world, c.text("expected.sql")) }
            }
            if (c.call == "definitionRender" && c.has("expected.sql") && c.text("expected.sql").isNotBlank()) {
                test("${c.id} — the expected SQL translates") {
                    val plan = translate(c.world, c.text("expected.sql"))
                    val au = oracle?.get("au")?.jsonPrimitive?.content ?: return@test
                    val auPlan = translate(c.world, c.text(au))
                    if (oracle["plan"]?.jsonPrimitive?.content == "equal") {
                        withClue("A-H-2: the rendering's ER plan equals AU's island's, byte for byte") {
                            plan.toByteArray().contentEquals(auPlan.toByteArray()) shouldBe true
                        }
                    }
                }
            }
            if (c.call == "materialize" && c.has("expected")) {
                test("${c.id} — the materialized query body translates on the strict lane") {
                    val ttrm = c.text("expected/placed_order.ttrm")
                    val body = ttrm.substringAfter("sourceText: \"\"\"sql\n").substringBefore("\n\"\"\"")
                    translate(c.world, body, strict = true)
                }
            }
        }
    }) {
    companion object {
        /** The values the oracle binds a placeholder to (any value of the right type: plans carry the parameter, not it). */
        private val PARAMS =
            mapOf(
                "as_of" to SqlParam("as_of", "datetime", "2026-10-22 00:00:00"),
                "window_lo" to SqlParam("window_lo", "datetime", "2025-10-22 00:00:00"),
                "as_of_hi" to SqlParam("as_of_hi", "datetime", "2026-10-23 00:00:00"),
                "tolerance" to SqlParam("tolerance", "float", 0.01),
            )

        private val CURLY = Regex("\\{(\\w+)}")
        private val COLON = Regex("(?<![\\w:]):(\\w+)")

        /** Translates [sql] over [world]'s er tier; a COLON placeholder is read as its CURLY twin first. */
        internal fun translate(
            world: String,
            sql: String,
            strict: Boolean? = null,
        ): PlanNode {
            val text = sql.trim().replace(COLON) { "{${it.groupValues[1]}}" }
            val names =
                CURLY
                    .findAll(text)
                    .map { it.groupValues[1] }
                    .distinct()
                    .toList()
            val params = names.map { PARAMS[it] ?: error("the oracle binds no value for `{$it}`") }
            val result =
                Translator(GoldenModelHandle.of(GoldenWorlds[world].cn.model)).parseToRelNode(
                    text,
                    Language.SQL,
                    targetSchema = SchemaCode.ER,
                    sourceSchema = SchemaCode.ER,
                    parameters = params,
                    strictLane = strict ?: names.isEmpty(),
                    expandLogicalBodies = true,
                )
            if (result is ParseResult.Failure) fail("does not translate (${result.code}): ${result.message}\n$text")
            return result.shouldBeInstanceOf<ParseResult.Success>().plan
        }

        private fun mdOracle(
            c: GoldenCases.Case,
            oracle: JsonObject,
        ): Pair<String, PlanNode> {
            val asOf = Instant.parse(oracle["asOf"]?.jsonPrimitive?.content ?: "2026-10-22T00:00:00Z")
            val md = GoldenWorlds[c.world].md ?: fail("world ${c.world} has no md tier")

            fun read(raw: String): Pair<String, PlanNode> {
                val out = DefaultMdPathResolver().resolve(PathText.parse(raw), md.model, null, asOf)
                val r = out as? ResolutionOutcome.Resolved ?: fail("`$raw` does not resolve: $out")
                val written = Regex("(\\w+)\\.\\*").findAll(raw).map { it.groupValues[1] }.toSet()
                val free = r.path.coordinates.filter { it.attribute.substringAfterLast('.') in written }
                val shown = r.path.coordinates.filter { it.selector != Selector.Star || it in free }
                val plan = MdPathLowering(md.bindings, md.model).lower(r.path, PathShape(free.map { it.attribute }))
                return CanonicalRenderer.render(r.path.copy(coordinates = shown)) to plan
            }
            oracle["mdPath"]?.let { return read(it.jsonPrimitive.content) }
            val sj = oracle["semiJoin"]!!.jsonObject
            val (outerText, outer) = read(sj["outer"]!!.jsonPrimitive.content)
            val (innerText, inner) = read(sj["inner"]!!.jsonPrimitive.content)
            val key = sj["key"]!!.jsonPrimitive.content
            return "$outerText where ${keyText(outerText)} in ($innerText)" to semiJoin(outer, inner, key)
        }

        /** The outer read's one free coordinate, as the canonical text spells it (`customer.name`). */
        private fun keyText(canonical: String): String =
            Regex("([a-z_]+\\.[a-zA-Z_]+): \\*").findAll(canonical).single().groupValues[1]

        /**
         * ⚑CN-P9's composition: the outer read grouped by the grain key, kept where the key is `IN` the inner read's keys,
         * then aggregated again (an additive measure: the sum of the per-key sums).
         */
        internal fun semiJoin(
            outer: PlanNode,
            inner: PlanNode,
            key: String,
        ): PlanNode {
            require(outer.hasAggregate()) { "the outer read must be a grouped aggregate" }

            fun col(n: String) = Expression.newBuilder().setColumnRef(ColumnRef.newBuilder().setName(n)).build()
            val keys =
                PlanNode
                    .newBuilder()
                    .setProject(
                        ProjectNode
                            .newBuilder()
                            .setInput(
                                inner,
                            ).addExpressions(NamedExpression.newBuilder().setExpression(col(key)).setAlias(key)),
                    ).build()
            val inKeys =
                SubqueryExpression
                    .newBuilder()
                    .setKind("in")
                    .addOperands(col(key))
                    .setSubquery(keys)
            val kept =
                PlanNode.newBuilder().setFilter(
                    FilterNode.newBuilder().setInput(outer).setCondition(Expression.newBuilder().setSubquery(inKeys)),
                )
            return PlanNode
                .newBuilder()
                .setAggregate(
                    AggregateNode.newBuilder().setInput(kept).addAllAggregates(outer.aggregate.aggregatesList),
                ).build()
        }
    }
}
