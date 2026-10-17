// SPDX-License-Identifier: Apache-2.0
import { describe, it, expect } from 'vitest';
import { parseString } from '@tatrman/parser';
import { ProjectSymbolTable } from '../project-symbols.js';
import { Resolver } from '../resolver.js';
import { buildCanonicalKey, modelForKind } from '../qname.js';
import { enclosingQnameOf } from '../reference-index.js';

/**
 * LQ-T0·S1 — a logical query (project `ttr-translator/logical-queries`, contract L-1): a `def query`
 * declared in a `model er` file belongs to the er model, `er.query.<name>`; in any other file it
 * stays the db-layer object of D14 (`db.<schema>.query.<name>`). The lane is the model the query
 * sits in — never guessed from its body.
 */
function buildSymbols(files: Array<[string, string]>): ProjectSymbolTable {
  const symbols = new ProjectSymbolTable();
  for (const [uri, src] of files) {
    const ast = parseString(src, uri).ast!;
    symbols.upsertDocument(uri, ast, ast.modelDirective?.modelCode ?? '', ast.modelDirective?.schema ?? '');
  }
  return symbols;
}

const ER_FILE = `model er

def query shopper_revenue {
    description: "Revenue per shopper",
    sourceText: """sql
        SELECT shopper.shopper_id, SUM(sale.revenue) AS revenue
        FROM sale JOIN shopper
        GROUP BY shopper.shopper_id
    """
}

def entity shopper_revenue {
    description: "A shopper with the revenue of their sales",
    binding: { target: { query: er.query.shopper_revenue } },
    attributes: [
        def attribute shopper_id { type: int, isKey: true },
        def attribute total_revenue { type: decimal, binding: revenue }
    ]
}
`;

describe('LQ-T0 — modelForKind: the declaring model decides a query', () => {
  it('a query declared in an er model is er', () => {
    expect(modelForKind('query', 'er')).toBe('er');
  });
  it('a query declared in a db model, a legacy `query` model or no model stays db (D14)', () => {
    expect(modelForKind('query', 'db')).toBe('db');
    expect(modelForKind('query', 'query')).toBe('db');
    expect(modelForKind('query', '')).toBe('db');
    expect(modelForKind('query')).toBe('db');
  });
  it('the declaring model does not move any other kind (D12)', () => {
    expect(modelForKind('table', 'er')).toBe('db');
    expect(modelForKind('drillMap', 'er')).toBe('db');
    expect(modelForKind('entity', 'db')).toBe('er');
  });
});

describe('LQ-T0 — buildCanonicalKey: er.query.<name> carries no schema slot', () => {
  it('er-declared query', () => {
    expect(buildCanonicalKey({ kind: 'query', declaringModel: 'er', schemaId: 'dbo', parts: ['x'] })).toBe('er.query.x');
    expect(buildCanonicalKey({ packageName: 'shop', kind: 'query', declaringModel: 'er', parts: ['x'] })).toBe(
      'shop.er.query.x',
    );
  });
  it('db-declared or undeclared query is unchanged', () => {
    expect(buildCanonicalKey({ kind: 'query', declaringModel: 'db', schemaId: 'dbo', parts: ['x'] })).toBe('db.dbo.query.x');
    expect(buildCanonicalKey({ kind: 'query', parts: ['x'] })).toBe('db.dbo.query.x');
  });
});

describe('LQ-T0 — symbol table + resolution', () => {
  it('a `model er` query registers as er.query.<name> with schemaCode er', () => {
    const symbols = buildSymbols([['er.ttrm', ER_FILE]]);
    const entry = symbols.get('er.query.shopper_revenue');
    expect(entry).toBeDefined();
    expect(entry!.kind).toBe('query');
    expect(entry!.schemaCode).toBe('er');
    expect(symbols.get('db.dbo.query.shopper_revenue')).toBeUndefined();
    // the stacked entity is an ordinary entity
    expect(symbols.get('er.entity.shopper_revenue')).toBeDefined();
  });

  it('a `model db` query is unchanged (db.dbo.query.<name>)', () => {
    const symbols = buildSymbols([
      ['db.ttrm', 'model db schema dbo\ndef query q { sourceText: "SELECT 1" }'],
    ]);
    expect(symbols.get('db.dbo.query.q')).toBeDefined();
    expect(symbols.get('er.query.q')).toBeUndefined();
  });

  it('`target: { query: er.query.x }` resolves to the logical query', () => {
    const symbols = buildSymbols([['er.ttrm', ER_FILE]]);
    const resolver = new Resolver(symbols);
    const enclosing = enclosingQnameOf({ kind: 'entity', name: 'shopper_revenue' } as never, 'er', '', '');
    const res = resolver.resolveReference(
      { path: 'er.query.shopper_revenue', parts: ['er', 'query', 'shopper_revenue'] },
      { schemaCode: 'er', namespace: '', enclosingQname: enclosing, packageName: '' },
    );
    expect(res.resolved).toBe(true);
    if (res.resolved) expect(res.symbol.qname).toBe('er.query.shopper_revenue');
  });

  it('the enclosing qname of an er-declared query is er.query.<name>', () => {
    expect(enclosingQnameOf({ kind: 'query', name: 'shopper_revenue' } as never, 'er', '', '')).toBe(
      'er.query.shopper_revenue',
    );
    expect(enclosingQnameOf({ kind: 'query', name: 'q' } as never, 'db', 'dbo', '')).toBe('db.dbo.query.q');
  });
});
