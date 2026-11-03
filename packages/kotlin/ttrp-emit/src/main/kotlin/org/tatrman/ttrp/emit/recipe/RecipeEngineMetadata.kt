// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.emit.recipe

import org.tatrman.ttr.metadata.model.AutomationAction
import org.tatrman.ttr.metadata.model.World
import org.tatrman.ttrp.bundle.RecipeAction
import org.tatrman.ttrp.bundle.RecipeDeployment
import org.tatrman.ttrp.bundle.RecipeDuration
import org.tatrman.ttrp.bundle.RecipeExecutor
import org.tatrman.ttrp.graph.TtrpPipeline
import org.tatrman.ttrp.graph.recipe.RecipeModel

/** One projection shared by the catalog and manifest; empty additions are omitted for byte compatibility. */
data class RecipeEngineMetadata(
    val actions: Map<String, RecipeAction>? = null,
    val deployments: Map<String, RecipeDeployment>? = null,
    val executors: Map<String, RecipeExecutor>? = null,
) {
    companion object {
        fun capture(
            plan: TtrpPipeline.PlanResult,
            model: RecipeModel,
            actions: Map<String, AutomationAction>,
        ): RecipeEngineMetadata {
            val world =
                plan.frontendReport
                    ?.modelIndex
                    ?.objectsByQname
                    ?.values
                    ?.filterIsInstance<World>()
                    ?.singleOrNull { it.qname == plan.bound?.world?.qname }
            val deployments = world?.deployments.orEmpty().filter { it.program in model.programs }
            val targets = deployments.map { it.target }.toSet()
            return RecipeEngineMetadata(
                actions =
                    actions
                        .toSortedMap()
                        .mapNotNull { (name, action) ->
                            if (action.byRole == null && action.due == null) {
                                null
                            } else {
                                name to
                                    RecipeAction(
                                        by = action.byRole?.let { "role.$it" },
                                        due = action.due?.let { RecipeDuration(it.amount, it.unit) },
                                    )
                            }
                        }.toMap()
                        .ifEmpty { null },
                deployments =
                    deployments
                        .sortedBy { it.qname.name }
                        .mapNotNull { deployment ->
                            deployment.startableBy?.let { roles ->
                                deployment.qname.name to
                                    RecipeDeployment(roles.map { "role.$it" })
                            }
                        }.toMap()
                        .ifEmpty { null },
                executors =
                    world
                        ?.executors
                        .orEmpty()
                        .filter { it.qname.name in targets }
                        .sortedBy { it.qname.name }
                        .mapNotNull { executor ->
                            executor.egressUrl?.let { executor.qname.name to RecipeExecutor(it) }
                        }.toMap()
                        .ifEmpty { null },
            )
        }
    }
}
