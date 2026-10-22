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
    MD_MD2ER_PHYSICAL_PROP = "md/md2er-physical-prop"
    MD_MD2ER_REFERENCE = "md/md2er-reference"
    MD_MD2ER_DIMENSION_KEY = "md/md2er-dimension-key"
    MD_MD2ER_PATH_AMBIGUOUS = "md/md2er-path-ambiguous"
    MD_MD2ER_MEASURE_UNKNOWN = "md/md2er-measure-unknown"
    MD_DUPLICATE_BINDING = "md/duplicate-binding"
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
    # 0.17 (CN-P1) - parse-level checks on a cn body. The `cn/*` slugs (cn contracts §7) are semantic, CN-P2.
    DUPLICATE_PROPERTY = "ttr/duplicate-property"  # a cn body names one key twice - the first is read
    EXPRESSION_BLOCK_REQUIRED = "ttr/expression-block-required"  # a binding `filter:` that is not a string or block
    # 0.17 (CN-P2) - the model-level `cn/*` slugs (cn contracts §7), the portable ones. The lexicon pair
    # (`cn/duplicate-default-sense`, `cn/phase-as-default`) is TS-only; the definition-level slugs are Kotlin's.
    CN_DUPLICATE_GROUNDING = "cn/duplicate-grounding"  # a concept's second grounding, or sugar restating a concept
    CN_GROUNDING_TARGET_KIND = "cn/grounding-target-kind"  # a target the stereotype x layer table does not allow
    CN_ROLE_NO_EVENT = "cn/role-no-event"  # a role without its defining event (CN-D6)
    CN_PHASE_DOUBLE_DEFINITION = "cn/phase-double-definition"  # a phase both defined and grounded (AM-2)
    CN_PHASE_NO_DEFINITION = "cn/phase-no-definition"  # hygiene: a phase neither defined nor grounded
    CN_EVENT_NO_PERIOD = "cn/event-no-period"  # hygiene: an event without `period:`
    CN_UNGROUNDED = "cn/ungrounded"  # hygiene: a concept with no grounding (P-1)
    CN_DB_GROUNDING = "cn/db-grounding"  # advisory: a db grounding - documentation only
    CN_HIERARCHY_SHAPE = "cn/hierarchy-shape"  # a `hierarchy:` closure without its two attributes or a key
    CN_PARTICIPANT_PATH_BROKEN = "cn/participant-path-broken"  # a participant's relation path does not chain
    CN_INVOLVES_UNLABELLED_DUPLICATE = "cn/involves-unlabelled-duplicate"  # two participants of one event, one label
    CN_UNKNOWN_RELATION_TYPE = "cn/unknown-relation-type"  # an open-tier edge whose `type:` is no relation type
    CN_ANTIRIGID_SUPERTYPE = "cn/antirigid-supertype"  # a kind whose `is_a` chain reaches a role or a phase
    CN_CNC_DEPRECATED = "cn/cnc-deprecated"  # `model cnc` / `roles:` - deprecated (cn contracts §1.6)

    def __str__(self) -> str:
        return self.value


class DiagnosticSeverity(Enum):
    ERROR = "Error"
    WARNING = "Warning"
    INFORMATION = "Information"
    HINT = "Hint"

    def __str__(self) -> str:
        return self.value