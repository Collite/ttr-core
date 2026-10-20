# SPDX-License-Identifier: Apache-2.0
"""Project-level symbol table (← `project-symbols.ts` + `symbol-table.ts`).

Builds one `SymbolEntry` per definition (and per child attribute/column), keyed
by the dotted qname. Qname construction mirrors the TS `DocumentSymbolTable`
exactly so the §5.1 conformance dump stays byte-identical:

    [package?] [cnc-if-stock] schema [namespace|default-ns|kind] name [child]

`upsert_document` replaces a document's prior entries; `duplicates()` surfaces
qname collisions across documents (the duplicate-definition validator reads it).
The `is_stock_cnc` gate stores the bundled cnc roles under the doubled
`cnc.cnc.role.<name>` form the resolver's auto-import step looks for.
"""

from __future__ import annotations

from dataclasses import dataclass

from ..model import (
    ConceptBindingDef,
    ConceptDef,
    ConceptInvolves,
    ConceptProperty,
    Definition,
    DimensionDef,
    EntityDef,
    ParseResult,
    ProcedureDef,
    SourceLocation,
    TableDef,
    ViewDef,
    WorldDef,
)
from .cn.desugar import BINDING, SUGAR, concept_sugar_of, desugar_concepts
from .default_schema import (
    build_canonical_key,
    key_kind_of,
    model_for_kind,
)
from .qname import Qname


@dataclass(frozen=True, slots=True)
class SymbolEntry:
    qname: Qname
    kind: str
    name: str
    package_name: str
    schema_code: str
    definition: Definition
    source_file: str
    mapping_source: SourceLocation | None = None
    #: 0.17 - a concept's stereotype (`kind == "concept"` only); every stereotype shares one namespace.
    stereotype: str | None = None
    #: 0.17 - a cn binding's keyword (`kind == "concept_binding"` only); its key's kind segment.
    binding_kind: str | None = None
    #: 0.17 - set on what the cn desugar synthesized ("grounds" | "sugar"): a concept a `concept { }`
    #: block declares (and its participant labels), or the binding a `grounds:` / block stands for.
    #: Element references never reach these; an authored symbol of the same key is preferred (`get`).
    cn_origin: str | None = None
    #: The parent symbol's qname (a child attribute / column / participant), else None.
    parent: str | None = None
    #: The symbol's own span where it is not its `definition`'s (a participant, a synthesized symbol).
    location: SourceLocation | None = None

    @property
    def site(self) -> SourceLocation:
        """Where the symbol is written."""
        return self.location if self.location is not None else self.definition.source


class SymbolTable:
    """Mirrors TS `ProjectSymbolTable`: a per-qname multimap plus per-document
    index for replacement."""

    def __init__(self) -> None:
        self._by_document: dict[str, list[SymbolEntry]] = {}
        self._by_qname: dict[str, list[SymbolEntry]] = {}

    # -- mutation -----------------------------------------------------------

    def upsert_document(
        self, uri: str, result: ParseResult, *, package_name: str = ""
    ) -> None:
        if uri in self._by_document:
            self.remove_document(uri)

        directive = result.model_directive
        namespace = (directive.schema if directive else "") or ""
        # the file's `model` code only moves a query declared under `model er` (LQ L-1)
        declaring_model = (directive.model_code if directive else "") or ""
        package = package_name or (result.package_name or "")

        entries: list[SymbolEntry] = []
        for definition in result.definitions:
            self._add_definition(entries, definition, uri, namespace, package, declaring_model)
        self._add_desugared(entries, result.definitions, uri, namespace, package)

        self._by_document[uri] = entries
        for entry in entries:
            self._by_qname.setdefault(entry.qname.value, []).append(entry)

    def remove_document(self, uri: str) -> None:
        entries = self._by_document.pop(uri, None)
        if not entries:
            return
        for entry in entries:
            existing = self._by_qname.get(entry.qname.value)
            if existing is None:
                continue
            remaining = [e for e in existing if e.source_file != uri]
            if remaining:
                self._by_qname[entry.qname.value] = remaining
            else:
                del self._by_qname[entry.qname.value]

    # -- qname construction (mirror of DocumentSymbolTable, v4.0) -----------

    def _add_definition(
        self,
        out: list[SymbolEntry],
        definition: Definition,
        uri: str,
        namespace: str,
        package: str,
        declaring_model: str = "",
    ) -> None:
        # v4.0 uniform key: model/schema/kind from the def's own kind (D12);
        # schema slot db-only (file `schema` id, else dbo); kind segment present.
        # A cn binding keys by its binding keyword (`key_kind_of`).
        qname = build_canonical_key(
            package, namespace, key_kind_of(definition), [definition.name], declaring_model
        )
        entry = SymbolEntry(
            qname=Qname(qname),
            kind=definition.kind,
            name=definition.name,
            package_name=package,
            schema_code=model_for_kind(definition.kind, declaring_model),
            definition=definition,
            source_file=uri,
            stereotype=definition.stereotype if isinstance(definition, ConceptDef) else None,
            binding_kind=definition.binding_kind if isinstance(definition, ConceptBindingDef) else None,
        )
        out.append(entry)

        # 0.17 - an event's participants register by label.
        if isinstance(definition, ConceptDef):
            if definition.stereotype == "event":
                self._add_participants(out, entry, definition, definition.body.properties, uri, namespace, package)
            return

        # v4.1 world — engines/executors/storages register under the world; nested
        # storage schemas one level deeper. Members are not Definition subclasses,
        # so the world def stands in as the entry's `definition` placeholder (only
        # the qname/kind/name are used downstream in v1).
        if isinstance(definition, WorldDef):
            members: list[tuple[str, str]] = (
                [(e.name, "engine") for e in definition.engines]
                + [(e.name, "executor") for e in definition.executors]
                + [(s.name, "storage") for s in definition.storages]
                # 0.16 — the named automation members (grants are unnamed: no symbol).
                + [(m.name, "identity") for m in definition.identities if m.name is not None]
                + [(m.name, "deployment") for m in definition.deployments if m.name is not None]
                + [(pr.name, "process") for pr in definition.processes]
            )
            for member_name, member_kind in members:
                mq = build_canonical_key(package, namespace, definition.kind, [definition.name, member_name])
                out.append(
                    SymbolEntry(
                        qname=Qname(mq),
                        kind=member_kind,
                        name=member_name,
                        package_name=package,
                        schema_code=model_for_kind(definition.kind),
                        definition=definition,
                        source_file=uri,
                    )
                )
            for storage in definition.storages:
                for schema in storage.schemas:
                    sq = build_canonical_key(
                        package, namespace, definition.kind, [definition.name, storage.name, schema.name]
                    )
                    out.append(
                        SymbolEntry(
                            qname=Qname(sq),
                            kind="worldSchema",
                            name=schema.name,
                            package_name=package,
                            schema_code=model_for_kind(definition.kind),
                            definition=definition,
                            source_file=uri,
                        )
                    )
            return

        children: tuple[Definition, ...] = ()
        if isinstance(definition, EntityDef):
            children = definition.attributes
        elif isinstance(definition, DimensionDef):
            # v3.1 MD - dimension attributes register dimension-qualified (`md.dimension.<Dim>.<attr>`).
            children = definition.attributes
        elif isinstance(definition, (TableDef, ViewDef)):
            children = definition.columns
        elif isinstance(definition, ProcedureDef):
            children = definition.result_columns

        if not children:
            return

        # Members are grouped under the parent def's model/schema/kind.
        for child in children:
            child_qname = build_canonical_key(
                package, namespace, definition.kind, [definition.name, child.name]
            )
            out.append(
                SymbolEntry(
                    qname=Qname(child_qname),
                    kind=child.kind,
                    name=child.name,
                    package_name=package,
                    schema_code=model_for_kind(definition.kind),
                    definition=child,
                    source_file=uri,
                    parent=qname,
                )
            )

    @staticmethod
    def _add_participants(
        out: list[SymbolEntry],
        event: SymbolEntry,
        definition: Definition,
        properties: tuple[ConceptProperty, ...],
        uri: str,
        namespace: str,
        package: str,
        cn_origin: str | None = None,
    ) -> None:
        """0.17 - an event's participants register by label (`Warehouse as fulfilling` -> `fulfilling`;
        unlabelled -> the concept name lower-cased, cn contracts §1.1). Two unlabelled participants of
        one concept share a key - `cn/involves-unlabelled-duplicate`, not a duplicate definition."""
        involves = next((p for p in properties if isinstance(p, ConceptInvolves)), None)
        if involves is None:
            return
        taken = {e.qname.value for e in out}
        for participant in involves.participants:
            label = participant.label or participant.ref.parts[-1].lower()
            pq = build_canonical_key(package, namespace, "concept", [event.name, label])
            if pq in taken:
                if cn_origin is not None:
                    continue
                # a repeated label within one def: the later replaces the earlier (the TS map's set).
                out[:] = [e for e in out if e.qname.value != pq]
            taken.add(pq)
            out.append(
                SymbolEntry(
                    qname=Qname(pq),
                    kind="participant",
                    name=label,
                    package_name=package,
                    schema_code=event.schema_code,
                    definition=definition,
                    source_file=uri,
                    cn_origin=cn_origin,
                    parent=event.qname.value,
                    location=participant.source,
                )
            )

    def _add_desugared(
        self,
        out: list[SymbolEntry],
        definitions: tuple[Definition, ...],
        uri: str,
        namespace: str,
        package: str,
    ) -> None:
        """0.17 - the concepts this document's `concept { }` blocks declare and the bindings its
        `grounds:` and `concept { }` stand for (`cn.desugar`). A synthesized symbol never replaces one
        the document declares. Mirrors TS `DocumentSymbolTable.addDesugared`."""
        desugar = desugar_concepts(definitions, package)
        hosts = {id(sugar): d for d in _sugar_host_defs(definitions) if (sugar := concept_sugar_of(d)) is not None}
        taken = {e.qname.value for e in out}
        for c in desugar.concepts:
            q = build_canonical_key(package, namespace, "concept", [c.name])
            if q in taken:
                continue
            taken.add(q)
            host = hosts.get(id(c.sugar))
            entry = SymbolEntry(
                qname=Qname(q),
                kind="concept",
                name=c.name,
                package_name=package,
                schema_code="cn",
                definition=host if host is not None else definitions[0],
                source_file=uri,
                stereotype=c.stereotype,
                cn_origin=SUGAR,
                location=c.declaring.source,
            )
            out.append(entry)
            if c.stereotype == "event":
                self._add_participants(out, entry, entry.definition, c.sugar.body.properties, uri, namespace, package, SUGAR)
                taken = {e.qname.value for e in out}
        for g in desugar.groundings:
            if g.origin == BINDING or g.binding_kind is None:
                continue
            q = build_canonical_key(package, namespace, g.binding_kind, g.name.split("."))
            if q in taken:
                continue
            taken.add(q)
            out.append(
                SymbolEntry(
                    qname=Qname(q),
                    kind="concept_binding",
                    name=g.name,
                    package_name=package,
                    schema_code="binding",
                    definition=definitions[0],
                    source_file=uri,
                    binding_kind=g.binding_kind,
                    cn_origin=g.origin,
                    location=g.source,
                )
            )

    # -- queries ------------------------------------------------------------

    def get(self, qname: Qname | str) -> SymbolEntry | None:
        """The entry a qname reads as. 0.17: an authored symbol over one the cn desugar synthesized
        (`cn_origin`); among synthesized ones the first by document, then source offset. Mirrors TS
        `ProjectSymbolTable.get`."""
        key = qname.value if isinstance(qname, Qname) else qname
        entries = self._by_qname.get(key)
        if not entries:
            return None
        if len(entries) < 2:
            return entries[0]
        authored = next((e for e in entries if e.cn_origin is None), None)
        if authored is not None:
            return authored
        return min(entries, key=lambda e: (e.source_file, e.site.offset_start))

    def packages(self) -> set[str]:
        """Every package a symbol is declared in (the root package '' included)."""
        return {e.package_name for e in self.get_all()}

    def get_all(self) -> tuple[SymbolEntry, ...]:
        out: list[SymbolEntry] = []
        seen: set[str] = set()
        for entries in self._by_qname.values():
            for entry in entries:
                if entry.qname.value not in seen:
                    seen.add(entry.qname.value)
                    out.append(entry)
        return tuple(out)

    def get_by_package(self, pkg: Qname | str) -> tuple[SymbolEntry, ...]:
        name = pkg.value if isinstance(pkg, Qname) else pkg
        return tuple(e for e in self.get_all() if e.package_name == name)

    def get_by_suffix(self, last: str) -> tuple[SymbolEntry, ...]:
        out: list[SymbolEntry] = []
        for entries in self._by_qname.values():
            for entry in entries:
                if entry.qname.value.endswith(f".{last}") or entry.qname.value == last:
                    out.append(entry)
        return tuple(out)

    def duplicates(self) -> tuple[tuple[SymbolEntry, ...], ...]:
        return tuple(
            tuple(entries)
            for entries in self._by_qname.values()
            if len(entries) > 1
        )


def _sugar_host_defs(definitions: tuple[Definition, ...]) -> list[Definition]:
    """Every def that may hold a `concept { }` block, nested attributes included."""
    out: list[Definition] = []
    for d in definitions:
        out.append(d)
        if isinstance(d, (EntityDef, DimensionDef)):
            out.extend(d.attributes)
    return out
