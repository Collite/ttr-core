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
    AttributeDef,
    ConceptBindingDef,
    ConceptBody,
    ConceptDef,
    ConceptEntry,
    ConceptFrom,
    ConceptGrounds,
    ConceptInvolves,
    ConceptPer,
    ConceptPeriod,
    ConceptRef,
    ConceptTarget,
    ConceptUntil,
    ConceptValueSpace,
    CubeletDef,
    Definition,
    DimensionDef,
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
    Md2erDimensionDef,
    MeasureDef,
    ObjectValue,
    PatternDef,
    ProcedureDef,
    PropertyValue,
    Reference,
    RelationDef,
    SourceLocation,
    TableDef,
    TargetObjectValue,
    TargetReferenceValue,
    TermDef,
    ViewDef,
)
from .cn.desugar import concept_sugar_of, sugar_stereotype_key
from .default_schema import build_canonical_key, kind_segment
from .resolver import MdReferenceRole


@dataclass(frozen=True, slots=True)
class CollectedRef:
    """One collected reference. 0.17: `cn_context` marks a reference written in a cn slot (cn contracts
    §1.1-§1.4) - it resolves among concepts only; `soft` marks a bearer-local name (a concept's
    `qualities:` item or `period:` value) - one that names no concept is an implicit quality, never an
    unresolved reference. Mirrors the TS `CnSlotReference`."""

    path: str
    parts: tuple[str, ...]
    source: SourceLocation
    owner_def: Definition
    cn_context: bool = False
    soft: bool = False
    md_context: MdReferenceRole | None = None


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
    if isinstance(definition, DimensionDef):
        # Inline MD attributes carry their own `domain:` cross-references (TS parity).
        return definition.attributes
    return ()


def collect_all_references(definitions: tuple[Definition, ...]) -> list[CollectedRef]:
    out: list[CollectedRef] = []
    for definition in definitions:
        _collect_into(definition, definition, out)
        for child in nested_defs(definition):
            _collect_into(child, definition, out)
    return out


def _ref_of(ref: Reference, owner: Definition, md_context: MdReferenceRole | None = None) -> CollectedRef:
    return CollectedRef(ref.path, ref.parts, ref.source, owner, md_context=md_context)


def _collect_into(
    definition: Definition, owner: Definition, out: list[CollectedRef]
) -> None:
    if isinstance(definition, EntityDef):
        if definition.name_attribute is not None:
            out.append(_ref_of(definition.name_attribute, owner))
        if definition.code_attribute is not None:
            out.append(_ref_of(definition.code_attribute, owner))
    elif isinstance(definition, Md2erDimensionDef):
        for ref in (definition.dimension_ref, definition.entity):
            if ref is not None:
                out.append(_ref_of(ref, owner))
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
    # v3.1 MD - the cross-references the TS walker collects as `crossRefs` (domain / hierarchy / grain /
    # measure), read off the identity-level fields the Python md hosts carry.
    elif isinstance(definition, AttributeDef):
        if definition.domain_ref is not None:
            out.append(_ref_of(definition.domain_ref, owner, "domain"))
    elif isinstance(definition, DimensionDef):
        out.extend(_ref_of(r, owner, "hierarchy") for r in definition.hierarchies)
    elif isinstance(definition, MeasureDef):
        if definition.domain_ref is not None:
            out.append(_ref_of(definition.domain_ref, owner, "domain"))
    elif isinstance(definition, CubeletDef):
        out.extend(_ref_of(r, owner, "grain") for r in definition.grain)
        out.extend(_ref_of(r, owner, "measure") for r in definition.measure_refs)
    # 0.17 - the cn model: a concept def's and a binding's slots.
    elif isinstance(definition, ConceptDef):
        _collect_concept_body(definition.body, "def", owner, out)
    elif isinstance(definition, ConceptBindingDef):
        _collect_concept_body(definition.body, "binding", owner, out)
    # 0.17 - the `concept { }` sugar on an md / er host.
    sugar = concept_sugar_of(definition)
    if sugar is not None:
        _collect_concept_body(sugar.body, "sugar", owner, out)


#: `period:` names its instants through `start` / `end` / `at`; `end_open` and `precision` are enums.
_PERIOD_INSTANTS = frozenset({"start", "end", "at"})

#: A binding's `period:` may also name a stored upper bound — `start_hi` / `at_hi` (⚑CN-P10, ruled 2026-10-22).
_BINDING_PERIOD_INSTANTS = _PERIOD_INSTANTS | {"start_hi", "at_hi"}


def ids_of(value: PropertyValue | None) -> list[IdValue]:
    """The id values of a value that is one id or a list of them."""
    if isinstance(value, IdValue):
        return [value]
    if isinstance(value, ListValue):
        return [i for item in value.items for i in ids_of(item)]
    return []


def entry_of(value: PropertyValue | None, key: str) -> PropertyValue | None:
    """The value under `key` of an object value, or None."""
    return value.entries.get(key) if isinstance(value, ObjectValue) else None


def id_path(v: IdValue) -> str:
    return v.ref.path if v.ref is not None else ".".join(v.parts)


def _collect_concept_body(body: ConceptBody, mode: str, owner: Definition, out: list[CollectedRef]) -> None:
    """The references of one cn body, in authored order. `mode` says whose body it is: a concept def
    (`period:` names the bearer's qualities), a binding (`period:` names er attributes, `participants:`
    keys name the event's labels) or the sugar (its stereotype slot declares - no reference). Groundings
    and binding targets resolve the generic way; `hierarchy:`'s `ancestor` / `descendant` are read
    relative to its `closure` entity. Mirrors TS `collectConceptBodyRefs`."""

    def cn(path: str, parts: tuple[str, ...], source: SourceLocation, soft: bool = False) -> None:
        out.append(CollectedRef(path, parts, source, owner, cn_context=True, soft=soft))

    def plain(v: IdValue) -> None:
        out.append(CollectedRef(id_path(v), v.parts, v.source, owner))

    declaring = sugar_stereotype_key(body) if mode == "sugar" else None
    event_ref = (
        next((p for p in body.properties if isinstance(p, ConceptRef) and p.key == "event"), None)
        if mode == "binding"
        else None
    )
    for p in body.properties:
        if isinstance(p, ConceptRef):
            if p.key != declaring:
                cn(p.ref.path, p.ref.parts, p.ref.source)
        elif isinstance(p, ConceptFrom):
            if len(p.ref.parts) == 1:
                cn(p.ref.path, p.ref.parts, p.ref.source)
        elif isinstance(p, (ConceptUntil, ConceptPer)):
            cn(p.ref.path, p.ref.parts, p.ref.source)
        elif isinstance(p, ConceptGrounds):
            out.append(_ref_of(p.ref, owner))
        elif isinstance(p, ConceptValueSpace):
            cn(p.ref.path, p.ref.parts, p.ref.source)
        elif isinstance(p, ConceptInvolves):
            for participant in p.participants:
                cn(participant.ref.path, participant.ref.parts, participant.ref.source)
        elif isinstance(p, ConceptPeriod):
            for key, value in p.value.entries.items():
                if key not in (_BINDING_PERIOD_INSTANTS if mode == "binding" else _PERIOD_INSTANTS):
                    continue
                for v in ids_of(value):
                    if mode == "binding":
                        plain(v)
                    else:
                        cn(id_path(v), v.parts, v.source, soft=True)
        elif isinstance(p, ConceptTarget):
            if isinstance(p.value, TargetReferenceValue) and p.value.ref is not None:
                out.append(_ref_of(p.value.ref, owner))
            elif isinstance(p.value, TargetObjectValue):
                _push_id_value(p.value.obj, owner, out)
        elif isinstance(p, ConceptEntry):
            if p.key in ("is_a", "of"):
                for v in ids_of(p.value):
                    cn(id_path(v), v.parts, v.source)
            elif p.key == "qualities":
                for v in ids_of(p.value):
                    cn(id_path(v), v.parts, v.source, soft=True)
            elif p.key == "relations" and isinstance(p.value, ListValue):
                for item in p.value.items:
                    for key in ("type", "to"):
                        for v in ids_of(entry_of(item, key)):
                            cn(id_path(v), v.parts, v.source)
            elif p.key == "hierarchy":
                closures = ids_of(entry_of(p.value, "closure"))
                if not closures:
                    continue
                closure = closures[0]
                plain(closure)
                for key in ("ancestor", "descendant"):
                    for v in ids_of(entry_of(p.value, key)):
                        out.append(
                            CollectedRef(
                                f"{id_path(closure)}.{id_path(v)}", (*closure.parts, *v.parts), v.source, owner
                            )
                        )
            elif p.key == "participants" and mode == "binding" and isinstance(p.value, ObjectValue):
                for key, value in p.value.entries.items():
                    if event_ref is not None:
                        ev = event_ref.ref
                        cn(f"{ev.path}.{key}", (*ev.parts, key), value.source)
                    for v in ids_of(value):
                        plain(v)
            elif p.key == "episodes":
                for v in ids_of(p.value):
                    plain(v)
            elif p.key == "bounds":
                for key in ("from", "until"):
                    for v in ids_of(entry_of(p.value, key)):
                        cn(id_path(v), v.parts, v.source)
            elif p.key == "via":
                for v in ids_of(p.value):
                    if mode == "binding" and body.ref("quality") is not None and v.parts[0] not in ("er", "md", "db"):
                        parts = v.parts[:-1]
                        cn(".".join(parts), parts, v.source)
                    else:
                        plain(v)


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
