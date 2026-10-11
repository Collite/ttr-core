# SPDX-License-Identifier: Apache-2.0
"""Grammar 0.14 — Python parity for the top-level ``def schema`` (a named row schema).

Mirrors the TS ``schema-grammar.test.ts`` and the Kotlin ``RowSchemaParseSpec.kt``: columns in
declared order, written exactly like a table's columns; the world-storage ``def schema`` stays a
separate production.
"""

from __future__ import annotations

from ttr_parser import parse_string
from ttr_parser.model import SchemaDef, WorldDef

EXAMPLE = """package shop.actions

def schema notify {
    description: "Send a notification to a recipient",
    columns: [
        def column recipient { type: text },
        def column order_id  { type: int },
        def column amount    { type: decimal, optional: true },
        def column due       { type: date, optional: true, description: "payment due date" },
    ]
}

def schema flag_order { tags: ["action"], columns: [ def column order_id { type: int } ] }
"""


def test_def_schema_parses_ordered_typed_optional_columns() -> None:
    result = parse_string(EXAMPLE, "schemas.ttrm")
    assert result.errors == ()
    schemas = [d for d in result.definitions if isinstance(d, SchemaDef)]
    assert [s.name for s in schemas] == ["notify", "flag_order"]
    notify = schemas[0]
    assert notify.kind == "schema"
    assert notify.description == "Send a notification to a recipient"
    assert [c.name for c in notify.columns] == ["recipient", "order_id", "amount", "due"]
    assert [c.type.name for c in notify.columns if c.type] == ["text", "int", "decimal", "date"]
    assert [c.optional for c in notify.columns] == [False, False, True, True]
    assert notify.columns[3].description == "payment due date"
    assert schemas[1].tags == ("action",)


def test_world_storage_schema_is_a_separate_production() -> None:
    result = parse_string(
        "model world\ndef world w { def storage files { type: local_dir, def schema sales { customer: string } } }",
        "w.ttrm",
    )
    assert result.errors == ()
    world = result.definitions[0]
    assert isinstance(world, WorldDef)
    assert [s.name for s in world.storages[0].schemas] == ["sales"]
    assert not any(isinstance(d, SchemaDef) for d in result.definitions)
