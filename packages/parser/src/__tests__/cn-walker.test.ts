// SPDX-License-Identifier: Apache-2.0
import { describe, it, expect } from 'vitest';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { parseString } from '../index.js';
import { conceptEntry, conceptProperty, conceptRef, embeddedBlockLocation } from '../concept.js';
import { DiagnosticCode } from '../diagnostics.js';
import type {
  ConceptBindingDef,
  ConceptBody,
  ConceptDef,
  ConceptSugar,
  CubeletDef,
  Definition,
  DimensionDef,
  EmbeddedBlock,
  EntityDef,
  LexiconEntryDef,
  MdDomainDef,
  MeasureDef,
  RoleDef,
  SourceLocation,
} from '../ast.js';

// CN-P1 S1.2 — the TS AST + walker for grammar 0.17 (cn contracts §1.1–§1.5). The conformance dumps
// (`tests/conformance/out-ts/75…79`) are the whole-fixture goldens; these specs pin the shapes.
const CN = join(__dirname, '../../../kotlin/ttr-parser/src/test/resources/cn');
const fixture = (name: string) => readFileSync(join(CN, name), 'utf-8');

function parseFixture(name: string) {
  const result = parseString(fixture(name), name);
  expect(result.errors).toEqual([]);
  return result.ast!;
}

function concept(defs: Definition[], name: string): ConceptDef {
  const d = defs.find((x): x is ConceptDef => x.kind === 'concept' && x.name === name);
  expect(d, name).toBeDefined();
  return d!;
}

function binding(defs: Definition[], name: string): ConceptBindingDef {
  const d = defs.find((x): x is ConceptBindingDef => x.kind === 'conceptBinding' && x.name === name);
  expect(d, name).toBeDefined();
  return d!;
}

const keys = (b: { properties: { key: string }[] }) => b.properties.map((p) => p.key);
const loc = (line: number, column: number, offset: number): SourceLocation => ({
  file: 'hand.ttrm', line, column, endLine: line, endColumn: column + 1, offsetStart: offset, offsetEnd: offset + 1,
});

/** 1-based line / 0-based column of an offset in `src` — the SourceLocation convention. */
function positionOf(src: string, offset: number): { line: number; column: number } {
  const before = src.slice(0, offset).split('\n');
  return { line: before.length, column: before[before.length - 1].length };
}

// ---------------------------------------------------------------------------------------------------
// T1 — the shapes
// ---------------------------------------------------------------------------------------------------

describe('cn AST shapes (T1)', () => {
  const ref = (path: string) => ({ path, parts: path.split('.'), source: loc(1, 0, 0) });
  const role: ConceptDef = {
    kind: 'concept',
    stereotype: 'role',
    name: 'Customer',
    source: loc(1, 0, 0),
    label: { kind: 'localizedString', entries: { en: 'Customer' }, source: loc(2, 4, 10) },
    properties: [
      { kind: 'entry', key: 'of', value: { kind: 'id', path: 'Party', parts: ['Party'], source: loc(2, 8, 20) }, source: loc(2, 4, 16) },
      { kind: 'ref', key: 'event', ref: ref('Order'), source: loc(3, 4, 30) },
      { kind: 'label', key: 'label', value: { kind: 'localizedString', entries: { en: 'Customer' }, source: loc(4, 11, 50) }, source: loc(4, 4, 43) },
      { kind: 'grounds', key: 'grounds', ref: ref('md.Customer'), source: loc(5, 4, 70) },
      { kind: 'grounds', key: 'grounds', ref: ref('er.entity.customer'), source: loc(6, 4, 90) },
    ],
  };

  it('reads the first property of a key — a repeat stays in the list (the walker reports it)', () => {
    expect(conceptProperty(role, 'grounds')?.ref.path).toBe('md.Customer');
    expect(role.properties.filter((p) => p.kind === 'grounds')).toHaveLength(2);
    expect(conceptRef(role, 'event')?.ref.path).toBe('Order');
    expect(conceptRef(role, 'kind')).toBeUndefined();
    expect(conceptEntry(role, 'of')?.value).toMatchObject({ kind: 'id', path: 'Party' });
    expect(conceptEntry(role, 'is_a')).toBeUndefined();
    expect(keys(role)).toEqual(['of', 'event', 'label', 'grounds', 'grounds']);
  });

  it('maps a position inside an expression block back to the file', () => {
    // `definition: """pl␊        a␊          b"""` — text "a\n  b", indent 8; line 0 at file line 2.
    const block: EmbeddedBlock = {
      kind: 'embeddedBlock',
      language: 'pl',
      form: 'tagged',
      text: 'a\n  b',
      textSource: { file: 'hand.ttrm', line: 2, column: 8, endLine: 3, endColumn: 11, offsetStart: 40, offsetEnd: 53 },
      lineOffsets: [40, 50],
      indentWidth: 8,
      source: loc(1, 16, 20),
    };
    expect(embeddedBlockLocation(block, 0, 0)).toMatchObject({ line: 2, column: 8, offsetStart: 40, offsetEnd: 41 });
    expect(embeddedBlockLocation(block, 1, 2, 1)).toMatchObject({ line: 3, column: 10, endColumn: 11, offsetStart: 52, offsetEnd: 53 });
  });
});

// ---------------------------------------------------------------------------------------------------
// T2 — concept defs and the `def role` dispatch (⚑CN-P6)
// ---------------------------------------------------------------------------------------------------

describe('cn walker — concept defs (T2, fixture 75)', () => {
  const ast = parseFixture('75-cn-concepts.ttrm');
  const defs = ast.definitions;

  it('walks every def of a `model cn` file to a ConceptDef with its stereotype', () => {
    expect(defs.every((d) => d.kind === 'concept')).toBe(true);
    const count = (s: string) => (defs as ConceptDef[]).filter((d) => d.stereotype === s).length;
    expect([count('kind'), count('role'), count('event'), count('quality'), count('phase'), count('relation_type')])
      .toEqual([7, 1, 5, 5, 6, 1]);
  });

  it('keeps every property in authored order, typed where the grammar types it', () => {
    const item = concept(defs, 'Item');
    expect(keys(item)).toEqual(['description', 'label', 'sources', 'grounds', 'qualities', 'lexicon']);
    expect(item.description).toMatchObject({ kind: 'string', value: 'A product the stores, the web shop and the marketplace sell.' });
    expect(item.label?.entries).toEqual({ en: 'Product', cs: 'Produkt' });
    expect(conceptProperty(item, 'grounds')?.ref).toMatchObject({ path: 'md.Product', parts: ['md', 'Product'] });
    expect(conceptEntry(item, 'sources')?.value).toMatchObject({ kind: 'list', items: [{ kind: 'string', value: 'doc://hartland/handbook#products' }] });
    expect(conceptEntry(item, 'qualities')?.value).toMatchObject({ kind: 'list', items: [{ kind: 'id', path: 'category' }, { kind: 'id', path: 'brand' }] });
    expect(conceptProperty(item, 'lexicon')?.block.terms).toEqual(['product', 'item', 'SKU']);
    for (const p of item.properties) {
      expect(p.source.offsetEnd, p.key).toBeGreaterThan(p.source.offsetStart);
      expect(fixture('75-cn-concepts.ttrm').slice(p.source.offsetStart, p.source.offsetEnd).startsWith(p.key), p.key).toBe(true);
    }
  });

  it('keeps a free-form object value structural (the grounding `hierarchy`)', () => {
    const cat = concept(defs, 'ProductCategory');
    expect(keys(cat)).toEqual(['label', 'grounds', 'hierarchy']);
    const h = conceptEntry(cat, 'hierarchy')!.value;
    expect(h).toMatchObject({
      kind: 'object',
      entries: [
        { key: 'closure', value: { kind: 'id', path: 'er.entity.category_closure' } },
        { key: 'ancestor', value: { kind: 'id', path: 'ancestor_code' } },
        { key: 'descendant', value: { kind: 'id', path: 'descendant_code' } },
        { key: 'ranges', value: { kind: 'id', path: 'node' } },
      ],
    });
  });

  it('reads `is_a` (one ref and a list), `members` (a block) and the open tier', () => {
    const home = concept(defs, 'HomeCategory');
    expect(conceptEntry(home, 'is_a')?.value).toMatchObject({ kind: 'id', path: 'ProductCategory' });
    expect(conceptProperty(home, 'members')?.block).toMatchObject({
      kind: 'embeddedBlock', language: 'pl', form: 'tripleString', text: "descendants(ProductCategory, 'H10-H19')",
    });
    const supplier = concept(defs, 'Supplier');
    expect(conceptEntry(supplier, 'is_a')?.value).toMatchObject({ kind: 'list', items: [{ path: 'Party' }, { path: 'Organization' }] });
    expect(conceptEntry(supplier, 'relations')?.value).toMatchObject({
      kind: 'list',
      items: [{ kind: 'object', entries: [{ key: 'type', value: { path: 'supplies' } }, { key: 'to', value: { path: 'Warehouse' } }] }],
    });
    const supplies = concept(defs, 'supplies');
    expect(supplies.stereotype).toBe('relation_type');
    expect(supplies.description?.value).toBe('A supplier supplies a distribution center.');
  });

  it('walks a `def role` in `model cn` to a concept role: `event:` a ref, `of:` free-form', () => {
    const customer = concept(defs, 'Customer');
    expect(customer.stereotype).toBe('role');
    expect(keys(customer)).toEqual(['of', 'event', 'label', 'grounds']);
    expect(conceptRef(customer, 'event')?.ref.path).toBe('Order');
    expect(conceptEntry(customer, 'of')?.value).toMatchObject({ kind: 'id', path: 'Party' });
    expect(customer.label?.entries).toEqual({ en: 'Customer', cs: 'Zákazník' });
  });

  it('reads labelled participants, the period object and the extent', () => {
    const transfer = concept(defs, 'StockTransfer');
    expect(conceptProperty(transfer, 'extent')?.value).toBe('instantaneous');
    const parts = conceptProperty(transfer, 'involves')!.participants;
    expect(parts.map((p) => [p.ref.path, p.label])).toEqual([['Item', undefined], ['Warehouse', 'from'], ['Warehouse', 'to']]);
    expect(conceptProperty(transfer, 'period')?.value).toMatchObject({
      kind: 'object',
      entries: [{ key: 'at', value: { kind: 'id', path: 'shipped_at' } }, { key: 'precision', value: { kind: 'id', path: 'day' } }],
    });
    const order = concept(defs, 'Order');
    expect(conceptProperty(order, 'involves')!.participants.map((p) => p.label)).toEqual([undefined, 'fulfilling']);
    expect(conceptProperty(order, 'period')?.value.entries.map((e) => e.key)).toEqual(['start', 'end', 'end_open']);
    // newline-separated properties, no commas
    expect(keys(concept(defs, 'WarehouseOutage'))).toEqual(['extent', 'involves', 'period']);
  });

  it('reads qualities: value_space, valued_state, and the three definition forms', () => {
    expect(conceptProperty(concept(defs, 'category'), 'valueSpace')?.ref.path).toBe('ProductCategory');
    expect(conceptProperty(concept(defs, 'category'), 'valueSpace')?.key).toBe('value_space');
    expect(conceptEntry(concept(defs, 'stock_level'), 'valued_state')?.value).toMatchObject({ kind: 'bool', value: true });
    expect(conceptProperty(concept(defs, 'open_amount'), 'definition')?.block).toMatchObject({ form: 'string', text: 'amount - paid' });
    expect(conceptProperty(concept(defs, 'transfer_delay'), 'definition')?.block).toMatchObject({
      form: 'tagged', language: 'pl', text: 'this.period.at - this.from.period.at',
    });
  });

  it('reads phases: defined, stacked, abstract, with a basis and an imprecision', () => {
    expect(conceptProperty(concept(defs, 'PendingOrder'), 'definition')?.block.text).toBe("state in ('placed', 'held')");
    expect(conceptEntry(concept(defs, 'DebtorCustomer'), 'imprecision')?.value).toMatchObject({ kind: 'id', path: 'certainly' });
    expect(conceptProperty(concept(defs, 'PlacedOrder'), 'grounds')?.ref.path).toBe('er.entity.placed_order');
    expect(conceptEntry(concept(defs, 'StockCondition'), 'abstract')?.value).toMatchObject({ kind: 'bool', value: true });
    const oos = concept(defs, 'OutOfStock');
    expect(keys(oos)).toEqual(['of', 'is_a', 'basis', 'definition']);
    expect(conceptProperty(oos, 'basis')?.value).toBe('current_state');
  });

  it('reports a repeated key once and reads the first', () => {
    const { ast: a, errors } = parseString('model cn\ndef kind X { grounds: md.A, label: { en: "x" }, grounds: md.B }', 'dup.ttrm');
    expect(errors).toHaveLength(1);
    expect(errors[0]).toMatchObject({ code: DiagnosticCode.DuplicateProperty, severity: 'error', source: { line: 2, column: 48 } });
    expect(errors[0].message).toContain("'grounds'");
    const x = a!.definitions[0] as ConceptDef;
    expect(conceptProperty(x, 'grounds')?.ref.path).toBe('md.A');
    expect(keys(x)).toEqual(['grounds', 'label', 'grounds']);
  });

  it('reads a role by the file model: a cnc role and an automation role keep their 0.16 shape', () => {
    const cnc = parseString('model cnc\ndef role buyer { label: { en: "Buyer" } }', 'r.ttrm');
    expect(cnc.errors).toEqual([]);
    expect(cnc.ast!.definitions[0]).toMatchObject({ kind: 'role', name: 'buyer', label: { entries: { en: 'Buyer' } } });
    const auto = parseString('model automation\ndef role sales { resolve: keycloak_group "hartland-sales" }', 'r.ttrm');
    expect(auto.errors).toEqual([]);
    const sales = auto.ast!.definitions[0] as RoleDef;
    expect(sales.kind).toBe('role');
    expect(sales.entries?.map((e) => e.key)).toEqual(['resolve']);
  });

  it('rejects the cnc / automation role properties on a concept role (review-114 M5, both ways)', () => {
    const src = 'model cn\ndef role Customer {\n  event: Order,\n  resolve: keycloak_group "x",\n  search { searchable: true }\n}';
    const { ast: a, errors } = parseString(src, 'r.ttrm');
    expect(errors.map((e) => [e.code, e.source.line])).toEqual([
      [DiagnosticCode.UnknownProperty, 4],
      [DiagnosticCode.UnknownProperty, 5],
    ]);
    expect(errors[0].message).toContain("'resolve'");
    const customer = a!.definitions[0] as ConceptDef;
    expect(keys(customer)).toEqual(['event']);
  });
});

// ---------------------------------------------------------------------------------------------------
// T3 — bindings, the sugar, lexicon targets
// ---------------------------------------------------------------------------------------------------

describe('cn walker — bindings (T3, fixture 76)', () => {
  const defs = parseFixture('76-cn-bindings.ttrm').definitions;

  it('walks the ten binding kinds', () => {
    expect((defs as ConceptBindingDef[]).map((d) => [d.kind, d.bindingKind])).toEqual([
      ['conceptBinding', 'cn2md_kind'],
      ['conceptBinding', 'cn2md_role'],
      ['conceptBinding', 'cn2md_quality'],
      ['conceptBinding', 'cn2md_quality'],
      ['conceptBinding', 'cn2md_event'],
      ['conceptBinding', 'cn2er_kind'],
      ['conceptBinding', 'cn2er_kind'],
      ['conceptBinding', 'cn2er_role'],
      ['conceptBinding', 'cn2er_role'],
      ['conceptBinding', 'cn2er_event'],
      ['conceptBinding', 'cn2er_event'],
      ['conceptBinding', 'cn2er_quality'],
      ['conceptBinding', 'cn2er_quality'],
      ['conceptBinding', 'cn2er_phase'],
      ['conceptBinding', 'cn2db_concept'],
    ]);
  });

  it('reads the stereotype ref (with its `cn.` head) and the target', () => {
    const item = binding(defs, 'item_g');
    expect(conceptRef(item, 'kind')?.ref).toMatchObject({ path: 'cn.Item', parts: ['cn', 'Item'] });
    expect(conceptProperty(item, 'target')?.value).toMatchObject({ path: 'md.Product' });
    const brand = binding(defs, 'brand_g');
    expect(brand.description?.value).toBe('an attribute, not a measure');
    expect(conceptProperty(brand, 'target')?.value).toMatchObject({ path: 'md.Product.brand' });
    const legacy = binding(defs, 'legacy_g');
    expect(conceptRef(legacy, 'concept')?.ref.path).toBe('cn.LegacyStock');
    expect(legacy.tags).toEqual(['legacy']);
    expect(conceptRef(binding(defs, 'placed_g'), 'phase')?.ref.path).toBe('cn.PlacedOrder');
  });

  it('keeps `participants` a structured label → relation-path object, `via` a list', () => {
    const transfer = binding(defs, 'transfer_g');
    expect(keys(transfer)).toEqual(['event', 'target', 'participants', 'period']);
    expect(conceptEntry(transfer, 'participants')?.value).toMatchObject({
      kind: 'object',
      entries: [
        { key: 'item', value: { kind: 'list', items: [{ path: 'er.relation.rel_transfer_item' }] } },
        { key: 'from', value: { kind: 'list', items: [{ path: 'er.relation.rel_transfer_from' }] } },
        { key: 'to', value: { kind: 'list', items: [{ path: 'er.relation.rel_transfer_to' }, { path: 'er.relation.rel_warehouse_region' }] } },
      ],
    });
    expect(conceptProperty(transfer, 'period')?.value.entries[0]).toMatchObject({ key: 'at', value: { path: 'er.entity.stock_transfer.shipped_at' } });
    expect(conceptEntry(binding(defs, 'orderer_g'), 'via')?.value).toMatchObject({ kind: 'list', items: [{ path: 'er.relation.rel_order_customer' }] });
    expect(conceptEntry(binding(defs, 'category_g'), 'hierarchy')?.value).toMatchObject({ kind: 'object' });
  });

  it('reads a binding `filter:` as an expression block', () => {
    const stock = binding(defs, 'stock_g');
    expect(conceptProperty(stock, 'filter')).toMatchObject({ key: 'filter', block: { form: 'tripleString', text: 'warehouse = 1' } });
    expect(conceptEntry(stock, 'filter')).toBeUndefined();
  });
});

describe('cn walker — the `concept { }` sugar (T3, fixture 77)', () => {
  const defs = parseFixture('77-cn-sugar.ttrm').definitions;
  const byName = (name: string) => defs.find((d) => d.name === name)!;

  it('attaches the sugar to every md host', () => {
    const cubelet = byName('storeReturns') as CubeletDef;
    expect(cubelet.concept?.kind).toBe('conceptSugar');
    expect(conceptRef(cubelet.concept!, 'event')?.ref.path).toBe('Return');
    expect(conceptProperty(cubelet.concept!, 'involves')?.participants.map((p) => p.ref.path)).toEqual(['Customer', 'Item', 'ReturnReason']);
    expect(cubelet.grain).toEqual(['Customer.code', 'Product.code']);
    const dim = byName('Product') as DimensionDef;
    expect(conceptRef(dim.concept!, 'kind')?.ref.path).toBe('Item');
    expect(dim.concept!.label?.entries).toEqual({ en: 'Product' });
    expect(dim.key).toBe('sku');
    expect(conceptRef((byName('ReasonCode') as MdDomainDef).concept!, 'kind')?.ref.path).toBe('ReturnReason');
    expect(conceptRef((byName('returnAmount') as MeasureDef).concept!, 'quality')?.ref.path).toBe('return_amount');
  });

  it('attaches the sugar to entities and er attributes', () => {
    const invoice = byName('invoice') as EntityDef;
    expect(conceptRef(invoice.concept!, 'kind')?.ref.path).toBe('Invoice');
    const status = invoice.attributes!.find((a) => a.name === 'status')!;
    expect(conceptRef(status.concept!, 'quality')?.ref.path).toBe('invoice_status');
    expect(invoice.attributes!.find((a) => a.name === 'invoice_id')!.concept).toBeUndefined();
    const order = byName('order') as EntityDef;
    expect(keys(order.concept!)).toEqual(['event', 'period']);
    const customer = byName('customer') as EntityDef;
    expect(keys(customer.concept!)).toEqual(['role', 'event']);
    const channel = (byName('channel_sales') as EntityDef).attributes![0];
    expect(conceptRef(channel.concept!, 'kind')?.ref.path).toBe('Channel');
    // the sugar spans the whole `concept { … }` property
    const src = fixture('77-cn-sugar.ttrm');
    expect(src.slice(order.concept!.source.offsetStart, order.concept!.source.offsetEnd)).toBe(
      'concept { event: Order, period: { start: placed_date, end: delivered_date } }',
    );
  });
});

describe('cn walker — lexicon targets (T3, fixture 78)', () => {
  const defs = parseFixture('78-cn-lexicon.ttrm').definitions as LexiconEntryDef[];
  const entry = (name: string) => defs.find((d) => d.name === name)!;

  it('reads `for: cn.X`, `default` and `sense_priority`', () => {
    expect(entry('customer')).toMatchObject({ kind: 'term', target: { path: 'cn.Customer' }, default: true });
    expect(entry('customer').sensePriority).toBeUndefined();
    expect(entry('debtor')).toMatchObject({ sensePriority: 2 });
    expect(entry('debtor').default).toBeUndefined();
    expect(entry('customer_phase')).toMatchObject({ default: false, sensePriority: 1 });
    expect(entry('revenue')).toMatchObject({ default: true, sensePriority: 3 });
    expect(entry('channel')).toMatchObject({ target: { path: 'cn.Channel' } });
    expect(entry('channel').default).toBeUndefined();
    expect(entry('web_revenue')).toMatchObject({ kind: 'example', default: true, sensePriority: 1 });
  });
});

// ---------------------------------------------------------------------------------------------------
// T4 — expression blocks
// ---------------------------------------------------------------------------------------------------

describe('cn walker — expression blocks (T4)', () => {
  function block(src: string, name = 'P'): { block: EmbeddedBlock; errors: ReturnType<typeof parseString>['errors'] } {
    const { ast, errors } = parseString(src, 'b.ttrm');
    const def = ast!.definitions.find((d) => d.name === name) as ConceptDef;
    return { block: conceptProperty(def, 'definition')!.block, errors };
  }

  const TAGGED = [
    'model cn',
    'def phase P {',
    '    of: Order,',
    '    definition: """pl',
    '        exists Invoicing i involving this',
    '          where i.amount > 0',
    '',
    "        and state = 'open'",
    '    """',
    '}',
  ].join('\n');

  it('keeps a tagged block byte-exact — inner indent, blank line, no closing newline', () => {
    const { block: b, errors } = block(TAGGED);
    expect(errors).toEqual([]);
    expect(b.form).toBe('tagged');
    expect(b.text).toBe("exists Invoicing i involving this\n  where i.amount > 0\n\nand state = 'open'");
    expect(b.indentWidth).toBe(8);
    expect(b.lineOffsets).toHaveLength(4);
  });

  it('points a diagnostic at column n of line 2 of the block', () => {
    const { block: b } = block(TAGGED);
    // `i.amount` — line 1 of the text, column 8
    const at = embeddedBlockLocation(b, 1, 8, 8);
    expect(TAGGED.slice(at.offsetStart, at.offsetEnd)).toBe('i.amount');
    expect({ line: at.line, column: at.column }).toEqual(positionOf(TAGGED, TAGGED.indexOf('i.amount')));
    expect(at.endColumn - at.column).toBe(8);
    // line 0 and the line after the blank one
    const exists = embeddedBlockLocation(b, 0, 0, 6);
    expect(TAGGED.slice(exists.offsetStart, exists.offsetEnd)).toBe('exists');
    expect({ line: exists.line, column: exists.column }).toEqual(positionOf(TAGGED, TAGGED.indexOf('exists')));
    const state = embeddedBlockLocation(b, 3, 4, 5);
    expect(TAGGED.slice(state.offsetStart, state.offsetEnd)).toBe('state');
    expect({ line: state.line, column: state.column }).toEqual(positionOf(TAGGED, TAGGED.indexOf("state = 'open'")));
    expect(TAGGED.slice(b.textSource.offsetStart, b.textSource.offsetEnd)).toBe(
      "exists Invoicing i involving this\n          where i.amount > 0\n\n        and state = 'open'",
    );
  });

  it('maps offsets in a CRLF file too', () => {
    const crlf = TAGGED.replace(/\n/g, '\r\n');
    const { block: b, errors } = block(crlf);
    expect(errors).toEqual([]);
    const at = embeddedBlockLocation(b, 1, 8, 8);
    expect(crlf.slice(at.offsetStart, at.offsetEnd)).toBe('i.amount');
  });

  it('maps a one-line triple-string and a plain string from their opening quote', () => {
    const src = `model cn\ndef phase P { of: Order, definition: """state in ('placed', 'held')""" }`;
    const { block: b } = block(src);
    expect(b).toMatchObject({ form: 'tripleString', text: "state in ('placed', 'held')", lineOffsets: [src.indexOf('state')] });
    const at = embeddedBlockLocation(b, 0, 9, 1);
    expect(src.slice(at.offsetStart, at.offsetEnd)).toBe('(');
    expect(at.column).toBe(src.indexOf('(') - src.indexOf('\n') - 1);
    const plain = `model cn\ndef quality P { of: Invoicing, definition: "amount - paid" }`;
    const { block: s } = block(plain);
    expect(s).toMatchObject({ form: 'string', text: 'amount - paid' });
    const minus = embeddedBlockLocation(s, 0, 7);
    expect(plain.slice(minus.offsetStart, minus.offsetEnd)).toBe('-');
  });

  it('keeps an unknown first-line word as text (a warning), and refuses a foreign-language tag', () => {
    const word = block('model cn\ndef phase P { definition: """exists\n  Invoicing involving this\n""" }');
    expect(word.block).toMatchObject({ form: 'tripleString', text: 'exists\n  Invoicing involving this\n' });
    expect(word.errors.map((e) => [e.code, e.severity])).toEqual([[DiagnosticCode.UnknownLanguageTag, 'warning']]);
    const sql = block('model cn\ndef phase P { definition: """sql\n  SELECT 1\n""" }');
    expect(sql.errors.map((e) => [e.code, e.severity])).toEqual([[DiagnosticCode.LanguageTagMismatch, 'error']]);
    expect(sql.errors[0].message).toContain("'sql'");
  });

  it('leaves the SQL hosts alone: `pl` is still an unknown tag on a view', () => {
    const { errors } = parseString('model db\ndef view v { definitionSql: """pl\nSELECT 1\n""" }', 'v.ttrm');
    expect(errors.map((e) => e.code)).toEqual([DiagnosticCode.UnknownLanguageTag]);
  });
});

// ---------------------------------------------------------------------------------------------------
// T6 — parse-level diagnostics and recovery
// ---------------------------------------------------------------------------------------------------

describe('cn walker — diagnostics and recovery (T6)', () => {
  it('names the block form when `definition:` / `members:` gets a bare value', () => {
    for (const [prop, value] of [['definition', 'OverdueInvoice'], ['members', '["H10", "H11"]']]) {
      const { errors } = parseString(`model cn\ndef phase P { of: Order, ${prop}: ${value} }`, 'n.ttrm');
      const parse = errors.filter((e) => e.code === DiagnosticCode.ParseError);
      expect(parse.length, prop).toBeGreaterThan(0);
      expect(parse[0].message, prop).toContain(`'${prop}:' takes an expression block`);
      expect(parse[0].source.line).toBe(2);
    }
  });

  it('names the bracket form when `involves:` gets a bare ref', () => {
    const { errors } = parseString('model cn\ndef event E { involves: Item }', 'n.ttrm');
    const parse = errors.filter((e) => e.code === DiagnosticCode.ParseError);
    expect(parse[0].message).toContain("'involves:' takes a bracketed list");
  });

  it('flags a binding `filter:` that is not a block', () => {
    const { ast, errors } = parseString('model binding\ndef cn2er_quality q { quality: cn.x, target: er.entity.o.v, filter: analyte }', 'f.ttrm');
    expect(errors.map((e) => [e.code, e.severity])).toEqual([[DiagnosticCode.ExpressionBlockRequired, 'error']]);
    const q = ast!.definitions[0] as ConceptBindingDef;
    expect(keys(q)).toEqual(['quality', 'target', 'filter']);
    expect(conceptEntry(q, 'filter')?.value).toMatchObject({ kind: 'id', path: 'analyte' });
  });

  it('recovers past an unknown binding kind', () => {
    const src = 'model binding\ndef cn2xx_kind a { kind: cn.Item, target: md.Product }\ndef cn2md_kind b { kind: cn.Item, target: md.Product }';
    const { ast, errors } = parseString(src, 'u.ttrm');
    expect(errors.some((e) => e.code === DiagnosticCode.ParseError && e.source.line === 2)).toBe(true);
    expect(ast!.definitions.some((d) => d.kind === 'conceptBinding' && d.name === 'b')).toBe(true);
  });

  it('keeps a half-typed `def phase` (unclosed body) — the AST the LSP works on', () => {
    const src = "model cn\ndef kind Order { label: { en: \"Order\" } }\ndef phase Pending {\n  of: Order,\n  definition: \"\"\"state = 'open'\"\"\",\n  basis: ";
    const { ast, errors } = parseString(src, 'h.ttrm');
    expect(errors.some((e) => e.severity === 'error')).toBe(true);
    expect(ast).toBeDefined();
    const pending = concept(ast!.definitions, 'Pending');
    expect(conceptEntry(pending, 'of')?.value).toMatchObject({ path: 'Order' });
    expect(conceptProperty(pending, 'definition')?.block.text).toBe("state = 'open'");
    expect(concept(ast!.definitions, 'Order').label?.entries).toEqual({ en: 'Order' });
  });

  it('survives a missing body and a conjured block token', () => {
    for (const src of ['model cn\ndef phase P', 'model cn\ndef phase P { definition: }', 'model cn\ndef event E { involves: [ ] }', 'model cn\ndef role R']) {
      const { ast, errors } = parseString(src, 'm.ttrm');
      expect(errors.some((e) => e.severity === 'error'), src).toBe(true);
      expect(ast, src).toBeDefined();
      expect(ast!.definitions.length, src).toBe(1);
    }
  });
});

// ---------------------------------------------------------------------------------------------------
// S1.3 T4 — the read is lossless, and the cn rejects fail at the same place on all three targets
// (the Kotlin `CnWalkerSpec` and the Python `test_cn_walker.py` assert the same table, 1-based columns)
// ---------------------------------------------------------------------------------------------------

describe('cn walker — lossless read and shared error positions (S1.3 T4)', () => {
  const bodies = (defs: Definition[]): ConceptBody[] =>
    defs.flatMap((d): ConceptBody[] => {
      if (d.kind === 'concept' || d.kind === 'conceptBinding') return [d];
      if (d.kind === 'entity') return [d.concept, ...(d.attributes ?? []).map((a) => a.concept)].filter((s): s is ConceptSugar => s !== undefined);
      if (d.kind === 'cubelet' || d.kind === 'dimension' || d.kind === 'mdDomain' || d.kind === 'measure') return d.concept ? [d.concept] : [];
      return [];
    });

  it.each(['75-cn-concepts.ttrm', '76-cn-bindings.ttrm', '77-cn-sugar.ttrm'])(
    'every cn property of %s spans source text that starts with its key, and no separator',
    (name) => {
      const src = fixture(name);
      for (const p of bodies(parseFixture(name).definitions).flatMap((b) => b.properties)) {
        const text = src.slice(p.source.offsetStart, p.source.offsetEnd);
        expect(text.startsWith(p.key), p.key).toBe(true);
        expect(text.trimEnd().endsWith(','), p.key).toBe(false);
      }
    },
  );

  it('keeps participant labels and a block tag in their spans; a trailing comma is dropped', () => {
    const src = fixture('75-cn-concepts.ttrm');
    const defs = parseFixture('75-cn-concepts.ttrm').definitions;
    const parts = conceptProperty(concept(defs, 'StockTransfer'), 'involves')!.participants;
    expect(parts.map((p) => src.slice(p.source.offsetStart, p.source.offsetEnd))).toEqual(['Item', 'Warehouse as from', 'Warehouse as to']);
    const delay = conceptProperty(concept(defs, 'transfer_delay'), 'definition')!.block;
    expect(src.slice(delay.source.offsetStart, delay.source.offsetEnd).startsWith('"""pl')).toBe(true);
    const home = concept(defs, 'HomeCategory');
    const last = home.properties[home.properties.length - 1].source;
    expect(src.slice(last.offsetStart, last.offsetEnd)).toBe('label: { en: "Home and garden categories" }');
  });

  it.each([
    ['neg-01-bare-definition.ttrm', 3, 54],
    ['neg-02-involves-no-brackets.ttrm', 3, 27],
    ['neg-03-bare-members.ttrm', 3, 56],
    ['neg-04-unlabelled-as.ttrm', 3, 76],
  ])('%s reports its first syntax error at %i:%i (0-based column) on all three targets', (name, line, column) => {
    const negative = readFileSync(join(CN, '..', 'cn-negative', name), 'utf-8');
    const first = parseString(negative, name).errors.find((e) => e.code === DiagnosticCode.ParseError)!;
    expect([first.source.line, first.source.column]).toEqual([line, column]);
  });
});
