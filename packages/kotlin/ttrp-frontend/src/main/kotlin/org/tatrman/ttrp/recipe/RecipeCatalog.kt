// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.recipe

import org.tatrman.ttrp.ast.RecipeDecl
import org.tatrman.ttrp.ast.TtrpDocument
import org.tatrman.ttrp.expr.Column
import org.tatrman.ttrp.expr.TtrpType
import org.tatrman.ttrp.resolve.ModelIndex

/**
 * The entities a recipe generates (AU contracts §5.3), as row schemas the resolver can load before the P2
 * compile writes them: `<recipe>_verdict` (and `_verdict_current`, the same columns) and `_verdict_detail`. A
 * recipe reads its own past verdicts — `monitor_inbound`'s `reminded` context counts the `remind` verdicts of
 * earlier runs (AU 17 §4.2). Another recipe's verdicts come from its bundle's catalog, served by Veles.
 */
object RecipeCatalog {
    /** The explicit `recipe:` name, else a stable identifier from the filename without language suffixes. */
    fun recipeName(
        recipe: RecipeDecl,
        fileName: String,
    ): String {
        recipe.name?.let { return it }
        val stem =
            fileName
                .substringAfterLast('/')
                .substringAfterLast('\\')
                .removeSuffix(".ttrp")
                .removeSuffix(".md")
                .removeSuffix(".cs")
                .removeSuffix(".en")
        val name = stem.replace(Regex("[^a-zA-Z0-9_\u00C0-\u024F]"), "_").ifEmpty { "recipe" }
        return if (name.first().isDigit()) "_$name" else name
    }

    fun ownEntities(
        doc: TtrpDocument,
        fileName: String,
        model: ModelIndex?,
    ): Map<String, List<Column>> {
        val recipe = doc.statements.filterIsInstance<RecipeDecl>().singleOrNull() ?: return emptyMap()
        val name = recipeName(recipe, fileName)
        val keys = recipe.key.map { q -> Column(q.parts.last(), keyType(q.parts, model)) }
        val text = TtrpType.Str
        val verdict =
            listOf(Column("run", text), Column("as_of", TtrpType.Timestamp)) + keys +
                listOf(
                    Column("route", text),
                    Column("verdict_reason", text),
                    Column("parameters_fp", text),
                    Column("run_as", text),
                    Column("written_at", TtrpType.Timestamp),
                )
        val detail =
            listOf(Column("run", text)) + keys +
                listOf(Column("route", text), Column("context", text), Column("row_no", TtrpType.Integer))
        return mapOf(
            "${name}_verdict" to verdict,
            "${name}_verdict_current" to verdict,
            "${name}_verdict_detail" to detail,
        )
    }

    /** The key attribute's type (`order.order_number` → the entity's attribute type), else text. */
    private fun keyType(
        parts: List<String>,
        model: ModelIndex?,
    ): TtrpType {
        if (parts.size < 2 || model == null) return TtrpType.Str
        val attr =
            model
                .entitiesNamed(parts[parts.size - 2])
                .firstOrNull()
                ?.let { e -> model.entityAttributes(e).firstOrNull { it.first == parts.last() } }
        return attr?.let { TtrpType.parse(it.second) } ?: TtrpType.Str
    }
}
