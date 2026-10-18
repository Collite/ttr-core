// SPDX-License-Identifier: Apache-2.0
package org.tatrman.translator.schema

import org.tatrman.plan.v1.ColumnRef
import org.tatrman.plan.v1.Expression
import org.tatrman.plan.v1.NamedExpression
import org.tatrman.plan.v1.PlanNode
import org.tatrman.plan.v1.ProjectNode
import org.tatrman.plan.v1.QualifiedName
import org.tatrman.plan.v1.ScanNode
import org.tatrman.plan.v1.SchemaCode
import org.tatrman.plan.v1.schemaCodeToToken
import org.apache.calcite.rel.RelNode
import org.tatrman.translator.framework.ModelHandle
import org.tatrman.translator.framework.TranslatorFramework
import org.tatrman.translator.joiner.JoinerPlanWalker
import org.tatrman.translator.wire.PlanNodeEncoder

/**
 * Phase 08 B1 — UNFOLD_QUERY_REFS stage.
 *
 * Replaces explicit references to saved queries (`obj.query.<name>`) with the body of the saved
 * query, recursively.
 *
 * ## Algorithm
 *
 * The whole stage operates on the wire-form [PlanNode] (not on [RelNode]): encode once on entry,
 * walk the proto recursively rewriting `ScanNode(OBJ, ...)` leaves bottom-up, decode once on
 * exit. This avoids splicing `RelNode` subtrees across different Calcite clusters (each
 * [PlanNodeDecoder.decode] call produces a fresh cluster); the single final decode produces one
 * coherent tree.
 *
 * ## Responsibilities
 *
 * 1. Tree-walk the proto looking for `ScanNode` whose qname has `schema_code = OBJ`.
 * 2. For each match, fetch the body via [ModelHandle.savedQueryBody] and recursively unfold the
 *    body (a body may itself reference other saved queries).
 * 3. Cycle detection — a `visited: Set<QualifiedName>` keyed by saved-query qname carries through
 *    the recursion. Revisiting any qname → `query_reference_cycle` error.
 * 4. Cardinality check — the Scan's declared `output_columns` size must equal the body's actual
 *    output column count. Mismatch → `saved_query_output_mismatch` (the "Scan's output_columns
 *    win" decision from the master plan §220).
 * 5. Output renaming — if the body's output column names differ from the Scan's declared names,
 *    wrap the body in a [ProjectNode] that renames each field. If a Scan has no declared
 *    `output_columns` (e.g. test fixture without the encoder pass), skip both cardinality and
 *    rename — the body is used as-is.
 *
 * ## Idempotency
 *
 * A tree with no OBJ scans is returned unchanged (encode + walk + decode is a semantic identity,
 * modulo the encode/decode round-trip which is itself byte-stable per `RoundTripMatrixSpec`).
 * Re-running on the result of a previous Unfold pass is also a no-op — there are no OBJ scans
 * left to expand.
 *
 * ## Parameter substitution
 *
 * NOT YET IMPLEMENTED — so a referenced body that declares parameters is refused (`saved_query_parameters`, LQ
 * contract L-25) rather than inlined with its parameters unbound. Requires `ScanNode.parameter_bindings` field in plan.proto (currently
 * reserved 3 to 7). When added, this stage will substitute outer parameter bindings into the
 * inner body's `RexDynamicParam` nodes. See Section B-3 of the Stage-2 task list.
 */
object Unfold {
    /**
     * Wire-form variant. Operates directly on a [PlanNode] (the orchestrator chains
     * proto-level stages after Resolve produces the initial PlanNode via the encoder), so this
     * stage no longer needs a [TranslatorFramework].
     */
    fun apply(
        plan: PlanNode,
        model: ModelHandle,
    ): UnfoldResult =
        try {
            UnfoldResult.Success(unfoldPlan(plan, model, visited = emptySet()))
        } catch (e: UnfoldException) {
            UnfoldResult.Error(e.code, e.message ?: "")
        }

    /**
     * RelNode-flavored shim — kept as a thin convenience for callers (or tests) that already
     * hold a [RelNode]. Equivalent to `apply(encode(rel), model)` then decode the result.
     */
    fun apply(
        rel: RelNode,
        framework: TranslatorFramework,
        model: ModelHandle,
    ): UnfoldResult =
        when (val result = apply(PlanNodeEncoder.encode(rel), model)) {
            is UnfoldResult.Success -> UnfoldResult.Success(result.plan)
            is UnfoldResult.Error -> result
        }

    // -- recursive proto rewrite ------------------------------------------------------------

    private fun unfoldPlan(
        plan: PlanNode,
        model: ModelHandle,
        visited: Set<QualifiedName>,
    ): PlanNode {
        // Bottom-up: rewrite children first (so nested OBJ scans deeper in the tree expand
        // before this node is inspected), then handle this node if it is an OBJ scan.
        val withChildren = rewriteChildren(plan) { child -> unfoldPlan(child, model, visited) }
        if (withChildren.nodeCase == PlanNode.NodeCase.SCAN &&
            withChildren.scan.getObject().schemaCode == SchemaCode.OBJ
        ) {
            return expandObjScan(withChildren.scan, model, visited)
        }
        return withChildren
    }

    private fun expandObjScan(
        scan: ScanNode,
        model: ModelHandle,
        visited: Set<QualifiedName>,
    ): PlanNode {
        val obj = scan.getObject()
        if (obj in visited) {
            val path = (visited + obj).joinToString(" → ", transform = ::qnameToken)
            throw UnfoldException("query_reference_cycle", "Cycle: $path")
        }
        val saved =
            try {
                model.savedQueryBody(obj)
            } catch (e: Exception) {
                throw UnfoldException(
                    "saved_query_body_missing",
                    "No saved query body for ${qnameToken(obj)}",
                )
            }
        // LQ (contract L-25): parameter substitution is not implemented (see the class doc), so a body that
        // declares parameters cannot be inlined — it would carry unbound dynamic parameters into the reader's plan.
        if (saved.parameters.isNotEmpty()) {
            throw UnfoldException(
                "saved_query_parameters",
                "Saved query ${qnameToken(obj)} declares parameters (${saved.parameters.joinToString { it.name }}); " +
                    "a saved query that takes parameters cannot be read by another query",
            )
        }
        val body = saved.planNode
        // Recurse into the body (it may itself contain OBJ scans) with `obj` added to visited.
        val unfoldedBody = unfoldPlan(body, model, visited + obj)

        // Cardinality + rename: only enforced if the Scan declared explicit output_columns.
        // A bare Scan (no declared cols) lets the body's natural shape through unchanged.
        val scanCount = scan.outputColumnsCount
        if (scanCount == 0) return unfoldedBody

        val bodyCount = countOutputColumns(unfoldedBody)
        if (scanCount != bodyCount) {
            throw UnfoldException(
                "saved_query_output_mismatch",
                "Saved query ${qnameToken(obj)} declares $scanCount output columns but body produces $bodyCount",
            )
        }
        val scanNames = scan.outputColumnsList.map { it.name }
        val bodyNames = collectOutputNames(unfoldedBody)
        return if (scanNames == bodyNames) {
            unfoldedBody
        } else {
            wrapWithRename(unfoldedBody, fromNames = bodyNames, toNames = scanNames)
        }
    }

    // -- proto helpers ----------------------------------------------------------------------

    /**
     * Apply [rewrite] to each child of [plan], returning a new PlanNode with the rewritten
     * children. Leaves (Scan / TableScan / Values / WorkspaceRef) and unknown cases are returned
     * unchanged.
     *
     * Delegates to the shared [JoinerPlanWalker.rewriteChildren], which also descends into
     * expression-level subqueries (Filter.condition / Project.expressions / Join.condition) — so a
     * saved-query (`obj.query.*`) reference nested inside a `WHERE x IN (SELECT ...)` subquery is
     * inlined just like one in the main FROM tree.
     */
    private fun rewriteChildren(
        plan: PlanNode,
        rewrite: (PlanNode) -> PlanNode,
    ): PlanNode = JoinerPlanWalker.rewriteChildren(plan, rewrite)

    private fun countOutputColumns(plan: PlanNode): Int =
        when (plan.nodeCase) {
            PlanNode.NodeCase.TABLE_SCAN -> plan.tableScan.outputColumnsCount
            PlanNode.NodeCase.SCAN -> plan.scan.outputColumnsCount
            PlanNode.NodeCase.PROJECT -> plan.project.expressionsCount
            PlanNode.NodeCase.FILTER -> countOutputColumns(plan.filter.input)
            PlanNode.NodeCase.JOIN ->
                countOutputColumns(plan.join.left) + countOutputColumns(plan.join.right)
            PlanNode.NodeCase.AGGREGATE ->
                plan.aggregate.groupKeysCount + plan.aggregate.aggregatesCount
            PlanNode.NodeCase.SORT -> countOutputColumns(plan.sort.input)
            PlanNode.NodeCase.LIMIT_OFFSET -> countOutputColumns(plan.limitOffset.input)
            PlanNode.NodeCase.VALUES -> plan.values.outputColumnsCount
            PlanNode.NodeCase.SUBQUERY -> countOutputColumns(plan.subquery.subquery)
            // A set operation's shape is its first branch's (SQL names a UNION's columns from it).
            PlanNode.NodeCase.UNION ->
                plan.union.inputsList
                    .firstOrNull()
                    ?.let(::countOutputColumns) ?: 0
            else -> 0
        }

    /** The output column names of [plan], in order — UNFOLD's rename source; public as [org.tatrman.translator.orchestrator.Translator.outputColumnNames]. */
    internal fun outputColumnNames(plan: PlanNode): List<String> = collectOutputNames(plan)

    private fun collectOutputNames(plan: PlanNode): List<String> =
        when (plan.nodeCase) {
            PlanNode.NodeCase.TABLE_SCAN -> plan.tableScan.outputColumnsList.map { it.name }
            PlanNode.NodeCase.SCAN -> plan.scan.outputColumnsList.map { it.name }
            PlanNode.NodeCase.PROJECT -> plan.project.expressionsList.map { it.alias }
            PlanNode.NodeCase.FILTER -> collectOutputNames(plan.filter.input)
            PlanNode.NodeCase.JOIN ->
                collectOutputNames(plan.join.left) + collectOutputNames(plan.join.right)
            PlanNode.NodeCase.AGGREGATE ->
                plan.aggregate.groupKeysList.map { it.name } +
                    plan.aggregate.aggregatesList.map { it.alias }
            PlanNode.NodeCase.SORT -> collectOutputNames(plan.sort.input)
            PlanNode.NodeCase.LIMIT_OFFSET -> collectOutputNames(plan.limitOffset.input)
            PlanNode.NodeCase.VALUES -> plan.values.outputColumnsList.map { it.name }
            PlanNode.NodeCase.SUBQUERY -> collectOutputNames(plan.subquery.subquery)
            PlanNode.NodeCase.UNION ->
                plan.union.inputsList
                    .firstOrNull()
                    ?.let(::collectOutputNames) ?: emptyList()
            else -> emptyList()
        }

    /**
     * Wrap [body] in a Project that selects each input column by name and aliases it to the
     * Scan's declared name. Lengths are equal — checked upstream by the cardinality test.
     */
    private fun wrapWithRename(
        body: PlanNode,
        fromNames: List<String>,
        toNames: List<String>,
    ): PlanNode {
        val builder = ProjectNode.newBuilder().setInput(body)
        for ((from, to) in fromNames.zip(toNames)) {
            val expr =
                Expression
                    .newBuilder()
                    .setColumnRef(ColumnRef.newBuilder().setName(from).build())
                    .build()
            builder.addExpressions(
                NamedExpression
                    .newBuilder()
                    .setExpression(expr)
                    .setAlias(to)
                    .build(),
            )
        }
        return PlanNode.newBuilder().setProject(builder).build()
    }

    private fun qnameToken(qn: QualifiedName): String = "${schemaCodeToToken(qn.schemaCode)}.${qn.namespace}.${qn.name}"
}

/**
 * Result of [Unfold.apply]. The orchestrator converts [Error] into a
 * `ResponseMessage(severity=ERROR, code=...)` on the gRPC boundary.
 */
sealed interface UnfoldResult {
    data class Success(
        val plan: PlanNode,
    ) : UnfoldResult

    data class Error(
        val code: String,
        val message: String,
    ) : UnfoldResult
}

/** Internal sentinel exception — thrown inside the recursive walk, caught at the top boundary. */
private class UnfoldException(
    val code: String,
    message: String,
) : RuntimeException(message)
