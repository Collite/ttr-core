// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.semantics.cn

import org.tatrman.ttr.parser.diagnostics.DiagnosticCode
import org.tatrman.ttr.parser.diagnostics.DiagnosticSeverity
import org.tatrman.ttr.parser.model.BindingPropertyBlock
import org.tatrman.ttr.parser.model.ConceptDef
import org.tatrman.ttr.parser.model.ConceptProperty
import org.tatrman.ttr.parser.model.CubeletDef
import org.tatrman.ttr.parser.model.CubeletMeasure
import org.tatrman.ttr.parser.model.Definition
import org.tatrman.ttr.parser.model.EntityDef
import org.tatrman.ttr.parser.model.Er2DbEntityDef
import org.tatrman.ttr.parser.model.PropertyValue
import org.tatrman.ttr.parser.model.Reference
import org.tatrman.ttr.parser.model.RelationDef
import org.tatrman.ttr.parser.model.SourceLocation
import org.tatrman.ttr.parser.model.TargetObjectValue
import org.tatrman.ttr.parser.model.TargetValue
import org.tatrman.ttr.semantics.ResolutionResult
import org.tatrman.ttr.semantics.Resolver
import org.tatrman.ttr.semantics.SemanticDocument
import org.tatrman.ttr.semantics.SymbolEntry
import org.tatrman.ttr.semantics.buildCanonicalKey
import org.tatrman.ttr.semantics.entryOf
import org.tatrman.ttr.semantics.idsOf
import org.tatrman.ttr.semantics.kindOf
import java.util.Collections
import java.util.IdentityHashMap

// 0.17 — the model-level `cn/…` checks (CN-P2 S2.3/S2.4; cn contracts §7, §1.1–§1.6). One project pass over the
// desugared model ([analyzeGroundings]): every concept with what it states (a cn def's properties, then those of
// each `concept { }` block that names it), every grounding with its concept and target resolved. A reference that
// does not resolve is `ttr/unresolved-reference`'s to report — a check here skips it — except where the contracts
// give the case a slug of its own (an undefined relation type, a closure attribute the closure entity lacks).
// Mirrors the TS twin (`packages/semantics/src/cn/validate.ts`), the portable slugs: the lexicon pair
// (`cn/duplicate-default-sense`, `cn/phase-as-default`) is TS-only; the definition-level slugs are S2.6's.

/** A property a concept states, with the document it is written in (its references resolve there). */
data class StatedProperty(
    val property: ConceptProperty,
    val doc: SemanticDocument,
)

/** A concept as the project states it — its cn def and every `concept { }` block that names it. */
class CnConcept(
    val key: String,
    val name: String,
    /** The cn def's; for a concept only blocks declare, the first block's. */
    val stereotype: String,
    /** Where it is declared: the cn def, else the declaring slot of its first block. */
    val uri: String,
    val source: SourceLocation,
    val def: ConceptDef?,
    /** The blocks that name it, in document and source order. */
    val sugars: MutableList<Pair<SugarConcept, SemanticDocument>> = mutableListOf(),
    /** The def's properties, then each block's past its declaring slot — the first of a key is the one read. */
    val properties: MutableList<StatedProperty> = mutableListOf(),
)

data class CnValidation(
    /** Every concept by key, the project's cn defs first, then the ones only blocks declare. */
    val concepts: Map<String, CnConcept>,
    val groundings: GroundingAnalysis,
    /** Every model-level `cn/…` diagnostic (and the cn `ttr/required-property-missing`), in document order. */
    val diagnostics: List<CnDiagnostic>,
)

/**
 * The model-level cn checks over a project. [resolver] must be built over the symbol table of exactly these
 * documents (the desugared concepts resolve through it).
 */
fun validateCn(
    docs: List<SemanticDocument>,
    resolver: Resolver,
): CnValidation {
    val v = CnValidator(docs, resolver)
    val diagnostics = v.run()
    return CnValidation(v.concepts, v.groundings, diagnostics)
}

/**
 * `cn/cnc-deprecated` (cn contracts §1.6) — `model cnc` and an entity's `roles:` parse through the window, warned.
 * Per document; never run it on the stock vocabulary. [directiveSource] is the `model` directive's span.
 */
fun cncDeprecations(
    doc: SemanticDocument,
    directiveSource: SourceLocation? = doc.modelDirectiveSource,
): List<CnDiagnostic> {
    val out = mutableListOf<CnDiagnostic>()
    if (doc.schemaCode == "cnc") {
        out +=
            CnDiagnostic(
                DiagnosticCode.CnCncDeprecated,
                "'model cnc' is deprecated — the cnc roles are superseded by the conceptual model ('model cn'); " +
                    "nothing to migrate to (CN-D18)",
                DiagnosticSeverity.Warning,
                doc.uri,
                directiveSource ?: SourceLocation.UNKNOWN,
            )
    }
    for (def in doc.definitions) {
        if (def !is EntityDef || def.roles.isEmpty()) continue
        out +=
            CnDiagnostic(
                DiagnosticCode.CnCncDeprecated,
                "'roles:' on entity '${def.name}' is deprecated — the cnc roles are superseded by the conceptual " +
                    "model ('model cn'); nothing to migrate to (CN-D18)",
                DiagnosticSeverity.Warning,
                doc.uri,
                def.source,
            )
    }
    return out
}

// ---------------------------------------------------------------------------------------------------------------
// Small readers
// ---------------------------------------------------------------------------------------------------------------

/** The first property of type [T] a concept states. */
private inline fun <reified T : ConceptProperty> CnConcept.stated(): StatedProperty? =
    properties.firstOrNull { it.property is T }

/** The first free-form entry a concept states under [key]. */
private fun CnConcept.statedEntry(key: String): StatedProperty? =
    properties.firstOrNull { it.property is ConceptProperty.Entry && it.property.key == key }

private fun StatedProperty?.isTrue(): Boolean =
    ((this?.property as? ConceptProperty.Entry)?.value as? PropertyValue.BoolValue)?.raw == true

private fun shortName(qname: String): String = qname.substringAfterLast('.')

/** The message for a `concept { }` on a cubelet's inline measure (review-119 M-11; TS `inlineMeasureSugar`). */
internal fun inlineMeasureSugar(
    measure: String,
    cubelet: String,
): String =
    "a 'concept { }' on the measure '$measure' written inline in cubelet '$cubelet' grounds nothing — declare it " +
        "with 'def measure $measure' and list it in 'measures:'"

/** The model of a symbol (`md`, `er`, `db`, …) — its key's first segment past the package. */
private fun modelOf(sym: SymbolEntry): String {
    val rest = if (sym.packageName.isNotEmpty()) sym.qname.substring(sym.packageName.length + 1) else sym.qname
    return rest.substringBefore('.')
}

private fun describe(sym: SymbolEntry): String =
    when (sym.kind) {
        "entity" -> "an er entity"
        "attribute" -> if (modelOf(sym) == "md") "an md attribute" else "an er attribute"
        "dimension" -> "a dimension"
        "mdDomain" -> "a domain"
        "measure" -> "a measure"
        "cubelet" -> "a cubelet"
        "relation" -> "a relation"
        "concept" -> "a ${sym.stereotype ?: "concept"}"
        else -> "${if (modelOf(sym) == "db") "a db " else "a "}${sym.kind}"
    }

private fun article(stereotype: String): String =
    if (stereotype.firstOrNull() in setOf('a', 'e', 'i', 'o', 'u')) "an $stereotype" else "a $stereotype"

/** The stereotype a binding kind grounds — null for `cn2db_concept`, which grounds any. */
private fun stereotypeOfBinding(bindingKind: String): String? =
    if (bindingKind == "cn2db_concept") null else bindingKind.substringAfter('_')

/** What a binding kind may target, as a predicate and as words (design `09` §2, cn contracts §1.2). */
private class TargetRule(
    val what: String,
    val ok: (SymbolEntry, Set<String>) -> Boolean,
)

private val TARGETS: Map<String, TargetRule> =
    mapOf(
        "cn2md_kind" to TargetRule("a dimension or a domain") { s, _ -> s.kind == "dimension" || s.kind == "mdDomain" },
        "cn2md_role" to TargetRule("a dimension or a domain") { s, _ -> s.kind == "dimension" || s.kind == "mdDomain" },
        "cn2md_quality" to
            TargetRule("a measure or an md attribute") { s, _ ->
                s.kind == "measure" || (s.kind == "attribute" && modelOf(s) == "md")
            },
        "cn2md_event" to TargetRule("a cubelet") { s, _ -> s.kind == "cubelet" },
        "cn2er_kind" to
            TargetRule("an er entity, or an er attribute (a value kind)") { s, _ ->
                s.kind == "entity" || (s.kind == "attribute" && modelOf(s) == "er")
            },
        "cn2er_role" to TargetRule("an er entity") { s, _ -> s.kind == "entity" },
        "cn2er_event" to TargetRule("an er entity") { s, _ -> s.kind == "entity" },
        "cn2er_quality" to TargetRule("an er attribute") { s, _ -> s.kind == "attribute" && modelOf(s) == "er" },
        "cn2er_phase" to
            TargetRule("an er entity stacked on a query (AM-2)") { s, stacked ->
                s.kind == "entity" &&
                    s.qname in stacked
            },
        "cn2db_concept" to TargetRule("a db object") { s, _ -> modelOf(s) == "db" },
    )

private fun isQueryTarget(t: TargetValue?): Boolean = t is TargetObjectValue && t.obj.entries.containsKey("query")

// ---------------------------------------------------------------------------------------------------------------
// The analysis
// ---------------------------------------------------------------------------------------------------------------

private class CnValidator(
    docs: List<SemanticDocument>,
    private val resolver: Resolver,
) {
    private val docs = docs.sortedBy { it.uri }
    private val docByUri = this.docs.associateBy { it.uri }
    private val out = mutableListOf<CnDiagnostic>()
    val concepts = LinkedHashMap<String, CnConcept>()
    val groundings = analyzeGroundings(this.docs, resolver)
    private val stacked: Set<String>

    init {
        out += groundings.diagnostics
        collectConcepts()
        stacked = collectStacked()
    }

    fun run(): List<CnDiagnostic> {
        checkGroundingTargets()
        for (c in concepts.values) {
            checkShape(c)
            checkInvolves(c)
            checkAntirigid(c)
            checkRelationTypes(c)
        }
        checkThingsInTime()
        checkHierarchies()
        checkParticipantPaths()
        return out.sortedWith(compareBy(BY_PLACE) { it.uri to it.source })
    }

    private fun report(
        code: DiagnosticCode,
        severity: DiagnosticSeverity,
        uri: String,
        source: SourceLocation,
        message: String,
    ) {
        out += CnDiagnostic(code, message, severity, uri, source)
    }

    // --- resolution --------------------------------------------------------------------------------------------

    /** A reference in a cn slot — among concepts only. */
    private fun concept(
        path: String,
        parts: List<String>,
        doc: SemanticDocument,
    ): SymbolEntry? {
        val r = resolver.resolveReference(Resolver.Ref(path, parts, cnContext = true), contextOf(doc))
        return (r as? ResolutionResult.Resolved)?.symbol?.takeIf { it.kind == "concept" }
    }

    private fun concept(
        ref: Reference,
        doc: SemanticDocument,
    ) = concept(ref.path, ref.parts, doc)

    private fun concept(
        id: PropertyValue.IdValue,
        doc: SemanticDocument,
    ) = concept(id.ref.path, id.parts, doc)

    /** A reference to an md / er / db element. */
    private fun element(
        path: String,
        parts: List<String>,
        doc: SemanticDocument,
        enclosingQname: String? = null,
    ): SymbolEntry? {
        val r = resolver.resolveReference(Resolver.Ref(path, parts), contextOf(doc, enclosingQname))
        return (r as? ResolutionResult.Resolved)?.symbol
    }

    private fun element(
        id: PropertyValue.IdValue,
        doc: SemanticDocument,
        enclosingQname: String? = null,
    ) = element(id.ref.path, id.parts, doc, enclosingQname)

    /** The top-level def a symbol stands for. */
    private fun defOf(sym: SymbolEntry): Definition? =
        docByUri[sym.documentUri]?.definitions?.firstOrNull {
            kindOf(it) == sym.kind && it.name == sym.name && it.source.offsetStart == sym.source.offsetStart
        }

    // --- the model ---------------------------------------------------------------------------------------------

    private fun collectConcepts() {
        for (doc in docs) {
            for (def in doc.definitions) {
                if (def !is ConceptDef) continue
                val key = conceptKeyOf(doc.packageName, def.name)
                if (concepts.containsKey(key)) continue // a second cn def of one name is `ttr/duplicate-definition`
                concepts[key] =
                    CnConcept(
                        key = key,
                        name = def.name,
                        stereotype = def.stereotype,
                        uri = doc.uri,
                        source = def.source,
                        def = def,
                        properties = def.body.properties.mapTo(mutableListOf()) { StatedProperty(it, doc) },
                    )
            }
        }
        for (doc in docs) {
            for (sugar in desugarConcepts(doc.definitions, doc.packageName).concepts) {
                val key = conceptKeyOf(doc.packageName, sugar.name)
                val c =
                    concepts.getOrPut(key) {
                        CnConcept(key, sugar.name, sugar.stereotype, doc.uri, sugar.declaring.source, null)
                    }
                c.sugars += sugar to doc
                for (p in sugar.sugar.body.properties) if (p !== sugar.declaring) c.properties += StatedProperty(p, doc)
            }
        }
    }

    /**
     * The entities stacked on a query: an entity whose inline `binding:` targets `{ query: … }`, or one a
     * `def er2db_entity` with such a target binds.
     */
    private fun collectStacked(): Set<String> {
        val out = mutableSetOf<String>()
        for (doc in docs) {
            for (def in doc.definitions) {
                if (def is EntityDef && isQueryTarget((def.binding as? BindingPropertyBlock)?.target)) {
                    out += buildCanonicalKey(doc.packageName, "", "entity", listOf(def.name))
                } else if (def is Er2DbEntityDef && isQueryTarget(def.target)) {
                    val entity = def.entity ?: continue
                    val sym = element(entity.path, entity.parts, doc)
                    if (sym?.kind == "entity") out += sym.qname
                }
            }
        }
        return out
    }

    private fun groundingsOf(c: CnConcept): List<PlacedGrounding> = groundings.byConcept[c.key] ?: emptyList()

    // --- cn/grounding-target-kind · cn/db-grounding ------------------------------------------------------------

    private fun checkGroundingTargets() {
        // A `concept { }` on a measure written inline in a cubelet's `measures:` grounds nothing — the inline measure
        // is no symbol a grounding could name (review-119 M-11): reported, never dropped silently.
        for (doc in docs) {
            for (def in doc.definitions) {
                if (def !is CubeletDef) continue
                for (m in def.measures) {
                    val inline = m as? CubeletMeasure.Inline ?: continue
                    val sugar = inline.measure.concept ?: continue
                    report(
                        DiagnosticCode.CnGroundingTargetKind,
                        DiagnosticSeverity.Error,
                        doc.uri,
                        sugar.source,
                        inlineMeasureSugar(inline.measure.name, def.name),
                    )
                }
            }
        }
        for (placed in groundings.groundings) {
            val g = placed.grounding
            val name = g.conceptName ?: placed.conceptKey?.let(::shortName) ?: g.conceptRef?.path ?: g.name
            val bindingKind = g.bindingKind
            if (bindingKind == null) {
                report(
                    DiagnosticCode.CnGroundingTargetKind,
                    DiagnosticSeverity.Error,
                    placed.uri,
                    g.target?.source ?: g.source,
                    noBindingKind(g, name),
                )
                continue
            }
            val expected = stereotypeOfBinding(bindingKind)
            if (g.origin == GroundingOrigin.Binding &&
                expected != null &&
                g.conceptRef != null &&
                placed.conceptKey != null
            ) {
                val st = concepts[placed.conceptKey]?.stereotype ?: resolver.getSymbol(placed.conceptKey)?.stereotype
                if (st != null && st != expected && !(st == "subkind" && expected in setOf("kind", "phase"))) {
                    report(
                        DiagnosticCode.CnGroundingTargetKind,
                        DiagnosticSeverity.Error,
                        placed.uri,
                        g.conceptRef.source,
                        "'$bindingKind' grounds ${article(expected)}, but '$name' is ${article(st)}",
                    )
                }
            }
            val sym = placed.targetSymbol
            if (sym != null) {
                val t = TARGETS.getValue(bindingKind)
                if (!t.ok(sym, stacked) &&
                    !(bindingKind == "cn2er_phase" && cEpisode(placed.conceptKey) && sym.kind == "entity")
                ) {
                    val isWhat =
                        if (bindingKind == "cn2er_phase" &&
                            sym.kind == "entity"
                        ) {
                            "an entity no query backs"
                        } else {
                            describe(sym)
                        }
                    report(
                        DiagnosticCode.CnGroundingTargetKind,
                        DiagnosticSeverity.Error,
                        placed.uri,
                        g.target?.source ?: g.source,
                        "'$bindingKind' grounds '$name' to ${t.what} — '${sym.name}' is $isWhat",
                    )
                }
            }
            if (bindingKind == "cn2db_concept") {
                report(
                    DiagnosticCode.CnDbGrounding,
                    DiagnosticSeverity.Information,
                    placed.uri,
                    g.source,
                    "'$name' grounds to db — documentation only: no consumer reaches a db grounding (ground it to md or er)",
                )
            }
        }
    }

    private fun cEpisode(key: String?): Boolean =
        key?.let { concepts[it] }?.let { c ->
            c.stereotype == "phase" &&
                (
                    c.properties.any { it.property is ConceptProperty.Period } ||
                        groundingsOf(c).any { it.grounding.properties.any { p -> p is ConceptProperty.Period } }
                )
        } ==
            true

    private fun noBindingKind(
        g: CnGrounding,
        name: String,
    ): String =
        when {
            g.stereotype == "relation_type" ->
                "relation type '$name' is open tier — it is never grounded (cn contracts §1.3)"
            g.target != null && layerOf(g.target) == null ->
                "'grounds: ${g.target.path}' names no md, er or db element — write the target with its model " +
                    "(`md.…`, `er.entity.…`, `db.…`)"
            else -> "phase '$name' grounds only to an er entity stacked on a query (AM-2), not to md or db"
        }

    // --- the def-shape slugs -----------------------------------------------------------------------------------

    private fun checkShape(c: CnConcept) {
        val grounded = groundingsOf(c).isNotEmpty()
        val definition = c.stated<ConceptProperty.DefinitionBlock>()
        val abstract = c.statedEntry("abstract").isTrue()
        when (c.stereotype) {
            "role" ->
                if (c.properties.none { it.property is ConceptProperty.Ref && it.property.key == "event" }) {
                    report(
                        DiagnosticCode.CnRoleNoEvent,
                        DiagnosticSeverity.Error,
                        c.uri,
                        c.source,
                        "role '${c.name}' names no defining event — add 'event: <Event>' (CN-D6)",
                    )
                }
            "quality", "phase", "subkind" ->
                if (c.statedEntry("of") == null) {
                    report(
                        DiagnosticCode.RequiredPropertyMissing,
                        DiagnosticSeverity.Error,
                        c.uri,
                        c.source,
                        "${c.stereotype} '${c.name}' names no bearer — 'of:' is required",
                    )
                }
        }
        if (c.stereotype == "phase") {
            val first = groundingsOf(c).firstOrNull()
            if (definition != null && first != null) {
                report(
                    DiagnosticCode.CnPhaseDoubleDefinition,
                    DiagnosticSeverity.Error,
                    first.uri,
                    first.grounding.source,
                    "phase '${c.name}' has a 'definition:' and a grounding — a phase is defined, or grounded to a " +
                        "stacked entity, never both (AM-2)",
                )
            }
            if (definition == null &&
                !grounded &&
                !abstract &&
                c.properties.none { it.property is ConceptProperty.From }
            ) {
                report(
                    DiagnosticCode.CnPhaseNoDefinition,
                    DiagnosticSeverity.Information,
                    c.uri,
                    c.source,
                    "phase '${c.name}' has neither a 'definition:' nor a grounding — no consumer can tell its " +
                        "members (mark a superconcept 'abstract: true')",
                )
            }
        }
        if (c.stereotype == "event" && c.stated<ConceptProperty.Period>() == null) {
            report(
                DiagnosticCode.CnEventNoPeriod,
                DiagnosticSeverity.Information,
                c.uri,
                c.source,
                "event '${c.name}' has no 'period:' — no consumer can place it in time",
            )
        }
        val exempt =
            c.stereotype == "relation_type" ||
                c.stereotype in setOf("phase", "subkind") ||
                c.properties.any { it.property is ConceptProperty.From } ||
                definition != null ||
                c.stated<ConceptProperty.MembersBlock>() != null ||
                abstract
        if (!grounded && !exempt) {
            report(
                DiagnosticCode.CnUngrounded,
                DiagnosticSeverity.Information,
                c.uri,
                c.source,
                "${c.stereotype} '${c.name}' is not grounded yet — no consumer reaches it (P-1, eventually grounded)",
            )
        }
    }

    // --- cn/involves-unlabelled-duplicate ----------------------------------------------------------------------

    private fun checkInvolves(c: CnConcept) {
        if (c.stereotype !in setOf("event", "kind", "subkind", "role")) return
        val involves = c.stated<ConceptProperty.Involves>() ?: return
        val seen = mutableSetOf<String>()
        for (p in (involves.property as ConceptProperty.Involves).participants) {
            val label =
                p.label ?: p.ref.parts
                    .last()
                    .lowercase()
            if (seen.add(label)) continue
            report(
                DiagnosticCode.CnInvolvesUnlabelledDuplicate,
                DiagnosticSeverity.Error,
                involves.doc.uri,
                p.source,
                "'${c.name}' involves a second participant under the label '$label' — label each one " +
                    "('${p.ref.path} as <label>')",
            )
        }
    }

    // --- cn/antirigid-supertype --------------------------------------------------------------------------------

    private fun checkAntirigid(c: CnConcept) {
        if (c.stereotype != "kind") return
        val isA = c.statedEntry("is_a") ?: return
        val seen = mutableSetOf(c.key)
        // Breadth-first up the chain; `via` keeps the direct supertype each one was reached through.
        val queue = ArrayDeque<Pair<String, String>>()

        fun push(
            s: StatedProperty,
            via: String?,
        ) {
            for (id in idsOf((s.property as ConceptProperty.Entry).value)) {
                val sym = concept(id, s.doc) ?: continue
                if (seen.add(sym.qname)) queue.addLast(sym.qname to (via ?: sym.name))
            }
        }
        push(isA, null)
        while (queue.isNotEmpty()) {
            val (key, via) = queue.removeFirst()
            val sup = concepts[key] ?: continue
            if (sup.stereotype == "role" || sup.stereotype == "phase") {
                val through = if (via != sup.name) " (through '$via')" else ""
                report(
                    DiagnosticCode.CnAntirigidSupertype,
                    DiagnosticSeverity.Warning,
                    isA.doc.uri,
                    isA.property.source,
                    "kind '${c.name}' specializes ${sup.stereotype} '${sup.name}'$through — a kind is rigid and " +
                        "cannot be a subtype of an anti-rigid ${sup.stereotype} (OntoUML); make '${c.name}' " +
                        article(sup.stereotype),
                )
                return
            }
            sup.statedEntry("is_a")?.let { push(it, via) }
        }
    }

    // --- cn/unknown-relation-type ------------------------------------------------------------------------------

    private fun checkRelationTypes(c: CnConcept) {
        val relations = c.statedEntry("relations") ?: return
        val list = (relations.property as ConceptProperty.Entry).value as? PropertyValue.ListValue ?: return
        for (item in list.items) {
            for (id in idsOf(entryOf(item, "type"))) {
                val sym = concept(id, relations.doc)
                if (sym?.stereotype == "relation_type") continue
                report(
                    DiagnosticCode.CnUnknownRelationType,
                    DiagnosticSeverity.Error,
                    relations.doc.uri,
                    id.source,
                    if (sym != null) {
                        "'${id.ref.path}' is ${article(sym.stereotype ?: "concept")}, not a relation type — an " +
                            "open-tier edge's 'type:' names a 'def relation_type'"
                    } else {
                        "'${id.ref.path}' is no relation type — declare 'def relation_type ${id.parts.last()}'"
                    },
                )
            }
        }
    }

    // --- cn/hierarchy-shape ------------------------------------------------------------------------------------

    private fun checkHierarchies() {
        val seen = Collections.newSetFromMap(IdentityHashMap<ConceptProperty, Boolean>())
        val all = mutableListOf<StatedProperty>()
        for (placed in groundings.groundings) {
            val doc = docByUri[placed.uri] ?: continue
            for (p in placed.grounding.properties) all += StatedProperty(p, doc)
        }
        for (c in concepts.values) all += c.properties
        for (s in all) {
            val p = s.property
            if (p !is ConceptProperty.Entry || p.key != "hierarchy" || !seen.add(p)) continue
            checkHierarchy(p, s.doc)
        }
    }

    private fun checkHierarchy(
        h: ConceptProperty.Entry,
        doc: SemanticDocument,
    ) {
        val closureId = idsOf(entryOf(h.value, "closure")).firstOrNull()
        if (closureId == null) {
            report(
                DiagnosticCode.CnHierarchyShape,
                DiagnosticSeverity.Error,
                doc.uri,
                h.source,
                "'hierarchy:' names no 'closure:' entity",
            )
            return
        }
        val closure = element(closureId, doc) ?: return // `ttr/unresolved-reference`
        if (closure.kind != "entity") {
            report(
                DiagnosticCode.CnHierarchyShape,
                DiagnosticSeverity.Error,
                doc.uri,
                closureId.source,
                "the hierarchy closure '${closureId.ref.path}' is ${describe(closure)} — it must be an er entity",
            )
            return
        }
        val def = defOf(closure) as? EntityDef ?: return
        val attributes = def.attributes.associateBy { it.name }
        for (key in listOf("ancestor", "descendant")) {
            val id = idsOf(entryOf(h.value, key)).firstOrNull()
            if (id == null) {
                report(
                    DiagnosticCode.CnHierarchyShape,
                    DiagnosticSeverity.Error,
                    doc.uri,
                    h.source,
                    "'hierarchy:' names no '$key:' attribute of '${closure.name}'",
                )
            } else if (id.ref.path !in attributes) {
                report(
                    DiagnosticCode.CnHierarchyShape,
                    DiagnosticSeverity.Error,
                    doc.uri,
                    id.source,
                    "the hierarchy closure '${closure.name}' has no attribute '${id.ref.path}' (its '$key:')",
                )
            }
        }
        if (attributes.values.none { it.isKey }) {
            report(
                DiagnosticCode.CnHierarchyShape,
                DiagnosticSeverity.Error,
                doc.uri,
                closureId.source,
                "the hierarchy closure '${closure.name}' has no key attribute",
            )
        }
    }

    // --- cn/participant-path-broken ----------------------------------------------------------------------------

    /** A relation's two entities, resolved in the relation's own document. */
    private fun endsOf(rel: SymbolEntry): Pair<String, String>? {
        val def = defOf(rel) as? RelationDef ?: return null
        val doc = docByUri[rel.documentUri] ?: return null

        fun end(v: PropertyValue?) = idsOf(v).firstOrNull()?.let { element(it, doc)?.qname }
        val from = end(def.from) ?: return null
        val to = end(def.to) ?: return null
        return from to to
    }

    /** The entity a participant concept's own grounding lands on directly in er; null when it has none. */
    private fun directErEntity(conceptKey: String): String? {
        val g = groundings.byConcept[conceptKey]?.firstOrNull() ?: return null
        val t = g.targetSymbol ?: return null
        if (g.grounding.bindingKind?.startsWith("cn2er_") != true) return null
        return when (t.kind) {
            "entity" -> t.qname
            "attribute" -> t.parent
            else -> null
        }
    }

    private fun checkParticipantPaths() {
        for (placed in groundings.groundings) {
            val g = placed.grounding
            val target = placed.targetSymbol
            if (g.bindingKind !in setOf("cn2er_event", "cn2er_kind", "cn2er_role", "cn2er_phase") ||
                target?.kind != "entity"
            ) {
                continue
            }
            val participants =
                g.properties.firstOrNull {
                    it is ConceptProperty.Entry && it.key == "participants"
                } as? ConceptProperty.Entry
            val entries = participants?.value as? PropertyValue.ObjectValue ?: continue
            val doc = docByUri[placed.uri] ?: continue
            val event = placed.conceptKey?.let { concepts[it] }
            val involves = event?.stated<ConceptProperty.Involves>()
            val enclosing = buildCanonicalKey(doc.packageName, "", "cn2er_event", listOf(g.name))
            for ((key, value) in entries.entries) {
                val path = idsOf(value)
                val rels = path.map { element(it, doc, enclosing) }
                if (path.isEmpty() || rels.any { it == null }) continue // `ttr/unresolved-reference`
                var at = target.qname
                var broken = false
                for (i in path.indices) {
                    if (broken) break
                    val rel = rels[i]!!
                    val ends = if (rel.kind == "relation") endsOf(rel) else null
                    if (rel.kind != "relation") {
                        report(
                            DiagnosticCode.CnParticipantPathBroken,
                            DiagnosticSeverity.Error,
                            placed.uri,
                            path[i].source,
                            "'${path[i].ref.path}' in the path of '$key' is ${describe(rel)}, not a relation",
                        )
                        broken = true
                    } else if (ends == null) {
                        // a relation whose ends do not resolve: `ttr/unresolved-reference` on the relation
                        broken = true
                    } else if (ends.first != at && ends.second != at) {
                        report(
                            DiagnosticCode.CnParticipantPathBroken,
                            DiagnosticSeverity.Error,
                            placed.uri,
                            path[i].source,
                            if (i == 0) {
                                "the path of '$key' starts at '${rel.name}', which does not touch the event's entity " +
                                    "'${shortName(at)}'"
                            } else {
                                "the path of '$key' breaks at '${rel.name}' — it does not continue from '${shortName(
                                    at,
                                )}'"
                            },
                        )
                        broken = true
                    } else {
                        at = if (ends.first == at) ends.second else ends.first
                    }
                }
                if (broken || involves == null) continue
                val participant =
                    (involves.property as ConceptProperty.Involves).participants.firstOrNull {
                        (
                            it.label ?: it.ref.parts
                                .last()
                                .lowercase()
                        ) == key
                    }
                val concept = participant?.let { concept(it.ref, involves.doc) } ?: continue
                val lands = directErEntity(concept.qname)
                if (lands != null && lands != at) {
                    report(
                        DiagnosticCode.CnParticipantPathBroken,
                        DiagnosticSeverity.Error,
                        placed.uri,
                        path.last().source,
                        "the path of '$key' ends on '${shortName(
                            at,
                        )}', but '${concept.name}' is grounded to '${shortName(lands)}'",
                    )
                }
            }
        }
    }

    /** CN-P8 AM-45…49: portable checks over the authored, reconciled CN graph. */
    private fun checkThingsInTime() {
        fun prop(
            c: CnConcept,
            key: String,
        ): StatedProperty? {
            c.properties.firstOrNull { it.property.key == key }?.let { return it }
            val g = groundingsOf(c).firstOrNull() ?: return null
            val doc = docByUri[g.uri] ?: return null
            val properties = g.grounding.properties
            properties.firstOrNull { it.key == key }?.let { return StatedProperty(it, doc) }
            val bounds =
                (
                    properties.firstOrNull {
                        it.key == "bounds"
                    } as? ConceptProperty.Entry
                )?.value as? PropertyValue.ObjectValue
            val id =
                (bounds?.entries?.get(key) as? PropertyValue.IdValue)
                    ?: if (key == "from" &&
                        c.stereotype == "quality"
                    ) {
                        (
                            properties.firstOrNull {
                                it.key == "via"
                            } as? ConceptProperty.Entry
                        )?.value as? PropertyValue.IdValue
                    } else {
                        null
                    }
            return id?.let {
                StatedProperty(
                    if (key ==
                        "until"
                    ) {
                        ConceptProperty.Until(key, it.ref, it.source)
                    } else {
                        ConceptProperty.From(key, it.ref, it.source)
                    },
                    doc,
                )
            }
        }

        fun named(
            c: CnConcept,
            key: String,
        ): CnConcept? {
            val s = prop(c, key) ?: return null
            val ref =
                when (val p = s.property) {
                    is ConceptProperty.Entry -> (p.value as? PropertyValue.IdValue)?.ref
                    is ConceptProperty.From -> p.ref
                    is ConceptProperty.Until -> p.ref
                    is ConceptProperty.Per -> p.ref
                    is ConceptProperty.ValueSpace -> p.ref
                    is ConceptProperty.Ref -> p.ref
                    else -> null
                } ?: return null
            return concept(ref, s.doc)?.qname?.let { concepts[it] }
        }

        fun chain(c: CnConcept): Set<String> {
            val seen = mutableSetOf<String>()
            var at: CnConcept? = c
            while (at != null && seen.add(at.key)) at = named(at, "of") ?: named(at, "is_a")
            return seen
        }

        fun participates(
            c: CnConcept,
            bearer: CnConcept,
        ): Boolean =
            chain(c).any { key ->
                val owner = concepts.getValue(key)
                val s = prop(owner, "involves") ?: return@any false
                val p = s.property as? ConceptProperty.Involves ?: return@any false
                p.participants.any { pt ->
                    concept(pt.ref, s.doc)?.qname?.let { concepts[it] }?.let { got ->
                        bearer.key in chain(got) || got.key in chain(bearer)
                    } ==
                        true
                }
            }

        fun emit(
            c: CnConcept,
            code: DiagnosticCode,
            message: String,
            warning: Boolean = false,
            key: String? = null,
        ) {
            val s = key?.let { prop(c, it) }
            report(
                code,
                if (warning) DiagnosticSeverity.Warning else DiagnosticSeverity.Error,
                s?.doc?.uri ?: c.uri,
                s?.property?.source ?: c.source,
                message,
            )
        }

        fun temporal(
            c: CnConcept,
            visiting: MutableSet<String>,
        ): Boolean {
            if (!visiting.add(c.key)) return false
            if (c.stereotype == "phase" || prop(c, "valued_state").isTrue()) return true
            val block =
                (prop(c, "definition")?.property as? ConceptProperty.DefinitionBlock)?.block
                    ?: (prop(c, "members")?.property as? ConceptProperty.MembersBlock)?.block ?: return false
            val text = block.text.replace(Regex("'(''|[^'])*'|\"(\\\\.|[^\"])*\""), " ")
            if (Regex(
                    "(?i)(?<![a-z0-9_]):[a-z_][a-z0-9_]*|\\b(period|at_or_before|before|contains|ended_before|overlaps|within|parameter)\\b",
                ).containsMatchIn(text)
            ) {
                return true
            }
            return concepts.values.any { x ->
                x.key != c.key &&
                    Regex("\\b${Regex.escape(x.name)}\\b").containsMatchIn(text) &&
                    temporal(x, visiting)
            }
        }
        for (c in concepts.values) {
            if (c.stereotype == "subkind" &&
                temporal(c, mutableSetOf())
            ) {
                emit(
                    c,
                    DiagnosticCode.CnSubkindTemporalDefinition,
                    "subkind '${c.name}' has a temporal or parametric definition",
                    key =
                        if (prop(c, "definition") !=
                            null
                        ) {
                            "definition"
                        } else {
                            "members"
                        },
                )
            }
            if (c.stereotype == "kind" &&
                prop(c, "members") != null &&
                prop(c, "is_a") != null
            ) {
                emit(
                    c,
                    DiagnosticCode.CnSubkindAsKind,
                    "kind '${c.name}' with members and is_a is a subkind — use 'def subkind'",
                    true,
                )
            }
            if (c.stereotype == "role" &&
                named(c, "event")?.stereotype?.let { it != "event" } == true
            ) {
                emit(
                    c,
                    DiagnosticCode.CnRoleNoEvent,
                    "role '${c.name}' requires an event as its defining event",
                    key = "event",
                )
            }
            val bearerStereotype = named(c, "of")?.stereotype
            if (c.stereotype == "subkind" &&
                bearerStereotype != null &&
                bearerStereotype !in setOf("kind", "subkind", "role", "event")
            ) {
                emit(
                    c,
                    DiagnosticCode.CnSubkindBearer,
                    "subkind '${c.name}' requires a rigid bearer — a kind, subkind, role or event, not a $bearerStereotype",
                    key = "of",
                )
            }
            val extent = prop(c, "extent")?.property as? ConceptProperty.Extent
            if (extent !=
                null
            ) {
                emit(
                    c,
                    DiagnosticCode.CnExtentRetired,
                    "extent is retired — events are instantaneous; kinds and phases carry intervals",
                    true,
                    "extent",
                )
            }
            val period = (prop(c, "period")?.property as? ConceptProperty.Period)?.value
            if (c.stereotype == "event" &&
                (extent?.value == "durative" || period?.entries?.keys?.any { it == "start" || it == "end" } == true)
            ) {
                emit(
                    c,
                    DiagnosticCode.CnDurativeEvent,
                    "event '${c.name}' is durative — model a kind with a lifetime or a phase",
                    key =
                        if (extent !=
                            null
                        ) {
                            "extent"
                        } else {
                            "period"
                        },
                )
            }
            if (c.stereotype in setOf("kind", "role", "subkind", "phase") &&
                period != null &&
                ("at" in period.entries || "start" !in period.entries || "end" !in period.entries)
            ) {
                emit(c, DiagnosticCode.CnLifetimePeriod, "'${c.name}' needs a start/end interval", key = "period")
            }
            val from = prop(c, "from") != null
            val until = prop(c, "until") != null
            if (c.stereotype == "phase") {
                val sources =
                    listOf(
                        prop(c, "definition") != null,
                        groundingsOf(c).any { g ->
                            g.grounding.target !=
                                null ||
                                g.grounding.targetKey != null
                        },
                        from || until,
                    ).count { it }
                if (sources > 1 ||
                    from != until
                ) {
                    emit(
                        c,
                        DiagnosticCode.CnPhaseSourceConflict,
                        "phase '${c.name}' needs exactly one source: definition, grounding, or both from and until",
                    )
                }
                val bearer = named(c, "of")
                if (from && until && bearer != null) {
                    for (key in listOf("from", "until")) {
                        val event = named(c, key)
                        if (event == null ||
                            event.stereotype != "event" ||
                            !participates(event, bearer)
                        ) {
                            emit(
                                c,
                                DiagnosticCode.CnPhaseBoundsParticipant,
                                "phase '${c.name}' $key bound must be an event involving '${bearer.name}'",
                                key = key,
                            )
                        }
                    }
                }
                if (period != null && bearer != null) {
                    val target = groundingsOf(c).firstOrNull()?.targetSymbol
                    val subject = chain(bearer).firstNotNullOfOrNull { directErEntity(it) }
                    if (target != null && subject != null) {
                        val paths =
                            docs.flatMap { d ->
                                d.definitions.filterIsInstance<RelationDef>().mapNotNull { r ->
                                    element(
                                        "er.relation.${r.name}",
                                        listOf("er", "relation", r.name),
                                        d,
                                    )?.let { endsOf(it) }
                                }
                            }
                        val reaches = paths.any { (a, b) -> a == target.qname && b == subject }
                        if (!reaches) {
                            emit(
                                c,
                                DiagnosticCode.CnEpisodeSubjectMissing,
                                "episode phase '${c.name}' has no relation to its subject '${bearer.name}'",
                            )
                        }
                    }
                }
            }
            if (c.stereotype == "quality" && (from || prop(c, "per") != null)) {
                val s = prop(c, "from")
                val ref = (s?.property as? ConceptProperty.From)?.ref
                val parts = ref?.parts?.dropLast(1).orEmpty()
                val source =
                    if (parts.isNotEmpty() &&
                        s != null
                    ) {
                        concept(parts.joinToString("."), parts, s.doc)?.qname?.let { concepts[it] }
                    } else {
                        null
                    }
                val bearer = named(c, "of")
                val quality =
                    concepts.values.firstOrNull { q ->
                        q.name == ref?.parts?.last() &&
                            q.stereotype == "quality" &&
                            named(q, "of")?.key == source?.key
                    }
                val per = named(c, "per")
                if (!prop(c, "valued_state").isTrue() ||
                    source == null ||
                    source.stereotype !in setOf("kind", "subkind", "role") ||
                    bearer == null ||
                    !participates(source, bearer) ||
                    quality == null ||
                    (prop(c, "per") != null && (per == null || !participates(source, per)))
                ) {
                    emit(
                        c,
                        DiagnosticCode.CnProjectionSource,
                        "quality '${c.name}' needs a valued-state projection of a relator quality involving its bearer and per participant",
                    )
                } else {
                    // ⚑C (review-175 F6): one row per bearer — every other participant of the relator is the `per:` one.
                    fun covers(
                        got: CnConcept,
                        by: CnConcept?,
                    ) = by != null && (by.key in chain(got) || got.key in chain(by))
                    val uncovered =
                        chain(source).flatMap { key ->
                            val s = prop(concepts.getValue(key), "involves") ?: return@flatMap emptyList()
                            val p = s.property as? ConceptProperty.Involves ?: return@flatMap emptyList()
                            p.participants.mapNotNull { pt ->
                                concept(pt.ref, s.doc)
                                    ?.qname
                                    ?.let { concepts[it] }
                                    ?.takeIf { got ->
                                        !covers(got, bearer) && !covers(got, per)
                                    }?.name
                            }
                        }
                    if (uncovered.isNotEmpty()) {
                        emit(
                            c,
                            DiagnosticCode.CnProjectionSource,
                            "quality '${c.name}' projects '${source.name}', whose participant " +
                                "'${uncovered.first()}' is neither its bearer nor its per: — one '${bearer.name}' " +
                                "reads several rows; add 'per: ${uncovered.first()}'",
                        )
                    }
                }
            }
        }

        data class Partition(
            val concept: CnConcept,
            val bearer: CnConcept,
            val attribute: String,
            val value: String,
        )
        val eq = Regex("^\\s*(?:this\\.)?([A-Za-z_][A-Za-z0-9_]*)\\s*=\\s*'([^']*)'\\s*$")
        val partitions =
            concepts.values
                .mapNotNull { c ->
                    if (c.stereotype != "subkind") return@mapNotNull null
                    val text =
                        (prop(c, "definition")?.property as? ConceptProperty.DefinitionBlock)?.block?.text
                            ?: return@mapNotNull null
                    val m = eq.matchEntire(text) ?: return@mapNotNull null
                    val bearer = named(c, "of") ?: return@mapNotNull null
                    Partition(c, bearer, m.groupValues[1], m.groupValues[2])
                }.groupBy { it.bearer.key to it.attribute }
        for ((_, group) in partitions) {
            for ((_, same) in group.groupBy { it.value }) {
                if (same.size >
                    1
                ) {
                    same.drop(1).forEach {
                        emit(
                            it.concept,
                            DiagnosticCode.CnPartitionOverlap,
                            "subkind '${it.concept.name}' overlaps '${same.first().concept.name}' on '${it.attribute}'",
                            key = "definition",
                        )
                    }
                }
            }
            val first = group.first()
            val quality =
                concepts.values.firstOrNull {
                    it.name == first.attribute &&
                        named(it, "of")?.key == first.bearer.key
                }
            val space = quality?.let { named(it, "value_space") } ?: continue
            val target = groundingsOf(space).firstOrNull()?.targetSymbol ?: continue
            val values =
                docs
                    .flatMap { it.definitions.filterIsInstance<EntityDef>() }
                    .flatMap { it.attributes }
                    .firstOrNull {
                        it.source ==
                            target.source
                    }?.valueLabels
                    ?.keys
                    .orEmpty()
            val missing = values - group.map { it.value }.toSet()
            if (missing.isNotEmpty()) {
                emit(
                    first.concept,
                    DiagnosticCode.CnPartitionIncomplete,
                    "subkind partition on '${first.attribute}' misses ${missing.sorted().joinToString(", ")}",
                    true,
                )
            }
        }
    }
}
