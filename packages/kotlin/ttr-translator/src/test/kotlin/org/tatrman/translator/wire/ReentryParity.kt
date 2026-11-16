// SPDX-License-Identifier: Apache-2.0
package org.tatrman.translator.wire

import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.tatrman.plan.v1.ParameterBinding
import org.tatrman.plan.v1.PlanNode
import org.tatrman.plan.v1.SchemaCode
import org.tatrman.plan.v1.Value
import org.tatrman.translate.v1.Language
import org.tatrman.translate.v1.SqlDialect
import org.tatrman.translator.framework.ModelHandle
import org.tatrman.translator.orchestrator.ParseResult
import org.tatrman.translator.orchestrator.Translator
import org.tatrman.translator.orchestrator.UnparseResult
import org.tatrman.translator.params.SqlParam

/**
 * ttr-core#407 / review-318 — what Query runs, both ways: the single call (`SQL → target=DB`) and the two halves
 * (`SQL → target=ER`, its bytes re-entered as `REL_NODE → target=DB`), each unparsed to PostgreSQL and MSSQL.
 */
internal class ReentryParity(
    private val model: ModelHandle,
) {
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
                parameters = if (language == Language.SQL) params else emptyList(),
            ).let { withClue("parse $language → $target") { it.shouldBeInstanceOf<ParseResult.Success>() }.plan }

    fun single(
        sql: String,
        params: List<SqlParam> = emptyList(),
    ): PlanNode = parse(sql, Language.SQL, SchemaCode.DB, params)

    fun twoHalves(
        sql: String,
        params: List<SqlParam> = emptyList(),
    ): PlanNode {
        val er = parse(sql, Language.SQL, SchemaCode.ER, params)
        return parse(String(er.toByteArray(), Charsets.ISO_8859_1), Language.REL_NODE, SchemaCode.DB, params)
    }

    fun unparse(
        plan: PlanNode,
        dialect: SqlDialect,
        params: List<SqlParam> = emptyList(),
    ): String =
        Translator(model)
            .unparseFromRelNode(plan, Language.SQL, dialect, parameters = params.map(::binding))
            .let { withClue("unparse to $dialect") { it.shouldBeInstanceOf<UnparseResult.Success>() }.output }

    /** The single call unparses on PG and MSSQL, and the two halves give the same SQL; returns the SQL by dialect. */
    fun sameSql(
        sql: String,
        params: List<SqlParam> = emptyList(),
    ): Map<SqlDialect, String> {
        val single = single(sql, params)
        val two = twoHalves(sql, params)
        return DIALECTS.associateWith { dialect ->
            val expected = unparse(single, dialect, params)
            withClue("$dialect: two halves vs the single call") { unparse(two, dialect, params) shouldBe expected }
            expected
        }
    }

    fun sameBytes(
        sql: String,
        params: List<SqlParam> = emptyList(),
    ) {
        withClue("two-half bytes == single-call bytes") {
            twoHalves(sql, params).toByteArray().contentEquals(single(sql, params).toByteArray()) shouldBe true
        }
    }

    private fun binding(p: SqlParam): ParameterBinding =
        ParameterBinding
            .newBuilder()
            .setName(p.name)
            .setType(p.type)
            .setValue(Value.newBuilder().setStringValue(p.value.toString()))
            .build()

    companion object {
        val DIALECTS: List<SqlDialect> = listOf(SqlDialect.POSTGRESQL, SqlDialect.MSSQL)
    }
}
