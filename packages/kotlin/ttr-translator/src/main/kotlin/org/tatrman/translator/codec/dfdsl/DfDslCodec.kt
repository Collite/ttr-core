// SPDX-License-Identifier: Apache-2.0
package org.tatrman.translator.codec.dfdsl

import com.google.protobuf.util.JsonFormat
import org.tatrman.dfdsl.v1.AssignExpression
import org.tatrman.dfdsl.v1.AssignOp
import org.tatrman.dfdsl.v1.FilterOp
import org.tatrman.dfdsl.v1.FromOp
import org.tatrman.dfdsl.v1.GroupByOp
import org.tatrman.dfdsl.v1.JoinOp
import org.tatrman.dfdsl.v1.LimitOp
import org.tatrman.dfdsl.v1.Operation
import org.tatrman.dfdsl.v1.OrderByOp
import org.tatrman.dfdsl.v1.Pipeline
import org.tatrman.dfdsl.v1.SelectColumn
import org.tatrman.dfdsl.v1.SelectOp
import org.tatrman.plan.v1.AggregateNode
import org.tatrman.plan.v1.ColumnRef
import org.tatrman.plan.v1.Expression
import org.tatrman.plan.v1.FilterNode
import org.tatrman.plan.v1.JoinNode
import org.tatrman.plan.v1.JoinType
import org.tatrman.plan.v1.LimitOffsetNode
import org.tatrman.plan.v1.NamedExpression
import org.tatrman.plan.v1.PlanNode
import org.tatrman.plan.v1.ProjectNode
import org.tatrman.plan.v1.SortNode
import org.tatrman.plan.v1.SubqueryNode
import org.tatrman.plan.v1.TableScanNode

/**
 * DataFrame DSL ↔ PlanNode codec.
 *
 * Per Round 1 §3.5e the chain is fixed at v1:
 *
 *     from → select → filter → assign → groupby → orderby → limit
 *
 * Each op appears 0-or-1 times and only in this order. The parser
 * enforces both rules; misordered chains raise
 * `dfdsl_chain_misordered`.
 *
 * Phase 08 B1 — Joins are supported as a single optional `join` op positioned
 * between `from` and `select`. The pipeline's running DataFrame is the LEFT side; the JoinOp
 * carries the RIGHT source + an `on` condition. B0 chose option (b) (no Joiner service): the
 * codec refuses a JoinOp without an `on` (`join_condition_required`). v1 allows at most one
 * join per pipeline (hub-and-spoke); chained joins are a future extension.
 */
object DfDslCodec {
    private val expectedOrder: List<Operation.OpCase> =
        listOf(
            Operation.OpCase.FROM,
            Operation.OpCase.JOIN,
            Operation.OpCase.SELECT,
            Operation.OpCase.FILTER,
            Operation.OpCase.ASSIGN,
            Operation.OpCase.GROUPBY,
            Operation.OpCase.ORDERBY,
            Operation.OpCase.LIMIT,
        )

    fun parse(pipeline: Pipeline): PlanNode {
        validateChain(pipeline)
        var node: PlanNode? = null
        for (op in pipeline.opsList) {
            node = applyOp(op, node)
        }
        return node ?: throw DfDslParseException("missing_from", "Pipeline must start with a `from` op")
    }

    fun parseJson(json: String): PlanNode {
        val builder = Pipeline.newBuilder()
        JsonFormat.parser().ignoringUnknownFields().merge(json, builder)
        return parse(builder.build())
    }

    fun unparse(plan: PlanNode): Pipeline {
        val out = Pipeline.newBuilder()
        unparseInto(plan, out)
        return out.build()
    }

    fun unparseJson(plan: PlanNode): String = JsonFormat.printer().print(unparse(plan))

    // -----------------------------------------------------------------
    // Parse helpers
    // -----------------------------------------------------------------

    private fun validateChain(pipeline: Pipeline) {
        if (pipeline.opsList.isEmpty()) {
            throw DfDslParseException("empty_pipeline", "Pipeline.ops is empty")
        }
        if (pipeline.opsList[0].opCase != Operation.OpCase.FROM) {
            throw DfDslParseException("missing_from", "Pipeline must start with a `from` op")
        }
        var lastIndex = -1
        for (op in pipeline.opsList) {
            val pos = expectedOrder.indexOf(op.opCase)
            if (pos < 0) {
                throw DfDslParseException(
                    "dfdsl_unknown_op",
                    "Unknown op ${op.opCase}; expected one of $expectedOrder",
                )
            }
            if (pos <= lastIndex) {
                throw DfDslParseException(
                    "dfdsl_chain_misordered",
                    "Op ${op.opCase} appeared after a later-stage op (chain index $pos ≤ $lastIndex)",
                )
            }
            lastIndex = pos
        }
    }

    private fun applyOp(
        op: Operation,
        input: PlanNode?,
    ): PlanNode =
        when (op.opCase) {
            Operation.OpCase.FROM -> applyFrom(op.from)
            Operation.OpCase.JOIN -> applyJoin(op.join, requireInput(input, op))
            Operation.OpCase.SELECT -> applySelect(op.select, requireInput(input, op))
            Operation.OpCase.FILTER -> applyFilter(op.filter, requireInput(input, op))
            Operation.OpCase.ASSIGN -> applyAssign(op.assign, requireInput(input, op))
            Operation.OpCase.GROUPBY -> applyGroupBy(op.groupby, requireInput(input, op))
            Operation.OpCase.ORDERBY -> applyOrderBy(op.orderby, requireInput(input, op))
            Operation.OpCase.LIMIT -> applyLimit(op.limit, requireInput(input, op))
            Operation.OpCase.OP_NOT_SET ->
                throw DfDslParseException("op_not_set", "Operation must set one of {from, join, select, filter, ...}")
        }

    private fun requireInput(
        input: PlanNode?,
        op: Operation,
    ): PlanNode =
        input ?: throw DfDslParseException(
            "missing_input",
            "Op ${op.opCase} requires a preceding source; pipeline must start with `from`",
        )

    private fun applyFrom(from: FromOp): PlanNode =
        when (from.sourceCase) {
            FromOp.SourceCase.TABLE ->
                PlanNode
                    .newBuilder()
                    .setTableScan(TableScanNode.newBuilder().setTable(from.table))
                    .build()
            FromOp.SourceCase.QUERY_REF ->
                PlanNode
                    .newBuilder()
                    .setSubquery(
                        SubqueryNode
                            .newBuilder()
                            .setAlias(from.alias.ifEmpty { from.queryRef }),
                    ).build()
            FromOp.SourceCase.WORKSPACE_REF ->
                // Phase 08 B2 — `workspace_ref: "q1"` reads from a session-scoped
                // workspace materialised by a prior query. The session_id travels in the
                // PipelineContext, not in the codec output; the codec carries only the name.
                PlanNode
                    .newBuilder()
                    .setWorkspaceRef(
                        org.tatrman.plan.v1.WorkspaceRef
                            .newBuilder()
                            .setWorkspaceName(from.workspaceRef),
                    ).build()
            FromOp.SourceCase.SOURCE_NOT_SET ->
                throw DfDslParseException(
                    "from_missing_source",
                    "FromOp must set table, query_ref, or workspace_ref",
                )
        }

    private fun applyJoin(
        join: JoinOp,
        input: PlanNode,
    ): PlanNode {
        // Phase 08 B1 — JoinOp requires an explicit `on` (B0 option (b)). v1 only
        // accepts a single JoinOp per pipeline; chained joins are blocked by the chain-order
        // rule (JOIN is a single position in `expectedOrder`).
        if (!join.hasOn()) {
            throw DfDslParseException(
                "join_condition_required",
                "JoinOp must set `on`; v1 refuses Cartesian joins from DataFrame DSL (B0 option (b)).",
            )
        }
        val rightPlan =
            when (join.rightCase) {
                JoinOp.RightCase.RIGHT_TABLE ->
                    PlanNode
                        .newBuilder()
                        .setTableScan(TableScanNode.newBuilder().setTable(join.rightTable))
                        .build()
                JoinOp.RightCase.RIGHT_QUERY_REF ->
                    PlanNode
                        .newBuilder()
                        .setSubquery(
                            SubqueryNode
                                .newBuilder()
                                .setAlias(join.rightAlias.ifEmpty { join.rightQueryRef }),
                        ).build()
                JoinOp.RightCase.RIGHT_WORKSPACE_REF ->
                    PlanNode
                        .newBuilder()
                        .setWorkspaceRef(
                            org.tatrman.plan.v1.WorkspaceRef
                                .newBuilder()
                                .setWorkspaceName(join.rightWorkspaceRef),
                        ).build()
                JoinOp.RightCase.RIGHT_NOT_SET ->
                    throw DfDslParseException(
                        "join_missing_right",
                        "JoinOp must set right_table, right_query_ref, or right_workspace_ref",
                    )
            }
        val joinType = if (join.joinType == JoinType.JOIN_TYPE_UNSPECIFIED) JoinType.INNER else join.joinType
        return PlanNode
            .newBuilder()
            .setJoin(
                JoinNode
                    .newBuilder()
                    .setLeft(input)
                    .setRight(rightPlan)
                    .setJoinType(joinType)
                    .setCondition(join.on),
            ).build()
    }

    private fun applySelect(
        select: SelectOp,
        input: PlanNode,
    ): PlanNode {
        val proj = ProjectNode.newBuilder().setInput(input)
        for (col in select.columnsList) {
            val ref = ColumnRef.newBuilder().setName(col.name)
            proj.addExpressions(
                NamedExpression
                    .newBuilder()
                    .setExpression(Expression.newBuilder().setColumnRef(ref))
                    .setAlias(col.alias.ifEmpty { col.name }),
            )
        }
        return PlanNode.newBuilder().setProject(proj).build()
    }

    private fun applyFilter(
        filter: FilterOp,
        input: PlanNode,
    ): PlanNode =
        PlanNode
            .newBuilder()
            .setFilter(FilterNode.newBuilder().setInput(input).setCondition(filter.condition))
            .build()

    private fun applyAssign(
        assign: AssignOp,
        input: PlanNode,
    ): PlanNode {
        // assign preserves all input columns plus adds new ones. The PlanNode
        // contract requires explicit projection — at v1 we add the computed
        // columns in a ProjectNode whose input is the unchanged source. The
        // Translator's RESOLVE stage (Phase 1.6) is responsible for prefixing
        // the input columns when it walks the tree; v1 callers consuming
        // this codec must accept that the assigned columns are the only
        // explicit outputs at the proto level. (The ProjectNode is marked
        // by carrying ONLY the computed expressions.)
        val proj = ProjectNode.newBuilder().setInput(input)
        for (assignment in assign.expressionsList) {
            proj.addExpressions(
                NamedExpression
                    .newBuilder()
                    .setExpression(assignment.expression)
                    .setAlias(assignment.alias),
            )
        }
        return PlanNode.newBuilder().setProject(proj).build()
    }

    private fun applyGroupBy(
        groupby: GroupByOp,
        input: PlanNode,
    ): PlanNode {
        val agg = AggregateNode.newBuilder().setInput(input)
        for (key in groupby.keysList) {
            agg.addGroupKeys(ColumnRef.newBuilder().setName(key))
        }
        for (call in groupby.aggregatesList) {
            agg.addAggregates(call)
        }
        return PlanNode.newBuilder().setAggregate(agg).build()
    }

    private fun applyOrderBy(
        order: OrderByOp,
        input: PlanNode,
    ): PlanNode {
        val sort = SortNode.newBuilder().setInput(input)
        order.keysList.forEach { sort.addSortKeys(it) }
        return PlanNode.newBuilder().setSort(sort).build()
    }

    private fun applyLimit(
        limit: LimitOp,
        input: PlanNode,
    ): PlanNode {
        val lo = LimitOffsetNode.newBuilder().setInput(input)
        if (limit.n > 0) lo.setLimit(limit.n)
        if (limit.offset > 0) lo.setOffset(limit.offset)
        return PlanNode.newBuilder().setLimitOffset(lo).build()
    }

    // -----------------------------------------------------------------
    // Unparse helpers
    // -----------------------------------------------------------------

    private fun unparseInto(
        plan: PlanNode,
        out: Pipeline.Builder,
    ) {
        // Reverse-walk peels chain layers in opposite of the canonical order:
        //   limit → orderby → groupby → assign(project) → filter → select(project) → from
        // Both `select` and `assign` map to PROJECT; the order
        // (select-then-filter-then-assign) lets us peel the upper PROJECT as
        // `assign` and the lower PROJECT as `select`. Single-PROJECT plans
        // emit as `select` when expressions are all bare ColumnRefs,
        // `assign` otherwise.
        val ops = mutableListOf<Operation>()
        var node: PlanNode = plan
        if (node.nodeCase == PlanNode.NodeCase.LIMIT_OFFSET) {
            ops.add(
                Operation
                    .newBuilder()
                    .setLimit(
                        LimitOp
                            .newBuilder()
                            .setN(if (node.limitOffset.hasLimit()) node.limitOffset.limit else 0)
                            .setOffset(if (node.limitOffset.hasOffset()) node.limitOffset.offset else 0),
                    ).build(),
            )
            node = node.limitOffset.input
        }
        if (node.nodeCase == PlanNode.NodeCase.SORT) {
            val orderBy = OrderByOp.newBuilder()
            node.sort.sortKeysList.forEach { orderBy.addKeys(it) }
            ops.add(Operation.newBuilder().setOrderby(orderBy).build())
            node = node.sort.input
        }
        if (node.nodeCase == PlanNode.NodeCase.AGGREGATE) {
            val gb = GroupByOp.newBuilder()
            node.aggregate.groupKeysList.forEach { gb.addKeys(it.name.ifEmpty { it.alias }) }
            node.aggregate.aggregatesList.forEach { gb.addAggregates(it) }
            ops.add(Operation.newBuilder().setGroupby(gb).build())
            node = node.aggregate.input
        }
        // First PROJECT (immediately above filter or source) — emit as
        // `assign` if any expression is computed; else as `select`.
        if (node.nodeCase == PlanNode.NodeCase.PROJECT) {
            ops.add(projectAsOp(node.project, preferSelect = false))
            node = node.project.input
        }
        if (node.nodeCase == PlanNode.NodeCase.FILTER) {
            ops.add(
                Operation
                    .newBuilder()
                    .setFilter(FilterOp.newBuilder().setCondition(node.filter.condition))
                    .build(),
            )
            node = node.filter.input
        }
        // Second PROJECT (below the filter) — emit as `select`.
        if (node.nodeCase == PlanNode.NodeCase.PROJECT) {
            ops.add(projectAsOp(node.project, preferSelect = true))
            node = node.project.input
        }
        // Phase 08 B1 — a Join at the bottom of the chain unparses as
        // `from(left) + join(right, on)`. Refuses on-less joins because the parser would also
        // refuse them (codec symmetry; a downstream that fed us a no-condition Join is a bug).
        if (node.nodeCase == PlanNode.NodeCase.JOIN) {
            val join = node.join
            ops.add(Operation.newBuilder().setJoin(joinNodeToOp(join)).build())
            node = join.left
        }
        // Source.
        ops.add(Operation.newBuilder().setFrom(unparseFrom(node)).build())
        // Reverse for canonical from→...→limit chain order.
        ops.reverse()
        ops.forEach { out.addOps(it) }
    }

    private fun projectAsOp(
        proj: org.tatrman.plan.v1.ProjectNode,
        preferSelect: Boolean,
    ): Operation {
        val allBareColumns =
            proj.expressionsList.isNotEmpty() &&
                proj.expressionsList.all { it.expression.exprCase == Expression.ExprCase.COLUMN_REF }
        return if (preferSelect || allBareColumns) {
            val sel = SelectOp.newBuilder()
            proj.expressionsList.forEach { ne ->
                sel.addColumns(
                    SelectColumn
                        .newBuilder()
                        .setName(
                            if (ne.expression.exprCase == Expression.ExprCase.COLUMN_REF) {
                                ne.expression.columnRef.name
                            } else {
                                ne.alias
                            },
                        ).setAlias(ne.alias),
                )
            }
            Operation.newBuilder().setSelect(sel).build()
        } else {
            val asg = AssignOp.newBuilder()
            proj.expressionsList.forEach { ne ->
                asg.addExpressions(
                    AssignExpression
                        .newBuilder()
                        .setAlias(ne.alias)
                        .setExpression(ne.expression),
                )
            }
            Operation.newBuilder().setAssign(asg).build()
        }
    }

    /**
     * Phase 08 B1 — turn a [JoinNode] into a [JoinOp]. The right side is rewritten back into the
     * matching `right_*` source kind based on the right child's leaf shape. A non-leaf right
     * (joined Project/Filter/etc.) is rejected — v1 supports only single-step joins (the left
     * side may have already been a Join chain, but the right is a direct source).
     */
    private fun joinNodeToOp(join: JoinNode): JoinOp {
        val builder = JoinOp.newBuilder()
        when (join.right.nodeCase) {
            PlanNode.NodeCase.TABLE_SCAN -> builder.setRightTable(join.right.tableScan.table)
            PlanNode.NodeCase.SUBQUERY -> {
                val alias = join.right.subquery.alias
                builder.setRightQueryRef(alias)
                if (alias.isNotEmpty()) builder.setRightAlias(alias)
            }
            PlanNode.NodeCase.WORKSPACE_REF ->
                builder.setRightWorkspaceRef(join.right.workspaceRef.workspaceName)
            else ->
                throw DfDslUnparseException(
                    "operation_not_supported_in_target_language",
                    "v1 DataFrame DSL only supports joins whose right side is a direct source (table / query_ref / workspace_ref); got ${join.right.nodeCase}",
                )
        }
        builder.joinType = if (join.joinType == JoinType.JOIN_TYPE_UNSPECIFIED) JoinType.INNER else join.joinType
        if (!join.hasCondition()) {
            throw DfDslUnparseException(
                "join_condition_required",
                "Cannot unparse a Join with no `condition` into DataFrame DSL; the codec refuses to emit a JoinOp without an `on`",
            )
        }
        builder.on = join.condition
        return builder.build()
    }

    private fun unparseFrom(node: PlanNode): FromOp =
        when (node.nodeCase) {
            PlanNode.NodeCase.TABLE_SCAN -> FromOp.newBuilder().setTable(node.tableScan.table).build()
            PlanNode.NodeCase.SUBQUERY ->
                FromOp
                    .newBuilder()
                    .setQueryRef(node.subquery.alias)
                    .setAlias(node.subquery.alias)
                    .build()
            // Phase 08 B2 — a `WorkspaceRef` leaf round-trips back into `from_workspace`.
            PlanNode.NodeCase.WORKSPACE_REF ->
                FromOp.newBuilder().setWorkspaceRef(node.workspaceRef.workspaceName).build()
            // Phase 08 B1 — JOIN is now peeled by `unparseInto` into a separate JoinOp before
            // we reach unparseFrom, so the case shouldn't surface here for a properly-shaped
            // plan. If it does, fall through to the catch-all.
            PlanNode.NodeCase.NODE_NOT_SET ->
                throw DfDslUnparseException("empty_plan", "Cannot unparse an empty PlanNode")
            else ->
                throw DfDslUnparseException(
                    "operation_not_supported_in_target_language",
                    "DataFrame DSL chain has no `from` slot for ${node.nodeCase}; wrap it in a Subquery",
                )
        }
}

class DfDslParseException(
    val code: String,
    message: String,
) : RuntimeException(message)

class DfDslUnparseException(
    val code: String,
    message: String,
) : RuntimeException(message)
