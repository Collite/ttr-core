// SPDX-License-Identifier: Apache-2.0
import { describe, it, expect } from 'vitest';
import { parseString } from '@tatrman/parser';
import { ProjectSymbolTable } from '../project-symbols.js';
import { Resolver } from '../resolver.js';
import type { CnDocument } from '../cn/groundings.js';
import { cncDeprecations, validateCn } from '../cn/validate.js';

// CN-P2 S2.3 — the model-level cn checks (cn contracts §7). The conformance fixtures 85–101 hold one
// firing case and one near-miss per slug (`@tatrman/lint` `cn-rules.test.ts`); these are the edge
// cases around them: which way a relation is read, what a block adds to a cn def, a cycle, a locale.

function validate(files: Array<{ uri: string; src: string }>) {
  const symbols = new ProjectSymbolTable();
  const docs: CnDocument[] = files.map(({ uri, src }) => {
    const r = parseString(src, uri);
    expect(r.errors.filter((e) => e.severity === 'error'), uri).toEqual([]);
    const ast = r.ast!;
    return {
      uri,
      ast,
      packageName: ast.packageDecl?.name ?? '',
      schemaCode: ast.modelDirective?.modelCode ?? '',
      namespace: ast.modelDirective?.schema ?? '',
    };
  });
  for (const d of docs) symbols.upsertDocument(d.uri, d.ast, d.schemaCode, d.namespace, d.packageName);
  return validateCn(docs, new Resolver(symbols));
}

/** `uri:line code` of every diagnostic but the hygiene ones (or only `code`, when given). */
function sites(files: Array<{ uri: string; src: string }>, code?: string): string[] {
  const hygiene = new Set(['cn/ungrounded', 'cn/event-no-period', 'cn/phase-no-definition']);
  return validate(files)
    .diagnostics.filter((d) => (code ? d.code === code : !hygiene.has(d.code)))
    .map((d) => `${d.uri}:${d.source.line} ${d.code}`);
}

const ER = {
  uri: 'a-er.ttrm',
  src: `package hartland.stores
model er
def entity item      { attributes: [ def attribute sku { type: text, isKey: true }, def attribute channel { type: text } ] }
def entity warehouse { attributes: [ def attribute warehouse_code { type: text, isKey: true } ] }
def entity transfer  { attributes: [ def attribute transfer_id { type: int, isKey: true } ] }
def relation warehouse_transfers { from: er.entity.warehouse, to: er.entity.transfer }
def relation transfer_item       { from: er.entity.transfer, to: er.entity.item }
`,
};

describe('cn/participant-path-broken — reading a path', () => {
  const concepts = (participants: string) => [
    ER,
    {
      uri: 'b-cn.ttrm',
      src: `package hartland.stores
model cn
def kind Warehouse { grounds: er.entity.warehouse }
def kind Channel   { grounds: er.entity.item.channel }
def event Transfer { involves: [Warehouse, Channel], period: { at: shipped_at } }
`,
    },
    {
      uri: 'c-binding.ttrm',
      src: `package hartland.stores
model binding
def cn2er_event transfer_g { event: cn.Transfer, target: er.entity.transfer, participants: { ${participants} } }
`,
    },
  ];

  it('a relation declared toward the event is walked the other way', () => {
    expect(sites(concepts('warehouse: [er.relation.warehouse_transfers]'))).toEqual([]);
  });

  it('a value kind lands on its attribute, so its path ends on the attribute’s entity', () => {
    expect(sites(concepts('channel: [er.relation.transfer_item]'))).toEqual([]);
    expect(sites(concepts('channel: [er.relation.warehouse_transfers]'))).toEqual(['c-binding.ttrm:3 cn/participant-path-broken']);
  });

  it('a path through an unresolved relation is the unresolved reference’s, not a broken path', () => {
    expect(sites(concepts('warehouse: [er.relation.nowhere]'))).toEqual([]);
  });

  it('a path step that is not a relation is broken', () => {
    expect(validate(concepts('warehouse: [er.entity.warehouse]')).diagnostics.map((d) => d.message)).toContain(
      "'er.entity.warehouse' in the path of 'warehouse' is an er entity, not a relation",
    );
  });
});

describe('what a `concept { }` block adds to a cn def', () => {
  it("a block supplies the role's event and the event's period the def leaves unset", () => {
    const files = [
      {
        uri: 'a-er.ttrm',
        src: `package hartland.stores
model er
def entity party { attributes: [ def attribute party_id { type: int, isKey: true } ], concept { role: Member, event: Visit } }
def entity visit {
    attributes: [ def attribute visit_id { type: int, isKey: true }, def attribute visited_at { type: timestamp } ],
    concept { event: Visit, period: { at: visited_at } }
}
`,
      },
      { uri: 'b-cn.ttrm', src: 'package hartland.stores\nmodel cn\ndef role Member { }\ndef event Visit { involves: [Member] }\n' },
    ];
    const v = validate(files);
    expect(v.diagnostics.map((d) => d.code)).toEqual([]);
    expect(v.concepts.get('hartland.stores.cn.concept.Member')?.sugars).toHaveLength(1);
  });

  it('a block on its own declares — and is checked like — a cn def', () => {
    const files = [
      {
        uri: 'a-er.ttrm',
        src: `package hartland.stores
model er
def entity visit { attributes: [ def attribute visit_id { type: int, isKey: true } ], concept { event: Visit, involves: [Party, Party] } }
def entity party { attributes: [ def attribute party_id { type: int, isKey: true } ], concept { kind: Party } }
`,
      },
    ];
    expect(sites(files)).toEqual(['a-er.ttrm:3 cn/involves-unlabelled-duplicate']);
    expect(sites(files, 'cn/event-no-period')).toEqual(['a-er.ttrm:3 cn/event-no-period']);
  });

  it("a block's `hierarchy:` is checked too", () => {
    const files = [
      {
        uri: 'a-er.ttrm',
        src: `package hartland.stores
model er
def entity closure { attributes: [ def attribute up { type: text }, def attribute down { type: text } ] }
def entity code {
    attributes: [ def attribute code { type: text, isKey: true } ],
    concept { kind: Code, hierarchy: { closure: er.entity.closure, ancestor: up, descendant: down } }
}
`,
      },
    ];
    expect(validate(files).diagnostics.map((d) => d.message)).toEqual(["the hierarchy closure 'closure' has no key attribute"]);
  });
});

describe('cn/grounding-target-kind — corners of the table', () => {
  const er = `package hartland.stores
model er
def entity order { attributes: [ def attribute order_number { type: int, isKey: true } ] }
def query placed { sourceText: """sql
SELECT order.order_number FROM order
""" }
def entity placed_order { attributes: [ def attribute order_number { type: int, isKey: true } ] }
`;
  const binding = `package hartland.stores
model binding
def er2db_entity placed_order_b { entity: er.entity.placed_order, target: { query: er.query.placed } }
`;
  const cn = (grounds: string) =>
    `package hartland.stores\nmodel cn\ndef event Order { period: { at: placed_date }, grounds: er.entity.order }\ndef phase Placed { of: Order, grounds: ${grounds} }\n`;

  it('an entity a `def er2db_entity` stacks on a query takes a phase', () => {
    expect(sites([{ uri: 'a.ttrm', src: er }, { uri: 'b.ttrm', src: binding }, { uri: 'c.ttrm', src: cn('er.entity.placed_order') }])).toEqual([]);
    expect(sites([{ uri: 'a.ttrm', src: er }, { uri: 'c.ttrm', src: cn('er.entity.placed_order') }])).toEqual([
      'c.ttrm:4 cn/grounding-target-kind',
    ]);
  });

  it('a `grounds:` that names no layer synthesizes nothing and says so', () => {
    const v = validate([{ uri: 'a.ttrm', src: er }, { uri: 'c.ttrm', src: cn('order') }]);
    expect(v.diagnostics.map((d) => d.code)).toContain('cn/grounding-target-kind');
    expect(v.diagnostics.find((d) => d.code === 'cn/grounding-target-kind')?.message).toMatch(/names no md, er or db element/);
  });

  it('an unresolved target is the unresolved reference’s', () => {
    expect(sites([{ uri: 'a.ttrm', src: er }, { uri: 'c.ttrm', src: cn('er.entity.nowhere') }])).toEqual([]);
  });
});

describe('cn/antirigid-supertype and cn/unknown-relation-type — corners', () => {
  it('an `is_a` cycle ends', () => {
    const src = 'package p\nmodel cn\ndef kind A { is_a: B }\ndef kind B { is_a: A }\n';
    expect(sites([{ uri: 'a.ttrm', src }])).toEqual([]);
  });

  it('a transitive supertype is named with the step it was reached through', () => {
    const src = `package p
model cn
def event Purchase { involves: [Customer], period: { at: sold_date } }
def role Customer { event: Purchase }
def kind Loyal { is_a: Customer }
def kind Vip { is_a: Loyal }
`;
    expect(validate([{ uri: 'a.ttrm', src }]).diagnostics.find((d) => d.code === 'cn/antirigid-supertype' && d.message.startsWith("kind 'Vip'"))?.message).toMatch(
      /specializes role 'Customer' \(through 'Loyal'\)/,
    );
  });

  it('a relation type in another package, imported, is known', () => {
    const files = [
      { uri: 'a.ttrm', src: 'package hartland.shared\nmodel cn\ndef relation_type supplies { }\n' },
      { uri: 'b.ttrm', src: 'package hartland.stores\nimport hartland.shared.*\nmodel cn\ndef kind Supplier { relations: [ { type: supplies, to: Supplier } ] }\n' },
    ];
    expect(sites(files)).toEqual([]);
  });
});

describe('the lexicon pair — locales and the bearer chain', () => {
  const cn = {
    uri: 'a-cn.ttrm',
    src: `package p
model cn
def kind Party { }
def event Purchase { involves: [Customer], period: { at: sold_date } }
def role Customer { of: Party, event: Purchase }
def role Shopper { of: Party, event: Purchase }
def phase Debtor { of: Customer, definition: """open_amount > 0""" }
def kind Brand { lexicon: { default: true, terms: ["customer"] } }
`,
  };

  it('a default in one locale does not clash with one in another, nor with an inline (base-locale) default', () => {
    const files = [
      cn,
      { uri: 'b-en.ttrm', src: 'package p\nmodel lexicon locale en\ndef term c { for: cn.Customer, forms: ["customer"], default: true }\n' },
      { uri: 'c-cs.ttrm', src: 'package p\nmodel lexicon locale cs\ndef term s { for: cn.Shopper, forms: ["customer"], default: true }\n' },
    ];
    expect(sites(files, 'cn/duplicate-default-sense')).toEqual([]);
  });

  it('the default sense is compared on the folded form', () => {
    const files = [
      cn,
      { uri: 'b-en.ttrm', src: 'package p\nmodel lexicon locale en\ndef term c { for: cn.Customer, forms: ["Customer"], default: true }\ndef term s { for: cn.Shopper, forms: ["customer "], default: true }\n' },
    ];
    expect(sites(files, 'cn/duplicate-default-sense')).toEqual(['b-en.ttrm:4 cn/duplicate-default-sense']);
  });

  it('a phase default sharing a form with a concept off its chain is not phase-as-default', () => {
    const files = [
      cn,
      { uri: 'b-en.ttrm', src: 'package p\nmodel lexicon locale en\ndef term s { for: cn.Shopper, forms: ["debtor"] }\ndef term d { for: cn.Debtor, forms: ["debtor"], default: true }\n' },
    ];
    expect(sites(files, 'cn/phase-as-default')).toEqual([]);
  });
});

describe('cn/cnc-deprecated', () => {
  it('`model cnc` and `roles:` — nothing else', () => {
    const cnc = parseString('model cnc\ndef role fact_role { }\n', 'a.ttrm').ast!;
    const er = parseString('model er\ndef entity sale { roles: [fact], attributes: [ def attribute id { type: int } ] }\ndef entity store { attributes: [ def attribute id { type: int } ] }\n', 'b.ttrm').ast!;
    const auto = parseString('model automation\ndef role resolver_role { }\n', 'c.ttrm').ast!;
    expect(cncDeprecations(cnc, 'a.ttrm').map((d) => d.source.line)).toEqual([1]);
    expect(cncDeprecations(er, 'b.ttrm').map((d) => d.source.line)).toEqual([2]);
    expect(cncDeprecations(auto, 'c.ttrm')).toEqual([]);
  });
});
