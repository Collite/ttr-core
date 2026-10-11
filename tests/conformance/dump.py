# SPDX-License-Identifier: Apache-2.0
"""Python side of the conformance harness (contracts.md §5).

Walks every shared top-level fixture in `tests/conformance/fixtures/` and writes
a normalised JSON dump to `tests/conformance/out-py/<fixture>.json`. The
output must be byte-identical to the committed TS golden at
`tests/conformance/out-ts/<fixture>.json`.

Normalisation rules (mirror `tests/conformance/dump.ts` and the Kotlin
`ConformanceDump.kt`):

- `kind` = the TTR keyword (lowercased), never the Python class name.
- Property keys = the TTR surface name (e.g. `primaryKey`, `valueLabels`),
  not the snake_case host field name. Mapping lives in
  `docs/grammar-master/AST-NAMING.md`.
- No `SourceLocation` anywhere.
- Object keys sorted alphabetically, recursively (the printed shape matches
  JS `JSON.stringify(value, null, 4)` byte-for-byte).
- Present-only properties: empty lists / objects / null strings are omitted
  from the dump.
- Whole-number floats render as integers (`1` not `1.0`).
- Numbers/bools/null are native JSON.

Local runs: `ttr_parser` must resolve to THIS repo's package, not a globally
installed / sibling-repo copy (a `modeler`-fork copy lacks newer defs and fails
with e.g. `ImportError: cannot import name 'EngineDef'`). Install editable first:
`pip install -e packages/python/ttr-parser` (or run under that package's venv).
CI's `py-vs-ts` job installs from this tree; only local runs hit the stale-path trap.
"""

from __future__ import annotations

import json
from collections.abc import Iterable, Mapping, Sequence
from pathlib import Path
from types import MappingProxyType
from typing import Any

import ttr_parser
from ttr_parser.model import (
    AreaDef,
    SchemaDef,
    AttributeDef,
    SecurityBlock,
    SecurityStatement,
    SemanticsBlock,
    EngineDef,
    ExecutorDef,
    StorageDef,
    WorldDef,
    TermDef,
    PatternDef,
    ExampleDef,
    LexiconBlock,
    WorldSchemaDef,
    BindingColumnBareId,
    BindingColumnEntry,
    BindingColumnObject,
    BindingProperty,
    BindingPropertyBareId,
    BindingPropertyBlock,
    BoolValue,
    ColumnDef,
    ConstraintDef,
    DataType,
    Definition,
    DrillMapDef,
    EntityDef,
    Er2CncRoleDef,
    Er2DbAttributeDef,
    Er2DbEntityDef,
    Er2DbRelationDef,
    FkDef,
    FunctionCall,
    IdValue,
    ImportStatement,
    IndexDef,
    ListValue,
    LocalizedStringListValue,
    LocalizedStringValue,
    ProjectDef,
    NullValue,
    NumberValue,
    ObjectValue,
    ParseResult,
    ProcedureDef,
    PropertyValue,
    QueryDef,
    Reference,
    RelationDef,
    RoleDef,
    ModelDirective,
    SearchHintsValue,
    StringValue,
    TableDef,
    TaggedBlockValue,
    TargetObjectValue,
    TargetReferenceValue,
    TargetValue,
    TripleStringValue,
    ViewDef,
)

REPO_ROOT = Path(__file__).resolve().parent.parent.parent
FIXTURES = REPO_ROOT / "tests" / "conformance" / "fixtures"
OUT_PY = REPO_ROOT / "tests" / "conformance" / "out-py"


KIND_KEYWORD: dict[str, str] = {
    "project": "project",
    "table": "table",
    "view": "view",
    "column": "column",
    "index": "index",
    "constraint": "constraint",
    "fk": "fk",
    "procedure": "procedure",
    "entity": "entity",
    "attribute": "attribute",
    "relation": "relation",
    "er2db_entity": "er2db_entity",
    "er2db_attribute": "er2db_attribute",
    "er2db_relation": "er2db_relation",
    "query": "query",
    "role": "role",
    "er2cnc_role": "er2cnc_role",
    "drill_map": "drill_map",
    "area": "area",
    "schema": "schema",
    "world": "world",
    "term": "term",
    "pattern": "pattern",
    "example": "example",
}


def dump(result: ParseResult) -> str:
    """Return the JSON-encoded normalised dump for `result`.

    Output format matches JS `JSON.stringify(value, null, 4)` so the byte
    representation is identical to the TS golden and the Kotlin dumper.
    """
    return json.dumps(dump_tree(result), sort_keys=True, ensure_ascii=False, indent=4) + "\n"


def dump_tree(result: ParseResult) -> dict[str, Any]:
    """Build the un-printed normalised tree (mostly for tests/debugging)."""
    sd: dict[str, Any] | None
    if result.model_directive is not None:
        sd = _schema_directive(result.model_directive)
    else:
        sd = None
    tree: dict[str, Any] = {
        "schemaDirective": sd,
        "package": result.package_name,
        "imports": [_import(i) for i in result.imports],
        "definitions": [_definition(d) for d in result.definitions],
    }
    # PL-P4.S3 — present-only, exactly as `dump.ts` emits it.
    if result.security_blocks:
        tree["securityBlocks"] = [_security_block(b) for b in result.security_blocks]
    return tree


def _security_block(b: SecurityBlock) -> dict[str, Any]:
    return {"statements": [_security_statement(st) for st in b.statements]}


def _security_statement(st: SecurityStatement) -> dict[str, Any]:
    """Per-verb projection — only the fields that verb carries, matching
    `securityStatementTree` in `dump.ts` field for field."""
    if st.verb == "own":
        return {"verb": "own", "objectRef": st.object_ref, "owner": st.owner}
    if st.verb == "classify":
        return {"verb": "classify", "objectRef": st.object_ref, "classification": st.classification}
    if st.verb == "grant":
        return {
            "verb": "grant",
            "privilege": st.privilege,
            "objectRef": st.object_ref,
            "grantee": st.grantee,
        }
    return {"verb": "mask", "objectRef": st.object_ref}


def _semantics(s: SemanticsBlock | None) -> dict[str, Any] | None:
    """Grounding Phase 1 — mirrors `semantics()` in `dump.ts`.

    Entries are emitted in sorted key order (the printer sorts, so this is
    for-the-reader) regardless of source ordering; `duplicateProperties` is
    present-only and sorted.
    """
    if s is None:
        return None
    out: dict[str, Any] = {"entries": {k: _sem_value(s.entries[k]) for k in sorted(s.entries)}}
    if s.duplicate_properties:
        out["duplicateProperties"] = sorted(s.duplicate_properties)
    return out


def _sem_value(v: Any) -> Any:
    """MS (vocabulary v3) — a semantics value may be a list or a nested object.

    LIST order is preserved: it is contract (the first `measures:` item is the default
    measure). Object keys are sorted at every depth, the same rule the block's own
    entries follow, so the three runtimes compare regardless of authored key order.
    Whole floats render as integers, matching JS `JSON.stringify(1.0)`.
    """
    if isinstance(v, bool):
        return v
    if isinstance(v, (int, float)):
        return _number(float(v))
    if isinstance(v, (list, tuple)):
        return [_sem_value(x) for x in v]
    if isinstance(v, Mapping):
        return {k: _sem_value(v[k]) for k in sorted(v)}
    return v


def _schema_directive(sd: ModelDirective) -> dict[str, Any]:
    out: dict[str, Any] = {"code": sd.model_code, "namespace": sd.schema}
    if sd.locale is not None:
        out["locale"] = sd.locale
    return out


def _import(i: ImportStatement) -> dict[str, Any]:
    return {"target": i.target, "wildcard": i.wildcard}


def _definition(d: Definition) -> dict[str, Any]:
    out: dict[str, Any] = {
        "kind": KIND_KEYWORD[d.kind],
        "name": d.name,
        "description": _description(d.description),
        "tags": list(d.tags),
        "properties": _properties(d),
    }
    # 0.13 (NLS-P10): the localised form of the SAME property, present-only (a
    # plain-string model dumps byte-identically to what 0.12 produced).
    dl = _localized(d.description_localized)
    if dl is not None:
        out["descriptionLocalized"] = dl
    return out


def _description(desc: str | None) -> str | None:
    """`description` on a def is a plain string.

    The walker unwraps the `PropertyValue` carrier (StringValue /
    TripleStringValue) to a bare `str` for ergonomics; the TS layer keeps
    the carrier but the conformance dump emits the bare string either way.
    Returns `None` when absent so JSON serialises as `null` (matches the
    golden's `"description": null` shape).
    """
    return None if desc is None else desc


def _properties(d: Definition) -> dict[str, Any]:
    """Per-kind property projection. Property keys = TTR surface names."""
    if isinstance(d, ProjectDef):
        return _model_props(d)
    if isinstance(d, TableDef):
        return _table_props(d)
    if isinstance(d, ViewDef):
        return _view_props(d)
    if isinstance(d, ColumnDef):
        return _column_props(d)
    if isinstance(d, IndexDef):
        return _index_props(d)
    if isinstance(d, ConstraintDef):
        return _constraint_props(d)
    if isinstance(d, FkDef):
        return _fk_props(d)
    if isinstance(d, ProcedureDef):
        return _procedure_props(d)
    if isinstance(d, EntityDef):
        return _entity_props(d)
    if isinstance(d, AttributeDef):
        return _attribute_props(d)
    if isinstance(d, RelationDef):
        return _relation_props(d)
    if isinstance(d, Er2DbEntityDef):
        return _er2db_entity_props(d)
    if isinstance(d, Er2DbAttributeDef):
        return _er2db_attribute_props(d)
    if isinstance(d, Er2DbRelationDef):
        return _er2db_relation_props(d)
    if isinstance(d, QueryDef):
        return _query_props(d)
    if isinstance(d, RoleDef):
        return _role_props(d)
    if isinstance(d, Er2CncRoleDef):
        return _er2cnc_role_props(d)
    if isinstance(d, DrillMapDef):
        return _drill_map_props(d)
    if isinstance(d, AreaDef):
        return _area_props(d)
    if isinstance(d, SchemaDef):
        return _schema_props(d)
    if isinstance(d, WorldDef):
        return _world_props(d)
    if isinstance(d, (TermDef, PatternDef, ExampleDef)):
        return _lexicon_entry_props(d)
    return {}


def _present(d: dict[str, Any], key: str, value: Any | None) -> None:
    """Set `key` on `d` only if `value` is present (matches present-only rule).

    `None` is present (serialised as `null`); an empty list/dict is *not*
    present (the property is omitted from the dump entirely).
    """
    if value is None:
        return
    if isinstance(value, (list, tuple)) and not value:
        return
    if isinstance(value, Mapping) and not value:
        return
    d[key] = value


def _model_props(d: ProjectDef) -> dict[str, Any]:
    p: dict[str, Any] = {}
    _present(p, "version", d.version)
    return p


def _table_props(d: TableDef) -> dict[str, Any]:
    p: dict[str, Any] = {}
    if d.primary_key:
        p["primaryKey"] = list(d.primary_key)
    if d.columns:
        p["columns"] = [_definition(c) for c in d.columns]
    if d.indices:
        p["indices"] = [_definition(i) for i in d.indices]
    if d.constraints:
        p["constraints"] = [_definition(c) for c in d.constraints]
    _present(p, "search", _search(d.search))
    _present(p, "semantics", _semantics(d.semantics))
    _present(p, "lexicon", _lexicon(d.lexicon))
    return p


def _view_props(d: ViewDef) -> dict[str, Any]:
    p: dict[str, Any] = {}
    if d.columns:
        p["columns"] = [_definition(c) for c in d.columns]
    if d.definition_sql is not None:
        p["definitionSql"] = _embedded(d.definition_sql)
    _present(p, "search", _search(d.search))
    return p


def _column_props(d: ColumnDef) -> dict[str, Any]:
    p: dict[str, Any] = {}
    if d.type is not None:
        p["type"] = _data_type(d.type)
    if d.optional:
        p["optional"] = True
    if d.is_key:
        p["isKey"] = True
    if d.indexed:
        p["indexed"] = True
    _present(p, "search", _search(d.search))
    _present(p, "semantics", _semantics(d.semantics))
    _present(p, "lexicon", _lexicon(d.lexicon))
    return p


def _index_props(d: IndexDef) -> dict[str, Any]:
    p: dict[str, Any] = {}
    _present(p, "indexType", d.index_type)
    if d.columns:
        p["columns"] = list(d.columns)
    return p


def _constraint_props(d: ConstraintDef) -> dict[str, Any]:
    p: dict[str, Any] = {}
    _present(p, "constraintType", d.constraint_type)
    if d.columns:
        p["columns"] = list(d.columns)
    return p


def _fk_props(d: FkDef) -> dict[str, Any]:
    p: dict[str, Any] = {}
    if d.from_ is not None:
        p["from"] = _pv(d.from_)
    if d.to is not None:
        p["to"] = _pv(d.to)
    return p


def _procedure_props(d: ProcedureDef) -> dict[str, Any]:
    p: dict[str, Any] = {}
    if d.parameters:
        p["parameters"] = [_param(pv) for pv in d.parameters]
    if d.result_columns:
        p["resultColumns"] = [_definition(c) for c in d.result_columns]
    return p


def _entity_props(d: EntityDef) -> dict[str, Any]:
    p: dict[str, Any] = {}
    _present(p, "labelPlural", d.label_plural)
    if d.name_attribute is not None:
        p["nameAttribute"] = _ref_path(d.name_attribute)
    if d.code_attribute is not None:
        p["codeAttribute"] = _ref_path(d.code_attribute)
    if d.aliases:
        p["aliases"] = list(d.aliases)
    if d.attributes:
        p["attributes"] = [_definition(a) for a in d.attributes]
    if d.roles:
        p["roles"] = [_ref_path(r) for r in d.roles]
    _present(p, "displayLabel", _localized(d.display_label))
    _present(p, "search", _search(d.search))
    _present(p, "semantics", _semantics(d.semantics))
    _present(p, "lexicon", _lexicon(d.lexicon))
    if d.binding is not None:
        p["binding"] = _binding(d.binding)
    return p


def _attribute_props(d: AttributeDef) -> dict[str, Any]:
    p: dict[str, Any] = {}
    if d.type is not None:
        p["type"] = _data_type(d.type)
    if d.is_key:
        p["isKey"] = True
    if d.optional:
        p["optional"] = True
    _present(p, "displayLabel", _localized(d.display_label))
    if d.value_labels:
        p["valueLabels"] = _value_labels(d.value_labels, d.value_label_aliases)
    _present(p, "search", _search(d.search))
    _present(p, "semantics", _semantics(d.semantics))
    _present(p, "lexicon", _lexicon(d.lexicon))
    if d.binding is not None:
        p["binding"] = _binding(d.binding)
    return p


def _relation_props(d: RelationDef) -> dict[str, Any]:
    p: dict[str, Any] = {}
    if d.from_ is not None:
        p["from"] = _pv(d.from_)
    if d.to is not None:
        p["to"] = _pv(d.to)
    if d.cardinality is not None:
        p["cardinality"] = _pv(d.cardinality)
    if d.join:
        p["join"] = [_pv(j) for j in d.join]
    _present(p, "search", _search(d.search))
    if d.binding is not None:
        p["binding"] = _binding(d.binding)
    return p


def _er2db_entity_props(d: Er2DbEntityDef) -> dict[str, Any]:
    p: dict[str, Any] = {}
    if d.entity is not None:
        p["entity"] = _ref_path(d.entity)
    if d.target is not None:
        p["target"] = _target(d.target)
    if d.where_filter is not None:
        p["whereFilter"] = _pv(d.where_filter)
    return p


def _er2db_attribute_props(d: Er2DbAttributeDef) -> dict[str, Any]:
    p: dict[str, Any] = {}
    if d.attribute is not None:
        p["attribute"] = _ref_path(d.attribute)
    if d.target is not None:
        p["target"] = _target(d.target)
    return p


def _er2db_relation_props(d: Er2DbRelationDef) -> dict[str, Any]:
    p: dict[str, Any] = {}
    if d.relation is not None:
        p["relation"] = _ref_path(d.relation)
    if d.fk is not None:
        p["fk"] = _ref_path(d.fk)
    return p


def _query_props(d: QueryDef) -> dict[str, Any]:
    p: dict[str, Any] = {}
    _present(p, "language", d.language)
    if d.parameters:
        p["parameters"] = [_param(pv) for pv in d.parameters]
    if d.source_text is not None:
        p["sourceText"] = _embedded(d.source_text)
    _present(p, "search", _search(d.search))
    return p


def _role_props(d: RoleDef) -> dict[str, Any]:
    p: dict[str, Any] = {}
    _present(p, "label", _localized(d.label))
    _present(p, "search", _search(d.search))
    return p


def _er2cnc_role_props(d: Er2CncRoleDef) -> dict[str, Any]:
    p: dict[str, Any] = {}
    if d.entity is not None:
        p["entity"] = _ref_path(d.entity)
    if d.role is not None:
        p["role"] = _ref_path(d.role)
    return p


def _drill_map_props(d: DrillMapDef) -> dict[str, Any]:
    p: dict[str, Any] = {}
    if d.from_ is not None:
        p["from"] = _ref_path(d.from_)
    if d.to is not None:
        p["to"] = _ref_path(d.to)
    if d.args:
        args: dict[str, Any] = {}
        for k, v in d.args.items():
            args[k] = v
        p["args"] = args
    _present(p, "display", _localized(d.display))
    if d.override_auto:
        p["override"] = True
    return p


def _area_props(d: AreaDef) -> dict[str, Any]:
    p: dict[str, Any] = {}
    if d.packages:
        p["packages"] = list(d.packages)
    if d.entities:
        p["entities"] = list(d.entities)
    return p


def _schema_props(d: SchemaDef) -> dict[str, Any]:
    """0.14 — a named row schema: its column list, dumped exactly like a view's."""
    p: dict[str, Any] = {}
    if d.columns:
        p["columns"] = [_definition(c) for c in d.columns]
    return p


def _world_props(d: WorldDef) -> dict[str, Any]:
    p: dict[str, Any] = {}
    if d.extends:
        p["extends"] = d.extends
    if d.engines:
        p["engines"] = [_engine_part_tree(e) for e in d.engines]
    if d.executors:
        p["executors"] = [_engine_part_tree(e) for e in d.executors]
    if d.storages:
        p["storages"] = [_storage_tree(s) for s in d.storages]
    return p


def _manifest_dump(m: Mapping[str, PropertyValue]) -> dict[str, Any]:
    return {k: _pv(v) for k, v in m.items()}


def _engine_part_tree(e: EngineDef | ExecutorDef) -> dict[str, Any]:
    m: dict[str, Any] = {"kind": e.kind, "name": e.name}
    if e.description is not None:
        m["description"] = e.description
    if e.tags:
        m["tags"] = list(e.tags)
    if e.type is not None:
        m["type"] = e.type
    if e.version is not None:
        m["version"] = e.version
    if e.extends is not None:
        m["extends"] = e.extends
    if e.manifest:
        m["manifest"] = _manifest_dump(e.manifest)
    return m


def _storage_tree(s: StorageDef) -> dict[str, Any]:
    m: dict[str, Any] = {"kind": s.kind, "name": s.name}
    if s.description is not None:
        m["description"] = s.description
    if s.tags:
        m["tags"] = list(s.tags)
    if s.type is not None:
        m["type"] = s.type
    if s.via is not None:
        m["via"] = s.via
    if s.hosts:
        m["hosts"] = list(s.hosts)
    if s.staging:
        m["staging"] = True
    if s.extends is not None:
        m["extends"] = s.extends
    if s.schemas:
        m["schemas"] = [_world_schema_tree(w) for w in s.schemas]
    if s.manifest:
        m["manifest"] = _manifest_dump(s.manifest)
    return m


def _world_schema_tree(w: WorldSchemaDef) -> dict[str, Any]:
    return {"kind": "schema", "name": w.name, "fields": {f.name: f.type for f in w.fields}}


def _ref_path(ref: Reference) -> str:
    return str(ref.path)


def _data_type(dt: DataType) -> dict[str, Any]:
    o: dict[str, Any] = {"name": dt.name}
    if dt.length is not None:
        o["length"] = dt.length
    if dt.precision is not None:
        o["precision"] = dt.precision
    return o


def _lexicon_entry_props(d: Any) -> dict[str, Any]:
    """v4.4 lexicon entry (term/pattern/example) — one shared body; emitted key
    for the target is `for` (the grammar keyword)."""
    p: dict[str, Any] = {}
    if d.target is not None:
        p["for"] = _ref_path(d.target)
    if d.forms:
        p["forms"] = list(d.forms)
    if d.match is not None:
        p["match"] = d.match
    if d.text is not None:
        p["text"] = d.text
    return p


def _lexicon(l: LexiconBlock | None) -> dict[str, Any] | None:
    """v4.4 inline `lexicon { … }` block — canonical shorthand keys, present-only."""
    if l is None:
        return None
    m: dict[str, Any] = {}
    if l.terms:
        m["terms"] = list(l.terms)
    if l.patterns:
        m["patterns"] = list(l.patterns)
    if l.examples:
        m["examples"] = list(l.examples)
    return m or None


def _search(s: SearchHintsValue | None) -> dict[str, Any] | None:
    if s is None:
        return None
    m: dict[str, Any] = {}
    if s.searchable:
        m["searchable"] = True
    if s.fuzzy:
        m["fuzzy"] = True
    # 0.12 (RV-32): the method is dumped as its authored surface text
    # (`TYPOS(2)`), which keeps the three targets byte-identical without
    # committing to a numeric encoding.
    if s.method is not None:
        arg = s.method.argument
        if arg is None:
            suffix = ""
        elif float(arg).is_integer():
            suffix = f"({int(arg)})"
        else:
            suffix = f"({arg})"
        m["method"] = f"{s.method.name}{suffix}"
    kw = _localized_list(s.keywords)
    if kw is not None:
        m["keywords"] = kw
    if s.patterns:
        m["patterns"] = list(s.patterns)
    desc = _localized_list(s.descriptions)
    if desc is not None:
        m["descriptions"] = desc
    if s.examples:
        m["examples"] = list(s.examples)
    if s.aliases:
        m["aliases"] = list(s.aliases)
    return m or None


def _localized(v: LocalizedStringValue | None) -> dict[str, Any] | None:
    if v is None or not v.by_language:
        return None
    return {k: v2 for k, v2 in v.by_language.items()}


def _localized_list(v: LocalizedStringListValue | None) -> dict[str, list[str]] | None:
    if v is None or not v.by_language:
        return None
    return {k: list(items) for k, items in v.by_language.items()}


def _value_labels(
    v: Mapping[str, LocalizedStringValue] | MappingProxyType[str, LocalizedStringValue],
    aliases: Mapping[str, tuple[str, ...]] | MappingProxyType[str, tuple[str, ...]],
) -> dict[str, Any]:
    # A4-β: an entry with `aliases` dumps the widened `{ label, aliases }` shape;
    # a legacy entry keeps the flat localized-label shape (present-only).
    out: dict[str, Any] = {}
    for k, ls in v.items():
        a = aliases.get(k)
        if a:
            out[k] = {"aliases": list(a), "label": (_localized(ls) or {})}
        else:
            out[k] = _localized(ls) or {}
    return out


def _param(pv: PropertyValue) -> dict[str, Any]:
    """Query/Procedure parameter projection.

    Parameters are `ObjectValue`s with `name`, `type`, `label`, `direction`
    entries (the walker records `type` as the type-name `IdValue`). The
    conformance dump emits the TS `ParameterDef` surface shape:
    `{name, type?: {name}, label?, direction?}` — strict: an unexpected
    key fails loudly (matches the Kotlin `param()` strict projection).
    """
    if not isinstance(pv, ObjectValue):
        raise ValueError(f"conformance: parameter must be an ObjectValue, got {type(pv).__name__}")
    m: dict[str, Any] = {}

    name = pv.entries.get("name")
    if not isinstance(name, IdValue):
        raise ValueError(f"conformance: parameter 'name' must be an id, got {type(name).__name__ if name else 'missing'}")
    m["name"] = name.parts[0] if name.parts else ""

    t = pv.entries.get("type")
    if t is not None:
        if not isinstance(t, IdValue):
            raise ValueError(f"conformance: parameter 'type' must be an id, got {type(t).__name__}")
        m["type"] = {"name": t.parts[0] if t.parts else ""}

    label = pv.entries.get("label")
    if label is not None:
        if not isinstance(label, StringValue):
            raise ValueError(f"conformance: parameter 'label' must be a string, got {type(label).__name__}")
        m["label"] = label.raw

    direction = pv.entries.get("direction")
    if direction is not None:
        if not isinstance(direction, IdValue):
            raise ValueError(f"conformance: parameter 'direction' must be an id, got {type(direction).__name__}")
        m["direction"] = direction.parts[0] if direction.parts else ""

    unexpected = set(pv.entries) - {"name", "type", "label", "direction"}
    if unexpected:
        raise ValueError(f"conformance: unexpected parameter key(s) {sorted(unexpected)}")
    return m


def _binding(m: BindingProperty) -> dict[str, Any]:
    if isinstance(m, BindingPropertyBareId):
        if m.id is None:
            raise ValueError("conformance: BindingPropertyBareId missing id")
        return {"id": _ref_path(m.id), "kind": "bareId"}
    if isinstance(m, BindingPropertyBlock):
        o: dict[str, Any] = {"kind": "block"}
        if m.target is not None:
            o["target"] = _target(m.target)
        if m.columns:
            o["columns"] = [_binding_column(c) for c in m.columns]
        if m.fk is not None:
            o["fk"] = _ref_path(m.fk)
        return o
    raise ValueError(f"conformance: unknown binding variant {type(m).__name__}")


def _binding_column(e: BindingColumnEntry) -> dict[str, Any]:
    if isinstance(e.value, BindingColumnBareId):
        if e.value.id is None:
            raise ValueError("conformance: BindingColumnBareId missing id")
        return {"name": e.name, "value": {"id": _ref_path(e.value.id), "kind": "bareId"}}
    if isinstance(e.value, BindingColumnObject):
        if e.value.obj is None:
            raise ValueError("conformance: BindingColumnObject missing obj")
        return {"name": e.name, "value": {"kind": "object", "object": _pv(e.value.obj)}}
    raise ValueError(f"conformance: unknown binding column variant {type(e.value).__name__}")


def _target(t: TargetValue) -> Any:
    if isinstance(t, TargetObjectValue):
        if t.obj is None:
            raise ValueError("conformance: TargetObjectValue missing obj")
        return _pv(t.obj)
    if isinstance(t, TargetReferenceValue):
        if t.ref is None:
            raise ValueError("conformance: TargetReferenceValue missing ref")
        return _ref_path(t.ref)
    raise ValueError(f"conformance: unknown target variant {type(t).__name__}")


def _pv(v: PropertyValue) -> dict[str, Any]:
    """Normalise a `PropertyValue` to the §5 discriminator shape."""
    if isinstance(v, StringValue):
        return {"kind": "string", "value": v.raw}
    if isinstance(v, TripleStringValue):
        return {"kind": "tripleString", "value": v.raw}
    if isinstance(v, TaggedBlockValue):
        embedded = _embedded(v)
        if not isinstance(embedded, dict):
            raise ValueError(f"conformance: tagged block must serialise to object, got {type(embedded).__name__}")
        return embedded
    if isinstance(v, NumberValue):
        return {"kind": "number", "value": _number(v.raw)}
    if isinstance(v, BoolValue):
        return {"kind": "bool", "value": v.raw}
    if isinstance(v, NullValue):
        return {"kind": "null"}
    if isinstance(v, IdValue):
        if v.ref is None:
            raise ValueError("conformance: IdValue missing ref")
        return {"kind": "id", "parts": list(v.parts), "path": str(v.ref.path)}
    if isinstance(v, ListValue):
        return {"items": [_pv(item) for item in v.items], "kind": "list"}
    if isinstance(v, ObjectValue):
        return {
            "entries": {k: _pv(val) for k, val in v.entries.items()},
            "kind": "object",
        }
    if isinstance(v, FunctionCall):
        return {
            "args": [_pv(arg) for arg in v.args],
            "kind": "functionCall",
            "name": v.name,
        }
    raise ValueError(f"conformance: unknown PropertyValue variant {type(v).__name__}")


def _embedded(v: PropertyValue) -> dict[str, Any] | str:
    """Serialise a `sourceText` / `definitionSql` value (embedded-sql §6.1)."""
    if isinstance(v, TaggedBlockValue):
        out: dict[str, Any] = {
            "dialect": v.dialect,
            "kind": "taggedBlock",
            "language": v.language,
            "tag": v.tag,
            "value": v.value,
        }
        return out
    if isinstance(v, StringValue):
        return str(v.raw)
    if isinstance(v, TripleStringValue):
        return str(v.raw)
    raise ValueError(f"conformance: embeddedDump unexpected {type(v).__name__}")


def _number(n: float) -> int | float:
    """Render whole floats as integers — matches JS `JSON.stringify(1.0)`."""
    import math
    if math.isfinite(n) and n == int(n):
        return int(n)
    return n


def main() -> int:
    """CLI entry point: walks every top-level fixture and writes `out-py/`."""
    OUT_PY.mkdir(parents=True, exist_ok=True)
    files = sorted(p for p in FIXTURES.iterdir() if p.is_file() and p.suffix == ".ttrm")
    rc = 0
    for f in files:
        result = ttr_parser.parse_file(f)
        fatal = [e for e in result.errors if e.code.value == "ttr/parse-error"]
        if fatal:
            print(f"x {f.name}: parse errors")
            for e in fatal:
                print(f"    {e}")
            rc = 1
            continue
        text = dump(result)
        out_path = OUT_PY / f.with_suffix(".json").name
        out_path.write_text(text, encoding="utf-8")
        print(f"  wrote {out_path.relative_to(REPO_ROOT)}")
    print(f"dumped {len(files)} fixtures to {OUT_PY.relative_to(REPO_ROOT)}/")
    return rc


def walk_typed_fixtures() -> Iterable[tuple[Path, ParseResult]]:
    """Iterate `(fixture_path, ParseResult)` pairs (used by the test harness)."""
    for p in sorted(FIXTURES.iterdir()):
        if p.is_file() and p.suffix == ".ttrm":
            yield p, ttr_parser.parse_file(p)


def list_fixtures() -> Sequence[str]:
    """Return the sorted list of top-level `.ttr` fixture basenames (no extension)."""
    return sorted(p.stem for p in FIXTURES.iterdir() if p.is_file() and p.suffix == ".ttrm")


if __name__ == "__main__":
    raise SystemExit(main())
