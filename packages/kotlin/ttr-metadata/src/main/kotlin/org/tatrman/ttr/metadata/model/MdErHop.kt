// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.metadata.model

/**
 * Grammar 0.17 — the md → er hop (cn contracts §8.1, the md/hop demand §3; architecture §3; CN-D21): where an md
 * element lands on E-R. The seam the cn typed model reads to give an md-grounded concept its [Concept.erTarget]; the
 * md/hop sub-arc fills it. Resolution order per md element (demand §3):
 *
 *  1. an explicit `md2er_*` ([HopVia.EXPLICIT]);
 *  2. for a quality's measure / attribute, the derivation `md2db_* ∘ er2db_*⁻¹` restricted to the bearer concept's
 *     er entity ([HopVia.DERIVED_BEARER], AM-29);
 *  3. the derivation unrestricted, when exactly one er element matches ([HopVia.DERIVED]);
 *  4. else `md/er-ambiguous`; no candidate at all → `md/er-unmapped`.
 *
 * Element refs are the archive's spelling (see [CnSchema]): `md.cubelet.<c>`, `md.dimension.<D>.<a>` in,
 * `er.entity.<e>` / `er.entity.<e>.<a>` out.
 */
interface MdErHop {
    /**
     * Where [mdElement] lands on E-R. [bearerEntity] — the er entity of the bearer concept when a quality asks (rule 2);
     * null otherwise.
     */
    fun erTargetOf(
        mdElement: String,
        bearerEntity: String? = null,
    ): HopResult
}

/** Which rule of the hop order answered. */
enum class HopVia { EXPLICIT, DERIVED, DERIVED_BEARER }

/**
 * An answer of [MdErHop]: the er landing and the rule that found it, or — when there is none — the md/hop lint's
 * slug ([diagnostic]: `md/er-unmapped` · `md/er-ambiguous`) and why ([message]).
 */
data class HopResult(
    val erTarget: String? = null,
    val via: HopVia? = null,
    val diagnostic: String? = null,
    val message: String = "",
) {
    companion object {
        const val UNMAPPED: String = "md/er-unmapped"
        const val AMBIGUOUS: String = "md/er-ambiguous"

        fun unmapped(message: String): HopResult = HopResult(diagnostic = UNMAPPED, message = message)

        fun ambiguous(message: String): HopResult = HopResult(diagnostic = AMBIGUOUS, message = message)
    }
}
