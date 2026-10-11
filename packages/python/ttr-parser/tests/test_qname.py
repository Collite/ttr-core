# SPDX-License-Identifier: Apache-2.0
"""test_qname.py — port of qname.test.ts (Stage 4.1.1).

Mirrors `packages/semantics/src/__tests__/qname.test.ts` and the Kotlin
`QnameSpec.kt`, but targets the **Python** semantics API of contracts §3.1
(`Qname` with `.value` / `.segments` / `.last` / `.parent`), not the TS
`parseQname`/`qnameToString` free functions.

Tests-first: `ttr_parser.semantics` does not exist until stages 4.2–4.4, so the
import fails and every test errors red — that is the expected end-of-4.1 state.
"""

from __future__ import annotations

from ttr_parser.semantics import Qname


def test_segments_multi() -> None:
    assert Qname("er.entity.item").segments == ("er", "entity", "item")


def test_segments_with_sub_part() -> None:
    assert Qname("er.entity.item.sk").segments == (
        "er",
        "entity",
        "item",
        "sk",
    )


def test_segments_single() -> None:
    assert Qname("foo").segments == ("foo",)


def test_last_multi() -> None:
    assert Qname("er.entity.item").last == "item"


def test_last_single() -> None:
    assert Qname("foo").last == "foo"


def test_parent_multi() -> None:
    assert Qname("er.entity.item").parent == Qname("er.entity")


def test_parent_chain() -> None:
    parent = Qname("a.b.c").parent
    assert parent == Qname("a.b")
    assert parent is not None
    assert parent.parent == Qname("a")


def test_parent_of_two_segment() -> None:
    assert Qname("db.t").parent == Qname("db")


def test_parent_of_single_is_none() -> None:
    assert Qname("foo").parent is None


def test_str_round_trips() -> None:
    assert str(Qname("er.entity.item")) == "er.entity.item"
    assert str(Qname("db.dbo.CATALOG_SALES")) == "db.dbo.CATALOG_SALES"


def test_value_field() -> None:
    assert Qname("cnc.cnc.role.fact").value == "cnc.cnc.role.fact"
