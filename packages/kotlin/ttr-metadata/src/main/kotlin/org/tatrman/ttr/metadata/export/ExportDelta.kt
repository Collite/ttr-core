// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.metadata.export

import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor

data class Rename(
    val from: String,
    val to: String,
    val since: String,
) {
    companion object {
        /** Parse the authored sidecar as data only; no YAML object construction. */
        fun read(yaml: String): List<Rename> {
            val value = Yaml(SafeConstructor(LoaderOptions())).load<Any?>(yaml) ?: return emptyList()
            require(value is List<*>) { "renames.yaml must be a list" }
            return value.map { item ->
                require(
                    item is Map<*, *> && item.keys == setOf("from", "to", "since"),
                ) { "rename requires from, to, since" }

                fun text(key: String): String =
                    (item[key] as? String)?.takeIf { it.isNotBlank() }
                        ?: throw IllegalArgumentException("rename $key must be a nonempty string")
                Rename(text("from"), text("to"), text("since"))
            }
        }
    }
}

data class ExportDelta(
    val since: String,
    val renamed: List<Rename>,
    val removed: List<String>,
) {
    companion object {
        /** Snapshot ids are ordered version strings, compared lexically (C-8); callers supply the known prior id set. */
        fun compute(
            previousIds: Set<String>,
            current: DefinitionGraph,
            renames: List<Rename>,
            since: String,
        ): ExportDelta {
            val renamed =
                renames
                    .filter {
                        it.since >= since
                    }.distinct()
                    .sortedWith(
                        compareBy<Rename, String>(bytewise) {
                            it.from
                        }.thenBy(bytewise) { it.to }.thenBy(bytewise) { it.since },
                    )
            val currentIds = current.nodes.map { it.id }.toSet() + current.edges.mapNotNull { it.id }
            return ExportDelta(
                since,
                renamed,
                (previousIds - currentIds - renamed.map { it.from }.toSet()).sortedWith(bytewise),
            )
        }
    }
}
