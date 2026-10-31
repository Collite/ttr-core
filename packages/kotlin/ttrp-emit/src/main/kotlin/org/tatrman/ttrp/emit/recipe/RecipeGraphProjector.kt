// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.emit.recipe

import org.tatrman.ttr.metadata.world.AutomationFingerprints

import org.tatrman.ttr.metadata.model.World
import org.tatrman.ttr.metadata.model.WorldDeployment
import org.tatrman.ttr.metadata.model.WorldProcess
import org.tatrman.ttr.metadata.model.AutomationProgram
import org.tatrman.ttr.metadata.model.AutomationAction
import org.tatrman.ttrp.bundle.RunManifest
import org.tatrman.ttrp.emit.spi.RecipeGraph
import org.tatrman.ttrp.emit.spi.RecipeMeta
import org.tatrman.ttrp.emit.spi.ProgramMeta2
import org.tatrman.ttrp.emit.spi.DeploymentMeta
import org.tatrman.ttrp.emit.spi.Roles
import org.tatrman.ttrp.emit.spi.ContextMeta
import org.tatrman.ttrp.emit.spi.RouteMeta
import org.tatrman.ttrp.emit.spi.WiringMeta
import org.tatrman.ttrp.emit.spi.ActionMeta
import org.tatrman.ttrp.emit.spi.ProcessMeta
import org.tatrman.ttrp.emit.spi.StepMeta
import org.tatrman.ttrp.emit.spi.RunApiMeta
import org.tatrman.ttrp.emit.spi.ParameterMeta
import org.tatrman.ttrp.graph.capability.RegistryGrid

object RecipeGraphProjector {
    fun project(
        bundle: RunManifest,
        world: World,
        program: AutomationProgram,
        deployment: WorldDeployment,
        actions: List<AutomationAction>,
        granularity: String =
            deployment.granularity
                ?: "parts",
        process: WorldProcess? = null,
        stepGraphs: Map<String, RecipeGraph> = emptyMap(),
    ): RecipeGraph {
        require(bundle.schemaVersion == 3) { "recipe export requires manifest v3" }
        val recipe = requireNotNull(bundle.recipe) { "bundle has no recipe" }
        require(program.recipe == recipe.name) {
            "program ${program.qname.name} uses ${program.recipe}, bundle holds ${recipe.name}"
        }
        val islandNames = bundle.islands.map { it.name }
        val waveNames = bundle.waves.flatten()
        require(
            islandNames.distinct().size == islandNames.size &&
                waveNames.distinct().size == waveNames.size &&
                waveNames.toSet() == islandNames.toSet(),
        ) {
            "bundle waves must name every island exactly once"
        }
        if (process != null) {
            require(process.steps.isNotEmpty()) { "process has no steps" }
            require(stepGraphs.keys == process.steps.map { it.name }.toSet()) { "process needs every step graph" }
            process.steps.forEach { step ->
                val graph = stepGraphs.getValue(step.name)
                require(
                    graph.process == null &&
                        graph.program.name == step.program &&
                        graph.deployment.name == deployment.qname.name &&
                        graph.deployment.target == deployment.target &&
                        graph.program.port == (step.port ?: graph.program.port),
                ) { "invalid process step projection ${step.name}" }
            }
        } else {
            require(stepGraphs.isEmpty()) { "step graphs require a process" }
        }
        val type = RegistryGrid.executorType(world, deployment)
        val executor = world.executors.singleOrNull { it.qname.name == deployment.target }
        val wired =
            recipe.wiring.map { it.action }.distinct().sorted().associateWith { name ->
                val a =
                    requireNotNull(
                        actions.singleOrNull { it.qname.name == name },
                    ) { "unknown or ambiguous action $name" }
                ActionMeta(
                    a.columns.associate { it.name to it.type },
                    a.sinks.toSortedMap(),
                    a.approval,
                    a.deliverable,
                    a.due?.let { "${it.amount} ${it.unit}" },
                    a.dryRun.byLanguage.toSortedMap(),
                    a.transition?.let { t ->
                        buildMap {
                            put("attribute", t.attribute)
                            t.to?.let { put("to", it) }
                            t.toColumn?.let { put("toColumn", it) }
                            put("from", t.from.joinToString(","))
                        }
                    },
                )
            }
        return RecipeGraph(
            RecipeMeta(
                recipe.name,
                recipe.key,
                recipe.asOf,
                recipe.parameters.map { ParameterMeta(it.name, it.type, it.value) },
                sortedMapOf(
                    "text" to recipe.fingerprints.text,
                    "values" to recipe.fingerprints.values,
                    "labels" to recipe.fingerprints.labels,
                ),
                recipe.mode,
                recipe.kinds,
            ),
            ProgramMeta2(
                program.qname.name,
                program.port,
                program.mode,
                program.asOf,
                program.property("deliver")?.let { AutomationFingerprints.render(it) },
                program.schedule,
                program.overrides.associate { it.first to AutomationFingerprints.render(it.second) }.toSortedMap(),
                buildMap {
                    program.budgetSync?.let { put("sync", "${it.amount} ${it.unit}") }
                    program.budgetBatch?.let { put("batch", "${it.amount} ${it.unit}") }
                },
            ),
            DeploymentMeta(
                deployment.qname.name,
                deployment.target,
                type,
                executor?.version ?: "1",
                AutomationFingerprints.runAsWire(deployment.runAs),
                granularity,
                deployment.dryRun,
                deployment.ungoverned,
                connectors(deployment),
            ),
            Roles(
                recipe.roles.candidates,
                recipe.roles.contexts.map {
                    ContextMeta(it.name, it.contextClass, it.kind)
                },
                recipe.roles.decision,
                recipe.roles.switchMode,
            ),
            recipe.roles.routes.map {
                RouteMeta(
                    it.name,
                    it.because,
                    it.detail,
                    it.turnFed,
                    it.name == recipe.roles.elseRoute,
                    false,
                )
            } +
                if (recipe.roles.elseRoute ==
                    "none"
                ) {
                    listOf(RouteMeta("none", null, emptyList(), false, true, true))
                } else {
                    emptyList()
                },
            recipe.wiring.map { WiringMeta(it.routes, it.action, it.columns.toSortedMap(), it.whenCondition) },
            wired,
            process?.let {
                ProcessMeta(
                    it.qname.name,
                    it.instance,
                    it.steps.map { s ->
                        StepMeta(
                            s.name,
                            s.program,
                            s.port,
                            s.after?.obligations.orEmpty(),
                            s.after?.time?.let {
                                "${it.amount} ${it.unit}"
                            },
                            s.after?.until.orEmpty(),
                            stepGraphs.getValue(s.name),
                        )
                    },
                )
            },
            RunApiMeta(
                "/automation",
                sortedMapOf(
                    "open" to "/recipes/{recipe}/runs",
                    "evaluate" to "/runs/{id}/evaluate",
                    "part" to "/runs/{id}/parts/{part}",
                    "island" to "/runs/{id}/islands/{island}",
                    "status" to "/runs/{id}",
                    "route" to "/runs/{id}/routes/{route}",
                    "detail" to "/runs/{id}/routes/{route}/details/{context}",
                    "perform" to "/runs/{id}/actions/{action}/perform",
                    "outcome" to "/runs/{id}/actions/{action}/outcome",
                    "digests" to "/runs/{id}/digests",
                    "batch" to "/runs/{id}/batch",
                    "close" to "/runs/{id}/close",
                ),
            ),
            bundle.waves.map { it.toList() },
        )
    }

    private fun connectors(deployment: WorldDeployment): Map<String, String> {
        val nested =
            deployment.properties.lastOrNull { it.first == "connectors" }?.second
                as? org.tatrman.ttr.parser.model.AutoValue.Obj
        val entries = nested?.entries.orEmpty().map { it.key to it.value } + deployment.properties
        return entries
            .filter { it.first in setOf("deliver", "ask", "start") }
            .associate { (name, value) ->
                name to
                    if (value is org.tatrman.ttr.parser.model.AutoValue.Str) {
                        value.raw
                    } else {
                        AutomationFingerprints.render(
                            value,
                        )
                    }
            }.toSortedMap()
    }

    fun sink(
        action: ActionMeta,
        type: String,
    ): String = action.sinks[type] ?: action.sinks[type.substringBefore('_')] ?: action.sinks["native"] ?: "no"
}
