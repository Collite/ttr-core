// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.imports

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.tatrman.ttr.profile.Bytewise
import org.tatrman.ttr.profile.ForeignNode
import org.tatrman.ttr.profile.ForeignTree
import org.tatrman.ttr.profile.JsonPointer
import org.tatrman.ttr.profile.ProfileException
import org.tatrman.ttr.profile.SourceEntry
import org.tatrman.ttr.yaml.CanonicalDocument
import org.tatrman.ttr.yaml.CanonicalYaml
import java.util.TreeMap

/**
 * One entry of a profile's reader-level `lexicon` key (MW-P5 S5.2 T4; ⚑MW-P5-S52-1 (d) → ✅D-MW-81): the records at [at]
 * (a mapping or a list in each input tree), each named by its string at [id]; the run's records of canonical kind [target]
 * with that id get one `def term` per locale, whose forms are the elements at [terms] — a list, or a string cut at [split]
 * as `values.split` cuts. An element's form is its string at [form] (or the element itself), its locale the string at
 * [language] within it (lower-cased) or the constant [locale].
 */
data class LexiconRule(
    val target: String,
    val at: String,
    val id: String,
    val terms: String,
    val form: String? = null,
    val language: String? = null,
    val locale: String? = null,
    val split: String? = null,
)

/**
 * The reader's lexicon emission (contracts §1, XM 06 §3 "synonyms → lexicon entries"): synonyms become `lexicon` documents,
 * one per package and locale (`model lexicon locale <id>`, a unit-level header the engine does not group by), beside the
 * engine's documents. Each concept gets one `def term <id>_<locale> { for: <model>.<id>, forms: […] }`, terms in bytewise
 * name order, forms in bytewise order with repeats dropped (a graph's set and a CSV cell's list agree). A record the run did not emit as `target` (a skipped draft,
 * say) gets no term. An element without a language is skipped with a warning; a language tag that is not a TTR locale
 * (`[a-z][a-z0-9_]*` once lower-cased) is `ys/profile-invalid` at the element.
 */
internal object Lexicon {
    /** One generated term: the concept's package, its reference (`cn.Customer`), its id, locale and forms. */
    class Term(
        val pkg: String,
        val reference: String,
        val id: String,
        val locale: String,
        val forms: List<String>,
    )

    private val LOCALE = Regex("[a-z][a-z0-9_]*")

    fun terms(
        trees: List<ForeignTree>,
        sources: List<SourceEntry>,
        rules: List<LexiconRule>,
        warnings: MutableList<String>,
    ): List<Term> {
        val terms = mutableListOf<Term>()
        for (rule in rules) {
            // The run's records of the target kind, by id (the sidecar's locator of a top-level record is its id).
            val emitted = sources.filter { it.kind == rule.target && it.locator != null }.groupBy { it.locator!! }
            for (tree in trees) {
                for (record in records(tree, rule.at)) {
                    val id = string(JsonPointer(rule.id).resolve(record)) ?: continue
                    for (entry in emitted[id].orEmpty()) {
                        val model = entry.qname.removePrefix("${entry.pkg}.").substringBefore('.')
                        elements(record, rule)
                            .mapNotNull { element -> located(element, rule, id, warnings) }
                            .groupBy({ it.first }, { it.second })
                            .forEach { (locale, forms) ->
                                terms += Term(entry.pkg, "$model.$id", id, locale, forms.distinct())
                            }
                    }
                }
            }
        }
        return terms
    }

    /** Canonical `lexicon` documents of [terms], keyed by their path under `--out`. */
    fun documents(
        terms: List<Term>,
        grammar: String,
    ): Map<String, CanonicalDocument> {
        val documents = TreeMap<String, CanonicalDocument>(Bytewise)
        terms.groupBy { it.pkg to it.locale }.forEach { (key, group) ->
            val (pkg, locale) = key
            val defs =
                group
                    .groupBy { "${it.id}_$locale" }
                    .toSortedMap(Bytewise)
                    .map { (name, same) ->
                        JsonObject(
                            mapOf(
                                "kind" to JsonPrimitive("term"),
                                "id" to JsonPrimitive(name),
                                "properties" to
                                    JsonObject(
                                        mapOf(
                                            "for" to JsonPrimitive(same.first().reference),
                                            "forms" to
                                                JsonArray(
                                                    same
                                                        .flatMap { it.forms }
                                                        .distinct()
                                                        .sortedWith(
                                                            Bytewise,
                                                        ).map(::JsonPrimitive),
                                                ),
                                        ),
                                    ),
                            ),
                        )
                    }
            val value =
                JsonObject(
                    mapOf(
                        "ttr" to JsonPrimitive(grammar),
                        "package" to JsonPrimitive(pkg),
                        "model" to JsonPrimitive("lexicon"),
                        "locale" to JsonPrimitive(locale),
                        "defs" to JsonArray(defs),
                    ),
                )
            val path =
                listOf(
                    pkg.replace('.', '/'),
                    "lexicon.$locale.ttrm",
                ).filter { it.isNotEmpty() }.joinToString("/")
            documents[path] = CanonicalYaml.read(Json.encodeToString(JsonObject.serializer(), value), path)
        }
        return documents
    }

    /** The records under [at]: a mapping's values or a list's elements, in the tree's order. */
    private fun records(
        tree: ForeignTree,
        at: String,
    ): List<ForeignNode> {
        val node = JsonPointer(at).resolve(tree.root) ?: return emptyList()
        return children(at, node, tree)
    }

    private fun children(
        at: String,
        node: ForeignNode,
        tree: ForeignTree,
    ): List<ForeignNode> =
        when (val value = node.value) {
            is JsonObject -> value.keys.mapNotNull { JsonPointer("$at/${escape(it)}").resolve(tree.root) }
            is JsonArray -> value.indices.mapNotNull { JsonPointer("$at/$it").resolve(tree.root) }
            else -> emptyList()
        }

    /** The synonym elements of [record]: the list at `terms`, or the string there cut at `split` (or whole). */
    private fun elements(
        record: ForeignNode,
        rule: LexiconRule,
    ): List<Pair<ForeignNode, String?>> {
        val node = JsonPointer(rule.terms).resolve(record) ?: return emptyList()
        return when (val value = node.value) {
            is JsonArray ->
                value.indices.mapNotNull { JsonPointer("${rule.terms}/$it").resolve(record) }.map {
                    it to
                        null
                }
            is JsonPrimitive ->
                if (!value.isString) {
                    throw ProfileException(
                        "ys/profile-invalid",
                        node.span(),
                        "lexicon terms must be a list or a string",
                    )
                } else {
                    cut(value.content, rule.split).map { node to it }
                }
            else -> throw ProfileException(
                "ys/profile-invalid",
                node.span(),
                "lexicon terms must be a list or a string",
            )
        }
    }

    /** `values.split`'s rule: trimmed non-empty parts; a blank separator cuts at whitespace runs. */
    private fun cut(
        text: String,
        separator: String?,
    ): List<String> {
        if (separator == null) return listOf(text.trim()).filter { it.isNotEmpty() }
        val trimmed = separator.trim()
        val parts = if (trimmed.isEmpty()) text.split(Regex("\\s+")) else text.split(trimmed)
        return parts.map { it.trim() }.filter { it.isNotEmpty() }
    }

    /** (locale, form) of one element, or null (with a warning) when it has no language. */
    private fun located(
        element: Pair<ForeignNode, String?>,
        rule: LexiconRule,
        id: String,
        warnings: MutableList<String>,
    ): Pair<String, String>? {
        val (node, part) = element
        val form =
            part ?: rule.form?.let { string(JsonPointer(it).resolve(node)) } ?: string(node)
                ?: throw ProfileException("ys/profile-invalid", node.span(), "a lexicon form must be a string")
        val locale =
            rule.locale ?: rule.language?.let { string(JsonPointer(it).resolve(node))?.lowercase() } ?: run {
                warnings += "lexicon: \"$form\" of $id has no language at ${rule.language}; skipped"
                return null
            }
        if (!LOCALE.matches(locale)) {
            throw ProfileException("ys/profile-invalid", node.span(), "language tag $locale is not a lexicon locale")
        }
        return locale to form
    }

    private fun string(node: ForeignNode?): String? =
        (node?.value as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

    private fun escape(key: String) = key.replace("~", "~0").replace("/", "~1")
}
