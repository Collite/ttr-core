// SPDX-License-Identifier: Apache-2.0
package org.tatrman.translator.schema

import org.tatrman.plan.v1.ColumnRef
import org.tatrman.plan.v1.Expression
import org.tatrman.plan.v1.FilterNode
import org.tatrman.plan.v1.NamedExpression
import org.tatrman.plan.v1.PlanNode
import org.tatrman.plan.v1.ProjectNode
import org.tatrman.plan.v1.QualifiedName
import org.tatrman.plan.v1.SchemaCode
import org.tatrman.plan.v1.TableScanNode
import org.tatrman.plan.v1.schemaCodeToToken
import org.tatrman.translator.framework.EntityMapping
import org.tatrman.translator.framework.ModelColumn
import org.tatrman.translator.framework.ModelHandle
import org.tatrman.translator.joiner.JoinerPlanWalker

/**
 * Phase 08 B3 — MAP_TO_PHYSICAL — Section D.
 *
 * Walks the wire-form `PlanNode` and rewrites every `ScanNode(ER, ...)` to its physical
 * equivalent via [ModelHandle.entityMapping]. After this stage runs, no `ScanNode(ER, ...)`
 * remains in the tree (the orchestrator's `unparseFromRelNode` enforces that invariant on the
 * way out).
 *
 * ## v1.0 mapping kinds
 *
 *   - `EntityMapping.ToTable(table, optionalWhere)` — happy path. The Scan becomes a
 *     `TableScanNode(DB, table)`; if the mapping carries a `whereFilter`, a `FilterNode` wraps
 *     the TableScan with that condition.
 *   - `EntityMapping.ToQuery(query)` — the entity is backed by a saved query (a parametrised
 *     view materialising, e.g., a row-filter). The `Scan(ER, entity)` is replaced by the query's
 *     parsed physical body (fetched via [ModelHandle.savedQueryBody]), recursively mapped to
 *     physical, then **projected down to the entity's declared attributes** and aliased back to
 *     the ER attribute names — the same alias-at-boundary contract `ToTable` uses, so the rest of
 *     the tree (joins/filters/projects on attribute names) is untouched. Failure modes:
 *       - backing query not found / not compiled → `entity_query_mapping_unresolved`.
 *       - the query (transitively) re-scans an entity already being expanded →
 *         `entity_query_mapping_cycle`.
 *     Parameter substitution into the backing query is **not** supported yet (saved-query
 *     parameter binding is unimplemented platform-wide — see [Unfold]); a parametrised backing
 *     query simply inlines its body without binding, which is correct only for the param-free
 *     synthetic-filter queries the YAML importer emits today.
 *   - No mapping registered — [MapToPhysicalResult.Failure] with code `entity_unmapped`.
 *
 * ## Attribute → column rename (v1.x — alias-at-boundary)
 *
 * The Scan's `output_columns` start out carrying ER attribute names. When the model declares
 * per-attribute renames via [ModelHandle.attributeColumnRenames] (sourced from
 * `ER2DB_ATTRIBUTE_MAPPING` entries on the metadata snapshot), the resulting `TableScan`'s
 * `output_columns` are rebuilt as:
 *
 *   - `name`  = DB column name (from the rename map; falls back to the ER name when the
 *               attribute has no rename, preserving the v1.0 "name = name" assumption).
 *   - `alias` = the original ER attribute name (only when it differs from `name`).
 *   - `type`  = the actual DB column's surface type, resolved from
 *               [ModelHandle.columns]. Falls back to the original Scan column's type if the
 *               DB column can't be resolved.
 *
 * **The rest of the plan tree stays unchanged.** Upstream `Filter.condition`,
 * `Project.expressions`, `Join.condition`, `Aggregate` keys, `Sort` keys — all continue to
 * reference columns by their ER attribute names. Those references resolve to the TableScan's
 * output_column aliases, so the worker emits `SELECT DB_COL AS er_attr FROM table` and
 * upstream operators read by the alias.
 *
 * This keeps the rename strictly local to the entity↔table boundary and avoids the risk of
 * naming collisions across joined tables — each TableScan owns its own alias namespace.
 *
 * Expression-target mappings (denormalised display columns from `oneof target { column,
 * expression }`) are still deferred; they don't contribute to the rename map.
 *
 * ## Idempotency
 *
 * After running, the tree contains no `ScanNode(ER, ...)`. Re-running on the result walks the
 * tree, finds no ER scans, and returns the same tree.
 *
 * ## Cardinality of error handling
 *
 * MAP_TO_PHYSICAL short-circuits on the **first** unmapped or unsupported-kind entity scan it
 * encounters and returns Failure. The orchestrator surfaces this as `ParseResult.Failure`;
 * downstream stages don't run. Future enhancement: accumulate errors and let the caller see
 * the full list — but v1 keeps it simple.
 */
object MapToPhysical {
    fun apply(
        plan: PlanNode,
        model: ModelHandle,
    ): MapToPhysicalResult =
        try {
            MapToPhysicalResult.Success(rewrite(plan, model, visited = emptySet()))
        } catch (e: MapToPhysicalException) {
            MapToPhysicalResult.Failure(e.code, e.message ?: "")
        }

    /**
     * @param visited entity qnames currently being expanded through a `ToQuery` mapping — guards
     *   against an entity whose backing query (transitively) re-scans the same entity.
     */
    private fun rewrite(
        plan: PlanNode,
        model: ModelHandle,
        visited: Set<QualifiedName>,
    ): PlanNode {
        val withChildren = JoinerPlanWalker.rewriteChildren(plan) { rewrite(it, model, visited) }
        if (withChildren.nodeCase != PlanNode.NodeCase.SCAN) return withChildren
        if (withChildren.scan.getObject().schemaCode != SchemaCode.ER) return withChildren
        return rewriteEntityScan(withChildren, model, visited)
    }

    private fun rewriteEntityScan(
        plan: PlanNode,
        model: ModelHandle,
        visited: Set<QualifiedName>,
    ): PlanNode {
        val entityQname = plan.scan.getObject()
        if (entityQname in visited) {
            throw MapToPhysicalException(
                "entity_query_mapping_cycle",
                "Cycle expanding entity ${qnameToken(entityQname)} through its backing query",
            )
        }
        val mapping =
            model.entityMapping(entityQname)
                ?: throw MapToPhysicalException(
                    "entity_unmapped",
                    "Entity ${qnameToken(entityQname)} has no physical mapping",
                )
        return when (mapping) {
            is EntityMapping.ToTable -> rewriteToTable(plan, mapping, model)
            is EntityMapping.ToQuery -> rewriteToQuery(plan, mapping, model, visited + entityQname)
        }
    }

    /**
     * Replace a query-backed entity scan with the backing query's body, projected onto the
     * entity's declared attributes. See the `ToQuery` section of the class KDoc.
     */
    private fun rewriteToQuery(
        scanPlan: PlanNode,
        mapping: EntityMapping.ToQuery,
        model: ModelHandle,
        visited: Set<QualifiedName>,
    ): PlanNode {
        val entityQname = scanPlan.scan.getObject()
        val body =
            resolveQueryBody(mapping.query, model)
                ?: throw MapToPhysicalException(
                    "entity_query_mapping_unresolved",
                    "Entity ${qnameToken(entityQname)} is mapped to query ${qnameToken(mapping.query)}," +
                        " which is not a compiled saved query in the model",
                )
        // The body may itself contain ER scans (a query-backed entity whose query joins other
        // entities). Map it to physical too; `visited` already carries this entity so a self-
        // reference is caught as a cycle rather than recursing forever.
        val physicalBody = rewrite(body, model, visited)

        // Alias-at-boundary: select, for each attribute the scan declares, the backing query's
        // output column it maps to (attributeColumnRenames; identity when unmapped), aliased back
        // to the ER attribute name so upstream operators keep resolving by attribute name. A bare
        // scan with no declared columns lets the body's natural shape through (mirrors Unfold).
        val scanCols = scanPlan.scan.outputColumnsList
        if (scanCols.isEmpty()) return physicalBody

        val renames = model.attributeColumnRenames(entityQname)
        val project = ProjectNode.newBuilder().setInput(physicalBody)
        for (erCol in scanCols) {
            val erName = erCol.name
            val sourceName = renames[erName] ?: erName
            project.addExpressions(
                NamedExpression
                    .newBuilder()
                    .setExpression(
                        Expression
                            .newBuilder()
                            .setColumnRef(ColumnRef.newBuilder().setName(sourceName).build())
                            .build(),
                    ).setAlias(erName)
                    .build(),
            )
        }
        return PlanNode.newBuilder().setProject(project).build()
    }

    /**
     * Fetch the physical body of a backing saved query, tolerant of the schema-code on the
     * reference. Saved queries are keyed in the snapshot under their own descriptor qname (the
     * `query` namespace, `schema_code` UNSPECIFIED — the `query` token isn't a real SchemaCode),
     * while an `er2db_entity` `sqlQuery` reference may carry a schema token (e.g. `db.query.x`).
     * Try the reference as-is, then with the schema stripped. Returns null when no compiled body
     * exists under either key.
     */
    private fun resolveQueryBody(
        queryQname: QualifiedName,
        model: ModelHandle,
    ): PlanNode? {
        runCatching { model.savedQueryBody(queryQname).planNode }.getOrNull()?.let { return it }
        if (queryQname.schemaCode != SchemaCode.SCHEMA_CODE_UNSPECIFIED) {
            val schemaless =
                queryQname.toBuilder().setSchemaCode(SchemaCode.SCHEMA_CODE_UNSPECIFIED).build()
            runCatching { model.savedQueryBody(schemaless).planNode }.getOrNull()?.let { return it }
        }
        return null
    }

    private fun rewriteToTable(
        scanPlan: PlanNode,
        mapping: EntityMapping.ToTable,
        model: ModelHandle,
    ): PlanNode {
        val entityQname = scanPlan.scan.getObject()
        val renames = model.attributeColumnRenames(entityQname)
        val dbColumnsByName: Map<String, ModelColumn> =
            model.columns(mapping.table).associateBy { it.name }

        val newOutputCols =
            scanPlan.scan.outputColumnsList.map { erCol ->
                rewriteOutputColumn(erCol, renames, dbColumnsByName)
            }

        val tableScan =
            TableScanNode
                .newBuilder()
                .setTable(mapping.table)
                .addAllOutputColumns(newOutputCols)
                .build()
        val tableScanPlan = PlanNode.newBuilder().setTableScan(tableScan).build()
        return if (mapping.whereFilter != null) {
            PlanNode
                .newBuilder()
                .setFilter(
                    FilterNode
                        .newBuilder()
                        .setInput(tableScanPlan)
                        .setCondition(mapping.whereFilter),
                ).build()
        } else {
            tableScanPlan
        }
    }

    /**
     * Build a [TableScan]-side [ColumnRef] from the original ER-side [erCol] and the model's
     * rename map + DB column registry. See KDoc on `MapToPhysical` for the field semantics.
     */
    private fun rewriteOutputColumn(
        erCol: ColumnRef,
        renames: Map<String, String>,
        dbColumnsByName: Map<String, ModelColumn>,
    ): ColumnRef {
        val erName = erCol.name
        val dbColName = renames[erName] ?: erName
        val dbCol = dbColumnsByName[dbColName]
        val builder =
            ColumnRef
                .newBuilder()
                .setSourceAlias(erCol.sourceAlias)
                .setName(dbColName)
        // Alias carries the ER attribute name only when it differs from the DB column name.
        // When they match (v1.0 default) the alias slot stays as it was on the ER-side Scan —
        // typically empty, since the parser didn't emit one.
        if (dbColName != erName) {
            builder.alias = erName
        } else if (erCol.alias.isNotEmpty()) {
            builder.alias = erCol.alias
        }
        // Type comes from the DB column when we can resolve it; otherwise we keep whatever the
        // ER-side Scan had (typically "unknown" at this stage of the pipeline).
        if (dbCol != null) {
            builder.type = dbCol.surfaceType.name.lowercase()
        } else if (erCol.type.isNotEmpty()) {
            builder.type = erCol.type
        }
        return builder.build()
    }

    private fun qnameToken(qn: QualifiedName): String = "${schemaCodeToToken(qn.schemaCode)}.${qn.namespace}.${qn.name}"
}

/**
 * Result of [MapToPhysical.apply]. The orchestrator converts [Failure] into
 * `ResponseMessage(severity=ERROR, code=..., context={entity: qname})` and abandons the plan.
 */
sealed interface MapToPhysicalResult {
    data class Success(
        val plan: PlanNode,
    ) : MapToPhysicalResult

    data class Failure(
        val code: String,
        val message: String,
    ) : MapToPhysicalResult
}

private class MapToPhysicalException(
    val code: String,
    message: String,
) : RuntimeException(message)
