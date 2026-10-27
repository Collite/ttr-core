// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.metadata.world

import org.tatrman.ttr.metadata.model.ModelObject
import org.tatrman.ttr.metadata.model.QualifiedName
import org.tatrman.ttr.metadata.model.SchemaCode
import org.tatrman.ttr.metadata.model.World
import org.tatrman.ttr.metadata.model.WorldEngine
import org.tatrman.ttr.metadata.model.WorldExecutor
import org.tatrman.ttr.metadata.model.WorldSchema
import org.tatrman.ttr.metadata.model.WorldStorage
import org.tatrman.ttr.metadata.registry.RegistrySnapshot
import org.tatrman.ttr.parser.model.PropertyValue

/**
 * The TTR-P-facing world resolution API (M2 — the half the kantheon metadata service never had; closes
 * the mechanism side of R2). Applies the instance-⊕-extends-type overlay, validates
 * exactly-one staging (D-f), maps `hosts:` to loaded packages (D-d-i), and reports
 * structured, id-free failures (MD5 — fields only; consumers render diagnostics).
 */
class WorldResolver(
    private val snapshot: RegistrySnapshot,
    /** Loader-owned declared-or-computed packages. When supplied, checkout paths never grant host scope. */
    private val packageOf: ((ModelObject) -> String)?,
) {
    /** Compatibility constructor for snapshot-only consumers without loader package identity. */
    constructor(snapshot: RegistrySnapshot) : this(snapshot, null)

    private val worldSchema: WorldSchema? =
        snapshot.model.schemas["world"] as? WorldSchema

    private val worlds: Map<QualifiedName, World> = worldSchema?.worlds ?: emptyMap()

    // Overlay lookup: engine/storage type defs are found by bare name across all
    // loaded worlds, or by full qname (dotted extends).
    private val engineByName: Map<String, WorldEngine> =
        worlds.values.flatMap { it.engines }.associateBy { it.qname.name }
    private val storageByName: Map<String, WorldStorage> =
        worlds.values.flatMap { it.storages }.associateBy { it.qname.name }
    private val engineByQname: Map<QualifiedName, WorldEngine> =
        worlds.values.flatMap { it.engines }.associateBy { it.qname }
    private val storageByQname: Map<QualifiedName, WorldStorage> =
        worlds.values.flatMap { it.storages }.associateBy { it.qname }

    // Older snapshot-only callers have no loader package map. Keep that constructor's legacy
    // behavior; package-aware consumers must supply the complete reconciled loader provider.
    private fun packageLoaded(pkg: String): Boolean =
        snapshot.model.objectByQname().values.any { obj ->
            if (packageOf == null) {
                obj.sourceFile.contains("/$pkg/")
            } else {
                val declared = packageOf.invoke(obj)
                declared == pkg || declared.startsWith("$pkg.")
            }
        }

    fun listWorlds(): List<QualifiedName> = worlds.keys.sortedBy { it.dotted() }

    fun resolve(worldQname: QualifiedName): WorldResolution {
        val world =
            worlds[worldQname]
                ?: return notFoundOrWrongKind(worldQname)

        // Overlay each member.
        val engines = mutableListOf<ResolvedEngine>()
        for (e in world.engines) {
            when (val ov = overlayEngine(e, mutableSetOf())) {
                is OverlayResult.Ok -> engines += ov.value
                is OverlayResult.Unresolved -> return WorldResolution.ExtendsUnresolved(
                    ov.typeRef,
                    e.qname,
                    e.sourceLocation,
                )
            }
        }
        val executors = world.executors.map { resolveExecutor(it) }
        val storages = mutableListOf<ResolvedStorage>()
        for (s in world.storages) {
            when (val ov = overlayStorage(s, mutableSetOf())) {
                is OverlayResult.Ok -> storages += ov.value
                is OverlayResult.Unresolved -> return WorldResolution.ExtendsUnresolved(
                    ov.typeRef,
                    s.qname,
                    s.sourceLocation,
                )
            }
        }

        // D-f: exactly one staging.
        val stagingStorages = storages.filter { it.staging }
        if (stagingStorages.size > 1) {
            return WorldResolution.StagingConflict(
                storages = stagingStorages.map { it.qname },
                locations = stagingStorages.mapNotNull { it.sourceLocation },
            )
        }

        // D-d-i: every hosts entry must name a loaded model package.
        for (s in storages) {
            for (pkg in s.hosts) {
                if (!packageLoaded(pkg)) {
                    return WorldResolution.HostsUnknownPackage(pkg, s.qname, s.sourceLocation)
                }
            }
        }

        val resolved =
            ResolvedWorld(
                qname = world.qname,
                engines = engines,
                executors = executors,
                storages = storages,
                staging = stagingStorages.singleOrNull(),
                fingerprint = "sha256:pending",
            )
        // Fingerprint the resolved (post-overlay) world (contracts §5, F-f-ii).
        return WorldResolution.Ok(resolved.copy(fingerprint = WorldFingerprint.of(resolved)))
    }

    private fun notFoundOrWrongKind(qname: QualifiedName): WorldResolution {
        val obj = snapshot.model.objectByQname()[qname]
        return if (obj != null) {
            val loc =
                when (obj) {
                    is WorldEngine -> obj.sourceLocation
                    is WorldExecutor -> obj.sourceLocation
                    is WorldStorage -> obj.sourceLocation
                    is World -> obj.sourceLocation
                    else -> null
                }
            WorldResolution.NotAWorld(qname, obj.kind, loc)
        } else {
            WorldResolution.WorldNotFound(qname, worlds.keys.sortedBy { it.dotted() })
        }
    }

    // ----- overlay -----

    private sealed interface OverlayResult<out T> {
        data class Ok<T>(
            val value: T,
        ) : OverlayResult<T>

        data class Unresolved(
            val typeRef: String,
        ) : OverlayResult<Nothing>
    }

    /** Resolve an `extends` ref to a type def, or null if not resolvable in-model. */
    private fun lookupEngine(ref: String): WorldEngine? =
        if (ref.contains('.')) engineByQname[parseRef(ref)] else engineByName[ref]

    private fun lookupStorage(ref: String): WorldStorage? =
        if (ref.contains('.')) storageByQname[parseRef(ref)] else storageByName[ref]

    private fun overlayEngine(
        e: WorldEngine,
        seen: MutableSet<QualifiedName>,
    ): OverlayResult<ResolvedEngine> {
        val ref = e.extendsRef
        if (ref == null) {
            return OverlayResult.Ok(ResolvedEngine(e.qname, e.type, e.version, null, e.manifest, e.sourceLocation))
        }
        val type = lookupEngine(ref)
        if (type == null) {
            // Dotted (qname-shaped) refs must resolve; bare ids pass through for the
            // compiler's ManifestSource join (contracts §3 / T2.1.3 rule).
            return if (ref.contains('.')) {
                OverlayResult.Unresolved(ref)
            } else {
                OverlayResult.Ok(ResolvedEngine(e.qname, e.type, e.version, ref, e.manifest, e.sourceLocation))
            }
        }
        if (!seen.add(e.qname)) return OverlayResult.Unresolved(ref) // cycle
        val base = overlayEngine(type, seen)
        if (base is OverlayResult.Unresolved) return base
        val b = (base as OverlayResult.Ok).value
        return OverlayResult.Ok(
            ResolvedEngine(
                qname = e.qname,
                type = e.type ?: b.type,
                version = e.version ?: b.version,
                extendsRef = ref,
                manifest = mergeManifest(b.manifest, e.manifest),
                sourceLocation = e.sourceLocation,
            ),
        )
    }

    private fun overlayStorage(
        s: WorldStorage,
        seen: MutableSet<QualifiedName>,
    ): OverlayResult<ResolvedStorage> {
        val ref = s.extendsRef
        if (ref == null) return OverlayResult.Ok(s.toResolved(null))
        val type = lookupStorage(ref)
        if (type == null) {
            return if (ref.contains('.')) OverlayResult.Unresolved(ref) else OverlayResult.Ok(s.toResolved(ref))
        }
        if (!seen.add(s.qname)) return OverlayResult.Unresolved(ref)
        val base = overlayStorage(type, seen)
        if (base is OverlayResult.Unresolved) return base
        val b = (base as OverlayResult.Ok).value
        return OverlayResult.Ok(
            ResolvedStorage(
                qname = s.qname,
                type = s.type ?: b.type,
                via = s.via ?: b.via,
                // Lists are replaced wholesale, not element-merged (T2.1.3 rule 3).
                hosts = s.hosts.ifEmpty { b.hosts },
                // Staging: boolean analog of instance-wins/type-fills — an instance
                // that declares `staging: true` wins; a `false`/absent instance inherits
                // the type's flag (a staging storage type propagates to its instances).
                staging = s.staging || b.staging,
                schemas = s.resolvedSchemas().ifEmpty { b.schemas },
                extendsRef = ref,
                manifest = mergeManifest(b.manifest, s.manifest),
                sourceLocation = s.sourceLocation,
            ),
        )
    }

    private fun resolveExecutor(e: WorldExecutor): ResolvedExecutor =
        ResolvedExecutor(e.qname, e.type, e.version, e.extendsRef, e.manifest, e.sourceLocation)

    private fun WorldStorage.toResolved(extendsPassThrough: String?): ResolvedStorage =
        ResolvedStorage(
            qname,
            type,
            via,
            hosts,
            staging,
            resolvedSchemas(),
            extendsPassThrough,
            manifest,
            sourceLocation,
        )

    private fun WorldStorage.resolvedSchemas(): List<ResolvedStorageSchema> =
        schemas.map { ResolvedStorageSchema(it.qname, it.fields, it.sourceLocation) }

    // Manifest overlay: instance wins WHOLESALE — a non-empty instance manifest
    // fully replaces the type's; an empty instance inherits the type's. Contracts §3
    // (changelog v1.2): "lists/manifest replaced wholesale (not element-merged)".
    // Mirrors the `hosts`/`schemas` list rule above (`ifEmpty`). Pinned by
    // WorldResolverSpec "manifest overlay replaces wholesale (instance wins)".
    private fun mergeManifest(
        type: Map<String, PropertyValue>,
        instance: Map<String, PropertyValue>,
    ): Map<String, PropertyValue> = instance.ifEmpty { type }

    private fun parseRef(dotted: String): QualifiedName {
        // Dotted extends ref (qname-shaped). Best-effort: last segment = name, the
        // segment before it = world namespace, remainder = package. Falls back to a
        // schemaCode=WORLD qname; unresolved refs simply won't be in the lookup maps.
        val parts = dotted.split('.')
        return when {
            parts.size >= 3 ->
                QualifiedName(
                    SchemaCode.WORLD,
                    parts[parts.size - 2],
                    parts.last(),
                    parts.dropLast(2).joinToString("."),
                )
            parts.size == 2 -> QualifiedName(SchemaCode.WORLD, parts[0], parts[1], "")
            else -> QualifiedName(SchemaCode.WORLD, "", dotted, "")
        }
    }
}
