# SPDX-License-Identifier: Apache-2.0
"""CN-P8 mechanical reader: ordered properties, spans and non-cn role guards."""

from pathlib import Path

import pytest

from ttr_parser import parse_string
from ttr_parser.model import ConceptDef, ConceptFrom, ConceptPer, ConceptUntil


def test_time_shapes_and_reference_spans() -> None:
    root = Path(__file__).resolve().parents[4]
    source = (root / "tests/conformance/fixtures/115-cn-things-in-time.ttrm").read_text()
    result = parse_string(source)
    assert not result.errors
    defs = {d.name: d for d in result.definitions if isinstance(d, ConceptDef)}
    assert defs["Invoice"].stereotype == "subkind"
    props = defs["OpenOrder"].body.properties
    assert [p.kind for p in props] == ["entry", "from", "until"]
    quality = defs["list_price"].body.properties
    assert isinstance(quality[-2], ConceptPer)
    assert quality[-2].ref.path == "Channel"
    assert isinstance(quality[-1], ConceptFrom)
    assert quality[-1].ref.path == "ListPrice.price"
    for prop in (*props, *quality):
        if isinstance(prop, (ConceptFrom, ConceptUntil, ConceptPer)):
            loc = prop.ref.source
            assert loc is not None
            assert source[loc.offset_start:loc.offset_end] == prop.ref.path


@pytest.mark.parametrize("model", ["cnc", "automation"])
@pytest.mark.parametrize("prop", ["involves: [Item]", "period: { start: since, end: until }"])
def test_lifetime_role_properties_are_cn_only(model: str, prop: str) -> None:
    assert parse_string(f"model {model}\ndef role Reader {{ {prop} }}").errors
