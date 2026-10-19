// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.metadata.model

import org.tatrman.ttr.parser.model.PropertyValue
import org.tatrman.ttr.parser.model.SourceLocation

/**
 * v4.1 world model tier (M2 — the half the kantheon metadata service never had). Typed, reconciled
 * counterparts of the parser world AST. Manifest entries are transported opaque
 * (T6 β data — MD5, never interpreted here). `sourceLocation` carries the def
 * span so `WorldResolver` failures can point the consumer at the instance def
 * (E-d-adjacent rendering).
 */
data class WorldSchema(
    val worlds: Map<QualifiedName, World> = emptyMap(),
) : SchemaContents {
    override val schemaCode: String = "world"

    override fun objects(): Sequence<ModelObject> =
        sequence {
            for (w in worlds.values) {
                yield(w)
                yieldAll(w.engines)
                yieldAll(w.executors)
                for (s in w.storages) {
                    yield(s)
                    yieldAll(s.schemas)
                }
                // Grammar 0.16 — the automation members.
                yieldAll(w.identities)
                yieldAll(w.grants)
                yieldAll(w.deployments)
                yieldAll(w.processes)
            }
        }
}

data class World(
    override val internalId: String,
    override val qname: QualifiedName,
    override val description: String = "",
    override val descriptionLocalized: LocalizedText = LocalizedText.EMPTY,
    override val tags: List<String> = emptyList(),
    override val sourceFile: String = "",
    override val binding: Binding = Binding.BoundReal,
    val extendsRef: String? = null,
    val engines: List<WorldEngine> = emptyList(),
    val executors: List<WorldExecutor> = emptyList(),
    val storages: List<WorldStorage> = emptyList(),
    val sourceLocation: SourceLocation? = null,
    /** Grammar 0.16 — `def identity` service principals (D-90). */
    val identities: List<WorldIdentity> = emptyList(),
    /** Grammar 0.16 — `def grant` rows (D-91), in authored order. */
    val grants: List<WorldGrant> = emptyList(),
    /** Grammar 0.16 — `def deployment` objects (D-107). */
    val deployments: List<WorldDeployment> = emptyList(),
    /** Grammar 0.16 — `def process` instances (D-39, D-86). */
    val processes: List<WorldProcess> = emptyList(),
) : ModelObject {
    override val kind: String = "world"

    fun identity(name: String): WorldIdentity? = identities.firstOrNull { it.qname.name == name }

    fun deployment(name: String): WorldDeployment? = deployments.firstOrNull { it.qname.name == name }

    fun process(name: String): WorldProcess? = processes.firstOrNull { it.qname.name == name }
}

data class WorldEngine(
    override val internalId: String,
    override val qname: QualifiedName,
    override val description: String = "",
    override val descriptionLocalized: LocalizedText = LocalizedText.EMPTY,
    override val tags: List<String> = emptyList(),
    override val sourceFile: String = "",
    override val binding: Binding = Binding.BoundReal,
    val type: String? = null,
    val version: String? = null,
    val extendsRef: String? = null,
    val manifest: Map<String, PropertyValue> = emptyMap(),
    val sourceLocation: SourceLocation? = null,
) : ModelObject {
    override val kind: String = "engine"
}

data class WorldExecutor(
    override val internalId: String,
    override val qname: QualifiedName,
    override val description: String = "",
    override val descriptionLocalized: LocalizedText = LocalizedText.EMPTY,
    override val tags: List<String> = emptyList(),
    override val sourceFile: String = "",
    override val binding: Binding = Binding.BoundReal,
    val type: String? = null,
    val version: String? = null,
    val extendsRef: String? = null,
    val manifest: Map<String, PropertyValue> = emptyMap(),
    val sourceLocation: SourceLocation? = null,
) : ModelObject {
    override val kind: String = "executor"
}

data class WorldStorage(
    override val internalId: String,
    override val qname: QualifiedName,
    override val description: String = "",
    override val descriptionLocalized: LocalizedText = LocalizedText.EMPTY,
    override val tags: List<String> = emptyList(),
    override val sourceFile: String = "",
    override val binding: Binding = Binding.BoundReal,
    val type: String? = null,
    val extendsRef: String? = null,
    val via: String? = null,
    val hosts: List<String> = emptyList(),
    val staging: Boolean = false,
    val schemas: List<WorldSchemaObject> = emptyList(),
    val manifest: Map<String, PropertyValue> = emptyMap(),
    val sourceLocation: SourceLocation? = null,
    /**
     * Grammar 0.16 — `writable: [ <entity> | <entity>.<attribute> | "<glob>" … ]` (D-65, D-76, D-79), read from
     * the manifest entry (still present there): each item as written, a glob without its quotes.
     */
    val writable: List<String> = emptyList(),
) : ModelObject {
    override val kind: String = "storage"
}

/** A named `def schema` declared on a world storage (D-c world home). */
data class WorldSchemaObject(
    override val internalId: String,
    override val qname: QualifiedName,
    override val description: String = "",
    override val descriptionLocalized: LocalizedText = LocalizedText.EMPTY,
    override val tags: List<String> = emptyList(),
    override val sourceFile: String = "",
    override val binding: Binding = Binding.BoundReal,
    val fields: Map<String, String> = emptyMap(),
    val sourceLocation: SourceLocation? = null,
) : ModelObject {
    override val kind: String = "worldSchema"
}
