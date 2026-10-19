// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.graph.capability

import org.tatrman.ttrp.diagnostics.Severity
import org.tatrman.ttrp.diagnostics.TtrpDiagnostic
import org.tatrman.ttrp.diagnostics.TtrpDiagnosticId
import org.tatrman.ttrp.expr.AggregateCall
import org.tatrman.ttrp.expr.CaseWhen
import org.tatrman.ttrp.expr.Cast
import org.tatrman.ttrp.expr.ColumnRef
import org.tatrman.ttrp.expr.Expression
import org.tatrman.ttrp.expr.FunctionCall
import org.tatrman.ttrp.expr.InList
import org.tatrman.ttrp.expr.IsNull
import org.tatrman.ttrp.expr.Literal
import org.tatrman.ttrp.expr.MdPath
import org.tatrman.ttrp.graph.model.Aggregate
import org.tatrman.ttrp.graph.model.Branch
import org.tatrman.ttrp.graph.model.Calc
import org.tatrman.ttrp.graph.model.Filter
import org.tatrman.ttrp.graph.model.Join
import org.tatrman.ttrp.graph.model.Node
import org.tatrman.ttrp.graph.model.Pivot
import org.tatrman.ttrp.graph.model.Project
import org.tatrman.ttrp.graph.model.Switch
import org.tatrman.ttrp.graph.model.TtrpGraph

/** A capability miss (T6-e). Ordered document-first for deterministic 2.3 consumption. */
sealed interface CapabilityMiss {
    val nodeId: String
    val engine: String

    data class NodeMiss(
        override val nodeId: String,
        val label: String,
        override val engine: String,
        val detail: String,
    ) : CapabilityMiss

    data class FunctionMiss(
        override val nodeId: String,
        val functionId: String,
        override val engine: String,
    ) : CapabilityMiss
}

/**
 * Reports every (node, engine) and (function, engine) miss over a Stage-2.1 graph
 * (T6-e). Parameter-aware (join types, aggregate functions/distinct, pivot native).
 * This stage ONLY reports — it never rewrites or escalates (T6-d α: native? →
 * rewrite exists? → escalate is the 2.3 normalizer's join). Output is document-ordered.
 */
class CapabilityChecker(
    private val bound: BoundWorld,
) {
    fun check(graph: TtrpGraph): List<CapabilityMiss> {
        val out = mutableListOf<CapabilityMiss>()
        for (container in graph.containers.values) {
            val engine = bound.engines[container.target] ?: continue
            val manifest = engine.manifest
            for (id in container.memberIds) {
                val node = graph.nodes[id] ?: continue
                out += missesFor(node, container.target, manifest)
            }
        }
        return out
    }

    fun diagnostics(misses: List<CapabilityMiss>): List<TtrpDiagnostic> =
        misses.map { m ->
            when (m) {
                is CapabilityMiss.NodeMiss ->
                    info(TtrpDiagnosticId.CAP_001, "node `${m.label}` (${m.detail}) is not native on `${m.engine}`")
                is CapabilityMiss.FunctionMiss ->
                    info(TtrpDiagnosticId.CAP_002, "function `${m.functionId}` is not supported on `${m.engine}`")
            }
        }

    private fun missesFor(
        node: Node,
        engine: String,
        manifest: EngineTypeManifest,
    ): List<CapabilityMiss> {
        val out = mutableListOf<CapabilityMiss>()
        val kind = node::class.simpleName ?: return out
        val entry = manifest.nodes[kind]
        if (entry == null) {
            out += CapabilityMiss.NodeMiss(node.id, node.label, engine, "node kind $kind")
        } else {
            when (node) {
                is Join ->
                    if (node.type.name.lowercase() !in (entry.types ?: emptyList())) {
                        out +=
                            CapabilityMiss.NodeMiss(
                                node.id,
                                node.label,
                                engine,
                                "join type ${node.type.name.lowercase()}",
                            )
                    }
                is Pivot ->
                    if (entry.native != true) {
                        out += CapabilityMiss.NodeMiss(node.id, node.label, engine, "native pivot")
                    }
                is Aggregate -> {
                    val supported = entry.functions ?: emptyList()
                    for (agg in aggIds(node)) {
                        if (agg !in supported) out += CapabilityMiss.FunctionMiss(node.id, agg, engine)
                    }
                }
                else -> Unit
            }
        }
        // Scalar function support (all nodes carrying expressions).
        for (fid in scalarIds(node)) {
            if (fid !in manifest.functions) out += CapabilityMiss.FunctionMiss(node.id, fid, engine)
        }
        return out
    }

    private fun nodeExpressions(node: Node): List<Expression> =
        when (node) {
            is Filter -> listOfNotNull(node.predicate)
            is Branch -> listOfNotNull(node.predicate)
            is Join -> listOfNotNull(node.on)
            is Aggregate -> node.aggregations.map { it.value } + listOfNotNull(node.having)
            is Project -> node.columns
            is Calc -> node.assignments.map { it.value }
            is Switch -> node.cases.mapNotNull { it.second }
            else -> emptyList()
        }

    private fun scalarIds(node: Node): List<String> =
        nodeExpressions(node)
            .flatMap { collectScalar(it) }
            .distinct()
            // `internal.*` validity fns (reject guards) are never engine-native functions — they lower
            // to raw dialect SQL / prelude helpers in emit (RJ-P3), not the manifest function set.
            .filterNot { it.startsWith("internal.") }

    private fun aggIds(node: Aggregate): List<String> = node.aggregations.flatMap { collectAgg(it.value) }.distinct()

    private fun collectScalar(e: Expression): List<String> =
        when (e) {
            is FunctionCall -> listOf(e.function.value) + e.args.flatMap { collectScalar(it) }
            is AggregateCall -> e.args.flatMap { collectScalar(it) }
            is Cast -> collectScalar(e.expr)
            is CaseWhen ->
                e.branches.flatMap { collectScalar(it.first) + collectScalar(it.second) } +
                    (e.elseExpr?.let { collectScalar(it) } ?: emptyList())
            is InList -> collectScalar(e.expr) + e.items.flatMap { collectScalar(it) }
            is IsNull -> collectScalar(e.expr)
            is ColumnRef, is Literal, is MdPath -> emptyList()
            // AU-core-P0: `:name` bindings and durations call no function.
            is org.tatrman.ttrp.expr.ParamRef, is org.tatrman.ttrp.expr.DurationExpr -> emptyList()
        }

    private fun collectAgg(e: Expression): List<String> =
        when (e) {
            is AggregateCall -> listOf(e.function.value) + e.args.flatMap { collectAgg(it) }
            is FunctionCall -> e.args.flatMap { collectAgg(it) }
            is Cast -> collectAgg(e.expr)
            is CaseWhen ->
                e.branches.flatMap { collectAgg(it.first) + collectAgg(it.second) } +
                    (e.elseExpr?.let { collectAgg(it) } ?: emptyList())
            is InList -> collectAgg(e.expr) + e.items.flatMap { collectAgg(it) }
            is IsNull -> collectAgg(e.expr)
            is ColumnRef, is Literal, is MdPath -> emptyList()
            // AU-core-P0: `:name` bindings and durations call no function.
            is org.tatrman.ttrp.expr.ParamRef, is org.tatrman.ttrp.expr.DurationExpr -> emptyList()
        }

    private fun info(
        id: TtrpDiagnosticId,
        message: String,
    ) = TtrpDiagnostic(id, Severity.INFO, message, org.tatrman.ttrp.ast.SourceLocation.UNKNOWN)
}
