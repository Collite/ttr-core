# SPDX-License-Identifier: Apache-2.0
"""test_loader.py — port of TtrLoaderSpec + ParseDirectorySpec.

Mirrors `packages/kotlin/ttr-parser/src/test/kotlin/.../loader/TtrLoaderSpec.kt`
and `ParseDirectorySpec.kt`. The walker/loader don't exist yet (P2.2/2.3),
so these tests will fail with ImportError until they do — that is the
expected red state at the end of stage 2.1.

Public API exercised (contracts §2):
- `ttr_parser.parse_string(content, file_label="<inline>") -> ParseResult`
- `ttr_parser.parse_file(path) -> ParseResult`
- `ttr_parser.parse_directory(root, recursive=True) -> list[ParseResult]`
- `ParseResult.definitions`, `.model_directive`, `.errors`, `.warnings`,
  `.package_name`, `.imports`, `.ok`, `.source_file`
- `ParseError.file`, `.line` (1-indexed), `.column` (1-indexed, display),
  `.message`, `.code`
- `Definition` subtypes with `.kind`, `.name`, `.source`, `.description`,
  `.tags`, plus kind-specific fields per contracts §2.5
"""

from __future__ import annotations

from pathlib import Path

import pytest

import ttr_parser
from ttr_parser import (  # noqa: F821 — these names will exist once stage 2.2 lands
    AttributeDef,
    BindingColumnBareId,
    BindingColumnObject,
    BindingPropertyBlock,
    ColumnDef,
    ConstraintDef,
    Definition,
    DrillMapDef,
    EntityDef,
    Er2CncRoleDef,
    Er2DbAttributeDef,
    Er2DbEntityDef,
    Er2DbRelationDef,
    FkDef,
    IndexDef,
    ParseError,
    ParseResult,
    ProcedureDef,
    ProjectDef,
    QueryDef,
    RelationDef,
    RoleDef,
    TableDef,
    TargetObjectValue,
    ViewDef,
)

FIXTURES = Path(__file__).parent / "fixtures"


def _read(name: str) -> str:
    return (FIXTURES / name).read_text(encoding="utf-8")


# --- One definition per kind — covers contracts §2.5 (table 18 rows) ---


EXPECTED = [
    ("01-model.ttrm", "project", ProjectDef, "erp_v1"),
    ("02-table.ttrm", "table", TableDef, "customers"),
    ("03-view.ttrm", "view", ViewDef, "active_customers"),
    ("04-column.ttrm", "column", ColumnDef, "total"),
    ("05-index.ttrm", "index", IndexDef, "ix_customers_name"),
    ("06-constraint.ttrm", "constraint", ConstraintDef, "uq_customers_email"),
    ("07-fk.ttrm", "fk", FkDef, "fk_orders_customer"),
    ("08-procedure.ttrm", "procedure", ProcedureDef, "sp_archive"),
    ("09-entity.ttrm", "entity", EntityDef, "Customer"),
    ("10-attribute.ttrm", "attribute", AttributeDef, "stav"),
    ("11-relation.ttrm", "relation", RelationDef, "rel_a_b"),
    ("12-er2db-entity.ttrm", "er2db_entity", Er2DbEntityDef, "artikl_to_qbozi"),
    ("13-er2db-attribute.ttrm", "er2db_attribute", Er2DbAttributeDef, "artikl_id"),
    ("14-er2db-relation.ttrm", "er2db_relation", Er2DbRelationDef, "rel_a_b_to_fk"),
    ("15-query.ttrm", "query", QueryDef, "topCustomers"),
    ("16-role.ttrm", "role", RoleDef, "fact"),
    ("17-er2cnc-role.ttrm", "er2cnc_role", Er2CncRoleDef, "objednavka_is_fact"),
    ("18-drill-map.ttrm", "drill_map", DrillMapDef, "reason_to_return_detail"),
]


@pytest.mark.parametrize(
    ("fixture", "kind", "cls", "expected_name"),
    [(name, kind, cls, n) for name, kind, cls, n in EXPECTED],
    ids=[name for name, *_ in EXPECTED],
)
def test_parse_string_yields_expected_kind_and_name(
    fixture: str, kind: str, cls: type, expected_name: str
) -> None:
    result = ttr_parser.parse_string(_read(fixture), file_label=fixture)
    assert isinstance(result, ParseResult)
    assert result.ok, f"expected ok, got errors={result.errors}"
    assert result.errors == ()
    assert len(result.definitions) == 1
    d = result.definitions[0]
    assert isinstance(d, Definition)
    assert d.kind == kind
    assert type(d) is cls
    assert d.name == expected_name


def test_parse_string_empty_document() -> None:
    r = ttr_parser.parse_string("")
    assert r.ok
    assert r.definitions == ()
    assert r.model_directive is None
    assert r.errors == ()
    assert r.warnings == ()
    assert r.package_name is None
    assert r.imports == ()


def test_parse_string_model_with_description_version_tags() -> None:
    r = ttr_parser.parse_string(_read("01-model.ttrm"))
    assert r.ok
    m = r.definitions[0]
    assert isinstance(m, ProjectDef)
    assert m.description == "ERP v1 model"
    assert m.version == "1.0.0"
    assert m.tags == ("v1", "erp")


def test_parse_string_table_with_schema_directive_and_inline_columns() -> None:
    r = ttr_parser.parse_string(_read("02-table.ttrm"))
    assert r.ok
    assert r.model_directive is not None
    assert r.model_directive.model_code == "db"
    assert r.model_directive.schema == "dbo"
    t = r.definitions[0]
    assert isinstance(t, TableDef)
    assert t.primary_key == ("id",)
    assert len(t.columns) == 2
    assert t.columns[0].name == "id"
    assert t.columns[0].type is not None
    assert t.columns[0].type.name == "int"
    assert t.columns[0].is_key is True
    assert t.columns[1].type.name == "text"


def test_parse_string_structured_data_type_with_length_precision() -> None:
    r = ttr_parser.parse_string(_read("04-column.ttrm"))
    assert r.ok
    c = r.definitions[0]
    assert isinstance(c, ColumnDef)
    assert c.type is not None
    assert c.type.name == "decimal"
    assert c.type.length == 19
    assert c.type.precision == 5


def test_parse_string_entity_with_inline_attributes() -> None:
    r = ttr_parser.parse_string(_read("09-entity.ttrm"))
    assert r.ok
    e = r.definitions[0]
    assert isinstance(e, EntityDef)
    assert e.label_plural == "Customers"
    assert e.name_attribute is not None
    assert e.name_attribute.path == "name"
    assert e.aliases == ("client", "buyer")
    assert len(e.attributes) == 2
    assert e.attributes[0].is_key is True


def test_parse_string_role_with_localized_label() -> None:
    r = ttr_parser.parse_string(_read("16-role.ttrm"))
    assert r.ok
    role = r.definitions[0]
    assert isinstance(role, RoleDef)
    assert role.label is not None
    assert role.label.by_language["cs"] == "Faktová entita"
    assert role.label.by_language["en"] == "Fact entity"


def test_parse_string_attribute_with_value_labels() -> None:
    r = ttr_parser.parse_string(_read("10-attribute.ttrm"))
    assert r.ok
    a = r.definitions[0]
    assert isinstance(a, AttributeDef)
    assert set(a.value_labels.keys()) == {"1", "2"}
    assert a.value_labels["1"].by_language["cs"] == "Aktivní"
    assert a.value_labels["2"].by_language["en"] == "Inactive"
    assert a.display_label is not None
    assert a.display_label.by_language["cs"] == "Stav"


def test_parse_string_er2cnc_role_long_form() -> None:
    r = ttr_parser.parse_string(_read("17-er2cnc-role.ttrm"))
    assert r.ok
    m = r.definitions[0]
    assert isinstance(m, Er2CncRoleDef)
    assert m.entity is not None
    assert m.entity.path == "er.entity.objednavka"
    assert m.role is not None
    assert m.role.path == "cnc.cnc.role.fact"


def test_parse_string_drill_map_full() -> None:
    r = ttr_parser.parse_string(_read("18-drill-map.ttrm"))
    assert r.ok
    assert r.package_name == "returns"
    d = r.definitions[0]
    assert isinstance(d, DrillMapDef)
    assert d.from_ is not None
    assert d.from_.path == "query.query.returns_by_reason"
    assert d.to is not None
    assert d.to.path == "query.query.return_detail"
    assert d.args == {"order_number": "cr_order_number"}
    assert d.display is not None
    assert d.display.by_language["en"] == "Return detail"
    assert d.override_auto is True


def test_parse_string_inline_binding_block() -> None:
    r = ttr_parser.parse_string(_read("19-inline-mapping.ttrm"))
    assert r.ok
    e = r.definitions[0]
    assert isinstance(e, EntityDef)
    # Assert the full inline-binding structure (all three column forms), so the
    # BindingColumnObject / nested-target / multi-column walker paths stay pinned.
    m = e.binding
    assert isinstance(m, BindingPropertyBlock)
    assert isinstance(m.target, TargetObjectValue)
    cols = {c.name: c.value for c in m.columns}
    assert isinstance(cols["id_artiklu"], BindingColumnBareId)       # bare-id form
    assert isinstance(cols["kod_artiklu"], BindingColumnObject)      # object form
    assert isinstance(cols["nazev_artiklu"], BindingColumnObject)    # nested target


# --- Error handling (contracts §2.1, §2.3) ---


def test_parse_string_syntactically_broken_does_not_raise() -> None:
    r = ttr_parser.parse_string("def project { description: \"x\" }", file_label="test.ttr")
    assert not r.ok
    assert len(r.errors) >= 1
    assert isinstance(r.errors[0], ParseError)
    assert r.errors[0].file == "test.ttr"
    assert r.errors[0].line == 1
    # Column 1-indexed for human display per contracts §2.3.
    assert r.errors[0].column >= 1
    # On any error, definitions is empty — no partial trees (contracts §2.1).
    assert r.definitions == ()


def test_parse_string_unknown_property_kind_yields_error() -> None:
    r = ttr_parser.parse_string('def project X { notARealProp: "y" }')
    assert not r.ok
    assert len(r.errors) > 0


def test_parse_string_comments_are_ignored() -> None:
    text = (
        "// a line comment\n"
        "/* a\n"
        "   block\n"
        "   comment */\n"
        'def project M { description: "x" }\n'
    )
    r = ttr_parser.parse_string(text)
    assert r.ok
    assert len(r.definitions) == 1


def test_parse_string_equals_and_colon_property_separator() -> None:
    text = (
        "def project X {\n"
        '    description = "with equals"\n'
        '    version: "1"\n'
        "}\n"
    )
    r = ttr_parser.parse_string(text)
    assert r.ok
    m = r.definitions[0]
    assert isinstance(m, ProjectDef)
    assert m.description == "with equals"


def test_parse_string_empty_definitions_for_minimal_entity() -> None:
    r = ttr_parser.parse_string("def entity X {}")
    assert r.ok
    e = r.definitions[0]
    assert isinstance(e, EntityDef)
    assert e.roles == ()


def test_parse_string_package_declaration_and_imports() -> None:
    # Grammar order is `package? import* (schema|graph)? definition*` — all
    # imports must precede the schema directive. See TTR.g4 line 37-39.
    text = (
        "package er.sales\n"
        "import cnc.role.fact\n"
        "import db.dbo.*\n"
        "model er\n"
        "def entity X {}\n"
    )
    r = ttr_parser.parse_string(text)
    assert r.ok
    assert r.package_name == "er.sales"
    assert len(r.imports) == 2
    assert r.imports[0].target == "cnc.role.fact"
    assert r.imports[0].wildcard is False
    assert r.imports[1].target == "db.dbo"
    assert r.imports[1].wildcard is True
