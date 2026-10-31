# SPDX-License-Identifier: Apache-2.0
import pytest

from ttr_parser.loader import parse_string


def test_query_carriers():
    result = parse_string('''model db
        def query raw { skipSecurity: true, parameters: [
            { name: p, type: text, defaultValue: "", binds: [er.Customer.name, er.Customer.code] }
        ] }
        def query checked { skipSecurity: false }
        def query ordinary { }
    ''', "carriers.ttrm")
    assert not result.errors
    raw, checked, ordinary = result.definitions
    assert getattr(raw, "skip_security") is True
    assert getattr(checked, "skip_security") is False
    assert getattr(ordinary, "skip_security") is None
    parameter = raw.parameters[0].entries
    assert parameter["defaultValue"].raw == ""
    assert [v.ref.path for v in parameter["binds"].items] == ["er.Customer.name", "er.Customer.code"]


def test_lexical_carriers():
    result = parse_string('''model lexicon locale en
        def pattern paired { for: db.query.raw, match: "name (.*)", text: "Name Alice?", indexSecurity: applied }
        def pattern alternate { for: db.query.raw, match: "lookup (.*)", text: "Lookup Bob?", indexSecurity: none }
    ''', "lex.ttrm")
    assert not result.errors
    first, second = result.definitions
    assert (first.match, first.text, getattr(first, "index_security")) == ("name (.*)", "Name Alice?", "applied")
    assert (second.match, second.text, getattr(second, "index_security")) == ("lookup (.*)", "Lookup Bob?", "none")


def test_property_keywords_remain_identifiers():
    result = parse_string("model er def entity skipSecurity { attributes: [def attribute defaultValue { type: text }, def attribute binds { type: text }] } def entity indexSecurity {}", "ids.ttrm")
    assert not result.errors


@pytest.mark.parametrize("source", [
    'def query q { skipSecurity: "true" }',
    'def query q { parameters: [{ name: p, defaultValue: true }] }',
    'def query q { parameters: [{ name: p, defaultValue: null }] }',
    'def query q { parameters: [{ name: p, binds: ["er.Customer.name"] }] }',
    'def term t { indexSecurity: true }',
])
def test_wrong_types(source):
    assert parse_string(source, "invalid.ttrm").errors
