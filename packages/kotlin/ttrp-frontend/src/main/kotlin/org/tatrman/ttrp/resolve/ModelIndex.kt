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
) {
    private val query = MetadataQuery(snapshot)
    private val objects: List<ModelObject> =
        snapshot.model
            .objectByQname()
            .values
            .toList()

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
        /** Parse `import erp.er.*` → ImportScope(pkg="erp", tier=ER). Last segment as a tier when it is a schema code. */
        fun importScope(
            qnameParts: List<String>,
            raw: String,
        ): ImportScope {
            if (qnameParts.size >= 2) {
                val last = qnameParts.last()
                val tier = parseSchemaCode(last)
                if (tier != null) {
                    return ImportScope(qnameParts.dropLast(1).joinToString("."), tier, raw)
                }
            }
            return ImportScope(qnameParts.joinToString("."), null, raw)
        }
    }
}
