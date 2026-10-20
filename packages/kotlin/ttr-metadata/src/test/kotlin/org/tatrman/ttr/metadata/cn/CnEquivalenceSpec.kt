// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.metadata.cn

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.tatrman.ttr.metadata.model.Binding
import org.tatrman.ttr.metadata.model.CnSchema
import org.tatrman.ttr.metadata.model.Grounding
import org.tatrman.ttr.semantics.cn.GroundingOrigin

/**
 * Grammar 0.17 — sugar ≡ canonical (CN-P2 S2.5 T7, plan TDD): a project whose concepts ground themselves (`grounds:`,
 * fixture 81) or are declared and grounded by `concept { }` blocks in their hosts (83) gives the SAME typed cn tier as
 * its twin written with cn defs and authored bindings (82, 84) — what differs by construction is masked: binding names
 * and their origin (`Item.grounds` vs `item_g`), where each thing is written, and the synthetic flag of a desugared
 * binding. Every concept property, every landing, the reverse index are compared whole.
 */
class CnEquivalenceSpec :
    StringSpec({
        fun load(dir: String) = CnFixtures.load(CnFixtures.dir(CnFixtures.conformance.resolve(dir), "hartland/stores"))

        fun masked(g: Grounding): Grounding =
            g.copy(
                internalId = "",
                qname = g.qname.copy(name = "_"),
                origin = GroundingOrigin.Binding,
                binding = Binding.BoundReal,
                sourceFile = "",
                sourceLocation = null,
            )

        /** The tier with what differs by construction masked. */
        fun canonical(cn: CnSchema): Map<String, Any?> =
            mapOf(
                "concepts" to
                    cn.concepts.mapValues { (_, c) ->
                        c.copy(
                            sourceFile = "",
                            sourceLocation = null,
                            grounding = c.grounding?.let(::masked),
                            lexiconLinks = c.lexiconLinks.map { it.copy(sourceFile = "", sourceLocation = null) },
                        )
                    },
                "bindings" to
                    cn.bindings.values
                        .map(::masked)
                        .sortedBy { it.concept.name },
                "relationTypes" to cn.relationTypes,
                "reverseIndex" to cn.reverseIndex,
            )

        listOf(
            Triple("81-cn-desugar-top-down", "82-cn-desugar-top-down-authored", 15),
            Triple("83-cn-desugar-bottom-up", "84-cn-desugar-bottom-up-authored", 12),
        ).forEach { (sugar, authored, concepts) ->
            "$sugar gives the same cn tier as $authored" {
                val s = load(sugar)
                val a = load(authored)
                s.result.errors shouldBe emptyList()
                a.result.errors shouldBe emptyList()
                canonical(s.cn) shouldBe canonical(a.cn)
                // the pair is not vacuous: every concept is there and grounded, md and er both land
                s.cn.concepts.size shouldBe concepts
                s.cn.concepts.values
                    .all { it.grounding != null } shouldBe true
                s.cn.reverseIndex.keys
                    .any { it.startsWith("md.") } shouldBe true
                s.cn.reverseIndex.keys
                    .any { it.startsWith("er.") } shouldBe true
            }
        }
    })
