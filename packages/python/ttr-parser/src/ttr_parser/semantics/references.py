# SPDX-License-Identifier: Apache-2.0
"""Cross-reference collection (← Kotlin `References.kt` / TS `references.ts`).

Walks every definition (and its nested children) and collects every
cross-reference paired with the top-level def it belongs to, plus the helpers
the validator needs: `enclosing_qname_of` (step-1 lexical scope), `kind_of` (the
camelCase kind), and `package_of_import`. Reference-typed slots (nameAttribute,
entity, role, …) and IdValue-based slots (relation/fk from/to) both report the
reference's own source span.
"""

from __future__ import annotations

from dataclasses import dataclass

from ..model import (
    Definition,
    EntityDef,
    Er2CncRoleDef,
    Er2DbAttributeDef,
    Er2DbEntityDef,
    Er2DbRelationDef,
    ExampleDef,
    FkDef,
    IdValue,
    ImportStatement,
    ListValue,
    ObjectValue,
    PatternDef,
    ProcedureDef,
    PropertyValue,
    Reference,
    RelationDef,
    SourceLocation,
    TableDef,
    TermDef,
    ViewDef,
)
from .default_schema import build_canonical_key, kind_segment


@dataclass(frozen=True, slots=True)
class CollectedRef:
    path: str
    parts: tuple[str, ...]
    source: SourceLocation
    owner_def: Definition


def nested_defs(definition: Definition) -> tuple[Definition, ...]:
    """Per-def children: attributes / columns (+ indices + constraints) / result columns."""
    if isinstance(definition, EntityDef):
        return definition.attributes
    if isinstance(definition, TableDef):
        return (*definition.columns, *definition.indices, *definition.constraints)
    if isinstance(definition, ViewDef):
        return definition.columns
    if isinstance(definition, ProcedureDef):
        return definition.result_columns
    return ()


def collect_all_references(definitions: tuple[Definition, ...]) -> list[CollectedRef]:
    out: list[CollectedRef] = []
    for definition in definitions:
        _collect_into(definition, definition, out)
        for child in nested_defs(definition):
            _collect_into(child, definition, out)
    return out


def _ref_of(ref: Reference, owner: Definition) -> CollectedRef:
    return CollectedRef(ref.path, ref.parts, ref.source, owner)


def _collect_into(
    definition: Definition, owner: Definition, out: list[CollectedRef]
) -> None:
    if isinstance(definition, EntityDef):
        if definition.name_attribute is not None:
            out.append(_ref_of(definition.name_attribute, owner))
        if definition.code_attribute is not None:
            out.append(_ref_of(definition.code_attribute, owner))
    elif isinstance(definition, Er2DbEntityDef):
        if definition.entity is not None:
            out.append(_ref_of(definition.entity, owner))
    elif isinstance(definition, Er2DbAttributeDef):
        if definition.attribute is not None:
            out.append(_ref_of(definition.attribute, owner))
    elif isinstance(definition, Er2DbRelationDef):
        if definition.relation is not None:
            out.append(_ref_of(definition.relation, owner))
        if definition.fk is not None:
            out.append(_ref_of(definition.fk, owner))
    elif isinstance(definition, Er2CncRoleDef):
        if definition.entity is not None:
            out.append(_ref_of(definition.entity, owner))
        if definition.role is not None:
            out.append(_ref_of(definition.role, owner))
    elif isinstance(definition, RelationDef):
        _push_id_value(definition.from_, owner, out)
        _push_id_value(definition.to, owner, out)
    elif isinstance(definition, FkDef):
        _push_id_value(definition.from_, owner, out)
        _push_id_value(definition.to, owner, out)
    # v4.4 lexicon entries — the `for:` target ref (er/db/md) resolves through the
    # standard path, giving goto-def + unresolved-reference for free (TS parity).
    elif isinstance(definition, (TermDef, PatternDef, ExampleDef)):
        if definition.target is not None:
            out.append(_ref_of(definition.target, owner))


def _push_id_value(
    value: PropertyValue | None, owner: Definition, out: list[CollectedRef]
) -> None:
    if isinstance(value, IdValue):
        if value.ref is not None:
            out.append(CollectedRef(value.ref.path, value.parts, value.source, owner))
    elif isinstance(value, ListValue):
        for item in value.items:
            _push_id_value(item, owner, out)
    elif isinstance(value, ObjectValue):
        for entry in value.entries.values():
            _push_id_value(entry, owner, out)


_ENCLOSING_KINDS = frozenset(
    {
        "entity",
        "table",
        "view",
        "procedure",
        "relation",
        "query",
        "role",
        "er2dbEntity",
        "er2dbAttribute",
        "er2dbRelation",
        "er2cncRole",
    }
)


def kind_of(definition: Definition) -> str:
    """The camelCase TS kind for a Python `Definition` (e.g. `er2db_entity` → `er2dbEntity`)."""
    return kind_segment(definition.kind)


def enclosing_qname_of(
    definition: Definition,
    schema_code: str,
    namespace: str,
    package_name: str,
) -> str | None:
    """Qname of the def that lexically encloses a reference (step-1 lexical)."""
    kind = kind_of(definition)
    if kind not in _ENCLOSING_KINDS:
        return None
    # v4.0 uniform key: model/schema/kind from the def's kind; `namespace` is the
    # file `schema` id (db only). `schema_code` (file model) only moves a query
    # declared under `model er` (a logical query, LQ L-1).
    return build_canonical_key(
        package_name, namespace, definition.kind, [definition.name], schema_code
    )


def package_of_import(imp: ImportStatement) -> str:
    if imp.wildcard:
        return imp.target
    parts = imp.target.split(".")
    return ".".join(parts[:-1]) if len(parts) >= 2 else ""
