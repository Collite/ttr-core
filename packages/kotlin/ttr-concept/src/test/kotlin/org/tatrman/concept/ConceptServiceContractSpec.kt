// SPDX-License-Identifier: Apache-2.0
package org.tatrman.concept

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.tatrman.assertion.v1.Assertion
import org.tatrman.concept.v1.ConceptRef
import org.tatrman.statement.v1.Statement

/**
 * CN-P0 S0.4 T3 — the API surface of cn contracts §3, pinned by SHAPE. A rename, an added or a dropped parameter at
 * CN-P3 must change this table in the same commit; it cannot drift silently.
 */
class ConceptServiceContractSpec :
    FunSpec({
        // method name → parameter count, exactly contracts §3
        val expected =
            mapOf(
                "statementValidate" to 1,
                "statementLower" to 1,
                "statementExplain" to 2,
                "assertionValidate" to 1,
                "assertionLower" to 1,
                "assertionExplain" to 2,
                "definitionRender" to 4,
                "materialize" to 2,
                "hygieneReport" to 0,
            )

        test("ConceptService declares exactly the §3 methods, with the §3 arities") {
            val declared = ConceptService::class.java.declaredMethods.associate { it.name to it.parameterCount }
            declared shouldBe expected
        }

        test("the §3 parameter and return types") {
            fun m(name: String) = ConceptService::class.java.declaredMethods.single { it.name == name }
            m("statementLower").parameterTypes.toList() shouldBe listOf(Statement::class.java)
            m("statementLower").returnType shouldBe LowerResult::class.java
            m("assertionLower").parameterTypes.toList() shouldBe listOf(Assertion::class.java)
            m("assertionLower").returnType shouldBe RowObjects::class.java
            m("definitionRender").parameterTypes.toList() shouldBe
                listOf(ConceptRef::class.java, Layer::class.java, String::class.java, RenderOptions::class.java)
            m("definitionRender").returnType shouldBe Rendering::class.java
            m("materialize").returnType shouldBe ModelText::class.java
            m("hygieneReport").returnType shouldBe HygieneReport::class.java
        }

        test("the scaffold stand-in refuses every call with the phase that builds it") {
            val svc: ConceptService = NotImplementedConceptService()
            val calls: List<() -> Any> =
                listOf(
                    { svc.statementValidate(Statement.getDefaultInstance()) },
                    { svc.statementLower(Statement.getDefaultInstance()) },
                    { svc.statementExplain(Statement.getDefaultInstance(), "cs") },
                    { svc.assertionValidate(Assertion.getDefaultInstance()) },
                    { svc.assertionLower(Assertion.getDefaultInstance()) },
                    { svc.assertionExplain(Assertion.getDefaultInstance(), "cs") },
                    { svc.definitionRender(ConceptRef.getDefaultInstance(), Layer.ER, "POSTGRESQL", RenderOptions()) },
                    { svc.materialize(ConceptRef.getDefaultInstance(), "POSTGRESQL") },
                    { svc.hygieneReport() },
                )
            for (call in calls) shouldThrow<UnsupportedOperationException> { call() }.message shouldBe "CN-P3"
        }

        test("every §3 data class constructs empty-safe (defaults only)") {
            LowerResult().provenance.imprecisionMode shouldBe ImprecisionMode.CERTAINLY
            Rendering().text shouldBe ""
            RenderOptions().mode shouldBe RenderMode.PREDICATE
            HygieneReport().concepts shouldBe emptyList()
            RowObjects().rows shouldBe emptyList()
            ModelText().files shouldBe emptyMap()
            Explanation().text shouldBe ""
        }
    })
