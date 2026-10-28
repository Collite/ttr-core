// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.resolve

import org.tatrman.ttr.metadata.model.PackageNames
import org.tatrman.ttr.metadata.world.ResolvedWorld
import org.tatrman.ttr.metadata.world.writable

/** A recipe load has one storage, even when two storages use the same engine. */
object RecipePlacement {
    fun writable(
        qname: String,
        world: ResolvedWorld,
    ): Boolean = world.storages.any { s -> s.writable.any { matches(it, qname) } }

    fun storages(
        source: LoadSource,
        world: ResolvedWorld,
        index: ModelIndex?,
    ): List<String> {
        val obj = index?.objectNamed(source.objectQname)
        if (source.unbound) {
            // A dangling/unsupported binding is never redirected into a writable storage.
            if (obj != null && index.erToDb(obj.qname).dbQname != null) return emptyList()
            return world.storages
                .filter { s -> s.writable.any { matches(it, source.objectQname) } }
                .map { it.qname.name }
        }
        val physical = obj?.let { index.bindingTarget(it) ?: it } ?: return emptyList()
        val pkg = index.packageOf(physical)
        // Prefixes are package segments, and the most specific host wins. Equal specificity is ambiguous.
        val hosts =
            world.storages.flatMap { s ->
                s.hosts.filter { PackageNames.covers(it, pkg) }.map { it to s.qname.name }
            }
        val longest = hosts.maxOfOrNull { it.first.split('.').size } ?: return emptyList()
        return hosts.filter { it.first.split('.').size == longest }.map { it.second }.distinct()
    }

    /** Shared diagnostic for authored and synthesized loads, reported at the load/sugar location. */
    fun failure(
        source: LoadSource,
        world: ResolvedWorld,
        placements: List<String>,
    ): String? {
        if (placements.size != 1) {
            return "entity `${source.objectQname}` has ${placements.size} storage placements: " +
                placements.joinToString()
        }
        val storage = world.storages.single { it.qname.name == placements.single() }
        if (storage.via == null || world.engines.none { it.qname.name == storage.via }) {
            return "entity `${source.objectQname}` storage `${storage.qname.name}` " +
                "needs a via engine bound in the world"
        }
        return null
    }

    private fun matches(
        pattern: String,
        qname: String,
    ): Boolean {
        val regex = pattern.split('*').joinToString(".*") { Regex.escape(it) }.toRegex()
        return regex.matches(qname) || regex.matches(qname.substringAfterLast('.'))
    }
}
