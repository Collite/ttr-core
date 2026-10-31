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
    assert raw.skip_security is True
    assert checked.skip_security is False
    assert ordinary.skip_security is None
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
    assert (first.match, first.text, first.index_security) == ("name (.*)", "Name Alice?", "applied")
    assert (second.match, second.text, second.index_security) == ("lookup (.*)", "Lookup Bob?", "none")


def test_property_keywords_remain_identifiers():
    result = parse_string("model er def entity skipSecurity { attributes: [def attribute defaultValue { type: text }, def attribute binds { type: text }] } def entity indexSecurity {}", "ids.ttrm")
    assert not result.errors


def test_procedure_parameter_carriers():
    result = parse_string('model db def procedure call { parameters: [{name: p, type: text, direction: IN, defaultValue: "seed", binds: [er.Customer.name]}] }', "procedure.ttrm")
    assert not result.errors
    parameter = result.definitions[0].parameters[0].entries
    assert parameter["defaultValue"].raw == "seed"
    assert parameter["binds"].items[0].ref.path == "er.Customer.name"


@pytest.mark.parametrize("source", [
    'def query q { skipSecurity: "true" }',
    'def query q { parameters: [{ name: p, defaultValue: true }] }',
    'def query q { parameters: [{ name: p, defaultValue: null }] }',
    'def query q { parameters: [{ name: p, binds: ["er.Customer.name"] }] }',
    'def term t { indexSecurity: true }',
])
def test_wrong_types(source):
    assert parse_string(source, "invalid.ttrm").errors


@pytest.mark.parametrize("key", ["binds", "defaultValue", "label"])
def test_incomplete_parameter_keeps_sibling(key):
    result = parse_string(f"model db def query q {{ parameters: [{{name: p, {key} }}] }} def query r {{}}", "typing.ttrm")
    assert result.errors
    from antlr4 import CommonTokenStream, InputStream

    from ttr_parser._generated.TTRLexer import TTRLexer
    from ttr_parser._generated.TTRParser import TTRParser
    from ttr_parser.walker import walk_document

    parser = TTRParser(CommonTokenStream(TTRLexer(InputStream(f"model db def query q {{ parameters: [{{name: p, {key} }}] }} def query r {{}}"))))
    parser.removeErrorListeners()
    walked = walk_document(parser.document(), "typing.ttrm")
    assert [d.name for d in walked.definitions] == ["q", "r"]
    assert key not in walked.definitions[0].parameters[0].entries


def test_cn_soft_keys():
    result = parse_string("model cn def kind Item { binds: x, defaultValue: y, skipSecurity: z, indexSecurity: w }", "keys.ttrm")
    assert not result.errors
    assert [p.key for p in result.definitions[0].body.properties] == ["binds", "defaultValue", "skipSecurity", "indexSecurity"]



def test_escape_values_and_source_locations():
    from ttr_parser.model import ConceptDef

    source = r'model cn def quality P { of: X, definition: "a\n😀\tb\r\"c\"" }'
    result = parse_string(source, "escapes.ttrm")
    assert not result.errors
    concept = result.definitions[0]
    assert isinstance(concept, ConceptDef)
    block = next(p.block for p in concept.body.properties if p.key == "definition")
    assert block.text == 'a\n😀\tb\r"c"'
    location = block.location(1, 2)
    offset = source.index("b", source.index('"a'))
    assert (location.offset_start, location.line, location.column) == (offset, 1, offset)
    assert block.text_source.end_line == 1
    assert block.text_source.end_column == source.index('" }')
