# SPDX-License-Identifier: Apache-2.0
"""Explicit MD/ER validation, in the same order as the TS and Kotlin implementations."""
from __future__ import annotations

from dataclasses import dataclass

from ..diagnostics import DiagnosticCode, DiagnosticSeverity
from ..model import (
    CubeletDef,
    DimensionDef,
    IdValue,
    Md2erCubeletDef,
    Md2erDimensionDef,
    RelationDef,
    SourceLocation,
)
from .cn.groundings import CnDiagnostic, CnDocument
from .default_schema import build_canonical_key
from .resolver import ResolutionContext, Resolved, Resolver
from .symbol_table import SymbolEntry


def resolve_md_er_ref(resolver: Resolver, doc: CnDocument, path: str) -> SymbolEntry | None:
    if not path:
        return None
    result = resolver.resolve_reference(path, ResolutionContext(
        schema_code=doc.schema_code, namespace=doc.namespace, imports=doc.imports, package_name=doc.package_name,
    ))
    return result.symbol if isinstance(result, Resolved) else None


def _md_paths(path: str, ns: str) -> tuple[str, ...]:
    if not path:
        return ()
    parts = path.split(".")
    canonical = (path if len(parts) > 1 and parts[1] == ns else ".".join(("md", ns, *parts[1:]))) if parts[0] == "md" else f"md.{ns}.{path}"
    return tuple(dict.fromkeys((canonical, path)))


def resolve_md_ns(resolver: Resolver, doc: CnDocument, path: str, ns: str) -> SymbolEntry | None:
    for candidate in _md_paths(path, ns):
        symbol = resolve_md_er_ref(resolver, doc, candidate)
        if symbol and symbol.kind == ns and symbol.schema_code == "md":
            return symbol
    return None


def resolve_md_attribute(resolver: Resolver, doc: CnDocument, path: str) -> SymbolEntry | None:
    for candidate in _md_paths(path, "dimension"):
        symbol = resolve_md_er_ref(resolver, doc, candidate)
        if symbol and symbol.kind == "attribute" and symbol.schema_code == "md":
            return symbol
    return None


@dataclass(frozen=True)
class RelationLanding:
    to_entity_qname: str
    package_name: str
    qname: str


RelationIndex = dict[str, dict[str, list[RelationLanding]]]


def build_relation_index(docs: list[CnDocument], resolver: Resolver) -> RelationIndex:
    index: RelationIndex = {}
    for doc in docs:
        for rel in doc.definitions:
            if not isinstance(rel, RelationDef):
                continue
            origin = resolve_md_er_ref(resolver, doc, ".".join(rel.from_.parts) if isinstance(rel.from_, IdValue) else "")
            target = resolve_md_er_ref(resolver, doc, ".".join(rel.to.parts) if isinstance(rel.to, IdValue) else "")
            if not origin or origin.kind != "entity" or not target or target.kind != "entity":
                continue
            qname = build_canonical_key(doc.package_name, doc.namespace, "relation", [rel.name], doc.schema_code)
            index.setdefault(str(origin.qname), {}).setdefault(rel.name, []).append(RelationLanding(str(target.qname), doc.package_name, qname))
    return index


def explicit_er_attributes(index: RelationIndex, doc: CnDocument, resolver: Resolver, entity: SymbolEntry, path: str) -> list[SymbolEntry]:
    parts = path.split(".")
    if len(parts) == 1:
        attr = resolver.get_symbol(f"{entity.qname}.{path}")
        return [attr] if attr and attr.kind == "attribute" else []
    if len(parts) != 2:
        return []
    found: list[SymbolEntry] = []
    for rel in index.get(str(entity.qname), {}).get(parts[0], []):
        visible = rel.package_name == doc.package_name or any(
            imp.target.removesuffix(".*") == rel.package_name if imp.wildcard else imp.target == rel.qname for imp in doc.imports
        )
        if visible:
            attr = resolver.get_symbol(f"{rel.to_entity_qname}.{parts[1]}")
            if attr and attr.kind == "attribute":
                found.append(attr)
    return found


def md_er_binding_diagnostics(docs: list[CnDocument], resolver: Resolver) -> list[CnDiagnostic]:
    out: list[CnDiagnostic] = []
    seen: set[str] = set()
    index = build_relation_index(docs, resolver)
    by_uri = {doc.uri: doc for doc in docs}
    for doc in docs:
        for binding in doc.definitions:
            if not isinstance(binding, (Md2erCubeletDef, Md2erDimensionDef)):
                continue

            def report(code: DiagnosticCode, message: str, *, uri: str = doc.uri, source: SourceLocation = binding.source) -> None:
                out.append(CnDiagnostic(code, message, DiagnosticSeverity.ERROR, uri, source))

            er_path = binding.entity.path if binding.entity else ""
            entity = resolve_md_er_ref(resolver, doc, er_path)
            if entity and entity.kind != "entity":
                entity = None
            if isinstance(binding, Md2erCubeletDef):
                if not entity:
                    report(DiagnosticCode.MD_MD2ER_REFERENCE, f"Unresolved ER entity '{er_path}'")
                if binding.physical_props:
                    report(DiagnosticCode.MD_MD2ER_PHYSICAL_PROP, f"md2er_cubelet is structural-only; remove: {', '.join(binding.physical_props)}")
                path = binding.cubelet_ref.path if binding.cubelet_ref else ""
                cubelet = resolve_md_ns(resolver, doc, path, "cubelet")
                if not cubelet:
                    report(DiagnosticCode.MD_MD2ER_REFERENCE, f"Unresolved MD cubelet '{path}'" if path else "md2er_cubelet needs `cubelet:`")
                measures: set[str] | None = None
                if cubelet:
                    measures = set()
                    cubelet_doc = by_uri.get(cubelet.source_file)
                    logical_cubelet = cubelet.definition
                    if cubelet_doc and isinstance(logical_cubelet, CubeletDef):
                        for ref in logical_cubelet.measure_refs:
                            measure = resolve_md_ns(resolver, cubelet_doc, ref.path, "measure")
                            if measure:
                                measures.add(str(measure.qname))
                for key, attr in binding.measures.items():
                    measure = resolve_md_ns(resolver, doc, key, "measure")
                    if not measure:
                        report(DiagnosticCode.MD_MD2ER_REFERENCE, f"Unknown MD measure '{key}'")
                    elif measures is not None and str(measure.qname) not in measures:
                        report(DiagnosticCode.MD_MD2ER_REFERENCE, f"'{key}' is not a measure of cubelet '{path}'")
                    target = resolver.get_symbol(f"{entity.qname}.{attr}") if entity else None
                    if entity and (not target or target.kind != "attribute"):
                        report(DiagnosticCode.MD_MD2ER_MEASURE_UNKNOWN, f"Measure '{key}' names unknown attribute '{attr}'")
                for key, attr in binding.attributes.items():
                    if not resolve_md_attribute(resolver, doc, key):
                        report(DiagnosticCode.MD_MD2ER_REFERENCE, f"Unknown MD attribute '{key}'")
                    target = resolver.get_symbol(f"{entity.qname}.{attr}") if entity else None
                    if entity and (not target or target.kind != "attribute"):
                        report(DiagnosticCode.MD_MD2ER_REFERENCE, f"Unresolved ER attribute path '{attr}' from '{er_path}'")
                continue
            path = binding.dimension_ref.path if binding.dimension_ref else ""
            dim = resolve_md_ns(resolver, doc, path, "dimension")
            if not dim:
                report(DiagnosticCode.MD_MD2ER_REFERENCE, f"Unresolved MD dimension '{path}'")
            logical = dim.definition if dim and by_uri.get(dim.source_file) else None
            if isinstance(logical, DimensionDef) and logical.key and logical.key not in binding.attributes:
                report(DiagnosticCode.MD_MD2ER_DIMENSION_KEY, f"md2er_dimension must map key '{logical.key}'")
            if dim:
                if str(dim.qname) in seen:
                    report(DiagnosticCode.MD_DUPLICATE_BINDING, f"Duplicate md2er_dimension for '{path}'")
                seen.add(str(dim.qname))
            if not entity:
                report(DiagnosticCode.MD_MD2ER_REFERENCE, f"Unresolved ER entity '{er_path}'")
            for key, attr in binding.attributes.items():
                md_attr = resolver.get_symbol(f"{dim.qname}.{key}") if dim else None
                if dim and (not md_attr or md_attr.kind != "attribute"):
                    report(DiagnosticCode.MD_MD2ER_REFERENCE, f"'{key}' is not an attribute of '{path}'")
                if not entity:
                    continue
                targets = explicit_er_attributes(index, doc, resolver, entity, attr)
                if len(targets) > 1:
                    report(DiagnosticCode.MD_MD2ER_PATH_AMBIGUOUS, f"ER attribute path '{attr}' is ambiguous from '{er_path}'")
                if not targets:
                    report(DiagnosticCode.MD_MD2ER_REFERENCE, f"Unresolved ER attribute path '{attr}' from '{er_path}'")
    return out
