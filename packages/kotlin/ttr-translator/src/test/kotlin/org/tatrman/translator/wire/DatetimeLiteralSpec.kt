// SPDX-License-Identifier: Apache-2.0
package org.tatrman.translator.wire

import com.google.protobuf.TextFormat
import org.tatrman.plan.v1.PlanNode
import org.tatrman.translate.v1.SqlDialect as SqlDialectProto
import io.kotest.assertions.throwables.shouldThrowAny
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldContainIgnoringCase
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.tatrman.translator.codec.sql.RelToSqlUnparser
import org.tatrman.translator.codec.sql.SqlValidator
import org.tatrman.translator.codec.sql.ValidateResult
import org.tatrman.translator.framework.FixtureModel
import org.tatrman.translator.framework.TranslatorFramework

/**
 * `Literal.datetime_value` is **ISO-8601** — plan.proto says so on the field — and the codec did not
 * honour that in either direction:
 *
 *  - **encode** wrote Calcite's `value2`, which for a TIMESTAMP is epoch milliseconds, so the wire
 *    carried `"1735689600000"`;
 *  - **decode** handed the string to `RelBuilder.literal(String)`, which builds a CHARACTER literal, so a
 *    date bound came back typed `text` and was compared to a date column as a string.
 *
 * An agent that writes the contract's own form (`"2025-01-01T00:00:00Z"`) therefore round-tripped into
 * a string comparison. These specs pin both halves, and the round trip between them.
 */
class DatetimeLiteralSpec :
    StringSpec({

        fun encode(sql: String): PlanNode {
            val fw = TranslatorFramework(FixtureModel.handle())
            val r = SqlValidator.validateAndConvert(fw.newPlanner(), sql)
            r.shouldBeInstanceOf<ValidateResult.Success>()
            return PlanNodeEncoder.encode(r.rel)
        }

        /** Every `datetime_value` in the plan, in text-format order. */
        fun datetimeValues(plan: PlanNode): List<String> =
            Regex("datetime_value: \"([^\"]*)\"").findAll(plan.toString()).map { it.groupValues[1] }.toList()

        /** The same plan with every `datetime_value` replaced — how a caller writing the wire form directly sends it. */
        fun withDatetimeValue(
            plan: PlanNode,
            value: String,
        ): PlanNode {
            val text = plan.toString().replace(Regex("datetime_value: \"[^\"]*\""), "datetime_value: \"$value\"")
            return PlanNode.newBuilder().also { TextFormat.merge(text, it) }.build()
        }

        fun unparsePostgres(plan: PlanNode): String =
            RelToSqlUnparser.unparse(
                PlanNodeDecoder.decode(plan, TranslatorFramework(FixtureModel.handle())),
                SqlDialectProto.POSTGRESQL,
            )

        val timestampFilter = "SELECT id FROM customers WHERE signup >= TIMESTAMP '2025-01-01 00:00:00'"

        "a TIMESTAMP literal encodes as ISO-8601 — what plan.proto says datetime_value is" {
            datetimeValues(encode(timestampFilter)) shouldBe listOf("2025-01-01T00:00:00Z")
        }

        "an ISO-8601 datetime_value decodes to a TIMESTAMP literal, not to a string" {
            val sql = unparsePostgres(withDatetimeValue(encode(timestampFilter), "2025-01-01T00:00:00Z"))

            sql shouldContainIgnoringCase "TIMESTAMP '2025-01-01 00:00:00'"
            sql shouldNotContain "2025-01-01T00:00:00Z"
        }

        "the literal survives encode → decode → encode unchanged" {
            val once = encode(timestampFilter)
            val rel = PlanNodeDecoder.decode(once, TranslatorFramework(FixtureModel.handle()))

            datetimeValues(PlanNodeEncoder.encode(rel)) shouldBe datetimeValues(once)
        }

        // TF-P1.S1 (G A7) — Calcite folds CAST('…' AS date) in a select list to a DATE literal; the wire once
        // carried its value2 (epoch days), so MSSQL got '20454'. Regression guard for the ISO encoding.
        "a folded DATE cast in the select list unparses as the date, not as epoch days (G A7)" {
            val translator =
                org.tatrman.translator.orchestrator
                    .Translator(FixtureModel.tfHandle())
            val parsed =
                translator.parseToRelNode(
                    "SELECT CAST('2026-01-01' AS date) AS DT FROM A a",
                    org.tatrman.translate.v1.Language.SQL,
                )
            parsed.shouldBeInstanceOf<org.tatrman.translator.orchestrator.ParseResult.Success>()
            val u =
                translator.unparseFromRelNode(parsed.plan, org.tatrman.translate.v1.Language.SQL, SqlDialectProto.MSSQL)
            u.shouldBeInstanceOf<org.tatrman.translator.orchestrator.UnparseResult.Success>()
            u.output shouldContain "2026-01-01"
            u.output shouldNotContain "20454"
        }

        "a date literal compared to GETDATE() is widened to a datetime string (G A7, unchanged)" {
            val translator =
                org.tatrman.translator.orchestrator
                    .Translator(FixtureModel.tfHandle())
            val parsed =
                translator.parseToRelNode(
                    "SELECT a.ID FROM A a WHERE GETDATE() >= '2026-01-01'",
                    org.tatrman.translate.v1.Language.SQL,
                )
            parsed.shouldBeInstanceOf<org.tatrman.translator.orchestrator.ParseResult.Success>()
            val u =
                translator.unparseFromRelNode(parsed.plan, org.tatrman.translate.v1.Language.SQL, SqlDialectProto.MSSQL)
            u.shouldBeInstanceOf<org.tatrman.translator.orchestrator.UnparseResult.Success>()
            u.output shouldContain "'2026-01-01 00:00:00'"
        }

        "a datetime_value that is not ISO-8601 fails loudly instead of quietly becoming a string" {
            shouldThrowAny { unparsePostgres(withDatetimeValue(encode(timestampFilter), "next tuesday")) }
                .message!! shouldContain "ISO-8601"
        }
    })
