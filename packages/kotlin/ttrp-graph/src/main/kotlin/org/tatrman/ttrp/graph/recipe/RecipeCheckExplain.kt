// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.graph.recipe

/**
 * The checker section of `ttrp explain` for a recipe (AU-core-P1 · S1.1 T7, S1.2 T6): the mode verdict and the
 * programs that run the recipe, each context's island kind and class (for a set-level one, the op that lost the key
 * and where), and the routes — `turn-fed` marked. Printed under the skeleton ([org.tatrman.ttrp.recipe.RecipeExplain]).
 */
object RecipeCheckExplain {
    fun render(model: RecipeModel): String =
        buildString {
            val programs = model.programs.joinToString(", ").ifEmpty { "none imported" }
            appendLine("checker · mode ${model.mode.spelling} · programs $programs")
            val width = model.contexts.maxOfOrNull { it.name.length } ?: 0
            val kindWidth = model.contexts.maxOfOrNull { it.kind.length } ?: 0
            for (c in model.contexts) {
                val lost =
                    c.lostKeyAt?.let { what ->
                        val at = c.lostKeyLocation?.let { " (${it.line}:${it.column})" } ?: ""
                        " — $what$at"
                    } ?: ""
                val window = c.window?.let { " · window by ${it.by.joinToString(", ") { b -> b.text }}" } ?: ""
                val name = c.name.padEnd(width)
                val kind = c.kind.padEnd(kindWidth)
                appendLine("  context $name  $kind  ${c.contextClass.spelling}$window$lost")
            }
            if (model.routes.isNotEmpty()) {
                val routes =
                    model.routes.joinToString(", ") { r ->
                        r.name + (if (r.isElse) " (else)" else "") + (if (r.turnFed) " [turn-fed]" else "")
                    }
                appendLine("  routes${model.switchMode?.let { " ($it)" } ?: ""}: $routes")
            }
        }.trimEnd()
}
