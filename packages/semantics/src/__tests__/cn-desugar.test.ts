// SPDX-License-Identifier: Apache-2.0
import { describe, it, expect } from 'vitest';
import { parseString, DiagnosticCode } from '@tatrman/parser';
import type { ConceptStereotype, Document } from '@tatrman/parser';
import { ProjectSymbolTable } from '../project-symbols.js';
import { Resolver } from '../resolver.js';
import { bindingConceptKey, bindingKindFor, desugarConcepts, layerOf, synthesizedBindingName } from '../cn/desugar.js';
import { analyzeGroundings, propertyFingerprint, type CnDocument } from '../cn/groundings.js';
import { desugarLexicon } from '../lexicon/desugar.js';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';

const FIXTURES = join(__dirname, '../../../../tests/conformance/fixtures');

// CN-P2 S2.2 — the cn desugar: `grounds:` (top-down) and `concept { }` (bottom-up) project into the
// binding an author would write; one grounding per concept (cn contracts §1.2, §1.4; CN-D9, CN-D12).

function parse(src: string): Document {
  const r = parseString(src);
  expect(r.errors.filter((e) => e.severity === 'error')).toEqual([]);
  return r.ast!;
}

interface Project {
  symbols: ProjectSymbolTable;
  resolver: Resolver;
  docs: CnDocument[];
}

function project(files: Array<{ uri: string; src: string }>): Project {
  const symbols = new ProjectSymbolTable();
  const docs: CnDocument[] = files.map(({ uri, src }) => {
    const ast = parse(src);
    return {
      uri,
      ast,
      packageName: ast.packageDecl?.name ?? '',
      schemaCode: ast.modelDirective?.modelCode ?? '',
      namespace: ast.modelDirective?.schema ?? '',
    };
  });
  for (const d of docs) symbols.upsertDocument(d.uri, d.ast, d.schemaCode, d.namespace, d.packageName);
  return { symbols, resolver: new Resolver(symbols), docs };
}

const ER = {
  uri: 'a-er.ttrm',
  src: `package hartland.stores
model er
def entity party { attributes: [ def attribute party_id { type: int, isKey: true } ] }
def entity sale {
    attributes: [ def attribute sale_id { type: int, isKey: true }, def attribute amount { type: decimal } ]
}
def entity placed_order { attributes: [ def attribute order_number { type: int, isKey: true } ] }
`,
};

const MD = {
  uri: 'b-md.ttrm',
  src: `package hartland.stores
model md
def dimension Product { key: sku, attributes: [ def attribute sku { domain: md.Sku, isKey: true } ] }
def measure net_sales { domain: md.Money, aggregation: sum }
`,
};

describe('T2 — the binding table: stereotype × layer', () => {
  it.each<[ConceptStereotype, string | undefined, string | undefined, string | undefined]>([
    ['kind', 'cn2md_kind', 'cn2er_kind', 'cn2db_concept'],
    ['role', 'cn2md_role', 'cn2er_role', 'cn2db_concept'],
    ['event', 'cn2md_event', 'cn2er_event', 'cn2db_concept'],
    ['quality', 'cn2md_quality', 'cn2er_quality', 'cn2db_concept'],
    ['phase', undefined, 'cn2er_phase', undefined],
    ['relation_type', undefined, undefined, undefined],
  ])('%s → md %s · er %s · db %s', (stereotype, md, er, db) => {
    expect(bindingKindFor(stereotype, 'md')).toBe(md);
    expect(bindingKindFor(stereotype, 'er')).toBe(er);
    expect(bindingKindFor(stereotype, 'db')).toBe(db);
  });

  it('a binding names its concept in the slot of its stereotype; `cn2db_concept` in `concept:`', () => {
    expect(bindingConceptKey('cn2md_event')).toBe('event');
    expect(bindingConceptKey('cn2er_quality')).toBe('quality');
    expect(bindingConceptKey('cn2er_phase')).toBe('phase');
    expect(bindingConceptKey('cn2db_concept')).toBe('concept');
  });

  it("a target's layer is its first md / er / db segment — a package prefix allowed, none = no layer", () => {
    const ref = (path: string) => ({ path, parts: path.split('.'), source: undefined as never });
    expect(layerOf(ref('md.Product'))).toBe('md');
    expect(layerOf(ref('er.entity.sale.amount'))).toBe('er');
    expect(layerOf(ref('db.dbo.loyalty_card'))).toBe('db');
    expect(layerOf(ref('hartland.stores.md.Product'))).toBe('md');
    expect(layerOf(ref('Product'))).toBeUndefined();
  });

  it('`grounds:` desugars to `cn2<layer>_<stereotype>` named `<Concept>.grounds`; `hierarchy:` rides along', () => {
    const doc = parse(`package hartland.stores
model cn
def event StoreSale { grounds: md.store_sales }
def kind ProductCategory {
    grounds: er.entity.category_code,
    hierarchy: { closure: er.entity.category_closure, ancestor: ancestor_code, descendant: descendant_code }
}
def kind LoyaltyCard { grounds: db.dbo.loyalty_card }
def phase PlacedOrder { of: Sale, grounds: er.entity.placed_order }
`);
    const { concepts, groundings } = desugarConcepts(doc);
    expect(concepts).toEqual([]);
    expect(groundings.map((g) => [g.origin, g.bindingKind, g.name, g.conceptName, g.target?.path])).toEqual([
      ['grounds', 'cn2md_event', 'StoreSale.grounds', 'StoreSale', 'md.store_sales'],
      ['grounds', 'cn2er_kind', 'ProductCategory.grounds', 'ProductCategory', 'er.entity.category_code'],
      ['grounds', 'cn2db_concept', 'LoyaltyCard.grounds', 'LoyaltyCard', 'db.dbo.loyalty_card'],
      ['grounds', 'cn2er_phase', 'PlacedOrder.grounds', 'PlacedOrder', 'er.entity.placed_order'],
    ]);
    expect(groundings[1].properties.map((p) => p.key)).toEqual(['hierarchy']);
    expect(synthesizedBindingName('StoreSale', 'grounds')).toBe('StoreSale.grounds');
  });

  it('a pair the table lacks (an md target on a phase) or a target with no layer synthesizes no binding', () => {
    const doc = parse(`package hartland.stores
model cn
def phase BigSale { of: Sale, grounds: md.big_sales }
def kind Item { grounds: Product }
def relation_type supplies { grounds: er.entity.supply }
`);
    const { groundings } = desugarConcepts(doc);
    expect(groundings.map((g) => [g.conceptName, g.bindingKind])).toEqual([
      ['BigSale', undefined],
      ['Item', undefined],
      ['supplies', undefined],
    ]);
    const { symbols } = project([{ uri: 'c.ttrm', src: `package hartland.stores
model cn
def phase BigSale { of: Sale, grounds: md.big_sales }
` }]);
    expect(symbols.all().filter((e) => e.kind === 'conceptBinding')).toEqual([]);
  });

  it('an authored binding names its concept by reference; its properties exclude the slot, `target:` and docs', () => {
    const doc = parse(`package hartland.stores
model binding
def cn2er_event purchase_g {
    description: "Web and store purchases",
    event: cn.Purchase,
    target: er.entity.sale,
    period: { at: er.entity.sale.sold_date }
}
def cn2db_concept loyalty_g { concept: cn.LoyaltyCard, target: db.dbo.loyalty_card }
`);
    const { groundings } = desugarConcepts(doc);
    expect(groundings.map((g) => [g.origin, g.bindingKind, g.name, g.conceptRef?.path, g.target?.path])).toEqual([
      ['binding', 'cn2er_event', 'purchase_g', 'cn.Purchase', 'er.entity.sale'],
      ['binding', 'cn2db_concept', 'loyalty_g', 'cn.LoyaltyCard', 'db.dbo.loyalty_card'],
    ]);
    expect(groundings[0].properties.map((p) => p.key)).toEqual(['period']);
  });
});

describe('T3 — the `concept { }` block declares its concept and grounds it to its host', () => {
  const SUGAR = `package hartland.stores
model er
def entity sale {
    attributes: [
        def attribute sale_id { type: int, isKey: true },
        def attribute amount  { type: decimal, concept { quality: amount, of: Purchase } }
    ],
    concept { event: Purchase, involves: [Buyer, Shop as outlet] }
}
def entity customer { attributes: [ def attribute customer_id { type: int, isKey: true } ], concept: { role: Buyer, event: Purchase } }
`;

  it('the stereotype slot declares (a role block: `role:`, not its `event:`); the host is the target', () => {
    const { concepts, groundings } = desugarConcepts(parse(SUGAR));
    expect(concepts.map((c) => [c.name, c.stereotype, c.hostKey, c.layer])).toEqual([
      ['Purchase', 'event', 'hartland.stores.er.entity.sale', 'er'],
      ['amount', 'quality', 'hartland.stores.er.entity.sale.amount', 'er'],
      ['Buyer', 'role', 'hartland.stores.er.entity.customer', 'er'],
    ]);
    expect(groundings.map((g) => [g.origin, g.bindingKind, g.name, g.targetKey])).toEqual([
      ['sugar', 'cn2er_event', 'Purchase.concept', 'hartland.stores.er.entity.sale'],
      ['sugar', 'cn2er_quality', 'amount.concept', 'hartland.stores.er.entity.sale.amount'],
      ['sugar', 'cn2er_role', 'Buyer.concept', 'hartland.stores.er.entity.customer'],
    ]);
  });

  it('md hosts: dimension, its attribute, domain, measure and cubelet land on the md layer', () => {
    const { concepts } = desugarConcepts(parse(`package hartland.stores
model md
def dimension Product {
    key: sku,
    attributes: [ def attribute sku { domain: md.Sku, isKey: true, concept { quality: sku_code } } ],
    concept { kind: Item }
}
def domain Brand { type: text, concept { kind: Brand } }
def measure net_sales { domain: md.Money, aggregation: sum, concept { quality: net_sales } }
def cubelet store_sales { grain: [Product.sku], concept { event: StoreSale } }
`));
    expect(concepts.map((c) => [c.name, c.hostKey, c.layer])).toEqual([
      ['Item', 'hartland.stores.md.dimension.Product', 'md'],
      ['sku_code', 'hartland.stores.md.dimension.Product.sku', 'md'],
      ['Brand', 'hartland.stores.md.domain.Brand', 'md'],
      ['net_sales', 'hartland.stores.md.measure.net_sales', 'md'],
      ['StoreSale', 'hartland.stores.md.cubelet.store_sales', 'md'],
    ]);
  });

  it('the declared concepts and synthesized bindings are symbols, flagged by origin, sourced at the block', () => {
    const { symbols } = project([{ uri: 'er.ttrm', src: SUGAR }]);
    const purchase = symbols.get('hartland.stores.cn.concept.Purchase')!;
    expect(purchase).toMatchObject({ kind: 'concept', stereotype: 'event', schemaCode: 'cn', cnOrigin: 'sugar' });
    expect(purchase.source.line).toBe(8);
    // the block's event registers its participant labels, as a cn def's does
    expect(symbols.get('hartland.stores.cn.concept.Purchase.buyer')).toMatchObject({ kind: 'participant', cnOrigin: 'sugar' });
    expect(symbols.get('hartland.stores.cn.concept.Purchase.outlet')).toBeDefined();
    expect(symbols.get('hartland.stores.binding.cn2er_event.Purchase.concept')).toMatchObject({
      kind: 'conceptBinding',
      bindingKind: 'cn2er_event',
      schemaCode: 'binding',
      cnOrigin: 'sugar',
    });
    expect(symbols.get('hartland.stores.binding.cn2er_quality.amount.concept')).toBeDefined();
  });

  it('a cn def in the same document keeps its symbol; the block only grounds it', () => {
    const { symbols } = project([{ uri: 'mixed.ttrm', src: `package hartland.stores
def kind Party { label: { en: "Party" } }
def entity party { attributes: [ def attribute party_id { type: int, isKey: true } ], concept { kind: Party } }
` }]);
    const party = symbols.getAll('hartland.stores.cn.concept.Party');
    expect(party).toHaveLength(1);
    expect(party[0].cnOrigin).toBeUndefined();
    expect(symbols.get('hartland.stores.binding.cn2er_kind.Party.concept')).toBeDefined();
  });
});

describe('T6 — resolution over the desugared symbols', () => {
  const CONCEPTS = {
    uri: 'c-concepts.ttrm',
    src: `package hartland.stores
model cn
def kind Party { label: { en: "Party" } }
def event Purchase { involves: [Party as buyer], grounds: er.entity.sale }
`,
  };
  const SUGAR = {
    uri: 'a-sugar.ttrm',
    src: `package hartland.stores
model er
def entity party { attributes: [ def attribute party_id { type: int, isKey: true } ], concept { kind: Party } }
def entity shop  { attributes: [ def attribute shop_id { type: int, isKey: true } ], concept { kind: Shop } }
`,
  };
  const ctx = { schemaCode: '', namespace: '', packageName: 'hartland.stores', imports: [] };

  it('a cn ref reaches a block-declared concept, landing on the block', () => {
    const { resolver } = project([SUGAR, CONCEPTS]);
    const r = resolver.resolveReference({ path: 'Shop', parts: ['Shop'], cnContext: true }, ctx);
    expect(r.resolved && r.symbol.documentUri).toBe('a-sugar.ttrm');
  });

  it('an authored concept wins over the block that grounds it, whatever the document order', () => {
    for (const files of [[SUGAR, CONCEPTS], [CONCEPTS, SUGAR]]) {
      const { resolver, symbols } = project(files);
      const r = resolver.resolveReference({ path: 'cn.Party', parts: ['cn', 'Party'] }, ctx);
      expect(r.resolved && r.symbol.documentUri).toBe('c-concepts.ttrm');
      expect(symbols.get('hartland.stores.cn.concept.Party')!.cnOrigin).toBeUndefined();
    }
  });

  it('an element ref never reaches a synthesized binding', () => {
    const { resolver } = project([ER, CONCEPTS]);
    for (const path of ['Purchase.grounds', 'grounds']) {
      const r = resolver.resolveReference({ path, parts: path.split('.') }, ctx);
      expect(r.resolved, path).toBe(false);
    }
  });
});

describe('T4 — one grounding per concept', () => {
  const codes = (p: Project) => analyzeGroundings(p.docs, p.resolver).diagnostics.map((d) => [d.code, d.uri, d.source.line]);

  it('`grounds:` and an authored binding: the second in document order is `cn/duplicate-grounding`', () => {
    const p = project([
      ER,
      { uri: 'c-concepts.ttrm', src: `package hartland.stores
model cn
def event Purchase { grounds: er.entity.sale }
` },
      { uri: 'd-bindings.ttrm', src: `package hartland.stores
model binding
def cn2er_event purchase_g { event: cn.Purchase, target: er.entity.sale }
` },
    ]);
    const a = analyzeGroundings(p.docs, p.resolver);
    const list = a.byConcept.get('hartland.stores.cn.concept.Purchase')!;
    expect(list.map((g) => g.origin)).toEqual(['grounds', 'binding']);
    expect(list[0].targetSymbol?.qname).toBe('hartland.stores.er.entity.sale');
    expect(codes(p)).toEqual([[DiagnosticCode.CnDuplicateGrounding, 'd-bindings.ttrm', 3]]);
    expect(a.diagnostics[0].message).toContain('c-concepts.ttrm:3');
  });

  it('a block and an authored binding of another layer: still two groundings', () => {
    const p = project([
      MD,
      { uri: 'a-er.ttrm', src: `package hartland.stores
model er
def entity sale { attributes: [ def attribute sale_id { type: int, isKey: true } ], concept { event: Purchase } }
` },
      { uri: 'd-bindings.ttrm', src: `package hartland.stores
model binding
def cn2md_event purchase_g { event: Purchase, target: md.store_sales }
` },
    ]);
    expect(codes(p)).toEqual([[DiagnosticCode.CnDuplicateGrounding, 'd-bindings.ttrm', 3]]);
  });

  it('two blocks declaring one concept: the second grounding is reported', () => {
    const p = project([
      { uri: 'a-er.ttrm', src: `package hartland.stores
model er
def entity party { attributes: [ def attribute party_id { type: int, isKey: true } ], concept { kind: Party } }
def entity person { attributes: [ def attribute person_id { type: int, isKey: true } ], concept { kind: Party } }
` },
    ]);
    expect(codes(p)).toEqual([[DiagnosticCode.CnDuplicateGrounding, 'a-er.ttrm', 4]]);
  });

  it('an unresolved concept slot, or a grounding with no binding kind, grounds nothing', () => {
    const p = project([
      ER,
      { uri: 'c-concepts.ttrm', src: `package hartland.stores
model cn
def phase BigSale { of: Purchase, grounds: md.big_sales }
def event Purchase { grounds: er.entity.sale }
` },
      { uri: 'd-bindings.ttrm', src: `package hartland.stores
model binding
def cn2er_event ghost_g { event: cn.Ghost, target: er.entity.sale }
def cn2er_phase big_g { phase: cn.BigSale, target: er.entity.placed_order }
` },
    ]);
    const a = analyzeGroundings(p.docs, p.resolver);
    expect([...a.byConcept.keys()].sort()).toEqual([
      'hartland.stores.cn.concept.BigSale',
      'hartland.stores.cn.concept.Purchase',
    ]);
    expect(a.diagnostics).toEqual([]);
  });
});

describe('T3 — the merge rule: a block may add only what the cn def leaves unset', () => {
  const merged = (block: string, def: string) => {
    const p = project([
      { uri: 'a-er.ttrm', src: `package hartland.stores
model er
def entity customer {
    attributes: [ def attribute customer_id { type: int, isKey: true } ],
    concept: { ${block} }
}
` },
      { uri: 'c-concepts.ttrm', src: `package hartland.stores
model cn
${def}
def event Purchase { }
def event Visit { }
def kind Party { }
` },
    ]);
    return analyzeGroundings(p.docs, p.resolver).diagnostics;
  };

  it('the block restating what the def says — or naming only the concept — is no conflict', () => {
    expect(merged('role: Buyer', 'def role Buyer { of: Party, event: Purchase }')).toEqual([]);
    expect(merged('role: Buyer, event: Purchase', 'def role Buyer { of: Party, event: Purchase }')).toEqual([]);
    expect(merged('role: Buyer, event: cn.Purchase', 'def role Buyer { of: Party, event: Purchase }')).toEqual([]);
    expect(merged('role: Buyer, event: Purchase', 'def role Buyer { of: Party }')).toEqual([]);
  });

  it('another stereotype is `cn/duplicate-grounding` at the declaring slot', () => {
    const d = merged('role: Buyer, event: Purchase', 'def kind Buyer { }');
    expect(d.map((x) => [x.code, x.uri, x.source.line])).toEqual([[DiagnosticCode.CnDuplicateGrounding, 'a-er.ttrm', 5]]);
    expect(d[0].message).toContain('declares a role');
  });

  it('a property both state differently is `cn/duplicate-grounding` at the block\'s property', () => {
    const d = merged('role: Buyer, event: Visit', 'def role Buyer { of: Party, event: Purchase }');
    expect(d.map((x) => [x.code, x.source.column])).toEqual([[DiagnosticCode.CnDuplicateGrounding, 28]]);
    expect(d[0].message).toContain("'event' of 'Buyer'");
  });

  it('a fingerprint ignores where a property was written and the `cn.` head', () => {
    const a = parse('package p\nmodel cn\ndef role Buyer { event: cn.Purchase }').definitions[0];
    const b = parse('package p\nmodel cn\n\n\ndef role Buyer {\n    event: Purchase\n}').definitions[0];
    if (a.kind !== 'concept' || b.kind !== 'concept') throw new Error('not a concept');
    expect(propertyFingerprint(a.properties[0])).toBe(propertyFingerprint(b.properties[0]));
  });
});

describe('T5 — a concept\'s inline `lexicon { }` and the sense fields', () => {
  it("fixture 75: `def kind Item`'s inline terms target `cn.Item`", () => {
    const doc = parse(readFileSync(join(FIXTURES, '75-cn-concepts.ttrm'), 'utf-8'));
    const item = desugarLexicon(doc).entries.filter((e) => e.target === 'cn.Item');
    expect(item.map((e) => [e.entryKind, e.origin, e.forms])).toEqual([['term', 'inline', ['product', 'item', 'SKU']]]);
  });

  it('fixture 78: `default` and `sense_priority` reach the canonical entries, unset ones stay absent', () => {
    const doc = parse(readFileSync(join(FIXTURES, '78-cn-lexicon.ttrm'), 'utf-8'));
    const sense = desugarLexicon(doc).entries.map((e) => [e.name, e.default, e.sensePriority]);
    expect(sense).toEqual([
      ['customer', true, undefined],
      ['debtor', undefined, 2],
      ['customer_phase', false, 1],
      ['revenue', true, 3],
      ['channel', undefined, undefined],
      ['product', undefined, undefined],
      ['web_revenue', true, 1],
    ]);
    expect(desugarLexicon(doc).entries.find((e) => e.name === 'channel')).not.toHaveProperty('default');
  });

  it("an inline block's sense fields ride its terms and examples; a `concept { }` block's lexicon names its concept", () => {
    const doc = parse(`package hartland.stores
model er
def entity store {
    attributes: [ def attribute store_id { type: int, isKey: true } ],
    lexicon: { terms: ["store"] },
    concept { kind: Shop, lexicon: { terms: ["shop", "outlet"], examples: ["sales per shop"], default: true, sense_priority: 2 } }
}
`);
    const entries = desugarLexicon(doc).entries.map((e) => [e.target, e.entryKind, e.default, e.sensePriority]);
    expect(entries).toEqual([
      ['er.entity.store', 'term', undefined, undefined],
      ['cn.Shop', 'term', true, 2],
      ['cn.Shop', 'example', true, 2],
    ]);
  });
});
