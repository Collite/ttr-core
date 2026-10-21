// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.resolve

import org.tatrman.ttr.metadata.model.Attribute
import org.tatrman.ttr.metadata.model.DbColumn
import org.tatrman.ttr.metadata.model.DbTable
import org.tatrman.ttr.metadata.model.Entity
import org.tatrman.ttr.metadata.model.ModelObject
import org.tatrman.ttr.metadata.model.QualifiedName
import org.tatrman.ttr.metadata.model.Relation
import org.tatrman.ttr.metadata.model.RowSchemaRecord
import org.tatrman.ttr.metadata.model.SchemaCode
import org.tatrman.ttr.metadata.model.parseSchemaCode
import org.tatrman.ttr.metadata.query.ErBindingResult
import org.tatrman.ttr.metadata.query.MetadataQuery
import org.tatrman.ttr.metadata.registry.RegistrySnapshot

/** A resolved `import <pkg>(.<tier>)?.*` scope entry (D-b). */
data class ImportScope(
    val pkg: String,
    val tier: SchemaCode?,
    val raw: String,
)

/**
 * A read-only façade over a ttr-metadata [RegistrySnapshot] for TTR-P resolution
 * (D-a sub-1: the qname path/package derives the tier — no kind sigils). Everything
 * goes THROUGH ttr-metadata; ttrp-frontend never parses `.ttrm` directly.
 */
class ModelIndex(
    val snapshot: RegistrySnapshot,
    private val packageOf: (ModelObject) -> String = { it.qname.`package` },
) {
    private val query = MetadataQuery(snapshot)
    val objectsByQname = snapshot.model.objectByQname()
    private val objects: List<ModelObject> = objectsByQname.values.toList()
    private val objectsByDotted = objects.associateBy { it.qname.dotted() }

    fun objectNamed(qname: String): ModelObject? = objectsByDotted[qname]

    /** Loader-owned package identity, never inferred from the checkout's absolute path. */
    fun packageOf(obj: ModelObject): String = packageOf.invoke(obj)

    /** Query bindings use db.query while query objects use their schema code. */
    fun bindingTarget(obj: ModelObject): ModelObject? {
        val target = erToDb(obj.qname).dbQname ?: return null
        return objectsByQname[target] ?: objects.firstOrNull {
            (it is org.tatrman.ttr.metadata.model.Query || it is org.tatrman.ttr.metadata.model.DbView) &&
                it.qname.name == target.name
        }
    }

    /**
     * True iff some model object lives in package [pkg] (dir-path convention: `.` → `/`).
     *
     * review-001 1.3-C: db/er model objects are matched to a package by their SOURCE-FILE
     * path, not `qname.package`. This is deliberate and currently unavoidable — ttr-metadata
     * populates `qname.package` only for WORLD objects; for db tables and er entities it is
     * empty (verified against the shared fixture). The path segment `/<pkg>/` is slash-
     * delimited so the match is boundary-safe (`/erp/` never matches `/erproot/`). What is
     * NOT settled is the wildcard semantics: `import erp.*` (tier=null) currently reaches the
     * `erp.er` sub-package (its file lives under `/erp/er/`), which the RES-002 ambiguity
     * fixture relies on. Whether a bare-package wildcard should include tier sub-packages is a
     * D-b decision for Stage 2.1; the precise fix also needs an UPSTREAM ttr-metadata change to
     * populate `qname.package` for db/er objects. Flagged in tasks-overview.md §Blockers.
     */
    fun packageExists(pkg: String): Boolean =
        objects.any { pathInPackage(it, pkg) } || rowSchemas.any { fileInPackage(it.sourceFile, pkg) }

    /** Segment-boundary path match: object's source file lies under the `/<pkg>/` directory. */
    private fun pathInPackage(
        obj: ModelObject,
        pkg: String,
    ): Boolean = fileInPackage(obj.sourceFile, pkg)

    private fun fileInPackage(
        sourceFile: String,
        pkg: String,
    ): Boolean = sourceFile.contains("/" + pkg.replace('.', '/') + "/")

    /** Grammar 0.14 `def schema` row schemas (not model objects — carried on the model beside them). */
    private val rowSchemas: List<RowSchemaRecord> =
        snapshot.model.rowSchemas.values
            .toList()

    /**
     * Row schemas named [name] that an import brings into scope (action displays, grammar 0.14). Scoped the
     * way loadable objects are — the declaring file lies under an imported package's directory (so a
     * wildcard reaches sub-packages, the entity rule) — and tier-neutral: a bare `import <pkg>.*` or a
     * `db`-tier one reaches them. More than one hit is an ambiguity the caller reports (no first-wins).
     */
    fun findRowSchemas(
        name: String,
        imports: List<ImportScope>,
    ): List<RowSchemaRecord> =
        rowSchemas
            .filter { rs ->
                rs.name == name &&
                    imports.any { imp ->
                        (imp.tier == null || imp.tier == SchemaCode.DB) &&
                            (fileInPackage(rs.sourceFile, imp.pkg) || rs.pkg == imp.pkg)
                    }
            }.sortedBy { it.qualifiedName }

    /** Every row schema an import brings into scope (the [findRowSchemas] rule, any name), by qualified name. */
    fun rowSchemasInScope(imports: List<ImportScope>): List<RowSchemaRecord> =
        rowSchemas
            .filter { rs ->
                imports.any { imp ->
                    (imp.tier == null || imp.tier == SchemaCode.DB) &&
                        (fileInPackage(rs.sourceFile, imp.pkg) || rs.pkg == imp.pkg)
                }
            }.sortedBy { it.qualifiedName }

    private fun inScope(
        obj: ModelObject,
        imports: List<ImportScope>,
    ): Boolean =
        imports.any { imp ->
            pathInPackage(obj, imp.pkg) &&
                (imp.tier == null || obj.qname.schemaCode == imp.tier)
        }

    /** Loadable model objects (db table / er entity) with the given simple name in import scope (D-b-iii). */
    fun findLoadable(
        name: String,
        imports: List<ImportScope>,
    ): List<ModelObject> =
        objects.filter {
            (it is DbTable || it is Entity) && it.qname.name == name && inScope(it, imports)
        }

    /**
     * Loadable objects matching [name] case-INSENSITIVELY in scope — used to DETECT a
     * same-name clash (C2-d/D-b no-first-wins), e.g. er entity `sales_txn` vs db table
     * `SALES_TXN` both reachable under an all-tier import → the caller must qualify.
     */
    fun findLoadableCi(
        name: String,
        imports: List<ImportScope>,
    ): List<ModelObject> =
        objects.filter {
            (it is DbTable || it is Entity) && it.qname.name.equals(name, ignoreCase = true) && inScope(it, imports)
        }

    /** A full-qname load like `erp.accounts` → the object named [name] in package [pkg] (imports not required). */
    fun findByPackage(
        pkg: String,
        name: String,
    ): List<ModelObject> =
        objects.filter {
            (it is DbTable || it is Entity) && it.qname.name == name && pathInPackage(it, pkg)
        }

    /** Entities in import scope by simple name (used to resolve a load's underlying entity). */
    fun findEntity(
        name: String,
        imports: List<ImportScope>,
    ): Entity? = objects.filterIsInstance<Entity>().firstOrNull { it.qname.name == name && inScope(it, imports) }

    /** Every er entity named [name], whatever its package (the recipe key's entity — `key:` names no import). */
    fun entitiesNamed(name: String): List<Entity> = objects.filterIsInstance<Entity>().filter { it.qname.name == name }

    /** Every er entity named [name] the imports bring into scope (an `er.entity.<name>` load). */
    fun findEntities(
        name: String,
        imports: List<ImportScope>,
    ): List<Entity> = objects.filterIsInstance<Entity>().filter { it.qname.name == name && inScope(it, imports) }

    /**
     * The automation stdlib's entity named [name] (`obligation`, `state_history`, `approvals` — ttr-metadata adds
     * them, package `automation`, to a project that declares `model automation`). Recipes read them without an
     * import, as a prelude; a project's own entity of the name, when imported, is found first.
     */
    fun findStdlibEntities(name: String): List<Entity> =
        objects.filterIsInstance<Entity>().filter { it.qname.name == name && isAutomationStdlib(it) }

    /** True for an object of the automation stdlib (its synthetic source root, ttr-metadata `stdlibSnapshot`). */
    fun isAutomationStdlib(obj: ModelObject): Boolean = obj.sourceFile.startsWith(STDLIB_ROOT)

    /**
     * The automation objects (`def parameter · action · service · role · approval · program`) the imports bring into
     * scope — a recipe's `import <pkg>.automation.*` (AU-core-P1). Package by source path, as for loadable objects.
     */
    fun automationInScope(imports: List<ImportScope>): List<org.tatrman.ttr.metadata.model.AutomationObject> =
        objects
            .filterIsInstance<org.tatrman.ttr.metadata.model.AutomationObject>()
            .filter { o -> imports.any { imp -> pathInPackage(o, imp.pkg) } }

    /** Every automation object of the model, imported or not — a `def program` names its recipe, not the reverse. */
    fun automationAll(): List<org.tatrman.ttr.metadata.model.AutomationObject> =
        objects.filterIsInstance<org.tatrman.ttr.metadata.model.AutomationObject>()

    /**
     * The er entity named [name] as a recipe sees it (AU-core-P1): the imports' first, then the automation stdlib's
     * (a prelude). Never another package's entity of the same name.
     */
    fun entityInScope(
        name: String,
        imports: List<ImportScope>,
    ): Entity? = findEntities(name, imports).firstOrNull() ?: findStdlibEntities(name).firstOrNull()

    /** The calendars of the model — entities with an attribute whose `semantics { role: event_date }` (D-82). */
    fun calendars(): List<Entity> = objects.filterIsInstance<Entity>().filter { isCalendar(it) }

    /** Every db foreign key (`def fk`): an sk's road to the calendar when a relation carries no `join:` pairs. */
    fun foreignKeys(): List<org.tatrman.ttr.metadata.model.DbForeignKey> =
        objects.filterIsInstance<org.tatrman.ttr.metadata.model.DbForeignKey>()

    /** Every relation of the model. */
    fun allRelations(): List<Relation> = objects.filterIsInstance<Relation>()

    /** Every relation with [entity] at either end (AU-core-P1: an sk's road to the calendar). */
    fun relationsOf(entity: Entity): List<Relation> =
        objects.filterIsInstance<Relation>().filter {
            it.fromEntity.name == entity.qname.name || it.toEntity.name == entity.qname.name
        }

    /** True for the calendar — an entity with an attribute whose `semantics { role: event_date }` (D-82). */
    fun isCalendar(entity: Entity): Boolean = entity.attributes.any { it.semantics?.role == "event_date" }

    /** Relations in import scope by simple name. */
    fun findRelations(
        name: String,
        imports: List<ImportScope>,
    ): List<Relation> = objects.filterIsInstance<Relation>().filter { it.qname.name == name && inScope(it, imports) }

    fun tableColumns(table: DbTable): List<Pair<String, String>> =
        table.columns.map { it.qname.name.substringAfterLast('.') to it.dataType }

    fun entityAttributes(entity: Entity): List<Pair<String, String>> =
        entity.attributes.map { it.qname.name.substringAfterLast('.') to it.type }

    fun erToDb(qname: QualifiedName): ErBindingResult = query.erToDb(qname)

    /** All db columns of a table (for join-condition / column typing). */
    fun columnsOf(table: DbTable): List<DbColumn> = table.columns

    fun attributesOf(entity: Entity): List<Attribute> = entity.attributes

    companion object {
        /** Where ttr-metadata roots the automation stdlib's one source file. */
        private const val STDLIB_ROOT = "/automation-stdlib/"

        /**
         * Parse `import erp.er.*` → ImportScope(pkg="erp", tier=ER). Last segment as a tier when it is a schema code —
         * except `automation`: a recipe's `import hartland.automation.*` names the PACKAGE that holds the actions and
         * parameters (and an `x.automation` package's ER objects and row schemas must stay in scope); the automation
         * tier is never imported by its code (AU-core-P0 review-114 B1).
         */
        fun importScope(
            qnameParts: List<String>,
            raw: String,
        ): ImportScope {
            if (qnameParts.size >= 2) {
                val last = qnameParts.last()
                // Nor `cn` (grammar 0.17): the conceptual tier is not a TTR-P import scope; `x.cn` stays a package.
                val tier = parseSchemaCode(last)?.takeUnless { it == SchemaCode.AUTOMATION || it == SchemaCode.CN }
                if (tier != null) {
                    return ImportScope(qnameParts.dropLast(1).joinToString("."), tier, raw)
                }
            }
            return ImportScope(qnameParts.joinToString("."), null, raw)
        }
    }
}
