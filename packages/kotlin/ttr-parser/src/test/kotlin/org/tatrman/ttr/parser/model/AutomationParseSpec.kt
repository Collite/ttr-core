// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.parser.model

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.tatrman.ttr.parser.loader.TtrLoader

/**
 * Grammar 0.16 — the automation model (AU-core-P0 S0.2). The design fixtures (`17` §1–§2 as written
 * for 0.16, under `resources/automation/`) parse with zero errors onto [AutomationDef] /
 * [WorldAutoMember] / [ProcessDef]; the parser-level rejects live in `automation-negative/`. Mirrors
 * the TS `automation-grammar.test.ts` and the Python `test_automation.py`.
 */
class AutomationParseSpec :
    StringSpec({

        "17 §1 automation.ttrm: parameters, actions, roles and approvals" {
            val r = TtrLoader.parseString(readResource("automation/automation.ttrm"), "automation.ttrm")
            r.errors shouldBe emptyList()
            r.modelDirective?.modelCode shouldBe "automation"
            val autos = r.definitions.filterIsInstance<AutomationDef>()
            autos.filter { it.kind == "parameter" } shouldHaveSize 9
            autos.filter { it.kind == "action" } shouldHaveSize 10
            autos.filter { it.kind == "approval" } shouldHaveSize 4
            r.definitions.filterIsInstance<RoleDef>() shouldHaveSize 8

            val tolerance = autos.single { it.name == "tolerance" }
            tolerance.body.type?.name shouldBe "decimal"
            (tolerance.body["value"] as AutoValue.Num).raw shouldBe 0.01
            tolerance.body.label?.byLanguage shouldBe mapOf("en" to "Price tolerance", "cs" to "Cenová tolerance")

            val hold = autos.single { it.name == "hold" }
            hold.body.columns.map { it.name } shouldContainExactly listOf("order", "department")
            hold.body.set?.attribute shouldBe "order.held_for"
            hold.body.set?.column shouldBe "department"
            hold.body.transition?.to shouldBe "held"
            hold.body.transition?.from shouldContainExactly listOf("placed")

            val setState = autos.single { it.name == "set_state" }
            setState.body.transition?.toColumn shouldBe "to"
            setState.body.transition
                ?.to
                .shouldBeNull()

            val due = autos.single { it.name == "ask" }.body["due"]
            due.shouldBeInstanceOf<AutoValue.Duration>()
            due.amount shouldBe 2.0
            due.unit shouldBe "working_days"

            val sales = r.definitions.filterIsInstance<RoleDef>().single { it.name == "sales" }
            sales.entries.map { it.key } shouldContainExactly listOf("resolve", "delivery")
            val resolve = sales.entries.first().value
            resolve.shouldBeInstanceOf<AutoValue.Tagged>()
            resolve.tag shouldBe "keycloak_group"
            resolve.value shouldBe "hartland-sales"
        }

        "17 §1 programs.ttrm: programs and event schemas" {
            val r = TtrLoader.parseString(readResource("automation/programs.ttrm"), "programs.ttrm")
            r.errors shouldBe emptyList()
            val programs = r.definitions.filterIsInstance<AutomationDef>().filter { it.kind == "program" }
            programs shouldHaveSize 8
            val decide = programs.single { it.name == "decide_on_request" }
            (decide.body["event"] as AutoValue.Id).ref.path shouldBe "schema.line_received"
            (decide.body["mode"] as AutoValue.Id).ref.path shouldBe "on_event"
            r.definitions.filterIsInstance<SchemaDef>() shouldHaveSize 3
        }

        "17 §2 demo-world.ttrm: the automation members nest in the world" {
            val r = TtrLoader.parseString(readResource("automation/demo-world.ttrm"), "demo-world.ttrm")
            r.errors shouldBe emptyList()
            val world = r.definitions.filterIsInstance<WorldDef>().single()
            world.identities.map { it.name } shouldContainExactly listOf("automation_decider")
            world.grants shouldHaveSize 3
            world.grants
                .first()
                .name
                .shouldBeNull()
            world.grants
                .first()
                .body["program"]
                .shouldBeInstanceOf<AutoValue.Star>()
            (world.grants.first().body["as"] as AutoValue.Id).ref.path shouldBe "identity.automation_decider"
            world.deployments shouldHaveSize 12
            val morning = world.deployments.single { it.name == "release_native_morning" }
            (morning.body.target as TargetReferenceValue).ref.path shouldBe "native"
            val trigger = morning.body["trigger"]
            trigger.shouldBeInstanceOf<AutoValue.Tagged>()
            trigger.tag shouldBe "schedule"
            world.executors.map { it.extends } shouldContainExactly listOf("n8n_l1", "camunda_8", "python_client")
            world.processes
                .single()
                .steps
                .map { it.name } shouldContainExactly listOf("decide", "inbound", "settle")
        }

        "a comma-less literal followed by the next key stays a literal (not a duration)" {
            val r =
                TtrLoader.parseString(
                    "model automation\ndef parameter p { value: 0.01 label: { en: \"P\" } quorum: 2 days }",
                )
            r.errors shouldBe emptyList()
            val p = r.definitions.filterIsInstance<AutomationDef>().single()
            (p.body["value"] as AutoValue.Num).raw shouldBe 0.01
            p.body.label?.byLanguage shouldBe mapOf("en" to "P")
            (p.body["quorum"] as AutoValue.Duration).unit shouldBe "days"
        }

        "an attribute carries states and transitions" {
            val r =
                TtrLoader.parseString(
                    "model er\ndef entity order { attributes: [ def attribute state { type: text, " +
                        "states: [placed, held], transitions: { placed: [held] } } ] }",
                )
            r.errors shouldBe emptyList()
            val attr =
                r.definitions
                    .filterIsInstance<EntityDef>()
                    .single()
                    .attributes
                    .single()
            attr.states shouldContainExactly listOf("placed", "held")
            attr.transitions.single().from shouldBe "placed"
            attr.transitions.single().to shouldContainExactly listOf("held")
        }

        // Table-driven parser-level rejects (automation-negative/*.ttrm).
        listOf(
            "neg-01-toplevel-identity.ttrm",
            "neg-02-named-grant.ttrm",
            "neg-03-transition-no-target.ttrm",
            "neg-04-step-outside-process.ttrm",
        ).forEach { name ->
            "rejects $name" {
                TtrLoader.parseString(readResource("automation-negative/$name")).ok shouldBe false
            }
        }
    })

private fun readResource(path: String): String =
    object {}
        .javaClass.classLoader
        .getResource(path)
        ?.readText()
        ?: error("missing test resource: $path")
