// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.metadata.source

import org.tatrman.ttr.metadata.model.*
import org.tatrman.ttr.parser.model.*
import org.tatrman.ttr.semantics.*
import org.tatrman.ttr.semantics.md.*

/** R1–R7 over canonical physical identities. Queries add origins; database views never invent lineage. */
class DerivedMdErHop(
    val docs: List<SemanticDocument>,
    val resolver: Resolver,
    val physical: PhysicalIndex = PhysicalIndex(docs, resolver),
    lineage: QueryLineage = QueryLineage.provider(),
) : MdErHop {
    val candidates = sortedMapOf<String, MutableSet<String>>()
    val causes = mutableMapOf<String, String>()
    val lineageIssues = mutableListOf<MdHopIssue>()
    private val domainAttributes = linkedMapOf<String, MutableSet<String>>()
    private val dimensions = linkedMapOf<String, Pair<SemanticDocument, DimensionDef>>()

    private fun add(
        md: String,
        er: Iterable<String>,
    ) {
        candidates.getOrPut(md) { sortedSetOf() }.addAll(er)
    }

    private fun local(key: String): String = resolver.getSymbol(key)?.let(::elementRefOf) ?: key

    private fun columns(
        md: String,
        column: String?,
        entities: Set<String>? = null,
    ) {
        if (column == null) {
            causes[md] = "no md2db column"
            return
        }
        val attrs =
            physical.columnToErAttr[column].orEmpty().filter {
                entities == null ||
                    it.substringBeforeLast('.') in entities
            }
        if (attrs.isEmpty()) causes[md] = "no er2db for column '$column'"
        add(md, attrs.map(::local))
    }

    init {
        // Query lineage can itself be stacked; resolve each layer with a cycle guard in the SPI context.
        val context = QueryLineageContext(physical)
        for ((entity, query) in physical.queryEntities) {
            val definition = physical.entities.getValue(entity).second
            for ((i, attr) in definition.attributes.withIndex()) {
                val origins = lineage.originsOf(context, query, attr.name, i)
                if (origins == null) {
                    lineageIssues +=
                        MdHopIssue(
                            local(entity) + "." + attr.name,
                            "md/er-lineage-opaque",
                            "info",
                            "Column '${attr.name}' of '$query' has opaque lineage",
                        )
                } else {
                    for (column in origins) physical.addOrigin(column, "$entity.${attr.name}")
                }
            }
        }
        for (doc in docs) {
            for (def in doc.definitions.filterIsInstance<DimensionDef>()) {
                val key = physical.key(doc, "dimension", def.name)
                dimensions[key] = doc to def
                for (attr in def.attributes) {
                    val domain = attr.domainRef?.path?.let { resolveMdNs(resolver, doc, it, "domain") } ?: continue
                    domainAttributes.getOrPut(domain.qname) { linkedSetOf() }.add("$key.${attr.name}")
                }
            }
        }
        // R1, R2 and R5, retaining every md2db source of a multi-source cubelet.
        for (doc in docs) {
            for (def in doc.definitions.filterIsInstance<Md2dbCubeletDef>()) {
                val cubelet = def.cubeletRef?.path?.let { resolveMdNs(resolver, doc, it, "cubelet") } ?: continue
                val md = elementRefOf(cubelet)
                val table = def.table?.path?.let { physical.resolve(doc, it) } ?: continue
                val entities = physical.tableToErEntity[table.qname].orEmpty()
                add(md, entities.map(::local))
                if (entities.isEmpty()) causes[md] = "no er2db for table '${table.qname}'"
                val measures = cubeletMeasureKeys(docs, resolver, cubelet)
                for ((name, binding) in def.measures) {
                    val measure =
                        resolveMdNs(resolver, doc, name, "measure")?.takeIf { it.qname in measures } ?: continue
                    val col =
                        when (binding) {
                            is MeasureColumnBinding.Column -> binding.column
                            is MeasureColumnBinding.Code -> (def.shape as? ShapeSpec.Long)?.valueColumn
                        }
                    columns(elementRefOf(measure), col?.let { physical.column(doc, table.qname, it) })
                }
                for ((name, binding) in def.attributes) {
                    val attr = resolveMdAttribute(resolver, doc, name) ?: continue
                    val grain = "$md.${elementRefOf(attr).removePrefix("md.dimension.")}"
                    val col =
                        when (binding) {
                            is AttrColumnBinding.Column -> physical.column(doc, table.qname, binding.column)
                            is AttrColumnBinding.Via ->
                                physical.resolve(doc, binding.from.table.path)?.let {
                                    physical.column(doc, it.qname, binding.from.column)
                                }
                        }
                    columns(grain, col, entities)
                    // R3(a): only a dimension key, with a relation whose from side binds this FK attribute.
                    val dimension = dimensions[attr.parent] ?: continue
                    if (dimension.second.key != attr.name) continue
                    val erAttrs = physical.columnToErAttr[col].orEmpty()
                    for (relation in physical.relations) {
                        if (relation.from !in entities || relation.pairs.none { it.first in erAttrs }) continue
                        val dim = local(attr.parent!!)
                        add(dim, listOf(local(relation.to)))
                        add(elementRefOf(attr), relation.pairs.filter { it.first in erAttrs }.map { local(it.second) })
                    }
                }
            }
        }
        // R3(b), R4: maps from a key domain corroborate its entity; maps to descriptive domains land attributes.
        for (doc in docs) {
            for (def in doc.definitions.filterIsInstance<Md2dbMapDef>()) {
                val map = def.mapRef?.path?.let { resolveMdNs(resolver, doc, it, "map") } ?: continue
                val mapDoc = docs.find { it.uri == map.documentUri } ?: continue
                val logical = mapDoc.definitions.filterIsInstance<MdMapDef>().find { it.name == map.name } ?: continue
                if (logical.cardinality == "N:N") continue
                val table = def.table?.path?.let { physical.resolve(doc, it) } ?: continue
                val from = logical.from.mapNotNull { resolveMdNs(resolver, mapDoc, it.path, "domain")?.qname }.toSet()
                val to = logical.to.mapNotNull { resolveMdNs(resolver, mapDoc, it.path, "domain")?.qname }.toSet()
                for ((name, col) in def.columns) {
                    val domain = resolveMdNs(resolver, doc, name, "domain") ?: continue
                    for (attr in domainAttributes[domain.qname].orEmpty()) {
                        val dim = dimensions[attr.substringBeforeLast('.')] ?: continue
                        val isKey = dim.second.key == attr.substringAfterLast('.')
                        if (domain.qname in from && isKey) {
                            add(
                                local(attr.substringBeforeLast('.')),
                                physical.tableToErEntity[table.qname].orEmpty().map(::local),
                            )
                            columns(local(attr), physical.column(doc, table.qname, col))
                        }
                        if (domain.qname in to && !isKey) columns(local(attr), physical.column(doc, table.qname, col))
                    }
                }
            }
        }
        // R3(c), R6: bound domain sources, then fallback through dimension attributes of that value domain.
        val bound = mutableSetOf<String>()
        for (doc in docs) {
            for (def in doc.definitions.filterIsInstance<Md2dbDomainDef>()) {
                val domain = def.domainRef?.path?.let { resolveMdNs(resolver, doc, it, "domain") } ?: continue
                val src = def.columnSource ?: continue
                val table = physical.resolve(doc, src.table.path) ?: continue
                bound += domain.qname
                val col = physical.column(doc, table.qname, src.column)
                columns(elementRefOf(domain), col)
                for (attr in domainAttributes[domain.qname].orEmpty()) {
                    columns(local(attr), col)
                    if (dimensions[attr.substringBeforeLast('.')]?.second?.key == attr.substringAfterLast('.')) {
                        add(
                            local(attr.substringBeforeLast('.')),
                            physical.tableToErEntity[table.qname].orEmpty().map(::local),
                        )
                    }
                }
            }
        }
        // R4 key fallback uses the declared ER key only inside the R3 entity set.
        for ((key, pair) in dimensions) {
            val attr = pair.second.key ?: continue
            val md = local(key)
            if (candidates["$md.$attr"].isNullOrEmpty()) {
                for (entity in candidates[md].orEmpty()) {
                    val er = physical.entities.entries.find { local(it.key) == entity } ?: continue
                    add(
                        "$md.$attr",
                        er.value.second.attributes
                            .filter { it.isKey }
                            .map { "$entity.${it.name}" },
                    )
                }
            }
            if (candidates[md].isNullOrEmpty()) {
                causes[md] =
                    "no relation or map from the dimension's key to an er entity"
            }
        }
        for ((domain, attrs) in domainAttributes) {
            if (domain !in bound) {
                add(local(domain), attrs.flatMap { candidates[local(it)].orEmpty() })
            }
        }
    }

    override fun erTargetOf(
        mdElement: String,
        bearerEntity: String?,
    ): HopResult {
        val found = candidates[mdElement].orEmpty()
        if (bearerEntity != null) {
            val restricted = found.filter { entityOfElement(it) == bearerEntity }
            if (restricted.size == 1) return HopResult(restricted.single(), HopVia.DERIVED_BEARER)
            if (restricted.size >
                1
            ) {
                return HopResult.ambiguous(
                    "'$mdElement' maps to ${restricted.joinToString(", ")}; author md2er_* to pick",
                )
            }
        }
        return when (found.size) {
            0 ->
                HopResult.unmapped(
                    "'$mdElement' has no er counterpart — ${causes[mdElement] ?: "no md2db binding for the element"}",
                )
            1 -> HopResult(found.single(), HopVia.DERIVED)
            else ->
                HopResult.ambiguous(
                    "'$mdElement' maps to ${found.joinToString(", ")}; author md2er_cubelet/md2er_dimension to pick",
                )
        }
    }

    override fun mdElementsOn(erElement: String): List<String> =
        candidates.filterValues { erElement in it }.keys.sorted()
}

data class MdHopIssue(
    val element: String,
    val slug: String,
    val severity: String,
    val message: String,
)

/** The explicit tier owns an element even when its authored mapping is ambiguous. */
class CompositeMdErHop(
    val explicit: MdErHop,
    val derived: DerivedMdErHop,
    private val allowScopes: Boolean = true,
) : MdErHop {
    constructor(
        docs: List<SemanticDocument>,
        resolver: Resolver,
    ) : this(ExplicitMdErHop(docs, resolver), DerivedMdErHop(docs, resolver))

    private val scopes = mutableMapOf<String, CompositeMdErHop>()

    private fun belongsTo(
        def: Definition,
        doc: SemanticDocument,
        pkg: String,
    ): Boolean {
        val ref =
            when (def) {
                is Md2dbCubeletDef -> def.cubeletRef?.path?.let { it to "cubelet" }
                is Md2erCubeletDef -> def.cubeletRef?.path?.let { it to "cubelet" }
                is Md2erDimensionDef -> def.dimensionRef?.path?.let { it to "dimension" }
                is Md2dbDomainDef -> def.domainRef?.path?.let { it to "domain" }
                is Md2dbMapDef -> def.mapRef?.path?.let { it to "map" }
                else -> null
            }
        if (ref != null) return resolveMdNs(derived.resolver, doc, ref.first, ref.second)?.packageName == pkg
        return when (def) {
            is DimensionDef, is MdDomainDef, is CubeletDef, is MdMapDef, is MeasureDef -> doc.packageName == pkg
            else -> true
        }
    }

    private fun scoped(mdElement: String): Pair<CompositeMdErHop, String>? {
        if (!allowScopes) return null
        val symbol =
            derived.resolver.getSymbol(mdElement)?.takeIf { it.schemaCode == "md" }
                ?: generateSequence(
                    mdElement.substringBeforeLast('.', ""),
                ) { it.substringBeforeLast('.', "").ifEmpty { null } }
                    .mapNotNull { derived.resolver.getSymbol(it) }
                    .firstOrNull { it.schemaCode == "md" && it.kind == "cubelet" }
                ?: return null
        val hop =
            scopes.getOrPut(symbol.packageName) {
                val docs =
                    derived.docs.map { doc ->
                        doc.copy(definitions = doc.definitions.filter { belongsTo(it, doc, symbol.packageName) })
                    }
                CompositeMdErHop(
                    ExplicitMdErHop(docs, derived.resolver),
                    DerivedMdErHop(docs, derived.resolver, derived.physical),
                    false,
                )
            }
        return hop to (elementRefOf(symbol) + mdElement.removePrefix(symbol.qname))
    }

    override fun erTargetOf(
        mdElement: String,
        bearerEntity: String?,
    ): HopResult {
        scoped(mdElement)?.let { (hop, local) -> return hop.erTargetOf(local, bearerEntity) }
        val pinned = explicit.erTargetOf(mdElement, bearerEntity)
        return if (pinned.diagnostic != HopResult.UNMAPPED) pinned else derived.erTargetOf(mdElement, bearerEntity)
    }

    fun issues(
        mdElement: String,
        bearerEntity: String? = null,
    ): List<MdHopIssue> {
        scoped(mdElement)?.let { (hop, local) ->
            return hop.issues(local, bearerEntity).map { if (it.element == local) it.copy(element = mdElement) else it }
        }
        val answer = erTargetOf(mdElement, bearerEntity)
        val out = mutableListOf<MdHopIssue>()
        if (answer.diagnostic != null) out += MdHopIssue(mdElement, answer.diagnostic, "warning", answer.message)
        if (answer.via == HopVia.EXPLICIT) {
            val candidates = derived.candidates[mdElement].orEmpty()
            if (candidates.isNotEmpty() &&
                candidates != setOf(answer.erTarget)
            ) {
                out +=
                    MdHopIssue(
                        mdElement,
                        "md/er-hop-override",
                        "info",
                        "Explicit '${answer.erTarget}' overrides derived ${candidates.joinToString(", ")}",
                    )
            }
        }
        out +=
            derived.lineageIssues.filter {
                it.element == answer.erTarget ||
                    (bearerEntity != null && entityOfElement(it.element) == bearerEntity)
            }
        return out
    }

    override fun mdElementsOn(erElement: String): List<String> =
        (explicit.mdElementsOn(erElement) + derived.mdElementsOn(erElement))
            .distinct()
            .filter { md ->
                val answer = erTargetOf(md, entityOfElement(erElement))
                answer.erTarget == erElement
            }.sorted()
}

/** Whole-model report inventory; cubelet grain refs are kept distinct from dimension attributes (R5). */
fun mdHopElements(
    docs: List<SemanticDocument>,
    resolver: Resolver,
): List<String> =
    buildSet {
        for (doc in docs) {
            for (def in doc.definitions) {
                val kind =
                    when (def) {
                        is DimensionDef -> "dimension"
                        is MdDomainDef -> "mdDomain"
                        is MeasureDef -> "measure"
                        is CubeletDef -> "cubelet"
                        else -> continue
                    }
                val key = buildCanonicalKey(doc.packageName, doc.namespace, kind, listOf(def.name), doc.schemaCode)
                val sym = resolver.getSymbol(key) ?: continue
                val ref = sym.qname
                add(ref)
                if (def is DimensionDef) def.attributes.forEach { add("$ref.${it.name}") }
                if (def is CubeletDef) {
                    def.grain.forEach { grain ->
                        resolveMdAttribute(
                            resolver,
                            doc,
                            grain.path,
                        )?.let { add("$ref.${elementRefOf(it).removePrefix("md.dimension.")}") }
                    }
                }
            }
        }
    }.sorted()

/** Default lint scope uses each concept's actual bearer context, not the unrestricted report row. */
fun groundedMdHopIssues(
    docs: List<SemanticDocument>,
    resolver: Resolver,
    hop: CompositeMdErHop,
): List<MdHopIssue> {
    val validation =
        org.tatrman.ttr.semantics.cn
            .validateCn(docs, resolver)
    val visiting = mutableSetOf<String>()

    fun bearer(key: String): String? {
        val c = validation.concepts[key] ?: return null
        val property = c.properties.find { it.property.key == "of" } ?: return null
        val value = (property.property as? ConceptProperty.Entry)?.value as? PropertyValue.IdValue ?: return null
        val doc = property.doc
        return (
            resolver.resolveReference(
                Resolver.Ref(value.ref.path, value.parts, true),
                ResolutionContext(doc.schemaCode, doc.namespace, null, doc.imports, doc.packageName),
            ) as? ResolutionResult.Resolved
        )?.symbol?.qname
    }

    fun landing(key: String): String? {
        if (!visiting.add(key)) return null
        return try {
            val target =
                validation.groundings.byConcept[key]
                    ?.firstOrNull()
                    ?.targetSymbol
            val of = bearer(key)
            when (target?.schemaCode) {
                "er" -> elementRefOf(target)
                "md" -> hop.erTargetOf(target.qname, of?.let { landing(it) }?.let(::entityOfElement)).erTarget
                else -> of?.let { landing(it) }
            }
        } finally {
            visiting.remove(key)
        }
    }
    return validation.groundings.groundings
        .flatMap { g ->
            val target = g.targetSymbol?.takeIf { it.schemaCode == "md" } ?: return@flatMap emptyList()
            val of =
                g.conceptKey
                    ?.let(::bearer)
                    ?.let { landing(it) }
                    ?.let(::entityOfElement)
            hop.issues(target.qname, of)
        }.distinct()
}
