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
    Definition,
    EntityDef,
    ParseResult,
    ProcedureDef,
    SourceLocation,
    TableDef,
    ViewDef,
    WorldDef,
)
from .default_schema import (
    build_canonical_key,
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
        qname = build_canonical_key(
            package, namespace, definition.kind, [definition.name], declaring_model
        )
        out.append(
            SymbolEntry(
                qname=Qname(qname),
                kind=definition.kind,
                name=definition.name,
                package_name=package,
                schema_code=model_for_kind(definition.kind, declaring_model),
                definition=definition,
                source_file=uri,
            )
        )

        # v4.1 world — engines/executors/storages register under the world; nested
        # storage schemas one level deeper. Members are not Definition subclasses,
        # so the world def stands in as the entry's `definition` placeholder (only
        # the qname/kind/name are used downstream in v1).
        if isinstance(definition, WorldDef):
            members: list[tuple[str, str]] = (
                [(e.name, "engine") for e in definition.engines]
                + [(e.name, "executor") for e in definition.executors]
                + [(s.name, "storage") for s in definition.storages]
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
                )
            )

    # -- queries ------------------------------------------------------------

    def get(self, qname: Qname | str) -> SymbolEntry | None:
        key = qname.value if isinstance(qname, Qname) else qname
        entries = self._by_qname.get(key)
        return entries[0] if entries else None

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
