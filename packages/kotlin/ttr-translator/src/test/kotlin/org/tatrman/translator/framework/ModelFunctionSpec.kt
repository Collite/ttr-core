// SPDX-License-Identifier: Apache-2.0
package org.tatrman.translator.framework

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.tatrman.plan.v1.Expression
import org.tatrman.plan.v1.PlanNode
import org.tatrman.plan.v1.SchemaCode
import org.tatrman.translate.v1.Language
import org.tatrman.translator.orchestrator.ParseResult
import org.tatrman.translator.orchestrator.Translator
import org.tatrman.translator.tf.Outcome
import org.tatrman.translator.tf.TfParam
import org.tatrman.translator.tf.roundTrip
import org.tatrman.translator.wire.PlanNodeDecoder
import org.tatrman.translator.wire.PlanNodeEncoder

/**
 * TF-P5.S1 (G C1; contracts §3.1, §3.6) — scalar functions the model declares
 * ([ModelHandle.functions]) resolve during validation, ride the plan.v1 wire under their qualified
 * lower-cased name and unparse schema-qualified (T-SQL requires the schema on a scalar UDF call).
 */
class ModelFunctionSpec :
    StringSpec({
        val translator = Translator(FixtureModel.handleWithFunctions())

        fun plan(
            sql: String,
            t: Translator = translator,
            sourceSchema: SchemaCode = SchemaCode.SCHEMA_CODE_UNSPECIFIED,
        ): PlanNode {
            val r = t.parseToRelNode(sql, Language.SQL, SchemaCode.DB, null, sourceSchema)
            r.shouldBeInstanceOf<ParseResult.Success>()
            return r.plan
        }

        fun functionCalls(e: Expression): List<Expression> =
            if (e.hasFunction()) listOf(e) + e.function.operandsList.flatMap(::functionCalls) else emptyList()

        fun projectExpressions(p: PlanNode): List<Expression> =
            when {
                p.hasProject() -> p.project.expressionsList.map { it.expression } + projectExpressions(p.project.input)
                p.hasFilter() -> projectExpressions(p.filter.input)
                p.hasSort() -> projectExpressions(p.sort.input)
                p.hasJoin() -> projectExpressions(p.join.left) + projectExpressions(p.join.right)
                else -> emptyList()
            }

        fun failure(sql: String): ParseResult.Failure {
            val r = translator.parseToRelNode(sql, Language.SQL)
            r.shouldBeInstanceOf<ParseResult.Failure>()
            return r
        }

        "a declared function parses, validates and unparses schema-qualified" {
            roundTrip(translator, "SELECT dbo.fn_price(a.ID, a.B_ID, GETDATE()) AS P FROM A a", emptyList()) shouldBe
                Outcome.Unparsed("SELECT [dbo].[fn_price]([ID], [B_ID], GETDATE()) AS [P] FROM [dbo].[A]")
        }

        "the plan carries the qualified lower-cased name and the declared return type" {
            val call =
                projectExpressions(plan("SELECT dbo.fn_price(a.ID, a.B_ID, GETDATE()) AS P FROM A a"))
                    .flatMap(::functionCalls)
                    .single { it.function.operation.contains("fn_price") }
            call.function.operation shouldBe "dbo.fn_price"
            // DECIMAL(18,4) — the physical return — reads as the surface `float`.
            call.resultType shouldBe "float"
        }

        "encode(decode(plan)) is byte-identical for a declared function call" {
            val p = plan("SELECT dbo.fn_price(a.ID, a.B_ID, GETDATE()) AS P, dbo.fn_today() AS T FROM A a")
            PlanNodeEncoder.encode(
                PlanNodeDecoder.decode(p, TranslatorFramework(FixtureModel.handleWithFunctions())),
            ) shouldBe
                p
        }

        "an unqualified call resolves too, and still unparses with the schema" {
            roundTrip(translator, "SELECT fn_price(a.ID, a.B_ID, GETDATE()) AS P FROM A a", emptyList()) shouldBe
                Outcome.Unparsed("SELECT [dbo].[fn_price]([ID], [B_ID], GETDATE()) AS [P] FROM [dbo].[A]")
        }

        "lookup is case-insensitive" {
            roundTrip(translator, "SELECT DBO.FN_PRICE(a.ID, a.B_ID, GETDATE()) AS P FROM A a", emptyList()) shouldBe
                Outcome.Unparsed("SELECT [dbo].[fn_price]([ID], [B_ID], GETDATE()) AS [P] FROM [dbo].[A]")
        }

        "a declared function named like a built-in: the bare name stays the built-in, the qualified one is the UDF" {
            val lenQname =
                FixtureModel.tfFunctions[0]
                    .qname
                    .toBuilder()
                    .setName("len")
                    .build()
            val len = ModelFunction(lenQname, listOf(SurfaceType.TEXT), SurfaceType.INT)
            val t =
                Translator(
                    InMemoryModelHandle(
                        tables =
                            FixtureModel
                                .tfHandle()
                                .tables(SchemaCode.DB, "dbo")
                                .values
                                .toList(),
                        functions = FixtureModel.tfFunctions + len,
                    ),
                )
            roundTrip(t, "SELECT LEN(a.NAME) AS L, dbo.len(a.NAME) AS U FROM A a", emptyList()) shouldBe
                Outcome.Unparsed("SELECT LEN([NAME]) AS [L], [dbo].[len]([NAME]) AS [U] FROM [dbo].[A]")
        }

        "a niladic declared function" {
            roundTrip(translator, "SELECT dbo.fn_today() AS T FROM A a", emptyList()) shouldBe
                Outcome.Unparsed("SELECT [dbo].[fn_today]() AS [T] FROM [dbo].[A]")
        }

        "a declared function in WHERE and inside another function" {
            roundTrip(
                translator,
                "SELECT a.ID FROM A a WHERE ISNULL(dbo.fn_price(a.ID, a.B_ID, dbo.fn_today()), 0) > 10",
                emptyList(),
            ) shouldBe
                // The literal takes the DECIMAL(18,4) return's type in the comparison.
                Outcome.Unparsed(
                    "SELECT [ID] FROM [dbo].[A] WHERE ISNULL([dbo].[fn_price]([ID], [B_ID], [dbo].[fn_today]()), 0) > 10.0",
                )
        }

        "arguments are passed as written — no CAST to the declared parameter type" {
            roundTrip(
                translator,
                "SELECT dbo.fn_price(a.ID * 1.5, a.B_ID, CONVERT(date, GETDATE())) AS P FROM A a",
                emptyList(),
            ) shouldBe
                Outcome.Unparsed(
                    "SELECT [dbo].[fn_price]([ID] * 1.5, [B_ID], CONVERT(DATE, GETDATE())) AS [P] FROM [dbo].[A]",
                )
        }

        "a parameter placeholder argument takes the declared parameter type" {
            roundTrip(
                translator,
                "SELECT dbo.fn_price({id}, a.B_ID, {d}) AS P FROM A a",
                listOf(TfParam("id", "int"), TfParam("d", "datetime")),
            ) shouldBe
                Outcome.Unparsed("SELECT [dbo].[fn_price](?, [B_ID], ?) AS [P] FROM [dbo].[A]")
        }

        "an operand outside the parameter's family is refused, not cast" {
            for (args in listOf("a.NAME, a.B_ID, GETDATE()", "a.ID, a.B_ID, 'x'", "a.ID, a.B_ID, 5")) {
                val f = failure("SELECT dbo.fn_price($args) AS P FROM A a")
                f.code shouldBe "validation_failed"
                f.message shouldContain "Cannot apply 'fn_price' to arguments of type"
                f.message shouldContain "fn_price(<NUMERIC>, <NUMERIC>, <DATETIME>)"
            }
        }

        "a NULL argument fits any parameter" {
            roundTrip(translator, "SELECT dbo.fn_price(NULL, a.B_ID, GETDATE()) AS P FROM A a", emptyList()) shouldBe
                Outcome.Unparsed("SELECT [dbo].[fn_price](NULL, [B_ID], GETDATE()) AS [P] FROM [dbo].[A]")
        }

        "the wrong arity is a validation failure naming the signature" {
            val f = failure("SELECT dbo.fn_price(a.ID) AS P FROM A a")
            f.code shouldBe "validation_failed"
            f.message shouldContain "No match found for function signature fn_price(<NUMERIC>)"
        }

        "an undeclared function fails exactly as without the feature" {
            val withFunctions = failure("SELECT dbo.fn_other(1) AS P FROM A a")
            val without =
                Translator(
                    FixtureModel.tfHandle(),
                ).parseToRelNode("SELECT dbo.fn_other(1) AS P FROM A a", Language.SQL)
            without.shouldBeInstanceOf<ParseResult.Failure>()
            withFunctions.code shouldBe without.code
            withFunctions.message shouldBe without.message
        }

        "a model without functions does not resolve fn_price" {
            val r =
                Translator(FixtureModel.tfHandle()).parseToRelNode(
                    "SELECT dbo.fn_price(a.ID, a.B_ID, GETDATE()) AS P FROM A a",
                    Language.SQL,
                )
            r.shouldBeInstanceOf<ParseResult.Failure>()
            r.message shouldContain "fn_price"
        }

        "ER source → DB target keeps the call opaque while MapToPhysical rewrites the scan (P5.1.6)" {
            val t = Translator(FixtureModel.handleWithEntitiesAndFunctions())
            val p = plan("SELECT dbo.fn_price(c.id, c.id, GETDATE()) AS p FROM customer c", t, SchemaCode.ER)
            projectExpressions(p).flatMap(::functionCalls).map { it.function.operation } shouldContainFn "dbo.fn_price"
            roundTrip(t, "SELECT dbo.fn_price(c.id, c.id, GETDATE()) AS p FROM customer c", emptyList()) shouldBe
                Outcome.Unparsed("SELECT [dbo].[fn_price]([id], [id], GETDATE()) AS [p] FROM [dbo].[customers]")
        }
    })

private infix fun List<String>.shouldContainFn(name: String) {
    (name in this) shouldBe true
}
