// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.metadata.world

import org.tatrman.ttr.metadata.model.QualifiedName
import org.tatrman.ttr.parser.model.PropertyValue
import org.tatrman.ttr.parser.model.SourceLocation

/**
 * Post-overlay resolved world (contracts §3). Engines/executors/storages have had
 * their instance-⊕-extends-type overlay applied; `manifest` is transported opaque
 * (T6/MD5 — format owned by TTR-P Stage 2.2). `fingerprint` is `sha256:<hex>`
 * (placeholder in M2.1; normative canonicalization in M2.2).
 */
data class ResolvedWorld(
    val qname: QualifiedName,
    val engines: List<ResolvedEngine>,
    val executors: List<ResolvedExecutor>,
    val storages: List<ResolvedStorage>,
    val staging: ResolvedStorage?,
    val fingerprint: String,
) {
    fun hostsByPackage(): Map<String, ResolvedStorage> =
        buildMap {
            for (s in storages) for (pkg in s.hosts) put(pkg, s)
        }
}

data class ResolvedEngine(
    val qname: QualifiedName,
    val type: String?,
    val version: String?,
    /** Raw extends spelling — bare ids pass through for the compiler's ManifestSource join. */
    val extendsRef: String?,
    val manifest: Map<String, PropertyValue>,
    val sourceLocation: SourceLocation? = null,
)

data class ResolvedExecutor(
    val qname: QualifiedName,
    val type: String?,
    val version: String?,
    val extendsRef: String?,
    val manifest: Map<String, PropertyValue>,
    val sourceLocation: SourceLocation? = null,
)

data class ResolvedStorage(
    val qname: QualifiedName,
    val type: String?,
    val via: String?,
    val hosts: List<String>,
    val staging: Boolean,
    val schemas: List<ResolvedStorageSchema>,
    val extendsRef: String?,
    val manifest: Map<String, PropertyValue>,
    val sourceLocation: SourceLocation? = null,
)

/**
 * Grammar 0.16 — the storage's `writable: [ <entity> | <entity>.<attribute> | "<glob>" … ]` (D-65, D-76, D-79), read
 * from the resolved (overlay-merged) manifest: each item as written, a glob without its quotes.
 */
val ResolvedStorage.writable: List<String>
    get() =
        org.tatrman.ttr.metadata.source.AutomationBuilder
            .writable(manifest)

/** A world-declared named schema on a storage (D-c world tier). */
data class ResolvedStorageSchema(
    val qname: QualifiedName,
    val fields: Map<String, String>,
    val sourceLocation: SourceLocation? = null,
)

/**
 * Structured, id-free resolution outcome (MD5 — no diagnostic ids, no policy
 * message strings; fields only, consumers render).
 */
sealed interface WorldResolution {
    data class Ok(
        val world: ResolvedWorld,
    ) : WorldResolution

    sealed interface Failure : WorldResolution

    data class WorldNotFound(
        val worldQname: QualifiedName,
        val knownWorlds: List<QualifiedName>,
    ) : Failure

    data class NotAWorld(
        val worldQname: QualifiedName,
        val foundKind: String,
        val definitionLocation: SourceLocation?,
    ) : Failure

    data class StagingConflict(
        val storages: List<QualifiedName>,
        val locations: List<SourceLocation>,
    ) : Failure

    data class HostsUnknownPackage(
        val pkg: String,
        val storageQname: QualifiedName,
        val definitionLocation: SourceLocation?,
    ) : Failure

    data class ExtendsUnresolved(
        val typeRef: String,
        val onDef: QualifiedName,
        val definitionLocation: SourceLocation?,
    ) : Failure
}
