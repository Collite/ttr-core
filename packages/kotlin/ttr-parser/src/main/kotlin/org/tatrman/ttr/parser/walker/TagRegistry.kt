// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.parser.walker

import org.tatrman.ttr.parser.model.LanguageKind

/**
 * Tag → language/dialect registry (embedded-sql DESIGN §5, contracts §3).
 *
 * A faithful mirror of the TS `TAG_REGISTRY` in
 * `packages/parser/src/tag-registry.ts`. Resolution to `language`/`dialect`
 * happens during the walk to populate [org.tatrman.ttr.parser.model.TaggedBlockValue]
 * — a static table lookup mirrored in both parsers, not cross-reference
 * resolution (which stays in the semantics layer). The two tables MUST agree
 * entry-for-entry (conformance §6).
 */
data class TagEntry(
    val language: LanguageKind,
    val dialect: String?, // null for a bare `sql` (→ modeler.toml default) or a non-SQL language
)

val TAG_REGISTRY: Map<String, TagEntry> =
    mapOf(
        // Bare SQL → dialect deferred to the project default in modeler.toml.
        "sql" to TagEntry("SQL", null),
        // T-SQL aliases.
        "ms-sql" to TagEntry("SQL", "tsql"),
        "tsql" to TagEntry("SQL", "tsql"),
        "mssql" to TagEntry("SQL", "tsql"),
        // PostgreSQL aliases.
        "postgres" to TagEntry("SQL", "postgres"),
        "postgresql" to TagEntry("SQL", "postgres"),
        "pg" to TagEntry("SQL", "postgres"),
        // DuckDB (postgres-derived grammar + patches).
        "duckdb" to TagEntry("SQL", "duckdb"),
        // Other SQL dialects (grammars land later).
        "mysql" to TagEntry("SQL", "mysql"),
        "bigquery" to TagEntry("SQL", "bigquery"),
        "bq" to TagEntry("SQL", "bigquery"),
        // Non-SQL embedded languages.
        "transform" to TagEntry("TRANSFORMATION_DSL", null),
        "dataframe" to TagEntry("DATAFRAME_DSL", null),
        "relnode" to TagEntry("REL_NODE", null),
    )

/** Resolve a tag to its registry entry, or `null` if unknown (caller diagnoses). */
fun resolveTag(tag: String): TagEntry? = TAG_REGISTRY[tag]

/**
 * Grammar 0.17 (⚑CN-P5) — the languages an EXPRESSION block (`definition:`, `members:`, a cn binding's
 * `filter:`) may be tagged with: only TTR-P's expression language, `"""pl`. A separate table from
 * [TAG_REGISTRY], which names the languages a `sourceText` / `definitionSql` block holds. Mirrors the TS
 * `EXPRESSION_TAG_REGISTRY`.
 */
val EXPRESSION_TAG_REGISTRY: Map<String, String> = mapOf("pl" to "pl")

/** Resolve an expression-block tag to its language, or `null` if it names none (caller diagnoses). */
fun resolveExpressionTag(tag: String): String? = EXPRESSION_TAG_REGISTRY[tag]
