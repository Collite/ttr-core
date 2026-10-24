// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.doc

import org.tatrman.ttr.metadata.model.AutomationAction
import org.tatrman.ttr.metadata.model.AutomationObject
import org.tatrman.ttr.metadata.model.Entity
import org.tatrman.ttr.metadata.model.ModelObject
import org.tatrman.ttrp.bundle.RecipeManifest

/** Labels are presentation only: identifiers and approval fingerprints remain in the canonical language. */
data class ReadmeLabels(
    val labels: Map<String, Map<String, String>> = emptyMap(),
    val dryRuns: Map<String, Map<String, String>> = emptyMap(),
) {
    companion object {
        fun from(inScope: Collection<ModelObject>): ReadmeLabels {
            val objects = inScope.sortedBy { it.qname.dotted() }
            val labels = linkedMapOf<String, Map<String, String>>()
            val dryRuns = linkedMapOf<String, Map<String, String>>()
            objects.forEach { obj ->
                val label =
                    when (obj) {
                        is AutomationObject -> obj.label
                        is Entity -> obj.displayLabel
                        else -> null
                    }
                label?.takeUnless { it.isEmpty }?.let {
                    labels[obj.qname.dotted()] = it.byLanguage
                    labels.putIfAbsent(obj.qname.name, it.byLanguage)
                }
                if (obj is AutomationAction) dryRuns[obj.qname.name] = obj.dryRun.byLanguage
            }
            return ReadmeLabels(labels, dryRuns)
        }
    }
}

object ReadmeRenderer {
    fun render(
        recipe: RecipeManifest,
        labels: ReadmeLabels = ReadmeLabels(),
        title: String? = null,
        lang: String = "en",
    ): String {
        require(lang in setOf("en", "cs")) { "--lang must be en or cs" }

        fun translated(
            en: String,
            cs: String,
        ) = if (lang == "cs") cs else en

        fun label(name: String): String? =
            (labels.labels[name] ?: labels.labels[name.substringAfterLast('.')])?.let {
                it[lang]
                    ?: it["en"]
            }

        fun named(name: String): String = label(name)?.let { "${escape(it)} (`$name`)" } ?: "`$name`"
        return buildString {
            append("# ").append(escape(title ?: label(recipe.name) ?: recipe.name)).append("\n\n")
            append("## ").append(translated("What this recipe decides", "O čem recept rozhoduje")).append("\n\n")
            recipe.roles.routes.forEach { route ->
                append("- `").append(route.name).append("`")
                route.because?.let { append(": ").append(escape(it)) }
                if (route.detail.isNotEmpty()) {
                    append(
                        " (",
                    ).append(translated("details", "podrobnosti"))
                        .append(": ")
                        .append(
                            route.detail.joinToString(", ") {
                                "`$it`"
                            },
                        ).append(')')
                }
                append('\n')
            }
            append("\n## ").append(translated("What it reads", "Co čte")).append("\n\n")
            recipe.objectsRead.sorted().forEach { append("- ").append(named(it)).append('\n') }
            append("\n## ").append(translated("What it does", "Co dělá")).append("\n\n")
            recipe.wiring.forEach { w ->
                append("- ").append(w.routes.joinToString(", ") { "`$it`" }).append(" → ").append(named(w.action))
                append(
                    ": ",
                ).append(
                    w.columns.entries
                        .sortedBy { it.key }
                        .joinToString(", ") { (n, v) -> "`$n` = `$v`" },
                )
                w.whenCondition?.let {
                    append(
                        "; ",
                    ).append(translated("when", "když")).append(" `").append(it).append('`')
                }
                val dry = labels.dryRuns[w.action]?.let { it[lang] ?: it["en"] }
                dry?.let { append(". ").append(escape(it)) }
                append('\n')
            }
            append("\n## ").append(translated("Parameters", "Parametry")).append("\n\n")
            recipe.parameters.forEach { p ->
                append(
                    "- ",
                ).append(named(p.name)).append(" = `").append(p.value ?: "?").append("` (").append(p.type).append(")\n")
            }
            append("\n## ")
                .append(translated("Mode", "Režim"))
                .append("\n\n`")
                .append(recipe.mode)
                .append("`\n")
        }
    }

    private fun escape(text: String) =
        text
            .replace("\\", "\\\\")
            .replace("\n", " ")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
}
