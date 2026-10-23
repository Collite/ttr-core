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

    private fun local(key: String): String = key

    private fun columns(
        doc: SemanticDocument,
        md: String,
        column: String?,
        entities: Set<String>? = null,
    ) {
        if (column == null) {
            causes[md] = "no md2db column"
            return
        }
        val attrs =
            physical.attributesOn(doc, column).filter {
                entities == null ||
                    it.substringBeforeLast('.') in entities
            }
        if (attrs.isEmpty()) {
            val scope = if (physical.columnToErAttr[column].orEmpty().isNotEmpty()) "visible " else ""
            causes[md] = "no ${scope}er2db for column '$column'"
        }
        add(md, attrs.map(::local))
    }

    init {
        // Query lineage can itself be stacked; resolve each layer with a cycle guard in the SPI context.
        val context = QueryLineageContext(physical)
        for ((entity, query) in physical.queryEntities) {
            val definition = physical.entities.getValue(entity).second
            for ((i, attr) in definition.attributes.withIndex()) {
                val origins =
                    runCatching {
                        lineage.originsOf(context, query, physical.boundColumn("$entity.${attr.name}"), i)
                    }.getOrNull()
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
        val mapTargets = mutableMapOf<String, MutableSet<String>>()
        for (doc in docs) {
            for (binding in doc.definitions.filterIsInstance<Md2dbMapDef>()) {
                val map = binding.mapRef?.path?.let { resolveMdNs(resolver, doc, it, "map") } ?: continue
                val mapDoc = docs.find { it.uri == map.documentUri } ?: continue
                val logical = mapDoc.definitions.filterIsInstance<MdMapDef>().find { it.name == map.name } ?: continue
                val targets = logical.to.mapNotNull { resolveMdNs(resolver, mapDoc, it.path, "domain")?.qname }.toSet()
                val table = binding.table?.path?.let { physical.resolve(doc, it) } ?: continue
                for ((domainName, column) in binding.columns) {
                    val domain = resolveMdNs(resolver, doc, domainName, "domain") ?: continue
                    if (domain.qname in
                        targets
                    ) {
                        mapTargets
                            .getOrPut(map.qname) {
                                sortedSetOf()
                            }.addAll(physical.attributesOn(doc, physical.column(doc, table.qname, column)))
                    }
                }
            }
        }
        // R1, R2 and R5, retaining every md2db source of a multi-source cubelet.
        for (doc in docs) {
            for (def in doc.definitions.filterIsInstance<Md2dbCubeletDef>()) {
                val cubelet = def.cubeletRef?.path?.let { resolveMdNs(resolver, doc, it, "cubelet") } ?: continue
                val md = cubelet.qname
                val table = def.table?.path?.let { physical.resolve(doc, it) } ?: continue
                val entities = physical.entitiesOn(doc, table.qname)
                add(md, entities.map(::local))
                if (entities.isEmpty()) {
                    val scope = if (physical.tableToErEntity[table.qname].orEmpty().isNotEmpty()) "visible " else ""
                    causes[md] = "no ${scope}er2db for table '${table.qname}'"
                }
                val measures = cubeletMeasureKeys(docs, resolver, cubelet)
                for ((name, binding) in def.measures) {
                    val measure =
                        resolveMdNs(resolver, doc, name, "measure")?.takeIf { it.qname in measures } ?: continue
                    val col =
                        when (binding) {
                            is MeasureColumnBinding.Column -> binding.column
                            is MeasureColumnBinding.Code -> (def.shape as? ShapeSpec.Long)?.valueColumn
                        }
                    columns(doc, measure.qname, col?.let { physical.column(doc, table.qname, it) })
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
                    if (binding is AttrColumnBinding.Via) {
                        val map = resolveMdNs(resolver, doc, binding.via.path, "map")
                        // A via grain uses this map's target, not every map sharing its output domain.
                        columns(doc, grain, col)
                        mapTargets[map?.qname]
                            ?.let { targets ->
                                candidates[grain] =
                                    targets.filter { physical.isVisible(doc, it) }.toMutableSet()
                                if (candidates[grain].isNullOrEmpty()) {
                                    causes[grain] =
                                        "no visible er2db for via map target"
                                }
                            }
                    } else {
                        columns(doc, grain, col, entities)
                    }
                    // R3(a): only a dimension key, with a relation whose from side binds this FK attribute.
                    val dimension = dimensions[attr.parent] ?: continue
                    if (dimension.second.key != attr.name) continue
                    val erAttrs = physical.attributesOn(doc, col)
                    for (relation in physical.relations) {
                        if (relation.from !in entities ||
                            !physical.isVisible(doc, relation.key) ||
                            !physical.isVisible(doc, relation.to) ||
                            relation.pairs.none { it.first in erAttrs }
                        ) {
                            continue
                        }
                        val dim = local(attr.parent!!)
                        add(dim, listOf(local(relation.to)))
                        add(
                            attr.qname,
                            relation.pairs
                                .filter {
                                    it.first in erAttrs &&
                                        physical.isVisible(doc, it.second)
                                }.map { local(it.second) },
                        )
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
                                physical.entitiesOn(doc, table.qname).map(::local),
                            )
                            columns(doc, local(attr), physical.column(doc, table.qname, col))
                        }
                        if (domain.qname in to &&
                            !isKey
                        ) {
                            columns(doc, local(attr), physical.column(doc, table.qname, col))
                        }
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
                columns(doc, domain.qname, col)
                for (attr in domainAttributes[domain.qname].orEmpty()) {
                    columns(doc, local(attr), col)
                    if (dimensions[attr.substringBeforeLast('.')]?.second?.key == attr.substringAfterLast('.')) {
                        add(
                            local(attr.substringBeforeLast('.')),
                            physical.entitiesOn(doc, table.qname).map(::local),
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
    ): HopResult =
        qualifiedTargetOf(mdElement, bearerEntity).let { answer ->
            answer.copy(erTarget = answer.erTarget?.let { resolver.getSymbol(it)?.let(::elementRefOf) ?: it })
        }

    fun qualifiedTargetOf(
        mdElement: String,
        bearerEntity: String? = null,
        allowed: Set<String>? = null,
    ): HopResult {
        val found = allowed ?: candidates[mdElement].orEmpty()
        if (bearerEntity != null) {
            val restricted =
                found.filter {
                    qualifiedEntity(it) == bearerEntity ||
                        (
                            resolver.getSymbol(bearerEntity) == null &&
                                resolver.getSymbol(qualifiedEntity(it).orEmpty())?.let(::elementRefOf) == bearerEntity
                        )
                }
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
        candidates
            .filterValues { values ->
                values.any {
                    it == erElement ||
                        resolver.getSymbol(it)?.let(::elementRefOf) == erElement
                }
            }.keys
            .sorted()
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
) : MdErHop {
    constructor(
        docs: List<SemanticDocument>,
        resolver: Resolver,
    ) : this(ExplicitMdErHop(docs, resolver, qualified = true), DerivedMdErHop(docs, resolver))

    override fun erTargetOf(
        mdElement: String,
        bearerEntity: String?,
    ): HopResult =
        qualifiedTargetOf(mdElement, bearerEntity).let { answer ->
            answer.copy(erTarget = answer.erTarget?.let { derived.resolver.getSymbol(it)?.let(::elementRefOf) ?: it })
        }

    private fun dimensionPin(md: String): String? {
        val attr = derived.resolver.getSymbol(md)?.takeIf { it.kind == "attribute" } ?: return null
        val dimension =
            derived.resolver.getSymbol(attr.parent.orEmpty())?.takeIf { it.kind == "dimension" } ?: return null
        return explicit.erTargetOf(dimension.qname).erTarget
    }

    private fun pinnedEntities(md: String): Set<String>? {
        val pin = dimensionPin(md) ?: return null
        val reached = linkedSetOf(pin)
        var size: Int
        do {
            size = reached.size
            for (rel in derived.physical.relations) {
                if (rel.from in reached && rel.toCardinality in setOf("1", "0..1", "1..1")) reached += rel.to
                if (rel.to in reached && rel.fromCardinality in setOf("1", "0..1", "1..1")) reached += rel.from
            }
        } while (reached.size != size)
        return reached
    }

    private fun attributeCandidates(md: String): Set<String>? {
        val reached = pinnedEntities(md) ?: return null
        val found =
            derived.candidates[md]
                .orEmpty()
                .filter { qualifiedEntity(it) in reached }
                .toSet()
        if (found.isNotEmpty()) return found
        val symbol = derived.resolver.getSymbol(md) ?: return found
        val owner = derived.resolver.getSymbol(symbol.parent.orEmpty())
        val dimension =
            derived.docs.find { it.uri == owner?.documentUri }?.definitions?.filterIsInstance<DimensionDef>()?.find {
                it.name ==
                    owner?.name
            }
        if (dimension?.key != symbol.name) return found
        return reached
            .flatMap { entity ->
                derived.physical.entities[entity]
                    ?.second
                    ?.attributes
                    .orEmpty()
                    .filter { it.isKey }
                    .map { "$entity.${it.name}" }
            }.toSet()
    }

    fun qualifiedTargetOf(
        mdElement: String,
        bearerEntity: String? = null,
    ): HopResult {
        val pinned = explicit.erTargetOf(mdElement, bearerEntity)
        return if (pinned.diagnostic !=
            HopResult.UNMAPPED
        ) {
            pinned
        } else {
            val allowed = attributeCandidates(mdElement)
            val answer = derived.qualifiedTargetOf(mdElement, bearerEntity, allowed)
            if (answer.diagnostic == HopResult.UNMAPPED &&
                allowed != null &&
                derived.candidates[mdElement].orEmpty().isNotEmpty()
            ) {
                HopResult.unmapped(
                    "'$mdElement' has no er counterpart — the dimension pin to '${dimensionPin(
                        mdElement,
                    )}' excludes every derived landing",
                )
            } else {
                answer
            }
        }
    }

    fun issues(
        mdElement: String,
        bearerEntity: String? = null,
    ): List<MdHopIssue> {
        val answer = qualifiedTargetOf(mdElement, bearerEntity)
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
        val reached = pinnedEntities(mdElement)
        val unreachable =
            derived.candidates[mdElement].orEmpty().filter {
                reached != null &&
                    qualifiedEntity(it) !in reached
            }
        if (unreachable.isNotEmpty()) {
            out +=
                MdHopIssue(
                    mdElement,
                    "md/er-hop-override",
                    "info",
                    "Dimension pin excludes derived ${unreachable.joinToString(", ")}",
                )
        }
        out +=
            derived.lineageIssues.filter {
                it.element == answer.erTarget ||
                    (bearerEntity != null && qualifiedEntity(it.element) == bearerEntity)
            }
        return out
    }

    override fun mdElementsOn(erElement: String): List<String> =
        (explicit.mdElementsOn(erElement) + derived.mdElementsOn(erElement))
            .distinct()
            .filter { md ->
                val answer = erTargetOf(md, entityOfElement(erElement))
                answer.erTarget == erElement
            }.map { derived.resolver.getSymbol(it)?.let(::elementRefOf) ?: it }
            .distinct()
            .sorted()
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

/** Default findings share the exact CN landing walk used by the typed model. */
fun groundedMdHopIssues(
    docs: List<SemanticDocument>,
    resolver: Resolver,
    hop: CompositeMdErHop,
): List<MdHopIssue> = cnLandingIssues(docs, resolver, hop)
