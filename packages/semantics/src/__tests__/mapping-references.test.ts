// SPDX-License-Identifier: Apache-2.0
import { describe, it, expect } from 'vitest';
import { parseString } from '@tatrman/parser';
import { ProjectSymbolTable } from '../project-symbols.js';
import { Resolver } from '../resolver.js';
import { collectBindingReferences, collectBindingTargetReferences } from '../mapping-references.js';

// A db file providing the target table + columns + a top-level fk, plus an er
// file with inline mappings. Returns the collected mapping references for the er
// doc.
function setup(er: string, pkg = '') {
  const db = `${pkg ? `package ${pkg}\n` : ''}model db schema dbo
def table promotion {
  columns: [
    def column p_promo_sk { type: int },
    def column p_promo_name { type: text }
  ]
}
def fk fk_p_item { description: "x" }
`;
  const symbols = new ProjectSymbolTable();
  const dbAst = parseString(db).ast!;
  symbols.upsertDocument('file:///p/db.ttrm', dbAst, 'db', 'dbo', pkg);

  const erAst = parseString(er).ast!;
  symbols.upsertDocument('file:///p/er.ttrm', erAst, 'er', 'entity', pkg);

  const resolver = new Resolver(symbols);
  return collectBindingReferences(erAst, resolver, 'er', 'entity', pkg);
}

describe('collectBindingReferences — Increment A (attribute column mappings)', () => {
  it('resolves a bare-id mapping (`binding: p_promo_sk`) to the db column', () => {
    const refs = setup(`model er schema entity
def entity promotion {
  binding: { target: { table: db.dbo.promotion } },
  attributes: [ def attribute sk { type: int, binding: p_promo_sk } ]
}
`);
    expect(refs).toHaveLength(1);
    expect(refs[0].ref.path).toBe('p_promo_sk');
    expect(refs[0].targetQname).toBe('db.dbo.table.promotion.p_promo_sk');
    expect(refs[0].referrerQname).toBe('er.entity.promotion');
  });

  it('resolves `{ target: COL }` and `{ target: { column: COL } }` forms', () => {
    const refs = setup(`model er schema entity
def entity promotion {
  binding: { target: { table: db.dbo.promotion } },
  attributes: [
    def attribute a { type: int, binding: { target: p_promo_sk } },
    def attribute b { type: text, binding: { target: { column: p_promo_name } } }
  ]
}
`);
    const targets = refs.map((r) => r.targetQname).sort();
    expect(targets).toEqual([
      'db.dbo.table.promotion.p_promo_name',
      'db.dbo.table.promotion.p_promo_sk',
    ]);
  });

  it('skips mappings whose column does not exist in the target table', () => {
    const refs = setup(`model er schema entity
def entity promotion {
  binding: { target: { table: db.dbo.promotion } },
  attributes: [ def attribute a { type: int, binding: NOSUCHCOL } ]
}
`);
    expect(refs).toHaveLength(0);
  });

  it('skips attribute mappings when the entity has no resolvable target table', () => {
    const refs = setup(`model er schema entity
def entity promotion {
  attributes: [ def attribute a { type: int, binding: p_promo_sk } ]
}
`);
    expect(refs).toHaveLength(0);
  });

  it('resolves an entity-level `columns:` map (all three value forms)', () => {
    const refs = setup(`model er schema entity
def entity promotion {
  binding: {
    target: { table: db.dbo.promotion },
    columns: {
      sk: p_promo_sk,
      a:  { target: p_promo_name },
      b:  { target: { column: p_promo_sk } }
    }
  },
  attributes: [ def attribute sk { type: int }, def attribute a { type: text }, def attribute b { type: int } ]
}
`);
    const targets = refs.map((r) => r.targetQname).sort();
    expect(targets).toEqual([
      'db.dbo.table.promotion.p_promo_name',
      'db.dbo.table.promotion.p_promo_sk',
      'db.dbo.table.promotion.p_promo_sk',
    ]);
  });

  it('resolves a relation fk mapping (bare-id and wrapped forms) to the db fk', () => {
    const bare = setup(`model er schema entity
def relation r { from: er.entity.x, to: er.entity.y, binding: db.dbo.fk_p_item }
`);
    expect(bare.map((r) => r.targetQname)).toEqual(['db.dbo.fk.fk_p_item']);
    expect(bare[0].referrerQname).toBe('er.relation.r');

    const wrapped = setup(`model er schema entity
def relation r { from: er.entity.x, to: er.entity.y, binding: { fk: db.dbo.fk_p_item } }
`);
    expect(wrapped.map((r) => r.targetQname)).toEqual(['db.dbo.fk.fk_p_item']);
  });

  it('resolves the target table from an explicit def er2db_entity (Increment B2)', () => {
    // Entity has attribute mappings but NO inline mapping block; the target
    // table is declared in a separate map.ttrm via `def er2db_entity`.
    const db = `model db schema dbo
def table promotion { columns: [ def column p_promo_sk { type: int } ] }
`;
    const map = `model binding
def er2db_entity promotion { entity: er.entity.promotion, target: { table: db.dbo.promotion } }
`;
    const er = `model er schema entity
def entity promotion {
  attributes: [ def attribute sk { type: int, binding: p_promo_sk } ]
}
`;
    const symbols = new ProjectSymbolTable();
    symbols.upsertDocument('file:///p/db.ttrm', parseString(db).ast!, 'db', 'dbo', '');
    symbols.upsertDocument('file:///p/map.ttrm', parseString(map).ast!, 'binding', '', '');
    const erAst = parseString(er).ast!;
    symbols.upsertDocument('file:///p/er.ttrm', erAst, 'er', 'entity', '');

    const refs = collectBindingReferences(erAst, new Resolver(symbols), 'er', 'entity', '');
    expect(refs).toHaveLength(1);
    expect(refs[0].targetQname).toBe('db.dbo.table.promotion.p_promo_sk');
  });

  it('resolves an inline `target: { view: … }` (view treated like a table)', () => {
    // A db view with columns; the entity binds to it via the `view` target key.
    const db = `model db schema dbo
def view v_promotion {
  columns: [ def column promo_sk { type: int }, def column promo_name { type: text } ],
  definitionSql: """SELECT 1"""
}
`;
    const er = `model er schema entity
def entity promotion {
  binding: { target: { view: db.dbo.v_promotion } },
  attributes: [
    def attribute a { type: int, binding: promo_sk },
    def attribute b { type: text, binding: { target: { column: promo_name } } }
  ]
}
`;
    const symbols = new ProjectSymbolTable();
    symbols.upsertDocument('file:///p/db.ttrm', parseString(db).ast!, 'db', 'dbo', '');
    const erAst = parseString(er).ast!;
    symbols.upsertDocument('file:///p/er.ttrm', erAst, 'er', 'entity', '');

    const refs = collectBindingReferences(erAst, new Resolver(symbols), 'er', 'entity', '');
    expect(refs.map((r) => r.targetQname).sort()).toEqual([
      'db.dbo.view.v_promotion.promo_name',
      'db.dbo.view.v_promotion.promo_sk',
    ]);
  });

  it('resolves the target view from an explicit def er2db_entity { target: { view: … } }', () => {
    const db = `model db schema dbo
def view v_promotion { columns: [ def column promo_sk { type: int } ], definitionSql: """SELECT 1""" }
`;
    const map = `model binding
def er2db_entity promotion { entity: er.entity.promotion, target: { view: db.dbo.v_promotion } }
`;
    const er = `model er schema entity
def entity promotion {
  attributes: [ def attribute sk { type: int, binding: promo_sk } ]
}
`;
    const symbols = new ProjectSymbolTable();
    symbols.upsertDocument('file:///p/db.ttrm', parseString(db).ast!, 'db', 'dbo', '');
    symbols.upsertDocument('file:///p/map.ttrm', parseString(map).ast!, 'binding', '', '');
    const erAst = parseString(er).ast!;
    symbols.upsertDocument('file:///p/er.ttrm', erAst, 'er', 'entity', '');

    const refs = collectBindingReferences(erAst, new Resolver(symbols), 'er', 'entity', '');
    expect(refs).toHaveLength(1);
    expect(refs[0].targetQname).toBe('db.dbo.view.v_promotion.promo_sk');
  });

  it('respects the package prefix on both sides', () => {
    const refs = setup(`package billing
model er schema entity
def entity promotion {
  binding: { target: { table: db.dbo.promotion } },
  attributes: [ def attribute a { type: int, binding: p_promo_sk } ]
}
`, 'billing');
    expect(refs).toHaveLength(1);
    expect(refs[0].targetQname).toBe('billing.db.dbo.table.promotion.p_promo_sk');
  });
});

// LQ-14 — a binding's *target* (`table:` / `view:` / `query:`) is itself a reference to that object, on an inline
// entity binding and on an explicit `def er2db_entity`, for a query of either lane.
describe('collectBindingTargetReferences — the object a binding targets', () => {
  const db = `model db schema dbo
def table promotion { columns: [ def column p_promo_sk { type: int } ] }
def view v_promotion { columns: [ def column promo_sk { type: int } ], definitionSql: """SELECT 1""" }
`;
  const dbQueries = `def query store_revenue { sourceText: "SELECT p_promo_sk FROM promotion" }
`;
  const erQueries = `model er
def query shopper_revenue { sourceText: "SELECT promotion.sk FROM promotion" }
`;

  function targets(file: string, schemaCode = 'er', namespace = 'entity') {
    const symbols = new ProjectSymbolTable();
    symbols.upsertDocument('file:///p/db.ttrm', parseString(db).ast!, 'db', 'dbo', '');
    symbols.upsertDocument('file:///p/queries.ttrm', parseString(dbQueries).ast!, '', '', '');
    symbols.upsertDocument('file:///p/logical.ttrm', parseString(erQueries).ast!, 'er', '', '');
    const ast = parseString(file).ast!;
    symbols.upsertDocument('file:///p/x.ttrm', ast, schemaCode, namespace, '');
    return collectBindingTargetReferences(ast, new Resolver(symbols), schemaCode, namespace, '');
  }

  it('an inline `target: { table: … }` / `{ view: … }` references the table / view', () => {
    const refs = targets(`model er schema entity
def entity a { binding: { target: { table: db.dbo.promotion } }, attributes: [ def attribute x { type: int } ] }
def entity b { binding: { target: { view: db.dbo.v_promotion } }, attributes: [ def attribute x { type: int } ] }
`);
    expect(refs.map((r) => [r.ref.path, r.targetQname, r.referrerQname])).toEqual([
      ['db.dbo.promotion', 'db.dbo.table.promotion', 'er.entity.a'],
      ['db.dbo.v_promotion', 'db.dbo.view.v_promotion', 'er.entity.b'],
    ]);
  });

  it('an inline `target: { query: … }` references the query — logical (er.query) and db, qualified or short', () => {
    const refs = targets(`model er schema entity
def entity a { binding: { target: { query: er.query.shopper_revenue } }, attributes: [ def attribute x { type: int } ] }
def entity b { binding: { target: { query: db.dbo.store_revenue } }, attributes: [ def attribute x { type: int } ] }
def entity c { binding: { target: { query: store_revenue } }, attributes: [ def attribute x { type: int } ] }
`);
    expect(refs.map((r) => r.targetQname)).toEqual([
      'er.query.shopper_revenue',
      'db.dbo.query.store_revenue',
      'db.dbo.query.store_revenue',
    ]);
  });

  it('a short `query:` target names the query even when an entity of the same name binds to it', () => {
    const refs = targets(`model er
def entity shopper_revenue { binding: { target: { query: shopper_revenue } }, attributes: [ def attribute x { type: int } ] }
`, 'er', '');
    expect(refs.map((r) => r.targetQname)).toEqual(['er.query.shopper_revenue']);
  });

  it('an explicit `def er2db_entity { target: { … } }` references its target', () => {
    const refs = targets(`model binding
def er2db_entity a { entity: er.entity.a, target: { table: db.dbo.promotion } }
def er2db_entity b { entity: er.entity.b, target: { query: er.query.shopper_revenue } }
`, 'binding', '');
    expect(refs.map((r) => r.targetQname)).toEqual(['db.dbo.table.promotion', 'er.query.shopper_revenue']);
  });

  it('skips a target that does not resolve, or resolves to an object of another kind', () => {
    const refs = targets(`model er schema entity
def entity a { binding: { target: { table: db.dbo.nothing } }, attributes: [ def attribute x { type: int } ] }
def entity b { binding: { target: { query: db.dbo.promotion } }, attributes: [ def attribute x { type: int } ] }
`);
    expect(refs).toEqual([]);
  });
});
