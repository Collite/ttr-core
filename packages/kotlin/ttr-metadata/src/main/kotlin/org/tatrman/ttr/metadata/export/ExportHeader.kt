// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.metadata.export

/** Callers read connections.toml and supply only public locator data, never credentials (C-22). */
data class ConnectionDescriptor(
    val id: String,
    val database: String?,
    val schema: String?,
    val locators: Map<String, Any> = emptyMap(),
)

data class ExportHeader(
    val grammar: String,
    val snapshot: String,
    val world: String,
    val scope: ExportScope = ExportScope(),
    val connections: List<ConnectionDescriptor> = emptyList(),
) {
    companion object {
        fun from(
            set: DefinitionSet,
            scope: ExportScope = ExportScope(),
            connections: List<ConnectionDescriptor> = emptyList(),
        ): ExportHeader = ExportHeader(set.grammar, set.snapshot, set.world, scope, connections)
    }
}
