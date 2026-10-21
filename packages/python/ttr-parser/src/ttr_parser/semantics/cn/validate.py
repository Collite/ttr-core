# SPDX-License-Identifier: Apache-2.0
"""0.17 — the model-level `cn/...` checks (CN-P2 S2.3/S2.4; cn contracts §7, §1.1-§1.6).

One project pass over the desugared model (`groundings.analyze_groundings`): every concept with what it
states (a cn def's properties, then those of each `concept { }` block that names it), every grounding
with its concept and target resolved. A reference that does not resolve is `ttr/unresolved-reference`'s
to report - a check here skips it - except where the contracts give the case a slug of its own (an
undefined relation type, a closure attribute the closure entity lacks). Mirrors the TS twin
(`cn/validate.ts`) and the Kotlin one (`CnValidator.kt`), the portable slugs: the lexicon pair is
TS-only; the definition-level slugs are Kotlin's.
"""

from __future__ import annotations

from collections import deque
from collections.abc import Callable, Sequence
from dataclasses import dataclass, field

from ...diagnostics import DiagnosticCode, DiagnosticSeverity
from ...model import (
    BindingPropertyBlock,
    BoolValue,
    ConceptDef,
    ConceptDefinition,
    ConceptEntry,
    ConceptInvolves,
    ConceptMembers,
    ConceptPeriod,
    ConceptProperty,
    ConceptRef,
    CubeletDef,
    Definition,
    EntityDef,
    Er2DbEntityDef,
    IdValue,
    ListValue,
    ObjectValue,
    RelationDef,
    SourceLocation,
    TargetObjectValue,
    TargetValue,
)
from ..default_schema import build_canonical_key
from ..references import entry_of, id_path, ids_of
from ..resolver import Resolved, Resolver
from ..symbol_table import SymbolEntry
from .desugar import CnGrounding, SugarConcept, desugar_concepts, layer_of
from .groundings import (
    CnDiagnostic,
    CnDocument,
    GroundingAnalysis,
    PlacedGrounding,
    analyze_groundings,
    by_place,
    concept_key_of,
    context_of,
)


@dataclass(frozen=True, slots=True)
class StatedProperty:
    """A property a concept states, with the document it is written in (its references resolve there)."""

    property: ConceptProperty
    doc: CnDocument


@dataclass(slots=True)
class CnConcept:
    """A concept as the project states it — its cn def and every `concept { }` block that names it."""

    key: str
    name: str
    #: The cn def's; for a concept only blocks declare, the first block's.
    stereotype: str
    #: Where it is declared: the cn def, else the declaring slot of its first block.
    uri: str
    source: SourceLocation
    definition: ConceptDef | None = None
    sugars: list[tuple[SugarConcept, CnDocument]] = field(default_factory=list)
    #: The def's properties, then each block's past its declaring slot - the first of a key is read.
    properties: list[StatedProperty] = field(default_factory=list)

    def stated(self, cls: type[ConceptProperty]) -> StatedProperty | None:
        return next((s for s in self.properties if isinstance(s.property, cls)), None)

    def stated_entry(self, key: str) -> StatedProperty | None:
        return next((s for s in self.properties if isinstance(s.property, ConceptEntry) and s.property.key == key), None)


@dataclass(frozen=True, slots=True)
class CnValidation:
    #: Every concept by key, the project's cn defs first, then the ones only blocks declare.
    concepts: dict[str, CnConcept]
    groundings: GroundingAnalysis
    #: Every model-level `cn/...` diagnostic (and the cn `ttr/required-property-missing`), in document order.
    diagnostics: tuple[CnDiagnostic, ...]


def validate_cn(docs: Sequence[CnDocument], resolver: Resolver) -> CnValidation:
    """The model-level cn checks over a project. `resolver` must be built over the symbol table of
    exactly these documents (the desugared concepts resolve through it)."""
    v = _CnValidator(docs, resolver)
    diagnostics = v.run()
    return CnValidation(v.concepts, v.groundings, diagnostics)


def cnc_deprecations(doc: CnDocument) -> list[CnDiagnostic]:
    """`cn/cnc-deprecated` (cn contracts §1.6) — `model cnc` and an entity's `roles:` parse through the
    window, warned. Per document; never run it on the stock vocabulary."""
    out: list[CnDiagnostic] = []
    if doc.schema_code == "cnc":
        out.append(
            CnDiagnostic(
                DiagnosticCode.CN_CNC_DEPRECATED,
                "'model cnc' is deprecated — the cnc roles are superseded by the conceptual model "
                "('model cn'); nothing to migrate to (CN-D18)",
                DiagnosticSeverity.WARNING,
                doc.uri,
                doc.directive_source or SourceLocation.UNKNOWN,
            )
        )
    for d in doc.definitions:
        if isinstance(d, EntityDef) and d.roles:
            out.append(
                CnDiagnostic(
                    DiagnosticCode.CN_CNC_DEPRECATED,
                    f"'roles:' on entity '{d.name}' is deprecated — the cnc roles are superseded by the "
                    "conceptual model ('model cn'); nothing to migrate to (CN-D18)",
                    DiagnosticSeverity.WARNING,
                    doc.uri,
                    d.source,
                )
            )
    return out


# ---------------------------------------------------------------------------
# Small readers
# ---------------------------------------------------------------------------


def _is_true(s: StatedProperty | None) -> bool:
    return (
        s is not None
        and isinstance(s.property, ConceptEntry)
        and isinstance(s.property.value, BoolValue)
        and s.property.value.raw
    )


def inline_measure_sugar(measure: str, cubelet: str) -> str:
    """The message for a `concept { }` on a cubelet's inline measure (review-119 M-11; TS `inlineMeasureSugar`)."""
    return (
        f"a 'concept {{ }}' on the measure '{measure}' written inline in cubelet '{cubelet}' grounds nothing — "
        f"declare it with 'def measure {measure}' and list it in 'measures:'"
    )


def _short_name(qname: str) -> str:
    return qname.rsplit(".", 1)[-1]


def _model_of(sym: SymbolEntry) -> str:
    """The model of a symbol (`md`, `er`, `db`, ...) — its key's first segment past the package."""
    q = sym.qname.value
    rest = q[len(sym.package_name) + 1 :] if sym.package_name else q
    return rest.split(".", 1)[0]


#: The Python kinds whose TS kind string differs (messages name the TS kind).
_TS_KIND = {"md_domain": "mdDomain", "concept_binding": "conceptBinding"}


def _describe(sym: SymbolEntry) -> str:
    kind = sym.kind
    if kind == "entity":
        return "an er entity"
    if kind == "attribute":
        return "an md attribute" if _model_of(sym) == "md" else "an er attribute"
    if kind == "dimension":
        return "a dimension"
    if kind == "md_domain":
        return "a domain"
    if kind == "measure":
        return "a measure"
    if kind == "cubelet":
        return "a cubelet"
    if kind == "relation":
        return "a relation"
    if kind == "concept":
        return f"a {sym.stereotype or 'concept'}"
    return f"{'a db ' if _model_of(sym) == 'db' else 'a '}{_TS_KIND.get(kind, kind)}"


def _article(stereotype: str) -> str:
    return f"an {stereotype}" if stereotype[:1] in "aeiou" and stereotype else f"a {stereotype}"


def _stereotype_of_binding(binding_kind: str) -> str | None:
    """The stereotype a binding kind grounds — None for `cn2db_concept`, which grounds any."""
    return None if binding_kind == "cn2db_concept" else binding_kind.split("_", 1)[1]


_TargetOk = Callable[[SymbolEntry, set[str]], bool]

#: What a binding kind may target, as words and a predicate (design `09` §2, cn contracts §1.2).
_TARGETS: dict[str, tuple[str, _TargetOk]] = {
    "cn2md_kind": ("a dimension or a domain", lambda s, _: s.kind in ("dimension", "md_domain")),
    "cn2md_role": ("a dimension or a domain", lambda s, _: s.kind in ("dimension", "md_domain")),
    "cn2md_quality": (
        "a measure or an md attribute",
        lambda s, _: s.kind == "measure" or (s.kind == "attribute" and _model_of(s) == "md"),
    ),
    "cn2md_event": ("a cubelet", lambda s, _: s.kind == "cubelet"),
    "cn2er_kind": (
        "an er entity, or an er attribute (a value kind)",
        lambda s, _: s.kind == "entity" or (s.kind == "attribute" and _model_of(s) == "er"),
    ),
    "cn2er_role": ("an er entity", lambda s, _: s.kind == "entity"),
    "cn2er_event": ("an er entity", lambda s, _: s.kind == "entity"),
    "cn2er_quality": ("an er attribute", lambda s, _: s.kind == "attribute" and _model_of(s) == "er"),
    "cn2er_phase": (
        "an er entity stacked on a query (AM-2)",
        lambda s, stacked: s.kind == "entity" and s.qname.value in stacked,
    ),
    "cn2db_concept": ("a db object", lambda s, _: _model_of(s) == "db"),
}


def _is_query_target(t: TargetValue | None) -> bool:
    return isinstance(t, TargetObjectValue) and t.obj is not None and "query" in t.obj.entries


# ---------------------------------------------------------------------------
# The analysis
# ---------------------------------------------------------------------------


class _CnValidator:
    def __init__(self, docs: Sequence[CnDocument], resolver: Resolver) -> None:
        self._docs = sorted(docs, key=lambda d: d.uri)
        self._doc_by_uri = {d.uri: d for d in self._docs}
        self._resolver = resolver
        self._out: list[CnDiagnostic] = []
        self.concepts: dict[str, CnConcept] = {}
        self.groundings = analyze_groundings(self._docs, resolver)
        self._out.extend(self.groundings.diagnostics)
        self._collect_concepts()
        self._stacked = self._collect_stacked()

    def run(self) -> tuple[CnDiagnostic, ...]:
        self._check_grounding_targets()
        for c in self.concepts.values():
            self._check_shape(c)
            self._check_involves(c)
            self._check_antirigid(c)
            self._check_relation_types(c)
        self._check_hierarchies()
        self._check_participant_paths()
        return tuple(sorted(self._out, key=lambda d: by_place(d.uri, d.source)))

    def _report(
        self, code: DiagnosticCode, severity: DiagnosticSeverity, uri: str, source: SourceLocation, message: str
    ) -> None:
        self._out.append(CnDiagnostic(code, message, severity, uri, source))

    # --- resolution ----------------------------------------------------------

    def _concept(self, path: str, doc: CnDocument) -> SymbolEntry | None:
        """A reference in a cn slot — among concepts only."""
        r = self._resolver.resolve_reference(path, context_of(doc), cn_context=True)
        return r.symbol if isinstance(r, Resolved) and r.symbol.kind == "concept" else None

    def _element(self, path: str, doc: CnDocument, enclosing: str | None = None) -> SymbolEntry | None:
        """A reference to an md / er / db element."""
        r = self._resolver.resolve_reference(path, context_of(doc, enclosing))
        return r.symbol if isinstance(r, Resolved) else None

    def _def_of(self, sym: SymbolEntry) -> Definition | None:
        """The top-level def a symbol stands for."""
        doc = self._doc_by_uri.get(sym.source_file)
        if doc is None:
            return None
        return next(
            (
                d
                for d in doc.definitions
                if d.kind == sym.kind and d.name == sym.name and d.source.offset_start == sym.site.offset_start
            ),
            None,
        )

    # --- the model -----------------------------------------------------------

    def _collect_concepts(self) -> None:
        for doc in self._docs:
            for d in doc.definitions:
                if not isinstance(d, ConceptDef):
                    continue
                key = concept_key_of(doc.package_name, d.name)
                if key in self.concepts:
                    continue  # a second cn def of one name is `ttr/duplicate-definition`
                self.concepts[key] = CnConcept(
                    key, d.name, d.stereotype, doc.uri, d.source, d,
                    properties=[StatedProperty(p, doc) for p in d.body.properties],
                )
        for doc in self._docs:
            for sugar in desugar_concepts(doc.definitions, doc.package_name).concepts:
                key = concept_key_of(doc.package_name, sugar.name)
                c = self.concepts.get(key)
                if c is None:
                    c = CnConcept(key, sugar.name, sugar.stereotype, doc.uri, sugar.declaring.source)
                    self.concepts[key] = c
                c.sugars.append((sugar, doc))
                c.properties.extend(StatedProperty(p, doc) for p in sugar.sugar.body.properties if p is not sugar.declaring)

    def _collect_stacked(self) -> set[str]:
        """The entities stacked on a query: an entity whose inline `binding:` targets `{ query: ... }`, or
        one a `def er2db_entity` with such a target binds."""
        out: set[str] = set()
        for doc in self._docs:
            for d in doc.definitions:
                if isinstance(d, EntityDef) and isinstance(d.binding, BindingPropertyBlock) and _is_query_target(d.binding.target):
                    out.add(build_canonical_key(doc.package_name, "", "entity", [d.name]))
                elif isinstance(d, Er2DbEntityDef) and d.entity is not None and _is_query_target(d.target):
                    sym = self._element(d.entity.path, doc)
                    if sym is not None and sym.kind == "entity":
                        out.add(sym.qname.value)
        return out

    def _groundings_of(self, c: CnConcept) -> list[PlacedGrounding]:
        return self.groundings.by_concept.get(c.key, [])

    # --- cn/grounding-target-kind - cn/db-grounding --------------------------

    def _check_grounding_targets(self) -> None:
        # A `concept { }` on a measure written inline in a cubelet's `measures:` grounds nothing - the
        # inline measure is no symbol a grounding could name (review-119 M-11): reported, never dropped.
        for doc in self._docs:
            for d in doc.definitions:
                if not isinstance(d, CubeletDef):
                    continue
                for measure, sugar in d.inline_measure_concepts:
                    self._report(
                        DiagnosticCode.CN_GROUNDING_TARGET_KIND,
                        DiagnosticSeverity.ERROR,
                        doc.uri,
                        sugar.source,
                        inline_measure_sugar(measure, d.name),
                    )
        for placed in self.groundings.groundings:
            g = placed.grounding
            name = g.concept_name or (
                _short_name(placed.concept_key) if placed.concept_key is not None else None
            ) or (g.concept_ref.path if g.concept_ref is not None else g.name)
            bk = g.binding_kind
            if bk is None:
                self._report(
                    DiagnosticCode.CN_GROUNDING_TARGET_KIND,
                    DiagnosticSeverity.ERROR,
                    placed.uri,
                    g.target.source if g.target is not None else g.source,
                    self._no_binding_kind(g, name),
                )
                continue
            expected = _stereotype_of_binding(bk)
            if g.origin == "binding" and expected is not None and g.concept_ref is not None and placed.concept_key is not None:
                known = self.concepts.get(placed.concept_key)
                sym = self._resolver.get_symbol(placed.concept_key)
                st = known.stereotype if known is not None else (sym.stereotype if sym is not None else None)
                if st is not None and st != expected:
                    self._report(
                        DiagnosticCode.CN_GROUNDING_TARGET_KIND,
                        DiagnosticSeverity.ERROR,
                        placed.uri,
                        g.concept_ref.source,
                        f"'{bk}' grounds {_article(expected)}, but '{name}' is {_article(st)}",
                    )
            target = placed.target_symbol
            if target is not None:
                what, ok = _TARGETS[bk]
                if not ok(target, self._stacked):
                    is_what = (
                        "an entity no query backs"
                        if bk == "cn2er_phase" and target.kind == "entity"
                        else _describe(target)
                    )
                    self._report(
                        DiagnosticCode.CN_GROUNDING_TARGET_KIND,
                        DiagnosticSeverity.ERROR,
                        placed.uri,
                        g.target.source if g.target is not None else g.source,
                        f"'{bk}' grounds '{name}' to {what} — '{target.name}' is {is_what}",
                    )
            if bk == "cn2db_concept":
                self._report(
                    DiagnosticCode.CN_DB_GROUNDING,
                    DiagnosticSeverity.INFORMATION,
                    placed.uri,
                    g.source,
                    f"'{name}' grounds to db — documentation only: no consumer reaches a db grounding "
                    "(ground it to md or er)",
                )

    @staticmethod
    def _no_binding_kind(g: CnGrounding, name: str) -> str:
        if g.stereotype == "relation_type":
            return f"relation type '{name}' is open tier — it is never grounded (cn contracts §1.3)"
        if g.target is not None and layer_of(g.target) is None:
            return (
                f"'grounds: {g.target.path}' names no md, er or db element — write the target with its "
                "model (`md.…`, `er.entity.…`, `db.…`)"
            )
        return f"phase '{name}' grounds only to an er entity stacked on a query (AM-2), not to md or db"

    # --- the def-shape slugs -------------------------------------------------

    def _check_shape(self, c: CnConcept) -> None:
        grounded = len(self._groundings_of(c)) > 0
        definition = c.stated(ConceptDefinition)
        abstract = _is_true(c.stated_entry("abstract"))
        if c.stereotype == "role":
            if not any(isinstance(s.property, ConceptRef) and s.property.key == "event" for s in c.properties):
                self._report(
                    DiagnosticCode.CN_ROLE_NO_EVENT, DiagnosticSeverity.ERROR, c.uri, c.source,
                    f"role '{c.name}' names no defining event — add 'event: <Event>' (CN-D6)",
                )
        elif c.stereotype in ("quality", "phase") and c.stated_entry("of") is None:
            self._report(
                DiagnosticCode.REQUIRED_PROPERTY_MISSING, DiagnosticSeverity.ERROR, c.uri, c.source,
                f"{c.stereotype} '{c.name}' names no bearer — 'of:' is required",
            )
        if c.stereotype == "phase":
            groundings = self._groundings_of(c)
            if definition is not None and groundings:
                first = groundings[0]
                self._report(
                    DiagnosticCode.CN_PHASE_DOUBLE_DEFINITION, DiagnosticSeverity.ERROR, first.uri, first.grounding.source,
                    f"phase '{c.name}' has a 'definition:' and a grounding — a phase is defined, or grounded to a "
                    "stacked entity, never both (AM-2)",
                )
            if definition is None and not grounded and not abstract:
                self._report(
                    DiagnosticCode.CN_PHASE_NO_DEFINITION, DiagnosticSeverity.INFORMATION, c.uri, c.source,
                    f"phase '{c.name}' has neither a 'definition:' nor a grounding — no consumer can tell its "
                    "members (mark a superconcept 'abstract: true')",
                )
        if c.stereotype == "event" and c.stated(ConceptPeriod) is None:
            self._report(
                DiagnosticCode.CN_EVENT_NO_PERIOD, DiagnosticSeverity.INFORMATION, c.uri, c.source,
                f"event '{c.name}' has no 'period:' — no consumer can place it in time",
            )
        exempt = (
            c.stereotype in ("relation_type", "phase")
            or definition is not None
            or c.stated(ConceptMembers) is not None
            or abstract
        )
        if not grounded and not exempt:
            self._report(
                DiagnosticCode.CN_UNGROUNDED, DiagnosticSeverity.INFORMATION, c.uri, c.source,
                f"{c.stereotype} '{c.name}' is not grounded yet — no consumer reaches it (P-1, eventually grounded)",
            )

    # --- cn/involves-unlabelled-duplicate -----------------------------------

    def _check_involves(self, c: CnConcept) -> None:
        if c.stereotype != "event":
            return
        involves = c.stated(ConceptInvolves)
        if involves is None or not isinstance(involves.property, ConceptInvolves):
            return
        seen: set[str] = set()
        for p in involves.property.participants:
            label = p.label or p.ref.parts[-1].lower()
            if label not in seen:
                seen.add(label)
                continue
            self._report(
                DiagnosticCode.CN_INVOLVES_UNLABELLED_DUPLICATE, DiagnosticSeverity.ERROR, involves.doc.uri, p.source,
                f"'{c.name}' involves a second participant under the label '{label}' — label each one "
                f"('{p.ref.path} as <label>')",
            )

    # --- cn/antirigid-supertype ---------------------------------------------

    def _check_antirigid(self, c: CnConcept) -> None:
        if c.stereotype != "kind":
            return
        is_a = c.stated_entry("is_a")
        if is_a is None:
            return
        seen = {c.key}
        # Breadth-first up the chain; `via` keeps the direct supertype each one was reached through.
        queue: deque[tuple[str, str]] = deque()

        def push(s: StatedProperty, via: str | None) -> None:
            value = s.property.value if isinstance(s.property, ConceptEntry) else None
            for v in ids_of(value):
                sym = self._concept(id_path(v), s.doc)
                if sym is not None and sym.qname.value not in seen:
                    seen.add(sym.qname.value)
                    queue.append((sym.qname.value, via or sym.name))

        push(is_a, None)
        while queue:
            key, via = queue.popleft()
            sup = self.concepts.get(key)
            if sup is None:
                continue
            if sup.stereotype in ("role", "phase"):
                through = f" (through '{via}')" if via != sup.name else ""
                self._report(
                    DiagnosticCode.CN_ANTIRIGID_SUPERTYPE, DiagnosticSeverity.WARNING, is_a.doc.uri, is_a.property.source,
                    f"kind '{c.name}' specializes {sup.stereotype} '{sup.name}'{through} — a kind is rigid and "
                    f"cannot be a subtype of an anti-rigid {sup.stereotype} (OntoUML); make '{c.name}' "
                    f"{_article(sup.stereotype)}",
                )
                return
            nxt = sup.stated_entry("is_a")
            if nxt is not None:
                push(nxt, via)

    # --- cn/unknown-relation-type -------------------------------------------

    def _check_relation_types(self, c: CnConcept) -> None:
        relations = c.stated_entry("relations")
        if relations is None or not isinstance(relations.property, ConceptEntry):
            return
        if not isinstance(relations.property.value, ListValue):
            return
        for item in relations.property.value.items:
            for v in ids_of(entry_of(item, "type")):
                sym = self._concept(id_path(v), relations.doc)
                if sym is not None and sym.stereotype == "relation_type":
                    continue
                message = (
                    f"'{id_path(v)}' is {_article(sym.stereotype or 'concept')}, not a relation type — an "
                    "open-tier edge's 'type:' names a 'def relation_type'"
                    if sym is not None
                    else f"'{id_path(v)}' is no relation type — declare 'def relation_type {v.parts[-1]}'"
                )
                self._report(DiagnosticCode.CN_UNKNOWN_RELATION_TYPE, DiagnosticSeverity.ERROR, relations.doc.uri, v.source, message)

    # --- cn/hierarchy-shape --------------------------------------------------

    def _check_hierarchies(self) -> None:
        seen: set[int] = set()
        stated: list[StatedProperty] = []
        for placed in self.groundings.groundings:
            doc = self._doc_by_uri.get(placed.uri)
            if doc is not None:
                stated.extend(StatedProperty(p, doc) for p in placed.grounding.properties)
        for c in self.concepts.values():
            stated.extend(c.properties)
        for s in stated:
            p = s.property
            if not isinstance(p, ConceptEntry) or p.key != "hierarchy" or id(p) in seen:
                continue
            seen.add(id(p))
            self._check_hierarchy(p, s.doc)

    def _check_hierarchy(self, h: ConceptEntry, doc: CnDocument) -> None:
        closures = ids_of(entry_of(h.value, "closure"))
        if not closures:
            self._report(DiagnosticCode.CN_HIERARCHY_SHAPE, DiagnosticSeverity.ERROR, doc.uri, h.source, "'hierarchy:' names no 'closure:' entity")
            return
        closure_id = closures[0]
        closure = self._element(id_path(closure_id), doc)
        if closure is None:
            return  # `ttr/unresolved-reference`
        if closure.kind != "entity":
            self._report(
                DiagnosticCode.CN_HIERARCHY_SHAPE, DiagnosticSeverity.ERROR, doc.uri, closure_id.source,
                f"the hierarchy closure '{id_path(closure_id)}' is {_describe(closure)} — it must be an er entity",
            )
            return
        entity = self._def_of(closure)
        if not isinstance(entity, EntityDef):
            return
        attributes = {a.name: a for a in entity.attributes}
        for key in ("ancestor", "descendant"):
            ids = ids_of(entry_of(h.value, key))
            if not ids:
                self._report(
                    DiagnosticCode.CN_HIERARCHY_SHAPE, DiagnosticSeverity.ERROR, doc.uri, h.source,
                    f"'hierarchy:' names no '{key}:' attribute of '{closure.name}'",
                )
            elif id_path(ids[0]) not in attributes:
                self._report(
                    DiagnosticCode.CN_HIERARCHY_SHAPE, DiagnosticSeverity.ERROR, doc.uri, ids[0].source,
                    f"the hierarchy closure '{closure.name}' has no attribute '{id_path(ids[0])}' (its '{key}:')",
                )
        if not any(a.is_key for a in attributes.values()):
            self._report(
                DiagnosticCode.CN_HIERARCHY_SHAPE, DiagnosticSeverity.ERROR, doc.uri, closure_id.source,
                f"the hierarchy closure '{closure.name}' has no key attribute",
            )

    # --- cn/participant-path-broken -----------------------------------------

    def _ends_of(self, rel: SymbolEntry) -> tuple[str, str] | None:
        """A relation's two entities, resolved in the relation's own document."""
        d = self._def_of(rel)
        doc = self._doc_by_uri.get(rel.source_file)
        if not isinstance(d, RelationDef) or doc is None:
            return None

        def end(value: object) -> str | None:
            ids = ids_of(value) if isinstance(value, (IdValue, ListValue)) else []
            sym = self._element(id_path(ids[0]), doc) if ids else None
            return sym.qname.value if sym is not None else None

        frm, to = end(d.from_), end(d.to)
        return (frm, to) if frm is not None and to is not None else None

    def _direct_er_entity(self, concept_key: str) -> str | None:
        """The entity a participant concept's own grounding lands on directly in er; None when it has none."""
        group = self.groundings.by_concept.get(concept_key)
        if not group:
            return None
        g = group[0]
        t = g.target_symbol
        if t is None or not (g.grounding.binding_kind or "").startswith("cn2er_"):
            return None
        if t.kind == "entity":
            return t.qname.value
        return t.parent if t.kind == "attribute" else None

    def _check_participant_paths(self) -> None:
        for placed in self.groundings.groundings:
            g = placed.grounding
            target = placed.target_symbol
            if g.binding_kind != "cn2er_event" or target is None or target.kind != "entity":
                continue
            participants = next((p for p in g.properties if isinstance(p, ConceptEntry) and p.key == "participants"), None)
            doc = self._doc_by_uri.get(placed.uri)
            if participants is None or not isinstance(participants.value, ObjectValue) or doc is None:
                continue
            event = self.concepts.get(placed.concept_key) if placed.concept_key is not None else None
            involves = event.stated(ConceptInvolves) if event is not None else None
            enclosing = build_canonical_key(doc.package_name, "", "cn2er_event", [g.name])
            for key, value in participants.value.entries.items():
                path = ids_of(value)
                rels = [self._element(id_path(v), doc, enclosing) for v in path]
                if not path or any(r is None for r in rels):
                    continue  # `ttr/unresolved-reference`
                at = target.qname.value
                broken = False
                for i, v in enumerate(path):
                    rel = rels[i]
                    assert rel is not None
                    ends = self._ends_of(rel) if rel.kind == "relation" else None
                    if rel.kind != "relation":
                        self._report(
                            DiagnosticCode.CN_PARTICIPANT_PATH_BROKEN, DiagnosticSeverity.ERROR, placed.uri, v.source,
                            f"'{id_path(v)}' in the path of '{key}' is {_describe(rel)}, not a relation",
                        )
                        broken = True
                    elif ends is None:
                        broken = True  # a relation whose ends do not resolve: `ttr/unresolved-reference`
                    elif at not in ends:
                        self._report(
                            DiagnosticCode.CN_PARTICIPANT_PATH_BROKEN, DiagnosticSeverity.ERROR, placed.uri, v.source,
                            f"the path of '{key}' starts at '{rel.name}', which does not touch the event's entity "
                            f"'{_short_name(at)}'"
                            if i == 0
                            else f"the path of '{key}' breaks at '{rel.name}' — it does not continue from "
                            f"'{_short_name(at)}'",
                        )
                        broken = True
                    else:
                        at = ends[1] if ends[0] == at else ends[0]
                    if broken:
                        break
                if broken or involves is None or not isinstance(involves.property, ConceptInvolves):
                    continue
                participant = next(
                    (p for p in involves.property.participants if (p.label or p.ref.parts[-1].lower()) == key), None
                )
                concept = self._concept(participant.ref.path, involves.doc) if participant is not None else None
                if concept is None:
                    continue
                lands = self._direct_er_entity(concept.qname.value)
                if lands is not None and lands != at:
                    self._report(
                        DiagnosticCode.CN_PARTICIPANT_PATH_BROKEN, DiagnosticSeverity.ERROR, placed.uri, path[-1].source,
                        f"the path of '{key}' ends on '{_short_name(at)}', but '{concept.name}' is grounded to "
                        f"'{_short_name(lands)}'",
                    )
