// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.metadata.query

import org.tatrman.ttr.metadata.graph.ModelGraph
import org.tatrman.ttr.metadata.model.Attribute
import org.tatrman.ttr.metadata.model.AttributeMappingTarget
import org.tatrman.ttr.metadata.model.DbColumn
import org.tatrman.ttr.metadata.model.DbTable
import org.tatrman.ttr.metadata.model.Entity
import org.tatrman.ttr.metadata.model.Er2DbAttributeMapping
import org.tatrman.ttr.metadata.model.Er2DbEntityMapping
import org.tatrman.ttr.metadata.model.MappingTarget
import org.tatrman.ttr.metadata.model.ModelObject
import org.tatrman.ttr.metadata.model.QualifiedName
import org.tatrman.ttr.metadata.model.SchemaCode
import org.tatrman.ttr.metadata.model.searchHintsOrNull
import org.tatrman.ttr.metadata.registry.RegistrySnapshot
import org.tatrman.ttr.metadata.search.SearchAlgorithmRegistry
import org.tatrman.ttr.metadata.search.SearchHit
import org.tatrman.ttr.metadata.search.SearchIndex
import org.tatrman.ttr.metadata.search.SearchIndexHolder
import org.tatrman.ttr.metadata.search.SearchQuery
import org.tatrman.ttr.metadata.search.postProcess

/**
 * MD2 pull-down: the reusable query logic the metadata service embedded inside its gRPC
 * `MetadataServiceImpl` bodies (ListObjects filtering/paging/fuzzy, search
 * orchestration, area resolution, object lookup). Extracted here so M4 shrinks
 * the gRPC methods to proto-conversion + delegation. Proto-free and message/id-free
 * (MD5): returns structured results / nulls; the facade mints diagnostics.
 *
 * NOT pulled down (stays kantheon — do not go looking): getModel package-bundle
 * assembly + PackageVersion sha256, getSnapshot etag walk, listQueries/getQuery
 * live-parse-status, listRoles/getRolesForEntity, validateModel/getStatus/refresh
 * shells, and all `to*Detail()`/`to*Proto()` builders.
 */
class MetadataQuery(
    private val snapshot: RegistrySnapshot,
    private val searchRegistry: SearchAlgorithmRegistry = SearchAlgorithmRegistry(emptyMap()),
    private val indexHolder: SearchIndexHolder? = null,
) {
    /** `ListObjectsRequest` filter surface, proto-free (MetadataServiceImpl lines 355–369). */
    data class ObjectFilter(
        val schema: SchemaCode? = null,
        val kind: String? = null,
        val tags: List<String> = emptyList(),
        val sourceFilePrefix: String? = null,
        val pkg: String? = null,
        /** Deprecated alias of [indexedOnly] (MV contracts §2.2): the bit it filtered on meant "indexed". */
        val fuzzyOnly: Boolean = false,
        /** MV — only carriers with a member vocabulary: indexed columns/attributes, and the columns backing an indexed attribute. */
        val indexedOnly: Boolean = false,
    ) {
        internal val onlyIndexed: Boolean get() = indexedOnly || fuzzyOnly
    }

    /** afterKey-based page window; `PageTokenCodec` (base64 wire token) stays kantheon. */
    data class PageRequest(
        val afterKey: String? = null,
        val pageSize: Int = DEFAULT_PAGE_SIZE,
    )

    data class Page<T>(
        val items: List<T>,
        val nextAfterKey: String?,
        val totalCount: Int,
    )

    /** `resolveArea` result (MetadataServiceImpl lines 892–920), message-free (MD5). */
    data class AreaResolution(
        val packages: List<String>,
        val description: String,
        val tags: List<String>,
    )

    fun graph(): ModelGraph = snapshot.graph

    fun getObject(qname: QualifiedName): ModelObject? = snapshot.model.objectByQname()[qname]

    /**
     * Kind-typed lookup (contracts §2, D-b support). `expected` is the object kind
     * string (`table`, `entity`, `engine`, `storage`, …); the position→kind table
     * is TTR-P compiler policy and never appears here (MD5).
     */
    fun resolve(
        qname: QualifiedName,
        expected: String,
    ): ResolveOutcome {
        val obj = snapshot.model.objectByQname()[qname] ?: return ResolveOutcome.NotFound(qname, expected)
        return if (obj.kind == expected) {
            ResolveOutcome.Found(obj)
        } else {
            ResolveOutcome.KindMismatch(qname, expected, obj.kind, obj.sourceFile.ifEmpty { null })
        }
    }

    /**
     * er→db binding traversal (E-d). Resolves an entity or attribute er qname to
     * its db counterpart via the model's er2db mappings, returning the hop chain
     * (attribute chains prepend the owning entity's hop when it is bound). Miss →
     * `dbQname = null` + [ErBindingResult.missing]. The library searches all loaded
     * binding packages; world-hosted scoping is the caller's policy (MD5).
     */
    fun erToDb(erQname: QualifiedName): ErBindingResult {
        val attrTarget = attrMappingByAttr[erQname]
        if (attrTarget != null) {
            val (colQname, srcFile) = attrTarget
            val chain = mutableListOf<BindingStep>()
            // Prepend the owning entity's hop if the entity qname (name before the
            // last dot) has an entity mapping.
            val entityName = erQname.name.substringBeforeLast('.', "")
            if (entityName.isNotEmpty()) {
                val entityQ = erQname.copy(name = entityName)
                entMappingByEntity[entityQ]?.let { (tableQ, entSrc) ->
                    chain += BindingStep(entityQ, tableQ, entSrc)
                }
            }
            chain += BindingStep(erQname, colQname, srcFile)
            return ErBindingResult(dbQname = colQname, chain = chain)
        }
        val entTarget = entMappingByEntity[erQname]
        if (entTarget != null) {
            val (tableQ, srcFile) = entTarget
            return ErBindingResult(dbQname = tableQ, chain = listOf(BindingStep(erQname, tableQ, srcFile)))
        }
        return ErBindingResult(
            dbQname = null,
            chain = emptyList(),
            missing = BindingMissing(erQname, bindingPackages),
        )
    }

    fun resolveArea(name: String): AreaResolution? =
        snapshot.model.areaByName(name)?.let { AreaResolution(it.packages, it.description, it.tags) }

    fun listObjects(
        filter: ObjectFilter,
        page: PageRequest,
    ): Page<ModelObject> {
        val backingIndexed = if (filter.onlyIndexed) indexedColumns else emptySet()
        val all =
            snapshot.model
                .objectByQname()
                .values
                .asSequence()
                .filter { filter.schema == null || it.qname.schemaCode == filter.schema }
                .filter { filter.kind.isNullOrEmpty() || it.kind == filter.kind }
                .filter { filter.tags.isEmpty() || filter.tags.any(it.tags::contains) }
                .filter { filter.sourceFilePrefix.isNullOrEmpty() || it.sourceFile.startsWith(filter.sourceFilePrefix) }
                .filter { obj ->
                    !filter.onlyIndexed ||
                        obj.searchHintsOrNull()?.indexed == true ||
                        (obj is DbColumn && obj.qname in backingIndexed)
                }.filter { obj -> filter.pkg.isNullOrEmpty() || obj.sourceFile.contains("/${filter.pkg}/") }
                .sortedBy { sortKey(it) }
                .toList()

        val pageSize = page.pageSize.takeIf { it > 0 }?.coerceAtMost(MAX_PAGE_SIZE) ?: DEFAULT_PAGE_SIZE
        // Cursor over a UNIQUE key (the full qname), so ties on the display sort key
        // (schemaCode.namespace.name — e.g. same-named columns in different tables)
        // don't skip rows on resume. Order stays by the display sort key.
        val startIndex =
            if (page.afterKey == null) {
                0
            } else {
                all.indexOfFirst { cursorKey(it) == page.afterKey }.let { if (it < 0) all.size else it + 1 }
            }
        val slice = all.drop(startIndex).take(pageSize)
        val nextAfterKey = if (startIndex + slice.size < all.size) slice.lastOrNull()?.let { cursorKey(it) } else null
        return Page(items = slice, nextAfterKey = nextAfterKey, totalCount = all.size)
    }

    // ---- Grounding surface (grammar 4.2 `semantics { … }`) ----
    // These five accessors are what ai-platform's metadata gRPC layer and the
    // grounding services' metadata lookups call to ground time / geo / money
    // semantics. Message/id-free (MD5): they return typed model objects or nulls.
    // Kind/role are the closed-vocabulary strings ttr-semantics resolves them to.

    /**
     * Grounding (metadata-service fiscal alignment): the package's period-table source — the
     * entity or db table declared `semantics { kind: period_table }`. `null` ⇒ the
     * package is **calendar-aligned** (grounding derives fiscal periods from the
     * request `GroundingContext` calendar instead). At most one is expected per package.
     */
    fun periodTableFor(pkg: String): ModelObject? =
        semanticEntities(pkg).firstOrNull { semanticsKindOf(it) == KIND_PERIOD_TABLE }

    /**
     * Grounding (role-aware planning): the resolved semantic role on an
     * attribute/column (`event_date`, `amount`, `geo_lat`, …), or `null` when it
     * carries none. Drives event-date defaulting, amount/currency pairing, and geo
     * column selection during query grounding.
     */
    fun semanticRole(attrQname: QualifiedName): String? =
        when (val o = getObject(attrQname)) {
            is Attribute -> o.semantics?.role
            is DbColumn -> o.semantics?.role
            else -> null
        }

    /**
     * Grounding (vocabulary sweep): every attribute/column in the package carrying
     * [role] (e.g. all `event_date`s across the package's entities, or all
     * `currency_code`s). Deterministic order (display sort key).
     */
    fun attributesByRole(
        pkg: String,
        role: String,
    ): List<ModelObject> =
        snapshot.model
            .objectByQname()
            .values
            .asSequence()
            .filter { inPackage(it, pkg) }
            .filter { attrRoleOf(it) == role }
            .sortedBy { sortKey(it) }
            .toList()

    /**
     * Grounding (geo): the package's points-of-interest — entities/tables declared
     * `semantics { kind: poi }` (carrying either a `geo_point` or a geo_lat/geo_lon
     * pair). Deterministic order (display sort key).
     */
    fun poiEntities(pkg: String): List<ModelObject> =
        semanticEntities(pkg)
            .filter { semanticsKindOf(it) == KIND_POI }
            .sortedBy { sortKey(it) }
            .toList()

    /**
     * Grounding (FX conversion): the package's `fx_rate`-kinded entity/table — the
     * currency-conversion source grounding joins to when an amount must be restated
     * across currencies. `null` when the package declares none.
     */
    fun fxRateTableFor(pkg: String): ModelObject? =
        semanticEntities(pkg).firstOrNull { semanticsKindOf(it) == KIND_FX_RATE }

    // Entities + db tables are the only kinds carrying a `semanticsKind`.
    private fun semanticEntities(pkg: String): Sequence<ModelObject> =
        snapshot.model
            .objectByQname()
            .values
            .asSequence()
            .filter { it is Entity || it is DbTable }
            .filter { inPackage(it, pkg) }

    private fun semanticsKindOf(o: ModelObject): String? =
        when (o) {
            is Entity -> o.semanticsKind
            is DbTable -> o.semanticsKind
            else -> null
        }

    private fun attrRoleOf(o: ModelObject): String? =
        when (o) {
            is Attribute -> o.semantics?.role
            is DbColumn -> o.semantics?.role
            else -> null
        }

    // Package membership mirrors ListObjects' filter: files live under `/<pkg>/`.
    private fun inPackage(
        o: ModelObject,
        pkg: String,
    ): Boolean = pkg.isEmpty() || o.sourceFile.contains("/$pkg/")

    /** Search orchestration (MetadataServiceImpl lines 639–694, minus OTel + proto mapping). */
    fun search(query: SearchQuery): List<SearchHit> {
        val requestedAlgo = query.algorithm.ifEmpty { DEFAULT_SEARCH_ALGORITHM }
        val language = query.language.ifEmpty { "cs" }
        val algo = searchRegistry.get(requestedAlgo) ?: return emptyList()
        val index =
            if (algo.name == "all") {
                SearchIndex.Empty
            } else {
                indexHolder?.get(algo.name, language) ?: SearchIndex.Empty
            }
        val raw = algo.search(query, index)
        return postProcess(raw, query)
    }

    // Indexed attribute → backing db column set (MetadataServiceImpl lines 171–209; MV renamed it
    // from `attributeBackedFuzzyColumns`). Memoised for the life of this query (constructed per
    // snapshot). Attributes mapped to an Expression or with no mapping are skipped (no physical column).
    private val indexedColumns: Set<QualifiedName> by lazy {
        val indexedAttrs =
            snapshot.model
                .objectByQname()
                .values
                .asSequence()
                .filterIsInstance<Attribute>()
                .filter { it.search.indexed }
                .map { it.qname }
                .toSet()
        val mappingByAttr =
            snapshot.model.mappings
                .asSequence()
                .filterIsInstance<Er2DbAttributeMapping>()
                .associateBy { it.attribute }
        buildSet {
            for (attr in indexedAttrs) {
                (mappingByAttr[attr]?.target as? AttributeMappingTarget.Column)?.let { add(it.qname) }
            }
        }
    }

    // er2db lookup indexes (E-d). Value = (db qname, binding def's source file).
    private val entMappingByEntity: Map<QualifiedName, Pair<QualifiedName, String?>> by lazy {
        snapshot.model.mappings
            .filterIsInstance<Er2DbEntityMapping>()
            .associate { m -> m.entity to (mappingTargetQname(m.target) to m.sourceFile.ifEmpty { null }) }
    }

    private val attrMappingByAttr: Map<QualifiedName, Pair<QualifiedName, String?>> by lazy {
        snapshot.model.mappings
            .filterIsInstance<Er2DbAttributeMapping>()
            .mapNotNull { m ->
                (m.target as? AttributeMappingTarget.Column)?.let {
                    m.attribute to
                        (it.qname to m.sourceFile.ifEmpty { null })
                }
            }.toMap()
    }

    private val bindingPackages: List<String> by lazy {
        snapshot.model.mappings
            .mapNotNull { it.qname.`package`.ifEmpty { null } }
            .distinct()
            .sorted()
    }

    private fun mappingTargetQname(t: MappingTarget): QualifiedName =
        when (t) {
            is MappingTarget.Table -> t.qname
            is MappingTarget.View -> t.qname
            is MappingTarget.SqlQuery -> t.qname
        }

    /** Display sort key (kantheon parity): `schemaCode.namespace.name`. */
    private fun sortKey(o: ModelObject): String = "${o.qname.schemaCode}.${o.qname.namespace}.${o.qname.name}"

    /** Unique page cursor — the full qname (incl. package), so tie rows aren't skipped on resume. */
    private fun cursorKey(o: ModelObject): String =
        "${o.qname.`package`}|${o.qname.schemaCode}|${o.qname.namespace}|${o.qname.name}"

    companion object {
        const val DEFAULT_PAGE_SIZE = 100
        const val MAX_PAGE_SIZE = 1000
        const val DEFAULT_SEARCH_ALGORITHM = "all"

        // Grounding entity/table kinds (grammar 4.2 `semantics { kind: … }`), matching
        // ttr-semantics `Vocabulary.ENTITY_KINDS`. The vocabulary is a closed string set.
        private const val KIND_PERIOD_TABLE = "period_table"
        private const val KIND_POI = "poi"
        private const val KIND_FX_RATE = "fx_rate"
    }
}
