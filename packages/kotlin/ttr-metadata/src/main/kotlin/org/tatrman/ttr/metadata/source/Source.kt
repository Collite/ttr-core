// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.metadata.source

import org.tatrman.ttr.metadata.model.QualifiedName
import org.tatrman.ttr.metadata.model.AreaRecord
import org.tatrman.ttr.metadata.model.Attribute
import org.tatrman.ttr.metadata.model.AttributeJoinPair
import org.tatrman.ttr.metadata.model.Cardinality
import org.tatrman.ttr.metadata.model.DbColumn
import org.tatrman.ttr.metadata.model.DbForeignKey
import org.tatrman.ttr.metadata.model.DbProcedure
import org.tatrman.ttr.metadata.model.DbTable
import org.tatrman.ttr.metadata.model.DbView
import org.tatrman.ttr.metadata.model.Entity
import org.tatrman.ttr.metadata.model.Er2CncRoleMapping
import org.tatrman.ttr.metadata.model.Er2DbAttributeMapping
import org.tatrman.ttr.metadata.model.Er2DbEntityMapping
import org.tatrman.ttr.metadata.model.Er2DbRelationMapping
import org.tatrman.ttr.metadata.model.AttributeMappingTarget
import org.tatrman.ttr.metadata.model.LocalizedText
import org.tatrman.ttr.metadata.model.LocalizedTextList
import org.tatrman.ttr.metadata.model.MappingSource
import org.tatrman.ttr.metadata.model.MappingTarget
import org.tatrman.ttr.metadata.model.Query
import org.tatrman.ttr.metadata.model.QueryParameterDef
import org.tatrman.ttr.metadata.model.Relation
import org.tatrman.ttr.metadata.model.Role
import org.tatrman.ttr.metadata.model.SearchHints
import org.tatrman.ttr.metadata.model.TableChangeSemantics
import org.tatrman.ttr.parser.diagnostics.DiagnosticCode
import org.tatrman.ttr.parser.loader.ParseError
import org.tatrman.ttr.parser.loader.ParseWarning
import org.tatrman.ttr.parser.loader.TtrLoader
import org.tatrman.ttr.parser.model.AreaDef
import org.tatrman.ttr.parser.model.ColumnDef as TtrColumnDef
import org.tatrman.ttr.parser.model.Definition
import org.tatrman.ttr.parser.model.EntityDef
import org.tatrman.ttr.parser.model.Er2CncRoleDef
import org.tatrman.ttr.parser.model.Er2DbAttributeDef
import org.tatrman.ttr.parser.model.Er2DbEntityDef
import org.tatrman.ttr.parser.model.Er2DbRelationDef
import org.tatrman.ttr.parser.model.FkDef
import org.tatrman.ttr.parser.model.LocalizedStringValue
import org.tatrman.ttr.parser.model.BindingColumnBareId
import org.tatrman.ttr.parser.model.BindingColumnObject
import org.tatrman.ttr.parser.model.BindingPropertyBareId
import org.tatrman.ttr.parser.model.BindingPropertyBlock
import org.tatrman.ttr.parser.model.PropertyValue
import org.tatrman.ttr.parser.model.QueryDef
import org.tatrman.ttr.parser.model.Reference as TtrReference
import org.tatrman.ttr.parser.model.RelationDef
import org.tatrman.ttr.parser.model.RoleDef
import org.tatrman.ttr.parser.model.TableDef
import org.tatrman.ttr.parser.model.TaggedBlockValue
import org.tatrman.ttr.parser.model.TargetObjectValue
import org.tatrman.ttr.parser.model.TargetReferenceValue
import org.tatrman.ttr.parser.model.SemanticsBlock
import org.tatrman.ttr.parser.model.SourceLocation
import org.tatrman.ttr.parser.model.ViewDef
import org.tatrman.ttr.semantics.semanticsblock.ResolvedAttributeSemantics
import org.tatrman.ttr.semantics.semanticsblock.ResolvedEntitySemantics
import org.tatrman.ttr.semantics.semanticsblock.SymbolRef
import org.tatrman.ttr.semantics.semanticsblock.ResolvedSemantics
import org.tatrman.ttr.semantics.semanticsblock.SemanticsAnalyzer
import java.nio.file.Files
import java.nio.file.Path

/**
 * One contributor of model fragments.
 *
 * Round 4 §4.C: orthogonal Storage × Parser × LiveDatabase sources. The
 * Phase 1.2 v1.2 MVP ships [FileBasedSource] backed by [DslParser] over
 * [LocalFsStorage]. Future work plugs in `GitArchiveStorage`, `S3Storage`,
 * `LiveDatabaseSource`, `YamlImportParser` without changing this surface.
 */
fun interface ModelSource {
    fun load(): SourceSnapshot
}

/**
 * One source's contribution. Reconciler merges multiple snapshots into a
 * single [org.tatrman.ttr.metadata.model.Model].
 */
data class SourceSnapshot(
    val sourceId: String,
    val priority: Int,
    val version: String,
    val tables: Map<QualifiedName, DbTable> = emptyMap(),
    val views: Map<QualifiedName, DbView> = emptyMap(),
    val procedures: Map<QualifiedName, DbProcedure> = emptyMap(),
    val foreignKeys: Map<QualifiedName, DbForeignKey> = emptyMap(),
    val entities: Map<QualifiedName, Entity> = emptyMap(),
    val relations: Map<QualifiedName, Relation> = emptyMap(),
    val mappings: List<org.tatrman.ttr.metadata.model.Mapping> = emptyList(),
    val queries: Map<QualifiedName, Query> = emptyMap(),
    /** Phase 2.2 — `cnc.role.*` objects contributed by this source. */
    val roles: Map<QualifiedName, Role> = emptyMap(),
    /** v2.2 — `def drill_map` objects (namespace `query.drill.*`). */
    val drillMaps: Map<QualifiedName, org.tatrman.ttr.metadata.model.DrillMap> = emptyMap(),
    /** Golem P4 S4.2 — `def area` blocks from `.ttrm` files, keyed by bare area name. */
    val areas: Map<String, AreaRecord> = emptyMap(),
    /** v4.1 world model (M2) — `def world` objects, keyed by world qname. */
    val worlds: Map<QualifiedName, org.tatrman.ttr.metadata.model.World> = emptyMap(),
    val warnings: List<LoadWarning> = emptyList(),
    val errors: List<LoadWarning> = emptyList(),
    /**
     * Phase 2.2 — qnames this source treats as protected. The reconciler
     * rejects any later source that attempts to redefine one of these qnames.
     * Used by the built-in stock-roles source to lock `cnc.role.fact` etc.
     */
    val protectedQnames: Set<QualifiedName> = emptySet(),
    /**
     * Per-file context collected during load. Used by the reconciler to run
     * reference resolution across all files at once (post-load pass).
     * Each entry carries the computed package (from directory path),
     * declared package (from `package` directive, may be null), imports,
     * and the raw definitions before they are turned into model objects.
     */
    val loadedFiles: List<LoadedFile> = emptyList(),
)

data class LoadWarning(
    val sourceId: String,
    val file: String,
    val line: Int,
    val column: Int,
    val message: String,
)

// =============================================================================
// Storage layer
// =============================================================================

interface ModelStorage {
    val id: String

    fun fetchVersion(): String

    fun listFiles(
        extensions: List<String>,
        prefixes: List<String> = emptyList(),
    ): List<StorageFile>

    fun read(file: StorageFile): String
}

data class StorageFile(
    val path: String,
    val sizeBytes: Long,
    val rootPath: Path? = null,
)

data class LoadedFile(
    val storageFile: StorageFile,
    val computedPackage: String,
    val declaredPackage: String?,
    val imports: List<org.tatrman.ttr.parser.model.ImportStatement>,
    val definitions: List<org.tatrman.ttr.parser.model.Definition>,
    val schemaCode: String,
    val namespace: String,
)

private fun listFilesMatching(
    rootPath: Path,
    extensions: List<String>,
    prefixes: List<String>,
): List<StorageFile> =
    Files
        .walk(rootPath)
        .filter { file ->
            Files.isRegularFile(file) &&
                extensions.any { ext -> file.fileName.toString().endsWith(".$ext") } &&
                (prefixes.isEmpty() || prefixes.any { prefix -> file.fileName.toString().startsWith(prefix) })
        }.sorted()
        .map { StorageFile(path = it.toString(), sizeBytes = Files.size(it), rootPath = rootPath) }
        .toList()

/** Read every `.ttr`/`.ttrm` file under [rootPath]. Recursive. */
class LocalFsStorage(
    override val id: String,
    private val rootPath: Path,
) : ModelStorage {
    // M1 fix: the kantheon original hashed only `.ttr`, so a change to a
    // `.ttrm`-only file (the tatrman model extension, CLAUDE.md v3.0) would not
    // bump the version and the refresher would miss it. Hash both extensions.
    override fun fetchVersion(): String =
        Files
            .walk(rootPath)
            .filter {
                Files.isRegularFile(it) &&
                    (it.fileName.toString().endsWith(".ttr") || it.fileName.toString().endsWith(".ttrm"))
            }.sorted()
            .map { "$it=${Files.getLastModifiedTime(it).toMillis()}" }
            .toList()
            .joinToString(separator = ";")
            .let { it.hashCode().toString(16) }

    override fun listFiles(
        extensions: List<String>,
        prefixes: List<String>,
    ): List<StorageFile> = listFilesMatching(rootPath, extensions, prefixes)

    override fun read(file: StorageFile): String = Files.readString(Path.of(file.path))
}

/** Read every `.ttr` resource matching [resourcePrefix] from the classpath. */
class ClasspathStorage(
    override val id: String,
    private val resourcePrefix: String,
) : ModelStorage {
    override fun fetchVersion(): String = "$resourcePrefix-classpath-static"

    override fun listFiles(
        extensions: List<String>,
        prefixes: List<String>,
    ): List<StorageFile> {
        val cl = Thread.currentThread().contextClassLoader
        val rootUrl = cl.getResource(resourcePrefix) ?: return emptyList()
        if (rootUrl.protocol != "file") return emptyList()
        return listFilesMatching(Path.of(rootUrl.toURI()), extensions, prefixes)
    }

    override fun read(file: StorageFile): String = Files.readString(Path.of(file.path))
}

// =============================================================================
// File-based source — composes Storage + DslParser
// =============================================================================

class FileBasedSource(
    private val sourceId: String,
    private val priority: Int,
    private val storage: ModelStorage,
    private val schemaCodeOverride: String? = null,
) : ModelSource {
    override fun load(): SourceSnapshot {
        val version = storage.fetchVersion()
        // Golem P4 S4.2 — area files carry a `.ttrm` extension (directive-less,
        // `def area` blocks). Walk both so they load alongside the model `.ttr` files.
        val files = storage.listFiles(listOf("ttr", "ttrm"))
        val warnings = mutableListOf<LoadWarning>()
        val errors = mutableListOf<LoadWarning>()
        val loadedFiles = mutableListOf<LoadedFile>()

        val tables = mutableMapOf<QualifiedName, DbTable>()
        val views = mutableMapOf<QualifiedName, DbView>()
        val procedures = mutableMapOf<QualifiedName, DbProcedure>()
        val foreignKeys = mutableMapOf<QualifiedName, DbForeignKey>()
        val entities = mutableMapOf<QualifiedName, Entity>()
        val relations = mutableMapOf<QualifiedName, Relation>()
        val mappings = mutableListOf<org.tatrman.ttr.metadata.model.Mapping>()
        val queries = mutableMapOf<QualifiedName, Query>()
        val roles = mutableMapOf<QualifiedName, Role>()
        val drillMaps = mutableMapOf<QualifiedName, org.tatrman.ttr.metadata.model.DrillMap>()
        val areas = mutableMapOf<String, AreaRecord>()
        val worlds = mutableMapOf<QualifiedName, org.tatrman.ttr.metadata.model.World>()

        for (file in files) {
            val content = storage.read(file)
            val pr = TtrLoader.parseString(content, fileLabel = file.path)
            errors += pr.errors.map { it.toLoadWarning(sourceId) }
            warnings += pr.warnings.map { it.toLoadWarning(sourceId) }
            if (!pr.ok) continue

            // Grounding Phase 1 (grammar 4.2) — resolve `semantics { … }` blocks
            // document-locally. `resolved` carries only diagnostics-free elements
            // (degrade, don't fail); each TTR-SEM-2xx diagnostic surfaces as a load
            // error that LoadIssue categorizes as SEMANTICS_INVALID (T5.4).
            //
            // ⛑ …every one EXCEPT the MS deprecation. `SemanticsDiagnostic` carries no severity,
            // so this loop could assume all of them were errors — true until MS-P1·S1 added
            // TTR-SEM-218, which contracts §4 rules a WARNING ("a deprecation is advice about
            // style, not a defect in the model"). Left in `errors` it would fail the load of
            // every estate still writing `nameAttribute:` — the exact behaviour change MS
            // promises silent estates will not see.
            val semanticsAnalysis = SemanticsAnalyzer.analyzeSemantics(pr.definitions)
            for (d in semanticsAnalysis.diagnostics) {
                val issue =
                    LoadWarning(
                        sourceId = sourceId,
                        file = file.path,
                        line = d.source.line,
                        column = d.source.column,
                        message = "${d.code.id}: ${d.message}",
                    )
                if (d.code == DiagnosticCode.SemLegacyMentionDeprecated) warnings += issue else errors += issue
            }
            val semanticsResolved = semanticsAnalysis.resolved

            // yaml-converter-inline-split Stage 3 / item 2: a file with no model
            // directive is directive-less — each def derives its schema+namespace from
            // its kind (see [schemaNamespaceForKind]). The published symbol table also
            // derives per-kind from an empty file schema, so LoadedFile carries "".
            // NOTE (2026-07-05, modeler 0.8.4 / grammar 4.0 qname-redesign): the AST
            // renamed `schemaDirective: SchemaDirective(schemaCode, namespace)` →
            // `modelDirective: ModelDirective(modelCode, schema)` — the directive is now
            // `model <code> (schema <id>)?`. Same semantics, new spelling.
            val directiveSchema = schemaCodeOverride ?: pr.modelDirective?.modelCode
            val directiveLess = directiveSchema == null
            val schemaCode = directiveSchema ?: ""
            val namespace =
                if (directiveLess) "" else (pr.modelDirective?.schema ?: defaultNamespaceFor(directiveSchema!!))
            val declaredPackage = pr.packageName
            val computedPackage = computePackageFromPath(file, storage)
            if (declaredPackage != null && declaredPackage != computedPackage) {
                errors +=
                    LoadWarning(
                        sourceId = sourceId,
                        file = file.path,
                        line = -1,
                        column = -1,
                        message =
                            "ttr/package-declaration-mismatch: declared '$declaredPackage' != directory computed '$computedPackage'",
                    )
            }

            // A5 Task 4 — wrong-file-kind: def-kind must belong to the declared schema.
            // The mapping kinds (er2db_*, er2cnc_role) and query are schema-pinned (always
            // map/query) and are not constrained by the file's schema directive; they are
            // excluded from this check.  See Stage 09 decision record.
            // Directive-less files (Stage 3) legitimately mix kinds (db.ttr holds tables +
            // queries; er.ttr holds entities + relations; cnc.ttr holds roles + er2cnc),
            // so the check applies only when a schema is declared.
            if (!directiveLess) {
                val schemaKindAllowlist =
                    mapOf(
                        "db" to setOf("table", "view", "procedure", "foreign_key"),
                        "er" to setOf("entity", "relation"),
                        "cnc" to setOf("role"),
                    )
                for (def in pr.definitions) {
                    val defKind = definitionKind(def)
                    val allowedKinds = schemaKindAllowlist[schemaCode.lowercase()]
                    if (allowedKinds != null && defKind !in allowedKinds) {
                        errors +=
                            LoadWarning(
                                sourceId = sourceId,
                                file = file.path,
                                line = -1,
                                column = -1,
                                message =
                                    "ttr/wrong-file-kind: '$defKind' definition not allowed in 'schema $schemaCode' file",
                            )
                    }
                }
            }
            for (def in pr.definitions) {
                // Golem P4 S4.2 — `def area` blocks live in directive-less `.ttrm` files and
                // are not model objects (no qname / schema). Intercept them before the
                // schema/namespace derivation and stash them on the snapshot's `areas` map.
                if (def is AreaDef) {
                    areas[def.name] =
                        AreaRecord(
                            def.name,
                            def.description ?: "",
                            def.descriptionLocalized.toLocalizedText(),
                            def.tags,
                            def.packages,
                        )
                    continue
                }
                // v4.1 world model (M2) — `def world` becomes typed world objects.
                if (def is org.tatrman.ttr.parser.model.WorldDef) {
                    val pkg = declaredPackage ?: computedPackage
                    val world = buildWorld(def, pkg, file.path)
                    worlds[world.qname] = world
                    continue
                }
                // Directive-less ⇒ derive (schema, namespace) per def kind; otherwise use
                // the file-level directive values for every def (legacy behaviour).
                val (defSchema, defNs) = if (directiveLess) schemaNamespaceForKind(def) else (schemaCode to namespace)
                ingestDefinition(
                    def,
                    defSchema,
                    defNs,
                    file.path,
                    tables,
                    views,
                    procedures,
                    foreignKeys,
                    entities,
                    relations,
                    mappings,
                    queries,
                    roles,
                    drillMaps,
                    semanticsResolved,
                )
            }
            loadedFiles.add(
                LoadedFile(
                    storageFile = file,
                    computedPackage = computedPackage,
                    declaredPackage = declaredPackage,
                    imports = pr.imports,
                    definitions = pr.definitions,
                    schemaCode = schemaCode,
                    namespace = namespace,
                ),
            )
        }

        return SourceSnapshot(
            sourceId = sourceId,
            priority = priority,
            version = version,
            tables = tables,
            views = views,
            procedures = procedures,
            foreignKeys = foreignKeys,
            entities = entities,
            relations = relations,
            mappings = mappings,
            queries = queries,
            roles = roles,
            drillMaps = drillMaps,
            areas = areas,
            worlds = worlds,
            warnings = warnings,
            errors = errors,
            loadedFiles = loadedFiles.toList(),
        )
    }

    /**
     * v4.1 (M2) — parser `WorldDef` → typed [org.tatrman.ttr.metadata.model.World]
     * (+ engine/executor/storage/schema children). Manifests are transported
     * verbatim (T6/MD5). Qname scheme: world = `pkg . world . <name>`; members =
     * `pkg . world . <world> . <member>` (world name as the namespace segment).
     */
    private fun buildWorld(
        def: org.tatrman.ttr.parser.model.WorldDef,
        pkg: String,
        sourceFile: String,
    ): org.tatrman.ttr.metadata.model.World {
        val worldQn = qname("world", "", def.name, pkg)

        fun memberQn(name: String) = qname("world", def.name, name, pkg)
        val engines =
            def.engines.map { e ->
                org.tatrman.ttr.metadata.model.WorldEngine(
                    internalId = idFor("engine", memberQn(e.name)),
                    qname = memberQn(e.name),
                    description = e.description ?: "",
                    descriptionLocalized = e.descriptionLocalized.toLocalizedText(),
                    tags = e.tags,
                    sourceFile = sourceFile,
                    type = e.type,
                    version = e.version,
                    extendsRef = e.extends,
                    manifest = e.manifest,
                    sourceLocation = e.source,
                )
            }
        val executors =
            def.executors.map { e ->
                org.tatrman.ttr.metadata.model.WorldExecutor(
                    internalId = idFor("executor", memberQn(e.name)),
                    qname = memberQn(e.name),
                    description = e.description ?: "",
                    descriptionLocalized = e.descriptionLocalized.toLocalizedText(),
                    tags = e.tags,
                    sourceFile = sourceFile,
                    type = e.type,
                    version = e.version,
                    extendsRef = e.extends,
                    manifest = e.manifest,
                    sourceLocation = e.source,
                )
            }
        val storages =
            def.storages.map { s ->
                val storageQn = memberQn(s.name)
                org.tatrman.ttr.metadata.model.WorldStorage(
                    internalId = idFor("storage", storageQn),
                    qname = storageQn,
                    description = s.description ?: "",
                    descriptionLocalized = s.descriptionLocalized.toLocalizedText(),
                    tags = s.tags,
                    sourceFile = sourceFile,
                    type = s.type,
                    extendsRef = s.extends,
                    via = s.via,
                    hosts = s.hosts,
                    staging = s.staging ?: false,
                    schemas =
                        s.schemas.map { sc ->
                            val scQn = qname("world", def.name, "${s.name}.${sc.name}", pkg)
                            org.tatrman.ttr.metadata.model.WorldSchemaObject(
                                internalId = idFor("worldSchema", scQn),
                                qname = scQn,
                                sourceFile = sourceFile,
                                fields = sc.fields.associate { it.name to it.type },
                                sourceLocation = sc.source,
                            )
                        },
                    manifest = s.manifest,
                    sourceLocation = s.source,
                )
            }
        return org.tatrman.ttr.metadata.model.World(
            internalId = idFor("world", worldQn),
            qname = worldQn,
            description = def.description ?: "",
            descriptionLocalized = def.descriptionLocalized.toLocalizedText(),
            tags = def.tags,
            sourceFile = sourceFile,
            extendsRef = def.extends,
            engines = engines,
            executors = executors,
            storages = storages,
            sourceLocation = def.source,
        )
    }

    private fun computePackageFromPath(
        file: StorageFile,
        storage: ModelStorage,
    ): String {
        val rootPath = file.rootPath ?: return ""
        val filePath = Path.of(file.path)
        val rootUri = rootPath.toUri()
        val fileUri = filePath.toUri()
        val relativized =
            try {
                rootUri.relativize(fileUri)
            } catch (e: Exception) {
                return ""
            }
        val pathStr = relativized.path ?: return ""
        val parts = pathStr.split("/")
        return parts.dropLast(1).joinToString(".")
    }

    private fun defaultNamespaceFor(schemaCode: String): String =
        when (schemaCode) {
            "db" -> "dbo"
            "er" -> "entity"
            "query" -> "query"
            "map" -> "map"
            else -> ""
        }

    /**
     * Directive-less schema+namespace for a definition, keyed on its kind — the
     * loader-side counterpart of the published `defaultSchemaForKind` (item 2).
     * Reproduces the per-bucket conventions the directive-ful files used, so a
     * directive-less file reloads to the same model qnames: db objects under
     * `db.dbo`, entities under `er.entity`, relations under `er.relation`, roles
     * under `cnc.role`. A `query` is a db-schema object — its inner table refs
     * resolve within a database+schema — so it lives under `db.dbo` alongside
     * table/view/procedure/foreign_key. Only the mapping kinds are schema-pinned
     * inside [ingestDefinition] (always `map`), so their pair is unused.
     */
    private fun schemaNamespaceForKind(def: Definition): Pair<String, String> =
        when (definitionKind(def)) {
            "table", "view", "procedure", "foreign_key", "query" -> "db" to "dbo"
            "entity" -> "er" to "entity"
            "relation" -> "er" to "relation"
            "role" -> "cnc" to "role"
            else -> "" to "" // mappings / drill_maps: ingest hardcodes their schema
        }

    private fun ingestDefinition(
        def: Definition,
        schemaCode: String,
        namespace: String,
        sourceFile: String,
        tables: MutableMap<QualifiedName, DbTable>,
        views: MutableMap<QualifiedName, DbView>,
        procedures: MutableMap<QualifiedName, DbProcedure>,
        foreignKeys: MutableMap<QualifiedName, DbForeignKey>,
        entities: MutableMap<QualifiedName, Entity>,
        relations: MutableMap<QualifiedName, Relation>,
        mappings: MutableList<org.tatrman.ttr.metadata.model.Mapping>,
        queries: MutableMap<QualifiedName, Query>,
        roles: MutableMap<QualifiedName, Role>,
        drillMaps: MutableMap<QualifiedName, org.tatrman.ttr.metadata.model.DrillMap>,
        semanticsResolved: Map<SourceLocation, ResolvedSemantics>,
    ) {
        when (def) {
            is TableDef -> {
                val qn = qname(schemaCode, namespace, def.name)
                tables[qn] =
                    DbTable(
                        internalId = idFor("db.table", qn),
                        qname = qn,
                        description = def.description ?: "",
                        descriptionLocalized = def.descriptionLocalized.toLocalizedText(),
                        tags = def.tags,
                        sourceFile = sourceFile,
                        primaryKey = def.primaryKey,
                        columns =
                            def.columns.map { col ->
                                val colQn = qname(schemaCode, namespace, "${def.name}.${col.name}")
                                ttrColumnToDbColumn(
                                    colQn,
                                    qn,
                                    col,
                                    sourceFile,
                                    attrSemanticsOf(col.semantics, semanticsResolved),
                                )
                            },
                        semanticsKind = entityKindOf(def.semantics, semanticsResolved),
                        mentionSemantics = mentionSemanticsOf(def.semantics, semanticsResolved),
                        managementMode = def.management,
                        changeSemantics =
                            def.changeSemantics?.let {
                                TableChangeSemantics(mode = it.mode, roleColumns = it.roles)
                            },
                    )
            }
            is ViewDef -> {
                val qn = qname(schemaCode, namespace, def.name)
                views[qn] =
                    DbView(
                        internalId = idFor("db.view", qn),
                        qname = qn,
                        description = def.description ?: "",
                        descriptionLocalized = def.descriptionLocalized.toLocalizedText(),
                        tags = def.tags,
                        sourceFile = sourceFile,
                        columns =
                            def.columns.map {
                                ttrColumnToDbColumn(
                                    qname(schemaCode, namespace, "${def.name}.${it.name}"),
                                    qn,
                                    it,
                                    sourceFile,
                                )
                            },
                        definitionSql = def.definitionSql ?: "",
                    )
            }
            is FkDef -> {
                val qn = qname(schemaCode, namespace, def.name)
                foreignKeys[qn] =
                    DbForeignKey(
                        internalId = idFor("db.fk", qn),
                        qname = qn,
                        description = def.description ?: "",
                        descriptionLocalized = def.descriptionLocalized.toLocalizedText(),
                        tags = def.tags,
                        sourceFile = sourceFile,
                        fromColumns = qnameList(def.from),
                        toColumns = qnameList(def.to),
                    )
            }
            is EntityDef -> {
                val qn = qname(schemaCode, namespace, def.name)
                val entityMention = mentionSemanticsOf(def.semantics, semanticsResolved)
                entities[qn] =
                    Entity(
                        internalId = idFor("er.entity", qn),
                        qname = qn,
                        description = def.description ?: "",
                        descriptionLocalized = def.descriptionLocalized.toLocalizedText(),
                        tags = def.tags,
                        sourceFile = sourceFile,
                        labelPlural = def.labelPlural ?: "",
                        nameAttribute = mergedMention(entityMention?.name, def.nameAttribute),
                        codeAttribute = mergedMention(entityMention?.code, def.codeAttribute),
                        aliases = def.aliases,
                        attributes =
                            def.attributes.map { a ->
                                val attrQn = qname(schemaCode, namespace, "${def.name}.${a.name}")
                                Attribute(
                                    internalId = idFor("er.attribute", attrQn),
                                    qname = attrQn,
                                    description = a.description ?: "",
                                    descriptionLocalized = a.descriptionLocalized.toLocalizedText(),
                                    tags = a.tags,
                                    sourceFile = sourceFile,
                                    entity = qn,
                                    type = a.type?.name ?: "text",
                                    isKey = a.isKey,
                                    nullable = a.optional,
                                    aggregated = a.aggregation != null,
                                    displayLabel = a.displayLabel.toLocalizedText(),
                                    valueLabels = a.valueLabels.mapValues { (_, v) -> v.toLocalizedText() },
                                    search = a.search.toSearchHints(),
                                    semantics = attrSemanticsOf(a.semantics, semanticsResolved),
                                )
                            },
                        displayLabel = def.displayLabel.toLocalizedText(),
                        search = def.search.toSearchHints(),
                        semanticsKind = entityMention?.kind,
                        mentionSemantics = entityMention,
                    )
                // `roles: [fact, dimension]` shorthand desugars to one
                // Er2CncRoleMapping per listed role. Bare names resolve to the
                // 4-part `cnc.cnc.role.<name>` (package `cnc`); qname-prefixed
                // forms (e.g. `cnc.cnc.role.fact`) pass through unchanged.
                def.roles.forEach { roleRef ->
                    val roleQn =
                        if (roleRef.path.split(".").size == 1) {
                            qname("cnc", "role", roleRef.path, "cnc")
                        } else {
                            Reference.toQname(roleRef.path, "cnc", "role")
                        }
                    val mappingQn =
                        qname(
                            "map",
                            "er2cnc_role",
                            "${def.name}__${roleQn.name}",
                        )
                    mappings +=
                        Er2CncRoleMapping(
                            internalId = idFor("map.er2cnc_role", mappingQn),
                            qname = mappingQn,
                            description = "",
                            tags = emptyList(),
                            sourceFile = sourceFile,
                            entity = qn,
                            role = roleQn,
                        )
                }
                // v2.1 — synthesise er2db_* mappings from any inline `mapping:` blocks
                // on the entity itself and on its attributes. These coexist with any
                // explicit `def er2db_*` declarations; the duplicate-mapping validator
                // catches collisions where the inline and explicit forms target the
                // same qname.
                synthesiseInlineEntityMappings(def, qn, sourceFile, mappings)
                synthesiseInlineAttributeMappings(def, qn, sourceFile, mappings)
            }
            is RelationDef -> {
                val qn = qname(schemaCode, namespace, def.name)
                relations[qn] =
                    Relation(
                        internalId = idFor("er.relation", qn),
                        qname = qn,
                        description = def.description ?: "",
                        descriptionLocalized = def.descriptionLocalized.toLocalizedText(),
                        tags = def.tags,
                        sourceFile = sourceFile,
                        fromEntity =
                            (def.from as? PropertyValue.IdValue)?.ref?.path?.let {
                                Reference.toQname(
                                    it,
                                    schemaCode,
                                    namespace,
                                )
                            }
                                ?: qn,
                        toEntity =
                            (def.to as? PropertyValue.IdValue)?.ref?.path?.let {
                                Reference.toQname(
                                    it,
                                    schemaCode,
                                    namespace,
                                )
                            }
                                ?: qn,
                        cardinality = cardinalityOf(def.cardinality),
                        // A `join:` list on a relation carries the attribute join pairs
                        // (er attribute → er attribute) that ARE the join condition; each
                        // element is `{ from: <attrRef>, to: <attrRef> }`. Endpoint refs are
                        // resolved to er qnames the same way `from`/`to` are, so a downstream
                        // `erToDb(pair.fromAttr)` resolves each side to its db column (E-d).
                        joinPairs =
                            def.join.mapNotNull { pv ->
                                val obj = pv as? PropertyValue.ObjectValue ?: return@mapNotNull null
                                val fromRef =
                                    (obj.entries["from"] as? PropertyValue.IdValue)?.ref?.path
                                        ?: return@mapNotNull null
                                val toRef =
                                    (obj.entries["to"] as? PropertyValue.IdValue)?.ref?.path
                                        ?: return@mapNotNull null
                                AttributeJoinPair(
                                    fromAttr = Reference.toQname(fromRef, schemaCode, namespace),
                                    toAttr = Reference.toQname(toRef, schemaCode, namespace),
                                )
                            },
                    )
                // v2.1 — inline `mapping: <fkRef>` or `mapping: { fk: <fkRef> }` on a
                // relation synthesises an Er2DbRelationMapping with mappingSource=Inline.
                synthesiseInlineRelationMapping(def, schemaCode, namespace, sourceFile, mappings)
            }
            is QueryDef -> {
                // A query is a db-schema object (see [schemaNamespaceForKind]): it
                // registers under the same schema+namespace as the tables it wraps
                // (db.dbo by default) so that fully-qualified `db.dbo.<name>` binding
                // references resolve to it. Was hardcoded `qname("query","query",…)`,
                // which stranded queries at UNSPECIFIED.query.<name> and broke every
                // entity→query mapping (entity_query_mapping_unresolved).
                val qn = qname(schemaCode, namespace, def.name)
                // Prefer the structured tagged block (DESIGN §10): its tag carries
                // language + dialect. Fall back to the soft-deprecated `language:`
                // property only when no tagged block is present.
                val block = def.sourceTextBlock as? TaggedBlockValue
                queries[qn] =
                    Query(
                        internalId = idFor("query", qn),
                        qname = qn,
                        description = def.description ?: "",
                        descriptionLocalized = def.descriptionLocalized.toLocalizedText(),
                        tags = def.tags,
                        sourceFile = sourceFile,
                        sourceLanguage = block?.language ?: def.language ?: "SQL",
                        sourceText = block?.value ?: def.sourceText ?: "",
                        dialect = block?.dialect,
                        search = def.search.toSearchHints(),
                        parameters =
                            def.parameters.mapNotNull { p ->
                                val obj = (p as? PropertyValue.ObjectValue)?.entries ?: return@mapNotNull null
                                QueryParameterDef(
                                    name = (obj["name"] as? PropertyValue.IdValue)?.ref?.path ?: return@mapNotNull null,
                                    type = (obj["type"] as? PropertyValue.IdValue)?.ref?.path ?: "text",
                                    label = (obj["label"] as? PropertyValue.StringValue)?.raw ?: "",
                                )
                            },
                    )
            }
            is Er2DbEntityDef -> {
                val qn = qname("binding", "er2db_entity", def.name)
                val target =
                    when (val t = def.target) {
                        is TargetObjectValue -> {
                            val entries = t.obj.entries
                            when {
                                entries.containsKey("table") ->
                                    MappingTarget.Table(refToQname(entries["table"], "db", "dbo"))
                                entries.containsKey("view") ->
                                    MappingTarget.View(refToQname(entries["view"], "db", "dbo"))
                                entries.containsKey("query") ->
                                    MappingTarget.SqlQuery(refToQname(entries["query"], "db", "dbo"))
                                else -> null
                            }
                        }
                        // v2.1: bare-ref form `target: db.dbo.T` ⇒ implicit table mapping.
                        is TargetReferenceValue ->
                            MappingTarget.Table(refToQname(t.ref, "db", "dbo"))
                        null -> null
                    } ?: return
                mappings +=
                    Er2DbEntityMapping(
                        internalId = idFor("map.er2db_entity", qn),
                        qname = qn,
                        description = def.description ?: "",
                        descriptionLocalized = def.descriptionLocalized.toLocalizedText(),
                        tags = def.tags,
                        sourceFile = sourceFile,
                        entity = def.entity?.path?.let { Reference.toQname(it, "er", "entity") } ?: return,
                        target = target,
                    )
            }
            is Er2DbAttributeDef -> {
                val qn = qname("binding", "er2db_attribute", def.name)
                val target =
                    when (val t = def.target) {
                        is TargetObjectValue -> {
                            val entries = t.obj.entries
                            when {
                                entries.containsKey("column") ->
                                    AttributeMappingTarget.Column(refToQname(entries["column"], "db", "dbo"))
                                entries.containsKey("expression") ->
                                    AttributeMappingTarget.Expression(
                                        (entries["expression"] as? PropertyValue.StringValue)?.raw ?: "",
                                    )
                                else -> null
                            }
                        }
                        // v2.1: bare-ref form `target: db.dbo.T.COL` ⇒ implicit column mapping.
                        is TargetReferenceValue ->
                            AttributeMappingTarget.Column(refToQname(t.ref, "db", "dbo"))
                        null -> null
                    } ?: return
                mappings +=
                    Er2DbAttributeMapping(
                        internalId = idFor("map.er2db_attribute", qn),
                        qname = qn,
                        description = def.description ?: "",
                        descriptionLocalized = def.descriptionLocalized.toLocalizedText(),
                        tags = def.tags,
                        sourceFile = sourceFile,
                        attribute = def.attribute?.path?.let { Reference.toQname(it, "er", "entity") } ?: return,
                        target = target,
                    )
            }
            is Er2DbRelationDef -> {
                val qn = qname("binding", "er2db_relation", def.name)
                mappings +=
                    Er2DbRelationMapping(
                        internalId = idFor("map.er2db_relation", qn),
                        qname = qn,
                        description = def.description ?: "",
                        descriptionLocalized = def.descriptionLocalized.toLocalizedText(),
                        tags = def.tags,
                        sourceFile = sourceFile,
                        relation = def.relation?.path?.let { Reference.toQname(it, "er", "entity") } ?: return,
                        foreignKey = def.fk?.path?.let { Reference.toQname(it, "db", "dbo") } ?: return,
                    )
            }
            is RoleDef -> {
                // `def role X` blocks live under `cnc.cnc.role.*` regardless of the
                // declaring file's schema directive — the conceptual layer is
                // always rooted at `cnc.cnc.role` (package + schema + namespace).
                val qn = qname("cnc", "role", def.name, "cnc")
                roles[qn] =
                    Role(
                        internalId = idFor("cnc.role", qn),
                        qname = qn,
                        description = def.description ?: "",
                        descriptionLocalized = def.descriptionLocalized.toLocalizedText(),
                        tags = def.tags,
                        sourceFile = sourceFile,
                        label = def.label.toLocalizedText(),
                        search = def.search.toSearchHints(),
                    )
            }
            is org.tatrman.ttr.parser.model.DrillMapDef -> {
                val qn = qname(schemaCode, namespace, def.name)
                // Drill-map endpoints reference queries, which live under db.dbo
                // (see [schemaNamespaceForKind]); bare refs default there too.
                val fromQn =
                    def.from?.path?.let { Reference.toQname(it, "db", "dbo") }
                        ?: return
                val toQn =
                    def.to?.path?.let { Reference.toQname(it, "db", "dbo") }
                        ?: return
                val rawDisplay = def.display.toLocalizedText()
                // OQ-03.A — when `display` is absent the loader supplies a default. We don't
                // yet have `to`'s description here (cross-package, resolved later); fall back
                // to plain "Detail" / "Detail" for now and let a later pass enrich if needed.
                val display =
                    if (rawDisplay.isEmpty) {
                        LocalizedText(byLanguage = mapOf("cs" to "Detail", "en" to "Detail"))
                    } else {
                        rawDisplay
                    }
                drillMaps[qn] =
                    org.tatrman.ttr.metadata.model.DrillMap(
                        internalId = idFor("query.drill_map", qn),
                        qname = qn,
                        description = def.description ?: "",
                        descriptionLocalized = def.descriptionLocalized.toLocalizedText(),
                        tags = def.tags,
                        sourceFile = sourceFile,
                        fromPattern = fromQn,
                        toPattern = toQn,
                        argMapping = def.args,
                        explicit = true,
                        overrideAuto = def.overrideAuto,
                        display = display,
                    )
            }
            is Er2CncRoleDef -> {
                val mappingQn = qname("map", "er2cnc_role", def.name)
                mappings +=
                    Er2CncRoleMapping(
                        internalId = idFor("map.er2cnc_role", mappingQn),
                        qname = mappingQn,
                        description = def.description ?: "",
                        descriptionLocalized = def.descriptionLocalized.toLocalizedText(),
                        tags = def.tags,
                        sourceFile = sourceFile,
                        entity =
                            def.entity?.toQualifiedName(defaultSchema = "er", defaultNamespace = "entity")
                                ?: return,
                        role =
                            def.role?.let { ref ->
                                if (ref.path.split(".").size == 1) {
                                    qname("cnc", "role", ref.path, "cnc")
                                } else {
                                    Reference.toQname(ref.path, "cnc", "role")
                                }
                            } ?: return,
                    )
            }
            else -> Unit
        }
    }

    // ----- v2.1: inline-mapping synthesisers -----

    /**
     * Materialises an entity-level inline `mapping: { target: ..., columns: { ... } }`
     * block into an Er2DbEntityMapping (and one Er2DbAttributeMapping per columns
     * entry) tagged with [MappingSource.Inline]. Qnames mirror the explicit
     * `def er2db_*` shape (no package; `map` schema; `er2db_entity` / `er2db_attribute`
     * namespace) so the duplicate-mapping validator finds collisions.
     */
    private fun synthesiseInlineEntityMappings(
        def: EntityDef,
        entityQn: QualifiedName,
        sourceFile: String,
        mappings: MutableList<org.tatrman.ttr.metadata.model.Mapping>,
    ) {
        val mapping = def.binding as? BindingPropertyBlock ?: return
        val target =
            when (val t = mapping.target) {
                is TargetObjectValue -> {
                    val entries = t.obj.entries
                    when {
                        entries.containsKey("table") ->
                            MappingTarget.Table(refToQname(entries["table"], "db", "dbo"))
                        entries.containsKey("view") ->
                            MappingTarget.View(refToQname(entries["view"], "db", "dbo"))
                        entries.containsKey("query") ->
                            MappingTarget.SqlQuery(refToQname(entries["query"], "db", "dbo"))
                        else -> null
                    }
                }
                is TargetReferenceValue ->
                    MappingTarget.Table(refToQname(t.ref, "db", "dbo"))
                null -> null
            }

        // Entity-level mapping: only synthesise if a target was provided. A pure
        // `columns: { ... }` block without a target wouldn't yield a usable entity
        // mapping; the column entries are still synthesised below.
        if (target != null) {
            val qn = qname("binding", "er2db_entity", def.name)
            mappings +=
                Er2DbEntityMapping(
                    internalId = idFor("map.er2db_entity", qn),
                    qname = qn,
                    description = "",
                    tags = emptyList(),
                    sourceFile = sourceFile,
                    mappingSource = MappingSource.Inline("entity"),
                    entity = entityQn,
                    target = target,
                )
        }

        // Each `columns: { id_artiklu: IDZBOZI, ... }` entry → one Er2DbAttributeMapping.
        // Bare column refs are canonicalised to the table-embedded qname (see
        // [canonicaliseColumnTarget]); the entity target table is the one resolved above.
        val entityTable = entityTargetTable(def)
        for (col in mapping.columns) {
            val attrTarget =
                inlineColumnToAttributeTarget(col.value)
                    ?.let { canonicaliseColumnTarget(it, entityTable) } ?: continue
            val attrQn = qname("binding", "er2db_attribute", "${def.name}.${col.name}")
            val attrEntityQn =
                qname(entityQn.schemaCode.name.lowercase(), entityQn.namespace, "${def.name}.${col.name}")
            mappings +=
                Er2DbAttributeMapping(
                    internalId = idFor("map.er2db_attribute", attrQn),
                    qname = attrQn,
                    description = "",
                    tags = emptyList(),
                    sourceFile = sourceFile,
                    mappingSource = MappingSource.Inline("entity"),
                    attribute = attrEntityQn,
                    target = attrTarget,
                )
        }
    }

    /**
     * Materialises `mapping:` on each `def attribute X { mapping: ... }` nested
     * inside an entity. Qname mirrors `<entityName>.<attrName>` to match the
     * explicit `def er2db_attribute <entity>.<attr>` convention.
     */
    private fun synthesiseInlineAttributeMappings(
        def: EntityDef,
        entityQn: QualifiedName,
        sourceFile: String,
        mappings: MutableList<org.tatrman.ttr.metadata.model.Mapping>,
    ) {
        // Resolve the entity's physical table once so bare column refs
        // (`mapping: KOD_STR`) can be canonicalised to the table-embedded qname.
        val entityTable = entityTargetTable(def)
        for (attr in def.attributes) {
            val attrMapping = attr.binding ?: continue
            val attrTarget =
                when (attrMapping) {
                    is BindingPropertyBareId ->
                        AttributeMappingTarget.Column(
                            refToQname(attrMapping.id, "db", "dbo"),
                        )
                    is BindingPropertyBlock -> {
                        when (val t = attrMapping.target) {
                            is TargetObjectValue -> {
                                val entries = t.obj.entries
                                when {
                                    entries.containsKey("column") ->
                                        AttributeMappingTarget.Column(refToQname(entries["column"], "db", "dbo"))
                                    entries.containsKey("expression") ->
                                        AttributeMappingTarget.Expression(
                                            (entries["expression"] as? PropertyValue.StringValue)?.raw ?: "",
                                        )
                                    else -> null
                                }
                            }
                            is TargetReferenceValue ->
                                AttributeMappingTarget.Column(
                                    refToQname(t.ref, "db", "dbo"),
                                )
                            null -> null
                        }
                    }
                }?.let { canonicaliseColumnTarget(it, entityTable) } ?: continue
            val attrQn = qname("binding", "er2db_attribute", "${def.name}.${attr.name}")
            val attrEntityQn =
                qname(
                    entityQn.schemaCode.name.lowercase(),
                    entityQn.namespace,
                    "${def.name}.${attr.name}",
                )
            mappings +=
                Er2DbAttributeMapping(
                    internalId = idFor("map.er2db_attribute", attrQn),
                    qname = attrQn,
                    description = "",
                    tags = emptyList(),
                    sourceFile = sourceFile,
                    mappingSource = MappingSource.Inline("attribute"),
                    attribute = attrEntityQn,
                    target = attrTarget,
                )
        }
    }

    /**
     * Materialises a relation-level `mapping: <fkRef>` (bare form) or
     * `mapping: { fk: <fkRef> }` (block form) into an Er2DbRelationMapping.
     */
    private fun synthesiseInlineRelationMapping(
        def: RelationDef,
        schemaCode: String,
        namespace: String,
        sourceFile: String,
        mappings: MutableList<org.tatrman.ttr.metadata.model.Mapping>,
    ) {
        val mapping = def.binding ?: return
        val fkRef =
            when (mapping) {
                is BindingPropertyBareId -> mapping.id
                is BindingPropertyBlock -> mapping.fk ?: return
            }
        val qn = qname("binding", "er2db_relation", def.name)
        mappings +=
            Er2DbRelationMapping(
                internalId = idFor("map.er2db_relation", qn),
                qname = qn,
                description = "",
                tags = emptyList(),
                sourceFile = sourceFile,
                mappingSource = MappingSource.Inline("relation"),
                relation = qname(schemaCode, namespace, def.name),
                foreignKey = Reference.toQname(fkRef.path, "db", "dbo"),
            )
    }

    /**
     * The physical table/view an entity maps to, or null when it maps to a
     * `query` (no single owning table) or declares no target. Used to
     * canonicalise *bare* attribute column refs — see [canonicaliseColumnTarget].
     */
    private fun entityTargetTable(def: EntityDef): QualifiedName? {
        val mapping = def.binding as? BindingPropertyBlock ?: return null
        return when (val t = mapping.target) {
            is TargetObjectValue -> {
                val entries = t.obj.entries
                when {
                    entries.containsKey("table") -> refToQname(entries["table"], "db", "dbo")
                    entries.containsKey("view") -> refToQname(entries["view"], "db", "dbo")
                    else -> null
                }
            }
            is TargetReferenceValue -> refToQname(t.ref, "db", "dbo")
            null -> null
        }
    }

    /**
     * Canonicalises an attribute's column mapping target so its qname carries the
     * owning-table segment, matching how DbColumn qnames are keyed
     * (`qname(schema, ns, "<TABLE>.<COLUMN>")`, see [ttrColumnToDbColumn] callers).
     *
     * A *bare* column ref — `mapping: KOD_STR`, which [Reference.toQname] resolves
     * table-less to `db.dbo.KOD_STR` (qname name `"KOD_STR"`, no dot) — is rewritten
     * to `db.dbo.QSTRED_DF.KOD_STR` under [entityTable]. Refs that already embed the
     * table (`db.dbo.QSTRED_DF.KOD_STR` → name `"QSTRED_DF.KOD_STR"`) and Expression
     * targets pass through unchanged; with no resolvable table the target is left as-is.
     *
     * Without this, a bare-ref column mapping points at a column qname that no
     * DbColumn / fuzzy-index / MAPS_TO edge / resolver-grouping key ever matches —
     * a dangling reference (GH #53 fixed the 4-part-path variant; this covers the
     * still-broken bare-ref form).
     */
    private fun canonicaliseColumnTarget(
        target: AttributeMappingTarget,
        entityTable: QualifiedName?,
    ): AttributeMappingTarget {
        if (target !is AttributeMappingTarget.Column) return target
        val col = target.qname
        if (entityTable == null || col.name.contains('.')) return target
        val canonical =
            col.copy(
                schemaCode = entityTable.schemaCode,
                namespace = entityTable.namespace,
                name = "${entityTable.name}.${col.name}",
                `package` = entityTable.`package`,
            )
        return AttributeMappingTarget.Column(canonical)
    }

    /**
     * Converts one entry in a `columns: { ... }` map into an
     * [AttributeMappingTarget]. Handles all three column-value shapes the walker
     * produces (bare id, wrapped `{ target: bareId }` / `{ target: { column: ... } }`,
     * and plain `{ column: ... }`).
     */
    private fun inlineColumnToAttributeTarget(
        value: org.tatrman.ttr.parser.model.BindingColumnValue,
    ): AttributeMappingTarget? =
        when (value) {
            is BindingColumnBareId ->
                AttributeMappingTarget.Column(refToQname(value.id, "db", "dbo"))
            is BindingColumnObject -> {
                val entries = value.obj.entries
                // The walker wraps form (b) `{ target: <inner> }` and form (c) the same way;
                // peel one layer of `target:` if present.
                val inner =
                    (entries["target"] as? PropertyValue.ObjectValue)?.entries
                        ?: (entries["target"] as? PropertyValue.IdValue)?.let {
                            return@let mapOf("column" to it as PropertyValue)
                        }
                        ?: entries
                when {
                    inner.containsKey("column") ->
                        AttributeMappingTarget.Column(refToQname(inner["column"], "db", "dbo"))
                    inner.containsKey("expression") ->
                        AttributeMappingTarget.Expression(
                            (inner["expression"] as? PropertyValue.StringValue)?.raw ?: "",
                        )
                    else -> null
                }
            }
        }

    private fun ttrColumnToDbColumn(
        qn: QualifiedName,
        tableQn: QualifiedName,
        col: TtrColumnDef,
        sourceFile: String,
        semantics: ResolvedAttributeSemantics? = null,
    ): DbColumn =
        DbColumn(
            internalId = idFor("db.column", qn),
            qname = qn,
            description = col.description ?: "",
            descriptionLocalized = col.descriptionLocalized.toLocalizedText(),
            tags = col.tags,
            sourceFile = sourceFile,
            table = tableQn,
            dataType = col.type?.name ?: "varchar",
            nullable = col.optional,
            isPrimaryKey = col.isKey,
            search = col.search.toSearchHints(),
            semantics = semantics,
        )

    /**
     * Grounding Phase 1 (grammar 4.2) — the resolved entity/table `kind` for [block],
     * or null when the element declares no block or the block carried diagnostics
     * (absent from `resolved` — degrade, don't fail).
     */
    private fun entityKindOf(
        block: SemanticsBlock?,
        resolved: Map<SourceLocation, ResolvedSemantics>,
    ): String? = mentionSemanticsOf(block, resolved)?.kind

    /**
     * MS (vocabulary v3) — the WHOLE resolved entity/table block for [block], or null when the
     * element declares none or the block carried diagnostics (degrade, don't fail).
     */
    private fun mentionSemanticsOf(
        block: SemanticsBlock?,
        resolved: Map<SourceLocation, ResolvedSemantics>,
    ): ResolvedEntitySemantics? = block?.let { resolved[it.source] as? ResolvedEntitySemantics }

    /**
     * contracts §1.2 / MS-D2 — one legacy mention field, merged.
     *
     * The semantics block is the new spelling of `nameAttribute:`/`codeAttribute:`, so where it
     * declares one, THAT is the answer and consumers still reading the old string field keep
     * working on a model written only in the new surface. Where it does not, the legacy property
     * keeps today's path unchanged. The two never disagree here: a disagreement is an ERROR in the
     * analyzer and degrades the block, so `declared` is null by the time this runs and the legacy
     * value survives — MS-D2's "refuse to pick a winner" falls out of the degrade rather than
     * needing a rule of its own.
     */
    private fun mergedMention(
        declared: SymbolRef?,
        legacy: TtrReference?,
    ): String = declared?.path ?: legacy?.path ?: ""

    /** Grounding Phase 1 — the resolved attribute/column semantics for [block], or null. */
    private fun attrSemanticsOf(
        block: SemanticsBlock?,
        resolved: Map<SourceLocation, ResolvedSemantics>,
    ): ResolvedAttributeSemantics? = block?.let { resolved[it.source] as? ResolvedAttributeSemantics }

    private fun qnameList(v: PropertyValue?): List<QualifiedName> =
        when (v) {
            is PropertyValue.IdValue -> listOf(Reference.toQname(v.ref.path, "db", "dbo"))
            is PropertyValue.ListValue ->
                v.items.mapNotNull {
                    (it as? PropertyValue.IdValue)?.ref?.path?.let { p ->
                        Reference.toQname(p, "db", "dbo")
                    }
                }
            else -> emptyList()
        }

    private fun refToQname(
        v: PropertyValue?,
        defaultSchema: String,
        defaultNamespace: String,
    ): QualifiedName =
        when (v) {
            is PropertyValue.IdValue -> Reference.toQname(v.ref.path, defaultSchema, defaultNamespace)
            else -> qname(defaultSchema, defaultNamespace, "<unresolved>")
        }

    // Overload for the common case where the caller already holds a TtrReference
    // (avoids wrapping it in a PropertyValue.IdValue, which now requires
    // parts + source on the published org.tatrman model).
    private fun refToQname(
        ref: TtrReference,
        defaultSchema: String,
        defaultNamespace: String,
    ): QualifiedName = Reference.toQname(ref.path, defaultSchema, defaultNamespace)

    companion object {
        // M1 de-proto: was `QualifiedName.newBuilder()…build()` (proto). Behaviour
        // preserved — unknown schema-code token folds to UNSPECIFIED.
        fun qname(
            schemaCode: String,
            namespace: String,
            name: String,
            `package`: String = "",
        ): QualifiedName =
            QualifiedName(
                schemaCode =
                    try {
                        org.tatrman.ttr.metadata.model.SchemaCode
                            .valueOf(schemaCode.uppercase())
                    } catch (e: Exception) {
                        org.tatrman.ttr.metadata.model.SchemaCode.UNSPECIFIED
                    },
                namespace = namespace,
                name = name,
                `package` = `package`,
            )

        fun idFor(
            prefix: String,
            qn: QualifiedName,
        ): String {
            val packageSegment = if (qn.`package`.isNotEmpty()) "${qn.`package`}." else ""
            return "$prefix:${packageSegment}${qn.schemaCode}.${qn.namespace}.${qn.name}"
        }
    }
}

private fun ParseError.toLoadWarning(sourceId: String): LoadWarning =
    LoadWarning(sourceId = sourceId, file = file, line = line, column = column, message = message)

private fun ParseWarning.toLoadWarning(sourceId: String): LoadWarning =
    LoadWarning(sourceId = sourceId, file = file, line = line, column = column, message = message)

private fun definitionKind(def: org.tatrman.ttr.parser.model.Definition): String =
    when (def) {
        is org.tatrman.ttr.parser.model.TableDef -> "table"
        is org.tatrman.ttr.parser.model.ViewDef -> "view"
        is org.tatrman.ttr.parser.model.ProcedureDef -> "procedure"
        is org.tatrman.ttr.parser.model.FkDef -> "foreign_key"
        is org.tatrman.ttr.parser.model.EntityDef -> "entity"
        is org.tatrman.ttr.parser.model.AttributeDef -> "attribute"
        is org.tatrman.ttr.parser.model.RelationDef -> "relation"
        is org.tatrman.ttr.parser.model.Er2DbEntityDef -> "er2db_entity_mapping"
        is org.tatrman.ttr.parser.model.Er2DbAttributeDef -> "er2db_attribute_mapping"
        is org.tatrman.ttr.parser.model.Er2DbRelationDef -> "er2db_relation_mapping"
        is org.tatrman.ttr.parser.model.RoleDef -> "role"
        is org.tatrman.ttr.parser.model.Er2CncRoleDef -> "er2cnc_role_mapping"
        is org.tatrman.ttr.parser.model.QueryDef -> "query"
        is org.tatrman.ttr.parser.model.DrillMapDef -> "drill_map"
        is org.tatrman.ttr.parser.model.ColumnDef -> "column"
        else -> "def"
    }

object Reference {
    /**
     * Best-effort qname resolution from a dotted path. The metadata service's
     * reconciler does the real work; this is the source-layer fallback.
     *
     * A 4+-part path is ambiguous and is disambiguated on whether `parts[1]` is a
     * recognised schema token (db/er/cnc/ws/obj):
     *  - **package.schema.namespace.name** — stock vocabulary, e.g. `cnc.cnc.role.master`
     *    (`parts[1] == "cnc"` is a schema token).
     *  - **schema.namespace.name** where `name` itself contains dots — e.g. the entity-attribute
     *    references `er.entity.účetní_středisko.kód_střediska` and column targets
     *    `db.dbo.QSTRED_DF.KOD_STR` (`parts[1]` is `entity`/`dbo`, not a schema token). The name is
     *    everything after the namespace, so the qname matches the attribute's/column's canonical
     *    `qname(schema, namespace, "<owner>.<member>")` — see GH #53 (these previously misparsed to
     *    `package=schema-token, schemaCode=UNSPECIFIED`, so fuzzy attributes silently lost their
     *    er2db column mapping).
     * A 3-part path is `schema.namespace.name`; 2-part and 1-part fall back to the defaults.
     */
    fun toQname(
        path: String,
        defaultSchema: String,
        defaultNamespace: String,
    ): QualifiedName {
        val parts = path.split(".")
        return when (parts.size) {
            1 -> FileBasedSource.qname(defaultSchema, defaultNamespace, parts[0])
            2 -> FileBasedSource.qname(defaultSchema, parts[0], parts[1])
            3 -> FileBasedSource.qname(parts[0], parts[1], parts[2])
            else ->
                if (org.tatrman.ttr.metadata.model
                        .parseSchemaCode(parts[1]) != null
                ) {
                    // package.schema.namespace.name (stock vocabulary)
                    FileBasedSource.qname(parts[1], parts[2], parts.drop(3).joinToString("."), parts[0])
                } else {
                    // schema.namespace.name — the name carries the remaining dotted segments
                    FileBasedSource.qname(parts[0], parts[1], parts.drop(2).joinToString("."))
                }
        }
    }
}

// ----- Phase 2.2 helpers -----

/** Convert a parser-side localised-string carrier into the model layer's. */
internal fun LocalizedStringValue?.toLocalizedText(): LocalizedText =
    this?.let { LocalizedText(byLanguage = it.byLanguage) } ?: LocalizedText.EMPTY

// ----- Search feature helpers -----

internal fun org.tatrman.ttr.parser.model.LocalizedStringListValue?.toLocalizedTextList(): LocalizedTextList =
    this?.let {
        org.tatrman.ttr.metadata.model
            .LocalizedTextList(byLanguage = it.byLanguage)
    }
        ?: org.tatrman.ttr.metadata.model.LocalizedTextList.EMPTY

/**
 * Grammar 0.12 (RV-P1.5, RV-32) + MV (member-vocabulary contracts §2.1) — the ONE boundary where a
 * `search { … }` block becomes [SearchHints.indexed] and [SearchHints.matchMethod].
 *
 * - An authored `method:` — ANY method, `EXACT` included — indexes the carrier, and rides along
 *   verbatim. Before MV only a partial method did, so `method: EXACT` had no member vocabulary.
 * - The deprecated `fuzzy: true` indexes too, as the `TYPOS(1)` grammar 0.12 maps it to (the parser
 *   model's own reading, `SearchHintsValue.fuzzy`, and `ttr-semantics`' `effectiveMatchMethod`), so
 *   the documented `fuzzy: true` → `method: TYPOS(1)` migration changes nothing downstream.
 * - An authored method wins over the deprecated boolean; `fuzzy: false` and a bare `searchable`
 *   index nothing.
 */
internal fun org.tatrman.ttr.parser.model.SearchHintsValue?.toSearchHints(): SearchHints {
    if (this == null) return org.tatrman.ttr.metadata.model.SearchHints.EMPTY
    val matchMethod = method?.toSurfaceText() ?: LEGACY_FUZZY_METHOD.takeIf { fuzzy }
    return org.tatrman.ttr.metadata.model.SearchHints(
        searchable = searchable,
        indexed = matchMethod != null,
        keywords = keywords.toLocalizedTextList(),
        patterns = patterns,
        descriptions = descriptions.toLocalizedTextList(),
        examples = examples,
        aliases = aliases,
        matchMethod = matchMethod,
        fuzzyAuthored = fuzzyAuthored,
    )
}

/** Grammar 0.12's reading of the deprecated `fuzzy: true` (`SearchHintsValue.fuzzy`'s own doc). */
private const val LEGACY_FUZZY_METHOD = "TYPOS(1)"

/** Resolve a parser-side dotted Reference to a [QualifiedName]. */
internal fun TtrReference.toQualifiedName(
    defaultSchema: String,
    defaultNamespace: String,
): QualifiedName = Reference.toQname(path, defaultSchema, defaultNamespace)

/**
 * MH — read `cardinality: { from: "0..*", to: "1" }` off a `def relation`.
 *
 * Until MH this was hardcoded to `Cardinality(0, -1, 0, -1)`: the property parsed, reached
 * [org.tatrman.ttr.parser.model.RelationDef.cardinality], and was then dropped on the floor, so
 * every loaded model claimed every relation was optional on both sides. Nothing in this repo read
 * the field, which is why it went unnoticed — MH's `reachedFrom.mandatory` is its first consumer,
 * and it is exactly the bound that distinguishes "the channel reading and the dimension reading
 * select the same rows" from "they differ".
 *
 * Bounds are the authored surface (`1` · `0..1` · `1..*` · `0..*` · `*` · `N`); `-1` is unbounded,
 * matching the previous hardcoded value. Anything unrecognised degrades to `0..*` — the widest,
 * least-claiming reading, and what a model that says nothing already meant.
 */
internal fun cardinalityOf(card: PropertyValue.ObjectValue?): Cardinality {
    val from = boundsOf(card, "from")
    val to = boundsOf(card, "to")
    return Cardinality(fromMin = from.first, fromMax = from.second, toMin = to.first, toMax = to.second)
}

private fun boundsOf(
    card: PropertyValue.ObjectValue?,
    side: String,
): Pair<Int, Int> {
    val raw =
        when (val v = card?.entries?.get(side)) {
            is PropertyValue.StringValue -> v.raw
            is PropertyValue.NumberValue -> v.raw.toInt().toString()
            is PropertyValue.IdValue -> v.ref.path
            else -> null
        }?.trim() ?: return 0 to -1

    fun bound(text: String): Int =
        if (text == "*" ||
            text.equals("N", ignoreCase = true)
        ) {
            -1
        } else {
            text.toIntOrNull() ?: -1
        }

    if (!raw.contains("..")) {
        val exact = bound(raw)
        // A bare `1` means exactly one; a bare `*`/`N` means "many", i.e. `0..*`.
        return if (exact < 0) 0 to -1 else exact to exact
    }
    val lo = bound(raw.substringBefore("..").trim())
    val hi = bound(raw.substringAfter("..").trim())
    return (if (lo < 0) 0 else lo) to hi
}
