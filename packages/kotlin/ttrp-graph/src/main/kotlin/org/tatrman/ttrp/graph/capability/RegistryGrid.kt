// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.graph.capability

import org.tatrman.ttr.metadata.model.AutomationProgram
import org.tatrman.ttr.metadata.model.World
import org.tatrman.ttr.metadata.model.WorldDeployment
import org.tatrman.ttrp.diagnostics.TtrpDiagnostic
import org.tatrman.ttrp.diagnostics.TtrpDiagnosticId
import org.tatrman.ttrp.diagnostics.Severity
import org.tatrman.ttrp.ast.SourceLocation

/** The runtime only needs these bundle facts to compute its grid; SQL and compiler internals stay out. */
data class RegistryRecipe(
    val kinds: List<String>,
    val sinks: Map<String, String>,
    val actionSinks: Map<String, Map<String, String>> = emptyMap(),
)

data class RegistryBundle(
    val programs: Map<String, AutomationProgram>,
    val recipes: Map<String, RegistryRecipe>,
)

data class RegistryCell(
    val deployment: String,
    val program: String,
    val type: String,
    val mode: String,
    val diagnostics: List<TtrpDiagnostic>,
) {
    val ok: Boolean get() = diagnostics.isEmpty()
}

class RegistryGrid(
    private val manifests: RecipeManifestSource,
) {
    fun compute(
        world: World,
        bundle: RegistryBundle,
    ): List<RegistryCell> =
        world.deployments.sortedBy { it.qname.name }.map { d ->
            val program = requireNotNull(bundle.programs[d.program]) { "unknown program ${d.program}" }
            val recipe = requireNotNull(bundle.recipes[program.recipe]) { "missing bundle for ${program.recipe}" }
            val type = executorType(world, d)
            val manifest = requireNotNull(manifests.load(type)) { "unknown executor type $type" }
            RegistryCell(
                d.qname.name,
                d.program,
                type,
                program.mode ?: "",
                ExecutorManifestGate.checkRecipe(
                    manifest,
                    program,
                    d,
                    recipe.copy(
                        sinks =
                            recipe.sinks +
                                recipe.actionSinks.mapValues { (_, sinks) ->
                                    sinks[type] ?: sinks[type.substringBefore('_')]
                                        ?: sinks["native"]
                                        ?: "no"
                                },
                    ),
                ),
            )
        }

    companion object {
        /**
         * The type of the world executor [deployment] targets. Grammar 0.22 (D-142): there is no `target: native`
         * pseudo-executor any more — the Server is an executor of the world (`def executor server { extends: native }`),
         * and an executor named `native` is resolved like any other.
         */
        fun executorType(
            world: World,
            deployment: WorldDeployment,
        ): String {
            val executor =
                requireNotNull(
                    world.executors.singleOrNull {
                        it.qname.name == deployment.target
                    },
                ) { "unknown executor ${deployment.target}" }
            return requireNotNull(
                executor.extendsRef ?: executor.type,
            ) { "executor ${deployment.target} has no type" }.replace('-', '_')
        }
    }
}

/** The Server's executor type. */
private const val NATIVE = "native"

internal fun recipeCellCheck(
    manifest: RecipeExecutorManifest,
    program: AutomationProgram,
    deployment: WorldDeployment,
    recipe: RegistryRecipe,
): List<TtrpDiagnostic> =
    buildList {
        fun refuse(
            id: TtrpDiagnosticId,
            text: String,
        ) {
            add(TtrpDiagnostic(id, Severity.ERROR, "${deployment.qname.name}: $text", SourceLocation.UNKNOWN))
        }
        if (program.mode !in
            manifest.values("modes")
        ) {
            refuse(TtrpDiagnosticId.TGT_001, "mode ${program.mode} not supported by ${manifest.id}")
        }
        for (kind in recipe.kinds) {
            val base = kind.substringBefore(':')
            if (manifest.entries("kinds")[base] in
                listOf(null, "no")
            ) {
                refuse(TtrpDiagnosticId.TGT_002, "island kind $kind not supported by ${manifest.id}")
            }
        }
        for ((action, sink) in recipe.sinks) {
            val supported = manifest.entries("sinks")
            // D-142: the Server records the action row of every wired action. One with no native sink is that row
            // only — the engine acts on it (a send task, a user task, a call activity) — so the Server takes it.
            if (sink == "no" && manifest.id == NATIVE) continue
            if (sink == "no" ||
                (
                    supported["all"] in listOf(null, "no") &&
                        supported[sink] in listOf(null, "no") &&
                        sink !in supported.values.filter { it != "no" }
                )
            ) {
                refuse(TtrpDiagnosticId.TGT_003, "action $action sink $sink not supported by ${manifest.id}")
            }
        }
        val granularity = deployment.granularity ?: "parts"
        if (granularity !in
            manifest.values("granularity")
        ) {
            refuse(TtrpDiagnosticId.TGT_004, "granularity $granularity not supported by ${manifest.id}")
        }
        if (manifest.flag("ungoverned") &&
            !deployment.ungoverned
        ) {
            refuse(TtrpDiagnosticId.TGT_005, "${manifest.id} requires ungoverned: true on the deployment")
        }
    }
