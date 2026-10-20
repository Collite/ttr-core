// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.expr

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.tatrman.ttrp.DeclaredSchemaSource
import org.tatrman.ttrp.TtrpFrontend
import org.tatrman.ttrp.diagnostics.Severity
import org.tatrman.ttrp.diagnostics.TtrpDiagnosticId
import org.tatrman.ttrp.parser.TtrpParser

/**
 * TTRP 0.5 (CN-P2 S2.6 T3/T4, CN-D29): the conceptual-model definition forms — cn contracts §1.1 "The 0.5 spellings"
 * (A-CN-25…31). Each form parses to its node; the TTR-P spellings a definition reuses (`:as_of`, `:name`, `hours(24)`,
 * `within(…, last:)`, `<>`) stay what they are; the negatives are syntax errors; and in a TTR-P program every form is
 * refused by the typechecker (`TTRP-CN-001`) — the grammar stays target-neutral, the context decides.
 */
class TtrpConceptFormsSpec :
    StringSpec({
        fun parse(source: String): Expression {
            val r = TtrpParser.parseExpression(source)
            r.diagnostics.shouldBeEmpty()
            return r.expression
        }

        fun errors(source: String) =
            TtrpParser.parseExpression(source).diagnostics.filter { it.severity == Severity.ERROR }

        "exists … involving this as <label> where <phase> (DC-2, fixture 75's DebtorCustomer)" {
            val e =
                parse(
                    "exists Invoicing involving this as customer where OverdueInvoice",
                ).shouldBeInstanceOf<ExistsExpr>()
            e.negated shouldBe false
            e.concepts.map { it.concept to it.variable } shouldBe listOf("Invoicing" to null)
            e.involving.single().label shouldBe "customer"
            e.involving
                .single()
                .path
                .shouldBeInstanceOf<ThisRef>()
                .path shouldBe emptyList()
            e.where.shouldBeInstanceOf<ColumnRef>().column shouldBe "OverdueInvoice"
        }

        "not exists with two participants and no where (DC-11) folds into the node" {
            val e =
                parse(
                    "not exists OrderLine involving this.request.order, this.item",
                ).shouldBeInstanceOf<ExistsExpr>()
            e.negated shouldBe true
            e.where.shouldBeNull()
            e.involving.map { (it.path as ThisRef).path } shouldBe listOf(listOf("request", "order"), listOf("item"))
            // a double negation reads back as an existence
            parse("not not exists A involving this").shouldBeInstanceOf<ExistsExpr>().negated shouldBe false
        }

        "named event variables, `<>` and durations as calls (DC-7, A-CN-27/29)" {
            val e =
                parse(
                    "exists Encounter e1, Encounter e2 involving this as patient " +
                        "where e1 <> e2 and e2.period.start - e1.period.end between days(0) and days(30)",
                ).shouldBeInstanceOf<ExistsExpr>()
            e.concepts.map { it.concept to it.variable } shouldBe listOf("Encounter" to "e1", "Encounter" to "e2")
            val and = e.where.shouldBeInstanceOf<FunctionCall>()
            and.function shouldBe CatalogId.AND
            and.args[0].shouldBeInstanceOf<FunctionCall>().function shouldBe CatalogId.NEQ
        }

        "where is greedy — it takes a following `or`; parentheses end it" {
            parse("exists A involving this where x = 1 or y = 2")
                .shouldBeInstanceOf<ExistsExpr>()
                .where
                .shouldBeInstanceOf<FunctionCall>()
                .function shouldBe CatalogId.OR
            val or = parse("(exists A involving this where x = 1) or y = 2").shouldBeInstanceOf<FunctionCall>()
            or.function shouldBe CatalogId.OR
            or.args[0].shouldBeInstanceOf<ExistsExpr>()
        }

        "a concept aggregate: count, sum(…).quality, latest(…, by:).quality (DC-4, DC-9)" {
            val cmp = parse("count(StoreVisit involving this) >= 4").shouldBeInstanceOf<FunctionCall>()
            cmp.function shouldBe CatalogId.GTE
            cmp.args[0].shouldBeInstanceOf<ConceptAggregate>().function shouldBe "count"

            val sum =
                parse("sum(Sale involving this where within(period.at, last: days(365))).revenue")
                    .shouldBeInstanceOf<ConceptAggregate>()
            sum.member shouldBe listOf("revenue")
            val within = sum.where.shouldBeInstanceOf<FunctionCall>()
            within.function.name shouldBe "within"
            within.argNames shouldBe listOf(null, "last")
            within.args[1].shouldBeInstanceOf<DurationLiteral>().unit shouldBe DurationUnit.DAYS

            val latest =
                parse(
                    "coalesce(latest(Inventory involving this.item, this.warehouse, by: as_of_date).quantity_on_hand, 0)",
                ).shouldBeInstanceOf<FunctionCall>()
                    .args[0]
                    .shouldBeInstanceOf<ConceptAggregate>()
            latest.function shouldBe "latest"
            latest.involving.size shouldBe 2
            latest.namedArgs.map { it.first } shouldBe listOf("by")
            latest.member shouldBe listOf("quantity_on_hand")
        }

        "an ordinary call stays a call: count(x), sum(a, b), count(distinct x)" {
            parse("count(x)").shouldBeInstanceOf<AggregateCall>()
            parse("count(distinct x)").shouldBeInstanceOf<AggregateCall>().distinct shouldBe true
            parse("coalesce(a, b)").shouldBeInstanceOf<FunctionCall>()
        }

        "hierarchy membership: `in <call>` beside `in (<list>)`; members: is a call (DC-1, DC-8)" {
            val inCall = parse("code in descendants(Diagnosis, 'I20-I25')").shouldBeInstanceOf<InCall>()
            inCall.negated shouldBe false
            inCall.call
                .shouldBeInstanceOf<FunctionCall>()
                .function.name shouldBe "descendants"
            parse("code not in descendants(Diagnosis, 'I21')").shouldBeInstanceOf<InCall>().negated shouldBe true
            parse("state in ('placed', 'held')").shouldBeInstanceOf<InList>()
            parse("descendants(ProductCategory, 'H10-H19')").shouldBeInstanceOf<FunctionCall>()
        }

        "the DC-12 words are infix at the comparison level" {
            listOf(
                "d.period.start at_or_before :as_of" to TemporalOp.AT_OR_BEFORE,
                "a before b" to TemporalOp.BEFORE,
                "e.period contains :as_of" to TemporalOp.CONTAINS,
                "e.period ended_before :as_of" to TemporalOp.ENDED_BEFORE,
                "s.period overlaps e.period" to TemporalOp.OVERLAPS,
            ).forEach { (source, op) ->
                val t = parse(source).shouldBeInstanceOf<TemporalPredicate>()
                t.op shouldBe op
            }
            parse("e.period contains :as_of")
                .shouldBeInstanceOf<TemporalPredicate>()
                .rhs
                .shouldBeInstanceOf<ParamRef>()
                .name shouldBe "as_of"
            // binds tighter than `and`, looser than arithmetic
            val and = parse("x = 1 and a - b before c").shouldBeInstanceOf<FunctionCall>()
            and.function shouldBe CatalogId.AND
            and.args[1]
                .shouldBeInstanceOf<TemporalPredicate>()
                .lhs
                .shouldBeInstanceOf<FunctionCall>()
                .function shouldBe CatalogId.SUB
        }

        "navigation: `this` and a path from it; `end` after a dot (DC-6)" {
            val sub = parse("this.period.at - this.from.period.end").shouldBeInstanceOf<FunctionCall>()
            sub.args.map { (it as ThisRef).path } shouldBe
                listOf(listOf("period", "at"), listOf("from", "period", "end"))
            parse("this").shouldBeInstanceOf<ThisRef>().path shouldBe emptyList()
            val lt = parse("invoice_status = 'open' and period.end < :as_of").shouldBeInstanceOf<FunctionCall>()
            lt.args[1].shouldBeInstanceOf<FunctionCall>().args[0].shouldBeInstanceOf<ColumnRef>().let {
                it.port shouldBe "period"
                it.column shouldBe "end"
            }
            // `end` still closes a case
            parse("case when a then x.y else z end").shouldBeInstanceOf<CaseWhen>()
        }

        "the TTR-P spellings a definition reuses (A-CN-25…28)" {
            parse("abs(unit_price - list_price) > :tolerance")
            parse("working_days(between: due, and: :as_of) > 5")
            parse("within(t.period.at, from: t.from.period.start, to: t.from.period.start + hours(24))")
                .shouldBeInstanceOf<FunctionCall>()
                .argNames shouldBe listOf(null, "from", "to")
        }

        "negatives: a bare `involving`, `as` without a label, no `involving`, a positional aggregate argument" {
            listOf(
                "x involving this",
                "exists A involving this as",
                "exists A involving this as where y = 1",
                "exists A where x = 1",
                "exists A",
                "latest(Inventory involving this, by: d, e)",
                "count(A involving this where)",
            ).forEach { source ->
                val errs = errors(source)
                errs.shouldNotBeEmpty()
                errs.first().id shouldBe TtrpDiagnosticId.PRS_001
            }
        }

        "negatives: the design spellings 0.5 re-spelled (A-CN-27/29) are syntax errors" {
            listOf("e1 != e2", "x <= 24h", "within(x, last(365, days)) and y covers z").forEach { source ->
                errors(source).shouldNotBeEmpty()
            }
        }

        "a concept aggregate names an argument once" {
            errors("latest(I involving this, by: a, by: b)").map { it.id } shouldContainExactly
                listOf(TtrpDiagnosticId.PRS_008)
        }

        "every new word but `this` is soft: a TTR-P program keeps them as column names and calc targets" {
            val schema =
                DeclaredSchemaSource(
                    mapOf(
                        "s" to
                            listOf(
                                "exists",
                                "involving",
                                "where",
                                "before",
                                "contains",
                                "overlaps",
                                "at_or_before",
                                "ended_before",
                            ).map { Column(it, TtrpType.Integer) },
                    ),
                )
            val program =
                "f = filter(s, exists > 0 and involving = 1 and before < contains and overlaps = 2)\n" +
                    "g = filter(s, where = at_or_before + ended_before)\n" +
                    "h = filter(s, exists > 0) -> calc { contains = before + 1, overlaps = where }\n"
            TtrpFrontend.check(program, schema, fileName = "soft.ttrp").diagnostics.filter {
                it.severity == Severity.ERROR
            } shouldBe emptyList()
        }

        "in a TTR-P program each form is refused by the typechecker: TTRP-CN-001" {
            val schema =
                DeclaredSchemaSource(
                    mapOf(
                        "s" to listOf(Column("code", TtrpType.Str), Column("d", TtrpType.Date)),
                    ),
                )
            listOf(
                "exists A involving this" to "`exists`",
                "not exists A involving this" to "`not exists`",
                "count(A involving this) > 1" to "`count(<concept> involving …)`",
                "code in descendants(K, 'X')" to "`in <call>`",
                "d before d" to "`before`",
                "this.code = 'a'" to "`this`",
            ).forEach { (predicate, named) ->
                val errs =
                    TtrpFrontend
                        .check("f = filter(s, $predicate)", schema, fileName = "cn.ttrp")
                        .diagnostics
                        .filter { it.severity == Severity.ERROR }
                errs.map { it.id } shouldContainExactly listOf(TtrpDiagnosticId.CN_001)
                errs.single().message shouldContain named
            }
        }
    })
