# SPDX-License-Identifier: Apache-2.0
"""Grammar 0.17 (CN-P1 S1.3) — the Python AST + walker for the conceptual model.

The parity of the TS ``cn-walker.test.ts`` and the Kotlin ``CnWalkerSpec.kt``. The conformance
dumps (``out-ts/75…79``, compared by ``test_conformance.py``) are the whole-fixture goldens; these
tests pin the shapes, the expression-block source map, the lossless read (every key, label, tag,
participant label and span survives) and the error positions the three targets share. Like Kotlin,
a Python parse with any error keeps no definitions, so the recovery cases assert diagnostics only.
"""

from __future__ import annotations

from pathlib import Path

import pytest

from ttr_parser import parse_string
from ttr_parser.diagnostics import DiagnosticCode
from ttr_parser.model import (
    BoolValue,
    ConceptBasis,
    ConceptBindingDef,
    ConceptBody,
    ConceptDef,
    ConceptDefinition,
    ConceptEntry,
    ConceptExtent,
    ConceptFilter,
    ConceptGrounds,
    ConceptInvolves,
    ConceptLabel,
    ConceptLexicon,
    ConceptMembers,
    ConceptPeriod,
    ConceptProperty,
    ConceptRef,
    ConceptTarget,
    ConceptValueSpace,
    CubeletDef,
    Definition,
    DimensionDef,
    EmbeddedBlock,
    EntityDef,
    IdValue,
    ListValue,
    LocalizedStringValue,
    MdDomainDef,
    MeasureDef,
    ObjectValue,
    ParseResult,
    Reference,
    RoleDef,
    SourceLocation,
    StringValue,
    TargetReferenceValue,
)

_RESOURCES = Path(__file__).resolve().parents[3] / "kotlin" / "ttr-parser" / "src" / "test" / "resources"


def _resource(rel: str) -> str:
    return (_RESOURCES / rel).read_text(encoding="utf-8")


def _fixture(name: str) -> ParseResult:
    r = parse_string(_resource(f"cn/{name}"), name)
    assert r.errors == ()
    return r


def _concept(defs: tuple[Definition, ...], name: str) -> ConceptDef:
    return next(d for d in defs if isinstance(d, ConceptDef) and d.name == name)


def _binding(defs: tuple[Definition, ...], name: str) -> ConceptBindingDef:
    return next(d for d in defs if isinstance(d, ConceptBindingDef) and d.name == name)


def _prop(body: ConceptBody, cls: type[ConceptProperty]) -> ConceptProperty:
    p = next((p for p in body.properties if isinstance(p, cls)), None)
    assert p is not None, cls.__name__
    return p


def _keys(body: ConceptBody) -> list[str]:
    return [p.key for p in body.properties]


def _slice(src: str, at: SourceLocation) -> str:
    return src[at.offset_start : at.offset_end]


def _position_of(src: str, offset: int) -> tuple[int, int]:
    """1-based line / 0-based column of an offset in `src` — the SourceLocation convention."""
    before = src[:offset].split("\n")
    return len(before), len(before[-1])


def _ids(v: object) -> list[str]:
    assert isinstance(v, ListValue)
    return [i.ref.path for i in v.items if isinstance(i, IdValue) and i.ref is not None]


# -----------------------------------------------------------------------------------------------
# T1 — the shapes
# -----------------------------------------------------------------------------------------------


def test_t1_a_body_reads_the_first_property_of_a_key() -> None:
    src = SourceLocation("hand.ttrm", 1, 0, 1, 1, 0, 1)
    body = ConceptBody(
        properties=(
            ConceptEntry("of", src, value=IdValue(ref=Reference("Party", ("Party",), src), parts=("Party",), source=src)),
            ConceptRef("event", src, ref=Reference("Order", ("Order",), src)),
            ConceptLabel("label", src, value=LocalizedStringValue({"en": "Customer"})),
            ConceptGrounds("grounds", src, ref=Reference("md.Customer", ("md", "Customer"), src)),
            ConceptGrounds("grounds", src, ref=Reference("er.entity.customer", ("er", "entity", "customer"), src)),
        )
    )
    role = ConceptDef(name="Customer", source=src, stereotype="role", body=body)
    grounds = role.body.get("grounds")
    assert isinstance(grounds, ConceptGrounds) and grounds.ref.path == "md.Customer"
    assert sum(isinstance(p, ConceptGrounds) for p in role.body.properties) == 2
    assert (r := role.body.ref("event")) is not None and r.path == "Order"
    assert role.body.ref("kind") is None
    assert isinstance(role.body.entry("of"), IdValue)
    assert role.body.entry("is_a") is None
    assert _keys(role.body) == ["of", "event", "label", "grounds", "grounds"]


def test_t1_maps_a_position_inside_an_expression_block_back_to_the_file() -> None:
    # `definition: """pl␊        a␊          b"""` — text "a\n  b", indent 8; line 0 at file line 2.
    block = EmbeddedBlock(
        form="tagged",
        text="a\n  b",
        text_source=SourceLocation("hand.ttrm", 2, 8, 3, 11, 40, 53),
        line_offsets=(40, 50),
        indent_width=8,
        source=SourceLocation("hand.ttrm", 1, 16, 1, 17, 20, 21),
    )
    assert block.location(0, 0) == SourceLocation("hand.ttrm", 2, 8, 2, 9, 40, 41)
    assert block.location(1, 2, 1) == SourceLocation("hand.ttrm", 3, 10, 3, 11, 52, 53)


# -----------------------------------------------------------------------------------------------
# T2 — concept defs and the `def role` dispatch (⚑CN-P6), fixture 75
# -----------------------------------------------------------------------------------------------

_DEFS75 = _fixture("75-cn-concepts.ttrm").definitions


def test_t2_walks_every_def_of_a_model_cn_file_to_a_concept_def() -> None:
    assert all(isinstance(d, ConceptDef) for d in _DEFS75)
    counts = [sum(1 for d in _DEFS75 if isinstance(d, ConceptDef) and d.stereotype == s)
              for s in ("kind", "role", "event", "quality", "phase", "relation_type")]
    assert counts == [7, 1, 5, 5, 6, 1]


def test_t2_keeps_every_property_in_authored_order() -> None:
    item = _concept(_DEFS75, "Item")
    assert _keys(item.body) == ["description", "label", "sources", "grounds", "qualities", "lexicon"]
    assert item.description == "A product the stores, the web shop and the marketplace sell."
    assert item.body.label is not None and dict(item.body.label.by_language) == {"en": "Product", "cs": "Produkt"}
    grounds = _prop(item.body, ConceptGrounds)
    assert isinstance(grounds, ConceptGrounds) and grounds.ref.parts == ("md", "Product")
    sources = item.body.entry("sources")
    assert isinstance(sources, ListValue) and isinstance(sources.items[0], StringValue)
    assert sources.items[0].raw == "doc://hartland/handbook#products"
    assert _ids(item.body.entry("qualities")) == ["category", "brand"]
    lexicon = _prop(item.body, ConceptLexicon)
    assert isinstance(lexicon, ConceptLexicon) and lexicon.block.terms == ("product", "item", "SKU")


def test_t2_keeps_a_free_form_object_value_structural() -> None:
    cat = _concept(_DEFS75, "ProductCategory")
    assert _keys(cat.body) == ["label", "grounds", "hierarchy"]
    h = cat.body.entry("hierarchy")
    assert isinstance(h, ObjectValue)
    assert list(h.entries) == ["closure", "ancestor", "descendant", "ranges"]


def test_t2_reads_is_a_members_and_the_open_tier() -> None:
    home = _concept(_DEFS75, "HomeCategory")
    is_a = home.body.entry("is_a")
    assert isinstance(is_a, IdValue) and is_a.ref is not None and is_a.ref.path == "ProductCategory"
    members = _prop(home.body, ConceptMembers)
    assert isinstance(members, ConceptMembers)
    assert (members.block.form, members.block.language, members.block.text) == (
        "tripleString", "pl", "descendants(ProductCategory, 'H10-H19')",
    )
    supplier = _concept(_DEFS75, "Supplier")
    assert _ids(supplier.body.entry("is_a")) == ["Party", "Organization"]
    relations = supplier.body.entry("relations")
    assert isinstance(relations, ListValue) and isinstance(relations.items[0], ObjectValue)
    assert list(relations.items[0].entries) == ["type", "to"]
    supplies = _concept(_DEFS75, "supplies")
    assert supplies.stereotype == "relation_type"
    assert supplies.description == "A supplier supplies a distribution center."


def test_t2_walks_a_cn_role_to_a_concept_role() -> None:
    customer = _concept(_DEFS75, "Customer")
    assert customer.stereotype == "role"
    assert _keys(customer.body) == ["of", "event", "label", "grounds"]
    assert (r := customer.body.ref("event")) is not None and r.path == "Order"
    assert customer.body.label is not None and dict(customer.body.label.by_language) == {"en": "Customer", "cs": "Zákazník"}


def test_t2_reads_labelled_participants_the_period_and_the_extent() -> None:
    transfer = _concept(_DEFS75, "StockTransfer")
    extent = _prop(transfer.body, ConceptExtent)
    assert isinstance(extent, ConceptExtent) and extent.value == "instantaneous"
    involves = _prop(transfer.body, ConceptInvolves)
    assert isinstance(involves, ConceptInvolves)
    assert [(p.ref.path, p.label) for p in involves.participants] == [("Item", None), ("Warehouse", "from"), ("Warehouse", "to")]
    period = _prop(transfer.body, ConceptPeriod)
    assert isinstance(period, ConceptPeriod) and list(period.value.entries) == ["at", "precision"]
    order = _concept(_DEFS75, "Order")
    order_involves = _prop(order.body, ConceptInvolves)
    assert isinstance(order_involves, ConceptInvolves) and [p.label for p in order_involves.participants] == [None, "fulfilling"]
    order_period = _prop(order.body, ConceptPeriod)
    assert isinstance(order_period, ConceptPeriod) and list(order_period.value.entries) == ["start", "end", "end_open"]
    # newline-separated properties, no commas
    assert _keys(_concept(_DEFS75, "WarehouseOutage").body) == ["extent", "involves", "period"]


def test_t2_reads_qualities_and_the_three_definition_forms() -> None:
    vs = _prop(_concept(_DEFS75, "category").body, ConceptValueSpace)
    assert isinstance(vs, ConceptValueSpace) and (vs.ref.path, vs.key) == ("ProductCategory", "value_space")
    valued = _concept(_DEFS75, "stock_level").body.entry("valued_state")
    assert isinstance(valued, BoolValue) and valued.raw is True
    plain = _prop(_concept(_DEFS75, "open_amount").body, ConceptDefinition)
    assert isinstance(plain, ConceptDefinition) and (plain.block.form, plain.block.text) == ("string", "amount - paid")
    tagged = _prop(_concept(_DEFS75, "transfer_delay").body, ConceptDefinition)
    assert isinstance(tagged, ConceptDefinition)
    assert (tagged.block.form, tagged.block.text) == ("tagged", "this.period.at - this.from.period.at")


def test_t2_reads_phases() -> None:
    pending = _prop(_concept(_DEFS75, "PendingOrder").body, ConceptDefinition)
    assert isinstance(pending, ConceptDefinition) and pending.block.text == "state in ('placed', 'held')"
    imprecision = _concept(_DEFS75, "DebtorCustomer").body.entry("imprecision")
    assert isinstance(imprecision, IdValue) and imprecision.ref is not None and imprecision.ref.path == "certainly"
    placed = _prop(_concept(_DEFS75, "PlacedOrder").body, ConceptGrounds)
    assert isinstance(placed, ConceptGrounds) and placed.ref.path == "er.entity.placed_order"
    abstract = _concept(_DEFS75, "StockCondition").body.entry("abstract")
    assert isinstance(abstract, BoolValue) and abstract.raw is True
    oos = _concept(_DEFS75, "OutOfStock")
    assert _keys(oos.body) == ["of", "is_a", "basis", "definition"]
    basis = _prop(oos.body, ConceptBasis)
    assert isinstance(basis, ConceptBasis) and basis.value == "current_state"


def test_t2_reports_a_repeated_key_once_at_the_repeat() -> None:
    r = parse_string('model cn\ndef kind X { grounds: md.A, label: { en: "x" }, grounds: md.B }', "dup.ttrm")
    assert [(e.code, e.line, e.column) for e in r.errors] == [(DiagnosticCode.DUPLICATE_PROPERTY, 2, 49)]
    assert "'grounds'" in r.errors[0].message


def test_t2_a_cnc_role_and_an_automation_role_keep_their_shape() -> None:
    cnc = parse_string('model cnc\ndef role buyer { label: { en: "Buyer" } }', "r.ttrm")
    assert cnc.errors == ()
    buyer = cnc.definitions[0]
    assert isinstance(buyer, RoleDef) and buyer.label is not None and dict(buyer.label.by_language) == {"en": "Buyer"}
    auto = parse_string('model automation\ndef role sales { resolve: keycloak_group "hartland-sales" }', "r.ttrm")
    assert auto.errors == ()
    sales = auto.definitions[0]
    assert isinstance(sales, RoleDef) and [e.key for e in sales.entries] == ["resolve"]


def test_t2_rejects_the_cnc_and_automation_role_properties_on_a_concept_role() -> None:
    src = 'model cn\ndef role Customer {\n  event: Order,\n  resolve: keycloak_group "x",\n  search { searchable: true }\n}'
    r = parse_string(src, "r.ttrm")
    assert [(e.code, e.line) for e in r.errors] == [(DiagnosticCode.UNKNOWN_PROPERTY, 4), (DiagnosticCode.UNKNOWN_PROPERTY, 5)]
    assert "'resolve'" in r.errors[0].message


# -----------------------------------------------------------------------------------------------
# T3 — bindings (76), the sugar (77), lexicon targets (78)
# -----------------------------------------------------------------------------------------------

_DEFS76 = _fixture("76-cn-bindings.ttrm").definitions


def test_t3_walks_the_ten_binding_kinds() -> None:
    assert [d.binding_kind for d in _DEFS76 if isinstance(d, ConceptBindingDef)] == [
        "cn2md_kind", "cn2md_role", "cn2md_quality", "cn2md_quality", "cn2md_event",
        "cn2er_kind", "cn2er_kind", "cn2er_role", "cn2er_role", "cn2er_event", "cn2er_event",
        "cn2er_quality", "cn2er_quality", "cn2er_phase", "cn2db_concept",
    ]


def test_t3_reads_the_stereotype_ref_and_the_target() -> None:
    item = _binding(_DEFS76, "item_g")
    assert (r := item.body.ref("kind")) is not None and r.parts == ("cn", "Item")
    target = _prop(item.body, ConceptTarget)
    assert isinstance(target, ConceptTarget) and isinstance(target.value, TargetReferenceValue)
    assert target.value.ref is not None and target.value.ref.path == "md.Product"
    brand = _binding(_DEFS76, "brand_g")
    assert brand.description == "an attribute, not a measure"
    legacy = _binding(_DEFS76, "legacy_g")
    assert (c := legacy.body.ref("concept")) is not None and c.path == "cn.LegacyStock"
    assert legacy.tags == ("legacy",)
    assert (ph := _binding(_DEFS76, "placed_g").body.ref("phase")) is not None and ph.path == "cn.PlacedOrder"


def test_t3_keeps_participants_structured_and_via_a_list() -> None:
    transfer = _binding(_DEFS76, "transfer_g")
    assert _keys(transfer.body) == ["event", "target", "participants", "period"]
    participants = transfer.body.entry("participants")
    assert isinstance(participants, ObjectValue) and list(participants.entries) == ["item", "from", "to"]
    assert _ids(participants.entries["to"]) == ["er.relation.rel_transfer_to", "er.relation.rel_warehouse_region"]
    assert _ids(_binding(_DEFS76, "orderer_g").body.entry("via")) == ["er.relation.rel_order_customer"]
    assert isinstance(_binding(_DEFS76, "category_g").body.entry("hierarchy"), ObjectValue)


def test_t3_reads_a_binding_filter_as_an_expression_block() -> None:
    stock = _binding(_DEFS76, "stock_g")
    f = _prop(stock.body, ConceptFilter)
    assert isinstance(f, ConceptFilter) and f.key == "filter"
    assert (f.block.form, f.block.text) == ("tripleString", "warehouse = 1")
    assert stock.body.entry("filter") is None


_SUGAR_SRC = _resource("cn/77-cn-sugar.ttrm")
_DEFS77 = parse_string(_SUGAR_SRC, "77-cn-sugar.ttrm").definitions


def _by_name(name: str) -> Definition:
    return next(d for d in _DEFS77 if d.name == name)


def test_t3_attaches_the_sugar_to_every_md_host() -> None:
    cubelet = _by_name("storeReturns")
    assert isinstance(cubelet, CubeletDef) and cubelet.concept is not None
    assert (e := cubelet.concept.body.ref("event")) is not None and e.path == "Return"
    involves = _prop(cubelet.concept.body, ConceptInvolves)
    assert isinstance(involves, ConceptInvolves)
    assert [p.ref.path for p in involves.participants] == ["Customer", "Item", "ReturnReason"]
    dim = _by_name("Product")
    assert isinstance(dim, DimensionDef) and dim.concept is not None
    assert (k := dim.concept.body.ref("kind")) is not None and k.path == "Item"
    assert dim.concept.body.label is not None and dict(dim.concept.body.label.by_language) == {"en": "Product"}
    domain = _by_name("ReasonCode")
    assert isinstance(domain, MdDomainDef) and domain.concept is not None
    assert (k := domain.concept.body.ref("kind")) is not None and k.path == "ReturnReason"
    measure = _by_name("returnAmount")
    assert isinstance(measure, MeasureDef) and measure.concept is not None
    assert (q := measure.concept.body.ref("quality")) is not None and q.path == "return_amount"


def test_t3_attaches_the_sugar_to_entities_and_er_attributes() -> None:
    invoice = _by_name("invoice")
    assert isinstance(invoice, EntityDef) and invoice.concept is not None
    assert (k := invoice.concept.body.ref("kind")) is not None and k.path == "Invoice"
    status = next(a for a in invoice.attributes if a.name == "status")
    assert status.concept is not None and (q := status.concept.body.ref("quality")) is not None and q.path == "invoice_status"
    assert next(a for a in invoice.attributes if a.name == "invoice_id").concept is None
    order = _by_name("order")
    assert isinstance(order, EntityDef) and order.concept is not None
    assert _keys(order.concept.body) == ["event", "period"]
    customer = _by_name("customer")
    assert isinstance(customer, EntityDef) and customer.concept is not None
    assert _keys(customer.concept.body) == ["role", "event"]
    assert _slice(_SUGAR_SRC, order.concept.source) == "concept { event: Order, period: { start: placed_date, end: delivered_date } }"


def test_t3_reads_lexicon_default_and_sense_priority() -> None:
    defs = _fixture("78-cn-lexicon.ttrm").definitions
    entry = {d.name: d for d in defs}
    customer = entry["customer"]
    assert customer.target is not None and customer.target.path == "cn.Customer"  # type: ignore[attr-defined]
    assert (customer.default, customer.sense_priority) == (True, None)  # type: ignore[attr-defined]
    assert (entry["debtor"].default, entry["debtor"].sense_priority) == (None, 2.0)  # type: ignore[attr-defined]
    assert (entry["customer_phase"].default, entry["customer_phase"].sense_priority) == (False, 1.0)  # type: ignore[attr-defined]
    assert (entry["revenue"].default, entry["revenue"].sense_priority) == (True, 3.0)  # type: ignore[attr-defined]
    assert entry["channel"].default is None  # type: ignore[attr-defined]
    assert entry["web_revenue"].kind == "example"


# -----------------------------------------------------------------------------------------------
# T4 — expression blocks: text exact, positions exact
# -----------------------------------------------------------------------------------------------

_TAGGED = "\n".join([
    "model cn",
    "def phase P {",
    "    of: Order,",
    '    definition: """pl',
    "        exists Invoicing i involving this",
    "          where i.amount > 0",
    "",
    "        and state = 'open'",
    '    """',
    "}",
])


def _definition_block(src: str) -> EmbeddedBlock:
    r = parse_string(src, "b.ttrm")
    assert r.errors == ()
    d = r.definitions[0]
    assert isinstance(d, ConceptDef)
    p = _prop(d.body, ConceptDefinition)
    assert isinstance(p, ConceptDefinition)
    return p.block


def test_t4_keeps_a_tagged_block_byte_exact() -> None:
    b = _definition_block(_TAGGED)
    assert b.form == "tagged"
    assert b.text == "exists Invoicing i involving this\n  where i.amount > 0\n\nand state = 'open'"
    assert b.indent_width == 8
    assert len(b.line_offsets) == 4


def test_t4_points_a_diagnostic_at_column_n_of_line_2_of_the_block() -> None:
    b = _definition_block(_TAGGED)
    at = b.location(1, 8, 8)
    assert _slice(_TAGGED, at) == "i.amount"
    assert (at.line, at.column) == _position_of(_TAGGED, _TAGGED.index("i.amount"))
    assert at.end_column - at.column == 8
    exists = b.location(0, 0, 6)
    assert _slice(_TAGGED, exists) == "exists"
    assert (exists.line, exists.column) == _position_of(_TAGGED, _TAGGED.index("exists"))
    state = b.location(3, 4, 5)
    assert _slice(_TAGGED, state) == "state"
    assert (state.line, state.column) == _position_of(_TAGGED, _TAGGED.index("state = 'open'"))
    assert _slice(_TAGGED, b.text_source) == "exists Invoicing i involving this\n          where i.amount > 0\n\n        and state = 'open'"


def test_t4_maps_offsets_in_a_crlf_file_too() -> None:
    crlf = _TAGGED.replace("\n", "\r\n")
    assert _slice(crlf, _definition_block(crlf).location(1, 8, 8)) == "i.amount"


def test_t4_maps_a_one_line_triple_string_and_a_plain_string() -> None:
    src = "model cn\ndef phase P { of: Order, definition: \"\"\"state in ('placed', 'held')\"\"\" }"
    b = _definition_block(src)
    assert (b.form, b.text) == ("tripleString", "state in ('placed', 'held')")
    assert b.line_offsets == (src.index("state"),)
    paren = b.location(0, 9, 1)
    assert _slice(src, paren) == "("
    assert paren.column == src.index("(") - src.index("\n") - 1
    plain = 'model cn\ndef quality P { of: Invoicing, definition: "amount - paid" }'
    s = _definition_block(plain)
    assert (s.form, s.text) == ("string", "amount - paid")
    assert _slice(plain, s.location(0, 7)) == "-"


def test_t4_keeps_an_unknown_first_line_word_as_text_and_refuses_a_foreign_tag() -> None:
    word = parse_string('model cn\ndef phase P { definition: """exists\n  Invoicing involving this\n""" }', "w.ttrm")
    d = word.definitions[0]
    assert isinstance(d, ConceptDef)
    wb = _prop(d.body, ConceptDefinition)
    assert isinstance(wb, ConceptDefinition)
    assert (wb.block.form, wb.block.text) == ("tripleString", "exists\n  Invoicing involving this\n")
    assert [w.code for w in word.warnings] == [DiagnosticCode.UNKNOWN_LANGUAGE_TAG]
    sql = parse_string('model cn\ndef phase P { definition: """sql\n  SELECT 1\n""" }', "s.ttrm")
    assert [e.code for e in sql.errors] == [DiagnosticCode.LANGUAGE_TAG_MISMATCH]
    assert "'sql'" in sql.errors[0].message


def test_t4_leaves_the_sql_hosts_alone() -> None:
    r = parse_string('model db\ndef view v { definitionSql: """pl\nSELECT 1\n""" }', "v.ttrm")
    assert [w.code for w in r.warnings] == [DiagnosticCode.UNKNOWN_LANGUAGE_TAG]


# -----------------------------------------------------------------------------------------------
# T4 — the read is lossless: every key, label, tag, participant label and span survives
# -----------------------------------------------------------------------------------------------


def _bodies(defs: tuple[Definition, ...]) -> list[ConceptBody]:
    out: list[ConceptBody] = []
    for d in defs:
        if isinstance(d, (ConceptDef, ConceptBindingDef)):
            out.append(d.body)
        elif isinstance(d, EntityDef):
            out += [s.body for s in [d.concept, *(a.concept for a in d.attributes)] if s is not None]
        elif isinstance(d, (CubeletDef, DimensionDef, MdDomainDef, MeasureDef)) and d.concept is not None:
            out.append(d.concept.body)
    return out


@pytest.mark.parametrize("name", ["75-cn-concepts.ttrm", "76-cn-bindings.ttrm", "77-cn-sugar.ttrm"])
def test_t4_lossless_every_property_spans_text_that_starts_with_its_key(name: str) -> None:
    src = _resource(f"cn/{name}")
    for body in _bodies(parse_string(src, name).definitions):
        for p in body.properties:
            assert _slice(src, p.source).startswith(p.key)
            # a property span never swallows the separator after it
            assert not _slice(src, p.source).rstrip().endswith(",")


def test_t4_lossless_participant_labels_block_tags_and_trailing_commas() -> None:
    src = _resource("cn/75-cn-concepts.ttrm")
    involves = _prop(_concept(_DEFS75, "StockTransfer").body, ConceptInvolves)
    assert isinstance(involves, ConceptInvolves)
    assert [_slice(src, p.source) for p in involves.participants] == ["Item", "Warehouse as from", "Warehouse as to"]
    delay = _prop(_concept(_DEFS75, "transfer_delay").body, ConceptDefinition)
    assert isinstance(delay, ConceptDefinition) and _slice(src, delay.block.source).startswith('"""pl')
    home = _concept(_DEFS75, "HomeCategory")
    assert _slice(src, home.body.properties[-1].source) == 'label: { en: "Home and garden categories" }'


# -----------------------------------------------------------------------------------------------
# T4 negative — the first syntax error of each cn reject sits where TS and Kotlin put it
# -----------------------------------------------------------------------------------------------


@pytest.mark.parametrize(
    ("name", "line", "column"),
    [
        ("neg-01-bare-definition.ttrm", 3, 55),
        ("neg-02-involves-no-brackets.ttrm", 3, 28),
        ("neg-03-bare-members.ttrm", 3, 57),
        ("neg-04-unlabelled-as.ttrm", 3, 77),
    ],
)
def test_t4_negative_first_syntax_error_position(name: str, line: int, column: int) -> None:
    first = parse_string(_resource(f"cn-negative/{name}"), name).errors[0]
    assert (first.code, first.line, first.column) == (DiagnosticCode.PARSE_ERROR, line, column)


# -----------------------------------------------------------------------------------------------
# T6 — parse-level diagnostics and recovery
# -----------------------------------------------------------------------------------------------


def test_t6_flags_a_binding_filter_that_is_not_a_block() -> None:
    r = parse_string("model binding\ndef cn2er_quality q { quality: cn.x, target: er.entity.o.v, filter: analyte }", "f.ttrm")
    assert [e.code for e in r.errors] == [DiagnosticCode.EXPRESSION_BLOCK_REQUIRED]


def test_t6_recovers_past_an_unknown_binding_kind() -> None:
    src = "model binding\ndef cn2xx_kind a { kind: cn.Item, target: md.Product }\ndef cn2md_kind b { kind: cn.Item, target: md.Product }"
    assert any(e.code == DiagnosticCode.PARSE_ERROR and e.line == 2 for e in parse_string(src, "u.ttrm").errors)


@pytest.mark.parametrize(
    "src",
    [
        "model cn\ndef phase P",
        "model cn\ndef phase P { definition: }",
        "model cn\ndef event E { involves: [ ] }",
        "model cn\ndef role R",
        "model cn\ndef phase Pending {\n  of: Order,\n  definition: \"\"\"state = 'open'\"\"\",\n  basis: ",
    ],
)
def test_t6_survives_a_missing_body_and_a_conjured_token(src: str) -> None:
    assert parse_string(src, "m.ttrm").errors != ()
