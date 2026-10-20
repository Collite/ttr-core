# SPDX-License-Identifier: Apache-2.0
"""Grammar 0.17 — Python parity for the conceptual model (CN-P1 S1.1).

Mirrors the TS ``cn-grammar.test.ts`` and the Kotlin ``CnGrammarSpec.kt``: the cn fixtures (cn
contracts §1, block-form definitions per ⚑CN-P5) parse with zero errors; the parser-level rejects
fail; the one ``def role`` production keeps review-114 M5 outside ``model cn`` (⚑CN-P6).
"""

from __future__ import annotations

import re
from pathlib import Path

import pytest

from ttr_parser import parse_string
from ttr_parser.diagnostics import DiagnosticCode

_RESOURCES = Path(__file__).resolve().parents[3] / "kotlin" / "ttr-parser" / "src" / "test" / "resources"

_CN_ROLE = 'def role Customer { of: Party, event: Order, grounds: md.Customer, lexicon: { terms: ["client"] } }'


def _parse(rel: str):  # type: ignore[no-untyped-def]
    return parse_string((_RESOURCES / rel).read_text(encoding="utf-8"), rel)


@pytest.mark.parametrize(
    ("name", "model_code"),
    [
        ("75-cn-concepts.ttrm", "cn"),
        ("76-cn-bindings.ttrm", "binding"),
        ("77-cn-sugar.ttrm", None),
        ("78-cn-lexicon.ttrm", "lexicon"),
        ("79-cn-nouns-as-ids.ttrm", "db"),
    ],
)
def test_cn_fixture_parses(name: str, model_code: str | None) -> None:
    r = _parse(f"cn/{name}")
    assert r.errors == ()
    assert (r.model_directive.model_code if r.model_directive else None) == model_code


@pytest.mark.parametrize(
    "name",
    [
        "neg-01-bare-definition.ttrm",
        "neg-02-involves-no-brackets.ttrm",
        "neg-03-bare-members.ttrm",
        "neg-04-unlabelled-as.ttrm",
    ],
)
def test_parser_rejects(name: str) -> None:
    assert _parse(f"cn-negative/{name}").errors != ()


def test_a_cn_role_takes_the_concept_properties() -> None:
    assert parse_string(f"model cn\n{_CN_ROLE}", "r.ttrm").errors == ()


@pytest.mark.parametrize("model", ["cnc", "automation"])
def test_a_non_cn_role_rejects_the_concept_properties(model: str) -> None:
    """review-114 M5 holds outside ``model cn``: one ``ttr/unknown-property`` error per cn property."""
    r = parse_string(f"model {model}\n{_CN_ROLE}", "r.ttrm")
    assert [e.code for e in r.errors] == [DiagnosticCode.UNKNOWN_PROPERTY] * 4
    keys = [m.group(1) for e in r.errors if (m := re.search(r"'([^']+)'", e.message))]
    assert keys == ["of", "event", "grounds", "lexicon"]
    assert f"model {model}" in r.errors[0].message


def test_a_role_typo_is_a_parse_error_without_a_model_too() -> None:
    assert parse_string('def role r { lable: { en: "x" } }', "r.ttrm").errors != ()
