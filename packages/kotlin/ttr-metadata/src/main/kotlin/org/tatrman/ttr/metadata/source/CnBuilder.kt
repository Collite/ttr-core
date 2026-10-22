// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.metadata.source

import org.tatrman.ttr.metadata.model.Basis
import org.tatrman.ttr.metadata.model.Binding
import org.tatrman.ttr.metadata.model.CnHierarchy
import org.tatrman.ttr.metadata.model.CnParticipant
import org.tatrman.ttr.metadata.model.CnPeriod
import org.tatrman.ttr.metadata.model.CnRelation
import org.tatrman.ttr.metadata.model.CnRelationType
import org.tatrman.ttr.metadata.model.CnSchema
import org.tatrman.ttr.metadata.model.Concept
import org.tatrman.ttr.metadata.model.EndState
import org.tatrman.ttr.metadata.model.Extent
import org.tatrman.ttr.metadata.model.Grounding
import org.tatrman.ttr.metadata.model.GroundingGrade
import org.tatrman.ttr.metadata.model.GroundingLayer
import org.tatrman.ttr.metadata.model.GroundingPeriod
import org.tatrman.ttr.metadata.model.HierarchyRanges
import org.tatrman.ttr.metadata.model.Imprecision
import org.tatrman.ttr.metadata.model.HopResult
import org.tatrman.ttr.metadata.model.LexiconLink
import org.tatrman.ttr.metadata.model.LexiconOrigin
import org.tatrman.ttr.metadata.model.LocalizedText
import org.tatrman.ttr.metadata.model.MdErHop
import org.tatrman.ttr.metadata.model.QualifiedName
import org.tatrman.ttr.metadata.model.SchemaCode
import org.tatrman.ttr.metadata.model.Via
import org.tatrman.ttr.parser.model.ConceptBindingDef
import org.tatrman.ttr.parser.model.ConceptDef
import org.tatrman.ttr.parser.model.ConceptProperty
import org.tatrman.ttr.parser.model.Definition
import org.tatrman.ttr.parser.model.LexiconEntryDef
import org.tatrman.ttr.parser.model.PropertyValue
import org.tatrman.ttr.semantics.ResolutionContext
import org.tatrman.ttr.semantics.ResolutionResult
import org.tatrman.ttr.semantics.Resolver
import org.tatrman.ttr.semantics.SemanticDocument
import org.tatrman.ttr.semantics.SymbolEntry
import org.tatrman.ttr.semantics.SymbolTable
import org.tatrman.ttr.semantics.buildCanonicalKey
import org.tatrman.ttr.semantics.cn.CnConcept
import org.tatrman.ttr.semantics.cn.CnDiagnostic
import org.tatrman.ttr.semantics.cn.GroundingOrigin
import org.tatrman.ttr.semantics.cn.PlacedGrounding
import org.tatrman.ttr.semantics.cn.StatedProperty
import org.tatrman.ttr.semantics.cn.desugarConcepts
import org.tatrman.ttr.semantics.cn.validateCn

/** What [CnBuilder.build] gives: the typed cn tier and the model-level `cn/…` diagnostics of the project. */
data class CnBuild(
    val schema: CnSchema,
    val diagnostics: List<CnDiagnostic>,
    val mdIssues: List<MdHopIssue> = emptyList(),
)

/**
 * Grammar 0.17 — builds the conceptual tier ([CnSchema]; CN-P2 S2.5 T2) over ONE source's files: a project, so a
 * concept's grounding, the `concept { }` blocks in its md / er hosts and the elements it targets resolve across files.
 * It reads the DESUGARED model — `ttr-semantics` `validateCn` (S2.2–S2.4: the concepts with what each states, every
 * grounding with its concept and target resolved, the first per concept the one read) — and adds what consumers read:
 * each concept's E-R landing ([Concept.erTarget], [Concept.via], [Concept.grade]; T4), its lexicon links (T6). The
 * reverse index is derived by [CnSchema] itself (T5). md groundings reach E-R through [hop]; P2's is
 * [CompositeMdErHop].
 *
 * A source with no cn content gets an empty tier and no symbol table is built.
 */
object CnBuilder {
    fun build(
        files: List<LoadedFile>,
        hop: ((List<SemanticDocument>, Resolver) -> MdErHop) = ::CompositeMdErHop,
    ): CnBuild = build(files, allMdHopIssues = false, hop = hop)

    fun build(
        files: List<LoadedFile>,
        allMdHopIssues: Boolean,
        hop: ((List<SemanticDocument>, Resolver) -> MdErHop) = ::CompositeMdErHop,
    ): CnBuild {
        if (!allMdHopIssues && files.none { hasCn(it.definitions, it.declaredPackage ?: it.computedPackage) }) {
            return CnBuild(CnSchema(), emptyList())
        }
        val (docs, resolver) = project(files)
        val locales = files.associate { it.storageFile.path to it.locale }
        return CnTierBuilder(docs, resolver, hop(docs, resolver), locales, allMdHopIssues).build()
    }

    /**
     * [files] as one `ttr-semantics` project: a document each (its effective package — declared, else the directory's,
     * as [org.tatrman.ttr.metadata.resolve.PublishedResolverAdapter] keys them) and a resolver over their symbols.
     */
    fun project(files: List<LoadedFile>): Pair<List<SemanticDocument>, Resolver> {
        val table = SymbolTable()
        val docs =
            files.map { f ->
                val pkg = f.declaredPackage ?: f.computedPackage
                table.upsertDocument(f.storageFile.path, f.definitions, f.schemaCode, f.namespace, pkg)
                SemanticDocument(f.storageFile.path, f.definitions, f.schemaCode, f.namespace, pkg, f.imports)
            }
        return docs to Resolver(table)
    }

    /** Does a file declare, ground or sugar a concept? */
    private fun hasCn(
        definitions: List<Definition>,
        packageName: String,
    ): Boolean =
        definitions.any { it is ConceptDef || it is ConceptBindingDef } ||
            desugarConcepts(definitions, packageName).concepts.isNotEmpty()
}

/** The landing of a concept on E-R (T4). */
private data class Landing(
    val erTarget: String? = null,
    val via: Via? = null,
    val grade: GroundingGrade = GroundingGrade.UNGROUNDED,
    val from: QualifiedName? = null,
    val hop: HopResult? = null,
)

/** The stock keys a concept's free-form entries may carry (CN-D7) — every other key is the open tier. */
private val STOCK_ENTRY_KEYS =
    setOf("of", "is_a", "sources", "qualities", "relations", "hierarchy", "imprecision", "valued_state", "abstract")

private class CnTierBuilder(
    docs: List<SemanticDocument>,
    private val resolver: Resolver,
    private val hop: MdErHop,
    private val locales: Map<String, String?>,
    private val allMdHopIssues: Boolean,
) {
    private val docs = docs.sortedBy { it.uri }
    private val docByUri = this.docs.associateBy { it.uri }
    private val validation = validateCn(this.docs, resolver)
    private val concepts: Map<String, CnConcept> = validation.concepts
    private val landings = mutableMapOf<String, Landing>()
    private val visiting = mutableSetOf<String>()
    private val lexicon: Map<String, List<LexiconLink>>
    private val elementTerms: Map<String, List<LexiconLink>>

    init {
        val (concepts, elements) = canonicalLexicon()
        lexicon = concepts
        elementTerms = elements
    }

    fun build(): CnBuild {
        val bindings = linkedMapOf<QualifiedName, Grounding>()
        for (placed in validation.groundings.groundings) {
            val g = grounding(placed) ?: continue
            bindings.putIfAbsent(g.qname, g)
        }
        val out = linkedMapOf<QualifiedName, Concept>()
        val relationTypes = linkedMapOf<QualifiedName, CnRelationType>()
        for (c in concepts.values) {
            if (c.stereotype == "relation_type") {
                val rt = relationType(c)
                relationTypes.putIfAbsent(rt.qname, rt)
                continue
            }
            val concept = concept(c, bindings)
            out.putIfAbsent(concept.qname, concept)
        }
        val mdIssues = mutableListOf<MdHopIssue>()
        if (hop is CompositeMdErHop) {
            if (allMdHopIssues) {
                for (element in mdHopElements(docs, resolver)) mdIssues += hop.issues(element)
                mdIssues += hop.derived.lineageIssues
            } else {
                mdIssues += groundedMdHopIssues(docs, resolver, hop)
            }
        }
        return CnBuild(
            CnSchema(out, bindings, relationTypes, elementTerms = elementTerms),
            validation.diagnostics,
            mdIssues.distinct(),
        )
    }

    // --- resolution ---------------------------------------------------------------------------------------------

    private fun ctx(
        doc: SemanticDocument,
        enclosing: String? = null,
    ) = ResolutionContext(doc.schemaCode, doc.namespace, enclosing, doc.imports, doc.packageName)

    private fun resolve(
        path: String,
        parts: List<String>,
        doc: SemanticDocument,
        cn: Boolean = false,
        enclosing: String? = null,
    ): SymbolEntry? =
        (resolver.resolveReference(Resolver.Ref(path, parts, cn), ctx(doc, enclosing)) as? ResolutionResult.Resolved)
            ?.symbol

    /** A reference in a cn slot — a concept, or null. */
    private fun conceptOf(
        id: PropertyValue.IdValue,
        doc: SemanticDocument,
    ): QualifiedName? = resolve(id.ref.path, id.parts, doc, cn = true)?.takeIf { it.kind == "concept" }?.let(::cnName)

    private fun cnName(sym: SymbolEntry) = QualifiedName(SchemaCode.CN, "concept", sym.name, sym.packageName)

    private fun cnName(key: String): QualifiedName {
        val name = key.substringAfterLast('.')
        return QualifiedName(SchemaCode.CN, "concept", name, key.substringBefore(".cn.concept.", ""))
    }

    // --- concepts -----------------------------------------------------------------------------------------------

    private fun first(
        c: CnConcept,
        key: String,
    ): StatedProperty? = c.properties.firstOrNull { it.property.key == key }

    private fun entry(
        c: CnConcept,
        key: String,
    ): Pair<PropertyValue, SemanticDocument>? =
        first(c, key)?.let { s -> (s.property as? ConceptProperty.Entry)?.let { it.value to s.doc } }

    private fun concept(
        c: CnConcept,
        bindings: Map<QualifiedName, Grounding>,
    ): Concept {
        val description = first(c, "description")?.property as? ConceptProperty.Description
        val landing = landingOf(c.key)
        val placed = validation.groundings.byConcept[c.key]?.firstOrNull()
        val grounding = placed?.let { bindings[bindingName(it)] }
        return Concept(
            internalId = FileBasedSource.idFor("cn.concept", cnName(c.key)),
            qname = cnName(c.key),
            stereotype = c.stereotype,
            description = description?.value?.let(::text) ?: "",
            descriptionLocalized = description?.localized?.toLocalizedText() ?: LocalizedText.EMPTY,
            tags = (first(c, "tags")?.property as? ConceptProperty.Tags)?.value ?: emptyList(),
            sourceFile = c.uri,
            label = (first(c, "label")?.property as? ConceptProperty.Label)?.value.toLocalizedText(),
            sources = entry(c, "sources")?.first?.let(::strings) ?: emptyList(),
            isA = entry(c, "is_a")?.let { (v, d) -> ids(v).mapNotNull { conceptOf(it, d) } } ?: emptyList(),
            abstract = (entry(c, "abstract")?.first as? PropertyValue.BoolValue)?.raw == true,
            of = entry(c, "of")?.let { (v, d) -> (v as? PropertyValue.IdValue)?.let { conceptOf(it, d) } },
            event =
                first(c, "event")?.let { s ->
                    (s.property as? ConceptProperty.Ref)?.ref?.let { r ->
                        resolve(r.path, r.parts, s.doc, cn = true)?.takeIf { it.kind == "concept" }?.let(::cnName)
                    }
                },
            extent =
                (first(c, "extent")?.property as? ConceptProperty.Extent)?.value?.let {
                    enumOf<Extent>(it)
                },
            involves = involves(c),
            period = (first(c, "period")?.property as? ConceptProperty.Period)?.value?.let(::period),
            qualities = entry(c, "qualities")?.first?.let { v -> ids(v).map { it.ref.path } } ?: emptyList(),
            valueSpace =
                first(c, "value_space")?.let { s ->
                    (s.property as? ConceptProperty.ValueSpace)?.ref?.let { r ->
                        resolve(r.path, r.parts, s.doc, cn = true)?.takeIf { it.kind == "concept" }?.let(::cnName)
                    }
                },
            valuedState = (entry(c, "valued_state")?.first as? PropertyValue.BoolValue)?.raw == true,
            imprecision =
                (entry(c, "imprecision")?.first as? PropertyValue.IdValue)?.ref?.path?.let {
                    enumOf<Imprecision>(it)
                },
            basis = (first(c, "basis")?.property as? ConceptProperty.Basis)?.value?.let { enumOf<Basis>(it) },
            definition = (first(c, "definition")?.property as? ConceptProperty.DefinitionBlock)?.block,
            members = (first(c, "members")?.property as? ConceptProperty.MembersBlock)?.block,
            relations = entry(c, "relations")?.let { (v, d) -> relations(v, d) } ?: emptyList(),
            lexiconLinks = inlineLexicon(c) + lexicon[c.key].orEmpty(),
            openEntries = openEntries(c),
            grounding = grounding,
            erTarget = landing.erTarget,
            via = landing.via,
            grade = landing.grade,
            landingFrom = landing.from,
            hop = landing.hop,
            reverseHop =
                landing.erTarget?.takeIf { landing.via == Via.DIRECT }?.let { hop.mdElementsOn(it) } ?: emptyList(),
            sourceLocation = c.source,
        )
    }

    private fun relationType(c: CnConcept): CnRelationType {
        val description = first(c, "description")?.property as? ConceptProperty.Description
        return CnRelationType(
            internalId = FileBasedSource.idFor("cn.relation_type", cnName(c.key)),
            qname = cnName(c.key),
            description = description?.value?.let(::text) ?: "",
            descriptionLocalized = description?.localized?.toLocalizedText() ?: LocalizedText.EMPTY,
            tags = (first(c, "tags")?.property as? ConceptProperty.Tags)?.value ?: emptyList(),
            sourceFile = c.uri,
            label = (first(c, "label")?.property as? ConceptProperty.Label)?.value.toLocalizedText(),
            sourceLocation = c.source,
        )
    }

    private fun involves(c: CnConcept): List<CnParticipant> {
        val s = first(c, "involves") ?: return emptyList()
        val p = s.property as? ConceptProperty.Involves ?: return emptyList()
        return p.participants.map { pt ->
            CnParticipant(
                label =
                    pt.label ?: pt.ref.parts
                        .last()
                        .lowercase(),
                concept =
                    resolve(pt.ref.path, pt.ref.parts, s.doc, cn = true)
                        ?.takeIf { it.kind == "concept" }
                        ?.let(::cnName),
            )
        }
    }

    private fun period(v: PropertyValue.ObjectValue): CnPeriod {
        fun name(key: String) = (v.entries[key] as? PropertyValue.IdValue)?.ref?.path
        val end = name("end")
        return CnPeriod(
            start = name("start"),
            end = end,
            at = name("at"),
            endState = if (end == null) null else name("end_open")?.let { enumOf<EndState>(it) } ?: EndState.PRESENT,
            precision = name("precision"),
        )
    }

    private fun relations(
        v: PropertyValue,
        doc: SemanticDocument,
    ): List<CnRelation> =
        (v as? PropertyValue.ListValue)?.items.orEmpty().mapNotNull { item ->
            val obj = item as? PropertyValue.ObjectValue ?: return@mapNotNull null
            val type = obj.entries["type"] as? PropertyValue.IdValue ?: return@mapNotNull null
            val to = obj.entries["to"] as? PropertyValue.IdValue
            CnRelation(
                typeName = type.ref.path,
                type = conceptOf(type, doc),
                toName = to?.ref?.path,
                to = to?.let { conceptOf(it, doc) },
            )
        }

    private fun openEntries(c: CnConcept): List<Pair<String, PropertyValue>> {
        val seen = mutableSetOf<String>()
        val out = mutableListOf<Pair<String, PropertyValue>>()
        for (s in c.properties) {
            val e = s.property as? ConceptProperty.Entry ?: continue
            if (e.key in STOCK_ENTRY_KEYS || !seen.add(e.key)) continue
            out += e.key to e.value
        }
        return out
    }

    // --- T4: the landing --------------------------------------------------------------------------------------

    /**
     * Where a concept lands on E-R (contracts §1.2): its own grounding's — an er target directly, an md target through
     * the hop (a quality's hop restricted to its bearer's entity, AM-29); a db grounding reaches no consumer. A
     * defined phase with no grounding inherits its bearer's (the `of:` chain; a cycle lands nowhere).
     */
    private fun landingOf(key: String): Landing {
        landings[key]?.let { return it }
        if (!visiting.add(key)) return Landing()
        val result = computeLanding(key)
        visiting.remove(key)
        landings[key] = result
        return result
    }

    private fun computeLanding(key: String): Landing {
        val c = concepts[key] ?: return Landing()
        val placed = validation.groundings.byConcept[key]?.firstOrNull()
        if (placed == null) {
            if (c.stereotype != "phase" || first(c, "definition") == null) return Landing()
            val (v, d) = entry(c, "of") ?: return Landing()
            val bearer =
                (v as? PropertyValue.IdValue)?.let { resolve(it.ref.path, it.parts, d, cn = true) }?.takeIf {
                    it.kind == "concept"
                } ?: return Landing()
            return landingOf(bearer.qname).copy(from = cnName(bearer), hop = null)
        }
        val target = placed.targetSymbol
        return when (layerOf(placed.grounding.bindingKind!!)) {
            GroundingLayer.ER ->
                Landing(
                    target?.takeIf { it.kind == "entity" || it.kind == "attribute" }?.let(::elementRefOf),
                    Via.DIRECT,
                    GroundingGrade.ER_GROUNDED,
                )
            GroundingLayer.MD -> {
                if (target == null) return Landing(null, Via.MD_HOP, HOP_MISSING)
                val bearer = if (c.stereotype == "quality") bearerEntity(c) else null
                // The built-in composite accepts canonical identities; injected hops retain the archive-ref seam.
                val md = if (hop is CompositeMdErHop) target.qname else elementRefOf(target)
                val answer = hop.erTargetOf(md, bearer)
                val er = answer.erTarget
                Landing(er, Via.MD_HOP, if (er != null) GroundingGrade.MD_GROUNDED else HOP_MISSING, hop = answer)
            }
            GroundingLayer.DB -> Landing()
        }
    }

    /** The er entity a quality's bearer lands on — rule 2 of the hop (AM-29). */
    private fun bearerEntity(c: CnConcept): String? {
        val (v, d) = entry(c, "of") ?: return null
        val bearer =
            (v as? PropertyValue.IdValue)?.let { resolve(it.ref.path, it.parts, d, cn = true) }?.takeIf {
                it.kind == "concept"
            } ?: return null
        return landingOf(bearer.qname).erTarget?.let(::entityOfElement)
    }

    // --- groundings ---------------------------------------------------------------------------------------------

    private fun bindingName(p: PlacedGrounding): QualifiedName =
        QualifiedName(SchemaCode.CN, p.grounding.bindingKind ?: "", p.grounding.name, p.packageName)

    private fun grounding(p: PlacedGrounding): Grounding? {
        val g = p.grounding
        val bindingKind = g.bindingKind ?: return null
        val conceptKey = p.conceptKey ?: return null
        val doc = docByUri.getValue(p.uri)
        val def =
            if (g.origin == GroundingOrigin.Binding) {
                doc.definitions.firstOrNull { it is ConceptBindingDef && it.source == g.source } as? ConceptBindingDef
            } else {
                null
            }
        val enclosing =
            if (g.origin == GroundingOrigin.Binding) {
                buildCanonicalKey(doc.packageName, "", bindingKind, listOf(g.name))
            } else {
                conceptKey
            }
        val dangling = mutableListOf<String>()
        if (p.targetSymbol == null) (g.target?.path ?: g.targetKey)?.let { dangling += it }

        fun element(id: PropertyValue.IdValue): String? =
            resolve(id.ref.path, id.parts, doc, enclosing = enclosing)?.let(::elementRefOf).also {
                if (it == null) dangling += id.ref.path
            }

        fun entryOf(key: String): PropertyValue? =
            (g.properties.firstOrNull { it.key == key } as? ConceptProperty.Entry)?.value

        val hierarchy =
            (entryOf("hierarchy") as? PropertyValue.ObjectValue)?.let { h ->
                val closureId = h.entries["closure"] as? PropertyValue.IdValue
                val closure =
                    closureId?.let { resolve(it.ref.path, it.parts, doc, enclosing = enclosing) }.also {
                        if (it == null && closureId != null) dangling += closureId.ref.path
                    }

                fun attr(key: String): String? {
                    val name = (h.entries[key] as? PropertyValue.IdValue)?.ref?.path ?: return null
                    val sym = closure?.let { resolver.getSymbol("${it.qname}.$name") }
                    if (sym == null && closure != null) dangling += "${elementRefOf(closure)}.$name"
                    return sym?.let(::elementRefOf)
                }
                CnHierarchy(
                    closure = closure?.let(::elementRefOf),
                    ancestor = attr("ancestor"),
                    descendant = attr("descendant"),
                    ranges =
                        (h.entries["ranges"] as? PropertyValue.IdValue)?.ref?.path?.let {
                            enumOf<HierarchyRanges>(it)
                        } ?: HierarchyRanges.NODE,
                )
            }
        val participants = linkedMapOf<String, List<String>>()
        (entryOf("participants") as? PropertyValue.ObjectValue)?.entries?.forEach { (label, value) ->
            val path = ids(value).map { element(it) }
            if (path.isNotEmpty() && path.all { it != null }) participants[label] = path.map { it!! }
        }
        val period =
            ((g.properties.firstOrNull { it.key == "period" } as? ConceptProperty.Period)?.value)?.let { v ->
                fun at(key: String) = (v.entries[key] as? PropertyValue.IdValue)?.let(::element)
                GroundingPeriod(
                    start = at("start"),
                    end = at("end"),
                    at = at("at"),
                    startHi = at("start_hi"),
                    atHi = at("at_hi"),
                )
            }
        val viaPath = entryOf("via")?.let { v -> ids(v).mapNotNull { element(it) } } ?: emptyList()
        val synthesized = g.origin != GroundingOrigin.Binding
        return Grounding(
            internalId = FileBasedSource.idFor("cn.binding", bindingName(p)),
            qname = bindingName(p),
            bindingKind = bindingKind,
            concept = cnName(conceptKey),
            layer = layerOf(bindingKind),
            origin = g.origin,
            description = def?.description ?: "",
            descriptionLocalized = def?.descriptionLocalized.toLocalizedText(),
            tags = def?.tags ?: emptyList(),
            sourceFile = p.uri,
            binding =
                if (synthesized) {
                    Binding.BoundSynthetic(
                        if (g.origin ==
                            GroundingOrigin.Grounds
                        ) {
                            "desugared from grounds:"
                        } else {
                            "desugared from concept { }"
                        },
                    )
                } else {
                    Binding.BoundReal
                },
            target = p.targetSymbol?.let(::elementRefOf),
            targetKind = p.targetSymbol?.kind,
            hierarchy = hierarchy,
            participants = participants,
            period = period,
            unit =
                entryOf("unit")?.let { u ->
                    text(u) ?: (u as? PropertyValue.IdValue)?.ref?.path
                },
            viaPath = viaPath,
            filter =
                (g.properties.firstOrNull { it is ConceptProperty.FilterBlock } as? ConceptProperty.FilterBlock)
                    ?.block,
            dangling = dangling.distinct(),
            sourceLocation = g.source,
        )
    }

    // --- T6: lexicon links --------------------------------------------------------------------------------------

    /**
     * Every canonical lexicon entry whose `for:` names a concept, by concept key — and (S2.7) every one naming an md or
     * er element, by element ref ([CnSchema.elementTerms]); both in document order.
     */
    private fun canonicalLexicon(): Pair<Map<String, List<LexiconLink>>, Map<String, List<LexiconLink>>> {
        val out = linkedMapOf<String, MutableList<LexiconLink>>()
        val elements = linkedMapOf<String, MutableList<LexiconLink>>()
        for (doc in docs) {
            for (def in doc.definitions) {
                if (def !is LexiconEntryDef) continue
                val target = def.target ?: continue
                val sym = resolve(target.path, target.parts, doc) ?: continue
                val key =
                    when {
                        sym.kind == "concept" -> sym.qname
                        sym.schemaCode == "md" || sym.schemaCode == "er" -> null
                        else -> continue
                    }
                val into =
                    if (key !=
                        null
                    ) {
                        out.getOrPut(key) { mutableListOf() }
                    } else {
                        elements.getOrPut(elementRefOf(sym)) { mutableListOf() }
                    }
                into +=
                    LexiconLink(
                        entryKind = def.entryKind,
                        name = def.name,
                        origin = LexiconOrigin.CANONICAL,
                        locale = locales[doc.uri],
                        forms = def.forms,
                        match = def.match,
                        text = def.text,
                        default = def.default,
                        sensePriority = def.sensePriority,
                        sourceFile = doc.uri,
                        sourceLocation = def.source,
                    )
            }
        }
        return out to elements
    }

    /**
     * A concept's inline `lexicon { }` — the first one it states — as entries: one `term` with every term, a `pattern`
     * per pattern, an `example` per example (TS `collectInline`); the base locale; the block's sense fields on the term
     * and the examples.
     */
    private fun inlineLexicon(c: CnConcept): List<LexiconLink> {
        val s = first(c, "lexicon") ?: return emptyList()
        val p = s.property as? ConceptProperty.Lexicon ?: return emptyList()
        val b = p.block

        fun link(
            kind: String,
            forms: List<String> = emptyList(),
            match: String? = null,
            text: String? = null,
            sense: Boolean = true,
        ) = LexiconLink(
            entryKind = kind,
            name = c.name,
            origin = LexiconOrigin.INLINE,
            forms = forms,
            match = match,
            text = text,
            default = if (sense) b.default else null,
            sensePriority = if (sense) b.sensePriority else null,
            sourceFile = s.doc.uri,
            sourceLocation = p.source,
        )
        return buildList {
            if (b.terms.isNotEmpty()) add(link("term", forms = b.terms))
            b.patterns.forEach { add(link("pattern", match = it, sense = false)) }
            b.examples.forEach { add(link("example", text = it)) }
        }
    }
}

private val HOP_MISSING = GroundingGrade.MD_GROUNDED_HOP_MISSING

private fun layerOf(bindingKind: String): GroundingLayer =
    when {
        bindingKind.startsWith("cn2md_") -> GroundingLayer.MD
        bindingKind.startsWith("cn2er_") -> GroundingLayer.ER
        else -> GroundingLayer.DB
    }

/** A value of a closed vocabulary (`current_state` → `CURRENT_STATE`); null outside it. */
private inline fun <reified E : Enum<E>> enumOf(raw: String): E? =
    enumValues<E>().firstOrNull {
        it.name ==
            raw.uppercase()
    }

private fun text(v: PropertyValue): String? =
    when (v) {
        is PropertyValue.StringValue -> v.raw
        is PropertyValue.TripleStringValue -> v.raw
        else -> null
    }

private fun strings(v: PropertyValue): List<String> =
    (v as? PropertyValue.ListValue)?.items.orEmpty().mapNotNull(::text)

/** The ids of a value: an id, or a list's ids, nested lists flattened (the ttr-semantics and TS `idsOf`). */
private fun ids(v: PropertyValue?): List<PropertyValue.IdValue> =
    when (v) {
        is PropertyValue.IdValue -> listOf(v)
        is PropertyValue.ListValue -> v.items.flatMap { ids(it) }
        else -> emptyList()
    }
