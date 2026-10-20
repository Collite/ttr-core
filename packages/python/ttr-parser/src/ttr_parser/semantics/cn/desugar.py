# SPDX-License-Identifier: Apache-2.0
"""0.17 - the cn desugar (CN-P2 S2.2; cn contracts §1.2, §1.4; CN-D9 "sugar in both directions").

A concept is grounded three ways - an authored `def cn2<layer>_*` binding, `grounds:` on the concept
def (top-down), or the `concept { }` block inside the md / er def it grounds to (bottom-up). All three
project into ONE canonical grounding shape here; the sugar also declares its concept. Per document and
mechanical: which concept an authored binding grounds, and whether a concept is grounded twice, is
`groundings.analyze_groundings`'. Mirrors the TS twin (`cn/desugar.ts`) - synthesized names and the
binding table byte-for-byte.
"""

from __future__ import annotations

from dataclasses import dataclass, field

from ...model import (
    AttributeDef,
    ConceptBindingDef,
    ConceptBody,
    ConceptDef,
    ConceptDescription,
    ConceptEntry,
    ConceptGrounds,
    ConceptLabel,
    ConceptProperty,
    ConceptRef,
    ConceptSugar,
    ConceptTags,
    ConceptTarget,
    CubeletDef,
    Definition,
    DimensionDef,
    EntityDef,
    MdDomainDef,
    MeasureDef,
    Reference,
    SourceLocation,
    TargetReferenceValue,
)
from ..default_schema import build_canonical_key

_LAYERS = frozenset({"md", "er", "db"})
_STEREOTYPE_KEYS = frozenset({"kind", "role", "quality", "event", "phase"})

#: How a grounding was written. A symbol the desugar synthesized carries "grounds" or "sugar" (`cn_origin`).
BINDING = "binding"
GROUNDS = "grounds"
SUGAR = "sugar"


def binding_kind_for(stereotype: str, layer: str) -> str | None:
    """The binding kind a grounding of a `stereotype` concept on `layer` desugars to (cn contracts §1.2),
    or None where the table has none: a phase grounds only to an er entity (AM-2), an open-tier relation
    type never grounds."""
    if stereotype == "relation_type":
        return None
    if layer == "db":
        return None if stereotype == "phase" else "cn2db_concept"
    if layer == "md":
        return None if stereotype == "phase" else f"cn2md_{stereotype}"
    return f"cn2er_{stereotype}"


def binding_concept_key(binding_kind: str) -> str:
    """The slot of a binding that names its concept (`event:` on `cn2md_event`, `concept:` on `cn2db_concept`)."""
    return "concept" if binding_kind == "cn2db_concept" else binding_kind.split("_", 1)[1]


def layer_of(ref: Reference) -> str | None:
    """The layer a written target names - its first `md` / `er` / `db` segment (a package prefix is allowed)."""
    return next((p for p in ref.parts if p in _LAYERS), None)


def synthesized_binding_name(concept: str, origin: str) -> str:
    """`<Concept>.grounds` for a `grounds:`, `<Concept>.concept` for a `concept { }` block - two segments,
    never an authored binding's name."""
    return f"{concept}.concept" if origin == SUGAR else f"{concept}.grounds"


@dataclass(frozen=True, slots=True)
class SugarConcept:
    """A concept declared by the `concept { }` block of an md / er def (cn contracts §1.4)."""

    name: str
    stereotype: str
    #: The block; every slot but the declaring one is a property of the concept.
    sugar: ConceptSugar
    #: The declaring slot (`event: Return`) - the concept's authoring site.
    declaring: ConceptRef
    #: The host def's canonical key - what the concept grounds to.
    host_key: str
    layer: str


@dataclass(frozen=True, slots=True)
class CnGrounding:
    """One grounding of a concept - the canonical binding def. An authored binding names its concept by
    reference (`concept_ref`); the two sugars declare it by name in the document's package (`concept_name`)."""

    origin: str
    #: None when the stereotype x layer pair has none, or a `grounds:` names no layer - nothing is grounded.
    binding_kind: str | None
    #: The stereotype a `grounds:` (its def's) or a block (its declaring slot's) states; None on a binding.
    stereotype: str | None
    #: The authored binding's name, or `synthesized_binding_name`.
    name: str
    source: SourceLocation
    concept_name: str | None = None
    concept_ref: Reference | None = None
    #: The written target (an authored binding's bare `target:`, a `grounds:`).
    target: Reference | None = None
    #: The sugar's host def, by canonical key.
    target_key: str | None = None
    #: The binding's properties past its concept slot and `target:`; a `grounds:` carries its `hierarchy:`.
    properties: tuple[ConceptProperty, ...] = field(default_factory=tuple)


@dataclass(frozen=True, slots=True)
class ConceptDesugar:
    concepts: tuple[SugarConcept, ...]
    groundings: tuple[CnGrounding, ...]


def concept_sugar_of(definition: Definition) -> ConceptSugar | None:
    """The `concept { }` sugar of a host def - a cubelet, dimension, domain, measure, entity or attribute."""
    if isinstance(definition, (EntityDef, AttributeDef, DimensionDef, MdDomainDef, MeasureDef, CubeletDef)):
        return definition.concept
    return None


def sugar_stereotype_key(body: ConceptBody) -> str | None:
    """The slot of a `concept { }` sugar that declares the concept - the stereotype slot. A role sugar also
    names its defining event (`{ role: Customer, event: Order }`), so `role:` wins; otherwise the first."""
    first: str | None = None
    for p in body.properties:
        if not isinstance(p, ConceptRef):
            continue
        if p.key == "role":
            return "role"
        if first is None:
            first = p.key
    return first


def concept_ref_of(body: ConceptBody, key: str) -> ConceptRef | None:
    """The first stereotype ref under `key` (`event: Order`, `kind: cn.Item`, ...)."""
    return next((p for p in body.properties if isinstance(p, ConceptRef) and p.key == key), None)


def _is_doc_property(p: ConceptProperty) -> bool:
    """Properties a binding carries for documentation only - not part of what it grounds."""
    return isinstance(p, (ConceptDescription, ConceptLabel, ConceptTags))


def _sugar_hosts(definition: Definition, package_name: str) -> list[tuple[Definition, str, str]]:
    """The md / er defs that may hold a `concept { }` block, nested attributes included: (host, key, layer)."""

    def key(parts: list[str]) -> str:
        return build_canonical_key(package_name, "", definition.kind, parts)

    out: list[tuple[Definition, str, str]] = []
    if isinstance(definition, EntityDef):
        out.append((definition, key([definition.name]), "er"))
        out.extend((a, key([definition.name, a.name]), "er") for a in definition.attributes)
    elif isinstance(definition, AttributeDef):
        out.append((definition, key([definition.name]), "md" if definition.domain_ref is not None else "er"))
    elif isinstance(definition, DimensionDef):
        out.append((definition, key([definition.name]), "md"))
        out.extend((a, key([definition.name, a.name]), "md") for a in definition.attributes)
    elif isinstance(definition, (MdDomainDef, MeasureDef, CubeletDef)):
        out.append((definition, key([definition.name]), "md"))
    return out


def _sugar_concept(sugar: ConceptSugar, host_key: str, layer: str) -> SugarConcept | None:
    """The concept a `concept { }` block declares, or None when its declaring slot names none."""
    key = sugar_stereotype_key(sugar.body)
    if key is None or key not in _STEREOTYPE_KEYS:
        return None
    declaring = concept_ref_of(sugar.body, key)
    if declaring is None:
        return None
    return SugarConcept(declaring.ref.parts[-1], key, sugar, declaring, host_key, layer)


def desugar_concepts(definitions: tuple[Definition, ...], package_name: str) -> ConceptDesugar:
    """Project a document's groundings into canonical binding defs, and collect the concepts its
    `concept { }` blocks declare. `package_name` is the document's effective package."""
    concepts: list[SugarConcept] = []
    groundings: list[CnGrounding] = []

    for definition in definitions:
        if isinstance(definition, ConceptBindingDef):
            slot = binding_concept_key(definition.binding_kind)
            target = next((p.value for p in definition.body.properties if isinstance(p, ConceptTarget)), None)
            declared = concept_ref_of(definition.body, slot)
            groundings.append(
                CnGrounding(
                    origin=BINDING,
                    binding_kind=definition.binding_kind,
                    stereotype=None,
                    name=definition.name,
                    source=definition.source,
                    concept_ref=declared.ref if declared is not None else None,
                    target=target.ref if isinstance(target, TargetReferenceValue) else None,
                    properties=tuple(
                        p
                        for p in definition.body.properties
                        if not (isinstance(p, ConceptRef) and p.key == slot)
                        and not isinstance(p, ConceptTarget)
                        and not _is_doc_property(p)
                    ),
                )
            )
            continue

        if isinstance(definition, ConceptDef):
            grounds = next((p for p in definition.body.properties if isinstance(p, ConceptGrounds)), None)
            if grounds is None:
                continue
            layer = layer_of(grounds.ref)
            hierarchy = next(
                (p for p in definition.body.properties if isinstance(p, ConceptEntry) and p.key == "hierarchy"), None
            )
            groundings.append(
                CnGrounding(
                    origin=GROUNDS,
                    binding_kind=binding_kind_for(definition.stereotype, layer) if layer is not None else None,
                    stereotype=definition.stereotype,
                    name=synthesized_binding_name(definition.name, GROUNDS),
                    source=grounds.source,
                    concept_name=definition.name,
                    target=grounds.ref,
                    properties=(hierarchy,) if hierarchy is not None else (),
                )
            )
            continue

        for host, key, layer in _sugar_hosts(definition, package_name):
            sugar = concept_sugar_of(host)
            if sugar is None:
                continue
            concept = _sugar_concept(sugar, key, layer)
            if concept is None:
                continue
            concepts.append(concept)
            groundings.append(
                CnGrounding(
                    origin=SUGAR,
                    binding_kind=binding_kind_for(concept.stereotype, layer),
                    stereotype=concept.stereotype,
                    name=synthesized_binding_name(concept.name, SUGAR),
                    source=sugar.source,
                    concept_name=concept.name,
                    target_key=key,
                )
            )

    return ConceptDesugar(tuple(concepts), tuple(groundings))
