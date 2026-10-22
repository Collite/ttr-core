# SPDX-License-Identifier: Apache-2.0
"""#258 / review-128: candidate selection and MD slot roles are independent of document order."""
from itertools import permutations

import pytest

from ttr_parser import parse_string
from ttr_parser.semantics import ResolutionContext, Resolved, Resolver, SymbolTable, Unresolved
from ttr_parser.semantics.references import collect_all_references


def resolver_for(texts, root=""):
    table = SymbolTable()
    for i, text in enumerate(texts):
        result = parse_string(text, f"{i}.ttrm")
        assert not result.errors
        table.upsert_document(f"{i}.ttrm", result, package_name=result.package_name or "")
    return Resolver(table, root)


def test_generic_candidate_priorities():
    texts = ["package shop\nmodel er\ndef entity customer {}",
             "package shop\nmodel er\ndef entity order { attributes: [def attribute customer { type: int }] }"]
    for order in permutations(texts):
        resolver = resolver_for(order)
        for model in ("er", "lexicon"):
            result = resolver.resolve_reference("customer", ResolutionContext(model, "", package_name="shop"))
            assert isinstance(result, Resolved)
            assert result.symbol.qname.value == "shop.er.entity.customer"
        result = resolver.resolve_reference("customer", ResolutionContext("er", "", package_name="shop", enclosing_qname="shop.er.entity.order"))
        assert isinstance(result, Resolved) and result.via_step == "lexical"
    result = resolver_for(texts[1:]).resolve_reference("customer", ResolutionContext("er", "", package_name="shop"))
    assert isinstance(result, Resolved) and result.symbol.qname.value == "shop.er.entity.order.customer"
    texts = ["package shop\nmodel er\ndef entity revenue {}", "package shop\nmodel md\ndef measure revenue {}"]
    for order in permutations(texts):
        result = resolver_for(order).resolve_reference("revenue", ResolutionContext("er", "", package_name="shop"))
        assert isinstance(result, Resolved) and result.symbol.qname.value == "shop.er.entity.revenue"


def test_member_ambiguity_is_sorted():
    texts = [f"package shop\nmodel er\ndef entity {name} {{ attributes: [def attribute revenue {{ type: decimal }}] }}" for name in ("sale", "order")]
    for order in permutations(texts):
        result = resolver_for(order).resolve_reference("revenue", ResolutionContext("lexicon", "", package_name="shop"))
        assert isinstance(result, Unresolved) and result.reason == "ambiguous"
        assert [e.qname.value for e in result.candidates] == ["shop.er.entity.order.revenue", "shop.er.entity.sale.revenue"]


@pytest.mark.parametrize("order", list(permutations([
    "package shop\nmodel er\ndef entity sale { attributes: [def attribute revenue { type: decimal }] }",
    "package shop\nmodel md\ndef domain Money { type: decimal }\ndef measure revenue { domain: md.Money }",
    "package shop\nmodel md\ndef cubelet Sales { measures: [revenue] }",
])))
def test_md_measure_slot_in_every_document_order(order):
    resolver = resolver_for(order)
    result = parse_string("package shop\nmodel md\ndef cubelet Sales { measures: [revenue] }")
    ref = collect_all_references(result.definitions)[0]
    assert ref.md_context == "measure"
    resolved = resolver.resolve_reference(ref.path, ResolutionContext("md", "", package_name="shop"), md_context=ref.md_context)
    assert isinstance(resolved, Resolved)
    assert resolved.symbol.qname.value == "shop.md.measure.revenue"
    assert resolved.via_step == "same-package"


def test_md_local_qualified_root_and_import_precedence():
    resolver = resolver_for([f"package {pkg}\nmodel md\ndef measure revenue {{}}" for pkg in ("east", "west")], "root")
    for path, target in (("revenue", "west"), ("east.md.revenue", "east"), ("east.md.measure.revenue", "east"), ("root.east.md.revenue", "east")):
        result = resolver.resolve_reference(path, ResolutionContext("md", "", package_name="west"), md_context="measure")
        assert isinstance(result, Resolved) and result.symbol.qname.value == f"{target}.md.measure.revenue"
    for imports, step in (("import west.md.measure.revenue", "named-import"), ("import west.*", "wildcard-import")):
        doc = parse_string(f"package app\n{imports}\nmodel md\ndef cubelet Sales {{ measures: [revenue] }}")
        result = resolver.resolve_reference("revenue", ResolutionContext("md", "", package_name="app", imports=doc.imports), md_context="measure")
        assert isinstance(result, Resolved) and result.via_step == step
        assert result.symbol.qname.value == "west.md.measure.revenue"
    doc = parse_string("package app\nimport east.*\nimport west.*\nmodel md")
    result = resolver.resolve_reference("revenue", ResolutionContext("md", "", package_name="app", imports=doc.imports), md_context="measure")
    assert isinstance(result, Unresolved) and result.reason == "ambiguous"
    result = resolver_for(["package shop\nmodel er\ndef entity revenue {}", "package shop\nmodel cn\ndef kind Revenue {}"])
    for path in ("revenue", "cn.Revenue"):
        missing = result.resolve_reference(path, ResolutionContext("md", "", package_name="shop"), md_context="measure")
        assert isinstance(missing, Unresolved) and missing.reason == "not-found"
