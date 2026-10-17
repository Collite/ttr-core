# SPDX-License-Identifier: Apache-2.0
"""Diagnostic codes and severity shared across the parser and semantics layers.

The `DiagnosticCode` enum's `.value` strings are part of the public contract —
they match the Kotlin `DiagnosticCode.id` (and the TS `DiagnosticCode.*` enum
literal strings) byte-for-byte so the conformance diagnostic dump (§5.1) is
interchangeable across the three runtimes.

The parser emits the parse-level codes (`PARSE_ERROR`, `PARSE_RECOVERY_INFO`).
The semantics layer (P4) emits the resolution/validation codes. Both layers
import from this single canonical enum.
"""

from __future__ import annotations

from enum import Enum


class DiagnosticCode(Enum):
    PARSE_ERROR = "ttr/parse-error"
    PARSE_RECOVERY_INFO = "ttr/parse-recovery-info"
    UNKNOWN_PROPERTY = "ttr/unknown-property"
    UNRESOLVED_REFERENCE = "ttr/unresolved-reference"
    DUPLICATE_DEFINITION = "ttr/duplicate-definition"
    REQUIRED_PROPERTY_MISSING = "ttr/required-property-missing"
    INVALID_TYPE = "ttr/invalid-type"
    ENTITY_ATTRIBUTE_NOT_FOUND = "ttr/entity-attribute-not-found"
    PRIMARY_KEY_COLUMN_NOT_FOUND = "ttr/primary-key-column-not-found"
    WRONG_FILE_KIND = "ttr/wrong-file-kind"
    UNIMPORTED_REFERENCE = "ttr/unimported-reference"
    UNUSED_IMPORT = "ttr/unused-import"
    WILDCARD_WITH_NO_MATCHES = "ttr/wildcard-with-no-matches"
    DUPLICATE_IMPORT = "ttr/duplicate-import"
    CIRCULAR_PACKAGE_DEPENDENCY = "ttr/circular-package-dependency"
    PACKAGE_DECLARATION_MISMATCH = "ttr/package-declaration-mismatch"
    MISSING_PACKAGE_DECLARATION = "ttr/missing-package-declaration"
    AMBIGUOUS_REFERENCE = "ttr/ambiguous-reference"
    GRAPH_OBJECT_NOT_FOUND = "ttr/graph-object-not-found"
    GRAPH_LAYOUT_STALE_NODE = "ttr/graph-layout-stale-node"
    # Grounding Phase 1 (grammar 4.2) — a `semantics { … }` entry whose value has no
    # data meaning at any depth: a `functionCall`. Lists and nested objects ARE carried
    # (MS, vocabulary v3); which keys may hold them is vocabulary knowledge and lives in
    # the semantics layer, not here.
    SEMANTICS_NON_SCALAR_VALUE = "ttr/semantics-non-scalar"
    GRAPH_OBJECTS_EMPTY = "ttr/graph-objects-empty"
    GRAPH_NAME_MISMATCH = "ttr/graph-name-mismatch"
    FILE_ORDERING = "ttr/file-ordering"
    FUZZY_WITHOUT_SEARCHABLE = "ttr/fuzzy-without-searchable"
    DUPLICATE_SEARCH_PROPERTY = "ttr/duplicate-search-property"
    # RV-P1.5 (grammar 0.12, RV-32) - the match-method attribute on `searchable`.
    # Cross-target contract: mirrored in the TS and Kotlin `DiagnosticCode`; the
    # deprecation rides the portable conformance subset.
    SEARCH_FUZZY_DEPRECATED = "ttr/search-fuzzy-deprecated"
    UNKNOWN_MATCH_METHOD = "ttr/unknown-match-method"
    INVALID_MATCH_METHOD_ARGUMENT = "ttr/invalid-match-method-argument"
    DUPLICATE_BINDING = "ttr/duplicate-binding"
    UNKNOWN_LANGUAGE_TAG = "ttr/unknown-language-tag"
    LANGUAGE_TAG_MISMATCH = "ttr/language-tag-mismatch"
    DEPRECATED_LANGUAGE_PROPERTY = "ttr/deprecated-language-property"
    # LQ (logical queries, LQ-5) - a `def query` in a `model er` file takes no parameters.
    LOGICAL_QUERY_PARAMETERS = "ttr/logical-query-parameters"

    def __str__(self) -> str:
        return self.value


class DiagnosticSeverity(Enum):
    ERROR = "Error"
    WARNING = "Warning"
    INFORMATION = "Information"
    HINT = "Hint"

    def __str__(self) -> str:
        return self.value