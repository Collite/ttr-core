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

import re
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
    ConceptExtent,
    ConceptFrom,
    ConceptInvolves,
    ConceptMembers,
    ConceptPer,
    ConceptPeriod,
    ConceptProperty,
    ConceptRef,
    ConceptUntil,
    ConceptValueSpace,
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

#: `cn/subkind-temporal-definition`'s word rule (review-175 F9; TS `validate.ts` and Kotlin say the same): a
#: definition's words are `TTR.g4`'s `IDENT` (`À-ɏ` included). It is temporal when it reads `:as_of` or a `:name`
#: parameter — written, or implied by `within(…)` / `age(…)` — or a DC-12 word; `period` alone is not temporal.
_DEFINITION_WORD = re.compile(r"[A-Za-zÀ-ɏ_][A-Za-z0-9_À-ɏ]*")
_TEMPORAL_READ = re.compile(
    r"(?<![A-Za-z0-9_À-ɏ]):[A-Za-zÀ-ɏ_]|(?<![.A-Za-z0-9_À-ɏ])(?:within|age)\s*\(", re.I
)
_TEMPORAL_WORDS = frozenset({"at_or_before", "before", "contains", "ended_before", "overlaps"})


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
        self._check_things_in_time()
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
                if st is not None and st != expected and not (st == "subkind" and expected in {"kind", "phase"}):
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
                if not ok(target, self._stacked) and not (bk == "cn2er_phase" and target.kind == "entity" and (owner := self.concepts.get(placed.concept_key or "")) and (owner.stated(ConceptPeriod) or any(isinstance(p, ConceptPeriod) for p in g.properties))):
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

    def _check_things_in_time(self) -> None:
        def prop(c: CnConcept, key: str) -> StatedProperty | None:
            own = next((s for s in c.properties if s.property.key == key), None)
            if own is not None:
                return own
            group = self._groundings_of(c)
            if not group:
                return None
            g = group[0]
            doc = self._doc_by_uri[g.uri]
            properties = g.grounding.properties
            direct = next((p for p in properties if p.key == key), None)
            if direct is not None:
                return StatedProperty(direct, doc)
            bounds = next((p.value for p in properties if isinstance(p, ConceptEntry) and p.key == "bounds"), None)
            value = bounds.entries.get(key) if isinstance(bounds, ObjectValue) else None
            if key == "from" and c.stereotype == "quality" and value is None:
                value = next((p.value for p in properties if isinstance(p, ConceptEntry) and p.key == "via"), None)
            if isinstance(value, IdValue) and value.ref is not None:
                cls = ConceptUntil if key == "until" else ConceptFrom
                return StatedProperty(cls(key=key, ref=value.ref, source=value.source), doc)
            return None

        def named(c: CnConcept, key: str) -> CnConcept | None:
            s = prop(c, key)
            if s is None:
                return None
            p = s.property
            ref = p.value.ref if isinstance(p, ConceptEntry) and isinstance(p.value, IdValue) else p.ref if isinstance(p, (ConceptFrom, ConceptUntil, ConceptPer, ConceptRef, ConceptValueSpace)) else None
            symbol = self._concept(ref.path, s.doc) if ref else None
            return self.concepts.get(symbol.qname.value) if symbol else None

        def chain(c: CnConcept) -> set[str]:
            seen: set[str] = set()
            at: CnConcept | None = c
            while at is not None and at.key not in seen:
                seen.add(at.key)
                at = named(at, "of") or named(at, "is_a")
            return seen

        def participates(c: CnConcept, bearer: CnConcept) -> bool:
            for key in chain(c):
                s = prop(self.concepts[key], "involves")
                if s is None or not isinstance(s.property, ConceptInvolves):
                    continue
                for pt in s.property.participants:
                    sym = self._concept(pt.ref.path, s.doc)
                    got = self.concepts.get(sym.qname.value) if sym else None
                    if got and (bearer.key in chain(got) or got.key in chain(bearer)):
                        return True
            return False

        def covers(got: CnConcept, by: CnConcept | None) -> bool:
            return by is not None and (by.key in chain(got) or got.key in chain(by))

        def subject_labels(event: CnConcept, bearer: CnConcept) -> list[str]:
            """⚑N1 (review-175 re-review 2): the labels of `event`'s participants `bearer` can be, read where the renderer
            reads an episode's subject (A-CN-114, R3) - its own `involves:`, else the labels its grounding names, else the
            nearest `of:` / `is_a` level that has one. One is the subject; two or more are ambiguous (⚑R4's count)."""

            def own(owner: CnConcept) -> list[str]:
                s = prop(owner, "involves")
                if s is None or not isinstance(s.property, ConceptInvolves):
                    return []
                out: list[str] = []
                for pt in s.property.participants:
                    sym = self._concept(pt.ref.path, s.doc)
                    got = self.concepts.get(sym.qname.value) if sym else None
                    if got is not None and covers(got, bearer):
                        out.append(pt.label or pt.ref.parts[-1].lower())
                return out

            mine = own(event)
            if mine:
                return mine
            placed = self._groundings_of(event)
            participants = next((p for p in placed[0].grounding.properties if isinstance(p, ConceptEntry) and p.key == "participants"), None) if placed else None
            grounded: list[str] = []
            if participants is not None and isinstance(participants.value, ObjectValue):
                for label in participants.value.entries:
                    sym = self._participant_up(event.key, label)
                    got = self.concepts.get(sym.qname.value) if sym else None
                    if got is not None and covers(got, bearer):
                        grounded.append(label)
            if grounded:
                return grounded
            walk: list[CnConcept] = []
            at: CnConcept | None = event
            while at is not None and all(w.key != at.key for w in walk):  # the chain in order, as TS / Kotlin read it
                walk.append(at)
                at = named(at, "of") or named(at, "is_a")
            return next((labels for labels in (own(level) for level in walk[1:]) if labels), [])

        def relator_participants(source: CnConcept) -> list[tuple[str, CnConcept]]:
            """The relator's participants up its chain, in order, by label — what ⚑R4 counts (not concepts)."""
            out: list[tuple[str, CnConcept]] = []
            walk: list[CnConcept] = []
            at: CnConcept | None = source
            while at is not None and all(w.key != at.key for w in walk):  # the chain in order, as TS / Kotlin read it
                walk.append(at)
                at = named(at, "of") or named(at, "is_a")
            for owner in walk:
                s = prop(owner, "involves")
                if s is None or not isinstance(s.property, ConceptInvolves):
                    continue
                for pt in s.property.participants:
                    sym = self._concept(pt.ref.path, s.doc)
                    got = self.concepts.get(sym.qname.value) if sym else None
                    if got is not None:
                        out.append((pt.label or pt.ref.parts[-1].lower(), got))
            return out

        def emit(c: CnConcept, code: DiagnosticCode, message: str, warning: bool = False, key: str | None = None) -> None:
            s = prop(c, key) if key else None
            self._report(code, DiagnosticSeverity.WARNING if warning else DiagnosticSeverity.ERROR, s.doc.uri if s else c.uri, s.property.source if s else c.source, message)

        def temporal(c: CnConcept, visiting: set[str]) -> bool:
            if c.key in visiting:
                return False
            visiting.add(c.key)
            if c.stereotype == "phase" or _is_true(prop(c, "valued_state")):
                return True
            s = prop(c, "definition") or prop(c, "members")
            if s is None or not isinstance(s.property, (ConceptDefinition, ConceptMembers)):
                return False
            text = re.sub(r"'(''|[^'])*'|\"(\\.|[^\"])*\"", " ", s.property.block.text)
            if _TEMPORAL_READ.search(text):
                return True
            words = set(_DEFINITION_WORD.findall(text))
            if any(w.lower() in _TEMPORAL_WORDS for w in words):
                return True
            return any(x.key != c.key and x.name in words and temporal(x, visiting) for x in self.concepts.values())

        for c in self.concepts.values():
            if c.stereotype == "subkind" and temporal(c, set()):
                emit(c, DiagnosticCode.CN_SUBKIND_TEMPORAL_DEFINITION, f"subkind '{c.name}' has a temporal or parametric definition", key="definition" if prop(c, "definition") else "members")
            if c.stereotype == "kind" and prop(c, "members") and prop(c, "is_a"):
                emit(c, DiagnosticCode.CN_SUBKIND_AS_KIND, f"kind '{c.name}' with members and is_a is a subkind — use 'def subkind'", True)
            event = named(c, "event")
            if c.stereotype == "role" and event and event.stereotype != "event":
                emit(c, DiagnosticCode.CN_ROLE_NO_EVENT, f"role '{c.name}' requires an event as its defining event", key="event")
            bearer = named(c, "of")
            if c.stereotype == "subkind" and bearer and bearer.stereotype not in {"kind", "subkind", "role", "event"}:
                emit(c, DiagnosticCode.CN_SUBKIND_BEARER, f"subkind '{c.name}' requires a rigid bearer — a kind, subkind, role or event, not a {bearer.stereotype}", key="of")
            ep = prop(c, "extent")
            extent = ep.property.value if ep and isinstance(ep.property, ConceptExtent) else None
            if ep:
                emit(c, DiagnosticCode.CN_EXTENT_RETIRED, "extent is retired — events are instantaneous; kinds and phases carry intervals", True, "extent")
            pp = prop(c, "period")
            period = pp.property.value.entries if pp and isinstance(pp.property, ConceptPeriod) else None
            if c.stereotype == "event" and (extent == "durative" or (period is not None and ("start" in period or "end" in period))):
                emit(c, DiagnosticCode.CN_DURATIVE_EVENT, f"event '{c.name}' is durative — model a kind with a lifetime or a phase", key="extent" if ep else "period")
            if c.stereotype in {"kind", "role", "subkind", "phase"} and period is not None and ("at" in period or "start" not in period or "end" not in period):
                emit(c, DiagnosticCode.CN_LIFETIME_PERIOD, f"'{c.name}' needs a start/end interval", key="period")
            from_ = prop(c, "from") is not None
            until = prop(c, "until") is not None
            if c.stereotype == "phase":
                sources = sum([prop(c, "definition") is not None, any(g.grounding.target or g.grounding.target_key for g in self._groundings_of(c)), from_ or until])
                if sources > 1 or from_ != until:
                    emit(c, DiagnosticCode.CN_PHASE_SOURCE_CONFLICT, f"phase '{c.name}' needs exactly one source: definition, grounding, or both from and until")
                if from_ and until and bearer:
                    for key in ("from", "until"):
                        bound = named(c, key)
                        labels = subject_labels(bound, bearer) if bound is not None and bound.stereotype == "event" else []
                        if bound is None or bound.stereotype != "event" or not labels:
                            emit(c, DiagnosticCode.CN_PHASE_BOUNDS_PARTICIPANT, f"phase '{c.name}' {key} bound must be an event involving '{bearer.name}'", key=key)
                        elif len(labels) > 1:
                            many = "two" if len(labels) == 2 else str(len(labels))
                            emit(c, DiagnosticCode.CN_PHASE_BOUNDS_PARTICIPANT, f"phase '{c.name}' {key} bound '{bound.name}' has {many} participants '{bearer.name}' can be — " + ", ".join(f"'{label}'" for label in labels), key=key)
                if period is not None and bearer:
                    group = self._groundings_of(c)
                    target = group[0].target_symbol if group else None
                    subject = next((q for key in chain(bearer) if (q := self._direct_er_entity(key))), None)
                    if target and subject:
                        paths = [ends for d in self._docs for r in d.definitions if isinstance(r, RelationDef) if (sym := self._element(f"er.relation.{r.name}", d)) if (ends := self._ends_of(sym))]
                        if not any(a == target.qname.value and b == subject for a, b in paths):
                            emit(c, DiagnosticCode.CN_EPISODE_SUBJECT_MISSING, f"episode phase '{c.name}' has no relation to its subject '{bearer.name}'")
            if c.stereotype == "quality" and (from_ or prop(c, "per")):
                s = prop(c, "from")
                ref = s.property.ref if s and isinstance(s.property, ConceptFrom) else None
                parts = ref.parts[:-1] if ref else ()
                sym = self._concept(".".join(parts), s.doc) if parts and s else None
                source = self.concepts.get(sym.qname.value) if sym else None
                quality = next((q for q in self.concepts.values() if ref and q.name == ref.parts[-1] and q.stereotype == "quality" and (qb := named(q, "of")) and source and qb.key == source.key), None)
                per = named(c, "per")
                if not _is_true(prop(c, "valued_state")) or source is None or source.stereotype not in {"kind", "subkind", "role"} or bearer is None or not participates(source, bearer) or quality is None or (prop(c, "per") is not None and (per is None or not participates(source, per))):
                    emit(c, DiagnosticCode.CN_PROJECTION_SOURCE, f"quality '{c.name}' needs a valued-state projection of a relator quality involving its bearer and per participant")
                else:
                    # ⚑C (review-175 F6, ⚑R4): one row per bearer — exactly one participant of the relator stands for
                    # the bearer, exactly one for `per:`, and every other one is uncovered. Counted by participant.
                    participants = relator_participants(source)
                    ambiguous = next(
                        ((by, fits) for by in (bearer, per) if by is not None
                         if len(fits := [label for label, got in participants if covers(got, by)]) > 1),
                        None,
                    )
                    uncovered = next(((label, got) for label, got in participants if not covers(got, bearer) and not covers(got, per)), None)
                    if ambiguous is not None:
                        by, labels = ambiguous
                        many = "two" if len(labels) == 2 else str(len(labels))
                        emit(c, DiagnosticCode.CN_PROJECTION_SOURCE, f"quality '{c.name}' projects '{source.name}', which has {many} participants '{by.name}' can be — " + ", ".join(f"'{label}'" for label in labels))
                    elif uncovered is not None:
                        label, got = uncovered
                        emit(c, DiagnosticCode.CN_PROJECTION_SOURCE, f"quality '{c.name}' projects '{source.name}', whose participant '{label}' ('{got.name}') is neither its bearer nor its per: — one '{bearer.name}' reads several rows; add 'per: {got.name}'")
        groups: dict[tuple[str, str], list[tuple[CnConcept, CnConcept, str, str]]] = {}
        for c in self.concepts.values():
            s = prop(c, "definition")
            if c.stereotype != "subkind" or s is None or not isinstance(s.property, ConceptDefinition):
                continue
            match = re.fullmatch(r"\s*(?:this\.)?([A-Za-z\u00C0-\u024F_][A-Za-z0-9_\u00C0-\u024F]*)\s*=\s*'([^']*)'\s*", s.property.block.text)
            bearer = named(c, "of")
            if match and bearer:
                attr, value = match.groups()
                groups.setdefault((bearer.key, attr), []).append((c, bearer, attr, value))
        for partition in groups.values():
            seen: dict[str, CnConcept] = {}
            for c, _bearer, attr, value in partition:
                if value in seen:
                    emit(c, DiagnosticCode.CN_PARTITION_OVERLAP, f"subkind '{c.name}' overlaps '{seen[value].name}' on '{attr}'", key="definition")
                else:
                    seen[value] = c
            first, partition_bearer, attr, _ = partition[0]
            quality = next((q for q in self.concepts.values() if q.name == attr and (qb := named(q, "of")) and qb.key == partition_bearer.key), None)
            space = named(quality, "value_space") if quality else None
            landed = self._groundings_of(space) if space else ()
            target = landed[0].target_symbol if landed else None
            if target:
                attribute = next((a for d in self._docs for e in d.definitions if isinstance(e, EntityDef) for a in e.attributes if a.source == target.site), None)
                missing = set(attribute.value_labels if attribute else ()) - seen.keys()
                if missing:
                    emit(first, DiagnosticCode.CN_PARTITION_INCOMPLETE, f"subkind partition on '{attr}' misses {', '.join(sorted(missing))}", True)

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
        elif c.stereotype in ("quality", "phase", "subkind") and c.stated_entry("of") is None:
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
            if definition is None and not grounded and not abstract and c.stated(ConceptFrom) is None:
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
            c.stereotype in ("relation_type", "phase", "subkind")
            or c.stated(ConceptFrom) is not None
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
        if c.stereotype not in {"event", "kind", "subkind", "role"}:
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

    def _participant_up(self, key: str, label: str) -> SymbolEntry | None:
        """The concept of a concept's participant `label`: its own `involves:`, else up its `of:` / `is_a` chain,
        nearest first (review-175 R5 - an event subkind inherits its bearer's participants). Mirrors TS
        `participantUp`."""
        seen: set[str] = set()
        queue: deque[str] = deque([key])
        while queue:
            at = self.concepts.get(queue.popleft())
            if at is None or at.key in seen:
                continue
            seen.add(at.key)
            involves = at.stated(ConceptInvolves)
            if involves is not None and isinstance(involves.property, ConceptInvolves):
                p = next(
                    (pt for pt in involves.property.participants if (pt.label or pt.ref.parts[-1].lower()) == label),
                    None,
                )
                if p is not None:
                    return self._concept(p.ref.path, involves.doc)
            for k in ("of", "is_a"):
                up = at.stated_entry(k)
                if up is None or not isinstance(up.property, ConceptEntry):
                    continue
                for v in ids_of(up.property.value):
                    sym = self._concept(id_path(v), up.doc)
                    if sym is not None:
                        queue.append(sym.qname.value)
        return None

    def _check_participant_paths(self) -> None:
        for placed in self.groundings.groundings:
            g = placed.grounding
            target = placed.target_symbol
            if g.binding_kind not in {"cn2er_event", "cn2er_kind", "cn2er_role", "cn2er_phase"} or target is None or target.kind != "entity":
                continue
            participants = next((p for p in g.properties if isinstance(p, ConceptEntry) and p.key == "participants"), None)
            doc = self._doc_by_uri.get(placed.uri)
            if participants is None or not isinstance(participants.value, ObjectValue) or doc is None:
                continue
            event = self.concepts.get(placed.concept_key) if placed.concept_key is not None else None
            enclosing = build_canonical_key(doc.package_name, "", "cn2er_event", [g.name])
            for key, value in participants.value.entries.items():
                path = ids_of(value)
                rels = [self._element(id_path(v), doc, enclosing) for v in path]
                if any(r is None for r in rels):
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
                if broken or event is None:
                    continue
                concept = self._participant_up(event.key, key)
                if concept is None:
                    continue
                lands = self._direct_er_entity(concept.qname.value)
                if lands is not None and lands != at:
                    # An empty path (`order: []`) says the participant IS the binding's own row: its concept must land there.
                    self._report(
                        DiagnosticCode.CN_PARTICIPANT_PATH_BROKEN, DiagnosticSeverity.ERROR, placed.uri,
                        path[-1].source if path else value.source,
                        f"the path of '{key}' ends on '{_short_name(at)}', but '{concept.name}' is grounded to "
                        f"'{_short_name(lands)}'"
                        if path
                        else f"the empty path of '{key}' reads the binding's own row '{_short_name(at)}', but "
                        f"'{concept.name}' is grounded to '{_short_name(lands)}'",
                    )
