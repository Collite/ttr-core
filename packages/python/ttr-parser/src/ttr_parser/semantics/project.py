# SPDX-License-Identifier: Apache-2.0
"""Project convenience entry point (contracts §3.0).

`load_project(root)` parses a directory, upserts every document plus the stock
CNC vocab (under `stock://`) into one `SymbolTable`, and returns a `Project` —
the common consumer flow. `Project.diagnostics()` aggregates parse errors,
resolution failures, and validation into one diagnostic stream.
"""

from __future__ import annotations

from pathlib import Path

from ..diagnostics import DiagnosticCode, DiagnosticSeverity
from ..loader import parse_directory, parse_string
from ..model import ParseError, ParseResult, SourceLocation
from .default_schema import default_schema_for_kind
from .references import collect_all_references, enclosing_qname_of, kind_of
from .resolver import (
    ResolutionContext,
    ResolutionResult,
    Resolver,
    Unresolved,
)
from .stock_loader import _read_stock
from .symbol_table import SymbolTable
from .validator import MD_KINDS, ValidationDiagnostic, Validator

_STOCK_URI = "stock://cnc-roles.ttrm"


def _error_location(err: ParseError) -> SourceLocation:
    """A SourceLocation for a parse error. `ParseError.column` is 1-indexed for
    display; `SourceLocation.column` is 0-indexed."""
    column = max(err.column - 1, 0)
    return SourceLocation(
        file=err.file,
        line=err.line,
        column=column,
        end_line=err.line,
        end_column=column,
        offset_start=0,
        offset_end=0,
    )


class Project:
    def __init__(
        self, symbols: SymbolTable, results: tuple[ParseResult, ...]
    ) -> None:
        self.symbols = symbols
        self.results = results
        self._resolver = Resolver(symbols)

    def resolve(self, reference: str, context: ResolutionContext) -> ResolutionResult:
        return self._resolver.resolve_reference(reference, context)

    def validate(self) -> tuple[ValidationDiagnostic, ...]:
        return Validator().validate(self.results, self.symbols)

    def diagnostics(self) -> tuple[ValidationDiagnostic, ...]:
        """Parse errors + unresolved references + validation, aggregated."""
        out: list[ValidationDiagnostic] = []

        for result in self.results:
            for err in result.errors:
                out.append(
                    ValidationDiagnostic(
                        code=err.code,
                        severity=DiagnosticSeverity.ERROR,
                        source=_error_location(err),
                        message=err.message,
                    )
                )

        for result in self.results:
            out.extend(self._resolution_diagnostics(result))

        out.extend(self.validate())
        return tuple(out)

    def _resolution_diagnostics(
        self, result: ParseResult
    ) -> list[ValidationDiagnostic]:
        directive = result.model_directive
        doc_schema = directive.model_code if directive else ""
        namespace = (directive.schema if directive else "") or ""
        package = result.package_name or ""

        out: list[ValidationDiagnostic] = []
        for collected in collect_all_references(result.definitions):
            # MD cross-references are the md resolver's (TS `md-unknown-ref`), not the generic one's.
            if kind_of(collected.owner_def) in MD_KINDS and not collected.cn_context:
                continue
            schema_code = doc_schema or default_schema_for_kind(
                collected.owner_def.kind
            )
            enclosing = enclosing_qname_of(
                collected.owner_def, schema_code, namespace, package
            )
            res = self._resolver.resolve_reference(
                collected.path,
                ResolutionContext(
                    schema_code=schema_code,
                    namespace=namespace,
                    imports=result.imports,
                    package_name=package,
                    enclosing_qname=enclosing,
                ),
                cn_context=collected.cn_context,
            )
            if isinstance(res, Unresolved) and (res.reason == "ambiguous" or not collected.soft):
                code = (
                    DiagnosticCode.AMBIGUOUS_REFERENCE
                    if res.reason == "ambiguous"
                    else DiagnosticCode.UNRESOLVED_REFERENCE
                )
                severity = (
                    DiagnosticSeverity.ERROR
                    if res.reason == "ambiguous"
                    else DiagnosticSeverity.WARNING
                )
                out.append(
                    ValidationDiagnostic(
                        code=code,
                        severity=severity,
                        source=collected.source,
                        message=f"Unresolved reference: '{collected.path}'",
                    )
                )
        return out


def load_project(root: str | Path, *, with_stock: bool = True) -> Project:
    results = tuple(parse_directory(root))
    symbols = SymbolTable()
    for result in results:
        symbols.upsert_document(result.source_file, result)
    if with_stock:
        # Upsert the stock cnc roles under the stock:// URI so the symbol table's
        # is_stock_cnc gate stores them under the doubled cnc.cnc.role.* form the
        # resolver's auto-import step looks for.
        stock_result = parse_string(_read_stock(), _STOCK_URI)
        if not stock_result.errors:
            symbols.upsert_document(_STOCK_URI, stock_result)
    return Project(symbols, results)
