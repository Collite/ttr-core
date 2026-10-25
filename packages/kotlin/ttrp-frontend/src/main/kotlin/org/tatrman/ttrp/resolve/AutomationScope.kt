// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.resolve

import org.tatrman.ttr.metadata.model.Attribute
import org.tatrman.ttr.metadata.model.AutomationAction
import org.tatrman.ttr.metadata.model.Entity

sealed interface ActionResolution {
    data class Resolved(
        val action: AutomationAction,
    ) : ActionResolution

    data class Unresolved(
        val name: String,
    ) : ActionResolution

    data class Ambiguous(
        val name: String,
    ) : ActionResolution
}

sealed interface TargetResolution {
    data class Resolved(
        val entity: Entity,
        val attribute: Attribute,
    ) : TargetResolution

    data class Unresolved(
        val ref: String,
    ) : TargetResolution

    data class Ambiguous(
        val ref: String,
    ) : TargetResolution
}

/** One immutable, indexed recipe scope shared by checking, approval production and export verification. */
class AutomationScope internal constructor(
    val imports: List<ImportScope>,
    actions: List<AutomationAction>,
    entities: List<Entity>,
    stdlib: List<Entity>,
    val missingImports: List<String> = emptyList(),
    private val inPackage: (Entity, String) -> Boolean,
) {
    private val actionsByName = actions.groupBy { it.qname.name }
    private val entitiesByName = entities.groupBy { it.qname.name }
    private val stdlibByName = stdlib.groupBy { it.qname.name }

    fun action(name: String): ActionResolution = resolveAction(name, actionsByName[name].orEmpty())

    /** Entity/attribute are the last two segments; a qualifier must name that same in-scope entity. */
    fun target(ref: String): TargetResolution {
        val parts = ref.split('.')
        if (parts.size < 2 || parts.any { it.isBlank() }) return TargetResolution.Unresolved(ref)
        val name = parts[parts.lastIndex - 1]
        val prefix = parts.dropLast(2).joinToString(".")
        val qualified: (Entity) -> Boolean = { prefix.isEmpty() || prefix == "er.entity" || inPackage(it, prefix) }
        val candidates =
            entitiesByName[name]
                .orEmpty()
                .filter(qualified)
                .ifEmpty { stdlibByName[name].orEmpty().filter(qualified) }
        if (candidates.size > 1) return TargetResolution.Ambiguous(ref)
        val entity = candidates.singleOrNull() ?: return TargetResolution.Unresolved(ref)
        val attributes = entity.attributes.filter { it.qname.name.substringAfterLast('.') == parts.last() }
        return when (attributes.size) {
            0 -> TargetResolution.Unresolved(ref)
            1 -> TargetResolution.Resolved(entity, attributes.single())
            else -> TargetResolution.Ambiguous(ref)
        }
    }

    companion object {
        fun resolveAction(
            name: String,
            candidates: List<AutomationAction>,
        ): ActionResolution =
            when (candidates.size) {
                0 -> ActionResolution.Unresolved(name)
                1 -> ActionResolution.Resolved(candidates.single())
                else -> ActionResolution.Ambiguous(name)
            }

        /** Shared target resolution and conflict rule for checking and fingerprinting. */
        fun targetsOf(
            action: AutomationAction,
            target: (String) -> TargetResolution,
        ): Map<String, TargetResolution> {
            val entities = mutableMapOf<String, org.tatrman.ttr.metadata.model.QualifiedName>()
            return targetRefs(action).associateWith { ref ->
                val result = target(ref)
                if (result is TargetResolution.Resolved) {
                    val previous = entities.put(result.entity.qname.name, result.entity.qname)
                    if (previous != null && previous != result.entity.qname) TargetResolution.Ambiguous(ref) else result
                } else {
                    result
                }
            }
        }

        fun targetRefs(action: AutomationAction): List<String> =
            (
                action.columns.map { it.type }.filter { '.' in it } +
                    listOfNotNull(action.set?.attribute, action.transition?.attribute)
            ).distinct().sorted()

        fun hasDefaults(action: AutomationAction): Boolean = action.properties.any { it.first == "writer_defaults" }
    }
}
