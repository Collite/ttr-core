# SPDX-License-Identifier: Apache-2.0
"""ttr_parser — Python parser, walker and reference resolver for the TTR modeling language.

Public API is re-exported here from `model`, `diagnostics`, `dedent`,
`loader`. The `loader` module is the entry point — `parse_string` /
`parse_file` / `parse_directory` boot ANTLR, run the walker, and surface a
typed `ParseResult`. The model types (`Definition` subtypes,
`PropertyValue` variants, `SourceLocation`, …) are pure data and live in
`model`.
"""

from __future__ import annotations

from .dedent import DedentResult, dedent, dedent_with_indent
from .diagnostics import DiagnosticCode, DiagnosticSeverity
from .loader import parse_directory, parse_file, parse_string
from .model import (
    AreaDef,
    AttributeDef,
    BindingColumnBareId,
    BindingColumnEntry,
    BindingColumnObject,
    BindingColumnValue,
    BindingProperty,
    BindingPropertyBareId,
    BindingPropertyBlock,
    BoolValue,
    ColumnDef,
    ConstraintDef,
    DataType,
    Definition,
    DrillMapDef,
    EngineDef,
    EntityDef,
    Er2CncRoleDef,
    Er2DbAttributeDef,
    Er2DbEntityDef,
    Er2DbRelationDef,
    ExecutorDef,
    FkDef,
    FunctionCall,
    IdValue,
    ImportStatement,
    IndexDef,
    LanguageKind,
    ListValue,
    LocalizedStringListValue,
    LocalizedStringValue,
    MatchMethodValue,
    ModelDirective,
    NullValue,
    NumberValue,
    ObjectValue,
    PackageDeclaration,
    ParseError,
    ParseResult,
    ParseWarning,
    ProcedureDef,
    ProjectDef,
    PropertyValue,
    QueryDef,
    Reference,
    RelationDef,
    RoleDef,
    SchemaDef,
    SearchHintsValue,
    SourceLocation,
    StorageDef,
    StringValue,
    TableDef,
    TaggedBlockValue,
    TargetObjectValue,
    TargetReferenceValue,
    TargetValue,
    TripleStringValue,
    ViewDef,
    WorldDef,
    WorldSchemaDef,
    WorldSchemaField,
)

__version__ = "0.0.0"

# Common semantics entry points, re-exported for convenience (the full surface
# lives in `ttr_parser.semantics`). Imported after the model/loader names above
# so the subpackage's `from ..loader import …` resolves cleanly.
from .semantics import Project, load_project  # noqa: E402


def extract_reference(value: PropertyValue) -> Reference | None:
    """Walker-style helper: extract a `Reference` from an `IdValue`; else `None`.

    Mirrors `walker.ts` `extractReference` — only `IdValue` carries a reference;
    every other `PropertyValue` variant (including lists/objects containing ids)
    returns `None`. Defined here so consumers can import it without the walker.
    """
    if isinstance(value, IdValue) and value.ref is not None:
        return value.ref
    return None


__all__ = [
    "__version__",
    "extract_reference",
    # semantics convenience entry points (full surface in ttr_parser.semantics)
    "Project",
    "load_project",
    # diagnostics
    "DiagnosticCode",
    "DiagnosticSeverity",
    # loader entry points (P2.3)
    "parse_string",
    "parse_file",
    "parse_directory",
    # dedent helper (P2.3 — public for consumers that need it)
    "dedent",
    "dedent_with_indent",
    "DedentResult",
    # model — Definition hierarchy
    "Definition",
    "ProjectDef",
    "TableDef",
    "ViewDef",
    "ColumnDef",
    "IndexDef",
    "ConstraintDef",
    "FkDef",
    "ProcedureDef",
    "EntityDef",
    "AttributeDef",
    "RelationDef",
    "Er2DbEntityDef",
    "Er2DbAttributeDef",
    "Er2DbRelationDef",
    "QueryDef",
    "RoleDef",
    "Er2CncRoleDef",
    "DrillMapDef",
    "AreaDef",
    "SchemaDef",
    "WorldDef",
    "EngineDef",
    "ExecutorDef",
    "StorageDef",
    "WorldSchemaDef",
    "WorldSchemaField",
    # model — PropertyValue hierarchy
    "PropertyValue",
    "StringValue",
    "TripleStringValue",
    "NumberValue",
    "BoolValue",
    "NullValue",
    "IdValue",
    "ListValue",
    "ObjectValue",
    "FunctionCall",
    "TaggedBlockValue",
    "LanguageKind",
    # model — supporting types
    "SourceLocation",
    "Reference",
    "DataType",
    "MatchMethodValue",
    "SearchHintsValue",
    "LocalizedStringValue",
    "LocalizedStringListValue",
    "ModelDirective",
    "ImportStatement",
    "PackageDeclaration",
    "ParseError",
    "ParseWarning",
    "ParseResult",
    # model — binding types (v3.0: `mapping:` → `binding:`)
    "BindingProperty",
    "BindingPropertyBareId",
    "BindingPropertyBlock",
    "TargetValue",
    "TargetObjectValue",
    "TargetReferenceValue",
    "BindingColumnEntry",
    "BindingColumnValue",
    "BindingColumnBareId",
    "BindingColumnObject",
]
