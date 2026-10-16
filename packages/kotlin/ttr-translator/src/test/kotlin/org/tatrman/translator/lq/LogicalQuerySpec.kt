// SPDX-License-Identifier: Apache-2.0
package org.tatrman.translator.lq

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.tatrman.plan.v1.SchemaCode
import org.tatrman.translate.v1.Language
import org.tatrman.translator.framework.HartlandJoinModel
import org.tatrman.translator.framework.HartlandStackModel
import org.tatrman.translator.orchestrator.ParseResult
import org.tatrman.translator.orchestrator.Translator
import org.tatrman.translator.params.SqlParam

/**
 * LQ (logical queries) — the acceptance corpus of project `ttr-translator/logical-queries/plan/plan.md §4`,
 * H1–H12, on [HartlandStackModel]. Contracts L-2/L-3 (the strict lane), L-7 (`strictLane`), L-8…L-12
 * (`EXPAND_LOGICAL_BODIES`, the stamped boundary, byte-identical physical plans).
 */
class LogicalQuerySpec :
    StringSpec({
        val base = HartlandJoinModel.handle()

        fun strict(
            sql: String,
            stated: SchemaCode,
            parameters: List<SqlParam> = emptyList(),
        ): ParseResult =
            Translator(base).parseToRelNode(
                sql,
                Language.SQL,
                targetSchema = SchemaCode.ER,
                sourceSchema = stated,
                parameters = parameters,
                strictLane = true,
            )

        // ---- S1: the strict lane (L-2, L-7) ----

        "H1 — a logical query parsed strict with ER stated: Success, entity scans kept, the bare JOIN conditioned" {
            val parsed = strict(HartlandStackModel.REVENUE_SQL, SchemaCode.ER).shouldBeInstanceOf<ParseResult.Success>()
            HartlandStackModel.erScans(parsed.plan).map { it.name } shouldContainExactlyInAnyOrder
                listOf("sale", "shopper")
            HartlandStackModel.tableScans(parsed.plan).shouldBeEmpty()
            parsed.warnings.shouldBeEmpty()
            parsed.modelJoinsSql!! shouldContain "ON \"sale\".\"shopper_id\" = \"shopper\".\"shopper_id\""
        }

        "H1 — strict ER is byte-identical to the lenient stated-ER parse it replaces" {
            val strictPlan = (strict(HartlandStackModel.REVENUE_SQL, SchemaCode.ER) as ParseResult.Success).plan
            val lenient = HartlandStackModel.parseEr(base, HartlandStackModel.REVENUE_SQL)
            strictPlan.toByteArray().contentEquals(lenient.toByteArray()) shouldBe true
        }

        "H2 — the same body with DB stated, strict: lane_mismatch naming the entities" {
            val failure =
                strict(HartlandStackModel.REVENUE_SQL, SchemaCode.DB).shouldBeInstanceOf<ParseResult.Failure>()
            failure.code shouldBe "lane_mismatch"
            failure.message shouldContain "sale"
            failure.message shouldContain "shopper"
        }

        "H3 — a body mixing entities and tables, strict: mixed_lanes naming both sets" {
            val failure =
                strict(
                    "SELECT sale.revenue, item.product_name FROM sale JOIN item ON sale.product_id = item.product_id",
                    SchemaCode.ER,
                ).shouldBeInstanceOf<ParseResult.Failure>()
            failure.code shouldBe "mixed_lanes"
            failure.message shouldContain "sale"
            failure.message shouldContain "item"
        }

        "H3b — an unknown name, strict: lane_mismatch naming it" {
            val failure =
                strict("SELECT nothing.x FROM nothing", SchemaCode.ER).shouldBeInstanceOf<ParseResult.Failure>()
            failure.code shouldBe "lane_mismatch"
            failure.message shouldContain "nothing"
        }

        "H3c — strict, no stated lane: lane_mismatch (a strict parse needs the lane the query sits in)" {
            strict(HartlandStackModel.REVENUE_SQL, SchemaCode.SCHEMA_CODE_UNSPECIFIED)
                .shouldBeInstanceOf<ParseResult.Failure>()
                .code shouldBe "lane_mismatch"
        }

        "H3d — strict, a body that does not parse: the parser's own error, not a lane verdict" {
            val failure =
                strict(
                    "SELEKT sale.revenue FROM sale",
                    SchemaCode.ER,
                ).shouldBeInstanceOf<ParseResult.Failure>()
            failure.code.shouldNotContain("lane")
        }

        "H4 — a logical query with a parameter: logical_query_parameters" {
            strict(
                "SELECT sale.revenue FROM sale WHERE sale.store_id = {store}",
                SchemaCode.ER,
                parameters = listOf(SqlParam(name = "store", type = "int", value = "5")),
            ).shouldBeInstanceOf<ParseResult.Failure>().code shouldBe "logical_query_parameters"
        }

        "H4b — lenient callers are untouched: the same mixed body without strictLane still fails as a lookup error" {
            val r =
                Translator(base).parseToRelNode(
                    "SELECT sale.revenue, item.product_name FROM sale JOIN item ON sale.product_id = item.product_id",
                    Language.SQL,
                    targetSchema = SchemaCode.ER,
                    sourceSchema = SchemaCode.ER,
                )
            r.shouldBeInstanceOf<ParseResult.Failure>().code shouldBe "validation_failed"
        }

        "outputColumnNames — the stored body's output names (what a stacked entity's bindings are checked against)" {
            val body = HartlandStackModel.parseEr(base, HartlandStackModel.REVENUE_SQL)
            Translator.outputColumnNames(body) shouldBe
                listOf("shopper_id", "last_name", "current_household", "revenue")
        }
    })
