# SPDX-License-Identifier: Apache-2.0
"""0.17 — Python parity for the cn model in semantics (CN-P2 S2.4).

The semantics conformance dump compares diagnostic codes only; this pins where each portable `cn/...`
slug fires (`file:line code`) on the conformance fixtures 85–99 — the same table the TS twin asserts
(`packages/lint/src/__tests__/cn-rules.test.ts`) and the Kotlin one (`CnSemanticsSpec.kt`) — plus the
keys and the cn-context resolution of S2.1's slot table.
"""

from __future__ import annotations

from pathlib import Path

import pytest

from ttr_parser import parse_string
from ttr_parser.diagnostics import DiagnosticCode
from ttr_parser.semantics import ResolutionContext, Resolved, Resolver, SymbolTable, Validator
from ttr_parser.semantics.cn.groundings import CnDocument, analyze_groundings
from ttr_parser.semantics.cn.validate import cnc_deprecations, validate_cn
from ttr_parser.semantics.references import collect_all_references

_FIXTURES = Path(__file__).resolve().parents[4] / "tests" / "conformance" / "fixtures"


class _Project:
    def __init__(self, files: list[tuple[str, str]]) -> None:
        self.symbols = SymbolTable()
        self.results = []
        self.docs: list[CnDocument] = []
        for uri, src in files:
            r = parse_string(src, uri)
            assert r.errors == ()
            self.symbols.upsert_document(uri, r, package_name=r.package_name or "")
            d = r.model_directive
            self.results.append(r)
            self.docs.append(
                CnDocument(
                    uri=uri,
                    definitions=r.definitions,
                    schema_code=d.model_code if d else "",
                    namespace=(d.schema if d else "") or "",
                    package_name=r.package_name or "",
                    imports=r.imports,
                    directive_source=d.source if d else None,
                )
            )
        self.resolver = Resolver(self.symbols)
        self.uris = [uri for uri, _ in files]

    def resolve(self, uri: str, path: str, *, cn: bool | None = None) -> str | None:
        doc = next(d for d in self.docs if d.uri == uri)
        ref = next(
            c for c in collect_all_references(doc.definitions) if c.path == path and (cn is None or c.cn_context == cn)
        )
        ctx = ResolutionContext(doc.schema_code, doc.namespace, doc.imports, doc.package_name)
        res = self.resolver.resolve_reference(ref.path, ctx, cn_context=ref.cn_context)
        return res.symbol.qname.value if isinstance(res, Resolved) else None


def _scenario(name: str) -> _Project:
    files = sorted(p for p in (_FIXTURES / name).iterdir() if p.suffix == ".ttrm")
    return _Project([(f"{name}/{p.name}", p.read_text(encoding="utf-8")) for p in files])


def _cn_sites(name: str) -> list[str]:
    p = _scenario(name)
    found = [*validate_cn(p.docs, p.resolver).diagnostics, *(d for doc in p.docs for d in cnc_deprecations(doc))]
    return sorted(f"{d.uri.split('/', 1)[1]}:{d.source.line} {d.code.value}" for d in found)


_EXPECTED: dict[str, list[str]] = {
    "85-cn-duplicate-grounding": [
        "a-er.ttrm:19 cn/duplicate-grounding",
        "a-er.ttrm:28 cn/duplicate-grounding",
        "c-bindings.ttrm:5 cn/duplicate-grounding",
    ],
    "86-cn-grounding-target-kind": [
        "c-concepts.ttrm:12 cn/grounding-target-kind",
        "c-concepts.ttrm:20 cn/grounding-target-kind",
        "c-concepts.ttrm:21 cn/grounding-target-kind",
        "c-concepts.ttrm:21 cn/phase-no-definition",
        "c-concepts.ttrm:23 cn/grounding-target-kind",
        "c-concepts.ttrm:8 cn/grounding-target-kind",
        "d-bindings.ttrm:5 cn/grounding-target-kind",
        "d-bindings.ttrm:6 cn/grounding-target-kind",
    ],
    "87-cn-role-no-event": ["a-er.ttrm:16 cn/role-no-event", "b-concepts.ttrm:6 cn/role-no-event"],
    "88-cn-bearer-missing": [
        "a-er.ttrm:13 ttr/required-property-missing",
        "b-concepts.ttrm:5 ttr/required-property-missing",
        "b-concepts.ttrm:7 ttr/required-property-missing",
    ],
    "89-cn-phase-double-definition": [
        "b-concepts.ttrm:5 cn/phase-double-definition",
        "c-bindings.ttrm:4 cn/phase-double-definition",
    ],
    "90-cn-phase-no-definition": ["b-concepts.ttrm:5 cn/phase-no-definition"],
    "91-cn-event-no-period": ["a-er.ttrm:17 cn/event-no-period", "b-concepts.ttrm:4 cn/event-no-period"],
    "92-cn-ungrounded": [
        "b-concepts.ttrm:4 cn/ungrounded",
        "b-concepts.ttrm:5 cn/ungrounded",
        "b-concepts.ttrm:6 cn/ungrounded",
        "b-concepts.ttrm:7 cn/ungrounded",
    ],
    "93-cn-db-grounding": ["c-concepts.ttrm:4 cn/db-grounding", "d-bindings.ttrm:4 cn/db-grounding"],
    "94-cn-involves-unlabelled-duplicate": [
        "b-concepts.ttrm:17 cn/involves-unlabelled-duplicate",
        "b-concepts.ttrm:7 cn/involves-unlabelled-duplicate",
    ],
    "95-cn-antirigid-supertype": [
        "b-concepts.ttrm:10 cn/antirigid-supertype",
        "b-concepts.ttrm:8 cn/antirigid-supertype",
        "b-concepts.ttrm:9 cn/antirigid-supertype",
    ],
    "96-cn-unknown-relation-type": [
        "b-concepts.ttrm:7 cn/unknown-relation-type",
        "b-concepts.ttrm:8 cn/unknown-relation-type",
    ],
    "97-cn-cnc-deprecated": ["a-cnc.ttrm:7 cn/cnc-deprecated", "b-er.ttrm:4 cn/cnc-deprecated"],
    "98-cn-hierarchy-shape": [
        "b-concepts.ttrm:10 cn/hierarchy-shape",
        "b-concepts.ttrm:14 cn/hierarchy-shape",
        "c-bindings.ttrm:7 cn/hierarchy-shape",
    ],
    "99-cn-participant-path-broken": [
        "d-bindings.ttrm:10 cn/participant-path-broken",
        "d-bindings.ttrm:19 cn/participant-path-broken",
        "d-bindings.ttrm:20 cn/participant-path-broken",
    ],
}


@pytest.mark.parametrize("name", sorted(_EXPECTED))
def test_cn_slug_fires_at_the_sites_its_header_names(name: str) -> None:
    assert _cn_sites(name) == _EXPECTED[name]


def test_equivalence_fixtures_stay_clean_but_for_the_advisory_db_grounding() -> None:
    assert _cn_sites("81-cn-desugar-top-down") == ["d-concepts.ttrm:28 cn/db-grounding"]
    assert _cn_sites("82-cn-desugar-top-down-authored") == ["e-bindings.ttrm:25 cn/db-grounding"]
    assert _cn_sites("83-cn-desugar-bottom-up") == []
    assert _cn_sites("84-cn-desugar-bottom-up-authored") == []


def test_top_down_and_bottom_up_pairs_ground_the_same_concepts() -> None:
    def groundings(name: str) -> list[str]:
        p = _scenario(name)
        by = analyze_groundings(p.docs, p.resolver).by_concept
        return sorted(
            f"{k} {v[0].grounding.binding_kind} {v[0].target_symbol.qname.value if v[0].target_symbol else None}"
            for k, v in by.items()
        )

    assert groundings("81-cn-desugar-top-down") == groundings("82-cn-desugar-top-down-authored")
    assert groundings("83-cn-desugar-bottom-up") == groundings("84-cn-desugar-bottom-up-authored")


_ER = "package p\nmodel er\n\ndef entity party { attributes: [ def attribute party_id { type: int, isKey: true } ] }\n"


def test_keys_concept_binding_and_participants() -> None:
    p = _Project(
        [
            ("er.ttrm", _ER),
            ("cn.ttrm", "package p\nmodel cn\n\ndef kind Party { }\ndef event Order { involves: [Party as buyer, Party as seller] }\n"),
            ("b.ttrm", "package p\nmodel binding\n\ndef cn2er_kind party_g { kind: cn.Party, target: er.entity.party }\n"),
        ]
    )
    keys = {e.qname.value for e in p.symbols.get_all()}
    assert {"p.cn.concept.Party", "p.cn.concept.Order.buyer", "p.cn.concept.Order.seller"} <= keys
    assert "p.binding.cn2er_kind.party_g" in keys
    party = p.symbols.get("p.cn.concept.Party")
    assert party is not None and party.stereotype == "kind"


def test_cn_slot_resolves_among_concepts_only() -> None:
    p = _Project(
        [
            ("er.ttrm", _ER),
            ("cn.ttrm", "package p\nmodel cn\n\ndef kind party { grounds: er.entity.party }\ndef role Buyer { of: party }\n"),
            ("er2.ttrm", "package p\nmodel er\n\ndef relation r { from: party, to: party }\n"),
        ]
    )
    assert p.resolve("cn.ttrm", "party", cn=True) == "p.cn.concept.party"
    assert p.resolve("er2.ttrm", "party") == "p.er.entity.party"


def test_cn_head_participant_key_and_the_declaring_slot() -> None:
    p = _Project(
        [
            (
                "er.ttrm",
                "package p\nmodel er\n\ndef entity sale { attributes: [ def attribute id { type: int, isKey: true } ],"
                " concept { event: Sale, involves: [Party as buyer] } }\n" + _ER.split("model er\n", 1)[1],
            ),
            ("cn.ttrm", "package p\nmodel cn\n\ndef kind Party { grounds: er.entity.party }\n"),
            ("lex.ttrm", 'package p\nmodel lexicon locale en\n\ndef term party { for: cn.Party, forms: ["party"] }\n'),
            (
                "b.ttrm",
                "package p\nmodel binding\n\ndef cn2er_event sale_g { event: Sale, target: er.entity.sale,"
                " participants: { buyer: [] } }\n",
            ),
        ]
    )
    assert p.resolve("lex.ttrm", "cn.Party") == "p.cn.concept.Party"
    assert p.resolve("b.ttrm", "Sale.buyer") == "p.cn.concept.Sale.buyer"
    er = next(d for d in p.docs if d.uri == "er.ttrm")
    assert "Sale" not in [c.path for c in collect_all_references(er.definitions)]
    sale = p.symbols.get("p.cn.concept.Sale")
    assert sale is not None and sale.cn_origin == "sugar"


def test_binding_period_upper_bounds_are_er_refs() -> None:
    """⚑CN-P10: a binding period's stored upper bounds `start_hi` / `at_hi` are er refs, like `start` / `at`."""
    p = _Project(
        [
            ("er.ttrm", _ER),
            ("cn.ttrm", "package p\nmodel cn\n\ndef event Visit { period: { start: s } }\n"),
            (
                "b.ttrm",
                "package p\nmodel binding\n\ndef cn2er_event visit_g { event: Visit, target: er.entity.party,"
                " period: { start: er.entity.party.party_id, start_hi: er.entity.party.party_id,"
                " at_hi: er.entity.party.nope } }\n",
            ),
        ]
    )
    b = next(d for d in p.docs if d.uri == "b.ttrm")
    paths = [c.path for c in collect_all_references(b.definitions)]
    assert paths.count("er.entity.party.party_id") == 2
    assert p.resolve("b.ttrm", "er.entity.party.nope") is None


def test_authored_concept_wins_over_the_block() -> None:
    p = _Project(
        [
            ("a.ttrm", "package p\nmodel er\n\ndef entity party { attributes: [ def attribute id { type: int } ], concept { kind: Party } }\n"),
            ("b.ttrm", "package p\nmodel cn\n\ndef kind Party { }\n"),
        ]
    )
    party = p.symbols.get("p.cn.concept.Party")
    assert party is not None and party.source_file == "b.ttrm"


def test_soft_names_and_md_cross_references_are_no_unresolved_reference() -> None:
    p = _Project(
        [
            ("er.ttrm", _ER),
            (
                "cn.ttrm",
                "package p\nmodel cn\n\ndef event Order { qualities: [amount], period: { at: placed_on },"
                " grounds: er.entity.party }\n",
            ),
            ("md.ttrm", "package p\nmodel md\n\ndef measure revenue { domain: md.Money, aggregation: sum }\n"),
        ]
    )
    codes = [d.code for d in Validator().validate(p.results, p.symbols, p.uris)]
    assert DiagnosticCode.UNRESOLVED_REFERENCE not in codes


def test_two_cn_defs_stay_a_duplicate_definition_a_restating_block_does_not() -> None:
    p = _Project(
        [
            ("a.ttrm", "package p\nmodel cn\n\ndef kind Party { }\n"),
            ("b.ttrm", "package p\nmodel cn\n\ndef kind Party { }\n"),
            ("c.ttrm", "package p\nmodel er\n\ndef entity party { attributes: [ def attribute id { type: int } ], concept { kind: Party } }\n"),
        ]
    )
    codes = [d.code for d in Validator().validate(p.results, p.symbols, p.uris)]
    assert codes.count(DiagnosticCode.DUPLICATE_DEFINITION) == 2
