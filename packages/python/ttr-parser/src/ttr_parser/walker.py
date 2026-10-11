# SPDX-License-Identifier: Apache-2.0
"""TTR parse-tree → typed AST walker (P2.3, the core port).

A faithful port of `packages/parser/src/walker.ts` (TS, 1863 lines) and
`packages/kotlin/ttr-parser/src/main/kotlin/.../walker/TtrWalker.kt`. The
binding instruction: **mirror walker.ts exactly**; cross-check each builder
against the Kotlin walker when in doubt.

The walker is intentionally **not** an ANTLR `ParseTreeVisitor` subclass
(even though we generated the visitor base in `_generated/`) — it traverses
the parse tree via direct context method calls, the same way walker.ts and
TtrWalker.kt do. The visitor base is generated but unused (harmless
artifact).

Public API (the only entry point used by the loader):
    `walk_document(parse_tree, file_label) -> WalkResult`

Where `WalkResult` carries the parsed `definitions`, the file-level
`model_directive`/`package_name`/`imports`, and the accumulated `errors`
and `warnings`.

Parse-tree contexts are typed `Any` throughout: ANTLR's generated Python
context classes are awkward to thread through `mypy --strict`, and the walker
only navigates them by the rule-accessor names the grammar guarantees. The
field *outputs* are fully typed via the `model` dataclasses, so type safety
holds at the module boundary even though the tree navigation itself is untyped.
"""
from __future__ import annotations

import re
from types import MappingProxyType
from typing import Any

from antlr4 import ParserRuleContext, TerminalNode
from antlr4.Token import CommonToken

from .dedent import dedent_with_indent
from .diagnostics import DiagnosticCode
from .model import (
    AreaDef,
    AttributeDef,
    BindingColumnBareId,
    BindingColumnEntry,
    BindingColumnObject,
    BindingColumnValue,
    BindingProperty,
    BindingPropertyBareId,
    BindingPropertyBlock,
    BoolValue,
    ColumnDef,
    ConstraintDef,
    DataType,
    Definition,
    DrillMapDef,
    EngineDef,
    EntityDef,
    Er2CncRoleDef,
    Er2DbAttributeDef,
    Er2DbEntityDef,
    Er2DbRelationDef,
    ExampleDef,
    ExecutorDef,
    FkDef,
    FunctionCall,
    IdValue,
    ImportStatement,
    IndexDef,
    LexiconBlock,
    ListValue,
    LocalizedStringListValue,
    LocalizedStringValue,
    MatchMethodValue,
    ModelDirective,
    NullValue,
    NumberValue,
    ObjectValue,
    ParseError,
    ParseWarning,
    PatternDef,
    ProcedureDef,
    ProjectDef,
    PropertyValue,
    QueryDef,
    Reference,
    RelationDef,
    RoleDef,
    SchemaDef,
    SearchHintsValue,
    SecurityBlock,
    SecurityStatement,
    SemanticsBlock,
    SemanticsValue,
    SourceLocation,
    StorageDef,
    StringValue,
    TableDef,
    TaggedBlockValue,
    TargetObjectValue,
    TargetReferenceValue,
    TargetValue,
    TermDef,
    TripleStringValue,
    ViewDef,
    WorldDef,
    WorldSchemaDef,
    WorldSchemaField,
)
from .tag_registry import resolve_tag

__all__ = ["WalkResult", "walk_document"]

# ---------------------------------------------------------------------------
# WalkResult
# ---------------------------------------------------------------------------


class WalkResult:
    """Outcome of walking a `DocumentContext`. Internal — the loader converts
    it into a public `ParseResult`."""

    __slots__ = (
        "definitions", "model_directive", "errors", "warnings", "package_name", "imports",
        "security_blocks",
    )

    def __init__(
        self,
        definitions: tuple[Definition, ...],
        model_directive: ModelDirective | None,
        errors: tuple[ParseError, ...],
        warnings: tuple[ParseWarning, ...],
        package_name: str | None,
        imports: tuple[ImportStatement, ...],
        security_blocks: tuple[SecurityBlock, ...] = (),
    ) -> None:
        self.definitions = definitions
        self.model_directive = model_directive
        self.errors = errors
        self.warnings = warnings
        self.package_name = package_name
        self.imports = imports
        self.security_blocks = security_blocks


# ---------------------------------------------------------------------------
# Source-location helper — the load-bearing span-invariant utility.
# ---------------------------------------------------------------------------


def _token_line(tok: CommonToken | None) -> int:
    """`tok.line` is 1-indexed; ANTLR tokens always have it."""
    return tok.line if tok is not None else 1


def _token_column(tok: CommonToken | None) -> int:
    """`tok.column` is the 0-indexed `charPositionInLine`."""
    return tok.column if tok is not None else 0


def _token_offset_start(tok: CommonToken | None) -> int:
    """Byte offset of the first character of the token (0-indexed)."""
    return tok.start if tok is not None else 0


def _token_offset_end_exclusive(tok: CommonToken | None) -> int:
    """Byte offset one past the last character of the token (0-indexed, exclusive)."""
    return (tok.stop + 1) if tok is not None else 0


def make_source_location(ctx: ParserRuleContext | TerminalNode | Any, file: str) -> SourceLocation:
    """Build a `SourceLocation` for `ctx`.

    Mirrors walker.ts `makeSourceLocation` (the multi-token-span invariant):
    `end_column = stop_token.column + len(stop_token.text)` — **NOT**
    `start_column + span_length`. The conformance dump and the LSP-style
    downstream consumers depend on this invariant; tests pin it
    (`test_source_location.py`).

    Falls back gracefully when `start`/`stop` tokens are absent (zero-span
    empty contexts).
    """
    start_tok = getattr(ctx, "start", None)
    stop_tok = getattr(ctx, "stop", None)
    if start_tok is None:
        start_tok = stop_tok
    if stop_tok is None:
        stop_tok = start_tok
    # Default to 1-indexed line 1 / 0-indexed column 0 / 0 offset when both are None.
    if start_tok is None:
        return SourceLocation(file, 1, 0, 1, 0, 0, 0)
    if stop_tok is None:
        stop_tok = start_tok
    stop_len = (stop_tok.stop - stop_tok.start + 1) if stop_tok.stop >= stop_tok.start else 1
    return SourceLocation(
        file=file,
        line=start_tok.line,
        column=start_tok.column,
        end_line=stop_tok.line,
        end_column=stop_tok.column + stop_len,
        offset_start=start_tok.start,
        offset_end=stop_tok.stop + 1,
    )


def _loc_of(ctx: Any, file: str) -> SourceLocation:
    """`make_source_location` for sub-contexts that may be `None`-missing."""
    if ctx is None:
        return SourceLocation.UNKNOWN
    return make_source_location(ctx, file)


# ---------------------------------------------------------------------------
# Generic helpers
# ---------------------------------------------------------------------------


def _tok_text(tok: Any) -> str:
    """Read a token's text whether it's a `CommonToken` (`.text`) or a
    `TerminalNode` from the parse tree (`.getText()`).

    ANTLR Python returns `TerminalNode` for token-accessor methods
    (`ctx.STRING_LITERAL()`, etc.); `Token.getTokenStream().tokens` returns
    raw `CommonToken`s. Both shapes carry the same payload; we accept both.
    """
    if tok is None:
        return ""
    if isinstance(tok, CommonToken):
        return tok.text or ""
    return tok.getText() or ""


def _str_lit_value(tok: Any) -> str:
    """Strip the quotes off a STRING_LITERAL token and unescape backslash-X.

    Mirrors walker.ts `StringLiteralForm` line 504: `raw.slice(1, -1).replace(/\\(.)/g, '$1')`.
    Only handles the simple backslash-escapes the lexer actually emits; the
    ANTLR STRING_LITERAL rule accepts `\\.` so we just drop the backslash.
    """
    raw = _tok_text(tok)
    if len(raw) < 2:
        return ""
    inner = raw[1:-1]
    return re.sub(r"\\(.)", r"\1", inner)


def _visit_string_value(ctx: Any, file: str) -> str:
    """Visit a `stringLiteralForm` context and return the unwrapped string.

    Convenience over `_visit_string_literal_form` for the common
    "description" / "label" use-cases where the consumer wants the string
    content, not the `StringValue | TripleStringValue` carrier.
    """
    return _visit_string_literal_form(ctx, file).raw


def _triple_lit_value(tok: Any) -> str:
    """Strip the triple-quote delimiters off a TRIPLE_STRING_LITERAL token.

    The body is then dedented by the caller via `dedent.dedent_with_indent`.
    """
    raw = _tok_text(tok)
    if len(raw) < 6:
        return raw
    return raw[3:-3]


def _unquote_triple(tok: Any) -> str:
    """A tagged block or plain triple-string: drop the triple-quote delimiters."""
    return _triple_lit_value(tok)


def _bool_text(tok: Any) -> bool | None:
    """Read a BOOLEAN_LITERAL token as Python bool."""
    if tok is None:
        return None
    return _tok_text(tok) == "true"


def _number_value(tok: Any) -> float:
    """Read a NUMBER_LITERAL token as Python float (matches Kotlin Double)."""
    return float(_tok_text(tok) or "0")


def _id_text(ctx: Any) -> str:
    """`IdContext.getText()` already concatenates dotted parts with `.`."""
    if ctx is None:
        return ""
    return ctx.getText() or ""


def _id_parts(ctx: Any) -> tuple[str, ...]:
    """`IdContext.idPart()` → tuple of bare parts."""
    if ctx is None:
        return ()
    return tuple(p.getText() or "" for p in ctx.idPart())


def _build_reference(ctx: Any, file: str) -> Reference | None:
    """Build a `Reference` from an `IdContext` (or None if absent)."""
    if ctx is None:
        return None
    path = _id_text(ctx)
    return Reference(path=path, parts=_id_parts(ctx), source=_loc_of(ctx, file))


# ---------------------------------------------------------------------------
# Walk document — entry point
# ---------------------------------------------------------------------------


def walk_document(doc: Any, file: str) -> WalkResult:
    """Top-down walk of a `DocumentContext` to typed `Definition`s.

    Mirrors `walker.ts` `walkDocument` and `TtrWalker.visitDocument`.
    Note the TS/Kotlin walkers also handle `graph { ... }` blocks (and emit
    a "wrong file kind" diagnostic when both graph and defs coexist) — the
    Python port is **read-only + models-only** per INDEX.md scope, so
    `graphBlock` is silently ignored.
    """
    errors: list[ParseError] = []
    warnings: list[ParseWarning] = []

    schema = doc.modelDirective()
    model_directive = _visit_model_directive(schema, file) if schema is not None else None

    definitions: list[Definition] = []
    for def_ctx in doc.definition() or ():
        d = _visit_definition(def_ctx, file, warnings, errors)
        if d is not None:
            definitions.append(d)

    package_name: str | None = None
    pkg_ctx = doc.packageDecl()
    if pkg_ctx is not None:
        package_name = _id_text(pkg_ctx.qualifiedName().id_())

    imports_list: list[ImportStatement] = []
    for imp_ctx in doc.importDecl() or ():
        target = _id_text(imp_ctx.qualifiedName().id_())
        imports_list.append(
            ImportStatement(
                target=target,
                wildcard=imp_ctx.STAR() is not None,
                source=_loc_of(imp_ctx, file),
            )
        )

    security_blocks = tuple(
        _visit_security_block(sb, file) for sb in doc.securityBlock() or ()
    )

    return WalkResult(
        definitions=tuple(definitions),
        model_directive=model_directive,
        errors=tuple(errors),
        warnings=tuple(warnings),
        package_name=package_name,
        imports=tuple(imports_list),
        security_blocks=security_blocks,
    )


def _visit_model_directive(ctx: Any, file: str) -> ModelDirective:
    code_ctx = ctx.modelCode()
    schema_code = (
        "db" if code_ctx.DB()
        else "er" if code_ctx.ER()
        else "binding" if code_ctx.BINDING()
        else "query" if code_ctx.QUERY()
        else "cnc" if code_ctx.CNC()
        else "world" if code_ctx.WORLD()
        else "lexicon" if code_ctx.LEXICON()
        else ""
    )
    # v4.4: `MODEL modelCode (SCHEMA id)? (LOCALE id)?` — up to two id slots. In
    # ANTLR-Python `id_()` returns a list; assign positionally by which of
    # SCHEMA / LOCALE is present (ids stay in order).
    ids = ctx.id_()
    if not isinstance(ids, list):
        ids = [ids] if ids is not None else []
    cursor = 0
    namespace: str | None = None
    locale: str | None = None
    if ctx.SCHEMA() is not None and cursor < len(ids):
        namespace = ids[cursor].getText()
        cursor += 1
    if ctx.LOCALE() is not None and cursor < len(ids):
        locale = ids[cursor].getText()
        cursor += 1
    return ModelDirective(model_code=schema_code, schema=namespace, locale=locale, source=_loc_of(ctx, file))


# ---------------------------------------------------------------------------
# Definition dispatch
# ---------------------------------------------------------------------------


def _visit_definition(ctx: Any, file: str, warnings: list[ParseWarning], errors: list[ParseError]) -> Definition | None:
    od = ctx.objectDefinition()
    if od is None:
        return None
    name = _id_text(od.id_())
    src = make_source_location(ctx, file)
    # Match by token to avoid relying on ANTLR rule-name casing.
    if od.PROJECT() is not None:
        return _visit_model(od, name, src, file, warnings)
    if od.TABLE() is not None:
        return _visit_table(od, name, src, file, warnings, errors)
    if od.VIEW() is not None:
        return _visit_view(od, name, src, file, warnings, errors)
    if od.COLUMN() is not None:
        return _visit_column(od, name, src, file, warnings, errors)
    if od.INDEX() is not None:
        return _visit_index(od, name, src, file, warnings)
    if od.CONSTRAINT() is not None:
        return _visit_constraint(od, name, src, file, warnings)
    if od.FK() is not None:
        return _visit_fk(od, name, src, file, warnings)
    if od.PROCEDURE() is not None:
        return _visit_procedure(od, name, src, file, warnings, errors)
    if od.ENTITY() is not None:
        return _visit_entity(od, name, src, file, warnings, errors)
    if od.ATTRIBUTE() is not None:
        return _visit_attribute(od, name, src, file, warnings, errors)
    if od.RELATION() is not None:
        return _visit_relation(od, name, src, file, warnings)
    if od.ER2DB_ENTITY() is not None:
        return _visit_er2db_entity(od, name, src, file, warnings)
    if od.ER2DB_ATTRIBUTE() is not None:
        return _visit_er2db_attribute(od, name, src, file, warnings)
    if od.ER2DB_RELATION() is not None:
        return _visit_er2db_relation(od, name, src, file, warnings)
    if od.QUERY() is not None:
        return _visit_query(od, name, src, file, warnings, errors)
    if od.ROLE() is not None:
        return _visit_role(od, name, src, file, warnings)
    if od.ER2CNC_ROLE() is not None:
        return _visit_er2cnc_role(od, name, src, file, warnings)
    if od.DRILL_MAP() is not None:
        return _visit_drill_map(od, name, src, file, warnings)
    if od.AREA() is not None:
        return _visit_area(od, name, src, file, warnings)
    if od.SCHEMA() is not None:
        return _visit_schema(od, name, src, file, warnings, errors)
    if od.WORLD() is not None:
        return _visit_world(od, name, src, file, warnings)
    # v4.4 lexicon kinds (model lexicon) — one shared body, kind tag distinguishes.
    if od.TERM() is not None:
        return _visit_lexicon_entry(od, "term", name, src, file)
    if od.PATTERN() is not None:
        return _visit_lexicon_entry(od, "pattern", name, src, file)
    if od.EXAMPLE() is not None:
        return _visit_lexicon_entry(od, "example", name, src, file)
    return None


def _visit_lexicon_entry(od: Any, kind: str, name: str, source: SourceLocation, file: str) -> Definition:
    """v4.4 — a canonical lexicon entry (`def term|pattern|example`). One shared
    permissive body; per-kind required-field validity lives in semantics."""
    description: str | None = None
    description_localized: LocalizedStringValue | None = None
    tags: tuple[str, ...] = ()
    target: Reference | None = None
    forms: tuple[str, ...] = ()
    match: str | None = None
    text: str | None = None
    for p in od.lexiconEntryDef().lexiconEntryProperty():
        d = p.descriptionProperty()
        if d is not None and description is None and description_localized is None:
            description, description_localized = _visit_description(d, file)
        t = p.tagsProperty()
        if t is not None:
            tags = _visit_list_of_strings(t.listOfStrings(), file)
        fp = p.forProperty()
        if fp is not None:
            target = _build_reference(fp.id_(), file)
        fm = p.formsProperty()
        if fm is not None:
            forms = _visit_list_of_strings(fm.listOfStrings(), file)
        mp = p.matchProperty()
        if mp is not None:
            match = _visit_string_value(mp.stringLiteralForm(), file)
        tp = p.textProperty()
        if tp is not None:
            text = _visit_string_value(tp.stringLiteralForm(), file)
    cls = {"term": TermDef, "pattern": PatternDef, "example": ExampleDef}[kind]
    return cls(name=name, source=source, description=description, description_localized=description_localized, tags=tags,
              target=target, forms=forms, match=match, text=text)


def _visit_lexicon_block(ctx: Any, file: str) -> LexiconBlock:
    """v4.4 — inline `lexicon { … }` sugar. Free-form object; the canonical
    `terms`/`patterns`/`examples` list-of-string keys are extracted."""
    obj = _visit_object(ctx.object_(), file)

    def _list_key(key: str) -> tuple[str, ...]:
        val = obj.entries.get(key)
        if isinstance(val, ListValue):
            out: list[str] = []
            for item in val.items:
                if isinstance(item, (StringValue, TripleStringValue)):
                    out.append(item.raw)
            return tuple(out)
        return ()

    return LexiconBlock(terms=_list_key("terms"), patterns=_list_key("patterns"), examples=_list_key("examples"))


def _inline_lexicon_of(props: Any, file: str) -> LexiconBlock | None:
    """Scan a carrier's property list for an inline `lexicon { … }` block (v4.4)."""
    for p in props:
        lx = p.lexiconBlockProperty()
        if lx is not None:
            return _visit_lexicon_block(lx, file)
    return None


# ---------------------------------------------------------------------------
# Per-kind visitors (db)
# ---------------------------------------------------------------------------


def _visit_model(od: Any, name: str, source: SourceLocation, file: str, warnings: list[ParseWarning]) -> ProjectDef:
    props = od.projectDef().projectProperty()
    description: str | None = None
    description_localized: LocalizedStringValue | None = None
    tags: tuple[str, ...] = ()
    version: str | None = None
    for p in props:
        d = p.descriptionProperty()
        if d is not None and description is None and description_localized is None:
            description, description_localized = _visit_description(d, file)
        t = p.tagsProperty()
        if t is not None:
            tags = _visit_list_of_strings(t.listOfStrings(), file)
        v = p.versionProperty()
        if v is not None:
            version = _str_lit_value(v.STRING_LITERAL())
    return ProjectDef(name=name, source=source, description=description, description_localized=description_localized, tags=tags, version=version)


def _visit_table(od: Any, name: str, source: SourceLocation, file: str, warnings: list[ParseWarning], errors: list[ParseError]) -> TableDef:
    props = od.tableDef().tableProperty()
    description: str | None = None
    description_localized: LocalizedStringValue | None = None
    tags: tuple[str, ...] = ()
    primary_key: tuple[str, ...] = ()
    columns: tuple[ColumnDef, ...] = ()
    indices: tuple[IndexDef, ...] = ()
    constraints: tuple[ConstraintDef, ...] = ()
    search = SearchHintsValue()
    semantics: SemanticsBlock | None = None
    for p in props:
        d = p.descriptionProperty()
        if d is not None and description is None and description_localized is None:
            description, description_localized = _visit_description(d, file)
        t = p.tagsProperty()
        if t is not None:
            tags = _visit_list_of_strings(t.listOfStrings(), file)
        pk = p.primaryKeyProperty()
        if pk is not None:
            primary_key = tuple(_visit_primary_key_value(pk.primaryKeyValue(), file))
        c = p.columnsProperty()
        if c is not None:
            columns = tuple(_visit_column_def_list(c.columnDefList(), file, warnings, errors))
        i = p.indicesProperty()
        if i is not None:
            indices = tuple(_visit_index_def_list(i.indexDefList(), file, warnings))
        ct = p.constraintsProperty()
        if ct is not None:
            constraints = tuple(_visit_constraint_def_list(ct.constraintDefList(), file, warnings))
        s = p.searchBlockProperty()
        if s is not None:
            search = _visit_search_block(s.searchBlock(), file)
        sem = p.semanticsBlockProperty()
        if sem is not None:
            semantics = _visit_semantics_block(sem, file, errors)
    return TableDef(
        name=name, source=source, description=description, description_localized=description_localized, tags=tags,
        primary_key=primary_key, columns=columns, indices=indices,
        constraints=constraints, search=search, semantics=semantics,
        lexicon=_inline_lexicon_of(props, file),
    )


def _visit_view(od: Any, name: str, source: SourceLocation, file: str, warnings: list[ParseWarning], errors: list[ParseError]) -> ViewDef:
    props = od.viewDef().viewProperty()
    description: str | None = None
    description_localized: LocalizedStringValue | None = None
    tags: tuple[str, ...] = ()
    columns: tuple[ColumnDef, ...] = ()
    definition_sql: PropertyValue | None = None
    search = SearchHintsValue()
    for p in props:
        d = p.descriptionProperty()
        if d is not None and description is None and description_localized is None:
            description, description_localized = _visit_description(d, file)
        t = p.tagsProperty()
        if t is not None:
            tags = _visit_list_of_strings(t.listOfStrings(), file)
        c = p.columnsProperty()
        if c is not None:
            columns = tuple(_visit_column_def_list(c.columnDefList(), file, warnings, errors))
        ds = p.definitionSqlProperty()
        if ds is not None:
            definition_sql = _visit_embedded_block(ds.embeddedBlock(), file, warnings)
        s = p.searchBlockProperty()
        if s is not None:
            search = _visit_search_block(s.searchBlock(), file)
    return ViewDef(
        name=name, source=source, description=description, description_localized=description_localized, tags=tags,
        columns=columns, definition_sql=definition_sql, search=search,
    )


def _visit_schema(od: Any, name: str, source: SourceLocation, file: str, warnings: list[ParseWarning], errors: list[ParseError]) -> SchemaDef:
    """Grammar 0.14 — `def schema <id> { description?, tags?, columns: [...] }` (a named row schema)."""
    description: str | None = None
    description_localized: LocalizedStringValue | None = None
    tags: tuple[str, ...] = ()
    columns: tuple[ColumnDef, ...] = ()
    for p in od.schemaDef().schemaProperty():
        d = p.descriptionProperty()
        if d is not None and description is None and description_localized is None:
            description, description_localized = _visit_description(d, file)
        t = p.tagsProperty()
        if t is not None:
            tags = _visit_list_of_strings(t.listOfStrings(), file)
        c = p.columnsProperty()
        if c is not None:
            columns = tuple(_visit_column_def_list(c.columnDefList(), file, warnings, errors))
    return SchemaDef(
        name=name, source=source, description=description, description_localized=description_localized, tags=tags,
        columns=columns,
    )


def _visit_column(od: Any, name: str, source: SourceLocation, file: str, warnings: list[ParseWarning], errors: list[ParseError]) -> ColumnDef:
    """Top-level `def column <id> { ... }` — uses the `columnProperty` rule on the columnDef."""
    return _visit_column_inline(od.columnDef(), name, source, file, warnings, errors)


def _visit_index(od: Any, name: str, source: SourceLocation, file: str, warnings: list[ParseWarning]) -> IndexDef:
    return _visit_index_inline(od.indexDef(), name, source, file, warnings)


def _visit_constraint(od: Any, name: str, source: SourceLocation, file: str, warnings: list[ParseWarning]) -> ConstraintDef:
    return _visit_constraint_inline(od.constraintDef(), name, source, file, warnings)


def _visit_fk(od: Any, name: str, source: SourceLocation, file: str, warnings: list[ParseWarning]) -> FkDef:
    props = od.fkDef().fkProperty()
    description: str | None = None
    description_localized: LocalizedStringValue | None = None
    tags: tuple[str, ...] = ()
    frm: PropertyValue | None = None
    to: PropertyValue | None = None
    for p in props:
        d = p.descriptionProperty()
        if d is not None and description is None and description_localized is None:
            description, description_localized = _visit_description(d, file)
        t = p.tagsProperty()
        if t is not None:
            tags = _visit_list_of_strings(t.listOfStrings(), file)
        fp = p.fromProperty()
        if fp is not None and fp.value() is not None and frm is None:
            frm = _visit_value(fp.value(), file)
        tp = p.toProperty()
        if tp is not None and tp.value() is not None and to is None:
            to = _visit_value(tp.value(), file)
    return FkDef(name=name, source=source, description=description, description_localized=description_localized, tags=tags, from_=frm, to=to)


def _visit_procedure(od: Any, name: str, source: SourceLocation, file: str, warnings: list[ParseWarning], errors: list[ParseError]) -> ProcedureDef:
    props = od.procedureDef().procedureProperty()
    description: str | None = None
    description_localized: LocalizedStringValue | None = None
    tags: tuple[str, ...] = ()
    parameters: tuple[PropertyValue, ...] = ()
    result_columns: tuple[ColumnDef, ...] = ()
    for p in props:
        d = p.descriptionProperty()
        if d is not None and description is None and description_localized is None:
            description, description_localized = _visit_description(d, file)
        t = p.tagsProperty()
        if t is not None:
            tags = _visit_list_of_strings(t.listOfStrings(), file)
        pp = p.parametersProperty()
        if pp is not None:
            parameters = _visit_parameter_def_list(pp.parameterDefList(), file)
        rc = p.resultColumnsProperty()
        if rc is not None:
            result_columns = tuple(_visit_column_def_list(rc.columnDefList(), file, warnings, errors))
    return ProcedureDef(
        name=name, source=source, description=description, description_localized=description_localized, tags=tags,
        parameters=parameters, result_columns=result_columns,
    )


# ---------------------------------------------------------------------------
# Per-kind visitors (er)
# ---------------------------------------------------------------------------


def _visit_entity(od: Any, name: str, source: SourceLocation, file: str, warnings: list[ParseWarning], errors: list[ParseError]) -> EntityDef:
    props = od.entityDef().entityProperty()
    description: str | None = None
    description_localized: LocalizedStringValue | None = None
    tags: tuple[str, ...] = ()
    label_plural: str | None = None
    name_attribute: Reference | None = None
    code_attribute: Reference | None = None
    aliases: tuple[str, ...] = ()
    attributes: tuple[AttributeDef, ...] = ()
    roles: tuple[Reference, ...] = ()
    display_label: LocalizedStringValue | None = None
    search = SearchHintsValue()
    semantics: SemanticsBlock | None = None
    binding: BindingProperty | None = None
    for p in props:
        d = p.descriptionProperty()
        if d is not None and description is None and description_localized is None:
            description, description_localized = _visit_description(d, file)
        t = p.tagsProperty()
        if t is not None:
            tags = _visit_list_of_strings(t.listOfStrings(), file)
        lp = p.labelPluralProperty()
        if lp is not None:
            label_plural = _str_lit_value(lp.STRING_LITERAL())
        na = p.nameAttributeProperty()
        if na is not None:
            name_attribute = _build_reference(na.id_(), file)
        ca = p.codeAttributeProperty()
        if ca is not None:
            code_attribute = _build_reference(ca.id_(), file)
        al = p.aliasesProperty()
        if al is not None:
            aliases = _visit_list_of_strings(al.listOfStrings(), file)
        at = p.attributesProperty()
        if at is not None:
            attributes = tuple(_visit_attribute_def_list(at.attributeDefList(), file, warnings, errors))
        rl = p.rolesProperty()
        if rl is not None:
            roles = tuple(_visit_list_of_ids_as_refs(rl.listOfIds(), file))
        dl = p.displayLabelProperty()
        if dl is not None:
            display_label = _visit_localized_string(dl.localizedString(), file)
        s = p.searchBlockProperty()
        if s is not None:
            search = _visit_search_block(s.searchBlock(), file)
        sem = p.semanticsBlockProperty()
        if sem is not None:
            semantics = _visit_semantics_block(sem, file, errors)
        m = p.bindingProperty()
        if m is not None:
            binding = _visit_binding_property(m, file)
    return EntityDef(
        name=name, source=source, description=description, description_localized=description_localized, tags=tags,
        label_plural=label_plural, name_attribute=name_attribute,
        code_attribute=code_attribute, aliases=aliases, attributes=attributes,
        roles=roles, display_label=display_label, search=search, semantics=semantics,
        lexicon=_inline_lexicon_of(props, file), binding=binding,
    )


def _visit_attribute(od: Any, name: str, source: SourceLocation, file: str, warnings: list[ParseWarning], errors: list[ParseError]) -> AttributeDef:
    """Top-level `def attribute <id> { ... }`."""
    return _visit_attribute_inline(od.attributeDef(), name, source, file, warnings, errors)


def _visit_relation(od: Any, name: str, source: SourceLocation, file: str, warnings: list[ParseWarning]) -> RelationDef:
    props = od.relationDef().relationProperty()
    description: str | None = None
    description_localized: LocalizedStringValue | None = None
    tags: tuple[str, ...] = ()
    frm: PropertyValue | None = None
    to: PropertyValue | None = None
    cardinality: ObjectValue | None = None
    join: tuple[PropertyValue, ...] = ()
    search = SearchHintsValue()
    binding: BindingProperty | None = None
    for p in props:
        d = p.descriptionProperty()
        if d is not None and description is None and description_localized is None:
            description, description_localized = _visit_description(d, file)
        t = p.tagsProperty()
        if t is not None:
            tags = _visit_list_of_strings(t.listOfStrings(), file)
        fp = p.fromProperty()
        if fp is not None and fp.value() is not None and frm is None:
            frm = _visit_value(fp.value(), file)
        tp = p.toProperty()
        if tp is not None and tp.value() is not None and to is None:
            to = _visit_value(tp.value(), file)
        cp = p.cardinalityProperty()
        if cp is not None and cp.object_() is not None:
            cardinality = _visit_object(cp.object_(), file)
        jp = p.joinProperty()
        if jp is not None and jp.list_() is not None:
            join = tuple(v for v in (_visit_value(v, file) for v in jp.list_().value() or ()))
        s = p.searchBlockProperty()
        if s is not None:
            search = _visit_search_block(s.searchBlock(), file)
        m = p.bindingProperty()
        if m is not None:
            binding = _visit_binding_property(m, file)
    return RelationDef(
        name=name, source=source, description=description, description_localized=description_localized, tags=tags,
        from_=frm, to=to, cardinality=cardinality, join=join,
        search=search, binding=binding,
    )


# ---------------------------------------------------------------------------
# Per-kind visitors (map)
# ---------------------------------------------------------------------------


def _visit_er2db_entity(od: Any, name: str, source: SourceLocation, file: str, warnings: list[ParseWarning]) -> Er2DbEntityDef:
    props = od.er2dbEntityDef().er2dbEntityProperty()
    description: str | None = None
    description_localized: LocalizedStringValue | None = None
    tags: tuple[str, ...] = ()
    entity: Reference | None = None
    target: TargetValue | None = None
    where_filter: ObjectValue | None = None
    for p in props:
        d = p.descriptionProperty()
        if d is not None and description is None and description_localized is None:
            description, description_localized = _visit_description(d, file)
        t = p.tagsProperty()
        if t is not None:
            tags = _visit_list_of_strings(t.listOfStrings(), file)
        ep = p.entityProperty_()
        if ep is not None:
            entity = _build_reference(ep.id_(), file)
        tp = p.targetProperty()
        if tp is not None:
            target = _visit_target_value(tp, file)
        wf = p.whereFilterProperty()
        if wf is not None and wf.object_() is not None:
            where_filter = _visit_object(wf.object_(), file)
    return Er2DbEntityDef(
        name=name, source=source, description=description, description_localized=description_localized, tags=tags,
        entity=entity, target=target, where_filter=where_filter,
    )


def _visit_er2db_attribute(od: Any, name: str, source: SourceLocation, file: str, warnings: list[ParseWarning]) -> Er2DbAttributeDef:
    props = od.er2dbAttributeDef().er2dbAttributeProperty()
    description: str | None = None
    description_localized: LocalizedStringValue | None = None
    tags: tuple[str, ...] = ()
    attribute: Reference | None = None
    target: TargetValue | None = None
    for p in props:
        d = p.descriptionProperty()
        if d is not None and description is None and description_localized is None:
            description, description_localized = _visit_description(d, file)
        t = p.tagsProperty()
        if t is not None:
            tags = _visit_list_of_strings(t.listOfStrings(), file)
        ap = p.attributeProperty_()
        if ap is not None:
            attribute = _build_reference(ap.id_(), file)
        tp = p.targetProperty()
        if tp is not None:
            target = _visit_target_value(tp, file)
    return Er2DbAttributeDef(
        name=name, source=source, description=description, description_localized=description_localized, tags=tags,
        attribute=attribute, target=target,
    )


def _visit_er2db_relation(od: Any, name: str, source: SourceLocation, file: str, warnings: list[ParseWarning]) -> Er2DbRelationDef:
    props = od.er2dbRelationDef().er2dbRelationProperty()
    description: str | None = None
    description_localized: LocalizedStringValue | None = None
    tags: tuple[str, ...] = ()
    relation: Reference | None = None
    fk: Reference | None = None
    for p in props:
        d = p.descriptionProperty()
        if d is not None and description is None and description_localized is None:
            description, description_localized = _visit_description(d, file)
        t = p.tagsProperty()
        if t is not None:
            tags = _visit_list_of_strings(t.listOfStrings(), file)
        rp = p.relationProperty_()
        if rp is not None:
            relation = _build_reference(rp.id_(), file)
        fp = p.fkProperty_()
        if fp is not None:
            fk = _build_reference(fp.id_(), file)
    return Er2DbRelationDef(
        name=name, source=source, description=description, description_localized=description_localized, tags=tags,
        relation=relation, fk=fk,
    )


# ---------------------------------------------------------------------------
# Per-kind visitors (query / cnc / drill_map)
# ---------------------------------------------------------------------------


def _visit_query(od: Any, name: str, source: SourceLocation, file: str, warnings: list[ParseWarning], errors: list[ParseError]) -> QueryDef:
    props = od.queryDef().queryProperty()
    description: str | None = None
    description_localized: LocalizedStringValue | None = None
    tags: tuple[str, ...] = ()
    language: str | None = None
    parameters: tuple[PropertyValue, ...] = ()
    source_text: PropertyValue | None = None
    search = SearchHintsValue()
    for p in props:
        d = p.descriptionProperty()
        if d is not None and description is None and description_localized is None:
            description, description_localized = _visit_description(d, file)
        t = p.tagsProperty()
        if t is not None:
            tags = _visit_list_of_strings(t.listOfStrings(), file)
        lp = p.languageProperty()
        if lp is not None:
            language = _visit_language_value(lp.languageValue())
        pp = p.parametersProperty()
        if pp is not None:
            parameters = _visit_parameter_def_list(pp.parameterDefList(), file)
        sp = p.sourceTextProperty()
        if sp is not None:
            source_text = _visit_embedded_block(sp.embeddedBlock(), file, warnings)
        s = p.searchBlockProperty()
        if s is not None:
            search = _visit_search_block(s.searchBlock(), file)
    return QueryDef(
        name=name, source=source, description=description, description_localized=description_localized, tags=tags,
        language=language, parameters=parameters, source_text=source_text, search=search,
    )


def _visit_role(od: Any, name: str, source: SourceLocation, file: str, warnings: list[ParseWarning]) -> RoleDef:
    props = od.roleDef().roleProperty()
    description: str | None = None
    description_localized: LocalizedStringValue | None = None
    tags: tuple[str, ...] = ()
    label: LocalizedStringValue | None = None
    search = SearchHintsValue()
    for p in props:
        d = p.descriptionProperty()
        if d is not None and description is None and description_localized is None:
            description, description_localized = _visit_description(d, file)
        t = p.tagsProperty()
        if t is not None:
            tags = _visit_list_of_strings(t.listOfStrings(), file)
        lp = p.labelProperty()
        if lp is not None:
            label = _visit_localized_string(lp.localizedString(), file)
        s = p.searchBlockProperty()
        if s is not None:
            search = _visit_search_block(s.searchBlock(), file)
    return RoleDef(name=name, source=source, description=description, description_localized=description_localized, tags=tags, label=label, search=search)


def _visit_er2cnc_role(od: Any, name: str, source: SourceLocation, file: str, warnings: list[ParseWarning]) -> Er2CncRoleDef:
    props = od.er2cncRoleDef().er2cncRoleProperty()
    description: str | None = None
    description_localized: LocalizedStringValue | None = None
    tags: tuple[str, ...] = ()
    entity: Reference | None = None
    role: Reference | None = None
    for p in props:
        d = p.descriptionProperty()
        if d is not None and description is None and description_localized is None:
            description, description_localized = _visit_description(d, file)
        t = p.tagsProperty()
        if t is not None:
            tags = _visit_list_of_strings(t.listOfStrings(), file)
        ep = p.entityProperty_()
        if ep is not None:
            entity = _build_reference(ep.id_(), file)
        rp = p.roleProperty_()
        if rp is not None:
            role = _build_reference(rp.id_(), file)
    return Er2CncRoleDef(name=name, source=source, description=description, description_localized=description_localized, tags=tags, entity=entity, role=role)


def _visit_drill_map(od: Any, name: str, source: SourceLocation, file: str, warnings: list[ParseWarning]) -> DrillMapDef:
    props = od.drillMapDef().drillMapProperty()
    description: str | None = None
    description_localized: LocalizedStringValue | None = None
    tags: tuple[str, ...] = ()
    frm: Reference | None = None
    to: Reference | None = None
    args: dict[str, str] = {}
    display: LocalizedStringValue | None = None
    override_auto = False
    for p in props:
        d = p.descriptionProperty()
        if d is not None and description is None and description_localized is None:
            description, description_localized = _visit_description(d, file)
        t = p.tagsProperty()
        if t is not None:
            tags = _visit_list_of_strings(t.listOfStrings(), file)
        fp = p.fromProperty()
        if fp is not None and fp.value() is not None and fp.value().id_() is not None and frm is None:
            frm = _build_reference(fp.value().id_(), file)
        tp = p.toProperty()
        if tp is not None and tp.value() is not None and tp.value().id_() is not None and to is None:
            to = _build_reference(tp.value().id_(), file)
        ap = p.argsProperty()
        if ap is not None and ap.drillArgsMap() is not None:
            args = _visit_drill_args_map(ap.drillArgsMap(), file)
        dp = p.displayProperty()
        if dp is not None:
            display = _visit_localized_string(dp.localizedString(), file)
        op = p.overrideProperty()
        if op is not None:
            v = _bool_text(op.BOOLEAN_LITERAL())
            override_auto = bool(v) if v is not None else False
    return DrillMapDef(
        name=name, source=source, description=description, description_localized=description_localized, tags=tags,
        from_=frm, to=to,
        args=MappingProxyType(args),
        display=display, override_auto=override_auto,
    )


def _visit_drill_args_map(ctx: Any, file: str) -> dict[str, str]:
    out: dict[str, str] = {}
    for entry in ctx.drillArgEntry() or ():
        name = _id_text(entry.id_())
        v = entry.stringLiteralForm()
        out[name] = _visit_string_value(v, file) if v is not None else ""
    return out


def _visit_area(od: Any, name: str, source: SourceLocation, file: str, warnings: list[ParseWarning]) -> AreaDef:
    """v3.0 — `def area <id> { description?, tags?, packages: [...], entities: [...] }`."""
    props = od.areaDef().areaProperty()
    description: str | None = None
    description_localized: LocalizedStringValue | None = None
    tags: tuple[str, ...] = ()
    packages: tuple[str, ...] = ()
    entities: tuple[str, ...] = ()
    package_sources: tuple[SourceLocation, ...] = ()
    entity_sources: tuple[SourceLocation, ...] = ()
    for p in props:
        d = p.descriptionProperty()
        if d is not None and description is None and description_localized is None:
            description, description_localized = _visit_description(d, file)
        t = p.tagsProperty()
        if t is not None:
            tags = _visit_list_of_strings(t.listOfStrings(), file)
        pp = p.areaPackagesProperty()
        if pp is not None:
            ids = pp.id_() or ()
            packages = tuple(_id_text(i) for i in ids)
            package_sources = tuple(make_source_location(i, file) for i in ids)
        ep = p.areaEntitiesProperty()
        if ep is not None:
            ids = ep.id_() or ()
            entities = tuple(_id_text(i) for i in ids)
            entity_sources = tuple(make_source_location(i, file) for i in ids)
    return AreaDef(
        name=name, source=source, description=description, description_localized=description_localized, tags=tags,
        packages=packages, entities=entities,
        package_sources=package_sources, entity_sources=entity_sources,
    )


# ---------------------------------------------------------------------------
# v4.1 world model (ttr-metadata M0). Manifest entries transported opaque (MD5).
# ---------------------------------------------------------------------------


def _visit_world(od: Any, name: str, source: SourceLocation, file: str, warnings: list[ParseWarning]) -> WorldDef:
    description: str | None = None
    description_localized: LocalizedStringValue | None = None
    tags: tuple[str, ...] = ()
    extends: str | None = None
    engines: list[EngineDef] = []
    executors: list[ExecutorDef] = []
    storages: list[StorageDef] = []
    for m in od.worldDef().worldMember() or ():
        wp = m.worldProperty()
        if wp is not None:
            d = wp.descriptionProperty()
            if d is not None:
                # Last-wins on repeated `description:` — matches the TS and Kotlin
                # walkers (was first-wins here, a silent cross-target parity break).
                description, description_localized = _visit_description(d, file)
            t = wp.tagsProperty()
            if t is not None:
                tags = _visit_list_of_strings(t.listOfStrings(), file)
            e = wp.extendsProperty()
            if e is not None:
                extends = _id_text(e.id_())
            continue
        member_name = _id_text(m.id_())
        member_src = make_source_location(m, file)
        if m.ENGINE() is not None:
            engines.append(_visit_engine_part(m.engineDef().enginePartProperty(), "engine", member_name, member_src, file))
        elif m.EXECUTOR() is not None:
            eng = _visit_engine_part(m.executorDef().enginePartProperty(), "executor", member_name, member_src, file)
            executors.append(
                ExecutorDef(
                    name=eng.name, source=eng.source, description=eng.description, tags=eng.tags,
                    type=eng.type, version=eng.version, extends=eng.extends, manifest=eng.manifest,
                )
            )
        elif m.STORAGE() is not None:
            storages.append(_visit_storage(m.storageDef(), member_name, member_src, file))
    return WorldDef(
        name=name, source=source, description=description, description_localized=description_localized, tags=tags, extends=extends,
        engines=tuple(engines), executors=tuple(executors), storages=tuple(storages),
    )


def _visit_engine_part(props: Any, _kind: str, name: str, source: SourceLocation, file: str) -> EngineDef:
    description: str | None = None
    description_localized: LocalizedStringValue | None = None
    tags: tuple[str, ...] = ()
    type_: str | None = None
    version: str | None = None
    extends: str | None = None
    manifest: dict[str, PropertyValue] = {}
    for p in props or ():
        d = p.descriptionProperty()
        if d is not None:
            description, description_localized = _visit_description(d, file)
        t = p.tagsProperty()
        if t is not None:
            tags = _visit_list_of_strings(t.listOfStrings(), file)
        tp = p.typeProperty()
        if tp is not None:
            type_ = tp.dataType().getText()
        v = p.versionProperty()
        if v is not None:
            version = _str_lit_value(v.STRING_LITERAL())
        e = p.extendsProperty()
        if e is not None:
            extends = _id_text(e.id_())
        pe = p.propertyEntry()
        if pe is not None:
            k_ctx = pe.key()
            key = _id_text(k_ctx.id_()) if k_ctx is not None else ""
            manifest[key] = _visit_value(pe.value(), file)
    return EngineDef(
        name=name, source=source, description=description, description_localized=description_localized, tags=tags,
        type=type_, version=version, extends=extends, manifest=MappingProxyType(manifest),
    )


def _visit_storage(ctx: Any, name: str, source: SourceLocation, file: str) -> StorageDef:
    description: str | None = None
    description_localized: LocalizedStringValue | None = None
    tags: tuple[str, ...] = ()
    type_: str | None = None
    extends: str | None = None
    via: str | None = None
    hosts: tuple[str, ...] = ()
    staging = False
    schemas: list[WorldSchemaDef] = []
    manifest: dict[str, PropertyValue] = {}
    for p in ctx.storageProperty() or ():
        d = p.descriptionProperty()
        if d is not None:
            description, description_localized = _visit_description(d, file)
        t = p.tagsProperty()
        if t is not None:
            tags = _visit_list_of_strings(t.listOfStrings(), file)
        tp = p.typeProperty()
        if tp is not None:
            type_ = tp.dataType().getText()
        e = p.extendsProperty()
        if e is not None:
            extends = _id_text(e.id_())
        vp = p.viaProperty()
        if vp is not None:
            via = _id_text(vp.id_())
        hp = p.hostsProperty()
        if hp is not None:
            hosts = _visit_list_of_ids(hp.listOfIds(), file)
        sp = p.stagingProperty()
        if sp is not None:
            staging = sp.BOOLEAN_LITERAL().getText() == "true"
        if p.SCHEMA() is not None:
            schemas.append(_visit_world_schema(p.worldSchemaDef(), _id_text(p.id_()), make_source_location(p, file), file))
        pe = p.propertyEntry()
        if pe is not None:
            k_ctx = pe.key()
            key = _id_text(k_ctx.id_()) if k_ctx is not None else ""
            manifest[key] = _visit_value(pe.value(), file)
    return StorageDef(
        name=name, source=source, description=description, description_localized=description_localized, tags=tags, type=type_, extends=extends,
        via=via, hosts=hosts, staging=staging, schemas=tuple(schemas), manifest=MappingProxyType(manifest),
    )


def _visit_world_schema(ctx: Any, name: str, source: SourceLocation, file: str) -> WorldSchemaDef:
    fields = tuple(
        WorldSchemaField(name=_id_text(f.id_()), type=f.dataType().getText(), source=make_source_location(f, file))
        for f in ctx.worldSchemaField() or ()
    )
    return WorldSchemaDef(name=name, source=source, fields=fields)


# ---------------------------------------------------------------------------
# Inline def lists
# ---------------------------------------------------------------------------


def _visit_column_def_list(ctx: Any, file: str, warnings: list[ParseWarning], errors: list[ParseError]) -> list[ColumnDef]:
    return [_visit_column_inline(inline.columnDef(), _id_text(inline.id_()), make_source_location(inline, file), file, warnings, errors) for inline in ctx.columnInline() or ()]


def _visit_index_def_list(ctx: Any, file: str, warnings: list[ParseWarning]) -> list[IndexDef]:
    return [_visit_index_inline(inline.indexDef(), _id_text(inline.id_()), make_source_location(inline, file), file, warnings) for inline in ctx.indexInline() or ()]


def _visit_constraint_def_list(ctx: Any, file: str, warnings: list[ParseWarning]) -> list[ConstraintDef]:
    return [_visit_constraint_inline(inline.constraintDef(), _id_text(inline.id_()), make_source_location(inline, file), file, warnings) for inline in ctx.constraintInline() or ()]


def _visit_attribute_def_list(ctx: Any, file: str, warnings: list[ParseWarning], errors: list[ParseError]) -> list[AttributeDef]:
    return [_visit_attribute_inline(inline.attributeDef(), _id_text(inline.id_()), make_source_location(inline, file), file, warnings, errors) for inline in ctx.attributeInline() or ()]


def _visit_parameter_def_list(ctx: Any, file: str) -> tuple[PropertyValue, ...]:
    """Each parameter is an inline `LBRACE ... RBRACE` with `name/type/label/direction` keys.

    The walker emits each as an `ObjectValue` carrying the same field set
    (matching Kotlin's `ParameterDef` shape; the TS layer uses a named
    ParameterDef dataclass). Consumers iterate `.entries`.

    `name` / `type` / `direction` are bare-identifier shapes emitted as
    `IdValue`s; `label` is a plain `StringValue`. The `type` slot carries
    just the type-name path (e.g. `"int"`, `"date"`) — not the structured
    `DataType` — matching both the Kotlin walker and the TS `ParameterDef`
    surface that the conformance dump expects (`type: {name: "int"}`).
    """
    out: list[PropertyValue] = []
    for inline in ctx.parameterInline() or ():
        entries: dict[str, PropertyValue] = {}
        for p in inline.paramProperty() or ():
            np = p.nameProperty()
            if np is not None:
                name_id = np.id_()
                entries["name"] = IdValue(
                    ref=_build_reference(name_id, file),
                    parts=_id_parts(name_id),
                    source=_loc_of(name_id, file),
                )
            tp = p.typeProperty()
            if tp is not None:
                dt = _visit_data_type(tp.dataType(), file)
                type_loc = make_source_location(tp.dataType(), file)
                entries["type"] = IdValue(
                    ref=Reference(path=dt.name, parts=(dt.name,), source=type_loc),
                    parts=(dt.name,),
                    source=type_loc,
                )
            pl = p.paramLabelProperty()
            if pl is not None:
                entries["label"] = _visit_string_literal_form(pl.stringLiteralForm(), file)
            dp = p.directionProperty()
            if dp is not None:
                d_id = dp.id_()
                entries["direction"] = IdValue(
                    ref=_build_reference(d_id, file),
                    parts=_id_parts(d_id),
                    source=_loc_of(d_id, file),
                )
        out.append(ObjectValue(entries=MappingProxyType(entries), source=_loc_of(inline, file)))
    return tuple(out)


def _visit_column_inline(ctx: Any, name: str, source: SourceLocation, file: str, warnings: list[ParseWarning], errors: list[ParseError]) -> ColumnDef:
    props = ctx.columnProperty()
    description: str | None = None
    description_localized: LocalizedStringValue | None = None
    tags: tuple[str, ...] = ()
    dt: DataType | None = None
    optional = False
    is_key = False
    indexed = False
    search = SearchHintsValue()
    semantics: SemanticsBlock | None = None
    for p in props:
        d = p.descriptionProperty()
        if d is not None and description is None and description_localized is None:
            description, description_localized = _visit_description(d, file)
        t = p.tagsProperty()
        if t is not None:
            tags = _visit_list_of_strings(t.listOfStrings(), file)
        tp = p.typeProperty()
        if tp is not None:
            dt = _visit_data_type(tp.dataType(), file)
        op = p.optionalProperty()
        if op is not None:
            v = _bool_text(op.BOOLEAN_LITERAL())
            optional = bool(v) if v is not None else False
        ik = p.isKeyProperty()
        if ik is not None:
            v = _bool_text(ik.BOOLEAN_LITERAL())
            is_key = bool(v) if v is not None else False
        ix = p.indexedProperty()
        if ix is not None:
            v = _bool_text(ix.BOOLEAN_LITERAL())
            indexed = bool(v) if v is not None else False
        s = p.searchBlockProperty()
        if s is not None:
            search = _visit_search_block(s.searchBlock(), file)
        sem = p.semanticsBlockProperty()
        if sem is not None:
            semantics = _visit_semantics_block(sem, file, errors)
    return ColumnDef(
        name=name, source=source, description=description, description_localized=description_localized, tags=tags,
        type=dt, optional=optional, is_key=is_key, indexed=indexed, search=search,
        semantics=semantics,
        lexicon=_inline_lexicon_of(props, file),
    )


def _visit_index_inline(ctx: Any, name: str, source: SourceLocation, file: str, warnings: list[ParseWarning]) -> IndexDef:
    props = ctx.indexProperty()
    description: str | None = None
    description_localized: LocalizedStringValue | None = None
    index_type: str | None = None
    columns: tuple[str, ...] = ()
    for p in props:
        d = p.descriptionProperty()
        if d is not None and description is None and description_localized is None:
            description, description_localized = _visit_description(d, file)
        it = p.indexTypeProperty()
        if it is not None and it.indexTypeValue() is not None:
            index_type = _visit_index_type_value(it.indexTypeValue())
        cnl = p.columnNamesListProperty()
        if cnl is not None:
            columns = _visit_list_of_strings(cnl.listOfStrings(), file)
    return IndexDef(name=name, source=source, description=description, description_localized=description_localized, index_type=index_type, columns=columns)


def _visit_constraint_inline(ctx: Any, name: str, source: SourceLocation, file: str, warnings: list[ParseWarning]) -> ConstraintDef:
    props = ctx.constraintProperty()
    description: str | None = None
    description_localized: LocalizedStringValue | None = None
    constraint_type: str | None = None
    columns: tuple[str, ...] = ()
    for p in props:
        d = p.descriptionProperty()
        if d is not None and description is None and description_localized is None:
            description, description_localized = _visit_description(d, file)
        ct = p.constraintTypeProperty()
        if ct is not None and ct.constraintTypeValue() is not None:
            constraint_type = _visit_constraint_type_value(ct.constraintTypeValue())
        cnl = p.columnNamesListProperty()
        if cnl is not None:
            columns = _visit_list_of_strings(cnl.listOfStrings(), file)
    return ConstraintDef(name=name, source=source, description=description, description_localized=description_localized, constraint_type=constraint_type, columns=columns)


def _visit_attribute_inline(ctx: Any, name: str, source: SourceLocation, file: str, warnings: list[ParseWarning], errors: list[ParseError]) -> AttributeDef:
    props = ctx.attributeProperty()
    description: str | None = None
    description_localized: LocalizedStringValue | None = None
    tags: tuple[str, ...] = ()
    dt: DataType | None = None
    is_key = False
    optional = False
    value_labels: dict[str, LocalizedStringValue] = {}
    value_label_aliases: dict[str, tuple[str, ...]] = {}
    display_label: LocalizedStringValue | None = None
    search = SearchHintsValue()
    semantics: SemanticsBlock | None = None
    binding: BindingProperty | None = None
    for p in props:
        d = p.descriptionProperty()
        if d is not None and description is None and description_localized is None:
            description, description_localized = _visit_description(d, file)
        t = p.tagsProperty()
        if t is not None:
            tags = _visit_list_of_strings(t.listOfStrings(), file)
        tp = p.typeProperty()
        if tp is not None:
            dt = _visit_data_type(tp.dataType(), file)
        ik = p.isKeyProperty()
        if ik is not None:
            v = _bool_text(ik.BOOLEAN_LITERAL())
            is_key = bool(v) if v is not None else False
        op = p.optionalProperty()
        if op is not None:
            v = _bool_text(op.BOOLEAN_LITERAL())
            optional = bool(v) if v is not None else False
        vl = p.valueLabelsProperty()
        if vl is not None and vl.valueLabelsBody() is not None:
            value_labels, value_label_aliases = _visit_value_labels(vl.valueLabelsBody(), file)
        dl = p.displayLabelProperty()
        if dl is not None:
            display_label = _visit_localized_string(dl.localizedString(), file)
        s = p.searchBlockProperty()
        if s is not None:
            search = _visit_search_block(s.searchBlock(), file)
        sem = p.semanticsBlockProperty()
        if sem is not None:
            semantics = _visit_semantics_block(sem, file, errors)
        m = p.bindingProperty()
        if m is not None:
            binding = _visit_binding_property(m, file)
    return AttributeDef(
        name=name, source=source, description=description, description_localized=description_localized, tags=tags,
        type=dt, is_key=is_key, optional=optional,
        value_labels=MappingProxyType(value_labels),
        value_label_aliases=MappingProxyType(value_label_aliases),
        display_label=display_label,
        search=search, semantics=semantics,
        lexicon=_inline_lexicon_of(props, file), binding=binding,
    )


# ---------------------------------------------------------------------------
# Property values (visitValue, walkLiteral, walkStringLiteralForm, …)
# ---------------------------------------------------------------------------


# ---------------------------------------------------------------------------
# Grounding Phase 1 (grammar 4.2) — the `semantics { … }` block
# ---------------------------------------------------------------------------

# Sentinel for "this value has no data meaning here" — a `functionCall`. Distinct from
# a legitimate `null`, which arrives as Python `None`; mirrors the TS `NON_SCALAR`
# symbol and the Kotlin `null` return.
_NON_SCALAR = object()


def _visit_semantics_block(ctx: Any, file: str, errors: list[ParseError]) -> SemanticsBlock:
    """Fold a `semantics { … }` block's free-form `object_` body into a record.

    The parser stays mechanical: no vocabulary/shape checks (that is the semantics
    layer's job). Ids become their identifier text, string literals are unquoted,
    numbers/booleans/null keep their primitive form, and lists/nested objects are
    carried verbatim. Only a `functionCall` value is rejected into a
    `ttr/semantics-non-scalar` diagnostic and dropped. Duplicate keys are last-wins and
    recorded in `duplicate_properties` at every depth.

    Mirrors `walkSemanticsBlock` in the TS walker and `visitSemanticsBlock` in the
    Kotlin one.
    """
    entries: dict[str, SemanticsValue] = {}
    seen: dict[str, int] = {}
    nested_duplicates: list[str] = []
    obj = ctx.object_() if ctx is not None else None
    prop_list = obj.propertyList() if obj is not None else None
    if prop_list is not None:
        for entry in prop_list.propertyEntry() or ():
            key_ctx = entry.key()
            key = key_ctx.getText() if key_ctx is not None else ""
            if not key:
                continue
            seen[key] = seen.get(key, 0) + 1
            value = _semantics_structured(entry.value(), file, key, nested_duplicates)
            if value is _NON_SCALAR:
                loc = _loc_of(entry.value() or entry, file)
                errors.append(
                    ParseError(
                        file=file,
                        # ParseError is 1-indexed for human display; SourceLocation
                        # columns are 0-indexed (contracts §2.3).
                        line=loc.line,
                        column=loc.column + 1,
                        message=f"semantics entries cannot be a function call; '{key}' has one",
                        code=DiagnosticCode.SEMANTICS_NON_SCALAR_VALUE,
                    )
                )
                continue
            entries[key] = value
    duplicates = tuple([k for k, n in seen.items() if n > 1] + nested_duplicates)
    # Source spans the `{ … }` object (the search-block convention).
    return SemanticsBlock(
        entries=MappingProxyType(entries),
        duplicate_properties=duplicates,
        source=_loc_of(obj, file) if obj is not None else SourceLocation.UNKNOWN,
    )


def _semantics_structured(
    ctx: Any, file: str, path: str, duplicates: list[str]
) -> Any:
    """Read a semantics `value` WITH its structure: scalars, lists and nested objects.

    Recurses without a depth limit, mirroring the grammar (`value : … | list | object_
    | …` is self-referential). Returns `_NON_SCALAR` for a `functionCall`, at any depth.
    `path` carries the dotted/indexed route to this value so a repeated key inside a
    nested object is recorded as e.g. `measures[1].attribute`.
    """
    if ctx is None:
        return None
    list_ctx = ctx.list_()
    if list_ctx is not None:
        items: list[SemanticsValue] = []
        for i, item in enumerate(list_ctx.value() or ()):
            walked = _semantics_structured(item, file, f"{path}[{i}]", duplicates)
            if walked is _NON_SCALAR:
                return _NON_SCALAR
            items.append(walked)
        return tuple(items)
    obj_ctx = ctx.object_()
    if obj_ctx is not None:
        obj: dict[str, SemanticsValue] = {}
        seen: dict[str, int] = {}
        prop_list = obj_ctx.propertyList()
        if prop_list is not None:
            for entry in prop_list.propertyEntry() or ():
                key_ctx = entry.key()
                key = key_ctx.getText() if key_ctx is not None else ""
                if not key:
                    continue
                seen[key] = seen.get(key, 0) + 1
                walked = _semantics_structured(entry.value(), file, f"{path}.{key}", duplicates)
                if walked is _NON_SCALAR:
                    return _NON_SCALAR
                obj[key] = walked
        duplicates.extend(f"{path}.{k}" for k, n in seen.items() if n > 1)
        return MappingProxyType(obj)
    return _semantics_scalar(ctx, file)


def _semantics_scalar(ctx: Any, file: str) -> Any:
    """A scalar semantics value, or `_NON_SCALAR` for list/object/functionCall.

    Ids collapse to their dotted text (`period_table`, `AccountingPeriod`) — resolution
    is the semantics layer's job, so the parser keeps them opaque.
    """
    id_ = ctx.id_()
    if id_ is not None:
        return _id_text(id_)
    lit = ctx.literal()
    if lit is not None:
        n = lit.NUMBER_LITERAL()
        if n is not None:
            return _number_value(n)
        b = lit.BOOLEAN_LITERAL()
        if b is not None:
            return _bool_text(b) is True
        slf = lit.stringLiteralForm()
        if slf is not None:
            walked = _visit_string_literal_form(slf, file)
            return walked.raw
        return None  # NULL_LITERAL
    return _NON_SCALAR


# ---------------------------------------------------------------------------
# PL-P4.S3 (grammar 0.11, H-1) — the document-level `security { … }` block
# ---------------------------------------------------------------------------


def _visit_security_block(ctx: Any, file: str) -> SecurityBlock:
    """Walk a `security { … }` block into its four structured §11 verbs.

    Object refs are kept as opaque qname strings (dotted ids verbatim); resolution is
    the semantics layer's job and advisory only. Mirrors `walkSecurityBlock` in the TS
    walker.
    """
    statements: list[SecurityStatement] = []
    for st in ctx.securityStatement() or ():
        ids = st.id_() or ()
        loc = _loc_of(st, file)
        if st.OWN() is not None and len(ids) >= 2:
            statements.append(
                SecurityStatement(
                    verb="own", object_ref=_id_text(ids[0]), owner=_id_text(ids[1]), source=loc
                )
            )
        elif st.CLASSIFY() is not None and len(ids) >= 2:
            statements.append(
                SecurityStatement(
                    verb="classify",
                    object_ref=_id_text(ids[0]),
                    classification=_id_text(ids[1]),
                    source=loc,
                )
            )
        elif st.GRANT() is not None and len(ids) >= 3:
            # `grant <privilege> on <object> to <grantee>` — id order is privilege,
            # object, grantee, which is NOT the order the dump emits them in.
            statements.append(
                SecurityStatement(
                    verb="grant",
                    privilege=_id_text(ids[0]),
                    object_ref=_id_text(ids[1]),
                    grantee=_id_text(ids[2]),
                    source=loc,
                )
            )
        elif st.MASK() is not None and len(ids) >= 1:
            statements.append(
                SecurityStatement(verb="mask", object_ref=_id_text(ids[0]), source=loc)
            )
    return SecurityBlock(statements=tuple(statements), source=_loc_of(ctx, file))


def _visit_value(ctx: Any, file: str) -> PropertyValue:
    if ctx is None:
        return NullValue(source=SourceLocation.UNKNOWN)
    lit = ctx.literal()
    if lit is not None:
        return _visit_literal(lit, file)
    id_ = ctx.id_()
    if id_ is not None:
        return _visit_id(id_, file)
    if ctx.list_() is not None:
        return _visit_list(ctx.list_(), file)
    if ctx.object_() is not None:
        return _visit_object(ctx.object_(), file)
    if ctx.functionCall() is not None:
        return _visit_function_call(ctx.functionCall(), file)
    return NullValue(source=make_source_location(ctx, file))


def _visit_literal(ctx: Any, file: str) -> PropertyValue:
    n = ctx.NUMBER_LITERAL()
    if n is not None:
        return NumberValue(raw=_number_value(n), source=_loc_of(ctx, file))
    b = ctx.BOOLEAN_LITERAL()
    if b is not None:
        return BoolValue(raw=_bool_text(b) is True, source=_loc_of(ctx, file))
    nu = ctx.NULL_LITERAL()
    if nu is not None:
        return NullValue(source=_loc_of(ctx, file))
    slf = ctx.stringLiteralForm()
    if slf is not None:
        return _visit_string_literal_form(slf, file)
    return NullValue(source=_loc_of(ctx, file))


def _visit_string_literal_form(ctx: Any, file: str) -> StringValue | TripleStringValue:
    """Reads STRING_LITERAL / TRIPLE_STRING_LITERAL / TAGGED_BLOCK_LITERAL.

    Per the grammar (TTR.g4), `stringLiteralForm` covers all three. A
    TAGGED_BLOCK_LITERAL here is read as a plain triple-string — the
    tag-peeling happens only in `embeddedBlock` (sourceText / definitionSql).
    """
    sl = ctx.STRING_LITERAL()
    if sl is not None:
        return StringValue(raw=_str_lit_value(sl), source=_loc_of(ctx, file))
    triple = ctx.TRIPLE_STRING_LITERAL()
    if triple is not None:
        return TripleStringValue(raw=dedent_with_indent(_triple_lit_value(triple)).value, source=_loc_of(ctx, file))
    tagged = ctx.TAGGED_BLOCK_LITERAL()
    if tagged is not None:
        return TripleStringValue(raw=dedent_with_indent(_unquote_triple(tagged)).value, source=_loc_of(ctx, file))
    return StringValue(raw="", source=_loc_of(ctx, file))


# Tagged-block lexer rule: `"""<tag>[ \t]*\r?\n<body>"""`. The lexer guarantees
# the `<tag>[ \t]*\r?\n` header is well-formed.
_TAGGED_HEADER_RE = re.compile(r"^([A-Za-z][A-Za-z0-9-]*)([ \t]*(?:\r?\n))")


def _visit_embedded_block(ctx: Any, file: str, warnings: list[ParseWarning]) -> PropertyValue:
    """Tag-peel a `sourceText` / `definitionSql` value into a `TaggedBlockValue`.

    Mirrors walker.ts `walkEmbeddedBlock`:
    - STRING_LITERAL → plain `StringValue`.
    - TRIPLE_STRING_LITERAL → dedented `TripleStringValue`.
    - TAGGED_BLOCK_LITERAL → strip the opener tag, dedent, strip one trailing
      newline. If the tag resolves in the registry, emit a `TaggedBlockValue`
      with `language`/`dialect` set; otherwise emit a warning and fall back
      to a `TripleStringValue` carrying the body verbatim.
    """
    sl = ctx.STRING_LITERAL()
    if sl is not None:
        return StringValue(raw=_str_lit_value(sl), source=_loc_of(ctx, file))
    triple = ctx.TRIPLE_STRING_LITERAL()
    if triple is not None:
        return TripleStringValue(raw=dedent_with_indent(_triple_lit_value(triple)).value, source=_loc_of(ctx, file))

    tagged = ctx.TAGGED_BLOCK_LITERAL()
    if tagged is None:
        return StringValue(raw="", source=_loc_of(ctx, file))

    raw = _tok_text(tagged)
    inner = raw[3:-3]  # strip the triple-quote delimiters
    m = _TAGGED_HEADER_RE.match(inner)
    if m is None:
        return TripleStringValue(raw=inner, source=_loc_of(ctx, file))
    tag = m.group(1)
    body = inner[m.end():]
    dedented = dedent_with_indent(body)
    # Strip exactly one trailing newline (the close-fence newline).
    value = dedented.value
    if value.endswith("\n"):
        value = value[:-1]

    loc = make_source_location(ctx, file)
    # The tag token sits immediately after the opening `"""` on the same line.
    # Use ANTLR's `line`/`column` (ctx.start) as the anchor; offset by 3 for
    # the opening fence and by tag length for the end. This matches the TS
    # `tagSource` calculation byte-for-byte.
    tag_source = SourceLocation(
        file=file,
        line=loc.line,
        column=loc.column + 3,
        end_line=loc.line,
        end_column=loc.column + 3 + len(tag),
        offset_start=loc.offset_start + 3,
        offset_end=loc.offset_start + 3 + len(tag),
    )

    entry = resolve_tag(tag)
    if entry is None:
        warnings.append(ParseWarning(
            file=file,
            line=tag_source.line,
            column=tag_source.column + 1,  # 1-indexed for human display
            message=f"unknown embedded-language tag '{tag}'",
            code=DiagnosticCode.UNKNOWN_LANGUAGE_TAG,
        ))
        return TripleStringValue(raw=value, source=loc)

    return TaggedBlockValue(
        tag=tag,
        language=entry.language,
        dialect=entry.dialect,
        value=value,
        tag_source=tag_source,
        value_source=loc,  # simplified; consumers don't rely on per-line columns
        indent_width=dedented.indent_width,
        source=loc,
    )


def _visit_id(ctx: Any, file: str) -> IdValue:
    parts = _id_parts(ctx)
    return IdValue(
        ref=Reference(path=".".join(parts), parts=parts, source=make_source_location(ctx, file)),
        parts=parts,
        source=make_source_location(ctx, file),
    )


def _visit_list(ctx: Any, file: str) -> ListValue:
    items = tuple(_visit_value(v, file) for v in ctx.value() or ())
    return ListValue(items=items, source=make_source_location(ctx, file))


def _visit_object(ctx: Any, file: str) -> ObjectValue:
    entries: dict[str, PropertyValue] = {}
    pl = ctx.propertyList()
    if pl is not None:
        for entry in pl.propertyEntry() or ():
            k_ctx = entry.key()
            key = _id_text(k_ctx.id_()) if k_ctx is not None else ""
            v_ctx = entry.value()
            entries[key] = _visit_value(v_ctx, file) if v_ctx is not None else NullValue(source=_loc_of(entry, file))
    return ObjectValue(entries=MappingProxyType(entries), source=make_source_location(ctx, file))


def _visit_function_call(ctx: Any, file: str) -> FunctionCall:
    name_ctx = ctx.id_()
    name = name_ctx.getText() if name_ctx is not None else ""
    args = tuple(_visit_value(v, file) for v in ctx.value() or ())
    return FunctionCall(name=name, args=args, source=make_source_location(ctx, file))


# ---------------------------------------------------------------------------
# Smaller helpers
# ---------------------------------------------------------------------------


def _visit_list_of_strings(ctx: Any, file: str) -> tuple[str, ...]:
    return tuple(_visit_string_value(s, file) for s in ctx.stringLiteralForm() or ())


def _visit_list_of_ids(ctx: Any, _file: str) -> tuple[str, ...]:
    return tuple(_id_text(i) for i in ctx.id_() or ())


def _visit_list_of_ids_as_refs(ctx: Any, file: str) -> list[Reference]:
    out: list[Reference] = []
    for i in ctx.id_() or ():
        r = _build_reference(i, file)
        if r is not None:
            out.append(r)
    return out

def _visit_primary_key_value(ctx: Any, file: str) -> list[str]:
    if ctx.listOfStrings() is not None:
        return list(_visit_list_of_strings(ctx.listOfStrings(), file))
    if ctx.listOfIds() is not None:
        return list(_visit_list_of_ids(ctx.listOfIds(), file))
    if ctx.id_() is not None:
        return [_id_text(ctx.id_())]
    return []


def _visit_data_type(ctx: Any, file: str) -> DataType:
    tv = ctx.typeValue()
    if tv is not None:
        return DataType(name=tv.getText() or "")
    type_name = ""
    length: int | None = None
    precision: int | None = None
    for p in ctx.dataTypeProperty() or ():
        if p.DATA_TYPE() is not None and p.typeValue() is not None:
            type_name = p.typeValue().getText() or ""
        if p.LENGTH() is not None and p.NUMBER_LITERAL() is not None:
            length = int(float(_tok_text(p.NUMBER_LITERAL()) or "0"))
        if p.PRECISION() is not None and p.NUMBER_LITERAL() is not None:
            precision = int(float(_tok_text(p.NUMBER_LITERAL()) or "0"))
    return DataType(name=type_name, length=length, precision=precision)


def _visit_index_type_value(ctx: Any) -> str:
    return (
        "primary" if ctx.PRIMARY() is not None
        else "secondary" if ctx.SECONDARY() is not None
        else "ordered" if ctx.ORDERED() is not None
        else "btree" if ctx.BTREE() is not None
        else "fulltext" if ctx.FULLTEXT() is not None
        else ""
    )


def _visit_constraint_type_value(ctx: Any) -> str:
    return (
        "unique" if ctx.UNIQUE() is not None
        else "notNull" if ctx.NOT_NULL() is not None
        else ""
    )


def _visit_language_value(ctx: Any) -> str:
    return (
        "SQL" if ctx.SQL() is not None
        else "TRANSFORMATION_DSL" if ctx.TRANSFORMATION_DSL() is not None
        else "DATAFRAME_DSL" if ctx.DATAFRAME_DSL() is not None
        else "REL_NODE" if ctx.REL_NODE() is not None
        else ""
    )


def _visit_localized_string(ctx: Any, file: str) -> LocalizedStringValue:
    entries: dict[str, str] = {}
    for e in ctx.localizedEntry() or ():
        key = _id_text(e.id_())
        v = e.stringLiteralForm()
        entries[key] = _visit_string_value(v, file) if v is not None else ""
    return LocalizedStringValue(by_language=MappingProxyType(entries))


def _visit_description(ctx: Any, file: str) -> tuple[str | None, LocalizedStringValue | None]:
    """Grammar 0.13 (NLS-P10 / GXP-D7) — `description:` has two legal shapes.

    The plain form feeds `description`, the localised map feeds
    `description_localized`, and exactly one of the pair is ever set. Folding a map
    down to one locale is a READER's decision (Veles' D7 fallback chain), never the
    parser's — the parser stays mechanical, as on the TS and Kotlin sides.
    """
    plain = ctx.stringLiteralForm()
    if plain is not None:
        return _visit_string_value(plain, file), None
    localized = ctx.localizedString()
    if localized is not None:
        return None, _visit_localized_string(localized, file)
    return None, None


def _visit_localized_string_list(ctx: Any, file: str) -> LocalizedStringListValue:
    entries: dict[str, tuple[str, ...]] = {}
    for e in ctx.localizedStringListEntry() or ():
        key = _id_text(e.id_())
        entries[key] = _visit_list_of_strings(e.listOfStrings(), file)
    return LocalizedStringListValue(by_language=MappingProxyType(entries))


def _visit_search_block(ctx: Any, file: str) -> SearchHintsValue:
    keywords = LocalizedStringListValue()
    patterns: tuple[str, ...] = ()
    descriptions = LocalizedStringListValue()
    examples: tuple[str, ...] = ()
    aliases: tuple[str, ...] = ()
    searchable = False
    fuzzy = False
    fuzzy_authored = False
    method: MatchMethodValue | None = None
    for p in ctx.searchSubProperty() or ():
        kp = p.keywordsProperty()
        if kp is not None:
            keywords = _visit_localized_string_list(kp.localizedStringList(), file)
        pp = p.patternsProperty()
        if pp is not None:
            patterns = _visit_list_of_strings(pp.listOfStrings(), file)
        dp = p.descriptionsProperty()
        if dp is not None:
            descriptions = _visit_localized_string_list(dp.localizedStringList(), file)
        ep = p.examplesProperty()
        if ep is not None:
            examples = _visit_list_of_strings(ep.listOfStrings(), file)
        ap = p.aliasesProperty()
        if ap is not None:
            aliases = _visit_list_of_strings(ap.listOfStrings(), file)
        sp = p.searchableProperty()
        if sp is not None:
            # 0.12: the boolean is optional - bare `searchable` IS the inclusion marker.
            v = _bool_text(sp.BOOLEAN_LITERAL())
            searchable = bool(v) if v is not None else True
            attr = sp.matchMethodAttr()
            if attr is not None:
                mv = attr.matchMethodValue()
                num = mv.NUMBER_LITERAL()
                method = MatchMethodValue(
                    name=mv.id_().getText(),
                    argument=float(num.getText()) if num is not None else None,
                )
        fp = p.fuzzyProperty()
        if fp is not None:
            fuzzy_authored = True
            v = _bool_text(fp.BOOLEAN_LITERAL())
            fuzzy = bool(v) if v is not None else False
    return SearchHintsValue(
        keywords=keywords, patterns=patterns, descriptions=descriptions,
        examples=examples, aliases=aliases, searchable=searchable, fuzzy=fuzzy,
        method=method, fuzzy_authored=fuzzy_authored,
    )


def _visit_value_labels(ctx: Any, file: str) -> tuple[dict[str, LocalizedStringValue], dict[str, tuple[str, ...]]]:
    """A4-β (v4.4 S2) — returns (labels, aliases). A value label is the legacy
    `{ cs: … }` form OR the widened `{ label: {…}, aliases: [ … ] }` form."""
    labels: dict[str, LocalizedStringValue] = {}
    aliases: dict[str, tuple[str, ...]] = {}
    for e in ctx.valueLabelEntry() or ():
        key_sl = e.stringLiteralForm()
        key = _visit_string_value(key_sl, file) if key_sl is not None else ""
        vv = e.valueLabelValue()
        labels[key] = _value_label_label(vv, file)
        al = _value_label_aliases(vv, file)
        if al:
            aliases[key] = al
    return labels, aliases


def _value_label_label(vv: Any, file: str) -> LocalizedStringValue:
    if vv is None:
        return LocalizedStringValue()
    legacy: dict[str, str] = {}
    for f in vv.valueLabelField() or ():
        fk = _id_text(f.id_())
        ls = f.localizedString()
        if fk == "label" and ls is not None:
            return _visit_localized_string(ls, file)
        if fk == "aliases":
            continue
        slf = f.stringLiteralForm()
        if slf is not None:
            legacy[fk] = _visit_string_value(slf, file)
    return LocalizedStringValue(by_language=MappingProxyType(legacy))


def _value_label_aliases(vv: Any, file: str) -> tuple[str, ...]:
    if vv is None:
        return ()
    for f in vv.valueLabelField() or ():
        if _id_text(f.id_()) == "aliases" and f.listOfStrings() is not None:
            return _visit_list_of_strings(f.listOfStrings(), file)
    return ()


# ---------------------------------------------------------------------------
# Inline-binding types (v2.1; v3.0 renamed `mapping:` → `binding:`)
# ---------------------------------------------------------------------------


def _visit_binding_property(ctx: Any, file: str) -> BindingProperty:
    v = ctx.bindingValue()
    if v is not None and v.id_() is not None:
        return BindingPropertyBareId(id=_build_reference(v.id_(), file), source=make_source_location(v, file))
    block = v.bindingBlock() if v is not None else None
    target: TargetValue | None = None
    columns: tuple[BindingColumnEntry, ...] = ()
    fk: Reference | None = None
    if block is not None:
        for p in block.bindingBlockProperty() or ():
            tp = p.targetProperty()
            if tp is not None:
                target = _visit_target_value(tp, file)
            mcp = p.bindingColumnsProperty()
            if mcp is not None and mcp.bindingColumnMap() is not None:
                columns = tuple(_visit_binding_column_map(mcp.bindingColumnMap(), file))
            fkp = p.fkProperty_()
            if fkp is not None:
                fk = _build_reference(fkp.id_(), file)
    return BindingPropertyBlock(target=target, columns=columns, fk=fk, source=make_source_location(block, file) if block is not None else SourceLocation.UNKNOWN)


def _visit_target_value(ctx: Any, file: str) -> TargetValue:
    id_ctx = ctx.id_()
    if id_ctx is not None:
        return TargetReferenceValue(ref=_build_reference(id_ctx, file), source=make_source_location(id_ctx, file))
    obj = ctx.object_()
    if obj is not None:
        return TargetObjectValue(obj=_visit_object(obj, file), source=make_source_location(obj, file))
    return TargetObjectValue(obj=None, source=_loc_of(ctx, file))


def _visit_binding_column_map(ctx: Any, file: str) -> list[BindingColumnEntry]:
    out: list[BindingColumnEntry] = []
    for e in ctx.bindingColumnEntry() or ():
        name = _id_text(e.id_())
        v = e.bindingColumnValue()
        out.append(BindingColumnEntry(name=name, value=_visit_binding_column_value(v, file), source=make_source_location(e, file)))
    return out


def _visit_binding_column_value(ctx: Any, file: str) -> BindingColumnValue:
    if ctx is None:
        return BindingColumnObject(obj=None, source=SourceLocation.UNKNOWN)
    id_ctx = ctx.id_()
    if id_ctx is not None:
        return BindingColumnBareId(id=_build_reference(id_ctx, file), source=make_source_location(id_ctx, file))
    mtv = ctx.bindingTargetValue()
    if mtv is not None:
        # `target: X` inside an inline binding — emit a single-entry
        # `{ target: ... }` object, mirroring the TS walker.
        inner_id = mtv.id_()
        if inner_id is not None:
            inner_value: PropertyValue = IdValue(
                ref=_build_reference(inner_id, file),
                parts=_id_parts(inner_id),
                source=make_source_location(inner_id, file),
            )
        else:
            inner_obj = mtv.object_()
            inner_value = _visit_object(inner_obj, file) if inner_obj is not None else NullValue(source=_loc_of(mtv, file))
        wrap = ObjectValue(entries=MappingProxyType({"target": inner_value}), source=_loc_of(mtv, file))
        return BindingColumnObject(obj=wrap, source=make_source_location(ctx, file))
    obj = ctx.object_()
    if obj is not None:
        return BindingColumnObject(obj=_visit_object(obj, file), source=make_source_location(obj, file))
    return BindingColumnObject(obj=None, source=_loc_of(ctx, file))
