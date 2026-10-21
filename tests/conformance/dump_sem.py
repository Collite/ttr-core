# SPDX-License-Identifier: Apache-2.0
"""Python-side SEMANTICS conformance dump (contracts §5.1).

Mirror of `dump-sem.ts` / `run-ts-sem.ts`. For each fixture — a single `.ttr`
file or a multi-document subdirectory — it loads the stock cnc vocab, builds ONE
`SymbolTable`, resolves every reference, runs the portable validator subset, and
emits a normalised `{ diagnostics, resolved, symbols }` object that must be
byte-identical to the committed TS golden `out-ts-sem/`.

Normalisation (identical to `dump-sem.ts`):
  - `resolved`  — sorted `"<refPath> => <resolvedQname>"`, resolved with the
                  document's RAW schema code (no per-kind default — this is what
                  exercises the schema-prefix in the qname), one per ref.
  - `diagnostics` — sorted `DiagnosticCode.value` strings from the portable
                    validator (severity/positions not compared).
  - `symbols`   — sorted full qnames of the scenario's own defs (stock excluded).

JSON formatting matches `JSON.stringify(value, null, 4)` + trailing newline.
"""

from __future__ import annotations

import json
import sys
from pathlib import Path

from ttr_parser import parse_file, parse_string
from ttr_parser.model import ParseResult
from ttr_parser.semantics import (
    ResolutionContext,
    Resolved,
    Resolver,
    SymbolTable,
    Validator,
)
from ttr_parser.semantics.references import collect_all_references, enclosing_qname_of

REPO_ROOT = Path(__file__).resolve().parent.parent.parent
FIXTURES = REPO_ROOT / "tests" / "conformance" / "fixtures"
OUT_PY_SEM = REPO_ROOT / "tests" / "conformance" / "out-py-sem"
OUT_TS_SEM = REPO_ROOT / "tests" / "conformance" / "out-ts-sem"
# The canonical stock vocab (the same content dump-sem.ts loads via
# @modeler/semantics); the build copies this into the package for runtime use.
STOCK_FILE = REPO_ROOT / "packages" / "semantics" / "src" / "stock" / "cnc-roles.ttrm"
STOCK_URI = "stock://cnc-roles.ttrm"


def dump_sem_docs(docs: list[tuple[ParseResult, str]]) -> str:
    """Build one symbol table from stock + every doc, resolve, validate, render."""
    symbols = SymbolTable()

    # Stock first, so cnc.* auto-imports resolve to the doubled cnc.cnc.role.*.
    stock_result = parse_string(STOCK_FILE.read_text(encoding="utf-8"), STOCK_URI)
    symbols.upsert_document(STOCK_URI, stock_result)

    # Upsert every document FIRST so cross-document lookups see the whole project.
    metas: list[tuple[ParseResult, str, str, str]] = []
    for result, uri in docs:
        directive = result.model_directive
        schema_code = directive.model_code if directive else ""
        namespace = (directive.schema if directive else "") or ""
        package = result.package_name or ""
        symbols.upsert_document(uri, result, package_name=package)
        metas.append((result, schema_code, namespace, package))

    resolver = Resolver(symbols)

    resolved: list[str] = []
    for result, schema_code, namespace, package in metas:
        for collected in collect_all_references(result.definitions):
            enclosing = enclosing_qname_of(
                collected.owner_def, schema_code, namespace, package
            )
            res = resolver.resolve_reference(
                collected.path,
                ResolutionContext(
                    schema_code=schema_code,
                    namespace=namespace,
                    imports=result.imports,
                    package_name=package,
                    enclosing_qname=enclosing,
                ),
                # The ref itself: a cn slot's ref carries its context (0.17).
                cn_context=collected.cn_context,
            )
            if isinstance(res, Resolved):
                resolved.append(f"{collected.path} => {res.symbol.qname.value}")

    diagnostics = [
        d.code.value
        for d in Validator().validate([r for r, _ in docs], symbols, [uri for _, uri in docs])
    ]

    symbol_qnames = [
        e.qname.value
        for e in symbols.get_all()
        if not e.source_file.startswith("stock://")
    ]

    resolved.sort()
    diagnostics.sort()
    symbol_qnames.sort()
    payload = {
        "diagnostics": diagnostics,
        "resolved": resolved,
        "symbols": symbol_qnames,
    }
    return json.dumps(payload, ensure_ascii=False, indent=4) + "\n"


def single_fixtures() -> list[Path]:
    return sorted(p for p in FIXTURES.iterdir() if p.is_file() and p.suffix == ".ttrm")


def dir_fixtures() -> list[Path]:
    """Multi-document scenario directories.

    `*-negative` directories are parser-REJECT rosters (each file is expected to fail to
    parse), not positive scenarios — they are exercised by the parser's negative specs
    (TS `semantics-block.test.ts`, Kotlin `SemanticsNegativeSpec`), so the conformance
    runners skip them. `run-ts-sem.ts` and the Kotlin harness have always skipped them;
    this one did not, which put two dumps in `out-py-sem/` with no TS golden to compare
    against and made the `py-sem-vs-ts` directory diff fail on extra files alone.
    """
    return sorted(p for p in FIXTURES.iterdir() if p.is_dir() and not p.name.endswith("-negative"))


def main() -> int:
    OUT_PY_SEM.mkdir(parents=True, exist_ok=True)
    status = 0

    for fixture in single_fixtures():
        result = parse_file(fixture)
        if result.errors:
            print(f"warning: {fixture.name}: parse errors", file=sys.stderr)
        text = dump_sem_docs([(result, fixture.name)])
        (OUT_PY_SEM / (fixture.stem + ".json")).write_text(text, encoding="utf-8")

    for directory in dir_fixtures():
        docs: list[tuple[ParseResult, str]] = []
        for sub in sorted(directory.iterdir()):
            if sub.is_file() and sub.suffix == ".ttrm":
                result = parse_file(sub)
                if result.errors:
                    print(f"warning: {directory.name}/{sub.name}: parse errors", file=sys.stderr)
                docs.append((result, f"{directory.name}/{sub.name}"))
        text = dump_sem_docs(docs)
        (OUT_PY_SEM / (directory.name + ".json")).write_text(text, encoding="utf-8")

    print(
        f"dumped {len(single_fixtures())} single-file + {len(dir_fixtures())} "
        f"multi-doc semantics fixtures to {OUT_PY_SEM}"
    )
    return status


if __name__ == "__main__":
    raise SystemExit(main())
