// SPDX-License-Identifier: Apache-2.0
package org.tatrman.concept

import org.tatrman.ttr.lexicon.TermNormalizer
import org.tatrman.ttr.metadata.model.CnSchema
import org.tatrman.ttr.metadata.model.Concept
import org.tatrman.ttr.metadata.model.GroundingLayer
import org.tatrman.ttr.metadata.model.LexiconLink

/**
 * Hygiene report v2 (cn contracts §3 `hygieneReport()`, §7; CN-P2 S2.7 T2) over a [ResolvedCnModel] — model-only, no
 * consumer. A row per concept, in package then name order:
 *
 *  - **grade · via · grounding · hop** — the typed tier's (S2.5): an md grounding's hop answer is the hop column (the
 *    er landing, or `md/er-unmapped` / `md/er-ambiguous`); a defined phase inherits its bearer's landing;
 *  - **core / open** — a stereotype concept is core (CN-D7); its open-tier content is listed: the free-form keys and the
 *    `relations:` edges; the `def relation_type`s are the report's own open-tier list;
 *  - **dangling groundings** — every ref a grounding of the concept writes that does not resolve;
 *  - **redundant term duplicates** — a form a concept's term shares (same locale, the lexicon's collision fold) with a
 *    term on an element that lifts to the concept (its grounding target or its E-R landing — the reverse index's
 *    elements; never a phase's);
 *  - **phase as default** — a phase's `default: true` term whose form also names a concept up its `of:` chain, nearest
 *    first (AM-36); one row per term;
 *  - **retired codes** — empty until vocabulary versions register (P4 S4.2).
 *
 * The definition-level findings ([ResolvedCnModel.definitionDiagnostics]) close the report — Kotlin only (CN-D29);
 * the editor's report (`@tatrman/semantics` `cnHygieneReport`) is this one less them.
 */
object CnHygiene {
    fun report(model: ResolvedCnModel): HygieneReport {
        val cn = model.cn
        val concepts =
            cn.concepts.values
                .sortedWith(compareBy({ it.qname.`package` }, { it.qname.name }))
                .map { row(cn, it) }
        val relationTypes =
            cn.relationTypes.values
                .map { "cn.${it.qname.name}" }
                .sorted()
        return HygieneReport(concepts, relationTypes, model.definitionDiagnostics())
    }

    private fun row(
        cn: CnSchema,
        c: Concept,
    ): ConceptHygiene {
        val g = c.grounding
        return ConceptHygiene(
            qname = c.ref,
            packageName = c.qname.`package`,
            stereotype = c.stereotype,
            grade = c.grade,
            via = c.via,
            grounding = g?.let { GroundingRow(it.layer, it.bindingKind, it.target) },
            hop =
                c.hop?.let { h ->
                    g?.target?.let { HopColumn(it, h.erTarget, h.diagnostic, h.message) }
                },
            erTarget = c.erTarget,
            landingFrom = c.landingFrom?.let { cn.concepts[it]?.ref ?: "cn.${it.name}" },
            openTier = c.openTier,
            openEntries = c.openEntries.map { it.first },
            relations = c.relations.map { r -> "${r.typeName} → ${r.to?.let { "cn.${it.name}" } ?: r.toName ?: "?"}" },
            danglingGroundings =
                cn.bindings.values
                    .filter { it.concept == c.qname }
                    .flatMap { b -> b.dangling.map { DanglingRef(b.qname.name, it) } },
            redundantTerms = redundantTerms(cn, c),
            phaseAsDefault = phaseAsDefault(cn, c),
        )
    }

    private fun terms(c: Concept): List<LexiconLink> = c.lexiconLinks.filter { it.entryKind == "term" }

    private fun fold(form: String): String = TermNormalizer.fold(form)

    /** The elements that lift to [c] — the reverse index's: its grounding target and its E-R landing. */
    private fun liftingElements(c: Concept): List<String> {
        val g = c.grounding ?: return emptyList()
        if (c.stereotype == "phase" || c.landingFrom != null || g.layer == GroundingLayer.DB) return emptyList()
        return listOfNotNull(g.target, c.erTarget).distinct()
    }

    private fun redundantTerms(
        cn: CnSchema,
        c: Concept,
    ): List<RedundantTerm> {
        val elements = liftingElements(c)
        if (elements.isEmpty()) return emptyList()
        val out = mutableListOf<RedundantTerm>()
        for (t in terms(c)) {
            for (form in t.forms.distinctBy(::fold)) {
                for (e in elements) {
                    for (u in cn.elementTerms[e].orEmpty()) {
                        if (u.entryKind != "term" || u.locale != t.locale) continue
                        if (u.forms.none { fold(it) == fold(form) }) continue
                        out += RedundantTerm(form, t.locale, t.name, e, u.name)
                    }
                }
            }
        }
        return out
    }

    private fun phaseAsDefault(
        cn: CnSchema,
        c: Concept,
    ): List<PhaseAsDefault> {
        if (c.stereotype != "phase") return emptyList()
        val chain = mutableListOf<Concept>()
        var at = c.of?.let { cn.concepts[it] }
        while (at != null && at.qname != c.qname && chain.none { it.qname == at!!.qname }) {
            chain += at
            at = at.of?.let { cn.concepts[it] }
        }
        return terms(c).filter { it.default == true }.mapNotNull { t ->
            t.forms.firstNotNullOfOrNull { form ->
                chain
                    .firstOrNull { b ->
                        terms(b).any { u -> u.locale == t.locale && u.forms.any { fold(it) == fold(form) } }
                    }?.let { PhaseAsDefault(t.name, form, it.ref) }
            }
        }
    }
}
