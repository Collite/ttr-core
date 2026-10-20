# SPDX-License-Identifier: Apache-2.0
"""Typed model produced by the TTR parser (P2).

Pure data — no logic. Every `def <kind> <name> { ... }` block in a `.ttrm`
file becomes one `Definition` subtype. Cross-references are kept as raw
`Reference`s; semantic resolution is the consumer's job (P4).

Mirrors the Kotlin `packages/kotlin/ttr-parser/src/main/kotlin/.../model/Definition.kt`
(PINNED.md: "Python classes mirror the Kotlin names") and the TS
`packages/parser/src/ast.ts`. Class names match Kotlin (`ProjectDef`,
`Er2DbEntityDef`, `Er2CncRoleDef`). Field names are **snake_case** (D5).
The `kind` class-var is the lowercased TTR keyword — used by the
conformance dumper (§5) and by `isinstance`/`match` dispatch in consumers.

No `GraphBlock` here: the Python port is read-only + models-only per
INDEX.md scope; graphs (`graph { … }`, `.ttrg`) are out of scope.

Conventions (contracts §0):
- `@dataclass(frozen=True, slots=True)` for every concrete type.
- Collection fields are **tuples** (immutable, hashable, parity with frozen).
- PEP 604 unions (`X | Y`).
- Every PropertyValue variant carries a `source: SourceLocation`.
"""

from __future__ import annotations

from collections.abc import Mapping
from dataclasses import dataclass, field
from types import MappingProxyType
from typing import ClassVar

from .diagnostics import DiagnosticCode

# ============================================================================
# SourceLocation (D4 superset; ANTLR-style)
# ============================================================================


@dataclass(frozen=True, slots=True)
class SourceLocation:
    """ANTLR-style source span (contracts §2.4).

    - `line` / `end_line` are 1-indexed (match ANTLR `token.line`).
    - `column` / `end_column` are 0-indexed (match ANTLR `charPositionInLine`);
      `end_column` is one past the last character of the last token in the span.
    - `offset_start` / `offset_end` are 0-indexed byte offsets; `offset_end`
      is **exclusive** — `source[offset_start:offset_end]` slices the span.

    The multi-token-span invariant: `end_column = stop_token.column + len(stop_token.text)`
    (NOT `start_column + span_length`). The walker enforces this.
    """

    file: str
    line: int
    column: int
    end_line: int
    end_column: int
    offset_start: int
    offset_end: int

    def __str__(self) -> str:
        return f"{self.file}:{self.line}:{self.column}"

    UNKNOWN: ClassVar[SourceLocation]


SourceLocation.UNKNOWN = SourceLocation("<unknown>", -1, -1, -1, -1, -1, -1)


# ============================================================================
# ParseError / ParseWarning
# ============================================================================


@dataclass(frozen=True, slots=True)
class ParseError:
    """Parser-level error (contracts §2.3).

    Note: `line` / `column` are **1-indexed for human display** (per contracts
    §2.3 + `CLAUDE.md` "LSP-style consumers subtract 1"). This is **distinct**
    from `SourceLocation` on AST nodes, which uses 0-indexed columns.
    """

    file: str
    line: int
    column: int
    message: str
    code: DiagnosticCode = DiagnosticCode.PARSE_ERROR

    def __str__(self) -> str:
        return f"{self.file}:{self.line}:{self.column}: {self.message}"


@dataclass(frozen=True, slots=True)
class ParseWarning:
    file: str
    line: int
    column: int
    message: str
    code: DiagnosticCode


# ============================================================================
# Reference (carries the id token's own span, distinct from the def's source)
# ============================================================================


@dataclass(frozen=True, slots=True)
class Reference:
    """Cross-reference identifier.

    Carries `parts` (the path split on `.`) and `source` — the **id token's**
    span, not the enclosing def's. Mirrors the canonical TS `Reference`
    (`{ path, parts, source }`) and Kotlin `Reference` shape.

    Use `Reference.of(path)` when constructing outside the parser (tests,
    synthesizers) where no token span exists; it derives `parts` and uses
    `SourceLocation.UNKNOWN`. The walker always supplies a real span.
    """

    path: str
    parts: tuple[str, ...]
    source: SourceLocation

    @classmethod
    def of(cls, path: str) -> Reference:
        return cls(path, tuple(path.split(".")), SourceLocation.UNKNOWN)

    def __str__(self) -> str:
        return self.path


# ============================================================================
# Localized strings & search hints
# ============================================================================


@dataclass(frozen=True, slots=True)
class LocalizedStringValue:
    """`{ cs: "...", en: "..." }` carrier."""

    by_language: Mapping[str, str] = field(default_factory=lambda: MappingProxyType({}))


@dataclass(frozen=True, slots=True)
class LocalizedStringListValue:
    """`{ cs: ["...", "..."], en: ["..."] }` carrier."""

    by_language: Mapping[str, tuple[str, ...]] = field(
        default_factory=lambda: MappingProxyType({})
    )


@dataclass(frozen=True, slots=True)
class MatchMethodValue:
    """Grammar 0.12 (RV-P1.5, RV-32) — `method: EXACT | TYPOS(n) | TOKENS`.

    Parser-side carrier: `name` exactly as authored, `argument` the raw numeric
    literal. Mirrors the TS `MatchMethod` and the Kotlin `MatchMethodValue`; the
    vocabulary and the arity rule live in the semantics layer.
    """

    name: str
    argument: float | None = None


@dataclass(frozen=True, slots=True)
class SearchHintsValue:
    """`search { keywords {...} patterns [...] descriptions {...} examples [...]
    aliases [...] searchable: bool fuzzy: bool }` carrier.

    v2.0.0: `searchable` lives **only** here, not on `ColumnDef`/`AttributeDef`.
    """

    keywords: LocalizedStringListValue = field(default_factory=LocalizedStringListValue)
    patterns: tuple[str, ...] = ()
    descriptions: LocalizedStringListValue = field(default_factory=LocalizedStringListValue)
    examples: tuple[str, ...] = ()
    aliases: tuple[str, ...] = ()
    searchable: bool = False
    #: Deprecated by grammar 0.12 (RV-32) — use `method` (`fuzzy: true` == `TYPOS(1)`).
    fuzzy: bool = False
    method: MatchMethodValue | None = None
    #: Whether `fuzzy` was authored at all, as opposed to defaulting to False.
    #: The 0.12 mapping needs the distinction (`fuzzy: false` -> EXACT, absent ->
    #: the RV-32 default TYPOS(1)); mirrors the Kotlin `fuzzyAuthored`.
    fuzzy_authored: bool = False


# ============================================================================
# DataType
# ============================================================================


@dataclass(frozen=True, slots=True)
class DataType:
    """Surface or physical type. `name` is the canonical token (`text`, `int`,
    `varchar`, `decimal`, …). `length`/`precision` are present on physical
    types only (e.g. `decimal(19, 5)`)."""

    name: str
    length: int | None = None
    precision: int | None = None


# ============================================================================
# PropertyValue (sealed-style hierarchy via common base + isinstance dispatch)
# ============================================================================


@dataclass(frozen=True, slots=True)
class PropertyValue:
    """Common base for every property value variant.

    Contracts §2.5 requires `isinstance(value, Definition)` to work; same for
    `PropertyValue` — so this is a concrete common base class (NOT a
    `Protocol`). Subclasses set `kind: ClassVar[str]` to the TTR-friendly
    discriminator (`"string"`, `"tripleString"`, `"id"`, …) used by the
    conformance dumper (§5).
    """

    source: SourceLocation
    kind: ClassVar[str] = "propertyValue"


@dataclass(frozen=True, slots=True)
class StringValue(PropertyValue):
    raw: str = ""
    kind: ClassVar[str] = "string"


@dataclass(frozen=True, slots=True)
class TripleStringValue(PropertyValue):
    raw: str = ""
    kind: ClassVar[str] = "tripleString"


@dataclass(frozen=True, slots=True)
class NumberValue(PropertyValue):
    raw: float = 0.0
    kind: ClassVar[str] = "number"


@dataclass(frozen=True, slots=True)
class BoolValue(PropertyValue):
    raw: bool = False
    kind: ClassVar[str] = "bool"


@dataclass(frozen=True, slots=True)
class NullValue(PropertyValue):
    kind: ClassVar[str] = "null"


@dataclass(frozen=True, slots=True)
class IdValue(PropertyValue):
    """An identifier reference inside a property value (e.g. `from: db.dbo.X`).

    Carries `ref` (a `Reference` with the id token's own span) **and** `parts`
    (the dotted-name split) — matches both TS `IdValue.parts` and Kotlin
    `IdValue.parts` for cross-language parity.
    """

    ref: Reference | None = None
    parts: tuple[str, ...] = ()
    kind: ClassVar[str] = "id"


@dataclass(frozen=True, slots=True)
class ListValue(PropertyValue):
    items: tuple[PropertyValue, ...] = ()
    kind: ClassVar[str] = "list"


@dataclass(frozen=True, slots=True)
class ObjectValue(PropertyValue):
    """Object literal `{ key: value, … }`.

    Per contracts §2.7 (Kotlin shape): entries are a **Mapping** (last-write-wins),
    not an ordered list. This diverges from the TS `entries: ObjectEntry[]`
    (ordered, with per-entry source). Consumers that need entry order read the
    raw parse tree; consumers that need a key→value lookup use this map.
    """

    entries: Mapping[str, PropertyValue] = field(default_factory=lambda: MappingProxyType({}))
    kind: ClassVar[str] = "object"


@dataclass(frozen=True, slots=True)
class FunctionCall(PropertyValue):
    """A function call expression `name(arg, arg, …)`."""

    name: str = ""
    args: tuple[PropertyValue, ...] = ()
    kind: ClassVar[str] = "functionCall"


# ============================================================================
# LanguageKind — `Literal` over the four query-language tags
# ============================================================================

LanguageKind = str  # see contracts §2.6: 'SQL' | 'TRANSFORMATION_DSL' | 'DATAFRAME_DSL' | 'REL_NODE'


# ============================================================================
# TaggedBlockValue — top-level PropertyValue variant for embedded SQL blocks
# ============================================================================


@dataclass(frozen=True, slots=True)
class TaggedBlockValue(PropertyValue):
    """A triple-quoted block with a tag prefix carrying embedded foreign-language source.

    Produced only by `sourceText` / `definitionSql` via the `embeddedBlock`
    grammar rule. The tag is peeled before `value`, so it never reaches the
    executed SQL.
    """

    tag: str = ""
    language: LanguageKind = "SQL"
    dialect: str | None = None
    value: str = ""
    tag_source: SourceLocation = field(default_factory=lambda: SourceLocation.UNKNOWN)
    value_source: SourceLocation = field(default_factory=lambda: SourceLocation.UNKNOWN)
    indent_width: int = 0
    kind: ClassVar[str] = "taggedBlock"


# ============================================================================
# Binding types (v2.1; v3.0 renamed the `mapping:` keyword → `binding:`)
# ============================================================================


@dataclass(frozen=True, slots=True)
class BindingProperty:
    """Common base for inline `binding:` property variants.

    Subclasses set `kind` to `"bareId"` or `"block"`.
    """

    source: SourceLocation = field(default_factory=lambda: SourceLocation.UNKNOWN)
    kind: ClassVar[str] = "binding"


@dataclass(frozen=True, slots=True)
class BindingPropertyBareId(BindingProperty):
    """`binding: db.dbo.fk_a_b` — relation FK shorthand or attribute-level binding."""

    id: Reference | None = None
    kind: ClassVar[str] = "bareId"


@dataclass(frozen=True, slots=True)
class BindingPropertyBlock(BindingProperty):
    """`binding: { target: …, columns: { … }, fk: … }`."""

    target: TargetValue | None = None
    columns: tuple[BindingColumnEntry, ...] = ()
    fk: Reference | None = None
    kind: ClassVar[str] = "block"


@dataclass(frozen=True, slots=True)
class TargetValue:
    """Common base for `target:` value variants.

    Both inline `binding: { target: … }` blocks and explicit `def er2db_*`
    `target:` slots accept either an object (e.g. `{ table: db.dbo.T }`) or
    a bare reference (e.g. `db.dbo.T`).
    """

    source: SourceLocation = field(default_factory=lambda: SourceLocation.UNKNOWN)
    kind: ClassVar[str] = "target"


@dataclass(frozen=True, slots=True)
class TargetObjectValue(TargetValue):
    obj: ObjectValue | None = None
    kind: ClassVar[str] = "object"


@dataclass(frozen=True, slots=True)
class TargetReferenceValue(TargetValue):
    ref: Reference | None = None
    kind: ClassVar[str] = "reference"


@dataclass(frozen=True, slots=True)
class BindingColumnEntry:
    """One entry in a `binding: { columns: { sk: i_item_sk, … } }` map."""

    name: str = ""
    value: BindingColumnValue | None = None
    source: SourceLocation = field(default_factory=lambda: SourceLocation.UNKNOWN)


@dataclass(frozen=True, slots=True)
class BindingColumnValue:
    """Common base for inline `columns:` value variants."""

    source: SourceLocation = field(default_factory=lambda: SourceLocation.UNKNOWN)
    kind: ClassVar[str] = "bindingColumn"


@dataclass(frozen=True, slots=True)
class BindingColumnBareId(BindingColumnValue):
    """`sk: i_item_sk` (bare-id form)."""

    id: Reference | None = None
    kind: ClassVar[str] = "bareId"


@dataclass(frozen=True, slots=True)
class BindingColumnObject(BindingColumnValue):
    """`kód_artiklu: { target: KOD_ZBOZI }` (object form)."""

    obj: ObjectValue | None = None
    kind: ClassVar[str] = "object"


# ============================================================================
# File-level constructs
# ============================================================================


@dataclass(frozen=True, slots=True)
class ModelDirective:
    """File-level `model <code> [schema <id>] [locale <id>]`."""

    model_code: str
    schema: str | None
    source: SourceLocation
    # v4.4 — the unit-level locale (`model lexicon locale <id>`). Permissive in the
    # grammar; locale-only-on-lexicon is enforced in semantics.
    locale: str | None = None


@dataclass(frozen=True, slots=True)
class ImportStatement:
    """File-level `import <qualifiedName> [.*]`."""

    target: str
    wildcard: bool
    source: SourceLocation


@dataclass(frozen=True, slots=True)
class PackageDeclaration:
    """File-level `package <qualifiedName>`."""

    name: str
    source: SourceLocation


# ============================================================================
# Definition base + the 18 kinds (D5; mirrors Kotlin)
# ============================================================================


@dataclass(frozen=True, slots=True)
class Definition:
    """Common base for every `def <kind>` AST node.

    The `kind` class-var is the **lowercased TTR keyword** (`"table"`,
    `"er2db_entity"`, `"drill_map"`, …) — used as the conformance-dump
    discriminator (§5) and by consumer `isinstance`/`match` dispatch.
    """

    name: str
    source: SourceLocation
    description: str | None = None
    #: Grammar 0.13 (NLS-P10) — the localised `description: { en: …, cs: … }` form.
    #: At most one of `description` / `description_localized` is ever set: an author
    #: writes one form or the other and the walker keeps them apart rather than
    #: folding a map to a single locale (that is a reader's job — Veles' D7 chain).
    description_localized: LocalizedStringValue | None = None
    tags: tuple[str, ...] = ()
    kind: ClassVar[str] = "definition"


@dataclass(frozen=True, slots=True)
class ProjectDef(Definition):
    kind: ClassVar[str] = "project"
    version: str | None = None


@dataclass(frozen=True, slots=True)
class TableDef(Definition):
    primary_key: tuple[str, ...] = ()
    columns: tuple[ColumnDef, ...] = ()
    indices: tuple[IndexDef, ...] = ()
    constraints: tuple[ConstraintDef, ...] = ()
    search: SearchHintsValue = field(default_factory=SearchHintsValue)
    semantics: SemanticsBlock | None = None
    lexicon: LexiconBlock | None = None
    kind: ClassVar[str] = "table"


@dataclass(frozen=True, slots=True)
class ViewDef(Definition):
    columns: tuple[ColumnDef, ...] = ()
    # The `definitionSql` property's `PropertyValue` carrier — a `StringValue`,
    # `TripleStringValue`, or `TaggedBlockValue` (embedded SQL block).
    definition_sql: PropertyValue | None = None
    search: SearchHintsValue = field(default_factory=SearchHintsValue)
    kind: ClassVar[str] = "view"


@dataclass(frozen=True, slots=True)
class ColumnDef(Definition):
    """v2.0.0: NO top-level `searchable`; it lives in `search` (TS parity).

    `indexed` stays top-level — it is a grammar-level column property, not a
    search hint.
    """

    type: DataType | None = None
    optional: bool = False
    is_key: bool = False
    indexed: bool = False
    search: SearchHintsValue = field(default_factory=SearchHintsValue)
    semantics: SemanticsBlock | None = None
    lexicon: LexiconBlock | None = None
    kind: ClassVar[str] = "column"


@dataclass(frozen=True, slots=True)
class IndexDef(Definition):
    index_type: str | None = None
    columns: tuple[str, ...] = ()
    kind: ClassVar[str] = "index"


@dataclass(frozen=True, slots=True)
class ConstraintDef(Definition):
    constraint_type: str | None = None
    columns: tuple[str, ...] = ()
    kind: ClassVar[str] = "constraint"


@dataclass(frozen=True, slots=True)
class FkDef(Definition):
    """`from` / `to` are typed as `PropertyValue` to allow either an `IdValue`
    (the common case) or an `ObjectValue` (rare)."""

    from_: PropertyValue | None = field(default=None, metadata={"surface": "from"})
    to: PropertyValue | None = None
    kind: ClassVar[str] = "fk"


@dataclass(frozen=True, slots=True)
class ProcedureDef(Definition):
    """Each `parameters` entry is an `ObjectValue` with keys `name`/`type`/`label`/`direction`."""

    parameters: tuple[PropertyValue, ...] = ()
    result_columns: tuple[ColumnDef, ...] = ()
    kind: ClassVar[str] = "procedure"


@dataclass(frozen=True, slots=True)
class EntityDef(Definition):
    """`roles: [fact, dimension]` shorthand — refs resolved by the consumer."""

    label_plural: str | None = None
    name_attribute: Reference | None = None
    code_attribute: Reference | None = None
    aliases: tuple[str, ...] = ()
    attributes: tuple[AttributeDef, ...] = ()
    roles: tuple[Reference, ...] = ()
    display_label: LocalizedStringValue | None = None
    search: SearchHintsValue = field(default_factory=SearchHintsValue)
    semantics: SemanticsBlock | None = None
    lexicon: LexiconBlock | None = None
    binding: BindingProperty | None = None
    #: Grammar 0.17 — the `concept { kind|role|event: … }` sugar.
    concept: ConceptSugar | None = None
    kind: ClassVar[str] = "entity"


@dataclass(frozen=True, slots=True)
class AttributeDef(Definition):
    """v2.0.0: NO top-level `searchable`; it lives in `search`."""

    type: DataType | None = None
    is_key: bool = False
    optional: bool = False
    display_label: LocalizedStringValue | None = None
    value_labels: Mapping[str, LocalizedStringValue] = field(
        default_factory=lambda: MappingProxyType({})
    )
    # A4-β (v4.4 S2) — per-value `aliases` (`"1": { label: {…}, aliases: […] }`).
    value_label_aliases: Mapping[str, tuple[str, ...]] = field(
        default_factory=lambda: MappingProxyType({})
    )
    search: SearchHintsValue = field(default_factory=SearchHintsValue)
    semantics: SemanticsBlock | None = None
    lexicon: LexiconBlock | None = None
    binding: BindingProperty | None = None
    #: Grammar 0.16 — `states: [placed, held, …]`, the lifecycle states (D-67).
    states: tuple[str, ...] = ()
    #: Grammar 0.16 — `transitions: { placed: [held, …] }` (D-67), in authored order.
    transitions: tuple[AttributeTransition, ...] = ()
    #: Grammar 0.17 — the `concept { quality: … }` sugar (er: also a value kind).
    concept: ConceptSugar | None = None
    kind: ClassVar[str] = "attribute"


@dataclass(frozen=True, slots=True)
class AttributeTransition:
    """Grammar 0.16 — one `from: [to, …]` entry of an attribute's `transitions:` map."""

    from_state: str
    to: tuple[str, ...]
    source: SourceLocation


# ----- Grounding Phase 1 semantics surface (grammar 4.2) -----
#
# Port of the TS `SemanticsBlock` (`packages/parser/src/ast.ts`) and the Kotlin
# `SemanticsBlock`/`SemanticsValue` (`ttr-parser` `Definition.kt`). The parser stays
# mechanical: entries are captured raw, with NO vocabulary or shape checking — that is
# the semantics layer's job.

# A semantics entry value. MS (vocabulary v3) widened this from scalars only: the
# grammar's `value` rule has always admitted `list` and `object_`, and both other
# walkers now carry them verbatim, so this one must too or the runtimes disagree on
# what a model says. Nesting is arbitrary on purpose — the parser has no vocabulary,
# so it cannot know how deep a given key is allowed to go.
#
# Mirrors the TS union `string | number | boolean | null | SemanticsValue[] |
# { [k: string]: SemanticsValue }`. Numbers are `float` throughout (Kotlin Double,
# JS number), lists are tuples for immutability, objects are mappings.
SemanticsValue = (
    str | float | bool | None | tuple["SemanticsValue", ...] | Mapping[str, "SemanticsValue"]
)


@dataclass(frozen=True, slots=True)
class SemanticsBlock:
    """The free-form `semantics { … }` block on an entity/table/attribute/column."""

    entries: Mapping[str, SemanticsValue] = field(default_factory=lambda: MappingProxyType({}))
    # Keys that appeared more than once — at every depth since MS: a top-level repeat is
    # the bare key, a repeat inside a nested object is the dotted/indexed path to it
    # (`measures[1].attribute`). Entries stay last-wins; this records the overwrite.
    duplicate_properties: tuple[str, ...] = ()
    source: SourceLocation = SourceLocation.UNKNOWN


# ----- PL-P4.S3 (grammar 0.11, H-1) document-level `security { … }` -----


@dataclass(frozen=True, slots=True)
class SecurityStatement:
    """One §11 verb. Flat rather than a four-class union: Python has no discriminated
    union and every consumer switches on `verb` anyway, which is exactly what the TS
    dump does. Object refs stay opaque qname strings — resolution is the semantics
    layer's job, and advisory only (never a compile block)."""

    verb: str  # own | classify | grant | mask
    object_ref: str
    owner: str | None = None
    classification: str | None = None
    privilege: str | None = None
    grantee: str | None = None
    source: SourceLocation = SourceLocation.UNKNOWN


@dataclass(frozen=True, slots=True)
class SecurityBlock:
    """A document-level `security { … }` block. Fingerprint-neutral."""

    statements: tuple[SecurityStatement, ...] = ()
    source: SourceLocation = SourceLocation.UNKNOWN


# ----- v4.4 lexicon surface (RG-P4, RS-9) -----


@dataclass(frozen=True, slots=True)
class LexiconBlock:
    """Inline `lexicon { … }` sugar — the canonical shorthand keys the walker
    extracts from the free-form object; desugared in semantics."""

    terms: tuple[str, ...] = ()
    patterns: tuple[str, ...] = ()
    examples: tuple[str, ...] = ()


@dataclass(frozen=True, slots=True)
class TermDef(Definition):
    """`def term <id> { for: <target>, forms: [...] }`."""

    target: Reference | None = None
    forms: tuple[str, ...] = ()
    match: str | None = None
    text: str | None = None
    #: Grammar 0.17 — `default: true|false`, a cn default sense (cn contracts §1.5).
    default: bool | None = None
    #: Grammar 0.17 — `sense_priority: <n>` (higher wins).
    sense_priority: float | None = None
    kind: ClassVar[str] = "term"


@dataclass(frozen=True, slots=True)
class PatternDef(Definition):
    """`def pattern <id> { for: <target>, match: "..." }`."""

    target: Reference | None = None
    forms: tuple[str, ...] = ()
    match: str | None = None
    text: str | None = None
    #: Grammar 0.17 — `default: true|false`, a cn default sense (cn contracts §1.5).
    default: bool | None = None
    #: Grammar 0.17 — `sense_priority: <n>` (higher wins).
    sense_priority: float | None = None
    kind: ClassVar[str] = "pattern"


@dataclass(frozen=True, slots=True)
class ExampleDef(Definition):
    """`def example <id> { for: <target>, text: "..." }`."""

    target: Reference | None = None
    forms: tuple[str, ...] = ()
    match: str | None = None
    text: str | None = None
    #: Grammar 0.17 — `default: true|false`, a cn default sense (cn contracts §1.5).
    default: bool | None = None
    #: Grammar 0.17 — `sense_priority: <n>` (higher wins).
    sense_priority: float | None = None
    kind: ClassVar[str] = "example"


@dataclass(frozen=True, slots=True)
class RelationDef(Definition):
    from_: PropertyValue | None = field(default=None, metadata={"surface": "from"})
    to: PropertyValue | None = None
    cardinality: ObjectValue | None = None
    join: tuple[PropertyValue, ...] = ()
    search: SearchHintsValue = field(default_factory=SearchHintsValue)
    binding: BindingProperty | None = None
    kind: ClassVar[str] = "relation"


@dataclass(frozen=True, slots=True)
class Er2DbEntityDef(Definition):
    """TTR keyword `er2db_entity` — kind discriminator `"er2db_entity"`.

    The TS type uses lowercase-`db` (`Er2dbEntityDef`); Kotlin/TS shape uses
    `Db` (`Er2DbEntityDef`). Python follows Kotlin (D5).
    """

    entity: Reference | None = None
    target: TargetValue | None = None
    where_filter: ObjectValue | None = None
    kind: ClassVar[str] = "er2db_entity"


@dataclass(frozen=True, slots=True)
class Er2DbAttributeDef(Definition):
    attribute: Reference | None = None
    target: TargetValue | None = None
    kind: ClassVar[str] = "er2db_attribute"


@dataclass(frozen=True, slots=True)
class Er2DbRelationDef(Definition):
    relation: Reference | None = None
    fk: Reference | None = None
    kind: ClassVar[str] = "er2db_relation"


@dataclass(frozen=True, slots=True)
class QueryDef(Definition):
    """`source_text` is the property's `PropertyValue` carrier — a `StringValue`,
    `TripleStringValue`, or `TaggedBlockValue` (embedded SQL/DSL block).

    `parameters` matches TS `ParameterDef[]` — named dicts, not `ObjectValue`s
    (Kotlin uses `ObjectValue`; the Python port goes with TS for the public
    surface to keep `from ttr_parser import QueryDef` ergonomics).
    """

    language: LanguageKind | None = None
    parameters: tuple[PropertyValue, ...] = ()
    source_text: PropertyValue | None = None
    search: SearchHintsValue = field(default_factory=SearchHintsValue)
    kind: ClassVar[str] = "query"


@dataclass(frozen=True, slots=True)
class RoleDef(Definition):
    label: LocalizedStringValue | None = None
    search: SearchHintsValue = field(default_factory=SearchHintsValue)
    #: Grammar 0.16 — an automation role's free-form properties (`resolve`, `delivery`,
    #: `members`), in authored order. Empty for a cnc role.
    entries: tuple[AutoEntry, ...] = ()
    kind: ClassVar[str] = "role"


@dataclass(frozen=True, slots=True)
class Er2CncRoleDef(Definition):
    """TTR keyword `er2cnc_role` — kind discriminator `"er2cnc_role"`.

    TS uses lowercase `cnc` (`Er2cncRoleDef`); Kotlin/TS shape uses `Cnc`
    (`Er2CncRoleDef`). Python follows Kotlin (D5).
    """

    entity: Reference | None = None
    role: Reference | None = None
    kind: ClassVar[str] = "er2cnc_role"


@dataclass(frozen=True, slots=True)
class DrillMapDef(Definition):
    """v2.2 — `def drill_map <id> { from, to, args, display?, override? }`.

    `args` is `Mapping[str, str]` (Kotlin shape); the walker flattens
    `StringValue`/`TripleStringValue` to plain strings, and column-vs-literal
    validation is deferred to the semantics layer.
    """

    from_: Reference | None = field(default=None, metadata={"surface": "from"})
    to: Reference | None = None
    args: Mapping[str, str] = field(default_factory=lambda: MappingProxyType({}))
    display: LocalizedStringValue | None = None
    override_auto: bool = False
    kind: ClassVar[str] = "drill_map"


@dataclass(frozen=True, slots=True)
class SchemaDef(Definition):
    """Grammar 0.14 — `def schema <id> { description?, tags?, columns: [...] }`.

    A named row schema: an ordered column list, each column written exactly like a
    table column (`type`, `optional`, `description`, …). Tier-neutral (maps to `db`
    by default, the `area` precedent); consumed by TTR-P, which holds
    `display(<name>)` rows to it. Not a loadable object.
    """

    columns: tuple[ColumnDef, ...] = ()
    kind: ClassVar[str] = "schema"


@dataclass(frozen=True, slots=True)
class AreaDef(Definition):
    """v3.0 — `def area <id> { description?, tags?, packages: [...], entities: [...] }`.

    Subject area (replaces the v2.3 `.ttrd` domain block). A normal definition
    that lives in ordinary model files and registers a resolvable symbol.

    `packages` are recursive members (each pulls the package and all
    descendants); `entities` are individual entity qnames loaded in addition.
    Both may be empty. `package_sources` / `entity_sources` are per-member
    source locations parallel to `packages` / `entities` (editor-only,
    additive — not part of the conformance dump).
    """

    packages: tuple[str, ...] = ()
    entities: tuple[str, ...] = ()
    package_sources: tuple[SourceLocation, ...] = ()
    entity_sources: tuple[SourceLocation, ...] = ()
    kind: ClassVar[str] = "area"


# ============================================================================
# v4.1 — world model (ttr-metadata M0; D-d-α). Manifest entries are transported
# opaque (T6 β data — MD5, never interpreted here).
# ============================================================================


@dataclass(frozen=True, slots=True)
class WorldSchemaField:
    name: str
    type: str  # raw dataType text
    source: SourceLocation


@dataclass(frozen=True, slots=True)
class WorldSchemaDef:
    """`def schema <id> { field: type, … }` nested in a storage (D-c world home)."""

    name: str
    source: SourceLocation
    fields: tuple[WorldSchemaField, ...] = ()
    kind: ClassVar[str] = "worldSchema"


@dataclass(frozen=True, slots=True)
class EngineDef:
    """`def engine <id>` inside a world (D-d-α)."""

    name: str
    source: SourceLocation
    description: str | None = None
    description_localized: LocalizedStringValue | None = None
    tags: tuple[str, ...] = ()
    type: str | None = None
    version: str | None = None
    extends: str | None = None
    manifest: Mapping[str, PropertyValue] = field(default_factory=lambda: MappingProxyType({}))
    kind: ClassVar[str] = "engine"


@dataclass(frozen=True, slots=True)
class ExecutorDef:
    """`def executor <id>` inside a world (same body as engine)."""

    name: str
    source: SourceLocation
    description: str | None = None
    description_localized: LocalizedStringValue | None = None
    tags: tuple[str, ...] = ()
    type: str | None = None
    version: str | None = None
    extends: str | None = None
    manifest: Mapping[str, PropertyValue] = field(default_factory=lambda: MappingProxyType({}))
    kind: ClassVar[str] = "executor"


@dataclass(frozen=True, slots=True)
class StorageDef:
    """`def storage <id>` inside a world (D-d-α/D-d-i/D-f)."""

    name: str
    source: SourceLocation
    description: str | None = None
    description_localized: LocalizedStringValue | None = None
    tags: tuple[str, ...] = ()
    type: str | None = None
    extends: str | None = None
    via: str | None = None
    hosts: tuple[str, ...] = ()
    staging: bool = False
    schemas: tuple[WorldSchemaDef, ...] = ()
    manifest: Mapping[str, PropertyValue] = field(default_factory=lambda: MappingProxyType({}))
    kind: ClassVar[str] = "storage"


@dataclass(frozen=True, slots=True)
class WorldDef(Definition):
    """v4.1 — `def world <id> { … }`, a deployment world (D-d-α)."""

    extends: str | None = None
    engines: tuple[EngineDef, ...] = ()
    executors: tuple[ExecutorDef, ...] = ()
    storages: tuple[StorageDef, ...] = ()
    #: Grammar 0.16 — the automation world members (D-90, D-91, D-107, D-39).
    identities: tuple[WorldAutoMember, ...] = ()
    grants: tuple[WorldAutoMember, ...] = ()
    deployments: tuple[WorldAutoMember, ...] = ()
    processes: tuple[ProcessDef, ...] = ()
    kind: ClassVar[str] = "world"


# ============================================================================
# Grammar 0.16 — the automation model (`model automation`; AU-core-P0 S0.2). Six
# def kinds share ONE permissive body (AutoBody): typed common fields + an ordered
# free-form entry list; the automation world members reuse it. Values are carried
# structurally — their meaning is validated in semantics, never here.
# ============================================================================


@dataclass(frozen=True, slots=True)
class AutoValue:
    """Common base for a free-form automation value (`autoValue`)."""

    source: SourceLocation
    kind: ClassVar[str] = "autoValue"


@dataclass(frozen=True, slots=True)
class AutoStr(AutoValue):
    raw: str = ""
    #: Written as a triple-quoted string (dedented).
    triple: bool = False
    kind: ClassVar[str] = "string"


@dataclass(frozen=True, slots=True)
class AutoNum(AutoValue):
    raw: float = 0.0
    #: The number as written (parameter values feed the D-13 values fingerprint).
    text: str = ""
    kind: ClassVar[str] = "number"


@dataclass(frozen=True, slots=True)
class AutoBool(AutoValue):
    raw: bool = False
    kind: ClassVar[str] = "bool"


@dataclass(frozen=True, slots=True)
class AutoNull(AutoValue):
    kind: ClassVar[str] = "null"


@dataclass(frozen=True, slots=True)
class AutoId(AutoValue):
    """A bare or dotted id (`none`, `role.ops`, `schema.line_received`)."""

    ref: Reference | None = None
    parts: tuple[str, ...] = ()
    kind: ClassVar[str] = "id"


@dataclass(frozen=True, slots=True)
class AutoDuration(AutoValue):
    """`2 working_days` — the unit is validated in semantics."""

    amount: float = 0.0
    unit: str = ""
    kind: ClassVar[str] = "duration"


@dataclass(frozen=True, slots=True)
class AutoTagged(AutoValue):
    """`schedule "06:00 Europe/Prague"` · `keycloak_group "hartland-sales"`."""

    tag: str = ""
    value: str = ""
    kind: ClassVar[str] = "tagged"


@dataclass(frozen=True, slots=True)
class AutoCall(AutoValue):
    """`hold { order: order_number }` — a call-shaped value."""

    name: str = ""
    entries: tuple[AutoEntry, ...] = ()
    kind: ClassVar[str] = "call"


@dataclass(frozen=True, slots=True)
class AutoStar(AutoValue):
    """`*` — any (`program: *`)."""

    kind: ClassVar[str] = "star"


@dataclass(frozen=True, slots=True)
class AutoObj(AutoValue):
    entries: tuple[AutoEntry, ...] = ()
    kind: ClassVar[str] = "object"


@dataclass(frozen=True, slots=True)
class AutoList(AutoValue):
    items: tuple[AutoValue, ...] = ()
    kind: ClassVar[str] = "list"


@dataclass(frozen=True, slots=True)
class AutoEntry:
    key: str
    value: AutoValue
    source: SourceLocation


@dataclass(frozen=True, slots=True)
class AutoTransition:
    """`transition: order.state -> released { from: [placed, held] }` / `-> {to}`."""

    attribute: str
    to: str | None
    to_column: str | None
    from_states: tuple[str, ...]
    source: SourceLocation


@dataclass(frozen=True, slots=True)
class AutoSet:
    """`set: order.held_for = department`."""

    attribute: str
    column: str
    source: SourceLocation


@dataclass(frozen=True, slots=True)
class AutoBody:
    """The shared automation body: typed common fields + the ordered free-form entries."""

    description: str | None = None
    description_localized: LocalizedStringValue | None = None
    tags: tuple[str, ...] = ()
    label: LocalizedStringValue | None = None
    type: DataType | None = None
    columns: tuple[ColumnDef, ...] = ()
    target: TargetValue | None = None
    transition: AutoTransition | None = None
    set: AutoSet | None = None
    entries: tuple[AutoEntry, ...] = ()


@dataclass(frozen=True, slots=True)
class AutomationDef(Definition):
    """Grammar 0.16 — the six automation kinds share one body; `kind` is the def keyword."""

    body: AutoBody = field(default_factory=AutoBody)
    kind: ClassVar[str] = "automation"


@dataclass(frozen=True, slots=True)
class AutomationParameterDef(AutomationDef):
    kind: ClassVar[str] = "parameter"


@dataclass(frozen=True, slots=True)
class AutomationActionDef(AutomationDef):
    kind: ClassVar[str] = "action"


@dataclass(frozen=True, slots=True)
class AutomationServiceDef(AutomationDef):
    kind: ClassVar[str] = "service"


@dataclass(frozen=True, slots=True)
class AutomationApprovalDef(AutomationDef):
    kind: ClassVar[str] = "approval"


@dataclass(frozen=True, slots=True)
class AutomationProgramDef(AutomationDef):
    kind: ClassVar[str] = "program"


@dataclass(frozen=True, slots=True)
class AutomationRecipeDef(AutomationDef):
    kind: ClassVar[str] = "recipe"


#: The automation def keyword → its class.
AUTOMATION_KINDS: Mapping[str, type[AutomationDef]] = MappingProxyType(
    {
        "parameter": AutomationParameterDef,
        "action": AutomationActionDef,
        "service": AutomationServiceDef,
        "approval": AutomationApprovalDef,
        "program": AutomationProgramDef,
        "recipe": AutomationRecipeDef,
    }
)


@dataclass(frozen=True, slots=True)
class WorldAutoMember:
    """Grammar 0.16 — `def identity <id>`, `def grant { … }` (unnamed), `def deployment <id>`,
    or a process's `def step <id>`. `kind` is the def keyword (an instance field here)."""

    member_kind: str
    name: str | None
    source: SourceLocation
    body: AutoBody = field(default_factory=AutoBody)


@dataclass(frozen=True, slots=True)
class ProcessDef:
    """Grammar 0.16 — world `def process <id> { instance: …, steps: [ def step … ] }`."""

    name: str
    source: SourceLocation
    description: str | None = None
    description_localized: LocalizedStringValue | None = None
    tags: tuple[str, ...] = ()
    steps: tuple[WorldAutoMember, ...] = ()
    entries: tuple[AutoEntry, ...] = ()
    kind: ClassVar[str] = "process"


# ============================================================================
# Grammar 0.17 — the conceptual model (`model cn`; CN-P1, cn contracts §1). The
# concept defs, a cn `def role` (⚑CN-P6), the ten cn bindings and the `concept { }`
# sugar read one body: every property, in authored order, to one `ConceptProperty`
# (its key as written, its span) — typed where the grammar types it, a free-form
# `ConceptEntry` otherwise. A repeated key is a `ttr/duplicate-property` error; the
# first is read. Meaning and legality per stereotype are the semantics layer's.
# Mirrors the TS `ast.ts` and the Kotlin `Definition.kt`.
# ============================================================================


@dataclass(frozen=True, slots=True)
class EmbeddedBlock:
    """An expression block (⚑CN-P5) — `definition:`, `members:`, a binding's `filter:`.

    `text` is exactly what the expression parser reads; `line_offsets` holds the file
    offset where every line of it starts once the dedent removed its indent, so a
    diagnostic about the expression can point into the `.ttrm`. `form` is how it was
    written (`string` · `tripleString` · `tagged`); `language` is always `pl`.
    """

    form: str
    text: str
    text_source: SourceLocation
    line_offsets: tuple[int, ...]
    indent_width: int
    #: The whole literal, quotes included.
    source: SourceLocation
    language: str = "pl"
    kind: ClassVar[str] = "embeddedBlock"

    def location(self, line: int, column: int, length: int = 1) -> SourceLocation:
        """The file span of a position inside `text` — `line` 0-based within the block,
        `column` 0-based within that line. Mirrors the TS `embeddedBlockLocation`."""
        ln = max(0, min(line, len(self.line_offsets) - 1))
        start_column = (self.text_source.column if ln == 0 else self.indent_width) + column
        offset_start = self.line_offsets[ln] + column
        return SourceLocation(
            file=self.source.file,
            line=self.text_source.line + ln,
            column=start_column,
            end_line=self.text_source.line + ln,
            end_column=start_column + length,
            offset_start=offset_start,
            offset_end=offset_start + length,
        )


@dataclass(frozen=True, slots=True)
class ConceptParticipant:
    """One `involves:` participant: a concept ref, optionally labelled (`Warehouse as from`)."""

    ref: Reference
    source: SourceLocation
    label: str | None = None


@dataclass(frozen=True, slots=True)
class ConceptProperty:
    """One property of a cn body. `key` is the key as written; `source` spans the property."""

    key: str
    source: SourceLocation
    kind: ClassVar[str] = "conceptProperty"


@dataclass(frozen=True, slots=True, kw_only=True)
class ConceptDescription(ConceptProperty):
    """`description:` as a plain or triple-quoted string (`value`) or `{ en: …, cs: … }` (`localized`)."""

    value: StringValue | TripleStringValue | None = None
    localized: LocalizedStringValue | None = None
    kind: ClassVar[str] = "description"


@dataclass(frozen=True, slots=True, kw_only=True)
class ConceptLabel(ConceptProperty):
    value: LocalizedStringValue
    kind: ClassVar[str] = "label"


@dataclass(frozen=True, slots=True, kw_only=True)
class ConceptTags(ConceptProperty):
    value: tuple[str, ...]
    kind: ClassVar[str] = "tags"


@dataclass(frozen=True, slots=True, kw_only=True)
class ConceptRef(ConceptProperty):
    """A stereotype ref — `key` is `kind | role | quality | event | phase | concept`."""

    ref: Reference
    kind: ClassVar[str] = "ref"


@dataclass(frozen=True, slots=True, kw_only=True)
class ConceptGrounds(ConceptProperty):
    ref: Reference
    kind: ClassVar[str] = "grounds"


@dataclass(frozen=True, slots=True, kw_only=True)
class ConceptValueSpace(ConceptProperty):
    """`value_space: ProductCategory`."""

    ref: Reference
    kind: ClassVar[str] = "valueSpace"


@dataclass(frozen=True, slots=True, kw_only=True)
class ConceptInvolves(ConceptProperty):
    participants: tuple[ConceptParticipant, ...]
    kind: ClassVar[str] = "involves"


@dataclass(frozen=True, slots=True, kw_only=True)
class ConceptPeriod(ConceptProperty):
    value: ObjectValue
    kind: ClassVar[str] = "period"


@dataclass(frozen=True, slots=True, kw_only=True)
class ConceptExtent(ConceptProperty):
    value: str
    kind: ClassVar[str] = "extent"


@dataclass(frozen=True, slots=True, kw_only=True)
class ConceptBasis(ConceptProperty):
    value: str
    kind: ClassVar[str] = "basis"


@dataclass(frozen=True, slots=True, kw_only=True)
class ConceptDefinition(ConceptProperty):
    """`definition:` — an expression block, parsed by the TTR-P expression grammar in CN-P2."""

    block: EmbeddedBlock
    kind: ClassVar[str] = "definition"


@dataclass(frozen=True, slots=True, kw_only=True)
class ConceptMembers(ConceptProperty):
    block: EmbeddedBlock
    kind: ClassVar[str] = "members"


@dataclass(frozen=True, slots=True, kw_only=True)
class ConceptFilter(ConceptProperty):
    """A binding's `filter:` — the expression block of a valued state's series."""

    block: EmbeddedBlock
    kind: ClassVar[str] = "filter"


@dataclass(frozen=True, slots=True, kw_only=True)
class ConceptLexicon(ConceptProperty):
    block: LexiconBlock
    kind: ClassVar[str] = "lexicon"


@dataclass(frozen=True, slots=True, kw_only=True)
class ConceptTarget(ConceptProperty):
    """A binding's `target:` (an object or a bare reference)."""

    value: TargetValue
    kind: ClassVar[str] = "target"


@dataclass(frozen=True, slots=True, kw_only=True)
class ConceptEntry(ConceptProperty):
    """A free-form entry (`of`, `is_a`, `qualities`, `relations`, `hierarchy`, `via`, …)."""

    value: PropertyValue
    kind: ClassVar[str] = "entry"


@dataclass(frozen=True, slots=True)
class ConceptBody:
    """One cn body. `properties` keeps every property in authored order, repeats included;
    the convenience fields hold the FIRST `description` / `label` / `tags` (the one read)."""

    properties: tuple[ConceptProperty, ...] = ()
    description: str | None = None
    description_localized: LocalizedStringValue | None = None
    tags: tuple[str, ...] = ()
    label: LocalizedStringValue | None = None

    def get(self, key: str) -> ConceptProperty | None:
        """The first property written under `key` (the one the walker reads)."""
        return next((p for p in self.properties if p.key == key), None)

    def ref(self, key: str) -> Reference | None:
        """The stereotype ref under `key` (`kind`, `role`, `quality`, `event`, `phase`, `concept`)."""
        p = self.get(key)
        return p.ref if isinstance(p, ConceptRef) else None

    def entry(self, key: str) -> PropertyValue | None:
        """The free-form value under `key` (`of`, `is_a`, `participants`, …)."""
        p = self.get(key)
        return p.value if isinstance(p, ConceptEntry) else None


@dataclass(frozen=True, slots=True)
class ConceptSugar:
    """`concept { … }` inside an er or md def: that def's own concept (desugared in CN-P2)."""

    body: ConceptBody
    #: The whole `concept { … }` property.
    source: SourceLocation


#: The concept stereotypes, in grammar order (`role` is the shared `def role`).
CONCEPT_STEREOTYPES: tuple[str, ...] = ("kind", "role", "event", "phase", "quality", "relation_type")

#: The ten cn binding keywords, in grammar order.
CONCEPT_BINDING_KINDS: tuple[str, ...] = (
    "cn2md_kind", "cn2md_role", "cn2md_quality", "cn2md_event",
    "cn2er_kind", "cn2er_role", "cn2er_event", "cn2er_quality", "cn2er_phase",
    "cn2db_concept",
)


@dataclass(frozen=True, slots=True)
class ConceptDef(Definition):
    """`def kind | role | event | phase | quality | relation_type <id> { … }` — one class for
    every stereotype; a `def role` is a concept only in a `model cn` file."""

    stereotype: str = "kind"
    body: ConceptBody = field(default_factory=ConceptBody)
    kind: ClassVar[str] = "concept"


@dataclass(frozen=True, slots=True)
class ConceptBindingDef(Definition):
    """`def cn2md_* | cn2er_* | cn2db_concept <id> { … }`; `binding_kind` is the def keyword."""

    binding_kind: str = "cn2md_kind"
    body: ConceptBody = field(default_factory=ConceptBody)
    kind: ClassVar[str] = "concept_binding"


# The md hosts of the `concept { }` sugar. The Python port carries no md model — the TS and
# Kotlin md fields (type, restrict, grain, measures, …) are not ported, and the conformance
# dump compares none of them; these four carry identity and the 0.17 `concept` sugar.


@dataclass(frozen=True, slots=True)
class MdDomainDef(Definition):
    concept: ConceptSugar | None = None
    kind: ClassVar[str] = "md_domain"


@dataclass(frozen=True, slots=True)
class DimensionDef(Definition):
    concept: ConceptSugar | None = None
    kind: ClassVar[str] = "dimension"


@dataclass(frozen=True, slots=True)
class MeasureDef(Definition):
    concept: ConceptSugar | None = None
    kind: ClassVar[str] = "measure"


@dataclass(frozen=True, slots=True)
class CubeletDef(Definition):
    concept: ConceptSugar | None = None
    kind: ClassVar[str] = "cubelet"


# ============================================================================
# Top-level ParseResult
# ============================================================================


@dataclass(frozen=True, slots=True)
class ParseResult:
    """Outcome of `parse_string` / `parse_file` / `parse_directory`."""

    definitions: tuple[Definition, ...]
    model_directive: ModelDirective | None
    errors: tuple[ParseError, ...]
    source_file: str
    warnings: tuple[ParseWarning, ...] = ()
    package_name: str | None = None
    imports: tuple[ImportStatement, ...] = ()
    security_blocks: tuple[SecurityBlock, ...] = ()

    @property
    def ok(self) -> bool:
        return not self.errors