# SPDX-License-Identifier: Apache-2.0
"""0.17 — one grounding per concept (CN-P2 S2.2; cn contracts §1.2, §1.4; CN-D12 strict, CN-D21).

Over a project's canonical groundings (`desugar.desugar_concepts`): resolve the concept each authored
binding names and the element each grounding targets, group by concept, and report the second and
later groundings of a concept - and a `concept { }` block that restates an authored concept differently
- as `cn/duplicate-grounding`. The first grounding in document and source order is the one read.
Mirrors the TS twin (`cn/groundings.ts`).
"""

from __future__ import annotations

import json
from collections.abc import Sequence
from dataclasses import dataclass

from ...diagnostics import DiagnosticCode, DiagnosticSeverity
from ...model import (
    BoolValue,
    ConceptBasis,
    ConceptDef,
    ConceptDefinition,
    ConceptDescription,
    ConceptEntry,
    ConceptExtent,
    ConceptFilter,
    ConceptGrounds,
    ConceptInvolves,
    ConceptLabel,
    ConceptLexicon,
    ConceptMembers,
    ConceptPeriod,
    ConceptProperty,
    ConceptRef,
    ConceptTags,
    ConceptTarget,
    ConceptValueSpace,
    Definition,
    EmbeddedBlock,
    FunctionCall,
    IdValue,
    ImportStatement,
    ListValue,
    LocalizedStringValue,
    NullValue,
    NumberValue,
    ObjectValue,
    PropertyValue,
    SourceLocation,
    StringValue,
    TargetObjectValue,
    TargetReferenceValue,
    TripleStringValue,
)
from ..default_schema import build_canonical_key
from ..references import id_path
from ..resolver import ResolutionContext, Resolved, Resolver
from ..symbol_table import SymbolEntry
from .desugar import BINDING, CnGrounding, SugarConcept, desugar_concepts


@dataclass(frozen=True, slots=True)
class CnDocument:
    """A project document, as the symbol table was built from it."""

    uri: str
    definitions: tuple[Definition, ...]
    #: The `model` directive code ('' when absent).
    schema_code: str
    namespace: str
    #: The effective package.
    package_name: str
    imports: tuple[ImportStatement, ...] = ()
    #: The `model` directive's span (`cn/cnc-deprecated` points at it).
    directive_source: SourceLocation | None = None


@dataclass(frozen=True, slots=True)
class CnDiagnostic:
    code: DiagnosticCode
    message: str
    #: The slug's grade (cn contracts §7): hygiene and advisory ones are INFORMATION.
    severity: DiagnosticSeverity
    uri: str
    source: SourceLocation


@dataclass(frozen=True, slots=True)
class PlacedGrounding:
    """A grounding placed in its document, its concept and target resolved."""

    grounding: CnGrounding
    uri: str
    package_name: str
    #: The grounded concept's key; None when an authored binding's concept slot does not resolve.
    concept_key: str | None
    #: The target element; None when the target does not resolve.
    target_symbol: SymbolEntry | None


@dataclass(frozen=True, slots=True)
class GroundingAnalysis:
    #: Every grounding, in document and source order.
    groundings: tuple[PlacedGrounding, ...]
    #: Per concept key, its groundings in document and source order - the first is the one read.
    by_concept: dict[str, list[PlacedGrounding]]
    diagnostics: tuple[CnDiagnostic, ...]


def concept_key_of(package_name: str, name: str) -> str:
    """The key of the concept `name` declared in `package_name`: `<pkg>.cn.concept.<name>`."""
    return build_canonical_key(package_name, "", "concept", [name])


def by_place(uri: str, source: SourceLocation) -> tuple[str, int, int]:
    """Document, then source offset — "package / source order" (S2.2)."""
    return (uri, source.offset_start, source.offset_end)


def context_of(doc: CnDocument, enclosing_qname: str | None = None) -> ResolutionContext:
    """The resolution context of a reference written in `doc`."""
    return ResolutionContext(
        schema_code=doc.schema_code,
        namespace=doc.namespace,
        imports=doc.imports,
        package_name=doc.package_name,
        enclosing_qname=enclosing_qname,
    )


def _ref_path(path: str) -> str:
    return path[3:] if path.startswith("cn.") else path


def _value_fp(v: PropertyValue | None) -> object:
    """A property value without its source positions; a ref's `cn.` head dropped."""
    if v is None:
        return None
    if isinstance(v, IdValue):
        return {"id": _ref_path(id_path(v))}
    if isinstance(v, ListValue):
        return [_value_fp(i) for i in v.items]
    if isinstance(v, ObjectValue):
        # entries sorted by key: `{ start:, end: }` says what `{ end:, start: }` says (review-119 M-10)
        return [[k, _value_fp(x)] for k, x in sorted(v.entries.items())]
    if isinstance(v, FunctionCall):
        return {"call": v.name, "args": [_value_fp(a) for a in v.args]}
    if isinstance(v, (StringValue, TripleStringValue, NumberValue, BoolValue)):
        return {v.kind: v.raw}
    if isinstance(v, NullValue):
        return {"null": None}
    return {v.kind: repr(v)}


def _loc_fp(v: LocalizedStringValue | None) -> object:
    return None if v is None else sorted(v.by_language.items())


def _block_fp(b: EmbeddedBlock) -> object:
    return [b.language, b.form, b.text]


def property_fingerprint(p: ConceptProperty) -> str:
    """A property's meaning as a string: its value with every source position dropped; a concept ref's
    optional `cn.` head dropped (`Order` and `cn.Order` say the same); object entries sorted by key. Mirrors
    TS `propertyFingerprint` (an equality, not the same string)."""
    body: object
    if isinstance(p, ConceptDescription):
        body = [_value_fp(p.value) if p.value is not None else None, _loc_fp(p.localized)]
    elif isinstance(p, ConceptLabel):
        body = _loc_fp(p.value)
    elif isinstance(p, ConceptTags):
        body = list(p.value)
    elif isinstance(p, (ConceptRef, ConceptGrounds, ConceptValueSpace)):
        body = _ref_path(p.ref.path)
    elif isinstance(p, ConceptInvolves):
        body = [[_ref_path(x.ref.path), x.label] for x in p.participants]
    elif isinstance(p, ConceptPeriod):
        body = _value_fp(p.value)
    elif isinstance(p, (ConceptExtent, ConceptBasis)):
        body = p.value
    elif isinstance(p, (ConceptDefinition, ConceptMembers, ConceptFilter)):
        body = _block_fp(p.block)
    elif isinstance(p, ConceptLexicon):
        # by its five known keys — the canonical form TS and Kotlin share (review-119 M-10)
        b = p.block
        body = {
            "terms": list(b.terms),
            "patterns": list(b.patterns),
            "examples": list(b.examples),
            "default": b.default,
            "sense_priority": b.sense_priority,
        }
    elif isinstance(p, ConceptTarget):
        if isinstance(p.value, TargetReferenceValue):
            body = {"ref": _ref_path(p.value.ref.path) if p.value.ref is not None else None}
        elif isinstance(p.value, TargetObjectValue):
            body = _value_fp(p.value.obj)
        else:
            body = None
    elif isinstance(p, ConceptEntry):
        body = _value_fp(p.value)
    else:
        body = None
    return json.dumps([type(p).__name__, p.key, body], sort_keys=True, ensure_ascii=False)


def _merge_conflicts(sugar: SugarConcept, uri: str, definition: ConceptDef, def_uri: str) -> list[CnDiagnostic]:
    """The sugar merge rule (S2.2 T3, cn contracts §1.4): a `concept { }` block may name a concept an
    authored cn def declares when it adds only what the def leaves unset."""
    at = f"{def_uri}:{definition.source.line}"
    if sugar.stereotype != definition.stereotype:
        return [
            CnDiagnostic(
                DiagnosticCode.CN_DUPLICATE_GROUNDING,
                f"'concept {{ {sugar.declaring.key}: {sugar.name} }}' declares a {sugar.stereotype}, but "
                f"'{sugar.name}' is a {definition.stereotype} ({at})",
                DiagnosticSeverity.ERROR,
                uri,
                sugar.declaring.source,
            )
        ]
    out: list[CnDiagnostic] = []
    for p in sugar.sugar.body.properties:
        if p is sugar.declaring:
            continue
        theirs = next((q for q in definition.body.properties if q.key == p.key), None)
        if theirs is None or property_fingerprint(theirs) == property_fingerprint(p):
            continue
        out.append(
            CnDiagnostic(
                DiagnosticCode.CN_DUPLICATE_GROUNDING,
                f"'{p.key}' of '{sugar.name}' differs from its concept def ({at}) — a 'concept {{ }}' block "
                "may add only what the def leaves unset",
                DiagnosticSeverity.ERROR,
                uri,
                p.source,
            )
        )
    return out


def analyze_groundings(docs: Sequence[CnDocument], resolver: Resolver) -> GroundingAnalysis:
    """Resolve, group and check the groundings of a project. `resolver` must be built over the symbol
    table of exactly these documents."""
    groundings: list[PlacedGrounding] = []
    diagnostics: list[CnDiagnostic] = []
    sugars: list[tuple[SugarConcept, str, str]] = []
    authored: dict[str, tuple[ConceptDef, str]] = {}

    for doc in docs:
        for d in doc.definitions:
            if isinstance(d, ConceptDef):
                authored.setdefault(concept_key_of(doc.package_name, d.name), (d, doc.uri))
        desugar = desugar_concepts(doc.definitions, doc.package_name)
        for c in desugar.concepts:
            sugars.append((c, doc.uri, concept_key_of(doc.package_name, c.name)))
        for g in desugar.groundings:
            concept_key: str | None = None
            if g.concept_name is not None:
                concept_key = concept_key_of(doc.package_name, g.concept_name)
            elif g.concept_ref is not None:
                r = resolver.resolve_reference(g.concept_ref.path, context_of(doc), cn_context=True)
                concept_key = r.symbol.qname.value if isinstance(r, Resolved) else None
            target_symbol: SymbolEntry | None = None
            if g.target_key is not None:
                target_symbol = resolver.get_symbol(g.target_key)
            elif g.target is not None:
                enclosing = (
                    build_canonical_key(doc.package_name, "", g.binding_kind or "concept_binding", [g.name])
                    if g.origin == BINDING
                    else concept_key
                )
                r = resolver.resolve_reference(g.target.path, context_of(doc, enclosing))
                target_symbol = r.symbol if isinstance(r, Resolved) else None
            groundings.append(PlacedGrounding(g, doc.uri, doc.package_name, concept_key, target_symbol))
    groundings.sort(key=lambda g: by_place(g.uri, g.grounding.source))

    by_concept: dict[str, list[PlacedGrounding]] = {}
    for pg in groundings:
        if pg.grounding.binding_kind is None or pg.concept_key is None:
            continue
        by_concept.setdefault(pg.concept_key, []).append(pg)
    for key, group in by_concept.items():
        first = group[0]
        for extra in group[1:]:
            diagnostics.append(
                CnDiagnostic(
                    DiagnosticCode.CN_DUPLICATE_GROUNDING,
                    f"'{key.rsplit('.', 1)[-1]}' is already grounded at {first.uri}:{first.grounding.source.line} "
                    "— one grounding per concept (CN-D12)",
                    DiagnosticSeverity.ERROR,
                    extra.uri,
                    extra.grounding.source,
                )
            )

    for concept, uri, key in sugars:
        found = authored.get(key)
        if found is not None:
            diagnostics.extend(_merge_conflicts(concept, uri, found[0], found[1]))
    diagnostics.sort(key=lambda d: by_place(d.uri, d.source))

    return GroundingAnalysis(tuple(groundings), by_concept, tuple(diagnostics))
