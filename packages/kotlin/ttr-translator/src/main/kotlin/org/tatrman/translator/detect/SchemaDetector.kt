// SPDX-License-Identifier: Apache-2.0
package org.tatrman.translator.detect

import org.tatrman.plan.v1.SchemaCode
import org.tatrman.translate.v1.Language
import org.tatrman.translator.codec.sql.ParseResult
import org.tatrman.translator.codec.sql.SqlParser
import org.tatrman.translator.framework.ModelHandle

enum class SchemaDecision {
    CONFIRMED,
    AUTODETECTED,
    CORRECTED,
    AMBIGUOUS,
    UNKNOWN,
    MIXED,
    NOT_APPLICABLE,
}

data class Suggestion(
    val unknownName: String,
    val candidates: List<String>,
)

data class SchemaDetectionResult(
    val effectiveSchema: SchemaCode,
    val decision: SchemaDecision,
    val statedSchema: SchemaCode,
    val referencedTables: List<String>,
    val perTableSchemas: Map<String, Set<SchemaCode>>,
    val unknownTables: List<String>,
    val suggestions: List<Suggestion> = emptyList(),
)

object SchemaDetector {
    val CONSIDERED: List<SchemaCode> = listOf(SchemaCode.DB, SchemaCode.ER)

    /**
     * Classifies [source] by the lane of the names its FROM clauses read. [savedQueryRefsNeutral] (LQ, the strict
     * saved-query parse) leaves saved-query references (`obj.<namespace>.<name>`) out of the verdict: they name
     * neither lane, and the translator checks the lane of what they unfold to on the plan.
     */
    @JvmOverloads
    fun detect(
        source: String,
        sourceLanguage: Language,
        statedSchema: SchemaCode,
        model: ModelHandle,
        savedQueryRefsNeutral: Boolean = false,
    ): SchemaDetectionResult {
        // Schema detection parses SQL via Calcite and is often the first Calcite touch in a
        // request (parseSql calls it before building the framework). Promote the default literal
        // charset to UTF-8 here too so it's set before Calcite loads. Idempotent; see CalciteCharset.
        org.tatrman.translator.framework.CalciteCharset
            .ensureUtf8()
        if (sourceLanguage != Language.SQL) {
            return SchemaDetectionResult(
                effectiveSchema = statedSchema,
                decision = SchemaDecision.NOT_APPLICABLE,
                statedSchema = statedSchema,
                referencedTables = emptyList(),
                perTableSchemas = emptyMap(),
                unknownTables = emptyList(),
            )
        }

        val parseResult = SqlParser.parseQuery(source)
        if (parseResult !is ParseResult.Success) {
            return SchemaDetectionResult(
                effectiveSchema = statedSchema,
                decision = SchemaDecision.NOT_APPLICABLE,
                statedSchema = statedSchema,
                referencedTables = emptyList(),
                perTableSchemas = emptyMap(),
                unknownTables = emptyList(),
            )
        }

        val tables = TableIdentifierExtractor.fromQuery(parseResult.sqlNode, skipSavedQueryRefs = savedQueryRefsNeutral)
        if (tables.isEmpty()) {
            return SchemaDetectionResult(
                effectiveSchema = statedSchema,
                decision = SchemaDecision.NOT_APPLICABLE,
                statedSchema = statedSchema,
                referencedTables = emptyList(),
                perTableSchemas = emptyMap(),
                unknownTables = emptyList(),
            )
        }

        val dbNames = buildLowercasedNames(model, SchemaCode.DB)
        val erNames = buildLowercasedNames(model, SchemaCode.ER)

        val perTableSchemas = mutableMapOf<String, MutableSet<SchemaCode>>()
        for (t in tables) {
            perTableSchemas.getOrPut(t) { mutableSetOf() }
            if (dbNames.contains(t)) perTableSchemas[t]!!.add(SchemaCode.DB)
            if (erNames.contains(t)) perTableSchemas[t]!!.add(SchemaCode.ER)
        }

        val unknownTables = tables.filter { perTableSchemas[it]!!.isEmpty() }.sorted()
        val nonUnknownTables = tables.filter { perTableSchemas[it]!!.isNotEmpty() }
        val candidateSchemas =
            CONSIDERED
                .filter { schema ->
                    nonUnknownTables.all { perTableSchemas[it]!!.contains(schema) }
                }.toSet()

        val (effectiveSchema, decision) =
            classify(
                perTableSchemas = perTableSchemas,
                statedSchema = statedSchema,
                unknownTables = unknownTables,
                candidateSchemas = candidateSchemas,
            )

        val suggestions =
            if (decision == SchemaDecision.UNKNOWN && unknownTables.isNotEmpty()) {
                val corpus = dbNames + erNames
                unknownTables.map { name ->
                    Suggestion(
                        unknownName = name,
                        candidates =
                            org.tatrman.translator.suggest.IdentifierSuggester
                                .suggest(name, corpus),
                    )
                }
            } else {
                emptyList()
            }

        return SchemaDetectionResult(
            effectiveSchema = effectiveSchema,
            decision = decision,
            statedSchema = statedSchema,
            referencedTables = tables.sorted(),
            perTableSchemas = perTableSchemas.mapValues { it.value.toSet() },
            unknownTables = unknownTables,
            suggestions = suggestions,
        )
    }

    private fun buildLowercasedNames(
        model: ModelHandle,
        schemaCode: SchemaCode,
    ): Set<String> {
        val names = mutableSetOf<String>()
        for (ns in model.namespaces(schemaCode)) {
            when (schemaCode) {
                SchemaCode.DB -> {
                    for (qname in model.tables(schemaCode, ns).keys) {
                        names.add(qname.name.lowercase())
                    }
                }
                SchemaCode.ER -> {
                    for (qname in model.entities(schemaCode, ns).keys) {
                        names.add(qname.name.lowercase())
                    }
                }
                else -> {}
            }
        }
        return names
    }

    internal fun classify(
        perTableSchemas: Map<String, Set<SchemaCode>>,
        statedSchema: SchemaCode,
        unknownTables: List<String>,
        candidateSchemas: Set<SchemaCode>,
    ): Pair<SchemaCode, SchemaDecision> {
        if (unknownTables.isNotEmpty()) {
            return SchemaCode.SCHEMA_CODE_UNSPECIFIED to SchemaDecision.UNKNOWN
        }

        if (candidateSchemas.isEmpty()) {
            return SchemaCode.SCHEMA_CODE_UNSPECIFIED to SchemaDecision.MIXED
        }

        if (candidateSchemas.size == 1) {
            val s = candidateSchemas.first()
            when {
                statedSchema == SchemaCode.SCHEMA_CODE_UNSPECIFIED -> return s to SchemaDecision.AUTODETECTED
                statedSchema == s -> return s to SchemaDecision.CONFIRMED
                else -> return s to SchemaDecision.CORRECTED
            }
        } else {
            when {
                statedSchema == SchemaCode.SCHEMA_CODE_UNSPECIFIED ->
                    return SchemaCode.SCHEMA_CODE_UNSPECIFIED to
                        SchemaDecision.AMBIGUOUS
                candidateSchemas.contains(statedSchema) -> return statedSchema to SchemaDecision.CONFIRMED
                else -> return SchemaCode.SCHEMA_CODE_UNSPECIFIED to SchemaDecision.AMBIGUOUS
            }
        }
    }
}
