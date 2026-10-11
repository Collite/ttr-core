# SPDX-License-Identifier: Apache-2.0
"""Phase P1 smoke test — confirms the generated ANTLR Python parser is wired correctly.

If `pip install -e .` and the generate step both worked, this file parses
without errors. The walker (P2) will replace this with a real `parse_string`
API; for now we exercise the raw ANTLR recogniser entry point.

Syntax rules exercised here (verified against `samples/2.1/*.ttr`):
- top-level `def <kind> NAME { … }`
- property keys are camelCase (`primaryKey`, `isKey`, `valueLabels`)
- child defs inside list properties still require the `def` keyword
"""

from __future__ import annotations

from antlr4 import CommonTokenStream, InputStream

from ttr_parser._generated.TTRLexer import TTRLexer
from ttr_parser._generated.TTRParser import TTRParser


def _parse(text: str) -> tuple[int, TTRParser.DocumentContext]:
    lexer = TTRLexer(InputStream(text))
    parser = TTRParser(CommonTokenStream(lexer))
    tree = parser.document()
    return parser.getNumberOfSyntaxErrors(), tree


def test_smoke_parses_empty_model():
    errors, tree = _parse("def project X {}\n")
    assert errors == 0, f"expected no syntax errors, got {errors}"
    assert tree is not None


def test_smoke_parses_table_with_columns():
    text = (
        "model db schema dbo\n"
        "def table CUSTOMER {\n"
        '  primaryKey: ["C_CUSTOMER_SK"]\n'
        "  columns: [\n"
        '    def column C_CUSTOMER_SK { type: int, isKey: true }\n'
        "  ]\n"
        "}\n"
    )
    errors, tree = _parse(text)
    assert errors == 0, f"expected no syntax errors, got {errors}"
    assert tree is not None


def test_smoke_parses_schema_then_defs():
    text = (
        "model er\n"
        "def entity Item {\n"
        '  attributes: [\n'
        '    def attribute ID { type: int, isKey: true }\n'
        "  ]\n"
        "}\n"
    )
    errors, tree = _parse(text)
    assert errors == 0, f"expected no syntax errors, got {errors}"
    assert tree is not None


def test_smoke_reports_syntax_error_on_garbage():
    errors, _tree = _parse("this is not a valid ttr document\n")
    assert errors > 0
