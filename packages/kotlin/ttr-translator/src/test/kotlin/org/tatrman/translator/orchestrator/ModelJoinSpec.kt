// SPDX-License-Identifier: Apache-2.0
package org.tatrman.translator.orchestrator

import com.google.protobuf.TextFormat
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.tatrman.plan.v1.PlanNode
import org.tatrman.plan.v1.SchemaCode
import org.tatrman.translate.v1.Language
import org.tatrman.translate.v1.SqlDialect
import org.tatrman.translator.framework.DfpJoinModel

/**
 * MJ (model joins) — end-to-end acceptance of the corpus in project `model-joins/plan/plan.md §4`.
 *
 * P0·S1 baseline (RED): H1–H3 fail at Calcite validation with `… join requires a condition`; H10 (comma
 * form) is GREEN already and pins today's plan bytes.
 */
class ModelJoinSpec :
    StringSpec({
        val translator = Translator(DfpJoinModel.handle())

        fun mssql(sql: String): TranslateResult =
            translator.translate(
                source = sql,
                sourceLanguage = Language.SQL,
                targetLanguage = Language.SQL,
                targetSchema = SchemaCode.DB,
                targetDialect = SqlDialect.MSSQL,
                sourceSchema = SchemaCode.ER,
            )

        "H1 — the hero chain resolves to three model conditions and MSSQL" {
            val r = mssql(DfpJoinModel.H1_HERO).shouldBeInstanceOf<TranslateResult.Success>()
            // kp ⋈ dm, dm ⋈ z (NOT kp ⋈ z), kp ⋈ p — rendered on the physical columns.
            r.output shouldContain "QKUMPRODEJ"
            r.output shouldContain "QDODMISTO"
            r.output shouldContain "IDSUBJEKT" // zákazník key crosses the rename
            r.output shouldContain "TOP (10)"
            r.output.count { it == '=' } shouldBe 3
            r.warnings shouldBe emptyList()
        }

        "H2 — aliases are used in the synthesised conditions" {
            val r = mssql(DfpJoinModel.H2_ALIASES).shouldBeInstanceOf<TranslateResult.Success>()
            r.output.count { it == '=' } shouldBe 2
            r.warnings shouldBe emptyList()
        }

        "H3 — a bare LEFT JOIN keeps its join type" {
            val r = mssql(DfpJoinModel.H3_LEFT).shouldBeInstanceOf<TranslateResult.Success>()
            r.output shouldContain "LEFT JOIN"
            r.output shouldNotContain "INNER JOIN"
            r.output.count { it == '=' } shouldBe 1
        }

        "H10 — the comma form is conditioned as before" {
            val r = mssql(DfpJoinModel.H10_COMMA).shouldBeInstanceOf<TranslateResult.Success>()
            r.output shouldContain "INNER JOIN"
            r.output.count { it == '=' } shouldBe 1
            r.warnings shouldBe emptyList()
        }

        "H10 — the comma form's plan bytes are unchanged from the pre-MJ golden (P0·S1.3)" {
            val r = mssql(DfpJoinModel.H10_COMMA).shouldBeInstanceOf<TranslateResult.Success>()
            val goldenText =
                checkNotNull(javaClass.getResourceAsStream("/mj/h10-comma-plan.txtpb")) { "golden missing" }
                    .bufferedReader()
                    .readText()
            val golden = PlanNode.newBuilder().also { TextFormat.merge(goldenText, it) }.build()
            r.plan shouldBe golden
            r.plan.toByteArray().contentEquals(golden.toByteArray()) shouldBe true
        }
    })
