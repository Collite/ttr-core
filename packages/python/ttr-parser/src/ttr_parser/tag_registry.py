# SPDX-License-Identifier: Apache-2.0
"""Tag registry — resolve a triple-quoted block's tag prefix to its language/dialect.

Mirrors `packages/parser/src/tag-registry.ts` (TAG_REGISTRY) and the Kotlin
`TagRegistry.kt` exactly. Used by the walker to populate
`TaggedBlockValue.language` / `TaggedBlockValue.dialect` when it strips the
tag prefix off a `sourceText` / `definitionSql` block.

The registry is a **static lookup table** — it is NOT cross-reference
resolution (that lives in `ttr_parser.semantics`, phase P4). Tags and
entries MUST stay byte-identical between TS and Kotlin so the conformance
§5.1 dump cannot drift.
"""

from __future__ import annotations

from dataclasses import dataclass

__all__ = ["EXPRESSION_TAG_REGISTRY", "TagEntry", "TAG_REGISTRY", "resolve_expression_tag", "resolve_tag"]


@dataclass(frozen=True, slots=True)
class TagEntry:
    """A row in the static tag table.

    `dialect` is `None` for bare `sql` (deferred to the project default in
    modeler.toml) and for non-SQL embedded languages (`transform`, `dataframe`,
    `relnode`).
    """

    language: str  # LanguageKind: 'SQL' | 'TRANSFORMATION_DSL' | 'DATAFRAME_DSL' | 'REL_NODE'
    dialect: str | None


# Mirror of `packages/parser/src/tag-registry.ts` `TAG_REGISTRY`.
# Any change here must be reflected in the TS source and the Kotlin
# `TagRegistry.kt` (conformance §5).
TAG_REGISTRY: dict[str, TagEntry] = {
    # Bare SQL → dialect deferred to the project default in modeler.toml.
    "sql": TagEntry("SQL", None),
    # T-SQL aliases.
    "ms-sql": TagEntry("SQL", "tsql"),
    "tsql": TagEntry("SQL", "tsql"),
    "mssql": TagEntry("SQL", "tsql"),
    # PostgreSQL aliases.
    "postgres": TagEntry("SQL", "postgres"),
    "postgresql": TagEntry("SQL", "postgres"),
    "pg": TagEntry("SQL", "postgres"),
    # DuckDB (postgres-derived grammar + patches).
    "duckdb": TagEntry("SQL", "duckdb"),
    # Other SQL dialects (grammars land later).
    "mysql": TagEntry("SQL", "mysql"),
    "bigquery": TagEntry("SQL", "bigquery"),
    "bq": TagEntry("SQL", "bigquery"),
    # Non-SQL embedded languages.
    "transform": TagEntry("TRANSFORMATION_DSL", None),
    "dataframe": TagEntry("DATAFRAME_DSL", None),
    "relnode": TagEntry("REL_NODE", None),
}


def resolve_tag(tag: str) -> TagEntry | None:
    """Resolve `tag` to its registry entry, or `None` if unknown.

    Caller diagnoses the unknown case (walker emits a `ParseWarning` and
    falls back to a plain `TripleStringValue` carrying the raw text — the
    parsed body is preserved, just not semantically tagged).
    """
    return TAG_REGISTRY.get(tag)


# Grammar 0.17 (⚑CN-P5) — the languages an EXPRESSION block (`definition:`, `members:`,
# a cn binding's `filter:`) may be tagged with: only TTR-P's expression language,
# `"""pl`. A separate table from `TAG_REGISTRY`, which names the languages a
# `sourceText` / `definitionSql` block holds. Mirrors the TS `EXPRESSION_TAG_REGISTRY`.
EXPRESSION_TAG_REGISTRY: dict[str, str] = {"pl": "pl"}


def resolve_expression_tag(tag: str) -> str | None:
    """Resolve an expression-block tag to its language, or `None` (caller diagnoses)."""
    return EXPRESSION_TAG_REGISTRY.get(tag)
