# SPDX-License-Identifier: Apache-2.0
from pathlib import Path

from ttr_parser import parse_file
from ttr_parser.semantics import SymbolTable, Validator


def test_explicit_md_er_structural_diagnostics() -> None:
    root = Path(__file__).resolve().parents[4] / "tests/conformance/fixtures"
    expected = {
        "113-md2er-nouns-resolve": set(),
        "112-md2er-partial": {"md/md2er-reference"},
        "110-md2er-validation": {"md/md2er-dimension-key", "md/md2er-measure-unknown", "md/duplicate-binding", "md/md2er-reference"},
        "111-md2er-path-ambiguous": {"md/md2er-path-ambiguous", "md/md2er-reference", "ttr/unused-import"},
    }
    for fixture, codes in expected.items():
        paths = sorted((root / fixture).glob("*.ttrm")) if (root / fixture).is_dir() else [root / f"{fixture}.ttrm"]
        parsed = [parse_file(p) for p in paths]
        symbols = SymbolTable()
        for path, result in zip(paths, parsed, strict=True):
            assert not result.errors
            symbols.upsert_document(str(path), result)
        actual = {d.code.value for d in Validator().validate(parsed, symbols, [str(p) for p in paths])}
        assert actual == codes


def test_md_namespace_collisions() -> None:
    from ttr_parser import parse_string

    md = '''model md
    def domain Customer { type: text }
    def dimension Customer { key: code, attributes: [def attribute code { domain: md.Customer }] }
    def measure revenue { domain: md.Customer, aggregation: sum }
    def cubelet revenue { measures: [revenue] }
    '''
    er = 'model er\ndef entity customer { attributes: [def attribute sk { type: int }] }'
    for attributes, expected in [('code: sk', set()), ('', {'md/md2er-dimension-key'})]:
        binding = f'''model binding
        def md2er_dimension c {{ dimension: md.Customer, entity: er.entity.customer, attributes: {{ {attributes} }} }}
        def md2er_cubelet m {{ cubelet: md.revenue, target: er.entity.customer, measures: {{ revenue: sk }} }}'''
        parsed = [parse_string(src, f'{i}.ttrm') for i, src in enumerate((md, er, binding))]
        symbols = SymbolTable()
        for i, result in enumerate(parsed):
            assert not result.errors
            symbols.upsert_document(f'{i}.ttrm', result)
        assert {d.code.value for d in Validator().validate(parsed, symbols, [f'{i}.ttrm' for i in range(3)])} == expected
