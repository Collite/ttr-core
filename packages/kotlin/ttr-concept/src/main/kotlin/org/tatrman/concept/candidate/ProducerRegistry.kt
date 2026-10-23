// SPDX-License-Identifier: Apache-2.0
package org.tatrman.concept.candidate

/** Whether a store takes a candidate from a producer. */
sealed interface Admission {
    data object Admitted : Admission

    data class Refused(
        val code: String,
        val reason: String,
    ) : Admission
}

/**
 * CN-P5 S5.2 T4 (design `10` §4.1, A-CN-83) — who may submit a candidate, checked at `submit` (S5.3). The schema checks
 * only a producer id's shape; this is the list.
 *
 * - **Built in** — the ecosystem's own producers: `kleio` (documents), `au:synthesizer` (the recipe authoring surface,
 *   Q14-7), `manual:<steward>` (a person, by name).
 * - **Per deployment** — `<deployment>:evidence` (an evidence miner: phases from code sets in questions and documents)
 *   and `<deployment>:span-catalogue` (aliases; **terms only** — a member alias is MV's business, never cn's). The store
 *   registers its [deployments]; no deployment is named here.
 * - **Never** — `import:*` (L15-5): an importer registers vocabulary versions in the snapshot; it does not propose
 *   concepts, whatever a store registers.
 */
class ProducerRegistry(
    val deployments: Set<String> = emptySet(),
) {
    init {
        for (d in deployments) {
            require(DEPLOYMENT.matches(d) && d !in RESERVED) {
                "'$d' cannot name a deployment (a lower-case word, not one of $RESERVED)"
            }
        }
    }

    fun admit(
        producer: String,
        proposal: Proposal,
    ): Admission {
        if (producer == "import" || producer.startsWith("import:")) {
            return Admission.Refused(
                CN_PRODUCER_IMPORTER,
                "'$producer' is an importer: it registers vocabulary versions, it never proposes cn content (L15-5)",
            )
        }
        if (producer in BUILT_IN || STEWARD.matches(producer)) return Admission.Admitted
        val deployment = producer.substringBefore(':', "")
        val role = producer.substringAfter(':', "")
        if (deployment in deployments) {
            when (role) {
                EVIDENCE -> return Admission.Admitted
                SPAN_CATALOGUE ->
                    return if (proposal is Proposal.Term) {
                        Admission.Admitted
                    } else {
                        Admission.Refused(
                            CN_PRODUCER_TERMS_ONLY,
                            "'$producer' submits terms only: a member alias is MV's, a concept alias a cn term",
                        )
                    }
            }
        }
        return Admission.Refused(CN_PRODUCER_UNREGISTERED, "'$producer' is not a registered producer")
    }

    companion object {
        const val CN_PRODUCER_UNREGISTERED = "CN_PRODUCER_UNREGISTERED"
        const val CN_PRODUCER_IMPORTER = "CN_PRODUCER_IMPORTER"
        const val CN_PRODUCER_TERMS_ONLY = "CN_PRODUCER_TERMS_ONLY"

        const val EVIDENCE = "evidence"
        const val SPAN_CATALOGUE = "span-catalogue"

        private val BUILT_IN = setOf("kleio", "au:synthesizer")
        private val STEWARD = Regex("^manual:[A-Za-z0-9._@-]+$")
        private val DEPLOYMENT = Regex("^[a-z][a-z0-9-]*$")
        private val RESERVED = setOf("kleio", "au", "manual", "import")
    }
}
