// SPDX-License-Identifier: Apache-2.0
package org.tatrman.translator.wire

import org.tatrman.plan.v1.AggregateNode
import org.tatrman.plan.v1.FilterNode
import org.tatrman.plan.v1.JoinNode
import org.tatrman.plan.v1.JoinType
import org.tatrman.plan.v1.LimitOffsetNode
import org.tatrman.plan.v1.NamedExpression
import org.tatrman.plan.v1.PlanNode
import org.tatrman.plan.v1.ProjectNode
import org.tatrman.plan.v1.QualifiedName
import org.tatrman.plan.v1.Row
import org.tatrman.plan.v1.SortKey
import org.tatrman.plan.v1.SortNode
import org.tatrman.plan.v1.ScanNode
import org.tatrman.plan.v1.TableHint
import org.tatrman.plan.v1.TableScanNode
import org.tatrman.plan.v1.UnionNode
import org.tatrman.plan.v1.ValuesNode
import org.tatrman.plan.v1.parseSchemaCode
import org.tatrman.translator.codec.sql.TableHintSpec
import org.apache.calcite.rel.RelNode
import org.apache.calcite.rel.core.Aggregate
import org.apache.calcite.rel.core.AggregateCall
import org.apache.calcite.rel.core.Filter
import org.apache.calcite.rel.core.Join
import org.apache.calcite.rel.core.JoinRelType
import org.apache.calcite.rel.core.Project
import org.apache.calcite.rel.core.Sort
import org.apache.calcite.rel.core.TableScan
import org.apache.calcite.rel.core.Union
import org.apache.calcite.rel.core.Values
import org.apache.calcite.rex.RexLiteral
import org.apache.calcite.sql.SqlKind

/**
 * Encode a Calcite [RelNode] into the v1 proto [PlanNode] wire format.
 *
 * Covers the full v1 RelOp subset: TableScan, Project, Filter, Join,
 * Aggregate, Sort, LimitOffset, Values. SubqueryNode is reached transparently
 * via input recursion when the Translator wraps a subplan.
 *
 * Anything outside the v1 subset throws [UnsupportedOperationException] with
 * the offending RelNode class named — the wire format must stay clean.
 */
object PlanNodeEncoder {
/**
     * Encode the rel without parameter-name restoration. `RexDynamicParam`s emit their positional
     * shape (`?N`); this is the call shape Calcite-only callers (no orchestrator yet) use.
     */
    fun encode(rel: RelNode): PlanNode = encode(rel, parameterNames = emptyMap())

    /**
     * Phase 08 A2 — encode with the original parameter names restored on `RexDynamicParam` nodes.
     * The orchestrator (Section J) supplies `parameterNames` from
     * [org.tatrman.translator.params.PreparedSql.parameterOrder] (`index -> name`). Empty map → same
     * behaviour as the single-arg overload.
     *
     * The three-level SchemaPlus tree ensures Calcite always provides a 3-part qualified name
     * `<schema>.<namespace>.<name>`, so no namespace-recovery workaround is needed.
     */
    fun encode(
        rel: RelNode,
        parameterNames: Map<Int, String> = emptyMap(),
    ): PlanNode =
        when (rel) {
            is TableScan -> encodeTableScan(rel)
            is Project -> encodeProject(rel, parameterNames)
            is Filter -> encodeFilter(rel, parameterNames)
            is Join -> encodeJoin(rel, parameterNames)
            is Aggregate -> encodeAggregate(rel, parameterNames)
            is Sort -> encodeSort(rel, parameterNames)
            is Values -> encodeValues(rel)
            is Union -> encodeUnion(rel, parameterNames)
            else -> throw UnsupportedOperationException(
                "RelOp '${rel.javaClass.simpleName}' is not in the v1 wire format",
            )
        }

    /**
     * NX-A.S4 — encode, then reattach T-SQL table hints (decision D9). `hintsByTable` maps a
     * lowercased table name (last dotted segment, e.g. `mu`) to the hints extracted from the source
     * SQL by [org.tatrman.translator.codec.sql.TableHintExtractor]; [stampHints] walks the encoded
     * tree and adds the matching proto [TableHint]s onto every `TableScanNode`/`ScanNode` whose name
     * matches. Empty map → identical output to the two-arg overload.
     */
    fun encode(
        rel: RelNode,
        parameterNames: Map<Int, String>,
        hintsByTable: Map<String, List<TableHintSpec>>,
    ): PlanNode {
        val plan = encode(rel, parameterNames)
        return if (hintsByTable.isEmpty()) plan else stampHints(plan, hintsByTable)
    }

    private fun protoHintsFor(
        name: String,
        hintsByTable: Map<String, List<TableHintSpec>>,
    ): List<TableHint> =
        hintsByTable[name.substringAfterLast('.').lowercase()].orEmpty().map { spec ->
            TableHint
                .newBuilder()
                .setName(spec.name)
                .addAllOptions(spec.options)
                .build()
        }

    /**
     * Walk the encoded wire tree and add the matching [TableHint]s onto each `TableScanNode` /
     * `ScanNode` leaf whose table name is in [hintsByTable]. Pure: proto messages are immutable, so
     * every touched node is rebuilt via `toBuilder()`; leaves with no matching hint are returned
     * unchanged.
     */
    private fun stampHints(
        plan: PlanNode,
        hintsByTable: Map<String, List<TableHintSpec>>,
    ): PlanNode =
        when (plan.nodeCase) {
            PlanNode.NodeCase.TABLE_SCAN -> {
                val hints = protoHintsFor(plan.tableScan.table.name, hintsByTable)
                if (hints.isEmpty()) {
                    plan
                } else {
                    PlanNode
                        .newBuilder()
                        .setTableScan(
                            plan.tableScan.toBuilder().addAllHints(hints),
                        ).build()
                }
            }
            PlanNode.NodeCase.SCAN -> {
                val hints = protoHintsFor(plan.scan.getObject().name, hintsByTable)
                if (hints.isEmpty()) {
                    plan
                } else {
                    PlanNode
                        .newBuilder()
                        .setScan(
                            plan.scan.toBuilder().addAllHints(hints),
                        ).build()
                }
            }
            PlanNode.NodeCase.PROJECT ->
                PlanNode
                    .newBuilder()
                    .setProject(
                        plan.project.toBuilder().setInput(stampHints(plan.project.input, hintsByTable)),
                    ).build()
            PlanNode.NodeCase.FILTER ->
                PlanNode
                    .newBuilder()
                    .setFilter(
                        plan.filter.toBuilder().setInput(stampHints(plan.filter.input, hintsByTable)),
                    ).build()
            PlanNode.NodeCase.JOIN ->
                PlanNode
                    .newBuilder()
                    .setJoin(
                        plan.join
                            .toBuilder()
                            .setLeft(stampHints(plan.join.left, hintsByTable))
                            .setRight(stampHints(plan.join.right, hintsByTable)),
                    ).build()
            PlanNode.NodeCase.AGGREGATE ->
                PlanNode
                    .newBuilder()
                    .setAggregate(
                        plan.aggregate.toBuilder().setInput(stampHints(plan.aggregate.input, hintsByTable)),
                    ).build()
            PlanNode.NodeCase.SORT ->
                PlanNode
                    .newBuilder()
                    .setSort(
                        plan.sort.toBuilder().setInput(stampHints(plan.sort.input, hintsByTable)),
                    ).build()
            PlanNode.NodeCase.LIMIT_OFFSET ->
                PlanNode
                    .newBuilder()
                    .setLimitOffset(
                        plan.limitOffset.toBuilder().setInput(stampHints(plan.limitOffset.input, hintsByTable)),
                    ).build()
            PlanNode.NodeCase.SUBQUERY ->
                PlanNode
                    .newBuilder()
                    .setSubquery(
                        plan.subquery.toBuilder().setSubquery(stampHints(plan.subquery.subquery, hintsByTable)),
                    ).build()
            PlanNode.NodeCase.UNION ->
                PlanNode
                    .newBuilder()
                    .setUnion(
                        plan.union.toBuilder().clearInputs().addAllInputs(
                            plan.union.inputsList.map { stampHints(it, hintsByTable) },
                        ),
                    ).build()
            // Hints on scans under a Store's RHS SELECT must be stamped too; the `else` would drop them.
            PlanNode.NodeCase.STORE ->
                PlanNode
                    .newBuilder()
                    .setStore(
                        plan.store.toBuilder().setInput(stampHints(plan.store.input, hintsByTable)),
                    ).build()
            else -> plan
        }

    private fun encodeTableScan(rel: TableScan): PlanNode {
        val path = rel.table!!.qualifiedName
        require(path.size == 3) {
            "Expected 3-part qualified name <schema>.<namespace>.<name>, got: $path"
        }

        val schemaCode =
            parseSchemaCode(path[0])
                ?: error("Unknown schema code '${path[0]}' in qualified name $path")
        val namespace = path[1]
        val tableName = path[2]
        val qname =
            QualifiedName
                .newBuilder()
                .setSchemaCode(schemaCode)
                .setNamespace(namespace)
                .setName(tableName)
                .build()

        val outputCols =
            rel.rowType.fieldList.map { f ->
                org.tatrman.plan.v1.ColumnRef
                    .newBuilder()
                    .setName(f.name)
                    .setType(Expressions.surfaceTypeOf(f.type))
                    .build()
            }

        val builder = PlanNode.newBuilder()
        if (schemaCode == org.tatrman.plan.v1.SchemaCode.DB) {
            builder.setTableScan(TableScanNode.newBuilder().setTable(qname).addAllOutputColumns(outputCols))
        } else {
            builder.setScan(ScanNode.newBuilder().setObject(qname).addAllOutputColumns(outputCols))
        }
        return builder.build()
    }

    private fun encodeProject(
        rel: Project,
        parameterNames: Map<Int, String>,
    ): PlanNode {
        val builder = ProjectNode.newBuilder().setInput(encode(rel.input, parameterNames))
        val fieldNames =
            rel.input.rowType.fieldList
                .map { it.name }
        val ctx =
            Expressions.ResolveContext(
                fieldNames = fieldNames,
                parameterNames = parameterNames,
            )
        rel.projects.forEachIndexed { idx, expr ->
            builder.addExpressions(
                NamedExpression
                    .newBuilder()
                    .setExpression(Expressions.encode(expr, ctx))
                    .setAlias(rel.rowType.fieldList[idx].name),
            )
        }
        return PlanNode.newBuilder().setProject(builder).build()
    }

    private fun encodeFilter(
        rel: Filter,
        parameterNames: Map<Int, String>,
    ): PlanNode {
        val fieldNames =
            rel.input.rowType.fieldList
                .map { it.name }
        val ctx =
            Expressions.ResolveContext(
                fieldNames = fieldNames,
                parameterNames = parameterNames,
            )
        return PlanNode
            .newBuilder()
            .setFilter(
                FilterNode
                    .newBuilder()
                    .setInput(encode(rel.input, parameterNames))
                    .setCondition(Expressions.encode(rel.condition, ctx)),
            ).build()
    }

    private fun encodeJoin(
        rel: Join,
        parameterNames: Map<Int, String>,
    ): PlanNode {
        val joinType =
            when (rel.joinType) {
                JoinRelType.INNER -> JoinType.INNER
                JoinRelType.LEFT -> JoinType.LEFT
                JoinRelType.RIGHT -> JoinType.RIGHT
                JoinRelType.FULL -> JoinType.FULL
                // NX-A: existence tests. SEMI keeps left rows that match (EXISTS/IN);
                // ANTI keeps left rows with no match (NOT EXISTS/NOT IN).
                JoinRelType.SEMI -> JoinType.SEMI
                JoinRelType.ANTI -> JoinType.ANTI
                else -> throw UnsupportedOperationException(
                    "Join type '${rel.joinType}' is not in the v1 wire format",
                )
            }
        val builder =
            JoinNode
                .newBuilder()
                .setLeft(encode(rel.left, parameterNames))
                .setRight(encode(rel.right, parameterNames))
                .setJoinType(joinType)
        if (!rel.condition.isAlwaysTrue) {
            // Phase 08 A1 — RESOLVE. Condition is over the combined row type; encode RexInputRefs
            // with the true field name plus a `$L`/`$R` source-alias hint so the decoder can
            // route the lookup into the correct join input (RelBuilder.field(2, ord, name)).
            // NX-A: the condition indexes into left++right, which for INNER/LEFT/RIGHT/FULL equals
            // `rel.rowType`, but for SEMI/ANTI `rel.rowType` is the LEFT input only — so a right-side
            // ref would fall outside `fieldNames` and lose its `$R` hint. Build `combined` from the
            // two inputs directly so it always spans the full condition index space.
            val combined =
                rel.left.rowType.fieldList
                    .map { it.name } +
                    rel.right.rowType.fieldList
                        .map { it.name }
            val ctx =
                Expressions.ResolveContext(
                    fieldNames = combined,
                    joinSplit = rel.left.rowType.fieldCount,
                    parameterNames = parameterNames,
                    // Original per-input names so a `$R` ref to a name Calcite
                    // uniquified in `combined` (e.g. IDSTRED→IDSTRED0) still decodes
                    // against the right input, where it's the un-suffixed name.
                    leftFieldNames =
                        rel.left.rowType.fieldList
                            .map { it.name },
                    rightFieldNames =
                        rel.right.rowType.fieldList
                            .map { it.name },
                )
            builder.setCondition(Expressions.encode(rel.condition, ctx))
        }
        return PlanNode.newBuilder().setJoin(builder).build()
    }

    private fun encodeUnion(
        rel: Union,
        parameterNames: Map<Int, String>,
    ): PlanNode {
        // All inputs share the output row type, so no per-input resolve context is
        // needed here — each child encodes independently and the decoder rebuilds
        // the set-op via RelBuilder.union(all, n).
        val builder = UnionNode.newBuilder().setAll(rel.all)
        rel.inputs.forEach { builder.addInputs(encode(it, parameterNames)) }
        return PlanNode.newBuilder().setUnion(builder).build()
    }

    private fun encodeAggregate(
        rel: Aggregate,
        parameterNames: Map<Int, String>,
    ): PlanNode {
        // TF-P1.S3 (G A6, C7; contracts §2) — the v1 AggregateNode has one flat group-key list and no
        // per-call filter, so ROLLUP/CUBE/GROUPING SETS and FILTER (WHERE …) used to be dropped
        // silently: the query still ran, with a different meaning. Refuse them instead
        // (`parse_pipeline_failed` through `runFrontHalfStages`).
        if (rel.groupSets.size > 1) {
            throw UnsupportedOperationException(
                "GROUP BY ROLLUP/CUBE/GROUPING SETS is not in the v1 wire format (aggregate has ${rel.groupSets.size} group sets)",
            )
        }
        val builder = AggregateNode.newBuilder().setInput(encode(rel.input, parameterNames))
        // Group keys are represented as positional column refs into the input.
        rel.groupSet.forEach { idx ->
            val field = rel.input.rowType.fieldList[idx]
            builder.addGroupKeys(
                org.tatrman.plan.v1.ColumnRef
                    .newBuilder()
                    .setName(field.name)
                    .setType(Expressions.surfaceTypeOf(field.type)),
            )
        }
        rel.aggCallList.forEachIndexed { idx, call ->
            if (call.filterArg >= 0) {
                throw UnsupportedOperationException("Aggregate FILTER (WHERE …) is not in the v1 wire format")
            }
            val outName = rel.rowType.fieldList[rel.groupSet.cardinality() + idx].name
            val agg =
                org.tatrman.plan.v1.AggregateCall
                    .newBuilder()
                    .setFunction(call.aggregation.name.lowercase())
                    .setDistinct(call.isDistinct)
                    .setAlias(outName)
            if (call.aggregation.kind == SqlKind.LISTAGG) {
                encodeListagg(rel, call, agg)
            } else {
                call.argList.forEach { argIdx -> agg.addArgs(inputColumnRef(rel, argIdx)) }
            }
            builder.addAggregates(agg)
        }
        return PlanNode.newBuilder().setAggregate(builder).build()
    }

    /**
     * TF-P1.S3 (G C6; contracts §5.4) — `LISTAGG(value, sep) WITHIN GROUP (ORDER BY …)`, which is what
     * Calcite makes of T-SQL `STRING_AGG`. `args` carries only the value column; the separator rides
     * as a literal on `separator` (Calcite projects it into the aggregate's input as a constant
     * column, so it is read back from that `Project`), and the order on `within_group`, encoded like
     * a [SortNode] key.
     */
    private fun encodeListagg(
        rel: Aggregate,
        call: AggregateCall,
        agg: org.tatrman.plan.v1.AggregateCall.Builder,
    ) {
        agg.addArgs(inputColumnRef(rel, call.argList[0]))
        if (call.argList.size > 1) {
            val separator =
                (rel.input as? Project)?.projects?.get(call.argList[1]) as? RexLiteral
                    ?: throw UnsupportedOperationException("LISTAGG separator must be a literal")
            agg.setSeparator(Expressions.encode(separator).literal)
        }
        call.collation.fieldCollations.forEach { fc ->
            agg.addWithinGroup(
                SortKey
                    .newBuilder()
                    .setColumn(inputColumnRef(rel, fc.fieldIndex))
                    .setDescending(fc.direction.isDescending)
                    .setNullsFirst(fc.nullDirection == org.apache.calcite.rel.RelFieldCollation.NullDirection.FIRST),
            )
        }
    }

    private fun inputColumnRef(
        rel: Aggregate,
        index: Int,
    ): org.tatrman.plan.v1.ColumnRef {
        val field = rel.input.rowType.fieldList[index]
        return org.tatrman.plan.v1.ColumnRef
            .newBuilder()
            .setName(field.name)
            .setType(Expressions.surfaceTypeOf(field.type))
            .build()
    }

    private fun encodeSort(
        rel: Sort,
        parameterNames: Map<Int, String>,
    ): PlanNode {
        val limit = rel.fetch?.let { (it as? RexLiteral)?.value2 as? Number }?.toLong()
        val offset = rel.offset?.let { (it as? RexLiteral)?.value2 as? Number }?.toLong()

        if (rel.collation.fieldCollations.isEmpty() && (limit != null || offset != null)) {
            val lo = LimitOffsetNode.newBuilder().setInput(encode(rel.input, parameterNames))
            limit?.let { lo.setLimit(it) }
            offset?.let { lo.setOffset(it) }
            return PlanNode.newBuilder().setLimitOffset(lo).build()
        }

        val sortBuilder = SortNode.newBuilder().setInput(encode(rel.input, parameterNames))
        rel.collation.fieldCollations.forEach { fc ->
            val field = rel.input.rowType.fieldList[fc.fieldIndex]
            sortBuilder.addSortKeys(
                SortKey
                    .newBuilder()
                    .setColumn(
                        org.tatrman.plan.v1.ColumnRef
                            .newBuilder()
                            .setName(field.name)
                            .setType(Expressions.surfaceTypeOf(field.type)),
                    ).setDescending(fc.direction.isDescending)
                    .setNullsFirst(fc.nullDirection == org.apache.calcite.rel.RelFieldCollation.NullDirection.FIRST),
            )
        }
        val sortNode = PlanNode.newBuilder().setSort(sortBuilder).build()

        if (limit != null || offset != null) {
            val lo = LimitOffsetNode.newBuilder().setInput(sortNode)
            limit?.let { lo.setLimit(it) }
            offset?.let { lo.setOffset(it) }
            return PlanNode.newBuilder().setLimitOffset(lo).build()
        }
        return sortNode
    }

    private fun encodeValues(rel: Values): PlanNode {
        val builder = ValuesNode.newBuilder()
        rel.tuples.forEach { tuple ->
            val rowBuilder = Row.newBuilder()
            tuple.forEach { rex ->
                val expr = Expressions.encode(rex)
                rowBuilder.addCells(expr.literal)
            }
            builder.addRows(rowBuilder)
        }
        rel.rowType.fieldList.forEach { field ->
            builder.addOutputColumns(
                org.tatrman.plan.v1.ColumnRef
                    .newBuilder()
                    .setName(field.name)
                    .setType(Expressions.surfaceTypeOf(field.type)),
            )
        }
        return PlanNode.newBuilder().setValues(builder).build()
    }
}
