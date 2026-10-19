// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.recipe

import org.tatrman.ttrp.ast.ContainerDecl
import org.tatrman.ttrp.ast.DecisionDecl
import org.tatrman.ttrp.ast.FlowBody
import org.tatrman.ttrp.ast.IslandForm
import org.tatrman.ttrp.ast.Qname
import org.tatrman.ttrp.ast.RecipeDecl

/**
 * The plain containers a recipe's members are, for the paths that already know containers (resolution, the
 * graph): every `target` role container with a body ([org.tatrman.ttrp.ast.RoleContainer.asContainer]) and
 * the decision — a canonical body whose ports are its `in` ports. The decision has no engine of its own in the
 * source; it is placed on the candidates' engine here (the P2 compile decides where it runs). Its closing
 * `switch` is not a statement of the body: the graph builder adds it from [DecisionDecl.switch].
 */
object RecipeContainers {
    fun of(recipe: RecipeDecl): List<ContainerDecl> {
        val roles = recipe.roleContainers.mapNotNull { it.asContainer() }
        val engine = decisionEngine(recipe) ?: return roles
        return roles + recipe.decisions.map { decision(it, engine) }
    }

    /** The engine the decision is placed on: the candidates' `target`, else the first `target` role's. */
    fun decisionEngine(recipe: RecipeDecl): Qname? =
        (recipe.candidates + recipe.contexts)
            .firstNotNullOfOrNull { (it.form as? IslandForm.Target)?.target }

    fun decision(
        d: DecisionDecl,
        engine: Qname,
    ): ContainerDecl =
        ContainerDecl(
            name = d.name,
            ports = d.ports,
            target = engine.copy(location = d.location),
            body = FlowBody(d.statements, d.location),
            location = d.location,
            nameLocation = d.nameLocation,
        )
}
