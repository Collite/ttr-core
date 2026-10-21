// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.resolve

import org.tatrman.ttr.metadata.model.DbTable
import org.tatrman.ttr.metadata.model.DbView
import org.tatrman.ttr.metadata.model.Entity
import org.tatrman.ttr.metadata.model.ModelObject
import org.tatrman.ttr.metadata.model.Query

/**
 * The physical source a model-object `load(...)` reads (AG-P0): the er→db binding resolved down to
 * something a SQL emitter can name — a db table (`namespace`.`name`), or an **inline** source whose
 * [sql] text is rendered as a derived table (a db `query` / `view` an entity binds to, e.g. the
 * `<entity>__filter` query a YAML `source: {table, where}` entity becomes).
 *
 * [columns] carry the load's **logical** row type (er attribute names for an entity, column names for
 * a table) with the physical column each one reads — the emitter scans the physical columns and
 * renames them to the logical names, so every downstream node keeps speaking the program's names.
 * Pure data, resolved once by the frontend (which owns the model index); emit never touches ttr-metadata.
 */
data class LoadSource(
    /** The loaded model object's dotted qname (`er.entity.inventory`, `db.dbo.catalog_sales`) — provenance. */
    val objectQname: String,
    /** The physical schema (`dbo`); empty for an inline [sql] source. */
    val namespace: String,
    /** The physical table name, or the inline source's name (the query/view it came from). */
    val name: String,
    /** Non-null ⇒ an inline source: this SQL text is the relation (rendered as a derived table). */
    val sql: String? = null,
    /** The source dialect of [sql] when the model declared one (`tsql`, …); null = the project default. */
    val sqlDialect: String? = null,
    val columns: List<LoadColumn>,
    /**
     * AU-core-P2: an entity no er2db binding reaches because the deploying world's automation storage holds it —
     * an automation stdlib entity (`obligation`, …) or a recipe's own verdict entity. [name] is the entity's, every
     * column reads itself: a statement over E-R names the entity, and a DB rendering names its table after it.
     */
    val unbound: Boolean = false,
    /** Resolved world storage; recorded for recipe loads (AU S2.5). */
    val storage: String? = null,
)

/** One logical column of a [LoadSource]: the program-facing [name], the [physical] column it reads, its [type]. */
data class LoadColumn(
    val name: String,
    val physical: String,
    val type: String,
)

/**
 * Resolves a loaded [ModelObject] to its [LoadSource] through the [ModelIndex] (er2db bindings, the
 * db object the binding targets). Null when the object has no physical binding reachable — the
 * checker already reported that (TTRP-RES-005), so emit simply has no physical source for it.
 */
object LoadSourceResolver {
    fun resolve(
        obj: ModelObject,
        index: ModelIndex,
        allowUnbound: Boolean? = null,
    ): LoadSource? =
        when (obj) {
            is DbTable -> tableSource(obj)
            is Entity ->
                entitySource(obj, index)
                    ?: obj
                        .takeIf {
                            index.erToDb(it.qname).dbQname == null &&
                                (allowUnbound ?: index.isAutomationStdlib(it))
                        }?.let { stdlib(it) }
            else -> null
        }

    /**
     * An entity the world's automation storage holds (AU-core-P2): [LoadSource.unbound], read by its own name
     * — [columns] its logical row (`name` → type spelling).
     */
    fun unbound(
        objectQname: String,
        name: String,
        columns: List<Pair<String, String>>,
    ): LoadSource =
        LoadSource(
            objectQname = objectQname,
            namespace = "",
            name = name,
            columns = columns.map { (n, t) -> LoadColumn(n, n, t) },
            unbound = true,
        )

    private fun stdlib(entity: Entity): LoadSource =
        unbound(
            entity.qname.dotted(),
            entity.qname.name.substringAfterLast('.'),
            entity.attributes.map { it.qname.name.substringAfterLast('.') to it.type },
        )

    private fun tableSource(table: DbTable): LoadSource =
        LoadSource(
            objectQname = table.qname.dotted(),
            namespace = table.qname.namespace,
            name = table.qname.name,
            columns =
                table.columns.map {
                    val col = it.qname.name.substringAfterLast('.')
                    LoadColumn(col, col, it.dataType)
                },
        )

    private fun entitySource(
        entity: Entity,
        index: ModelIndex,
    ): LoadSource? {
        val obj = index.bindingTarget(entity) ?: return null
        // Each bound attribute → (logical name, physical column). An unbound attribute has no column to
        // read; it is simply absent from the physical row (referencing it is the checker's RES-005).
        val columns =
            entity.attributes.mapNotNull { attr ->
                val col = index.erToDb(attr.qname).dbQname ?: return@mapNotNull null
                LoadColumn(
                    name = attr.qname.name.substringAfterLast('.'),
                    physical = col.name.substringAfterLast('.'),
                    type = attr.type,
                )
            }
        val qname = entity.qname.dotted()
        return when (obj) {
            is DbTable -> {
                // Prefer the physical column type (the scan's real type) where the table declares it.
                val physTypes = obj.columns.associate { it.qname.name.substringAfterLast('.') to it.dataType }
                LoadSource(
                    objectQname = qname,
                    namespace = obj.qname.namespace,
                    name = obj.qname.name,
                    columns = columns.map { it.copy(type = physTypes[it.physical] ?: it.type) },
                )
            }
            is Query ->
                LoadSource(
                    objectQname = qname,
                    namespace = "",
                    name = obj.qname.name,
                    sql = obj.sourceText.trim(),
                    sqlDialect = obj.dialect,
                    columns = columns,
                )
            is DbView ->
                LoadSource(
                    objectQname = qname,
                    namespace = "",
                    name = obj.qname.name,
                    sql = obj.definitionSql.trim(),
                    columns = columns,
                )
            else -> null
        }
    }
}
