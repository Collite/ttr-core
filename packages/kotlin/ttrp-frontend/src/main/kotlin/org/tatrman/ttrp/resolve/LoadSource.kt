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
    ): LoadSource? =
        when (obj) {
            is DbTable -> tableSource(obj)
            is Entity -> entitySource(obj, index)
            else -> null
        }

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
        val target = index.erToDb(entity.qname).dbQname ?: return null
        val byQname = index.snapshot.model.objectByQname()
        // A query binding is written `db.query.<name>` while the Query object itself is keyed under the
        // query schema code — fall back to the same-named query/view when the exact qname misses.
        val obj =
            byQname[target]
                ?: byQname.values.firstOrNull { (it is Query || it is DbView) && it.qname.name == target.name }
                ?: return null
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
