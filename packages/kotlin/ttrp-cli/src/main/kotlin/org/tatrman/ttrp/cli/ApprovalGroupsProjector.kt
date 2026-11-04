// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.cli

import org.tatrman.ttr.metadata.model.AutomationAction
import org.tatrman.ttr.metadata.model.AutomationRole
import org.tatrman.ttr.metadata.model.RoleMembership
import org.tatrman.ttrp.resolve.ModelIndex

/** Match the automation validator: role.<name> names a role within the project. Refuse ambiguity. */
internal object ApprovalGroupsProjector {
    fun group(
        action: AutomationAction,
        index: ModelIndex,
    ): String? {
        val name = action.byRole ?: return null
        val roles =
            index.automationAll().filterIsInstance<AutomationRole>().filter {
                it.qname.name == name
            }
        val group = (roles.singleOrNull()?.membership as? RoleMembership.KeycloakGroup)?.group
        require(!group.isNullOrBlank()) {
            "TTRP-EXPORT-ROLE: action ${action.qname.name}: role.$name has no unique Keycloak group"
        }
        return group
    }
}
