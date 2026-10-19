// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.graph.capability

import org.tatrman.ttr.metadata.world.ResolvedEngine
import org.tatrman.ttr.metadata.world.ResolvedExecutor
import org.tatrman.ttr.metadata.world.ResolvedStorage
import org.tatrman.ttr.metadata.world.ResolvedWorld
import org.tatrman.ttrp.ast.SourceLocation
import org.tatrman.ttrp.diagnostics.Severity
import org.tatrman.ttrp.diagnostics.TtrpDiagnostic
import org.tatrman.ttrp.diagnostics.TtrpDiagnosticId

data class BoundEngine(
    val engine: ResolvedEngine,
    val manifest: EngineTypeManifest,
)

data class BoundExecutor(
    val executor: ResolvedExecutor,
    val manifest: EngineTypeManifest,
)

/**
 * The resolved world with each engine/executor bound to its shipped capability
 * manifest (T6-b type layer). ttr-metadata already applied the instance-⊕-extends-type
 * overlay (RM7); this binder resolves the engine's `type` (+ major version) or its raw
 * `extends` id (`extendsRef`) against the shipped manifests (the RM6 seam).
 */
data class BoundWorld(
    val world: ResolvedWorld,
    val engines: Map<String, BoundEngine>,
    val executors: Map<String, BoundExecutor>,
    val storages: List<ResolvedStorage>,
    val diagnostics: List<TtrpDiagnostic>,
)

class WorldBinder(
    private val manifests: ManifestSource,
) {
    fun bind(world: ResolvedWorld): BoundWorld {
        val diags = mutableListOf<TtrpDiagnostic>()
        val engines = LinkedHashMap<String, BoundEngine>()
        for (e in world.engines) {
            val m = matchData(e.type, e.version, e.extendsRef)
            if (m == null) {
                diags +=
                    diag(
                        TtrpDiagnosticId.WLD_005,
                        "unknown engine type for `${e.qname.name}` (type=${e.type}, extends=${e.extendsRef})",
                        SourceLocation.UNKNOWN,
                    )
            } else {
                engines[e.qname.name] = BoundEngine(e, m)
            }
        }
        val executors = LinkedHashMap<String, BoundExecutor>()
        for (x in world.executors) {
            // An automation executor (`extends: n8n_l1`, …) runs recipe programs, not TTR-P bundles: its type
            // manifest is the exporters' registry's (AU D-35), so the TTR-P binder leaves it unbound (AU-core-P1).
            if (x.extendsRef in AUTOMATION_EXECUTOR_TYPES) continue
            val m = matchExecution(x.type, x.extendsRef)
            if (m == null) {
                diags +=
                    diag(
                        TtrpDiagnosticId.WLD_005,
                        "unknown executor type for `${x.qname.name}` (type=${x.type}, extends=${x.extendsRef})",
                        SourceLocation.UNKNOWN,
                    )
            } else {
                executors[x.qname.name] = BoundExecutor(x, m)
            }
        }
        return BoundWorld(world, engines, executors, world.storages, diags)
    }

    private fun matchData(
        type: String?,
        version: String?,
        extendsRef: String?,
    ): EngineTypeManifest? =
        manifests.all().firstOrNull { it.kind == ManifestKind.DATA && matches(it, type, version, extendsRef) }

    private fun matchExecution(
        type: String?,
        extendsRef: String?,
    ): EngineTypeManifest? =
        manifests.all().firstOrNull { it.kind == ManifestKind.EXECUTION && matches(it, type, null, extendsRef) }

    private fun matches(
        m: EngineTypeManifest,
        type: String?,
        version: String?,
        extendsRef: String?,
    ): Boolean {
        if (extendsRef != null && extendsRef == m.id) return true
        if (type != null && type == m.type) {
            // A manifest with no pinned major matches any version; a declared version must
            // parse and equal the manifest's major. A present-but-unparseable version (`"16beta"`,
            // `"latest"`) is NOT treated as version-agnostic — it falls through to `WLD-005`.
            val major = version?.substringBefore('.')?.toIntOrNull()
            return m.versionMajor == null || version == null || m.versionMajor == major
        }
        return false
    }

    private fun diag(
        id: TtrpDiagnosticId,
        message: String,
        location: SourceLocation,
    ) = TtrpDiagnostic(id, Severity.ERROR, message, location)

    companion object {
        /** The automation executor types (AU exporters contracts §1, spelled as TTR-M ids). */
        val AUTOMATION_EXECUTOR_TYPES =
            setOf("native", "n8n_l1", "camunda_7", "camunda_8", "python_client", "python_offline", "sql_job")
    }
}
