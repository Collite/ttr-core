// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.recipe

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.tatrman.ttrp.ast.AssignEntry
import org.tatrman.ttrp.ast.Assignment
import org.tatrman.ttrp.ast.ContainerDecl
import org.tatrman.ttrp.ast.CubeletStmt
import org.tatrman.ttrp.ast.FragmentBody
import org.tatrman.ttrp.ast.OpCall
import org.tatrman.ttrp.ast.RecipeDecl
import org.tatrman.ttrp.ast.UsesWorld
import org.tatrman.ttrp.diagnostics.TtrpDiagnosticId
import org.tatrman.ttrp.expr.DurationLiteral
import org.tatrman.ttrp.expr.DurationUnit
import org.tatrman.ttrp.expr.FunctionCall
import org.tatrman.ttrp.expr.ParamRef
import org.tatrman.ttrp.parser.TtrpParser

/** AU-core-P0 phase-exit review (review-114): one regression per fixed finding on the TTR-P / TTR-SQL side. */
class RecipeReviewSpec :
    StringSpec({
        fun parse(src: String) = TtrpParser.parseString(src.trimIndent() + "\n", "review.ttrp")

        fun ids(src: String) = parse(src).diagnostics.map { it.id }

        // ---- M1: a duration is not a juxtaposition inside an expression -------------------------------------

        "M1: a comma-less config entry ending in a number is not glued to a following `days = …` entry" {
            val r =
                parse(
                    """
                    x = a -> calc {
                        lead = 30
                        days = amount
                    }
                    """,
                )
            r.diagnostics.shouldBeEmpty()
            val calc = (r.document.statements.single() as Assignment).chain.elements[1] as OpCall
            calc.config!!.entries.map { (it as AssignEntry).name } shouldContainExactly listOf("lead", "days")
        }

        "M1: two cubelet statements stay two" {
            val r =
                parse(
                    """
                    rate = 12
                    months = sales.2025
                    """,
                )
            r.document.statements.filterIsInstance<CubeletStmt>() shouldHaveSize 2
        }

        "M1: in an expression a duration is a call — `days(30)`, `days(:p)`" {
            val r = parse("x = a -> filter(placed >= :as_of - days(30) and due < :as_of + working_days(:settlement))")
            r.diagnostics.shouldBeEmpty()
            val filter = (r.document.statements.single() as Assignment).chain.elements[1] as OpCall
            val text = filter.args.single().toString()
            (text.contains("DurationLiteral(amount=30, unit=DAYS")) shouldBe true
            (text.contains("DurationParam(unit=WORKING_DAYS, param=settlement")) shouldBe true
        }

        // ---- M2: named arguments only where a name means something ------------------------------------------

        "M2: a named argument on an ordinary function is refused (it would bind by position)" {
            ids("x = a -> calc { s = substring(customer, length: 1, start: 3) }") shouldContainExactly
                listOf(TtrpDiagnosticId.PRS_008)
        }

        "M2: a duplicated argument name is refused; the time functions keep their names" {
            ids("x = a -> calc { d = age(since: a, since: b) }") shouldContainExactly listOf(TtrpDiagnosticId.PRS_008)
            val ok = parse("x = a -> calc { d = age(since: placed), w = working_days(between: due, and: :as_of) }")
            ok.diagnostics.shouldBeEmpty()
        }

        // ---- M3: TTR-SQL reads what its own advice says ----------------------------------------------------

        fun sqlBody(sql: String): FragmentBody {
            val src = "container c(in a, out rows) target e \"\"\"sql\n$sql\n\"\"\"\n"
            val r = TtrpParser.parseString(src, "q.ttrp")
            r.diagnostics.map { "${it.id.id} ${it.message}" }.shouldBeEmpty()
            return (r.document.statements.single() as ContainerDecl).body as FragmentBody
        }

        "M3: a double-quoted identifier is a name — the form TTRP-SQL-004 tells the author to use" {
            // (`"a""b"`, not `""hi"""`: three quotes would close the TTR-P tagged block itself)
            val body = sqlBody("SELECT \"Customer\", \"a\"\"b\" FROM a")
            body.decomposition!!
                .statements
                .toString()
                .contains("column=Customer") shouldBe true
            body.decomposition!!
                .statements
                .toString()
                .contains("column=a\"b") shouldBe true
        }

        "M3: a BOM, a no-break space and a form feed are whitespace" {
            sqlBody("SELECT amount\u000CFROM a")
        }

        "M3: bracket quoting is still the named reject" {
            val r =
                TtrpParser.parseString(
                    "container c(in a, out rows) target e \"\"\"sql\nSELECT [Customer] FROM a\n\"\"\"\n",
                    "q.ttrp",
                )
            r.diagnostics.map { it.id } shouldContainExactly listOf(TtrpDiagnosticId.SQL_004)
        }

        "TTR-SQL `:name`: a keyword is a legal parameter name; a lone colon is not a binding" {
            val body = sqlBody("SELECT amount FROM a WHERE amount > :limit")
            body.decomposition!!
                .statements
                .toString()
                .contains("ParamRef(name=limit") shouldBe true
            val r =
                TtrpParser.parseString(
                    "container c(in a, out rows) target e \"\"\"sql\nSELECT amount FROM a WHERE amount > : p\n\"\"\"\n",
                    "q.ttrp",
                )
            r.diagnostics.map { it.id } shouldContainExactly listOf(TtrpDiagnosticId.SQL_015)
        }

        // ---- the minors ---------------------------------------------------------------------------------------

        "an out-of-range number is a diagnostic, never an exception out of the parser" {
            ids(
                """
                recipe {
                    context c(in a, out rows) target e window { by: k over: rows: 99999999999 } { a -> rows }
                    actions { v.r -> ask { due: 99999999999999999999 days } }
                }
                """,
            ) shouldContainExactly List(2) { TtrpDiagnosticId.PRS_006 }
        }

        "a half-typed role container costs only itself — the rest of the document survives" {
            val r =
                parse(
                    """
                    uses world "w"
                    x = a -> filter(b > 1)
                    recipe { context c(in a, out r) { a -> r } }
                    """,
                )
            r.document.statements.filterIsInstance<UsesWorld>() shouldHaveSize 1
            r.document.statements.filterIsInstance<Assignment>() shouldHaveSize 1
            r.document.statements.filterIsInstance<RecipeDecl>() shouldHaveSize 1
        }

        "recipe shape slips are named — TTRP-PRS-007" {
            ids("recipe { key: a.b  key: c.d }") shouldContainExactly listOf(TtrpDiagnosticId.PRS_007)
            ids("recipe { parameters: [tolerance, as_of] }") shouldContainExactly listOf(TtrpDiagnosticId.PRS_007)
            ids("recipe { decision v(in a, out b) { a -> switch { r: x > 0 } } }") shouldContainExactly
                listOf(TtrpDiagnosticId.PRS_007)
            ids("recipe { decision v(in a) { a -> switch { else: none } } }") shouldContainExactly
                listOf(TtrpDiagnosticId.PRS_007)
            ids("recipe { decision v(in a) { a -> switch { all: x > 0 } } }") shouldContainExactly
                listOf(TtrpDiagnosticId.PRS_007)
            ids("x = a -> filter(amount > : limit)") shouldContainExactly listOf(TtrpDiagnosticId.PRS_007)
        }

        "a role container's `on failure of` is checked like a container's" {
            ids(
                """
                recipe {
                    context c(in a, out rows) target e on failure of nothere absorbs { a -> rows }
                }
                """,
            ) shouldContainExactly listOf(TtrpDiagnosticId.FAIL_003, TtrpDiagnosticId.FAIL_001)
        }

        "`as of:` with a number is the syntax error alone, not a value complaint about `null`" {
            val d = parse("recipe { as of: 2026 }").diagnostics
            d.map { it.id } shouldContain TtrpDiagnosticId.PRS_001
            d.none { it.id == TtrpDiagnosticId.PRS_006 } shouldBe true
        }

        "a wiring's `due: 2 working_days` is still a duration value" {
            val r = parse("recipe { actions { v.r -> ask { due: 2 working_days } } }")
            r.diagnostics.shouldBeEmpty()
            val due = (r.document.statements.single() as RecipeDecl).wirings.single().arg("due")!!
            (due as org.tatrman.ttrp.ast.WiringValue.Expr).expression shouldBe
                DurationLiteral(2, DurationUnit.WORKING_DAYS, due.expression.location)
            val p = parse("x = a -> filter(b > :limit)")
            val f = (p.document.statements.single() as Assignment).chain.elements[1] as OpCall
            ((f.args.single().value as org.tatrman.ttrp.ast.ExprArg).expr as FunctionCall)
                .args
                .last()
                .shouldBeInstanceOf<ParamRef>()
        }
    })
