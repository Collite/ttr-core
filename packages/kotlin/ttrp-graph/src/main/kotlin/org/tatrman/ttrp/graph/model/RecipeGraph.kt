// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.graph.model

import org.tatrman.ttrp.ast.RecipeDecl

/**
 * Where a recipe's members landed in the built [TtrpGraph] (AU-core-P1): the recipe checker walks the graph
 * through this index. Every role container is a [Container] — a `target` form with its members, any other island
 * form opaque (no members, its declared ports) — and the decision is a container whose last member is the
 * [Switch]; its route names are OUT ports of the decision container. The implicit role wiring (`in orders` ←
 * the candidates' `out orders`, `in shortfall` ← the context `shortfall`) is ordinary DATA edges.
 */
data class RecipeGraph(
    val recipe: RecipeDecl,
    /** Role container name → container node id. */
    val roles: Map<String, String>,
    /** The decision container's node id, or null for a decision-less recipe. */
    val decision: String?,
    /** The decision's switch node id. */
    val switch: String?,
    /** Join node id → the relation named by its `on: relation <r>`. */
    val relationJoins: Map<String, String>,
)
