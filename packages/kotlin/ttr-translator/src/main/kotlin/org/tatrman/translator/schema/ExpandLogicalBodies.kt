// SPDX-License-Identifier: Apache-2.0
package org.tatrman.translator.schema

import org.tatrman.plan.v1.PlanNode
import org.tatrman.plan.v1.QualifiedName
import org.tatrman.plan.v1.SchemaCode
import org.tatrman.plan.v1.schemaCodeToToken
import org.tatrman.translator.framework.EntityMapping
import org.tatrman.translator.framework.ModelHandle
import org.tatrman.translator.joiner.JoinerPlanWalker

/**
 * LQ (logical queries, contracts L-8/L-9) — EXPAND_LOGICAL_BODIES, the last stage of the ER half, opt-in.
 *
 * An entity **stacked** on a logical query (`EntityMapping.ToQuery` onto a saved query written over entities,
 * whose stored body is an ER-level plan, L-3) is, until MAP_TO_PHYSICAL, a single `Scan(ER, stacked)`: the
 * entities its query reads are invisible to anything that inspects the ER plan — the validator's first pass
 * among them, so a row policy keyed on an entity read only inside the body would never apply (F-LQ-1). This
 * stage expands each such scan **in place, still at the ER level**:
 *
 *     Scan(ER, e)  ⟶  Project(expanded_from = e, <alias-at-boundary>)( <e's stored ER body> )
 *
 * The Project is [MapToPhysical.boundaryProject] — the one implementation MAP_TO_PHYSICAL uses when it inlines
 * the same body later — stamped with the stacked entity's qname (plan.v1 `ProjectNode.expanded_from`, L-11),
 * which is where the validator matches a policy keyed on the stacked entity itself (L-13). Bodies are
 * expanded recursively (a body may read another stacked entity), with `visited` catching a stack that reads
 * itself (`entity_query_mapping_cycle`, the code MAP_TO_PHYSICAL reports).
 *
 * **Left alone** — MAP_TO_PHYSICAL inlines them exactly as with the stage off:
 *  - a body containing any table scan (a db-lane query: the `__filter` row-filter queries, a physical
 *    `source.query`) — expanding it would mix ER and DB scans in one plan, which the validator rejects (L-9
 *    holds by construction);
 *  - a body the model cannot resolve (MAP_TO_PHYSICAL reports `entity_query_mapping_unresolved`).
 *
 * **Refused** — a stacked scan that declares no columns has no boundary to stamp (`entity_query_no_columns`):
 * left unexpanded, the entities its body reads would stay invisible to the validator's ER pass, the very gap
 * this stage closes — so it fails closed rather than pass the scan on silently.
 *
 * Idempotent: an expanded plan has no eligible scan left. The stamp survives a REL_NODE re-entry (carried as a
 * Calcite hint, [org.tatrman.translator.wire.ExpandedFromHint]); MAP_TO_PHYSICAL closes each stamped boundary
 * into the same form it gives an unexpanded `ToQuery` boundary and ends the stamp — so the physical plan is the
 * same with the stage on or off (L-10).
 */
object ExpandLogicalBodies {
    fun apply(
        plan: PlanNode,
        model: ModelHandle,
    ): ExpandLogicalBodiesResult =
        try {
            ExpandLogicalBodiesResult.Success(rewrite(plan, model, visited = emptySet()))
        } catch (e: ExpandLogicalBodiesException) {
            ExpandLogicalBodiesResult.Failure(e.code, e.message ?: "")
        }

    private fun rewrite(
        plan: PlanNode,
        model: ModelHandle,
        visited: Set<QualifiedName>,
    ): PlanNode {
        val withChildren = JoinerPlanWalker.rewriteChildren(plan) { rewrite(it, model, visited) }
        if (withChildren.nodeCase != PlanNode.NodeCase.SCAN) return withChildren
        if (withChildren.scan.getObject().schemaCode != SchemaCode.ER) return withChildren
        return expandIfLogical(withChildren, model, visited)
    }

    private fun expandIfLogical(
        scanPlan: PlanNode,
        model: ModelHandle,
        visited: Set<QualifiedName>,
    ): PlanNode {
        val entity = scanPlan.scan.getObject()
        val mapping = model.entityMapping(entity) as? EntityMapping.ToQuery ?: return scanPlan
        val body = MapToPhysical.resolveQueryBody(mapping.query, model) ?: return scanPlan
        if (readsTables(body)) return scanPlan
        if (entity in visited) {
            throw ExpandLogicalBodiesException(
                "entity_query_mapping_cycle",
                "Cycle expanding entity ${qnameToken(entity)} through its backing query",
            )
        }
        val expandedBody = rewrite(body, model, visited + entity)
        val boundary =
            MapToPhysical.boundaryProject(scanPlan, model, expandedBody)
                ?: throw ExpandLogicalBodiesException(
                    "entity_query_no_columns",
                    "Entity ${qnameToken(entity)} is stacked on a logical query but its scan declares no " +
                        "columns: its body cannot be expanded under a boundary, and left unexpanded the entities " +
                        "it reads would escape the ER-level checks",
                )
        return PlanNode
            .newBuilder()
            .setProject(boundary.setExpandedFrom(entity))
            .build()
    }

    /** True when [plan] scans a table anywhere — expression sub-queries included. */
    private fun readsTables(plan: PlanNode): Boolean =
        JoinerPlanWalker.anyNode(plan) { it.nodeCase == PlanNode.NodeCase.TABLE_SCAN }

    private fun qnameToken(qn: QualifiedName): String = "${schemaCodeToToken(qn.schemaCode)}.${qn.namespace}.${qn.name}"
}

/** Result of [ExpandLogicalBodies.apply]; the orchestrator turns a [Failure] into `ParseResult.Failure`. */
sealed interface ExpandLogicalBodiesResult {
    data class Success(
        val plan: PlanNode,
    ) : ExpandLogicalBodiesResult

    data class Failure(
        val code: String,
        val message: String,
    ) : ExpandLogicalBodiesResult
}

private class ExpandLogicalBodiesException(
    val code: String,
    message: String,
) : RuntimeException(message)
