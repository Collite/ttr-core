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


@dataclass(frozen=True)
class _Slots:
    """A dotted reference (or a canonical key) read into its slots; `tail` is the trailing name path."""

    package: str | None
    model: str | None
    schema: str | None
    kind: str | None
    tail: str


# Kind segments that may lead a written reference (camelCase + MD aliases).
_KIND_KEYWORDS: frozenset[str] = frozenset(
    {
        "entity", "attribute", "relation", "table", "view", "column", "index",
        "constraint", "fk", "procedure", "query", "drillMap", "role", "project",
        "area", "er2dbEntity", "er2dbAttribute", "er2dbRelation", "er2cncRole",
        "domain", "map", "dimension", "hierarchy", "measure", "cubelet",
        "md2db_cubelet", "md2db_domain", "md2db_map", "md2er_cubelet", "md2er_dimension",
    }
)

MdReferenceRole = Literal["domain", "map", "dimension", "measure", "hierarchy", "grain"]

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
    def __init__(self, symbols: SymbolTable, root: str = "") -> None:
        self._symbols = symbols
        self._root = root
        #: `_vocab` for a symbol-table version - a whole-table scan, so once per change
        self._vocab_cache: tuple[int, tuple[set[str], set[str]]] | None = None

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
        self, ref: str, context: ResolutionContext, *, cn_context: bool = False,
        md_context: MdReferenceRole | None = None,
    ) -> ResolutionResult:
        """Resolve a dotted reference. `cn_context` marks a reference from a cn slot
        (`CollectedRef.cn_context`): it - and any `cn.`-headed reference - resolves among concepts only.
        `md_context` preserves an MD reference's target namespace from its originating slot."""
        path = ref
        parts = path.split(".")
        if md_context is not None:
            return self._resolve_md(path, context, md_context)
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
            matches = [
                e for e in self._elements_by_package(context.package_name)
                if e.name == path and e.qname.value not in (enclosing_candidate, full_qname)
            ]
            same_model = [e for e in matches if e.schema_code == context.schema_code]
            pool = same_model or matches
            top_level = [e for e in pool if e.parent is None]
            candidates = sorted(top_level or pool, key=lambda e: e.qname.value)
            if len(candidates) == 1:
                return Resolved(candidates[0], "same-package")
            if len(candidates) > 1:
                tried.extend(ResolutionAttempt("same-package", e.qname.value, "ambiguous") for e in candidates)
                return Unresolved("ambiguous", tuple(tried), tuple(candidates))

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
        # explicitly (`db.dbo.Orders` → `…db.dbo.table.Orders`) resolves by suffix. An
        # exact key wins ahead of the suffix match (TS: `er.entity.store` is the root
        # package's).
        tail = self._classified_tail(path)
        forms = [path, tail] if tail and tail != path else [path]
        for cand in forms:
            exact = self._element(cand)
            if exact is not None and exact.qname.value not in (full_qname, cnc_qname):
                tried.append(ResolutionAttempt("fully-qualified", exact.qname.value))
                return Resolved(exact, "fully-qualified")
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

        # Step 7 — the slot-filling fallback (TS `slotResolveReference`, qname-redesign §5;
        # ttr-core#228): read the reference into its slots and look for its name path in the
        # reference's package and model (its kind too, when written; for db its schema -
        # written, else the file's), then in its model, then anywhere. The first scope with one
        # candidate wins; a scope with several is ambiguous. So `er.entity.store` in
        # `hartland.west` names west's `store` beside `hartland.east`'s (D10 "current package
        # first"), `md.revenue` the md measure beside a lexicon term, and `db.Orders.x` in a
        # `schema dbo` file dbo's `Orders`. TS also reads the manifest's default schemas; this
        # resolver has no manifest.
        vocab = self._vocab()
        written = self._classify(path, vocab)
        keyed: dict[str, tuple[SymbolEntry, _Slots]] = {}
        for e in self._elements_by_suffix(written.tail):
            if e.qname.value in (full_qname, cnc_qname) or e.qname.value in keyed:
                continue
            slots = self._classify(e.qname.value, vocab)
            if slots.tail == written.tail:
                keyed[e.qname.value] = (e, slots)
        pkg = written.package or context.package_name or ""
        schema = (written.schema or context.namespace or None) if written.model == "db" else None

        def in_model(s: _Slots) -> bool:
            return written.model is None or s.model == written.model

        def in_package(s: _Slots) -> bool:
            return (
                in_model(s)
                and (s.package or "") == pkg
                and (schema is None or s.schema == schema)
            )

        scopes = (
            lambda s: in_package(s) and (written.kind is None or s.kind == written.kind),
            in_package,
            in_model,
            lambda s: True,
        )
        for scope in scopes:
            candidates = [e for e, s in keyed.values() if scope(s)]
            if len(candidates) == 1:
                tried.append(ResolutionAttempt("fully-qualified", candidates[0].qname.value))
                return Resolved(candidates[0], "fully-qualified")
            if len(candidates) > 1:
                for c in candidates:
                    tried.append(ResolutionAttempt("fully-qualified", c.qname.value, "ambiguous"))
                return Unresolved("ambiguous", tuple(tried), tuple(candidates))

        return Unresolved("not-found", tuple(tried))

    def _root_variants(self, path: str) -> list[str]:
        if not self._root:
            return [path]
        if path == self._root or path.startswith(self._root + "."):
            variant = path[len(self._root):].removeprefix(".")
        else:
            variant = f"{self._root}.{path}"
        return list(dict.fromkeys(p for p in (path, variant) if p))

    def _md_symbol(self, path: str, role: MdReferenceRole, package: str = "") -> SymbolEntry | None:
        """Role-aware namespace sugar and unique package suffix lookup (TS `resolveMdRef`)."""
        namespaces = ["dimension" if role == "grain" else role]
        if role == "domain" and "." in path and not path.startswith("md."):
            namespaces.append("dimension")
        for ns in namespaces:
            parts = path.split(".")
            model = max((i for i, p in enumerate(parts) if p == "md"), default=-1)
            canonical = (
                ".".join([*parts[:model + 1], ns, *parts[model + 1:]])
                if model >= 0 else f"md.{ns}.{path}"
            )
            already_canonical = model >= 0 and parts[model + 1:model + 2] == [ns]
            candidates = list(dict.fromkeys([path, canonical] if already_canonical else [canonical]))
            if package:
                for candidate in candidates:
                    if (hit := self._symbols.get(f"{package}.{candidate}")) is not None:
                        return hit
            for candidate in candidates:
                if (hit := self._symbols.get(candidate)) is not None:
                    return hit
            matches = {e.qname.value: e for e in self._symbols.get_by_suffix(candidates[-1])}
            if len(matches) == 1:
                return next(iter(matches.values()))
        return None

    def _resolve_md(self, path: str, context: ResolutionContext, role: MdReferenceRole) -> ResolutionResult:
        fallback: SymbolEntry | None = None
        for variant in self._root_variants(path):
            symbol = self._md_symbol(variant, role, context.package_name)
            if symbol is None:
                continue
            if symbol.package_name == context.package_name or "." in path:
                return Resolved(symbol, "fully-qualified" if "." in path else "same-package")
            if fallback is None:
                fallback = symbol
        for imp in context.imports:
            if imp.wildcard or not imp.target.endswith(f".{path}"):
                continue
            for variant in self._root_variants(imp.target):
                if (symbol := self._md_symbol(variant, role)) is not None:
                    return Resolved(symbol, "named-import")
        wildcard_matches: dict[str, SymbolEntry] = {}
        for imp in context.imports:
            if not imp.wildcard:
                continue
            for pkg in self._root_variants(imp.target):
                symbol = self._md_symbol(path, role, pkg)
                if symbol is not None and symbol.package_name == pkg:
                    wildcard_matches[symbol.qname.value] = symbol
        candidates = sorted(wildcard_matches.values(), key=lambda e: e.qname.value)
        if len(candidates) == 1:
            return Resolved(candidates[0], "wildcard-import")
        if len(candidates) > 1:
            return Unresolved(
                "ambiguous",
                tuple(ResolutionAttempt("wildcard-import", e.qname.value, "ambiguous") for e in candidates),
                tuple(candidates),
            )
        if fallback is not None:
            return Resolved(fallback, "fully-qualified")
        return Unresolved("not-found", (ResolutionAttempt("fully-qualified", path, "unknown-symbol"),))

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

    def _vocab(self) -> tuple[set[str], set[str]]:
        """The project's packages and db schemas - what `_classify` reads a dotted path against."""
        version = self._symbols.version
        if self._vocab_cache is not None and self._vocab_cache[0] == version:
            return self._vocab_cache[1]
        all_entries = self._symbols.get_all()
        packages = {e.package_name for e in all_entries if e.package_name}
        schemas = {"dbo"}
        for e in all_entries:
            parts = e.qname.value.split(".")
            if "db" in parts:
                k = parts.index("db")
                if k + 1 < len(parts):
                    schemas.add(parts[k + 1])
        self._vocab_cache = (version, (packages, schemas))
        return packages, schemas

    def _classify(self, path: str, vocab: tuple[set[str], set[str]] | None = None) -> _Slots:
        """Read a dotted reference (or a canonical key) into its slots: the leading
        model/package/schema/kind segments (D4 slot order) and the trailing name path
        (← TS ``classifyReference``)."""
        packages, schemas = vocab if vocab is not None else self._vocab()
        segs = [s for s in path.split(".") if s]
        i = 0
        model = pkg = schema = kind = None
        if i < len(segs) and segs[i] in MODEL_CODES:
            model = segs[i]
            i += 1
        best = -1
        for j in range(i, len(segs)):
            if ".".join(segs[i : j + 1]) in packages:
                best = j
        if best >= i:
            pkg = ".".join(segs[i : best + 1])
            i = best + 1
        if i < len(segs) and segs[i] in schemas:
            schema = segs[i]
            i += 1
        # The model may also follow the package (`pkg.er.entity.x`) - only when none led (TS
        # `classifyReference`; since 0.17 `cn` is a model code, and `db.dbo.cn.x` names a column `cn`).
        if model is None and i < len(segs) and segs[i] in MODEL_CODES:
            model = segs[i]
            i += 1
        # schema may follow the post-package model (`pkg.db.dbo.x`).
        if schema is None and i < len(segs) and segs[i] in schemas:
            schema = segs[i]
            i += 1
        if i < len(segs) and segs[i] in _KIND_KEYWORDS:
            kind = segs[i]
            i += 1
        return _Slots(pkg, model, schema, kind, ".".join(segs[i:]))

    def _classified_tail(self, path: str) -> str:
        """The trailing name path with leading model/package/schema/kind segments
        stripped (← TS ``Resolver.classifiedTail`` / ``classifyReference``)."""
        return self._classify(path).tail

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
