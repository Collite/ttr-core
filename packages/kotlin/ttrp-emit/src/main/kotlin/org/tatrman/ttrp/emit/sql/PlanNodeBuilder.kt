// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.emit.sql

import org.tatrman.plan.v1.AggregateCall as PbAggregateCall
import org.tatrman.plan.v1.AggregateNode
import org.tatrman.plan.v1.ColumnRef as PbColumnRef
import org.tatrman.plan.v1.Expression as PbExpression
import org.tatrman.plan.v1.FilterNode
import org.tatrman.plan.v1.FunctionCall as PbFunctionCall
import org.tatrman.plan.v1.JoinNode
import org.tatrman.plan.v1.JoinType as PbJoinType
import org.tatrman.plan.v1.LimitOffsetNode
import org.tatrman.plan.v1.Literal as PbLiteral
import org.tatrman.plan.v1.NamedExpression
import org.tatrman.plan.v1.ParameterRef
import org.tatrman.plan.v1.PlanNode
import org.tatrman.plan.v1.ProjectNode
import org.tatrman.plan.v1.SortKey
import org.tatrman.plan.v1.SortNode
import org.tatrman.plan.v1.UnionNode
import org.tatrman.ttrp.emit.EmitDiagnosticId
import org.tatrman.ttrp.emit.TtrpEmitException
import org.tatrman.ttrp.expr.AggregateCall
import org.tatrman.ttrp.expr.CaseWhen
import org.tatrman.ttrp.expr.Cast
import org.tatrman.ttrp.expr.ColumnRef
import org.tatrman.ttrp.expr.Expression
import org.tatrman.ttrp.expr.FunctionCall
import org.tatrman.ttrp.expr.InList
import org.tatrman.ttrp.expr.IsNull
import org.tatrman.ttrp.expr.Literal
import org.tatrman.ttrp.expr.LiteralValue
import org.tatrman.ttrp.ast.SourceLocation
import org.tatrman.ttrp.expr.MdPath
import org.tatrman.ttrp.expr.MdResolution
import org.tatrman.ttrp.graph.model.Aggregate
import org.tatrman.ttrp.graph.model.Filter
import org.tatrman.ttrp.graph.model.Join
import org.tatrman.ttrp.graph.model.JoinType
import org.tatrman.ttrp.graph.model.Limit
import org.tatrman.ttrp.graph.model.Node
import org.tatrman.ttrp.graph.model.PortNames
import org.tatrman.ttrp.graph.model.Project
import org.tatrman.ttrp.graph.model.Sort
import org.tatrman.ttrp.graph.model.Union
import org.tatrman.ttrp.graph.model.Calc
import org.tatrman.ttrp.graph.model.Distinct
import org.tatrman.ttrp.graph.model.Select

/**
 * Lowers a single normalized TTR-P relational [Node] (plus its already-built input
 * [PlanNode]s) and the TTR-P [Expression] IR to the `plan.v1` wire form the translator
 * consumes. This is a purely mechanical structural map — the two IRs are deliberate twins
 * (T5). Sugar nodes are an internal-invariant failure here (T8 must have expanded them).
 *
 * Node coverage is the plan.v1-representable relational subset: Filter, Project, Aggregate,
 * Sort (always NULLS LAST unless authored otherwise — Q9-3), Limit, Union, and
 * Join(inner/left/right/full/semi/anti/cross). A join's `on` condition is encoded with per-side
 * `source_alias = $L/$R` tags (from the `left`/`right` port qualifiers) so the translator
 * decoder routes each column into the correct join input via `RelBuilder.field(2, ord, name)`
 * — see [LEFT_INPUT_TAG]/[RIGHT_INPUT_TAG] and `org.tatrman.translator.wire.Expressions`.
 *
 * SEMI/ANTI ride the wire natively since NX-A (`JoinType` SEMI=5/ANTI=6; Intersect/Except lower
 * to them); CROSS is an INNER join on TRUE (AG-P0). The `sql-text` path lowers an equi SEMI/ANTI
 * to a plain join instead ([existenceAsJoin]) — the native shape unparses to invalid T-SQL.
 */
class PlanNodeBuilder(
    /**
     * MD dot-path read lowering (S4-A). Null when the program has no MD paths (the common case) —
     * an `mdPath` expression then still raises [EmitDiagnosticId.UNSUPPORTED_NODE]. When present,
     * an in-scalar-position `mdPath` lowers to a scalar subquery over the §8 relational subtree.
     */
    private val mdLowering: MdPathLowering? = null,
    /** The graph-side MD resolutions (from [org.tatrman.ttrp.graph.model.TtrpGraph.mdResolutions]). */
    private val mdResolutions: Map<SourceLocation, MdResolution> = emptyMap(),
    /**
     * AG-P0 — the program's runtime params by name (`param x: int`): an unqualified [ColumnRef] naming
     * one lowers to a `plan.v1` [ParameterRef] (a positional placeholder the unparse reports by name),
     * never a column. Empty (the default) keeps every ref a column — the pre-AG behaviour.
     */
    private val params: Map<String, PlanParam> = emptyMap(),
    /**
     * AG-P0 — lower an equi SEMI/ANTI join to an INNER / LEFT(+IS NULL) join against the right side's
     * DISTINCT keys instead of the native wire SEMI/ANTI: Calcite 1.41's MSSQL unparse of a native
     * semi/anti join double-aliases a subquery left input (`) AS [t] AS [t0]`) and aliases a
     * parenthesized join (`(A JOIN B ON …) AS [t5]`), both invalid T-SQL (F-AG, a ttr-translator
     * item). Exact for an equi condition (a left row matches at most one distinct key tuple).
     */
    private val existenceAsJoin: Boolean = false,
) {
    /** Column names of each input, in port order, when the inputs are not bare scans (nested sql-text plans). */
    private var knownInputColumns: List<List<String>>? = null

    /**
     * Build the plan.v1 body for [node] over its pre-built [inputs]. [inputColumns] (AG-P0) names each
     * input's columns when an input is a nested plan rather than a scan, so calc passthrough and the
     * join right-key dedup still know the row type; null ⇒ read it off the scans (the CTE path).
     */
    fun body(
        node: Node,
        inputs: List<PlanNode>,
        inputColumns: List<List<String>>? = null,
        /** AU-core-P2: a join's right equi-keys to keep ([EmitNode.joinKeep]). */
        joinKeep: Set<String> = emptySet(),
    ): PlanNode {
        knownInputColumns = inputColumns
        this.joinKeep = joinKeep
        return bodyOf(node, inputs)
    }

    private var joinKeep: Set<String> = emptySet()

    private fun bodyOf(
        node: Node,
        inputs: List<PlanNode>,
    ): PlanNode =
        when (node) {
            is Filter -> filter(node, single(node, inputs))
            is Project -> project(node, single(node, inputs))
            is Aggregate -> aggregate(node, single(node, inputs))
            is Sort -> sort(node, single(node, inputs))
            is Limit -> limit(node, single(node, inputs))
            is Union -> union(node, inputs)
            is Join -> join(node, inputs)
            is Select, is Calc, is Distinct ->
                throw TtrpEmitException(
                    EmitDiagnosticId.SUGAR_REACHED_EMIT,
                    detail = "sugar node ${node::class.simpleName} '${node.label}' reached SQL emit",
                    location = node.location,
                )
            else ->
                throw TtrpEmitException(
                    EmitDiagnosticId.UNSUPPORTED_NODE,
                    detail = "node ${node::class.simpleName} '${node.label}' has no plan.v1 SQL lowering",
                    location = node.location,
                )
        }

    private fun single(
        node: Node,
        inputs: List<PlanNode>,
    ): PlanNode =
        inputs.singleOrNull()
            ?: throw TtrpEmitException(
                EmitDiagnosticId.UNSUPPORTED_NODE,
                detail = "${node::class.simpleName} '${node.label}' expected 1 input, got ${inputs.size}",
                location = node.location,
            )

    private fun filter(
        node: Filter,
        input: PlanNode,
    ): PlanNode {
        val pred =
            node.predicate
                ?: throw TtrpEmitException(
                    EmitDiagnosticId.UNSUPPORTED_NODE,
                    detail = "Filter '${node.label}' has no predicate",
                    location = node.location,
                )
        return PlanNode
            .newBuilder()
            .setFilter(FilterNode.newBuilder().setInput(input).setCondition(expr(pred)))
            .build()
    }

    private fun project(
        node: Project,
        input: PlanNode,
    ): PlanNode {
        val b = ProjectNode.newBuilder().setInput(input)
        // calc add-semantics: emit the input columns first (minus any the projection re-defines by
        // alias), so `calc { x = expr }` renders `SELECT src.*, expr AS x` rather than dropping the
        // row. Input column names come from the scan CtePlanner feeds us (RJ-P3).
        val overridden =
            node.columns.indices
                .mapNotNull { node.aliasOf(it) }
                .toSet()
        if (node.passthrough) {
            columnsOf(input, 0)
                .orEmpty()
                .filter { it !in overridden && !RejectGuardSql.isValidityFlag(it) }
                .forEach { name -> b.addExpressions(passthroughColumn(name)) }
        }
        node.columns.forEachIndexed { i, c ->
            val ne = NamedExpression.newBuilder().setExpression(expr(c))
            node.aliasOf(i)?.let { ne.alias = it }
            b.addExpressions(ne)
        }
        return PlanNode.newBuilder().setProject(b).build()
    }

    /** A `SELECT "name" AS "name"` passthrough entry for a calc's un-overridden input column. */
    private fun passthroughColumn(name: String): NamedExpression {
        val ref = PbExpression.newBuilder().setColumnRef(PbColumnRef.newBuilder().setName(name))
        return NamedExpression
            .newBuilder()
            .setExpression(ref)
            .setAlias(name)
            .build()
    }

    private fun aggregate(
        node: Aggregate,
        input: PlanNode,
    ): PlanNode {
        if (node.aggregations.any { !plainAggregate(it.value) }) return aggregateOverExpressions(node, input)
        val b = AggregateNode.newBuilder().setInput(input)
        node.groupBy.forEach { b.addGroupKeys(PbColumnRef.newBuilder().setName(it)) }
        node.aggregations.forEach { agg ->
            val call =
                agg.value as? AggregateCall
                    ?: throw TtrpEmitException(
                        EmitDiagnosticId.UNSUPPORTED_NODE,
                        detail = "aggregation '${agg.name}' is not an AggregateCall",
                        location = node.location,
                    )
            val pb =
                PbAggregateCall
                    .newBuilder()
                    .setFunction(aggName(call))
                    .setDistinct(call.distinct)
                    .setAlias(agg.name)
            call.args.forEach { arg ->
                val ref =
                    arg as? ColumnRef
                        ?: throw TtrpEmitException(
                            EmitDiagnosticId.UNSUPPORTED_NODE,
                            detail = "aggregate arg must be a column ref (got ${arg::class.simpleName})",
                            location = node.location,
                        )
                pb.addArgs(PbColumnRef.newBuilder().setName(ref.column))
            }
            b.addAggregates(pb)
        }
        // HAVING is split into a Filter-over-Aggregate by T8 — a residual having here is a bug.
        node.having?.let {
            throw TtrpEmitException(
                EmitDiagnosticId.SUGAR_REACHED_EMIT,
                detail = "Aggregate '${node.label}' still carries a HAVING clause (T8 should have split it)",
                location = node.location,
            )
        }
        return PlanNode.newBuilder().setAggregate(b).build()
    }

    /** An aggregation plan.v1 carries as it stands: one aggregate call over plain columns. */
    private fun plainAggregate(e: Expression): Boolean = e is AggregateCall && e.args.all { it is ColumnRef }

    /**
     * An aggregate whose calls read expressions, or whose bindings compute over aggregates (AU-core-P2: TTR-SQL's
     * `SUM(quantity * unit_price)`, `COALESCE(SUM(CASE … END), 0)`) — plan.v1's aggregate takes plain columns only,
     * so: a projection computes every non-column argument (`_ttrp_a<n>`), the aggregate names each call
     * (`_ttrp_g<n>`), and a projection over it computes every binding from the group keys and those calls. The
     * output row is the same — the group keys, then each binding in order.
     */
    private fun aggregateOverExpressions(
        node: Aggregate,
        input: PlanNode,
    ): PlanNode {
        node.having?.let {
            throw TtrpEmitException(
                EmitDiagnosticId.SUGAR_REACHED_EMIT,
                detail = "Aggregate '${node.label}' still carries a HAVING clause (T8 should have split it)",
                location = node.location,
            )
        }
        val preArgs = LinkedHashMap<String, Expression>()
        val calls = mutableListOf<Pair<String, PbAggregateCall.Builder>>()

        fun argColumn(arg: Expression): String =
            if (arg is ColumnRef) {
                arg.column
            } else {
                "_ttrp_a${preArgs.size}".also { preArgs[it] = arg }
            }

        fun lift(e: Expression): Expression =
            when (e) {
                is AggregateCall -> {
                    val name = "_ttrp_g${calls.size}"
                    val pb =
                        PbAggregateCall
                            .newBuilder()
                            .setFunction(aggName(e))
                            .setDistinct(e.distinct)
                            .setAlias(name)
                    e.args.forEach { pb.addArgs(PbColumnRef.newBuilder().setName(argColumn(it))) }
                    calls += name to pb
                    ColumnRef(null, name, e.location)
                }
                is FunctionCall -> e.copy(args = e.args.map { lift(it) })
                is Cast -> e.copy(expr = lift(e.expr))
                is CaseWhen ->
                    e.copy(
                        branches =
                            e.branches.map { (w, t) ->
                                lift(w) to lift(t)
                            },
                        elseExpr = e.elseExpr?.let { lift(it) },
                    )
                is IsNull -> e.copy(expr = lift(e.expr))
                is InList -> e.copy(expr = lift(e.expr), items = e.items.map { lift(it) })
                else -> e
            }
        val bindings = node.aggregations.map { it.name to lift(it.value) }

        // pre: the group keys, every plain column a call reads, every computed argument
        val pre = ProjectNode.newBuilder().setInput(input)
        val plainArgs =
            calls
                .flatMap { (_, pb) -> pb.argsList.map { it.name } }
                .filter { it !in preArgs && it !in node.groupBy }
                .distinct()
        (node.groupBy + plainArgs).forEach { pre.addExpressions(passthroughColumn(it)) }
        preArgs.forEach { (name, e) ->
            pre.addExpressions(NamedExpression.newBuilder().setExpression(expr(e)).setAlias(name))
        }

        val agg = AggregateNode.newBuilder().setInput(PlanNode.newBuilder().setProject(pre))
        node.groupBy.forEach { agg.addGroupKeys(PbColumnRef.newBuilder().setName(it)) }
        calls.forEach { (_, pb) -> agg.addAggregates(pb) }

        // post: the group keys, then each binding over the calls
        val post = ProjectNode.newBuilder().setInput(PlanNode.newBuilder().setAggregate(agg))
        node.groupBy.forEach { post.addExpressions(passthroughColumn(it)) }
        bindings.forEach { (name, e) ->
            post.addExpressions(NamedExpression.newBuilder().setExpression(expr(e)).setAlias(name))
        }
        return PlanNode.newBuilder().setProject(post).build()
    }

    private fun sort(
        node: Sort,
        input: PlanNode,
    ): PlanNode {
        val b = SortNode.newBuilder().setInput(input)
        node.keys.forEach { key ->
            // `keys` carry the column name; descending suffixes are parsed upstream. NULLS LAST
            // is always emitted (Q9-3): nulls_first defaults false → decoder → builder.nullsLast.
            val (name, desc) = parseSortKey(key)
            b.addSortKeys(
                SortKey
                    .newBuilder()
                    .setColumn(PbColumnRef.newBuilder().setName(name))
                    .setDescending(desc)
                    .setNullsFirst(false),
            )
        }
        return PlanNode.newBuilder().setSort(b).build()
    }

    private fun parseSortKey(key: String): Pair<String, Boolean> {
        val trimmed = key.trim()
        return when {
            trimmed.endsWith(" desc", ignoreCase = true) -> trimmed.dropLast(5).trim() to true
            trimmed.endsWith(" asc", ignoreCase = true) -> trimmed.dropLast(4).trim() to false
            else -> trimmed to false
        }
    }

    private fun limit(
        node: Limit,
        input: PlanNode,
    ): PlanNode {
        val b = LimitOffsetNode.newBuilder().setInput(input)
        node.count?.let { b.limit = it }
        return PlanNode.newBuilder().setLimitOffset(b).build()
    }

    private fun union(
        node: Union,
        inputs: List<PlanNode>,
    ): PlanNode {
        val b = UnionNode.newBuilder().setAll(true) // UNION ALL — distinct-union is a config we don't author yet
        inputs.forEach { b.addInputs(it) }
        return PlanNode.newBuilder().setUnion(b).build()
    }

    private fun join(
        node: Join,
        inputs: List<PlanNode>,
    ): PlanNode {
        if (inputs.size != 2) {
            throw TtrpEmitException(
                EmitDiagnosticId.UNSUPPORTED_NODE,
                detail = "Join '${node.label}' expected 2 inputs, got ${inputs.size}",
                location = node.location,
            )
        }
        if (existenceAsJoin && (node.type == JoinType.SEMI || node.type == JoinType.ANTI)) {
            return existenceJoin(node, inputs)
        }
        // SEMI/ANTI ride the wire since NX-A (plan.v1 JoinType SEMI=5/ANTI=6); CROSS is an INNER join
        // on TRUE. A semi/anti join's row type is the LEFT input only — no right-key dedup applies.
        val jt =
            when (node.type) {
                JoinType.INNER, JoinType.CROSS -> PbJoinType.INNER
                JoinType.LEFT -> PbJoinType.LEFT
                JoinType.RIGHT -> PbJoinType.RIGHT
                JoinType.FULL -> PbJoinType.FULL
                JoinType.SEMI -> PbJoinType.SEMI
                JoinType.ANTI -> PbJoinType.ANTI
            }
        val b =
            JoinNode
                .newBuilder()
                .setLeft(inputs[0])
                .setRight(inputs[1])
                .setJoinType(jt)
        // The condition is port-qualified (`left.x = right.y`); encode it in join context so
        // `left`/`right` become the `$L`/`$R` input tags the decoder routes on.
        val on = node.on
        when {
            on != null -> b.condition = withJoinSides(inputs) { expr(on, inJoin = true) }
            node.type == JoinType.CROSS -> b.condition = trueLiteralExpr()
        }
        val joinNode = PlanNode.newBuilder().setJoin(b).build()
        if (node.type == JoinType.SEMI || node.type == JoinType.ANTI) return joinNode

        // Polars `right_on` parity (A4 identical-results): an equi-join DROPS the right-side key
        // columns, so `join(a, b, on: a.x = b.y)` yields `a.* + b.(non-key)` — the same output
        // schema Polars produces (see hero_crunch.py `right_on=…`). Replicate in SQL with a
        // positional Project over the join: Calcite uniquifies duplicate names in the combined
        // join row (`region`/`region0`), so we select surviving columns by ordinal (unambiguous)
        // and re-alias to their names. This also keeps the emitted CTE well-formed — a bare
        // `SELECT *` over a join with a duplicated column name is an invalid CTE.
        //
        // Falls back to the bare join for non-equi / cross / null conditions or when an input's
        // columns aren't known (input isn't a scan) — no right key to drop, no dedup needed.
        val rightKeys = node.on?.let { JoinDedup.rightEquiKeys(it) }
        val leftCols = columnsOf(inputs[0], 0)
        val rightCols = columnsOf(inputs[1], 1)
        if (rightKeys.isNullOrEmpty() || leftCols == null || rightCols == null) {
            return joinNode
        }
        val project = ProjectNode.newBuilder().setInput(joinNode)
        JoinDedup.survivors(leftCols, rightCols, rightKeys, joinKeep).forEach { (ordinal, name) ->
            project.addExpressions(
                NamedExpression
                    .newBuilder()
                    .setExpression(
                        PbExpression.newBuilder().setColumnRef(PbColumnRef.newBuilder().setName("\$$ordinal")),
                    ).setAlias(name),
            )
        }
        return PlanNode.newBuilder().setProject(project).build()
    }

    /** See [existenceAsJoin]: `L ⋉ R on a = b` → `π_L(L ⋈ δ(π_b R))`; `L ▷ R` → `π_L(σ_{m IS NULL}(L ⟕ δ(π_b R) + m))`. */
    private fun existenceJoin(
        node: Join,
        inputs: List<PlanNode>,
    ): PlanNode {
        fun unsupported(why: String): Nothing =
            throw TtrpEmitException(
                EmitDiagnosticId.UNSUPPORTED_NODE,
                detail =
                    "${node.type} join '${node.label}' $why on this engine " +
                        "(equi semi/anti only — F-AG translator item)",
                location = node.location,
            )
        val on = node.on ?: unsupported("has no condition")
        val keys =
            JoinDedup.rightEquiKeys(on)?.takeIf { it.isNotEmpty() }?.toList() ?: unsupported("is not an equi-join")
        val leftCols = columnsOf(inputs[0], 0) ?: unsupported("has an input of unknown row type")
        val anti = node.type == JoinType.ANTI
        val distinctKeys =
            AggregateNode.newBuilder().setInput(inputs[1]).apply {
                keys.forEach { addGroupKeys(PbColumnRef.newBuilder().setName(it)) }
            }
        var right = PlanNode.newBuilder().setAggregate(distinctKeys).build()
        if (anti) {
            val marked = ProjectNode.newBuilder().setInput(right)
            keys.forEach { marked.addExpressions(passthroughColumn(it)) }
            marked.addExpressions(
                NamedExpression
                    .newBuilder()
                    .setExpression(
                        PbExpression.newBuilder().setLiteral(PbLiteral.newBuilder().setIntValue(1).setType("int")),
                    ).setAlias(EXISTS_MARKER),
            )
            right = PlanNode.newBuilder().setProject(marked).build()
        }
        val joined =
            JoinNode
                .newBuilder()
                .setLeft(inputs[0])
                .setRight(right)
                .setJoinType(if (anti) PbJoinType.LEFT else PbJoinType.INNER)
                .setCondition(withJoinSides(listOf(inputs[0], inputs[1])) { expr(on, inJoin = true) })
        var plan = PlanNode.newBuilder().setJoin(joined).build()
        if (anti) {
            val isNull =
                PbExpression
                    .newBuilder()
                    .setFunction(
                        PbFunctionCall
                            .newBuilder()
                            .setOperation("is_null")
                            .addOperands(
                                PbExpression.newBuilder().setColumnRef(PbColumnRef.newBuilder().setName(EXISTS_MARKER)),
                            ),
                    ).build()
            plan = PlanNode.newBuilder().setFilter(FilterNode.newBuilder().setInput(plan).setCondition(isNull)).build()
        }
        val project = ProjectNode.newBuilder().setInput(plan)
        leftCols.forEachIndexed { i, name ->
            project.addExpressions(
                NamedExpression
                    .newBuilder()
                    .setExpression(PbExpression.newBuilder().setColumnRef(PbColumnRef.newBuilder().setName("\$$i")))
                    .setAlias(name),
            )
        }
        return PlanNode.newBuilder().setProject(project).build()
    }

    /** Input [i]'s column names: the scan's own, else the caller-supplied [knownInputColumns] (nested plans). */
    private fun columnsOf(
        input: PlanNode,
        i: Int,
    ): List<String>? = scanColumnNames(input) ?: knownInputColumns?.getOrNull(i)

    private fun trueLiteralExpr(): PbExpression =
        PbExpression
            .newBuilder()
            .setLiteral(PbLiteral.newBuilder().setBoolValue(true).setType("bool"))
            .build()

    /** Column names of a [PlanNode] iff it is a TableScan (CtePlanner feeds joins base/CTE scans); else null. */
    private fun scanColumnNames(input: PlanNode): List<String>? =
        if (input.nodeCase == PlanNode.NodeCase.TABLE_SCAN) {
            input.tableScan.outputColumnsList.map { it.name }
        } else {
            null
        }

    // --- Expressions -----------------------------------------------------------------

    /**
     * Lower a TTR-P [Expression] to `plan.v1`. When [inJoin] is true this expression is a join
     * `on` condition: a [ColumnRef]'s `left`/`right` port qualifier is emitted as the `$L`/`$R`
     * `source_alias` tag so the decoder resolves it against the correct join input (rather than
     * the ambiguous combined join row type). Everywhere else `inJoin` is false and a port
     * qualifier is a plain label the single-input decoder ignores.
     */
    fun expr(
        e: Expression,
        inJoin: Boolean = false,
    ): PbExpression =
        when (e) {
            is ColumnRef -> {
                val p = if (e.port == null) params[e.column] else null
                if (p != null) {
                    PbExpression
                        .newBuilder()
                        .setParameter(ParameterRef.newBuilder().setName(e.column).setPositionalIndex(p.index))
                        .setResultType(p.typeTag)
                        .build()
                } else {
                    val ref = PbColumnRef.newBuilder().setName(e.column)
                    // In a join condition a qualifier that is no side (`customer.center`, a table alias of an
                    // earlier join) is resolved by name, as an unqualified column is (AU-core-P2).
                    val alias =
                        if (inJoin) {
                            joinInputTag(e.port)
                                ?: unqualifiedSide(if (joinInputTag(e.port) == null) e.copy(port = null) else e)
                        } else {
                            e.port
                        }
                    alias?.let { ref.sourceAlias = it }
                    PbExpression.newBuilder().setColumnRef(ref).build()
                }
            }
            is Literal -> PbExpression.newBuilder().setLiteral(literal(e.value)).build()
            is FunctionCall ->
                PbExpression
                    .newBuilder()
                    .setFunction(
                        PbFunctionCall
                            .newBuilder()
                            .setOperation(operation(e))
                            .addAllOperands(e.args.map { expr(it, inJoin) }),
                    ).build()
            is IsNull ->
                PbExpression
                    .newBuilder()
                    .setFunction(
                        PbFunctionCall
                            .newBuilder()
                            .setOperation(if (e.negated) "is_not_null" else "is_null")
                            .addOperands(expr(e.expr, inJoin)),
                    ).build()
            is InList -> inList(e, inJoin)
            is CaseWhen -> caseWhen(e, inJoin)
            // The translator decodes a cast in its FUNCTION form only (`operation = "cast"`, the target as the
            // expression's result type — a physical code, [SqlCastTypes]); its `Expression.cast` oneof is a
            // decode TODO there (the sql-text `CastExpression decoding is TODO` failure).
            is Cast ->
                PbExpression
                    .newBuilder()
                    .setFunction(
                        PbFunctionCall
                            .newBuilder()
                            .setOperation("cast")
                            .addOperands(expr(e.expr, inJoin)),
                    ).setResultType(SqlCastTypes.codeOf(e.target))
                    .build()
            is AggregateCall ->
                throw TtrpEmitException(
                    EmitDiagnosticId.UNSUPPORTED_NODE,
                    detail = "AggregateCall is only legal inside an Aggregate node, not a scalar expression",
                    location = e.location,
                )
            is MdPath -> mdPath(e)
            // AU-core-P2: a recipe `:name` (a parameter, `:as_of`, a value the run binds) is a runtime param — the
            // statement keeps it as `:name` and the runner binds it.
            is org.tatrman.ttrp.expr.ParamRef -> {
                val p =
                    params[e.name] ?: throw TtrpEmitException(
                        EmitDiagnosticId.UNSUPPORTED_NODE,
                        detail = "`:${e.name}` is no parameter of the recipe nor a value the run binds",
                        location = e.location,
                    )
                PbExpression
                    .newBuilder()
                    .setParameter(ParameterRef.newBuilder().setName(e.name).setPositionalIndex(p.index))
                    .setResultType(p.typeTag)
                    .build()
            }
            is org.tatrman.ttrp.expr.DurationExpr ->
                throw TtrpEmitException(
                    EmitDiagnosticId.UNSUPPORTED_NODE,
                    detail = "a duration is lowered with the time sugar (AU-core-P2 S2.2)",
                    location = e.location,
                )
            // TTRP 0.5: a cn definition form — refused at check (TTRP-CN-001); a program never emits one.
            is org.tatrman.ttrp.expr.ConceptForm ->
                throw TtrpEmitException(
                    EmitDiagnosticId.UNSUPPORTED_NODE,
                    detail = "a conceptual-model form belongs in a cn `definition:` (TTRP-CN-001)",
                    location = e.location,
                )
        }

    /**
     * Lower an `mdPath` in scalar expression position to a scalar subquery over the §8 relational
     * subtree ([MdPathLowering]). Requires both the lowering context (the cubelet bindings) and this
     * path's S3 resolution (carried on the graph, keyed by location) — absent either, the path can't
     * be lowered here and raises [EmitDiagnosticId.UNSUPPORTED_NODE] with a specific reason.
     */
    private fun mdPath(e: MdPath): PbExpression {
        val lowering =
            mdLowering ?: throw TtrpEmitException(
                EmitDiagnosticId.UNSUPPORTED_NODE,
                detail = "MD dot-path reached emit with no binding context (md2db bindings not wired)",
                location = e.location,
            )
        val resolution =
            mdResolutions[e.location] ?: throw TtrpEmitException(
                EmitDiagnosticId.UNSUPPORTED_NODE,
                detail = "MD dot-path reached emit unresolved (no S3 resolution on the graph)",
                location = e.location,
            )
        return try {
            lowering.lowerScalar(resolution.path, resolution.shape)
        } catch (ex: MdLoweringException) {
            throw TtrpEmitException(
                EmitDiagnosticId.UNSUPPORTED_NODE,
                detail = ex.message ?: ex.code,
                location = e.location,
            )
        }
    }

    private fun inList(
        e: InList,
        inJoin: Boolean,
    ): PbExpression {
        val inCall =
            PbFunctionCall
                .newBuilder()
                .setOperation("in")
                .addOperands(expr(e.expr, inJoin))
                .addAllOperands(e.items.map { expr(it, inJoin) })
        val inExpr = PbExpression.newBuilder().setFunction(inCall).build()
        return if (!e.negated) {
            inExpr
        } else {
            PbExpression
                .newBuilder()
                .setFunction(PbFunctionCall.newBuilder().setOperation("not").addOperands(inExpr))
                .build()
        }
    }

    private fun caseWhen(
        e: CaseWhen,
        inJoin: Boolean,
    ): PbExpression {
        // plan.v1 "case" rides a generic FunctionCall; operands are Calcite's flat
        // [when1, then1, …, else] list (the decoder maps "case" → SqlStdOperatorTable.CASE).
        val call = PbFunctionCall.newBuilder().setOperation("case")
        e.branches.forEach { (whenExpr, thenExpr) ->
            call.addOperands(expr(whenExpr, inJoin))
            call.addOperands(expr(thenExpr, inJoin))
        }
        call.addOperands(e.elseExpr?.let { expr(it, inJoin) } ?: nullLiteralExpr())
        return PbExpression.newBuilder().setFunction(call).build()
    }

    /** The two inputs' column names while a join condition is being encoded (null outside one, or unknown). */
    private var joinSides: Pair<List<String>?, List<String>?>? = null

    private fun <T> withJoinSides(
        inputs: List<PlanNode>,
        block: () -> T,
    ): T {
        val saved = joinSides
        joinSides = columnsOf(inputs[0], 0) to columnsOf(inputs[1], 1)
        try {
            return block()
        } finally {
            joinSides = saved
        }
    }

    /**
     * An UNQUALIFIED column in a join condition (`on: x = right.y`): the left input's when the left has
     * a column of that name (the TTR-P convention — unqualified is the left), else the right input's;
     * no tag when neither side's row type is known (the bare combined-row lookup, as before).
     */
    private fun unqualifiedSide(e: ColumnRef): String? {
        if (e.port != null) return null
        val (left, right) = joinSides ?: return null
        return when {
            left?.contains(e.column) == true -> LEFT_INPUT_TAG
            right?.contains(e.column) == true -> RIGHT_INPUT_TAG
            else -> null
        }
    }

    /**
     * Map a join `on`-condition column's `left`/`right` port qualifier to the `$L`/`$R` wire tag
     * the translator decoder resolves against the correct join input. An unqualified column
     * (null port) gets no tag — it falls to a bare `field(name)` lookup over the combined join
     * row type, which is unambiguous only when the name is unique across both inputs.
     */
    private fun joinInputTag(port: String?): String? =
        when (port) {
            PortNames.LEFT -> LEFT_INPUT_TAG
            PortNames.RIGHT -> RIGHT_INPUT_TAG
            else -> null
        }

    private fun nullLiteralExpr(): PbExpression =
        PbExpression.newBuilder().setLiteral(PbLiteral.newBuilder().setIsNull(true)).build()

    private fun literal(v: LiteralValue): PbLiteral =
        when (v) {
            is LiteralValue.Str ->
                PbLiteral
                    .newBuilder()
                    .setStringValue(v.value)
                    .setType("text")
                    .build()
            is LiteralValue.Bool ->
                PbLiteral
                    .newBuilder()
                    .setBoolValue(v.value)
                    .setType("bool")
                    .build()
            LiteralValue.Null -> PbLiteral.newBuilder().setIsNull(true).build()
            is LiteralValue.Num -> {
                val raw = v.raw
                if (raw.contains('.') || raw.contains('e') || raw.contains('E')) {
                    PbLiteral
                        .newBuilder()
                        .setFloatValue(raw.toDouble())
                        .setType("float")
                        .build()
                } else {
                    PbLiteral
                        .newBuilder()
                        .setIntValue(raw.toLong())
                        .setType("int")
                        .build()
                }
            }
        }

    /** CatalogId `.name` → plan.v1 operation string (decoder vocabulary in wire/Expressions.kt). */
    private fun operation(call: FunctionCall): String {
        val name = call.function.name
        return OP_MAP[name] ?: name
    }

    private fun aggName(call: AggregateCall): String {
        val name = call.function.name
        return when (name) {
            "sum", "count", "avg", "min", "max" -> name
            else ->
                throw TtrpEmitException(
                    EmitDiagnosticId.UNSUPPORTED_NODE,
                    detail = "aggregate function '$name' is not in the plan.v1 wire format",
                    location = call.location,
                )
        }
    }

    companion object {
        /**
         * Synthetic `source_alias` markers routing a join-condition [ColumnRef] into the left/right
         * input on decode. These MIRROR the wire contract owned by the published translator
         * (`org.tatrman.translator.wire.Expressions.LEFT_INPUT_TAG`/`RIGHT_INPUT_TAG`); kept as
         * local constants here for the same reason the operator vocabulary below is inlined — the
         * `plan.v1` wire strings are a contract, not a shared code dependency.
         */
        const val LEFT_INPUT_TAG: String = "\$L"
        const val RIGHT_INPUT_TAG: String = "\$R"

        /** The anti-join match marker column ([existenceAsJoin]); never part of an output row. */
        const val EXISTS_MARKER: String = "_ttrp_exists"

        /** TTR-P operator surface name → plan.v1 operation string (see decoder `operatorFor`). */
        private val OP_MAP =
            mapOf(
                "eq" to "eq",
                "neq" to "ne",
                "lt" to "lt",
                "lte" to "le",
                "gt" to "gt",
                "gte" to "ge",
                "and" to "and",
                "or" to "or",
                "not" to "not",
                "add" to "add",
                "sub" to "sub",
                "mul" to "mul",
                "div" to "div",
                "neg" to "-",
                // AU-core-P2: `because:` texts concatenate (the decoder's `||` → SqlStdOperatorTable.CONCAT)
                "concat" to "||",
            )
    }
}

/**
 * A runtime param as the plan sees it (AG-P0): its positional [index] (declaration order — the
 * `RexDynamicParam` index) and the plan.v1 surface [typeTag] (`text`/`int`/`decimal`/`date`/`datetime`/`bool`).
 */
data class PlanParam(
    val index: Int,
    val typeTag: String,
) {
    companion object {
        /** TTR-P param type (`string`/`int`/`decimal`/`date`/`datetime`/`bool`) → the plan.v1 surface tag. */
        fun tagOf(ttrpType: String): String =
            when (ttrpType.substringBefore('(').trim().lowercase()) {
                "string", "text" -> "text"
                else -> ttrpType.substringBefore('(').trim().lowercase()
            }
    }
}
