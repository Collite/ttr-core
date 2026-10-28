// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.resolve

import org.tatrman.ttr.metadata.model.QualifiedName
import org.tatrman.ttr.metadata.model.SchemaCode
import org.tatrman.ttr.metadata.registry.RegistrySnapshot
import org.tatrman.ttr.metadata.world.ResolvedWorld
import org.tatrman.ttr.metadata.world.WorldResolution
import org.tatrman.ttr.metadata.world.WorldResolver as MetadataWorldResolver
import org.tatrman.ttrp.ast.SourceLocation
import org.tatrman.ttrp.diagnostics.Severity
import org.tatrman.ttrp.diagnostics.TtrpDiagnostic
import org.tatrman.ttrp.diagnostics.TtrpDiagnosticId
import org.tatrman.ttrp.project.TtrpManifest

/**
 * TTR-P's world-selection + world-resolution wrapper over ttr-metadata's
 * [MetadataWorldResolver] (T1.3.3, D-g offline). Selection precedence is the CALLER's
 * contract (contracts §2): `uses world` pin > `[ttrp] world` > `TTRP-WLD-001`.
 * ttr-metadata's structured, id-free failures are mapped to `TTRP-WLD-*` here (MD5).
 */
object TtrpWorldResolver {
    data class Selection(
        val world: ResolvedWorld?,
        val worldQname: QualifiedName?,
        val diagnostics: List<TtrpDiagnostic>,
    )

    /**
     * [packageOf] must be supplied explicitly. Null selects the legacy path rule and is only for
     * snapshot-only callers without loader identity; checker/build pass the reconciled provider.
     */
    fun resolve(
        snapshot: RegistrySnapshot?,
        manifest: TtrpManifest,
        pin: String?,
        pinLocation: SourceLocation?,
        packageOf: ((org.tatrman.ttr.metadata.model.ModelObject) -> String)?,
    ): Selection {
        val loc =
            pinLocation
                ?: SourceLocation(manifest.manifestDir.resolve("modeler.toml").toString(), 1, 0, 1, 1, -1, -1)
        val worldString = pin ?: manifest.world
        if (worldString == null) {
            return Selection(
                null,
                null,
                listOf(diag(TtrpDiagnosticId.WLD_001, "no world selected for this program", loc)),
            )
        }
        if (snapshot == null) {
            return Selection(
                null,
                null,
                listOf(diag(TtrpDiagnosticId.WLD_002, "world `$worldString` — no model repo to resolve it in", loc)),
            )
        }
        val resolver = MetadataWorldResolver(snapshot, packageOf)
        val worldQ = worldQname(worldString)

        return when (val r = resolver.resolve(worldQ)) {
            is WorldResolution.Ok -> Selection(r.world, worldQ, emptyList())
            is WorldResolution.WorldNotFound -> {
                // Retry as a member qname so a pin naming a non-world def (engine/storage)
                // surfaces NotAWorld (WLD-003) rather than a bare not-found (WLD-002).
                val memberQ = memberQname(worldString)
                if (memberQ != null) {
                    when (val r2 = resolver.resolve(memberQ)) {
                        is WorldResolution.NotAWorld ->
                            Selection(null, worldQ, listOf(notAWorld(worldString, r2.foundKind, loc)))
                        else -> Selection(null, worldQ, listOf(worldNotFound(worldString, r.knownWorlds, loc)))
                    }
                } else {
                    Selection(null, worldQ, listOf(worldNotFound(worldString, r.knownWorlds, loc)))
                }
            }
            is WorldResolution.NotAWorld -> Selection(null, worldQ, listOf(notAWorld(worldString, r.foundKind, loc)))
            is WorldResolution.StagingConflict ->
                Selection(
                    null,
                    worldQ,
                    listOf(
                        diag(
                            TtrpDiagnosticId.WLD_004,
                            "world `$worldString` declares more than one staging storage: " +
                                r.storages.joinToString(", ") { it.name },
                            loc,
                        ),
                    ),
                )
            is WorldResolution.HostsUnknownPackage ->
                Selection(
                    null,
                    worldQ,
                    listOf(
                        diag(
                            TtrpDiagnosticId.WLD_002,
                            "world `$worldString` cannot resolve: storage `${r.storageQname.name}` " +
                                "hosts unknown package `${r.pkg}`",
                            loc,
                        ),
                    ),
                )
            is WorldResolution.ExtendsUnresolved ->
                Selection(
                    null,
                    worldQ,
                    listOf(
                        diag(
                            TtrpDiagnosticId.WLD_002,
                            "world `$worldString` cannot resolve: `${r.onDef.name}` extends unresolved " +
                                "type `${r.typeRef}`",
                            loc,
                        ),
                    ),
                )
        }
    }

    private fun worldNotFound(
        worldString: String,
        known: List<QualifiedName>,
        loc: SourceLocation,
    ): TtrpDiagnostic {
        val knownList = known.joinToString(", ") { "${it.`package`}.${it.name}" }.ifEmpty { "(none)" }
        return diag(
            TtrpDiagnosticId.WLD_002,
            "no `def world` named `$worldString` in the model repo — known worlds: $knownList",
            loc,
        )
    }

    private fun notAWorld(
        worldString: String,
        foundKind: String,
        loc: SourceLocation,
    ): TtrpDiagnostic =
        diag(
            TtrpDiagnosticId.WLD_003,
            "`$worldString` is a `$foundKind`, not a `def world`",
            loc,
            suggestion = "name a `def world`; `$worldString` is a `$foundKind`",
        )

    /** World qname: `pkg.<name>` → (WORLD, namespace="", name, package=pkg). */
    private fun worldQname(dotted: String): QualifiedName {
        val parts = dotted.split('.')
        return if (parts.size >= 2) {
            QualifiedName(SchemaCode.WORLD, "", parts.last(), parts.dropLast(1).joinToString("."))
        } else {
            QualifiedName(SchemaCode.WORLD, "", dotted, "")
        }
    }

    /** Member qname (WLD-003 fallback): `pkg.<world>.<member>` → (WORLD, namespace=world, name=member, package=pkg). */
    private fun memberQname(dotted: String): QualifiedName? {
        val parts = dotted.split('.')
        if (parts.size < 3) return null
        return QualifiedName(SchemaCode.WORLD, parts[parts.size - 2], parts.last(), parts.dropLast(2).joinToString("."))
    }

    private fun diag(
        id: TtrpDiagnosticId,
        message: String,
        loc: SourceLocation,
        suggestion: String? = id.suggestedAlternative,
    ) = TtrpDiagnostic(id, Severity.ERROR, message, loc, suggestion)
}
