# SPDX-License-Identifier: Apache-2.0
"""test_id_value_parts.py — port of IdValuePartsSpec.

Mirrors `packages/kotlin/ttr-parser/src/test/kotlin/.../model/IdValuePartsSpec.kt`
and the TS `inline-mappings.test.ts` "source locations" block.

Contracts §2.6 (`IdValue`):
- `ref`: a `Reference` with `.path`, `.parts`, `.source`.
- `parts`: tuple of dotted-name segments (split on `.`), e.g.
  `"db.dbo.customers"` → `("db", "dbo", "customers")`.
- `path`: the original dotted-name string verbatim.
- `source`: the **id token's** SourceLocation (NOT the enclosing def's).

Convenience: `Reference.of("a.b.c")` returns a Reference with `path="a.b.c"`,
`parts=("a","b","c")`, and `source=SourceLocation.UNKNOWN`.
"""

from __future__ import annotations

import pytest

import ttr_parser
from ttr_parser import (  # noqa: F821 — will exist once stages 2.2/2.3 land
    IdValue,
    Reference,
    SourceLocation,
    StringValue,
    extract_reference,
)


def test_id_value_splits_dotted_reference_into_parts() -> None:
    text = (
        "def relation R {\n"
        "    from: db.dbo.fk_item_category\n"
        "    to: db.dbo.Y\n"
        "}\n"
    )
    r = ttr_parser.parse_string(text)
    assert r.ok
    rel = r.definitions[0]
    assert rel.kind == "relation"

    frm = rel.from_  # type: ignore[attr-defined]
    assert isinstance(frm, IdValue)
    assert frm.ref.path == "db.dbo.fk_item_category"
    assert frm.parts == ("db", "dbo", "fk_item_category")

    to = rel.to  # type: ignore[attr-defined]
    assert isinstance(to, IdValue)
    assert to.ref.path == "db.dbo.Y"
    assert to.parts == ("db", "dbo", "Y")


@pytest.mark.parametrize(
    ("path", "expected_parts"),
    [
        ("x", ("x",)),
        ("a.b", ("a", "b")),
        ("db.dbo.customers", ("db", "dbo", "customers")),
        ("cnc.cnc.role.fact", ("cnc", "cnc", "role", "fact")),
    ],
)
def test_reference_of_splits_path_into_parts(path: str, expected_parts: tuple[str, ...]) -> None:
    ref = Reference.of(path)
    assert ref.path == path
    assert ref.parts == expected_parts
    assert ref.source == ttr_parser.SourceLocation.UNKNOWN


def test_id_value_source_is_the_id_token_not_the_enclosing_def() -> None:
    """ReferenceSourceSpec rule: ref.source.line != entity.source.line.

    On `def relation R { from: db.dbo.X }` the `db.dbo.X` id token sits on
    line 2 (the second line of the file), while the `def relation R`
    header sits on line 1. The id's own SourceLocation must point at the
    id token, not at the def's start.
    """
    text = (
        "def relation R {\n"
        "    from: db.dbo.X\n"
        "}\n"
    )
    r = ttr_parser.parse_string(text)
    assert r.ok
    rel = r.definitions[0]
    assert rel.kind == "relation"
    frm = rel.from_  # type: ignore[attr-defined]
    assert isinstance(frm, IdValue)
    assert frm.source.line == 2
    assert rel.source.line == 1
    assert frm.source.line != rel.source.line


def test_extract_reference_returns_id_for_id_value_only() -> None:
    """Mirrors walker.ts `extractReference` — returns a Reference for IdValue,
    None for every other PropertyValue variant."""

    frm_id = ttr_parser.parse_string(
        "def relation R { from: db.dbo.X }\n"
    ).definitions[0].from_  # type: ignore[attr-defined]
    assert isinstance(frm_id, IdValue)
    ref = extract_reference(frm_id)
    assert isinstance(ref, Reference)
    assert ref.path == "db.dbo.X"

    # A non-Id PropertyValue → None. Use a constructed StringValue (not the
    # unwrapped `description` str, which is not a PropertyValue) so the input
    # type matches extract_reference's contract (§2.6).
    non_id: StringValue = StringValue("x", SourceLocation.UNKNOWN)
    assert extract_reference(non_id) is None
