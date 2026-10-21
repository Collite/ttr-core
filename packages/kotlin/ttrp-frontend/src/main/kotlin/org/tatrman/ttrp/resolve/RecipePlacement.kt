// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.resolve

import org.tatrman.ttr.metadata.world.ResolvedWorld
import org.tatrman.ttr.metadata.world.writable

/** A recipe load has one storage, even when two storages use the same engine. */
object RecipePlacement {
    fun storages(
        source: LoadSource,
        world: ResolvedWorld,
        index: ModelIndex?,
    ): List<String> {
        val objects =
            index
                ?.snapshot
                ?.model
                ?.objectByQname()
                ?.values
                .orEmpty()
        val obj = objects.firstOrNull { it.qname.dotted() == source.objectQname }
        // The er2db target determines physical placement, rather than the logical entity's package.
        val target = obj?.let { index?.erToDb(it.qname)?.dbQname }
        val physical = target?.let { t -> objects.firstOrNull { it.qname == t } } ?: obj
        return world.storages
            .filter { storage ->
                if (source.unbound) {
                    storage.writable.any { matches(it, source.objectQname) }
                } else {
                    storage.hosts.any { pkg ->
                        physical?.sourceFile?.replace('\\', '/')?.contains("/" + pkg.replace('.', '/') + "/") == true ||
                            physical?.qname?.`package`?.let { it == pkg || it.startsWith("$pkg.") } == true
                    }
                }
            }.map { it.qname.name }
    }

    private fun matches(
        pattern: String,
        qname: String,
    ): Boolean {
        val regex = pattern.split('*').joinToString(".*") { Regex.escape(it) }.toRegex()
        return regex.matches(qname) || regex.matches(qname.substringAfterLast('.'))
    }
}
