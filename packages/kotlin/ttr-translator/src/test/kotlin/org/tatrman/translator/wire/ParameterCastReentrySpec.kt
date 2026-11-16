// SPDX-License-Identifier: Apache-2.0
package org.tatrman.translator.wire

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.tatrman.plan.v1.Expression
import org.tatrman.plan.v1.PlanNode
import org.tatrman.plan.v1.SchemaCode
import org.tatrman.translate.v1.Language
import org.tatrman.translator.framework.HartlandJoinModel
import org.tatrman.translator.framework.TranslatorFramework
import org.tatrman.translator.orchestrator.ParseResult
import org.tatrman.translator.orchestrator.Translator
import org.tatrman.translator.params.SqlParam

/**
 * AU-core S2.4c (ttr-core#407) T2 — the smallest shape of the hartland `reject_late` failure: an author's
 * `CAST({p} AS VARCHAR)` over an `int` parameter, inside `||`.
 *
 * [org.tatrman.translator.params.ParameterTyper] unwrapped every `CAST(?)` over a declared parameter — meant for the
 * validation crutch `CAST(? AS <declared type>)` the bridge's typed retry adds, it also took the author's own cast,
 * to another type. The ER plan then concatenated a bare `int` parameter (the call keeping its validated `text` type),
 * and the REL_NODE re-entry re-derived `||`'s type from its operands: Calcite's `checkArgument`, an
 * `IllegalArgumentException` with no message. The neighbours name the target: without `||` the plan decoded but had
 * silently lost the cast; a cast over a column, or to the parameter's own declared type, was never touched.
 */
class ParameterCastReentrySpec :
    StringSpec({
        val model = HartlandJoinModel.handle()

        fun parse(
            source: String,
            language: Language,
            target: SchemaCode,
            params: List<SqlParam>,
        ): PlanNode =
            Translator(model)
                .parseToRelNode(
                    source,
                    language,
                    targetSchema = target,
                    sourceSchema = if (language == Language.SQL) SchemaCode.ER else SchemaCode.SCHEMA_CODE_UNSPECIFIED,
                    parameters = params,
                ).shouldBeInstanceOf<ParseResult.Success>()
                .plan

        fun er(
            sql: String,
            params: List<SqlParam>,
        ) = parse(sql, Language.SQL, SchemaCode.ER, params)

        /** Pass 2 of Query's compile: the ER plan's bytes re-entered as REL_NODE, to the db tier. */
        fun reentered(erPlan: PlanNode) =
            parse(String(erPlan.toByteArray(), Charsets.ISO_8859_1), Language.REL_NODE, SchemaCode.DB, emptyList())

        fun single(
            sql: String,
            params: List<SqlParam>,
        ) = parse(sql, Language.SQL, SchemaCode.DB, params)

        /** The (only) projected expression of the plan's root Project. */
        fun projected(plan: PlanNode): Expression =
            plan.project.expressionsList
                .single()
                .expression

        val intW = listOf(SqlParam("w", "int", 30))
        val textW = listOf(SqlParam("w", "text", "30"))

        fun decodes(
            sql: String,
            params: List<SqlParam>,
        ) {
            PlanNodeDecoder.decode(PlanNode.parseFrom(er(sql, params).toByteArray()), TranslatorFramework(model))
        }

        fun twoHalvesEqualSingle(
            sql: String,
            params: List<SqlParam>,
        ) {
            reentered(er(sql, params)).toByteArray().contentEquals(single(sql, params).toByteArray()) shouldBe true
        }

        val failing = "SELECT 'delivered more than ' || CAST({w} AS VARCHAR) || ' days ago' AS r FROM sale"

        "M1 — an int parameter cast to text inside || : the ER plan decodes (red before #407)" {
            decodes(failing, intW)
        }

        "M2 — the same: two halves give the single-call plan byte for byte (red before #407)" {
            twoHalvesEqualSingle(failing, intW)
        }

        "M3 — the author's cast stays in the plan: the parameter is cast, not a bare int (red before #407)" {
            val cast = projected(er("SELECT CAST({w} AS VARCHAR) AS r FROM sale", intW))
            cast.function.operation shouldBe "cast"
            cast.function.operandsList
                .single()
                .parameter.name shouldBe "w"
            cast.function.operandsList
                .single()
                .resultType shouldBe "int"
            twoHalvesEqualSingle("SELECT CAST({w} AS VARCHAR) AS r FROM sale", intW)
        }

        "M4 — control: a cast over a column inside || re-enters (green before #407)" {
            val sql = "SELECT 'shopper ' || CAST(shopper_id AS VARCHAR) AS r FROM sale"
            decodes(sql, emptyList())
            twoHalvesEqualSingle(sql, emptyList())
        }

        "M5 — control: a cast to the parameter's own declared type is the crutch's shape and is unwrapped (green before #407)" {
            val sql = "SELECT 'delivered more than ' || CAST({w} AS VARCHAR) || ' days ago' AS r FROM sale"
            decodes(sql, textW)
            twoHalvesEqualSingle(sql, textW)
            // `||`'s middle operand is the bare text parameter, no cast
            val concat = projected(er(sql, textW)).function
            val inner = concat.operandsList.first { it.hasFunction() }.function
            inner.operandsList.any { it.hasParameter() && it.parameter.name == "w" } shouldBe true
        }

        "M6 — control: the typed-retry crutch over a bare parameter in || leaves no cast (green before #407)" {
            val sql = "SELECT sale_month || {w} AS r FROM sale"
            val concat = projected(er(sql, textW)).function
            concat.operandsList.map { it.exprCase } shouldBe
                listOf(Expression.ExprCase.COLUMN_REF, Expression.ExprCase.PARAMETER)
            twoHalvesEqualSingle(sql, textW)
        }
    })
