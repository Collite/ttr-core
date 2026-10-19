// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.recipe

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.tatrman.ttrp.ast.ActionsBlock
import org.tatrman.ttrp.ast.AsOf
import org.tatrman.ttrp.ast.Assignment
import org.tatrman.ttrp.ast.ContainerDecl
import org.tatrman.ttrp.ast.FlowBody
import org.tatrman.ttrp.ast.FragmentBody
import org.tatrman.ttrp.ast.GroupByEntry
import org.tatrman.ttrp.ast.Grain
import org.tatrman.ttrp.ast.IslandForm
import org.tatrman.ttrp.ast.OpCall
import org.tatrman.ttrp.ast.PortKind
import org.tatrman.ttrp.ast.RecipeDecl
import org.tatrman.ttrp.ast.RecipeProtocol
import org.tatrman.ttrp.ast.RecipeRole
import org.tatrman.ttrp.ast.RelationArg
import org.tatrman.ttrp.ast.SwitchMode
import org.tatrman.ttrp.ast.WindowOver
import org.tatrman.ttrp.ast.WiringValue
import org.tatrman.ttrp.diagnostics.TtrpDiagnosticId
import org.tatrman.ttrp.expr.ColumnRef
import org.tatrman.ttrp.expr.DurationLiteral
import org.tatrman.ttrp.expr.DurationParam
import org.tatrman.ttrp.expr.DurationUnit
import org.tatrman.ttrp.expr.FunctionCall
import org.tatrman.ttrp.expr.InList
import org.tatrman.ttrp.expr.ParamRef
import org.tatrman.ttrp.parser.Fixtures
import org.tatrman.ttrp.parser.TtrpParseResult
import org.tatrman.ttrp.parser.TtrpParser

/**
 * AU-core-P0 · S0.1 T1 — the recipe block parses into the AST of T2. The four hero recipes of AU design
 * `17` §3–§4 (the shared corpus every later module's spec reads: `src/test/resources/recipes/`), every
 * header and island form of AU contracts §1, the soft-keyword regression, and the structural negatives.
 * The semantic recipe checks (routes ↔ wiring, parameters, REC-000) are S1.1's — not asserted here.
 */
class RecipeParseSpec :
    StringSpec({

        fun parse(path: String): TtrpParseResult = TtrpParser.parseString(Fixtures.read("/recipes/$path"), path)

        fun recipeOf(r: TtrpParseResult): RecipeDecl =
            r.document.statements
                .filterIsInstance<RecipeDecl>()
                .single()

        "the four 17 hero recipes parse with no diagnostic at all (fragments decomposed too)" {
            for (n in listOf("release_order", "decide_return", "monitor_inbound", "settle")) {
                val r = parse("$n.ttrp")
                r.diagnostics
                    .map { "${it.id.id} ${it.location.line}:${it.location.column} ${it.message}" }
                    .shouldBeEmpty()
                val recipe = recipeOf(r)
                // every `target` role container with a fragment body was decomposed like a container's
                recipe.roleContainers
                    .mapNotNull { it.body as? FragmentBody }
                    .forEach { it.decomposition.shouldNotBeNull() }
            }
        }

        "H-1 release_order: header, roles, the overlapping switch, the wiring" {
            val recipe = recipeOf(parse("release_order.ttrp"))
            recipe.key.map { it.text } shouldContainExactly listOf("order.order_number")
            recipe.asOf shouldBe AsOf.Now
            recipe.parameters.map { it.text } shouldContainExactly listOf("tolerance", "confirmation_threshold")
            recipe.document shouldBe "releasing-a-placed-order.md"
            recipe.protocol.shouldBeNull()

            val cand = recipe.candidates.single()
            cand.name shouldBe "placed_orders"
            cand.ports.map { it.kind to it.name } shouldContainExactly listOf(PortKind.OUT to "orders")
            cand.form
                .shouldBeInstanceOf<IslandForm.Target>()
                .target.text shouldBe "erp"
            cand.body.shouldBeInstanceOf<FragmentBody>().tag shouldBe "sql"

            recipe.contexts.map { it.name } shouldContainExactly listOf("shortfall", "price_differences", "overdue")
            recipe.contexts.forEach { c ->
                c.role shouldBe RecipeRole.CONTEXT
                c.ports.map { it.kind to it.name } shouldContainExactly
                    listOf(PortKind.IN to "orders", PortKind.OUT to "rows")
            }
            // shortfall is canonical: the 1..* relation join, `latest`, `:as_of` in a filter
            val shortfall =
                recipe.contexts
                    .first()
                    .body
                    .shouldBeInstanceOf<FlowBody>()
            val lines = shortfall.statements.first().shouldBeInstanceOf<Assignment>()
            val join = lines.chain.elements[1].shouldBeInstanceOf<OpCall>()
            join.name shouldBe "join"
            join.args
                .first { it.name == "on" }
                .value
                .shouldBeInstanceOf<RelationArg>()
                .qname.text shouldBe
                "rel_order_line"

            val decision = recipe.decisions.single()
            decision.name shouldBe "verdict"
            decision.ports.map { it.name } shouldContainExactly
                listOf("orders", "shortfall", "price_differences", "overdue")
            decision.statements shouldHaveSize 4
            // `aggregate { group_by: order_number … }` — the LD spelling (D-108)
            val nShort = decision.statements.first().shouldBeInstanceOf<Assignment>()
            val agg = nShort.chain.elements[1].shouldBeInstanceOf<OpCall>()
            agg.config!!
                .entries
                .filterIsInstance<GroupByEntry>()
                .single()
                .keys shouldContainExactly
                listOf("order_number")

            val sw = decision.switch
            sw.input.parts shouldContainExactly listOf("v")
            sw.mode shouldBe SwitchMode.OVERLAPPING
            sw.routes.map { it.name } shouldContainExactly
                listOf("hold_sales", "hold_ar", "release_confirmed", "release")
            sw.routes[0].because shouldBe "stock shortfall or price difference"
            sw.routes[0].detail.map { it.text } shouldContainExactly listOf("shortfall", "price_differences")
            sw.routes[1].detail.map { it.text } shouldContainExactly listOf("overdue")
            sw.routes[3].detail.shouldBeEmpty()
            sw.elseRoute.shouldBeNull()
            // a route condition is one expression, `:param` included
            val release = sw.routes[3].condition.shouldBeInstanceOf<FunctionCall>()
            release.args
                .last()
                .shouldBeInstanceOf<FunctionCall>()
                .args
                .last() shouldBe
                ParamRef(
                    "confirmation_threshold",
                    release.args
                        .last()
                        .shouldBeInstanceOf<FunctionCall>()
                        .args
                        .last()
                        .location,
                )

            val w = recipe.wirings
            w shouldHaveSize 6
            w[0].routes.map { "${it.decision}.${it.route}" } shouldContainExactly listOf("verdict.release")
            w[0].action shouldBe "release"
            w[0]
                .arg(
                    "order",
                ).shouldBeInstanceOf<WiringValue.Expr>()
                .expression
                .shouldBeInstanceOf<ColumnRef>()
                .column shouldBe
                "order_number"
            // the confirmation ask: a role, a duration, an action call for on_due (D-98)
            val ask = w[1]
            ask.action shouldBe "ask"
            ask.arg("who").shouldBeInstanceOf<WiringValue.Expr>().expression.shouldBeInstanceOf<ColumnRef>().let {
                it.port shouldBe "role"
                it.column shouldBe "sales_manager"
            }
            ask.arg("due").shouldBeInstanceOf<WiringValue.Expr>().expression.shouldBeInstanceOf<DurationLiteral>().let {
                it.amount shouldBe 2L
                it.unit shouldBe DurationUnit.WORKING_DAYS
            }
            val onDue = ask.arg("on_due").shouldBeInstanceOf<WiringValue.Call>()
            onDue.action shouldBe "hold"
            onDue.args.map { it.name } shouldContainExactly listOf("order", "department")
            // `detail: shortfall, price_differences` — a bare list
            w[3]
                .arg(
                    "detail",
                ).shouldBeInstanceOf<WiringValue.Values>()
                .items
                .map { (it as ColumnRef).column } shouldContainExactly
                listOf("shortfall", "price_differences")
            w.forEach { it.whenCondition.shouldBeNull() }
        }

        "H-2 R2a decide_return: exclusive, an else route, the lookup context, durations from parameters" {
            val recipe = recipeOf(parse("decide_return.ttrp"))
            recipe.key.single().text shouldBe "return_request_line.line_id"
            recipe.parameters shouldHaveSize 6
            recipe.contexts.map { it.name } shouldContainExactly
                listOf("bought_here", "return_share", "request_value", "stock_at_center")
            // the lookup context reads no candidates port
            recipe.contexts
                .first { it.name == "request_value" }
                .ports
                .map { it.kind } shouldContainExactly
                listOf(PortKind.OUT)

            val sw = recipe.decisions.single().switch
            sw.mode shouldBe SwitchMode.EXCLUSIVE
            sw.routes.map { it.name } shouldContainExactly
                listOf(
                    "reject_not_ours",
                    "reject_late",
                    "reject_abuse",
                    "to_customer_care",
                    "refund_no_return",
                    "replacement_no_return",
                    "exchange",
                )
            sw.elseRoute!!.name shouldBe "refund_after_return"
            sw.elseRoute!!.because shouldBe "goods back, then the money"
            sw.routeNames.last() shouldBe "refund_after_return"
            // `reason in ("damaged", "did_not_arrive")` inside the route condition
            val refund =
                sw.routes
                    .first { it.name == "refund_no_return" }
                    .condition
                    .shouldBeInstanceOf<FunctionCall>()
            refund.args
                .last()
                .shouldBeInstanceOf<InList>()
                .items shouldHaveSize 2

            val w = recipe.wirings
            w shouldHaveSize 11
            w[0].routes.map { it.route } shouldContainExactly listOf("reject_not_ours", "reject_late", "reject_abuse")
            w[0]
                .arg(
                    "to",
                ).shouldBeInstanceOf<WiringValue.Expr>()
                .expression
                .shouldBeInstanceOf<ColumnRef>()
                .column shouldBe
                "rejected"
            // `group_by:` on an ask (D-100) and a literal duration
            w[2].arg("group_by").shouldNotBeNull()
            w[2].arg("due").shouldBeInstanceOf<WiringValue.Expr>().expression shouldBe
                DurationLiteral(3, DurationUnit.WORKING_DAYS, (w[2].arg("due") as WiringValue.Expr).expression.location)
            // `working_days(:settlement)` / `days(:inbound_reminder)` — the amount is a parameter (17 §5)
            w[4].arg("due").shouldBeInstanceOf<WiringValue.Expr>().expression.shouldBeInstanceOf<DurationParam>().let {
                it.unit shouldBe DurationUnit.WORKING_DAYS
                it.param shouldBe "settlement"
            }
            w[9].arg("due").shouldBeInstanceOf<WiringValue.Expr>().expression.shouldBeInstanceOf<DurationParam>().let {
                it.unit shouldBe DurationUnit.DAYS
                it.param shouldBe "inbound_reminder"
            }
        }

        "H-2 R2b monitor_inbound: `else: none`, `age(since: …)`" {
            val recipe = recipeOf(parse("monitor_inbound.ttrp"))
            val sw = recipe.decisions.single().switch
            sw.mode shouldBe SwitchMode.EXCLUSIVE
            sw.routes.map { it.name } shouldContainExactly listOf("complete", "damaged", "expired", "remind")
            sw.elseRoute
                .shouldNotBeNull()
                .name
                .shouldBeNull() // `else: none` — no verdict row (D-80)
            sw.routeNames shouldContainExactly listOf("complete", "damaged", "expired", "remind")

            val age =
                recipe.contexts
                    .first { it.name == "age" }
                    .body
                    .shouldBeInstanceOf<FlowBody>()
            val calc =
                age.statements
                    .single()
                    .let { (it as org.tatrman.ttrp.ast.ChainStmt).chain.elements }
                    .filterIsInstance<OpCall>()
                    .first { it.name == "calc" }
            val days =
                (calc.args.single().value as org.tatrman.ttrp.ast.ExprArg)
                    .expr
                    .shouldBeInstanceOf<FunctionCall>()
            val ageCall = days.args.last().shouldBeInstanceOf<FunctionCall>()
            ageCall.function.name shouldBe "age"
            ageCall.argNames shouldContainExactly listOf("since")
            recipe.wirings shouldHaveSize 8
        }

        "H-2 R2c settle: `working_days(between:, and: :as_of)`, comma-separated config entries, no else" {
            val recipe = recipeOf(parse("settle.ttrp"))
            val overdue =
                recipe.contexts
                    .first { it.name == "overdue_days" }
                    .body
                    .shouldBeInstanceOf<FlowBody>()
            val ops =
                (overdue.statements.single() as org.tatrman.ttrp.ast.ChainStmt)
                    .chain.elements
                    .filterIsInstance<OpCall>()
            val late =
                (
                    ops
                        .first { it.name == "calc" }
                        .args
                        .single()
                        .value as org.tatrman.ttrp.ast.ExprArg
                ).expr
                    .shouldBeInstanceOf<FunctionCall>()
                    .args
                    .last()
                    .shouldBeInstanceOf<FunctionCall>()
            late.function.name shouldBe "working_days"
            late.argNames shouldContainExactly listOf("between", "and")
            late.args
                .last()
                .shouldBeInstanceOf<ParamRef>()
                .name shouldBe "as_of"
            val agg = ops.first { it.name == "aggregate" }.config!!
            agg.entries
                .filterIsInstance<GroupByEntry>()
                .single()
                .keys shouldContainExactly listOf("line_id")
            agg.entries.filterIsInstance<org.tatrman.ttrp.ast.AssignEntry>().map { it.name } shouldContainExactly
                listOf("max_late", "kinds_open")

            val sw = recipe.decisions.single().switch
            sw.mode shouldBe SwitchMode.OVERLAPPING
            sw.routes shouldHaveSize 6
            sw.elseRoute.shouldBeNull()
            recipe.wirings shouldHaveSize 7
        }

        "every header and island form of contracts §1" {
            val r = parse("forms/island-forms.ttrp")
            r.diagnostics.map { "${it.id.id} ${it.location.line}:${it.location.column} ${it.message}" }.shouldBeEmpty()
            val recipe = recipeOf(r)
            recipe.name shouldBe "forms_tour"
            recipe.key.map { it.text } shouldContainExactly listOf("sales_line.order_number", "sales_line.line_no")
            recipe.asOf shouldBe AsOf.Instant("2026-10-01T06:00:00+02:00")
            recipe.protocol shouldBe RecipeProtocol.ON_ROUTE

            val byName = recipe.contexts.associateBy { it.name }
            byName.getValue("distance").form.shouldBeInstanceOf<IslandForm.Builtin>().let {
                it.capability.text shouldBe "geo.distance"
                it.config!!.entries shouldHaveSize 2
            }
            byName.getValue("distance").body.shouldBeNull()
            byName.getValue("scored").form.shouldBeInstanceOf<IslandForm.Foreign>().let {
                it.language shouldBe "python"
                it.grain shouldBe Grain.PER_CANDIDATE
                it.schema shouldBe "line_score"
            }
            // a foreign body is python, not a TTR fragment — never decomposed
            byName
                .getValue("scored")
                .body
                .shouldBeInstanceOf<FragmentBody>()
                .decomposition
                .shouldBeNull()
            byName.getValue("priced").form.shouldBeInstanceOf<IslandForm.Call>().let {
                it.service.text shouldBe "pricing.quote"
                it.grain shouldBe Grain.SET
            }
            byName.getValue("checked").form.shouldBeInstanceOf<IslandForm.Turn>().let {
                it.agent.text shouldBe "agents.care"
                it.ask shouldBe "Is this line a duplicate?"
                it.answerSchema shouldBe "duplicate_answer"
            }
            byName.getValue("per_day").window!!.over shouldBe WindowOver.Calendar("day")
            byName.getValue("last_five").window!!.let {
                it.by.map { b -> b.text } shouldContainExactly listOf("item", "order_number")
                it.over shouldBe WindowOver.Rows(5)
            }
            byName
                .getValue("sessions")
                .window!!
                .over
                .shouldBeInstanceOf<WindowOver.Gap>()
                .duration
                .unit shouldBe DurationUnit.MINUTES

            val sw = recipe.decisions.single().switch
            sw.mode.shouldBeNull()
            sw.elseRoute!!.name.shouldBeNull()
            val wirings =
                recipe.members
                    .filterIsInstance<ActionsBlock>()
                    .single()
                    .wirings
            wirings[0].whenCondition.shouldNotBeNull()
            wirings[1].routes.single().isAll shouldBe true
        }

        "every recipe word stays an ordinary name outside a recipe (the WORLD precedent)" {
            val r = parse("forms/soft-keywords.ttrp")
            r.diagnostics.map { "${it.id.id} ${it.location.line}:${it.location.column} ${it.message}" }.shouldBeEmpty()
            val c =
                r.document.statements
                    .filterIsInstance<ContainerDecl>()
                    .single()
            (c.body as FlowBody).statements.filterIsInstance<Assignment>().map { it.target } shouldContainExactly
                listOf("key", "mode", "call", "per")
        }

        // ---- the structural negatives ------------------------------------------------------------

        fun ids(path: String) = parse(path).diagnostics.map { it.id }

        "two recipe blocks parse — refusing them is the S1.1 checker's TTRP-REC-000" {
            val r = parse("negative/rec-000-two-blocks.ttrp")
            r.diagnostics.shouldBeEmpty()
            r.document.statements.filterIsInstance<RecipeDecl>() shouldHaveSize 2
        }

        "a program header beside a recipe is TTRP-PRS-002" {
            ids("negative/rec-000-program-and-recipe.ttrp") shouldContainExactly listOf(TtrpDiagnosticId.PRS_002)
        }

        "`because:` takes a string — a malformed attribute reads as a route and is named, TTRP-PRS-007" {
            ids("negative/bad-route-attr.ttrp") shouldContainExactly listOf(TtrpDiagnosticId.PRS_007)
        }

        "the retired `reason:` spelling is named — TTRP-PRS-007" {
            val r = parse("negative/reason-spelling.ttrp")
            r.diagnostics.map { it.id } shouldContainExactly listOf(TtrpDiagnosticId.PRS_007)
            r.diagnostics
                .single()
                .message
                .contains("because:") shouldBe true
        }

        "enumerated values outside the contract — TTRP-PRS-006 for each" {
            ids("negative/bad-values.ttrp") shouldContainExactly List(4) { TtrpDiagnosticId.PRS_006 }
        }

        "a target role container needs a body — TTRP-PRS-007" {
            ids("negative/target-no-body.ttrp") shouldContainExactly listOf(TtrpDiagnosticId.PRS_007)
        }

        "a recipe block is top-level only — TTRP-PRS-007" {
            ids("negative/nested-recipe.ttrp") shouldContainExactly listOf(TtrpDiagnosticId.PRS_007)
        }
    })
