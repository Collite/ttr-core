// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.cli

import org.tatrman.ttr.metadata.model.AutomationAction
import org.tatrman.ttr.metadata.model.AutomationRole
import org.tatrman.ttr.metadata.model.RoleMembership
import org.tatrman.ttr.metadata.model.WorldDeployment
import org.tatrman.ttrp.resolve.ModelIndex

/** Match the automation validator: role.<name> names a role within the project. Refuse ambiguity. */
internal object ApprovalGroupsProjector {
    fun group(
        action: AutomationAction,
        index: ModelIndex,
    ): String? {
        val name = action.byRole ?: return null
        val group = keycloakGroup(name, index)
        require(!group.isNullOrBlank()) {
            "TTRP-EXPORT-ROLE: action ${action.qname.name}: role.$name has no unique Keycloak group"
        }
        return group
    }

    /**
     * ⚑R226-A: the Keycloak groups of a deployment's `startable_by` roles, in declaration order, by the same rule as
     * [group]. Null when no `startable_by` is declared; `[]` stays `[]`.
     */
    fun startGroups(
        deployment: WorldDeployment,
        index: ModelIndex,
    ): List<String>? =
        deployment.startableBy?.map { name ->
            val group = keycloakGroup(name, index)
            require(!group.isNullOrBlank()) {
                "TTRP-EXPORT-ROLE: deployment ${deployment.qname.name}: startable_by role.$name has no unique Keycloak group"
            }
            group
        }

    private fun keycloakGroup(
        name: String,
        index: ModelIndex,
    ): String? {
        val roles = index.automationAll().filterIsInstance<AutomationRole>().filter { it.qname.name == name }
        return (roles.singleOrNull()?.membership as? RoleMembership.KeycloakGroup)?.group
    }
}
