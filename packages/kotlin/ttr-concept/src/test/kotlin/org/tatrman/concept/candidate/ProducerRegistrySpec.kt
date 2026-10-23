// SPDX-License-Identifier: Apache-2.0
package org.tatrman.concept.candidate

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

/**
 * CN-P5 S5.2 T4 — who may submit (design `10` §4.1, A-CN-83): a test per producer pattern. The built-ins are the
 * ecosystem's own producers; an evidence miner and a span catalogue belong to a deployment, which the store registers
 * (`clinic` here — a deployment name never appears in this public module); an importer never submits (L15-5), whatever
 * is registered.
 */
class ProducerRegistrySpec :
    FunSpec({
        val concept =
            Proposal.Concept(
                Stereo.PHASE,
                "LargeOrder",
                of = "cn.Order",
                definitionText = "order_value > 1000",
            )
        val term = Proposal.Term("cn.Transfer", null, "cs", listOf("přeložení"))
        val relation = Proposal.Relation("stocks", "cn.Warehouse", "cn.Item")
        val all = listOf(concept, term, relation)
        val registry = ProducerRegistry(deployments = setOf("clinic"))

        fun refusal(
            producer: String,
            proposal: Proposal = concept,
            r: ProducerRegistry = registry,
        ): String = r.admit(producer, proposal).shouldBeInstanceOf<Admission.Refused>().code

        test("kleio — the document pipeline — submits any proposal") {
            for (p in all) registry.admit("kleio", p) shouldBe Admission.Admitted
        }

        test("au:synthesizer — the recipe authoring surface (Q14-7) — submits any proposal") {
            for (p in all) registry.admit("au:synthesizer", p) shouldBe Admission.Admitted
            refusal("au:planner") shouldBe ProducerRegistry.CN_PRODUCER_UNREGISTERED
        }

        test("manual:<steward> — a person — submits any proposal, and must name the steward") {
            for (p in all) registry.admit("manual:a.steward", p) shouldBe Admission.Admitted
            registry.admit("manual:steward@example.org", term) shouldBe Admission.Admitted
            refusal("manual") shouldBe ProducerRegistry.CN_PRODUCER_UNREGISTERED
            refusal("manual:") shouldBe ProducerRegistry.CN_PRODUCER_UNREGISTERED
        }

        test("<deployment>:evidence — an evidence miner — submits for a registered deployment only") {
            for (p in all) registry.admit("clinic:evidence", p) shouldBe Admission.Admitted
            refusal("ward:evidence") shouldBe ProducerRegistry.CN_PRODUCER_UNREGISTERED
            refusal("clinic:evidence", r = ProducerRegistry()) shouldBe ProducerRegistry.CN_PRODUCER_UNREGISTERED
        }

        test("<deployment>:span-catalogue submits terms only — a member alias is MV's, never cn's") {
            registry.admit("clinic:span-catalogue", term) shouldBe Admission.Admitted
            refusal("clinic:span-catalogue", concept) shouldBe ProducerRegistry.CN_PRODUCER_TERMS_ONLY
            refusal("clinic:span-catalogue", relation) shouldBe ProducerRegistry.CN_PRODUCER_TERMS_ONLY
        }

        test(
            "import:* never submits (L15-5) — an importer registers vocabulary versions, it does not propose concepts",
        ) {
            for (id in listOf("import:athena@v5", "import:icd10@2026", "import")) {
                for (p in all) refusal(id, p) shouldBe ProducerRegistry.CN_PRODUCER_IMPORTER
            }
        }

        test("anything else is unregistered") {
            for (id in listOf("golem", "clinic", "clinic:other", "kleio:docs", "au:synthesizer:x", "Kleio", "")) {
                refusal(id) shouldBe ProducerRegistry.CN_PRODUCER_UNREGISTERED
            }
        }

        test("a deployment cannot take a built-in's name, nor the importer's") {
            for (name in listOf("kleio", "au", "manual", "import", "Bad Name", "")) {
                shouldThrow<IllegalArgumentException> { ProducerRegistry(deployments = setOf(name)) }
            }
        }
    })
