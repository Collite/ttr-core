// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.graph.recipe

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.tatrman.ttrp.diagnostics.Severity
import org.tatrman.ttrp.diagnostics.TtrpDiagnosticId

/**
 * AU-core-P1 · S1.1 + S1.2 (AU contracts §4): the four `17` recipes check clean against the hartland model, and
 * every negative fixture fires exactly its id, naming the offending element.
 */
class RecipeCheckerSpec :
    StringSpec({
        fun ids(name: String) = RecipeFixtures.findings(RecipeFixtures.negative(name)).map { it.id }

        fun message(name: String) =
            RecipeFixtures.findings(RecipeFixtures.negative(name)).joinToString("\n") { it.message }

        for (f in RecipeFixtures.SEVENTEEN) {
            "the `17` recipe $f checks clean — no error, no warning" {
                val r = RecipeFixtures.sharedRecipe(f)
                RecipeFixtures
                    .findings(
                        r,
                    ).map { "${it.id.id} ${it.location.line}:${it.location.column} ${it.message}" }
                    .shouldBeEmpty()
                // until AU-core-P2 a recipe is checked, never built
                r.diagnostics.map { it.id } shouldContainExactly listOf(TtrpDiagnosticId.REC_099)
                r.recipe!!.mode shouldBe RecipeMode.PER_CANDIDATE
            }
        }

        "the positive controls check clean" {
            for (f in listOf("base-order.ttrp", "base-line.ttrp")) {
                RecipeFixtures.findings(RecipeFixtures.fixture(f)).shouldBeEmpty()
            }
        }

        "REC-000 — the role wiring: a port nothing feeds, a missing candidates island, no actions" {
            val r =
                RecipeFixtures.inline(
                    """
                    key: order.order_number
                    candidates placed(out orders) target erp { load(er.entity.order) -> orders }
                    context c(in nowhere, out rows) target erp { nowhere -> rows }
                    decision v(in orders) { orders -> switch { go: value > 0  because: "x" } }
                    actions { v.go -> release { order: order_number } }
                    """,
                )
            RecipeFixtures.findings(r).map { it.id } shouldContainExactly listOf(TtrpDiagnosticId.REC_000)
            RecipeFixtures.findings(r).single().message shouldContain "`c` reads `nowhere`"

            val bare =
                RecipeFixtures.inline(
                    """
                    key: order.order_number
                    context c(out rows) target erp { load(er.entity.order) -> rows }
                    """,
                )
            RecipeFixtures.findings(bare).map { it.message }.let { msgs ->
                msgs.any { "no `candidates` island" in it } shouldBe true
                msgs.any { "wires no actions" in it } shouldBe true
            }
        }

        "REC-001 — an unknown action, a missing required column" {
            ids("rec-001-unknown-action.ttrp") shouldContainExactly listOf(TtrpDiagnosticId.REC_001)
            message("rec-001-unknown-action.ttrp") shouldContain "no action `relase`"
            ids("rec-001-missing-column.ttrp") shouldContainExactly listOf(TtrpDiagnosticId.REC_001)
            message("rec-001-missing-column.ttrp") shouldContain "`hold` needs its column `department`"
        }

        "REC-002 — a column of the wrong type" {
            ids("rec-002-type.ttrp") shouldContainExactly listOf(TtrpDiagnosticId.REC_002)
            message("rec-002-type.ttrp") shouldContain "`hold.order` (order.order_number) gets a string value"
        }

        "REC-002 — a role, rows and an action column read their own kinds of value" {
            val r =
                RecipeFixtures.inline(
                    """
                    key: order.order_number
                    candidates placed(out orders) target erp { load(er.entity.order) -> orders }
                    decision v(in orders) { orders -> switch { go: value > 0  because: "x" } }
                    actions {
                        v.go -> notify { who: sales, detail: placed }
                        v.go -> ask    { who: role.nobody, about: order_number, action: nonesuch }
                        v.go -> notify { who: role.sales, detail: nothere }
                    }
                    """,
                )
            val msgs = RecipeFixtures.findings(r).filter { it.id == TtrpDiagnosticId.REC_002 }.map { it.message }
            msgs.any { "`notify.who` (role) takes a role" in it } shouldBe true
            msgs.any { "names `role.nobody`" in it } shouldBe true
            msgs.any { "`ask.action` (action) takes an action" in it } shouldBe true
            msgs.any { "`notify.detail` (rows) takes rows" in it } shouldBe true
            msgs.size shouldBe 4
        }

        "REC-003 — a route nothing consumes, and a wiring naming no route" {
            ids("rec-003-unconsumed-route.ttrp") shouldContainExactly listOf(TtrpDiagnosticId.REC_003)
            message("rec-003-unconsumed-route.ttrp") shouldContain "route `release` is wired to no action"
            val r =
                RecipeFixtures.inline(
                    """
                    key: order.order_number
                    candidates placed(out orders) target erp { load(er.entity.order) -> orders }
                    decision v(in orders) { orders -> switch { go: value > 0  because: "x"  else: none } }
                    actions { v.go -> release { order: order_number }  v.og -> release { order: order_number } }
                    """,
                )
            RecipeFixtures.findings(r).single().message shouldContain "`v.og` is no route"
        }

        "REC-004 — the candidates do not project the key" {
            ids("rec-004-no-key.ttrp") shouldContainExactly listOf(TtrpDiagnosticId.REC_004)
        }

        "REC-005 — the candidates filter on the key" {
            ids("rec-005-key-filter.ttrp") shouldContainExactly listOf(TtrpDiagnosticId.REC_005)
        }

        "REC-010 — a set-level context without a window: a warning, an error under an on-event program" {
            val warn = RecipeFixtures.findings(RecipeFixtures.negative("rec-010-self-join.ttrp")).single()
            warn.id shouldBe TtrpDiagnosticId.REC_010
            warn.severity shouldBe Severity.WARNING
            warn.message shouldContain "context `siblings`"
            warn.message shouldContain "not on the key"
            val error = RecipeFixtures.findings(RecipeFixtures.negative("rec-010-event.ttrp")).single()
            error.severity shouldBe Severity.ERROR
            error.message shouldContain "program `probe_event_setlevel` runs it per event"
        }

        "REC-010 — a window grouping by a column its rows lack" {
            ids("window-by-missing.ttrp") shouldContainExactly listOf(TtrpDiagnosticId.REC_010)
            message("window-by-missing.ttrp") shouldContain "`by: warehouse`"
        }

        "REC-011 — a daily batch program over a weekly window" {
            ids("rec-011-batch-scope.ttrp") shouldContainExactly listOf(TtrpDiagnosticId.REC_011)
            message("rec-011-batch-scope.ttrp") shouldContain "`probe_weekly_batch`"
        }

        "REC-012 — `port: key` on a set-level recipe" {
            ids("rec-012-port-key-on-set-level.ttrp") shouldContainExactly listOf(TtrpDiagnosticId.REC_012)
            message("rec-012-port-key-on-set-level.ttrp") shouldContain "program `probe_key`"
        }

        "REC-020 — a `:name` nothing binds" {
            ids("rec-020-unbound-param.ttrp") shouldContainExactly listOf(TtrpDiagnosticId.REC_020)
            message("rec-020-unbound-param.ttrp") shouldContain "`:limit`"
        }

        "REC-021 — an unknown `def parameter` (error), an unused one (warning)" {
            ids("rec-021-unknown-param.ttrp") shouldContainExactly listOf(TtrpDiagnosticId.REC_021)
            message("rec-021-unknown-param.ttrp") shouldContain "`nonesuch` is no `def parameter`"
            val unused = RecipeFixtures.findings(RecipeFixtures.negative("rec-021-unused-param.ttrp")).single()
            unused.id shouldBe TtrpDiagnosticId.REC_021
            unused.severity shouldBe Severity.WARNING
            unused.message shouldContain "`tolerance`"
        }

        "REC-030 — a transition to a state the attribute does not have" {
            ids("rec-030-bad-transition.ttrp") shouldContainExactly listOf(TtrpDiagnosticId.REC_030)
            message("rec-030-bad-transition.ttrp") shouldContain "to `shipped`"
        }

        "REC-040 — an agent's answer fires an action that needs no approval" {
            ids("rec-040-turn-release.ttrp") shouldContainExactly listOf(TtrpDiagnosticId.REC_040)
            RecipeFixtures
                .negative(
                    "rec-040-turn-release.ttrp",
                ).recipe!!
                .routes
                .single { it.name == "release" }
                .turnFed shouldBe
                true
        }

        "REC-060 / REC-070 — parse-time: an island that writes, a decision with an island of its own" {
            RecipeFixtures.negative("rec-060-dml.ttrp").diagnostics.map { it.id } shouldContainExactly
                listOf(TtrpDiagnosticId.SQL_001, TtrpDiagnosticId.REC_060)
            RecipeFixtures.negative("rec-070-fragment-decision.ttrp").diagnostics.map { it.id } shouldContainExactly
                listOf(TtrpDiagnosticId.REC_070)
        }

        "a call-valued `action:` and an `on_due:` call are checked as the actions they name" {
            val r =
                RecipeFixtures.inline(
                    """
                    key: order.order_number
                    candidates placed(out orders) target erp { load(er.entity.order) -> orders }
                    decision v(in orders) { orders -> switch { go: value > 0  because: "x" } }
                    actions {
                        v.go -> ask { who: role.sales_manager, about: order_number, action: release { order: order_number },
                                      due: 2 working_days, on_due: hold { order: order_number } }
                    }
                    """,
                )
            RecipeFixtures.findings(r).map { it.message } shouldContainExactly
                listOf("action `hold` needs its column `department`")
        }
    })
