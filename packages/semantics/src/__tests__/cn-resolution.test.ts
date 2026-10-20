// SPDX-License-Identifier: Apache-2.0
import { describe, it, expect } from 'vitest';
import { parseString } from '@tatrman/parser';
import type { Definition, Document } from '@tatrman/parser';
import { ProjectSymbolTable } from '../project-symbols.js';
import { Resolver } from '../resolver.js';
import { collectAllReferences, collectReferences, isCnContextRef } from '../references.js';
import type { CnSlotReference } from '../references.js';
import { enclosingQnameOf } from '../reference-index.js';
import { MODEL_CODES, cnWireName, modelForKind } from '../qname.js';

// CN-P2 S2.1 — the `cn` model in TS semantics: concept and binding keys, participant labels as child
// symbols, the cn slots collected as references and resolved among concepts only (cn contracts §1.1–§1.5).

const ER = {
  uri: 'er.ttrm',
  src: `package hartland.web
model er
def entity customer { attributes: [ def attribute customer_id { type: int, isKey: true } ] }
def entity warehouse { attributes: [ def attribute warehouse_id { type: int, isKey: true } ] }
def entity order {
    attributes: [
        def attribute order_number { type: int, isKey: true },
        def attribute customer_id { type: int },
        def attribute warehouse_id { type: int },
        def attribute placed_date { type: date },
        def attribute state { type: text }
    ]
}
def entity category_code { attributes: [ def attribute code { type: text, isKey: true } ] }
def entity category_closure {
    attributes: [ def attribute ancestor_code { type: text }, def attribute descendant_code { type: text } ]
}
def relation order_customer { from: er.entity.order, to: er.entity.customer }
def relation order_warehouse { from: er.entity.order, to: er.entity.warehouse }
`,
};

const MD = {
  uri: 'md.ttrm',
  src: `package hartland.web
model md
def dimension Customer { key: code, attributes: [ def attribute code { domain: md.CustomerCode, isKey: true } ] }
def dimension Product {
    key: sku,
    attributes: [ def attribute sku { domain: md.Sku, isKey: true }, def attribute brand { domain: md.Brand } ]
}
`,
};

const CONCEPTS = {
  uri: 'concepts.ttrm',
  src: `package hartland.web
model cn
def kind Party { label: { en: "Party" } }
def kind Organization { label: { en: "Organization" } }
def kind Warehouse { label: { en: "Distribution center" } }
def kind Item { qualities: [brand, unit_price] }
def kind ProductCategory {
    grounds: er.entity.category_code,
    hierarchy: { closure: er.entity.category_closure, ancestor: ancestor_code, descendant: descendant_code }
}
def kind Supplier { is_a: [Party, Organization], relations: [ { type: supplies, to: Warehouse } ] }
def role Customer { of: Party, event: Order, grounds: md.Customer }
def event Order {
    involves: [Customer, Warehouse as fulfilling],
    period: { start: placed_date, end: delivered_date },
    qualities: [state, placed_date]
}
def quality state { of: Order }
def quality category { of: Item, value_space: ProductCategory }
def phase PendingOrder { of: Order, definition: """state = 'placed'""" }
def relation_type supplies { label: { en: "supplies" } }
`,
};

const BINDINGS = {
  uri: 'bindings.ttrm',
  src: `package hartland.bindings
import hartland.web.*
model binding
def cn2er_role customer_g { role: Customer, target: er.entity.customer }
def cn2er_event order_g {
    event: cn.Order,
    target: er.entity.order,
    participants: { customer: [er.relation.order_customer], fulfilling: [er.relation.order_warehouse] },
    period: { start: er.entity.order.placed_date }
}
def cn2md_kind item_g { kind: cn.Item, target: md.Product }
def cn2er_kind category_g {
    kind: cn.ProductCategory,
    target: er.entity.category_code,
    hierarchy: { closure: er.entity.category_closure, ancestor: ancestor_code, descendant: descendant_code }
}
`,
};

const LEXICON = {
  uri: 'lexicon.ttrm',
  src: `package hartland.web
model lexicon locale en
def term customer { for: cn.Customer, forms: ["customer"] }
def term customer_dim { for: md.Customer, forms: ["customer dimension"] }
`,
};

const SUGAR = {
  uri: 'sugar.ttrm',
  src: `package hartland.web
def entity buyer { concept: { role: Buyer, event: Order } }
def cubelet storeReturns { grain: [Customer.code], concept { event: Return, involves: [Customer, Item] } }
`,
};

interface Project {
  table: ProjectSymbolTable;
  resolver: Resolver;
  docs: Map<string, Document>;
}

function project(files: Array<{ uri: string; src: string }>): Project {
  const table = new ProjectSymbolTable();
  const docs = new Map<string, Document>();
  for (const f of files) {
    const { ast, errors } = parseString(f.src, f.uri);
    expect(errors.filter((e) => e.severity === 'error'), f.uri).toEqual([]);
    table.upsertDocument(f.uri, ast!, ast!.modelDirective?.modelCode ?? '', ast!.modelDirective?.schema ?? '', ast!.packageDecl?.name ?? '');
    docs.set(f.uri, ast!);
  }
  return { table, resolver: new Resolver(table), docs };
}

function def(p: Project, uri: string, name: string): Definition {
  const d = p.docs.get(uri)!.definitions.find((x) => 'name' in x && x.name === name);
  if (!d) throw new Error(`no def ${name} in ${uri}`);
  return d;
}

/** Resolve every reference of one document the way the lint rule and the reference index do. */
function resolveAll(p: Project, uri: string): Map<string, string | undefined> {
  const ast = p.docs.get(uri)!;
  const schemaCode = ast.modelDirective?.modelCode ?? '';
  const pkg = ast.packageDecl?.name ?? '';
  const out = new Map<string, string | undefined>();
  for (const { ref, ownerDef } of collectAllReferences(ast)) {
    const res = p.resolver.resolveReference(ref, {
      schemaCode,
      namespace: '',
      enclosingQname: enclosingQnameOf(ownerDef, schemaCode, '', pkg),
      imports: ast.imports,
      packageName: pkg,
    });
    out.set(ref.path, res.resolved ? res.symbol.qname : undefined);
  }
  return out;
}

describe('cn S2.1 T1 — the key shape', () => {
  it('cn is a model code; concepts are model cn, bindings model binding', () => {
    expect(MODEL_CODES.has('cn')).toBe(true);
    expect(modelForKind('concept')).toBe('cn');
    expect(modelForKind('conceptBinding')).toBe('binding');
    expect(modelForKind('cn2er_event')).toBe('binding');
  });

  it('every stereotype keys under one namespace, <pkg>.cn.concept.<Name>, carrying its stereotype', () => {
    const p = project([CONCEPTS]);
    const cases: Array<[string, string]> = [
      ['Party', 'kind'],
      ['Customer', 'role'],
      ['Order', 'event'],
      ['state', 'quality'],
      ['PendingOrder', 'phase'],
      ['supplies', 'relation_type'],
    ];
    for (const [name, stereotype] of cases) {
      const e = p.table.get(`hartland.web.cn.concept.${name}`);
      expect(e, name).toBeDefined();
      expect(e!.kind).toBe('concept');
      expect(e!.stereotype).toBe(stereotype);
      expect(e!.schemaCode).toBe('cn');
    }
  });

  it('every binding keys as <pkg>.binding.<bindingKind>.<name>', () => {
    const p = project([BINDINGS]);
    for (const [kind, name] of [['cn2er_role', 'customer_g'], ['cn2er_event', 'order_g'], ['cn2md_kind', 'item_g'], ['cn2er_kind', 'category_g']]) {
      const e = p.table.get(`hartland.bindings.binding.${kind}.${name}`);
      expect(e, name).toBeDefined();
      expect(e!.kind).toBe('conceptBinding');
      expect(e!.bindingKind).toBe(kind);
    }
  });

  it('two stereotypes of one name in a package are one key — a duplicate definition', () => {
    const p = project([
      CONCEPTS,
      { uri: 'more.ttrm', src: 'package hartland.web\nmodel cn\ndef kind Order { label: { en: "Order" } }\n' },
    ]);
    expect(p.table.duplicates().map((d) => d.qname)).toEqual(['hartland.web.cn.concept.Order']);
  });

  it('the wire name is derived from the key, never a second key', () => {
    expect(cnWireName('hartland.web.cn.concept.Customer')).toBe('cn.Customer');
    expect(cnWireName('cn.concept.Customer')).toBe('cn.Customer');
    expect(cnWireName('hartland.web.cn.concept.Order.fulfilling')).toBe('cn.Order.fulfilling');
    expect(cnWireName('hartland.web.er.entity.order')).toBeUndefined();
  });
});

describe('cn S2.1 T2 — symbols', () => {
  it("an event's participant labels are child symbols; the default label is the concept name lower-cased", () => {
    const p = project([CONCEPTS]);
    const customer = p.table.get('hartland.web.cn.concept.Order.customer');
    const fulfilling = p.table.get('hartland.web.cn.concept.Order.fulfilling');
    expect(customer?.kind).toBe('participant');
    expect(customer?.parent).toBe('hartland.web.cn.concept.Order');
    expect(fulfilling?.kind).toBe('participant');
    // `Warehouse as fulfilling` registers the label, not the concept name.
    expect(p.table.get('hartland.web.cn.concept.Order.warehouse')).toBeUndefined();
  });

  it('the referrer of a cn ref is its concept or binding key', () => {
    const p = project([CONCEPTS, BINDINGS]);
    expect(enclosingQnameOf(def(p, CONCEPTS.uri, 'Customer'), 'cn', '', 'hartland.web')).toBe('hartland.web.cn.concept.Customer');
    expect(enclosingQnameOf(def(p, BINDINGS.uri, 'order_g'), 'binding', '', 'hartland.bindings')).toBe(
      'hartland.bindings.binding.cn2er_event.order_g',
    );
  });
});

describe('cn S2.1 T3 — concept slots', () => {
  it('is_a, relations[].type / .to are cn refs; grounds and hierarchy are not', () => {
    const p = project([CONCEPTS]);
    const supplier = collectReferences(def(p, CONCEPTS.uri, 'Supplier'));
    expect(supplier.map((r) => [r.path, isCnContextRef(r)])).toEqual([
      ['Party', true],
      ['Organization', true],
      ['supplies', true],
      ['Warehouse', true],
    ]);
    const category = collectReferences(def(p, CONCEPTS.uri, 'ProductCategory'));
    expect(category.map((r) => [r.path, isCnContextRef(r)])).toEqual([
      ['er.entity.category_code', false],
      ['er.entity.category_closure', false],
      // the closure's two attributes are read relative to the closure entity
      ['er.entity.category_closure.ancestor_code', false],
      ['er.entity.category_closure.descendant_code', false],
    ]);
  });

  it('qualities items and period values are soft: bearer-local names', () => {
    const p = project([CONCEPTS]);
    const order = collectReferences(def(p, CONCEPTS.uri, 'Order')) as CnSlotReference[];
    expect(order.map((r) => [r.path, r.cnContext, r.soft ?? false])).toEqual([
      ['Customer', true, false],
      ['Warehouse', true, false],
      ['placed_date', true, true],
      ['delivered_date', true, true],
      ['state', true, true],
      ['placed_date', true, true],
    ]);
  });

  it('a cn ref resolves among concepts only — the md dimension Customer in the same package is not a candidate', () => {
    const p = project([ER, MD, CONCEPTS]);
    const resolved = resolveAll(p, CONCEPTS.uri);
    expect(resolved.get('Party')).toBe('hartland.web.cn.concept.Party');
    expect(resolved.get('Customer')).toBe('hartland.web.cn.concept.Customer');
    expect(resolved.get('Order')).toBe('hartland.web.cn.concept.Order');
    expect(resolved.get('supplies')).toBe('hartland.web.cn.concept.supplies');
    expect(resolved.get('ProductCategory')).toBe('hartland.web.cn.concept.ProductCategory');
    // groundings resolve through the generic path, to the layer they name
    expect(resolved.get('md.Customer')).toBe('hartland.web.md.dimension.Customer');
    expect(resolved.get('er.entity.category_closure.ancestor_code')).toBe('hartland.web.er.entity.category_closure.ancestor_code');
    // a declared quality resolves; an undeclared one is an implicit quality — soft, unresolved, never reported
    expect(resolved.get('state')).toBe('hartland.web.cn.concept.state');
    expect(resolved.get('delivered_date')).toBeUndefined();
  });

  it('concepts are invisible to an element reference — a generic `Customer` still finds the dimension', () => {
    const p = project([MD, CONCEPTS]);
    const res = p.resolver.resolveReference(
      { path: 'Customer', parts: ['Customer'] },
      { schemaCode: 'md', namespace: '', packageName: 'hartland.web' },
    );
    expect(res.resolved && res.symbol.qname).toBe('hartland.web.md.dimension.Customer');
  });

  it('a cn ref never falls back to an element of the same name', () => {
    const p = project([MD]);
    const res = p.resolver.resolveReference(
      { path: 'Customer', parts: ['Customer'], cnContext: true },
      { schemaCode: 'cn', namespace: '', packageName: 'hartland.web' },
    );
    expect(res.resolved).toBe(false);
  });
});

describe('cn S2.1 T4 — bindings, sugar, lexicon', () => {
  it('binding slots: the stereotype ref (bare or cn.-headed), target, participant labels, period, hierarchy', () => {
    const p = project([ER, MD, CONCEPTS, BINDINGS]);
    const resolved = resolveAll(p, BINDINGS.uri);
    // bare in a cn slot — across packages through the wildcard import
    expect(resolved.get('Customer')).toBe('hartland.web.cn.concept.Customer');
    expect(resolved.get('cn.Order')).toBe('hartland.web.cn.concept.Order');
    expect(resolved.get('cn.Item')).toBe('hartland.web.cn.concept.Item');
    // a participants key is the event's label
    expect(resolved.get('cn.Order.customer')).toBe('hartland.web.cn.concept.Order.customer');
    expect(resolved.get('cn.Order.fulfilling')).toBe('hartland.web.cn.concept.Order.fulfilling');
    expect(resolved.get('er.relation.order_customer')).toBe('hartland.web.er.relation.order_customer');
    expect(resolved.get('er.entity.order')).toBe('hartland.web.er.entity.order');
    expect(resolved.get('er.entity.order.placed_date')).toBe('hartland.web.er.entity.order.placed_date');
    expect(resolved.get('md.Product')).toBe('hartland.web.md.dimension.Product');
    expect(resolved.get('er.entity.category_closure.descendant_code')).toBe(
      'hartland.web.er.entity.category_closure.descendant_code',
    );
  });

  it('a participants key that names no label of the event does not resolve', () => {
    const p = project([
      ER,
      CONCEPTS,
      {
        uri: 'bad.ttrm',
        src: `package hartland.web
model binding
def cn2er_event order_g { event: Order, target: er.entity.order, participants: { buyer: [er.relation.order_customer] } }
`,
      },
    ]);
    const resolved = resolveAll(p, 'bad.ttrm');
    expect(resolved.has('Order.buyer')).toBe(true);
    expect(resolved.get('Order.buyer')).toBeUndefined();
  });

  it("sugar: the stereotype slot declares (no ref); every other slot is a cn ref", () => {
    const p = project([CONCEPTS, MD, SUGAR]);
    const buyer = collectReferences(def(p, SUGAR.uri, 'buyer'));
    expect(buyer.map((r) => [r.path, isCnContextRef(r)])).toEqual([['Order', true]]);
    const cubelet = collectReferences(def(p, SUGAR.uri, 'storeReturns'));
    expect(cubelet.filter(isCnContextRef).map((r) => r.path)).toEqual(['Customer', 'Item']);
    const resolved = resolveAll(p, SUGAR.uri);
    expect(resolved.get('Order')).toBe('hartland.web.cn.concept.Order');
    expect(resolved.get('Item')).toBe('hartland.web.cn.concept.Item');
  });

  it('lexicon `for: cn.X` resolves to the concept; `for: md.X` to the element', () => {
    const p = project([MD, CONCEPTS, LEXICON]);
    const resolved = resolveAll(p, LEXICON.uri);
    expect(resolved.get('cn.Customer')).toBe('hartland.web.cn.concept.Customer');
    expect(resolved.get('md.Customer')).toBe('hartland.web.md.dimension.Customer');
  });

  it('collectAllReferences carries the cn context through to its callers', () => {
    const p = project([CONCEPTS]);
    const refs = collectAllReferences(p.docs.get(CONCEPTS.uri)!);
    expect(refs.some(({ ref }) => isCnContextRef(ref))).toBe(true);
  });
});
