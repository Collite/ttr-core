// SPDX-License-Identifier: Apache-2.0
package org.tatrman.translator.suggest

import org.tatrman.plan.v1.SchemaCode
import org.tatrman.plan.v1.schemaCodeToToken
import org.tatrman.translator.framework.ModelHandle

/**
 * Phase 08 C2 — best-effort enrichment of a Calcite validation-error message with
 * "did you mean…?" suggestions. Calcite's `SqlValidatorException` messages typically embed the
 * offending identifier in single quotes (`Column 'custmer_name' not found in any table`,
 * `Object 'CUSTOMRES' not found`); this helper extracts the first such token, looks it up in
 * the corpus, and appends the top suggestions to the message.
 *
 * Falls back to the original message untouched when:
 *   - no quoted identifier is found (message shape isn't the expected one)
 *   - the suggester returns no qualifying candidates (typo distance > threshold)
 *   - the corpus is empty (no model loaded yet)
 *
 * Format of the appended fragment: `" — did you mean: a, b, c?"`. Keeps the original message
 * verbatim ahead of the suffix so log greps and existing tests don't break.
 */
object SuggestingMessage {
    private val QUOTED_IDENT = Regex("'([^']{1,128})'")

    fun enrich(
        message: String,
        corpus: Collection<String>,
        limit: Int = 3,
    ): String {
        if (corpus.isEmpty()) return message
        val bad = QUOTED_IDENT.find(message)?.groupValues?.get(1) ?: return message
        val suggestions = IdentifierSuggester.suggest(bad, corpus, limit)
        if (suggestions.isEmpty()) return message
        return "$message — did you mean: ${suggestions.joinToString(", ")}?"
    }

    fun enrich(
        message: String,
        handle: ModelHandle,
        activeSchema: SchemaCode,
        namespace: String,
        limit: Int = 3,
    ): String {
        val bad = QUOTED_IDENT.find(message)?.groupValues?.get(1) ?: return message
        val activeIsConsidered = activeSchema == SchemaCode.DB || activeSchema == SchemaCode.ER

        // Cross-schema hint: only meaningful when we parsed against a concrete schema and the
        // identifier actually lives in a different one. With no resolved active schema
        // (UNSPECIFIED), skip the hint and fall back to plain suggestions.
        if (activeIsConsidered) {
            val inOtherSchemas = ModelIdentifierCorpus.locate(handle, bad)
            if (inOtherSchemas.isNotEmpty() && !inOtherSchemas.contains(activeSchema)) {
                val otherSchemas = inOtherSchemas.joinToString(", ") { schemaCodeToToken(it) }
                return "$message — '$bad' is a $otherSchemas object; this query was parsed against ${schemaCodeToToken(
                    activeSchema,
                )}. Set source_schema=${schemaCodeToToken(
                    inOtherSchemas.first(),
                ).uppercase()}, or use the ${schemaCodeToToken(activeSchema)} entity name."
            }
        }

        // Suggestions: scoped to the active schema when known, else the model-wide corpus.
        val corpus =
            if (activeIsConsidered) {
                val activeNamespace = if (namespace.isEmpty()) defaultNamespace(activeSchema) else namespace
                ModelIdentifierCorpus.collect(handle, activeSchema, activeNamespace)
            } else {
                ModelIdentifierCorpus.collect(handle)
            }
        if (corpus.isEmpty()) return message
        val suggestions = IdentifierSuggester.suggest(bad, corpus, limit)
        if (suggestions.isEmpty()) return message
        return "$message — did you mean: ${suggestions.joinToString(", ")}?"
    }

    private fun defaultNamespace(schemaCode: SchemaCode): String =
        when (schemaCode) {
            SchemaCode.DB -> "dbo"
            SchemaCode.ER -> "entity"
            else -> ""
        }
}
