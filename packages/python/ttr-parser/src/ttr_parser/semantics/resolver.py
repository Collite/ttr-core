# SPDX-License-Identifier: Apache-2.0
"""Reference resolver — the six-step chain (← `resolver.ts`, contracts §3.3).

The step order is load-bearing and pinned by the §5.1 conformance dump:

  1. lexical          — bare id as a child of the enclosing def
  2. same-package     — `schema.ns.name` direct hit, then a sibling in the
                        document's package
  3. named-import     — an `import a.b.c.name` whose suffix matches
  4. wildcard-import  — `import a.b.*`, NON-recursive (exactly one segment below
                        the imported package); two matches ⇒ ambiguous
  5. auto-import      — stock cnc roles under the doubled `cnc.cnc.role.<name>`
  6. fully-qualified  — a unique suffix match anywhere in the project

A named import shadows a wildcard (step 3 precedes step 4). Each failed step is
recorded in `tried` for the not-found diagnostic. Mirrors `resolver.ts` exactly;
the Python API takes the reference as a dotted string (split internally) rather
than the TS `{ path, parts }`.
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Literal

from ..model import ImportStatement
from .default_schema import MODEL_CODES
from .symbol_table import SymbolEntry, SymbolTable

# Kind segments that may lead a written reference (camelCase + MD aliases).
_KIND_KEYWORDS: frozenset[str] = frozenset(
    {
        "entity", "attribute", "relation", "table", "view", "column", "index",
        "constraint", "fk", "procedure", "query", "drillMap", "role", "project",
        "area", "er2dbEntity", "er2dbAttribute", "er2dbRelation", "er2cncRole",
        "domain", "map", "dimension", "hierarchy", "measure", "cubelet",
        "md2db_cubelet", "md2db_domain", "md2db_map", "md2er_cubelet",
    }
)

ResolutionStep = Literal[
    "lexical",
    "same-package",
    "named-import",
    "wildcard-import",
    "auto-import",
    "fully-qualified",
]

ResolutionReason = Literal[
    "unknown-symbol",
    "not-imported",
    "wildcard-non-recursive",
    "shadowed-by-named-import",
    "lexical-scope-empty",
    "ambiguous",
]


@dataclass(frozen=True, slots=True)
class ResolutionContext:
    schema_code: str
    namespace: str
    imports: tuple[ImportStatement, ...] = ()
    package_name: str = ""
    enclosing_qname: str | None = None


@dataclass(frozen=True, slots=True)
class EnclosingDef:
    kind: str
    qname: str


@dataclass(frozen=True, slots=True)
class LexicalScope:
    schema_code: str
    namespace: str
    enclosing: EnclosingDef | None = None


@dataclass(frozen=True, slots=True)
class ResolutionAttempt:
    step: ResolutionStep
    candidate: str
    reason: ResolutionReason | None = None


@dataclass(frozen=True, slots=True)
class Resolved:
    symbol: SymbolEntry
    via_step: ResolutionStep


@dataclass(frozen=True, slots=True)
class Unresolved:
    reason: Literal["not-found", "ambiguous"]
    tried: tuple[ResolutionAttempt, ...]
    candidates: tuple[SymbolEntry, ...] = ()


ResolutionResult = Resolved | Unresolved


def _is_cn_symbol(e: SymbolEntry) -> bool:
    """A concept or one of its participant labels (model `cn`, 0.17)."""
    return e.schema_code == "cn"


def _is_hidden_from_elements(e: SymbolEntry) -> bool:
    """What an element reference never reaches: the cn model, and the bindings the cn desugar
    synthesized - named `<Concept>.grounds` / `<Concept>.concept`, they would match a bare name by suffix."""
    return _is_cn_symbol(e) or e.cn_origin is not None


def _cn_key(pkg: str, parts: list[str] | tuple[str, ...]) -> str:
    """The canonical key of a concept (or `<Name>.<label>`) in `pkg`: `<pkg>.cn.concept.<parts>`."""
    return (f"{pkg}." if pkg else "") + ".".join(["cn", "concept", *parts])


class Resolver:
    def __init__(self, symbols: SymbolTable) -> None:
        self._symbols = symbols

    def get_symbol(self, qname: str) -> SymbolEntry | None:
        """Direct symbol-table lookup by fully-qualified name."""
        return self._symbols.get(qname)

    # -- 0.17: concepts are reachable only from a cn slot or the `cn.` head (CN-P2 S2.1) --

    def _element(self, qname: str) -> SymbolEntry | None:
        e = self._symbols.get(qname)
        return None if e is None or _is_hidden_from_elements(e) else e

    def _elements_by_package(self, pkg: str) -> tuple[SymbolEntry, ...]:
        return tuple(e for e in self._symbols.get_by_package(pkg) if not _is_hidden_from_elements(e))

    def _elements_by_suffix(self, suffix: str) -> tuple[SymbolEntry, ...]:
        return tuple(e for e in self._symbols.get_by_suffix(suffix) if not _is_hidden_from_elements(e))

    def _cn_symbol(self, key: str) -> SymbolEntry | None:
        e = self._symbols.get(key)
        return e if e is not None and _is_cn_symbol(e) else None

    def resolve_reference(
        self, ref: str, context: ResolutionContext, *, cn_context: bool = False
    ) -> ResolutionResult:
        """Resolve a dotted reference. `cn_context` marks a reference from a cn slot
        (`CollectedRef.cn_context`): it - and any `cn.`-headed reference - resolves among concepts only."""
        path = ref
        parts = path.split(".")
        if cn_context or (len(parts) > 1 and parts[0] == "cn"):
            return self._resolve_cn(parts, context)
        tried: list[ResolutionAttempt] = []

        enclosing_candidate = (
            f"{context.enclosing_qname}.{path}" if context.enclosing_qname else None
        )

        # Step 1 — lexical scope.
        if enclosing_candidate is not None:
            symbol = self._element(enclosing_candidate)
            if symbol is not None:
                return Resolved(symbol, "lexical")
            tried.append(
                ResolutionAttempt("lexical", enclosing_candidate, "unknown-symbol")
            )

        # Step 2 — same-package: direct `schema.ns.name`, then package sibling.
        full_qname = f"{context.schema_code}.{context.namespace}.{path}"
        if full_qname != context.enclosing_qname:
            symbol = self._element(full_qname)
            if symbol is not None:
                return Resolved(symbol, "same-package")
            tried.append(
                ResolutionAttempt("same-package", full_qname, "unknown-symbol")
            )

        if context.package_name:
            for entry in self._elements_by_package(context.package_name):
                if entry.name == path:
                    candidate = entry.qname.value
                    if candidate not in (enclosing_candidate, full_qname):
                        return Resolved(entry, "same-package")

        # Steps 3–4 — imports.
        if context.imports:
            for imp in context.imports:
                if imp.wildcard:
                    continue
                if imp.target.endswith(f".{path}"):
                    symbol = self._element(imp.target)
                    if symbol is not None:
                        return Resolved(symbol, "named-import")

            wildcard_matches: list[SymbolEntry] = []
            for imp in context.imports:
                if not imp.wildcard:
                    continue
                for entry in self._elements_by_package(imp.target):
                    if entry.name == path and entry.qname.value != full_qname:
                        if not any(
                            w.qname.value == entry.qname.value for w in wildcard_matches
                        ):
                            wildcard_matches.append(entry)

            if len(wildcard_matches) > 1:
                for match in wildcard_matches:
                    tried.append(
                        ResolutionAttempt(
                            "wildcard-import", match.qname.value, "ambiguous"
                        )
                    )
                return Unresolved("ambiguous", tuple(tried), tuple(wildcard_matches))
            if len(wildcard_matches) == 1:
                tried.append(
                    ResolutionAttempt("wildcard-import", wildcard_matches[0].qname.value)
                )
                return Resolved(wildcard_matches[0], "wildcard-import")

        # Step 5 — auto-import (stock cnc roles, keyed cnc.role.* — D15).
        cnc_qname = f"cnc.role.{path}"
        if cnc_qname not in (full_qname, context.enclosing_qname):
            symbol = self._element(cnc_qname)
            if symbol is not None:
                return Resolved(symbol, "auto-import")
            tried.append(ResolutionAttempt("auto-import", cnc_qname, "unknown-symbol"))

        # Step 6 — fully-qualified: the written form, then the classified tail —
        # the trailing name path with leading model/package/schema/kind segments
        # stripped — so a reference naming a slot the v4.0 key now carries
        # explicitly (`db.dbo.Orders` → `…db.dbo.table.Orders`) resolves by suffix.
        tail = self._classified_tail(path)
        forms = [path, tail] if tail and tail != path else [path]
        for cand in forms:
            seen: set[str] = set()
            unique_matches = []
            for e in self._elements_by_suffix(cand):
                if e.qname.value in (full_qname, cnc_qname) or e.qname.value in seen:
                    continue
                seen.add(e.qname.value)
                unique_matches.append(e)
            if len(unique_matches) == 1:
                tried.append(
                    ResolutionAttempt("fully-qualified", unique_matches[0].qname.value)
                )
                return Resolved(unique_matches[0], "fully-qualified")

        return Unresolved("not-found", tuple(tried))

    def _resolve_cn(self, written: list[str], context: ResolutionContext) -> ResolutionResult:
        """0.17 - a reference from a cn slot, or one written with the `cn.` head, resolves among concepts
        only: `<pkg>.cn.concept.<Name>`, or an event's participant label `<Name>.<label>`. The steps mirror
        the element ones - package-qualified, same package, named import, wildcard import, then a unique
        match across the project. It never falls back to an element. Mirrors TS `Resolver.resolveCn`."""
        tried: list[ResolutionAttempt] = []
        parts = written[1:] if len(written) > 1 and written[0] == "cn" else written

        def lookup(step: ResolutionStep, key: str) -> SymbolEntry | None:
            symbol = self._cn_symbol(key)
            tried.append(ResolutionAttempt(step, key, None if symbol is not None else "unknown-symbol"))
            return symbol

        # Package-qualified (`hartland.core.Party`): a known package prefix that leaves a name.
        packages = self._symbols.packages()
        for i in range(len(parts) - 1, 0, -1):
            pkg = ".".join(parts[:i])
            if pkg not in packages:
                continue
            symbol = lookup("fully-qualified", _cn_key(pkg, parts[i:]))
            if symbol is not None:
                return Resolved(symbol, "fully-qualified")

        symbol = lookup("same-package", _cn_key(context.package_name, parts))
        if symbol is not None:
            return Resolved(symbol, "same-package")

        for imp in context.imports:
            if imp.wildcard:
                continue
            segs = imp.target.split(".")
            if len(segs) < 2 or segs[-1] != parts[0]:
                continue
            symbol = lookup("named-import", _cn_key(".".join(segs[:-1]), parts))
            if symbol is not None:
                return Resolved(symbol, "named-import")

        wildcard: list[SymbolEntry] = []
        for imp in context.imports:
            if not imp.wildcard:
                continue
            m = self._cn_symbol(_cn_key(imp.target, parts))
            if m is not None and all(w.qname.value != m.qname.value for w in wildcard):
                wildcard.append(m)
        if len(wildcard) > 1:
            tried.extend(ResolutionAttempt("wildcard-import", m.qname.value, "ambiguous") for m in wildcard)
            return Unresolved("ambiguous", tuple(tried), tuple(wildcard))
        if len(wildcard) == 1:
            tried.append(ResolutionAttempt("wildcard-import", wildcard[0].qname.value))
            return Resolved(wildcard[0], "wildcard-import")

        anywhere: list[SymbolEntry] = []
        for e in self._symbols.get_by_suffix(_cn_key("", parts)):
            if not _is_cn_symbol(e) or any(a.qname.value == e.qname.value for a in anywhere):
                continue
            anywhere.append(self._cn_symbol(e.qname.value) or e)
        if len(anywhere) == 1:
            tried.append(ResolutionAttempt("fully-qualified", anywhere[0].qname.value))
            return Resolved(anywhere[0], "fully-qualified")
        if len(anywhere) > 1:
            tried.extend(ResolutionAttempt("fully-qualified", m.qname.value, "ambiguous") for m in anywhere)
            return Unresolved("ambiguous", tuple(tried), tuple(anywhere))
        return Unresolved("not-found", tuple(tried))

    def _classified_tail(self, path: str) -> str:
        """The trailing name path with leading model/package/schema/kind segments
        stripped (← TS ``Resolver.classifiedTail`` / ``classifyReference``)."""
        segs = [s for s in path.split(".") if s]
        all_entries = self._symbols.get_all()
        packages = {e.package_name for e in all_entries if e.package_name}
        schemas = {"dbo"}
        for e in all_entries:
            parts = e.qname.value.split(".")
            if "db" in parts:
                k = parts.index("db")
                if k + 1 < len(parts):
                    schemas.add(parts[k + 1])
        i = 0
        model = schema = False
        if i < len(segs) and segs[i] in MODEL_CODES:
            i += 1
            model = True
        best = -1
        for j in range(i, len(segs)):
            if ".".join(segs[i : j + 1]) in packages:
                best = j
        if best >= i:
            i = best + 1
        if i < len(segs) and segs[i] in schemas:
            i += 1
            schema = True
        # The model may also follow the package (`pkg.er.entity.x`) - only when none led (TS
        # `classifyReference`; since 0.17 `cn` is a model code, and `db.dbo.cn.x` names a column `cn`).
        if not model and i < len(segs) and segs[i] in MODEL_CODES:
            i += 1
        # schema may follow the post-package model (`pkg.db.dbo.x`).
        if not schema and i < len(segs) and segs[i] in schemas:
            i += 1
        if i < len(segs) and segs[i] in _KIND_KEYWORDS:
            i += 1
        return ".".join(segs[i:])

    def resolve_bare_id(self, name: str, scope: LexicalScope) -> ResolutionResult:
        tried: list[ResolutionAttempt] = []

        if scope.enclosing is not None:
            with_enclosing = f"{scope.enclosing.qname}.{name}"
            symbol = self._symbols.get(with_enclosing)
            if symbol is not None:
                return Resolved(symbol, "lexical")
            tried.append(
                ResolutionAttempt("lexical", with_enclosing, "unknown-symbol")
            )

        with_schema = f"{scope.schema_code}.{scope.namespace}.{name}"
        enclosing_qname = scope.enclosing.qname if scope.enclosing else None
        if with_schema != enclosing_qname:
            symbol = self._symbols.get(with_schema)
            if symbol is not None:
                return Resolved(symbol, "same-package")
            tried.append(
                ResolutionAttempt("same-package", with_schema, "unknown-symbol")
            )

        cnc_qname = f"cnc.role.{name}"
        if cnc_qname != with_schema:
            symbol = self._symbols.get(cnc_qname)
            if symbol is not None:
                return Resolved(symbol, "auto-import")
            tried.append(ResolutionAttempt("auto-import", cnc_qname, "unknown-symbol"))

        return Unresolved("not-found", tuple(tried))
