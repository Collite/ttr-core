# SPDX-License-Identifier: Apache-2.0
"""Default schema/namespace derivation (← `default-schema.ts`).

Applied only when a file has no explicit `schema` directive (kind → schema) or
no explicit `namespace` (schema → namespace). An explicit directive always wins.
Kept identical to the TS twin so the symbol table builds byte-identical qnames
(pinned by the §5.1 conformance dump).

`kind` here is the **Python** `Definition.kind` string (snake_case, e.g.
`er2db_entity`) — see `default_schema_for_kind`. The qname *namespace-fallback*
segment, however, must match the TS camelCase form; that snake→camel mapping
lives in `symbol_table._kind_segment`, not here.
"""

from __future__ import annotations


def default_namespace_for_schema(schema_code: str) -> str:
    """`db` → `dbo`; every other schema → '' (caller keeps the per-kind fallback)."""
    return "dbo" if schema_code == "db" else ""


def default_schema_for_kind(kind: str, declaring_model: str = "") -> str:
    """Deprecated alias of :func:`model_for_kind` — the single kind→model source of
    truth (D4/D14/D15). Applied only when a file has no explicit ``schema``
    directive. ``query``/``drill_map`` → ``db`` (D14, not the retired ``query``
    value). Mirrors the TS twin, where ``defaultSchemaForKind`` aliases
    ``modelForKind``."""
    return model_for_kind(kind, declaring_model)


# The TS/Kotlin `def.kind` is camelCase (`er2dbEntity`); the Python model uses
# snake_case (`er2db_entity`). Qnames and the enclosing-qname namespace-fallback
# segment must use the camelCase form so they are byte-identical to the §5.1
# golden (e.g. `binding.er2dbEntity.x`, and `kindOf` membership in `references`).
_KIND_SEGMENT: dict[str, str] = {
    "er2db_entity": "er2dbEntity",
    "er2db_attribute": "er2dbAttribute",
    "er2db_relation": "er2dbRelation",
    "er2cnc_role": "er2cncRole",
    "drill_map": "drillMap",
}


def kind_segment(kind: str) -> str:
    """The camelCase (TS) form of a Python snake_case `Definition.kind`."""
    return _KIND_SEGMENT.get(kind, kind)


# ---------------------------------------------------------------------------
# v4.0 uniform key (← `qname.ts` modelForKind / namespaceForKind / buildCanonicalKey)
# ---------------------------------------------------------------------------

MODEL_CODES: frozenset[str] = frozenset({"db", "er", "md", "binding", "cnc", "world", "automation"})

_MODEL_BY_KIND: dict[str, str] = {
    "entity": "er",
    "attribute": "er",
    "relation": "er",
    "er2db_entity": "binding",
    "er2db_attribute": "binding",
    "er2db_relation": "binding",
    "md2db_cubelet": "binding",
    "md2db_domain": "binding",
    "md2db_map": "binding",
    "md2er_cubelet": "binding",
    "role": "cnc",
    "er2cnc_role": "cnc",
    "md_domain": "md",
    "dimension": "md",
    "md_map": "md",
    "hierarchy": "md",
    "measure": "md",
    "cubelet": "md",
    "world": "world",
    "engine": "world",
    "executor": "world",
    "storage": "world",
    "worldSchema": "world",
    # 0.16 — the automation world members nest in a world; the automation kinds are `automation`.
    "identity": "world",
    "deployment": "world",
    "process": "world",
    "parameter": "automation",
    "action": "automation",
    "service": "automation",
    "approval": "automation",
    "program": "automation",
    "recipe": "automation",
}


def model_for_kind(kind: str, declaring_model: str = "") -> str:
    """Kind → model layer (D14/D15). `query`/`drill_map` and everything not
    explicitly mapped → `db`, except a `query` declared in a `model er` file
    (``declaring_model`` = the file's `model` directive code): a logical query
    → `er` (LQ L-1). Mirrors TS `modelForKind`."""
    if kind == "query" and declaring_model == "er":
        return "er"
    # 0.16 — a `def role` in a `model automation` file is an automation role, not a cnc role.
    if kind == "role" and declaring_model == "automation":
        return "automation"
    return _MODEL_BY_KIND.get(kind, "db")


_NAMESPACE_BY_KIND: dict[str, str] = {
    "md_domain": "domain",
    "md_map": "map",
    "dimension": "dimension",
    "hierarchy": "hierarchy",
    "measure": "measure",
    "cubelet": "cubelet",
    "md2db_cubelet": "md2db_cubelet",
    "md2db_domain": "md2db_domain",
    "md2db_map": "md2db_map",
    "md2er_cubelet": "md2er_cubelet",
}


def namespace_for_kind(kind: str) -> str:
    """The kind-segment namespace alias (MD kinds), else '' (caller keeps the
    camelCase kind segment). Mirrors TS `namespaceForKind`."""
    return _NAMESPACE_BY_KIND.get(kind, "")


def build_canonical_key(
    package_name: str,
    schema_id: str,
    kind: str,
    parts: list[str],
    declaring_model: str = "",
) -> str:
    """The single v4.0 uniform-key builder
    `<package>.<model>.<schema?>.<kind>.<parts>` (← TS `buildCanonicalKey`):
    model from the kind, schema slot db-only (default `dbo`), kind segment via
    the namespace alias else the camelCase kind."""
    model = model_for_kind(kind, declaring_model)
    segments: list[str] = []
    if package_name:
        segments.append(package_name)
    segments.append(model)
    if model == "db":
        segments.append(schema_id or "dbo")
    segments.append(namespace_for_kind(kind) or kind_segment(kind))
    segments.extend(parts)
    return ".".join(segments)
