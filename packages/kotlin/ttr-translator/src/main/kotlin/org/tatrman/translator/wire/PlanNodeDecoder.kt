// SPDX-License-Identifier: Apache-2.0
package org.tatrman.translator.wire

import org.tatrman.plan.v1.AggregateNode
import org.tatrman.plan.v1.ColumnRef
import org.tatrman.plan.v1.FilterNode
import org.tatrman.plan.v1.JoinNode
import org.tatrman.plan.v1.JoinType
import org.tatrman.plan.v1.LimitOffsetNode
import org.tatrman.plan.v1.PlanNode
import org.tatrman.plan.v1.ProjectNode
import org.tatrman.plan.v1.SortKey
import org.tatrman.plan.v1.SortNode
import org.tatrman.plan.v1.UnionNode
import org.tatrman.plan.v1.ValuesNode
import org.apache.calcite.rel.RelNode
import org.apache.calcite.rel.core.JoinRelType
import org.apache.calcite.rex.RexNode
import org.apache.calcite.sql.SqlAggFunction
import org.apache.calcite.tools.RelBuilder
import org.tatrman.translator.framework.TranslatorFramework
import org.tatrman.translator.functions.FunctionCatalog
import org.tatrman.plan.v1.schemaCodeToToken

/**
 * Decode a v1 proto [PlanNode] back into a Calcite [RelNode] using the
 * framework's [RelBuilder]. Inverse of [PlanNodeEncoder].
 *
 * The decoder threads a single [RelBuilder] through the recursive descent;
 * each branch leaves its sub-tree on the builder's stack and the parent
 * pops it. RelBuilder enforces stack discipline, so a buggy decoder fails
 * loudly with `IllegalStateException` ("expected exactly one rel on the
 * stack") rather than producing a wrong tree.
 */
object PlanNodeDecoder {
    fun decode(
        plan: PlanNode,
        framework: TranslatorFramework,
    ): RelNode {
        val builder = framework.newRelBuilder()
        push(builder, plan, framework.functionCatalog)
        return builder.build()
    }

    /**
     * Build a [PlanNode] embedded as an expression-level subquery (see [SubqueryExpression]) into
     * a standalone [RelNode], sharing [builder]'s cluster. Pushes the subtree onto the builder and
     * pops it with [RelBuilder.build], so the outer builder stack is left exactly as it was found —
     * the subquery becomes a detached rel suitable for `RexSubQuery.scalar/exists/in`.
     *
     * Cluster sharing is mandatory: a `RexSubQuery`'s `rel` must live in the same
     * [org.apache.calcite.plan.RelOptCluster] as the outer expression it sits in, so we reuse the
     * caller's builder rather than spinning up a fresh one.
     */
    internal fun decodeSubrel(
        builder: RelBuilder,
        plan: PlanNode,
        catalog: FunctionCatalog,
    ): RelNode {
        push(builder, plan, catalog)
        return builder.build()
    }

    private fun push(
        builder: RelBuilder,
        plan: PlanNode,
        catalog: FunctionCatalog,
    ) {
        when (plan.nodeCase) {
            PlanNode.NodeCase.TABLE_SCAN ->
                pushTableScan(builder, plan.tableScan.table, plan.tableScan.outputColumnsList, plan.tableScan.hintsList)

            PlanNode.NodeCase.SCAN ->
                pushTableScan(builder, plan.scan.getObject(), plan.scan.outputColumnsList, plan.scan.hintsList)

            PlanNode.NodeCase.PROJECT -> pushProject(builder, plan.project, catalog)
            PlanNode.NodeCase.FILTER -> pushFilter(builder, plan.filter, catalog)
            PlanNode.NodeCase.JOIN -> pushJoin(builder, plan.join, catalog)
            PlanNode.NodeCase.AGGREGATE -> pushAggregate(builder, plan.aggregate, catalog)
            PlanNode.NodeCase.SORT -> pushSort(builder, plan.sort, catalog)
            PlanNode.NodeCase.LIMIT_OFFSET -> pushLimitOffset(builder, plan.limitOffset, catalog)
            PlanNode.NodeCase.VALUES -> pushValues(builder, plan.values)
            PlanNode.NodeCase.UNION -> pushUnion(builder, plan.union, catalog)
            // A StoreNode is a write root with no query-RelNode form; it is intercepted by the DML
            // unparse path (StoreDmlUnparser) before reaching the generic decoder. Its `input` is
            // decoded there, never inline here (that would break RelBuilder stack discipline).
            PlanNode.NodeCase.STORE -> throw UnsupportedOperationException(
                "StoreNode has no RelNode form; write plans unparse to DML via StoreDmlUnparser",
            )
            else -> throw UnsupportedOperationException(
                "PlanNode case '${plan.nodeCase}' is not in the v1 wire format",
            )
        }
    }

    private fun pushTableScan(
        builder: RelBuilder,
        tableQname: org.tatrman.plan.v1.QualifiedName,
        outputColumns: List<ColumnRef>,
        hints: List<org.tatrman.plan.v1.TableHint> = emptyList(),
    ) {
        val parts =
            listOfNotNull(
                schemaCodeToToken(tableQname.schemaCode).takeUnless { it.isEmpty() },
                tableQname.namespace.takeUnless { it.isEmpty() },
                tableQname.name,
            )
        builder.scan(parts)

        // NX-A.S4 (calcite-ext, D9) — reattach T-SQL table hints as Calcite RelHints on the raw
        // scan, *before* the alias-projection wrap below, so the hint sits on the `TableScan` (the
        // only Hintable leaf) where RelToSqlConverter expects it. The stock
        // `RelToSqlConverter.visit(TableScan)` turns getHints() into a SqlTableRef the MSSQL dialect
        // renders as `WITH (NOLOCK)` (other dialects drop). Option-bearing hints (`INDEX(0)`) are
        // folded into the hint *name* because `RelToSqlConverter.toSqlHint` always routes through its
        // `kvOptions` branch, so list-options never reach unparse and would emit invalid `WITH
        // (INDEX)`. The structured name/options split stays intact on the proto wire (source of
        // truth); the decoded RelHint is terminal — unparsed, never re-encoded.
        if (hints.isNotEmpty()) {
            builder.hints(
                hints.map { h ->
                    val name = if (h.optionsList.isEmpty()) h.name else "${h.name}(${h.optionsList.joinToString(", ")})"
                    org.apache.calcite.rel.hint.RelHint
                        .builder(name)
                        .build()
                },
            )
        }

        // DF-T05 v1.x — alias-at-boundary. When MapToPhysical emitted a TableScan whose
        // output_columns carry aliases (name=DB column, alias=ER attribute), we need to
        // wrap the raw Calcite scan with a Project that aliases the columns. Downstream
        // operators (Filter, Project, …) reference columns by ER attribute name; those
        // references resolve to the alias-projected fields. Without this, RelBuilder.field
        // would throw "field [er_attr] not found" because the catalog exposes DB column names.
        //
        // Skip the wrap when no output_column declares an alias (v1.0 default path) — keeps
        // round-trips structurally stable and emitted SQL free of unnecessary subqueries.
        val needsAliasing = outputColumns.any { it.alias.isNotEmpty() && it.alias != it.name }
        if (needsAliasing) {
            val fields = outputColumns.map { builder.field(it.name) }
            val aliases = outputColumns.map { it.alias.ifEmpty { it.name } }
            builder.project(fields, aliases, true)
        }
    }

    private fun pushProject(
        builder: RelBuilder,
        project: ProjectNode,
        catalog: FunctionCatalog,
    ) {
        push(builder, project.input, catalog)
        val nodes = project.expressionsList.map { Expressions.decode(builder, it.expression, catalog) }
        val aliases = project.expressionsList.map { it.alias.ifEmpty { null } }
        // `force = true` — preserve identity Projects. A `ProjectNode` in the wire form must
        // round-trip to a Calcite `Project`; without `force`, RelBuilder silently elides projects
        // whose nodes equal the input's row type, which breaks structure-preserving round-trips
        // (e.g. encode/decode/encode byte stability) and the two-half pipeline's byte-equality
        // contract between single-call target=DB and (target=ER → REL_NODE → target=DB).
        builder.project(nodes, aliases, true)
    }

    private fun pushFilter(
        builder: RelBuilder,
        filter: FilterNode,
        catalog: FunctionCatalog,
    ) {
        push(builder, filter.input, catalog)
        val cond = Expressions.decode(builder, filter.condition, catalog)
        builder.filter(cond)
    }

    private fun pushJoin(
        builder: RelBuilder,
        join: JoinNode,
        catalog: FunctionCatalog,
    ) {
        push(builder, join.left, catalog)
        push(builder, join.right, catalog)
        val type =
            when (join.joinType) {
                JoinType.INNER -> JoinRelType.INNER
                JoinType.LEFT -> JoinRelType.LEFT
                JoinType.RIGHT -> JoinRelType.RIGHT
                JoinType.FULL -> JoinRelType.FULL
                // NX-A: existence tests — inverse of PlanNodeEncoder's SEMI/ANTI arms.
                JoinType.SEMI -> JoinRelType.SEMI
                JoinType.ANTI -> JoinRelType.ANTI
                else -> throw UnsupportedOperationException(
                    "Join type '${join.joinType}' is not in the v1 wire format",
                )
            }
        if (join.hasCondition()) {
            val cond = Expressions.decode(builder, join.condition, catalog)
            builder.join(type, cond)
        } else {
            builder.join(type, builder.literal(true))
        }
    }

    private fun pushUnion(
        builder: RelBuilder,
        union: UnionNode,
        catalog: FunctionCatalog,
    ) {
        // Push each input in order, then collapse the top `n` stack entries into a
        // single set-op node (RelBuilder.union pops n rels and pushes the Union).
        union.inputsList.forEach { push(builder, it, catalog) }
        builder.union(union.all, union.inputsList.size)
    }

    private fun pushAggregate(
        builder: RelBuilder,
        aggregate: AggregateNode,
        catalog: FunctionCatalog,
    ) {
        push(builder, aggregate.input, catalog)
        val keyFields = aggregate.groupKeysList.map { it.name }
        val groupKey = builder.groupKey(keyFields.map { Expressions.fieldByName(builder, it) })
        val aggCalls = aggregate.aggregatesList.map { decodeAggCall(builder, it) }
        builder.aggregate(groupKey, aggCalls)
    }

    private fun decodeAggCall(
        builder: RelBuilder,
        call: org.tatrman.plan.v1.AggregateCall,
    ): RelBuilder.AggCall {
        val fn: SqlAggFunction =
            when (call.function.lowercase()) {
                "count" -> org.apache.calcite.sql.`fun`.SqlStdOperatorTable.COUNT
                "sum" -> org.apache.calcite.sql.`fun`.SqlStdOperatorTable.SUM
                "min" -> org.apache.calcite.sql.`fun`.SqlStdOperatorTable.MIN
                "max" -> org.apache.calcite.sql.`fun`.SqlStdOperatorTable.MAX
                "avg" -> org.apache.calcite.sql.`fun`.SqlStdOperatorTable.AVG
                // TF-P1.S3 (contracts §5.4) — STRING_AGG; the MSSQL dialect renders the name back.
                "listagg" -> org.apache.calcite.sql.`fun`.SqlStdOperatorTable.LISTAGG
                else -> throw UnsupportedOperationException(
                    "Aggregate function '${call.function}' is not in the v1 wire format",
                )
            }
        val columns: List<RexNode> = call.argsList.map { Expressions.fieldByName(builder, it.name) }
        val args = if (call.hasSeparator()) columns + separatorArg(builder, call.separator.stringValue) else columns
        val unordered = builder.aggregateCall(fn, args)
        val agg =
            if (call.withinGroupCount >
                0
            ) {
                unordered.sort(call.withinGroupList.map { sortKeyToRex(builder, it) })
            } else {
                unordered
            }
        val withDistinct = if (call.distinct) agg.distinct() else agg
        return if (call.alias.isNotEmpty()) withDistinct.`as`(call.alias) else withDistinct
    }

    /**
     * TF-P1.S3 — the listagg separator operand. The encoder read it from the constant column Calcite
     * projects below the aggregate, and that column is still on the wire in the input `Project`: reuse it
     * rather than projecting a second copy (which `RelBuilder` would name `$f<n+1>`, so every round trip
     * renamed it).
     */
    private fun separatorArg(
        builder: RelBuilder,
        separator: String,
    ): RexNode {
        val input = builder.peek() as? org.apache.calcite.rel.core.Project
        val ordinal =
            input?.projects?.indexOfFirst {
                it is org.apache.calcite.rex.RexLiteral && it.getValueAs(String::class.java) == separator
            } ?: -1
        return if (ordinal >= 0) builder.field(ordinal) else builder.literal(separator)
    }

    private fun pushSort(
        builder: RelBuilder,
        sort: SortNode,
        catalog: FunctionCatalog,
    ) {
        push(builder, sort.input, catalog)
        val sortNodes = sort.sortKeysList.map { sortKeyToRex(builder, it) }
        builder.sort(sortNodes)
    }

    private fun sortKeyToRex(
        builder: RelBuilder,
        key: SortKey,
    ): RexNode {
        val field = Expressions.fieldByName(builder, key.column.name)
        val descending = if (key.descending) builder.desc(field) else field
        return when {
            key.nullsFirst -> builder.nullsFirst(descending)
            else -> builder.nullsLast(descending)
        }
    }

    private fun pushLimitOffset(
        builder: RelBuilder,
        lo: LimitOffsetNode,
        catalog: FunctionCatalog,
    ) {
        push(builder, lo.input, catalog)
        val limit = if (lo.hasLimit()) lo.limit.toInt() else -1
        val offset = if (lo.hasOffset()) lo.offset.toInt() else 0
        builder.limit(offset, limit)
    }

    private fun pushValues(
        builder: RelBuilder,
        values: ValuesNode,
    ) {
        // RelBuilder.values takes a flat array of cells laid out row-major,
        // with the field names supplied separately.
        val fieldNames = values.outputColumnsList.map { it.name }.toTypedArray()
        val cells =
            values.rowsList
                .flatMap { row ->
                    row.cellsList.map { decodeLiteralValue(it) }
                }.toTypedArray()
        builder.values(fieldNames, *cells)
    }

    private fun decodeLiteralValue(lit: org.tatrman.plan.v1.Literal): Any? =
        when {
            lit.isNull -> null
            lit.valueCase == org.tatrman.plan.v1.Literal.ValueCase.STRING_VALUE -> lit.stringValue
            lit.valueCase == org.tatrman.plan.v1.Literal.ValueCase.INT_VALUE -> lit.intValue
            lit.valueCase == org.tatrman.plan.v1.Literal.ValueCase.FLOAT_VALUE -> lit.floatValue
            lit.valueCase == org.tatrman.plan.v1.Literal.ValueCase.BOOL_VALUE -> lit.boolValue
            lit.valueCase == org.tatrman.plan.v1.Literal.ValueCase.DATETIME_VALUE -> lit.datetimeValue
            else -> null
        }

    /** Public hook listing the PlanNode cases this decoder handles. */
    fun supportedNodeCases(): Set<PlanNode.NodeCase> =
        setOf(
            PlanNode.NodeCase.TABLE_SCAN,
            PlanNode.NodeCase.SCAN,
            PlanNode.NodeCase.PROJECT,
            PlanNode.NodeCase.FILTER,
            PlanNode.NodeCase.JOIN,
            PlanNode.NodeCase.AGGREGATE,
            PlanNode.NodeCase.SORT,
            PlanNode.NodeCase.LIMIT_OFFSET,
            PlanNode.NodeCase.VALUES,
        )
}
