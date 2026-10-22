# SPDX-License-Identifier: Apache-2.0
from pathlib import Path

from ttr_parser import parse_file
from ttr_parser.semantics import SymbolTable, Validator


def test_explicit_md_er_structural_diagnostics() -> None:
    root = Path(__file__).resolve().parents[4] / "tests/conformance/fixtures"
    expected = {
        "110-md2er-validation": {"md/md2er-dimension-key", "md/md2er-measure-unknown", "md/duplicate-binding"},
        "111-md2er-path-ambiguous": {"md/md2er-path-ambiguous"},
    }
    for fixture, codes in expected.items():
        paths = sorted((root / fixture).glob("*.ttrm"))
        parsed = [parse_file(p) for p in paths]
        symbols = SymbolTable()
        for path, result in zip(paths, parsed, strict=True):
            assert not result.errors
            symbols.upsert_document(str(path), result)
        actual = {d.code.value for d in Validator().validate(parsed, symbols, [str(p) for p in paths])}
        assert codes <= actual
