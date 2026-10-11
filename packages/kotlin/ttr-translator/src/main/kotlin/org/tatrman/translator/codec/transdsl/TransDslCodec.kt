// SPDX-License-Identifier: Apache-2.0
package org.tatrman.translator.codec.transdsl

import com.google.protobuf.util.JsonFormat
import org.tatrman.plan.v1.AggregateCall
import org.tatrman.plan.v1.AggregateNode
import org.tatrman.plan.v1.ColumnRef
import org.tatrman.plan.v1.Expression
import org.tatrman.plan.v1.FilterNode
import org.tatrman.plan.v1.JoinNode
import org.tatrman.plan.v1.JoinType
import org.tatrman.plan.v1.NamedExpression
import org.tatrman.plan.v1.PlanNode
import org.tatrman.plan.v1.WorkspaceRef
import org.tatrman.plan.v1.ProjectNode
import org.tatrman.plan.v1.SubqueryNode
import org.tatrman.plan.v1.TableScanNode
import org.tatrman.transdsl.v1.Aggregation
import org.tatrman.transdsl.v1.AggregateColumn as TransAggregateColumn
import org.tatrman.transdsl.v1.Calculation
import org.tatrman.transdsl.v1.Column as TransColumn
import org.tatrman.transdsl.v1.Query
import org.tatrman.transdsl.v1.Source

/**
 * TransDSL ↔ PlanNode codec.
 *
 * Converts the declarative JSONC tree language (proto-encoded as
 * `org.tatrman.transdsl.v1.Query`) to the v1 RelOp wire form
 * (`org.tatrman.plan.v1.PlanNode`) and back. Both directions are
 * hand-rolled per Phase 1.10 plan; no codegen.
 *
 * Build order on parse (Round 1 §3.4-3.6):
 *
 *   1. `core` sources → leaf [PlanNode]s. A single core becomes a
 *      [TableScanNode] (data_object) or [SubqueryNode] wrapping a
 *      nested Query / query_ref. Multiple cores fold into a left-deep
 *      [JoinNode] tree with no condition (Cartesian; the Joiner adds
 *      conditions later, per the Round 1 hub-n-spoke commitment).
 *   2. `filter` → [FilterNode]. When `aggregation` is present and the
 *      filter references aggregated columns, the v1 implementation
 *      emits the filter as HAVING (above the Aggregate); otherwise as
 *      WHERE (below). v1 heuristic: if the filter expression contains
 *      any column reference whose name appears in `aggregation.*.alias`,
 *      treat it as HAVING.
 *   3. `aggregation` → [AggregateNode] with group keys plus one
 *      [AggregateCall] per (function, AggregateColumn).
 *   4. `columns` + `calculations` → [ProjectNode]. Column refs first,
 *      calculations second (so calculations can reference columns by
 *      alias).
 *
 * The Round 1 design says explicitly: TransDSL never emits a `*` —
 * the column list is always explicit at this layer.
 *
 * Unsupported v1 RelOps unparsed back to TransDSL produce
 * `operation_not_supported_in_target_language` errors with the
 * offending op named.
 */
object TransDslCodec {
    /**
     * Parse a TransDSL `Query` proto into a v1 [PlanNode].
     *
     * [queryRefs] controls how `query_ref` sources are handled:
     *   - `null` (default): emit a placeholder [SubqueryNode] carrying only an alias — the legacy
     *     behaviour; a downstream stage (or nobody) fills the inner subquery.
     *   - non-null map: resolve each `query_ref` string to its stored [PlanNode] from the map and
     *     wrap it in a [SubqueryNode]. A `query_ref` not present in the map is a parse error
     *     (`query_ref_unresolved`). Callers building this map (the translator service) fetch each
     *     referenced query's canonical form from the metadata service first. Only the `query_ref`s
     *     appearing in *this* `Query` are resolved — nested `query_ref`s inside a resolved query's
     *     canonical form are left as-is (a known v1 limitation; no cycle risk — resolution is one
     *     level deep).
     */
    fun parse(
        query: Query,
        queryRefs: Map<String, PlanNode>? = null,
    ): PlanNode {
        // Phase 08 B3 — multi-core Cartesian guard. A TransDSL Query with multiple
        // cores joins them left-deep with no condition (`parseSources` builds the Cartesian);
        // pre-B3, the user got a silently-huge cross-product if they forgot to provide a
        // connecting filter. B0 chose option (b) — enforce join conditions in the codec /
        // validator path rather than a separate Joiner service — so the codec eagerly refuses
        // multi-core requests without a `filter`. The filter doesn't have to be perfect (we
        // don't yet verify it actually references both cores; that's an A1-RESOLVE-shaped
        // tightening, deferred); the bar is "something filter-shaped is present" which catches
        // the typical mistake.
        if (query.coreList.size > 1 && !query.hasFilter()) {
            throw TransDslParseException(
                "join_condition_required",
                "TransDSL Query has ${query.coreList.size} cores but no `filter` to connect them; refusing to produce a Cartesian product. Add a `filter` whose condition references columns from both cores (e.g. core[0].fk = core[1].pk) or split into separate queries.",
            )
        }
        val source = parseSources(query.coreList, queryRefs)
        val withFilter = applyFilter(source, query)
        val withProject = applyProject(withFilter, query)
        return withProject
    }

    /** Parse the proto3 JSON form (camelCase keys). See [parse] for [queryRefs] semantics. */
    fun parseJson(
        json: String,
        queryRefs: Map<String, PlanNode>? = null,
    ): PlanNode {
        val builder = Query.newBuilder()
        JsonFormat.parser().ignoringUnknownFields().merge(json, builder)
        return parse(builder.build(), queryRefs)
    }

    /** All `query_ref` strings referenced by [query] (including those inside nested inline `query` sources). */
    fun queryRefsIn(query: Query): Set<String> =
        buildSet {
            for (source in query.coreList) {
                when (source.sourceKindCase) {
                    Source.SourceKindCase.QUERY_REF -> add(source.queryRef)
                    Source.SourceKindCase.QUERY -> addAll(queryRefsIn(source.query))
                    else -> Unit
                }
            }
        }

    /** [queryRefsIn] for the proto3 JSON form. */
    fun queryRefsInJson(json: String): Set<String> {
        val builder = Query.newBuilder()
        JsonFormat.parser().ignoringUnknownFields().merge(json, builder)
        return queryRefsIn(builder.build())
    }

    /** Walk a [PlanNode] back into a canonical TransDSL `Query`. */
    fun unparse(plan: PlanNode): Query {
        val builder = Query.newBuilder()
        unparseInto(plan, builder)
        return builder.build()
    }

    /** Render an unparsed `Query` as proto3 JSON (camelCase, sorted by canonical order). */
    fun unparseJson(plan: PlanNode): String = JsonFormat.printer().print(unparse(plan))

    // -----------------------------------------------------------------
    // Parse helpers
    // -----------------------------------------------------------------

    private fun parseSources(
        sources: List<Source>,
        queryRefs: Map<String, PlanNode>?,
    ): PlanNode {
        if (sources.isEmpty()) {
            throw TransDslParseException("missing_core", "TransDSL Query requires at least one source in `core`")
        }
        val nodes = sources.map { sourceToPlan(it, queryRefs) }
        if (nodes.size == 1) return nodes.single()
        // Left-deep Cartesian Join chain. The Joiner adds conditions in a
        // later stage; here we mark each as INNER with no condition per the
        // Round 1 §3.4 contract.
        return nodes.drop(1).fold(nodes.first()) { acc, next ->
            PlanNode
                .newBuilder()
                .setJoin(
                    JoinNode
                        .newBuilder()
                        .setLeft(acc)
                        .setRight(next)
                        .setJoinType(JoinType.INNER),
                ).build()
        }
    }

    private fun sourceToPlan(
        source: Source,
        queryRefs: Map<String, PlanNode>?,
    ): PlanNode =
        when (source.sourceKindCase) {
            Source.SourceKindCase.DATA_OBJECT -> {
                val scan =
                    TableScanNode
                        .newBuilder()
                        .setTable(source.dataObject)
                        .build()
                PlanNode.newBuilder().setTableScan(scan).build()
            }
            Source.SourceKindCase.QUERY -> {
                val nested = parse(source.query, queryRefs)
                PlanNode
                    .newBuilder()
                    .setSubquery(SubqueryNode.newBuilder().setSubquery(nested).setAlias(source.alias))
                    .build()
            }
            Source.SourceKindCase.QUERY_REF -> {
                val alias = source.alias.ifEmpty { source.queryRef }
                if (queryRefs == null) {
                    // Resolution not requested — emit a placeholder SubqueryNode (alias only); a
                    // downstream stage (or nobody) fills the inner subquery.
                    PlanNode.newBuilder().setSubquery(SubqueryNode.newBuilder().setAlias(alias)).build()
                } else {
                    val resolved =
                        queryRefs[source.queryRef]
                            ?: throw TransDslParseException(
                                "query_ref_unresolved",
                                "query_ref '${source.queryRef}' could not be resolved (no entry in the supplied resolution map)",
                            )
                    PlanNode
                        .newBuilder()
                        .setSubquery(SubqueryNode.newBuilder().setSubquery(resolved).setAlias(alias))
                        .build()
                }
            }
            Source.SourceKindCase.WORKSPACE_REF -> {
                // Phase 2.4 — `workspaceRef: "q1"` desugars to a leaf
                // PlanNode whose `workspace_ref` is set. The session_id is
                // supplied later from the PipelineContext; the codec only
                // carries the workspace name here.
                PlanNode
                    .newBuilder()
                    .setWorkspaceRef(
                        WorkspaceRef
                            .newBuilder()
                            .setWorkspaceName(source.workspaceRef),
                    ).build()
            }
            Source.SourceKindCase.SOURCEKIND_NOT_SET ->
                throw TransDslParseException(
                    "missing_source_kind",
                    "Source must set one of {data_object, query, query_ref, workspace_ref}",
                )
        }

    private fun applyFilter(
        input: PlanNode,
        query: Query,
    ): PlanNode {
        if (!query.hasFilter()) {
            // No filter — just thread aggregation if present.
            return applyAggregation(input, query)
        }
        val filter = query.filter
        val agg = if (query.hasAggregation()) query.aggregation else null
        val isHaving = agg != null && referencesAggregatedAlias(filter, agg)
        return if (isHaving) {
            // Apply aggregation first, then filter (HAVING).
            val aggregated = applyAggregation(input, query)
            PlanNode
                .newBuilder()
                .setFilter(FilterNode.newBuilder().setInput(aggregated).setCondition(filter))
                .build()
        } else {
            // Filter (WHERE) below, then aggregation.
            val filtered =
                PlanNode
                    .newBuilder()
                    .setFilter(FilterNode.newBuilder().setInput(input).setCondition(filter))
                    .build()
            applyAggregation(filtered, query)
        }
    }

    private fun applyAggregation(
        input: PlanNode,
        query: Query,
    ): PlanNode {
        if (!query.hasAggregation()) return input
        val agg = query.aggregation
        val node =
            AggregateNode
                .newBuilder()
                .setInput(input)
        for (key in agg.groupList) {
            node.addGroupKeys(ColumnRef.newBuilder().setName(key))
        }
        for (col in agg.sumList) node.addAggregates(aggCall("sum", col))
        for (col in agg.maxList) node.addAggregates(aggCall("max", col))
        for (col in agg.minList) node.addAggregates(aggCall("min", col))
        for (col in agg.avgList) node.addAggregates(aggCall("avg", col))
        for (col in agg.countList) node.addAggregates(aggCall("count", col))
        for (col in agg.countDistinctList) node.addAggregates(aggCall("count_distinct", col, distinct = true))
        return PlanNode.newBuilder().setAggregate(node).build()
    }

    private fun aggCall(
        function: String,
        col: TransAggregateColumn,
        distinct: Boolean = false,
    ): AggregateCall =
        AggregateCall
            .newBuilder()
            .setFunction(function)
            .addArgs(ColumnRef.newBuilder().setName(col.name))
            .setDistinct(distinct)
            .setAlias(col.alias)
            .build()

    private fun applyProject(
        input: PlanNode,
        query: Query,
    ): PlanNode {
        if (query.columnsList.isEmpty() && query.calculationsList.isEmpty()) return input
        val proj = ProjectNode.newBuilder().setInput(input)
        for (col in query.columnsList) {
            val name =
                NamedExpression
                    .newBuilder()
                    .setExpression(
                        columnRefExpression(col),
                    ).setAlias(col.alias.ifEmpty { col.name })
            proj.addExpressions(name)
        }
        for (calc in query.calculationsList) {
            proj.addExpressions(
                NamedExpression
                    .newBuilder()
                    .setExpression(calc.expression)
                    .setAlias(calc.alias),
            )
        }
        return PlanNode.newBuilder().setProject(proj).build()
    }

    private fun columnRefExpression(col: TransColumn): Expression =
        Expression
            .newBuilder()
            .setColumnRef(
                ColumnRef
                    .newBuilder()
                    .setSourceAlias(col.source)
                    .setName(col.name)
                    .setAlias(col.alias),
            ).build()

    private fun referencesAggregatedAlias(
        expr: Expression,
        agg: Aggregation,
    ): Boolean {
        val aliases =
            buildSet {
                addAll(agg.sumList.map { it.alias })
                addAll(agg.maxList.map { it.alias })
                addAll(agg.minList.map { it.alias })
                addAll(agg.avgList.map { it.alias })
                addAll(agg.countList.map { it.alias })
                addAll(agg.countDistinctList.map { it.alias })
            }
        if (aliases.isEmpty()) return false
        return walkColumnRefs(expr).any { it.name in aliases || it.alias in aliases }
    }

    private fun walkColumnRefs(expr: Expression): Sequence<ColumnRef> =
        sequence {
            when (expr.exprCase) {
                Expression.ExprCase.COLUMN_REF -> yield(expr.columnRef)
                Expression.ExprCase.FUNCTION -> expr.function.operandsList.forEach { yieldAll(walkColumnRefs(it)) }
                Expression.ExprCase.CAST -> yieldAll(walkColumnRefs(expr.cast.value))
                else -> Unit
            }
        }

    // -----------------------------------------------------------------
    // Unparse helpers
    // -----------------------------------------------------------------

    private fun unparseInto(
        plan: PlanNode,
        out: Query.Builder,
    ) {
        // Walk top-down, peeling layers off into the canonical Query slots.
        var current: PlanNode = plan
        // Outer Project (if any) → columns + calculations.
        if (current.nodeCase == PlanNode.NodeCase.PROJECT) {
            consumeProject(current.project, out)
            current = current.project.input
        }
        // Outer Filter that sits above an Aggregate → HAVING.
        if (current.nodeCase == PlanNode.NodeCase.FILTER &&
            current.filter.input.nodeCase == PlanNode.NodeCase.AGGREGATE
        ) {
            out.setFilter(current.filter.condition)
            current = current.filter.input
        }
        // Aggregate.
        if (current.nodeCase == PlanNode.NodeCase.AGGREGATE) {
            out.setAggregation(unparseAggregation(current.aggregate))
            current = current.aggregate.input
        }
        // Outer Filter below an Aggregate → WHERE (if no HAVING already set
        // by the branch above).
        if (current.nodeCase == PlanNode.NodeCase.FILTER && !out.hasFilter()) {
            out.setFilter(current.filter.condition)
            current = current.filter.input
        }
        // Sources.
        unparseSourcesInto(current, out)
    }

    private fun consumeProject(
        proj: ProjectNode,
        out: Query.Builder,
    ) {
        for (expr in proj.expressionsList) {
            val inner = expr.expression
            if (inner.exprCase == Expression.ExprCase.COLUMN_REF) {
                out.addColumns(
                    TransColumn
                        .newBuilder()
                        .setSource(inner.columnRef.sourceAlias)
                        .setName(inner.columnRef.name)
                        .setAlias(expr.alias),
                )
            } else {
                out.addCalculations(
                    Calculation
                        .newBuilder()
                        .setAlias(expr.alias)
                        .setExpression(inner),
                )
            }
        }
    }

    private fun unparseAggregation(node: AggregateNode): Aggregation {
        val agg = Aggregation.newBuilder()
        for (key in node.groupKeysList) {
            agg.addGroup(key.name.ifEmpty { key.alias })
        }
        for (call in node.aggregatesList) {
            val pair =
                TransAggregateColumn
                    .newBuilder()
                    .setName(call.argsList.firstOrNull()?.name ?: "")
                    .setAlias(call.alias)
                    .build()
            when (call.function) {
                "sum" -> agg.addSum(pair)
                "max" -> agg.addMax(pair)
                "min" -> agg.addMin(pair)
                "avg" -> agg.addAvg(pair)
                "count" -> if (call.distinct) agg.addCountDistinct(pair) else agg.addCount(pair)
                "count_distinct" -> agg.addCountDistinct(pair)
                else ->
                    throw TransDslUnparseException(
                        "operation_not_supported_in_target_language",
                        "Aggregate function '${call.function}' has no TransDSL slot",
                    )
            }
        }
        return agg.build()
    }

    private fun unparseSourcesInto(
        plan: PlanNode,
        out: Query.Builder,
    ) {
        // Flatten left-deep INNER Joins-without-condition into multiple cores
        // (the Round 1 multi-core Cartesian convention).
        when (plan.nodeCase) {
            PlanNode.NodeCase.TABLE_SCAN ->
                out.addCore(Source.newBuilder().setDataObject(plan.tableScan.table))
            PlanNode.NodeCase.SCAN ->
                out.addCore(Source.newBuilder().setDataObject(plan.scan.getObject()))
            PlanNode.NodeCase.JOIN -> {
                if (plan.join.joinType == JoinType.INNER && plan.join.condition == Expression.getDefaultInstance()) {
                    unparseSourcesInto(plan.join.left, out)
                    unparseSourcesInto(plan.join.right, out)
                } else {
                    // Non-Cartesian / non-INNER joins are unsupported in v1.10
                    // (Round 1 §3.4 ambiguity acknowledged in the plan's
                    // Risks section).
                    throw TransDslUnparseException(
                        "operation_not_supported_in_target_language",
                        "TransDSL cannot express a JOIN with a condition or a non-INNER join type at v1.10",
                    )
                }
            }
            PlanNode.NodeCase.SUBQUERY ->
                out.addCore(
                    Source
                        .newBuilder()
                        .setQuery(unparse(plan.subquery.subquery))
                        .setAlias(plan.subquery.alias),
                )
            // Phase 2.4 — workspace_ref unparses to a Source with
            // `workspace_ref` set; alias is empty (caller can attach one
            // later if needed).
            PlanNode.NodeCase.WORKSPACE_REF ->
                out.addCore(
                    Source
                        .newBuilder()
                        .setWorkspaceRef(plan.workspaceRef.workspaceName),
                )
            PlanNode.NodeCase.VALUES,
            PlanNode.NodeCase.PROJECT,
            PlanNode.NodeCase.FILTER,
            PlanNode.NodeCase.AGGREGATE,
            PlanNode.NodeCase.SORT,
            PlanNode.NodeCase.LIMIT_OFFSET,
            PlanNode.NodeCase.UNION,
            ->
                throw TransDslUnparseException(
                    "operation_not_supported_in_target_language",
                    "TransDSL has no slot for ${plan.nodeCase} as a source. Wrap it in a SubqueryNode.",
                )
            // A StoreNode is a write plan; TransDSL is a read-only query DSL and has no write form.
            PlanNode.NodeCase.STORE ->
                throw TransDslUnparseException(
                    "operation_not_supported_in_target_language",
                    "TransDSL cannot express a StoreNode (write plan); only SQL DML unparse is supported.",
                )
            PlanNode.NodeCase.NODE_NOT_SET ->
                throw TransDslUnparseException("empty_plan", "Cannot unparse an empty PlanNode")
        }
    }
}

class TransDslParseException(
    val code: String,
    message: String,
) : RuntimeException(message)

class TransDslUnparseException(
    val code: String,
    message: String,
) : RuntimeException(message)
