// SPDX-License-Identifier: Apache-2.0
import { describe, it, expect } from 'vitest';
import { parseString } from '@tatrman/parser';
import { ProjectSymbolTable } from '../project-symbols.js';
import { Resolver } from '../resolver.js';
import { enclosingQnameOf } from '../reference-index.js';
import { collectAllReferences } from '../references.js';

/**
 * Stage 2 (RED until Stage 3) — kind → default-schema behavior.
 *
 * Contract Stage 3 establishes: callers pass the schema directive's code, or
 * `''` when the file has no `schema` directive; the qname layer then derives
 * the default schema per definition from its kind. So these tests build the
 * symbol table the way production will (`?? ''`), not the legacy `?? 'db'`.
 *
 * Today (no directive) the schema component is empty (`.entity.e`), so the
 * `er.`/`cnc.`/… assertions fail. After Stage 3 they pass.
 */
function buildSymbols(uri: string, src: string): ProjectSymbolTable {
  const ast = parseString(src, uri).ast!;
  const schemaCode = ast.modelDirective?.modelCode ?? '';
  const namespace = ast.modelDirective?.schema ?? '';
  const symbols = new ProjectSymbolTable();
  symbols.upsertDocument(uri, ast, schemaCode, namespace);
  return symbols;
}

describe('2.2 — symbol-table qname schema component (no directive ⇒ per-kind default)', () => {
  const groups: Array<{ name: string; src: string; qname: string; schema: string }> = [
    {
      name: 'entity ⇒ er',
      src: 'def entity ent_e { attributes: [def attribute a { type: int }] }',
      qname: 'er.entity.ent_e',
      schema: 'er',
    },
    {
      name: 'table ⇒ db',
      src: 'def table tbl_t { columns: [def column c { type: int }] }',
      qname: 'db.dbo.table.tbl_t', // db schema's default handle is dbo
      schema: 'db',
    },
    {
      name: 'role ⇒ cnc',
      src: 'def role rol_r { description: "r" }',
      qname: 'cnc.role.rol_r',
      schema: 'cnc',
    },
    {
      name: 'query ⇒ db (D14)',
      src: 'def query qry_q { language: SQL, sourceText: "SELECT 1" }',
      qname: 'db.dbo.query.qry_q',
      schema: 'db',
    },
    {
      name: 'er2db_entity ⇒ map',
      src: 'def er2db_entity map_m { entity: er.entity.x, target: { table: db.dbo.T } }',
      qname: 'binding.er2dbEntity.map_m',
      schema: 'binding',
    },
  ];

  for (const g of groups) {
    it(g.name, () => {
      const symbols = buildSymbols('file.ttrm', g.src);
      const entry = symbols.get(g.qname);
      expect(entry).toBeDefined();
      expect(entry!.schemaCode).toBe(g.schema);
    });
  }

  it('entity child attribute also inherits the er default', () => {
    const symbols = buildSymbols(
      'file.ttrm',
      'def entity ent_e { attributes: [def attribute a { type: int }] }'
    );
    expect(symbols.get('er.entity.ent_e.a')).toBeDefined();
  });
});

describe('2.3 — schema-less reference resolves to the kind-derived schema', () => {
  it('relation from/to in a schema-less er file resolve to er.entity.*', () => {
    const src = `def entity alpha { attributes: [def attribute id { type: int }] }
                 def entity beta { attributes: [def attribute id { type: int }] }
                 def relation rel_r { from: alpha, to: beta }`;
    const symbols = buildSymbols('file.ttrm', src);
    const resolver = new Resolver(symbols);

    // Mirror validator.ts:156–167 resolution-context construction for a
    // schema-less file: directive code or '' (Stage 3 derives the rest).
    const schemaCode = '';
    const namespace = '';
    const enclosing = enclosingQnameOf(
      { kind: 'relation', name: 'rel_r' } as never,
      schemaCode,
      namespace,
      ''
    );
    const res = resolver.resolveReference(
      { path: 'alpha', parts: ['alpha'] },
      { schemaCode, namespace, enclosingQname: enclosing, packageName: '' }
    );
    expect(res.resolved).toBe(true);
    if (res.resolved) expect(res.symbol.qname).toBe('er.entity.alpha');
  });
});

describe('2.4 — explicit schema directive still wins (regression, must stay green)', () => {
  it('model db schema dbo + def table ⇒ db.dbo.t', () => {
    const symbols = buildSymbols(
      'file.ttrm',
      `model db schema dbo
       def table t { columns: [def column c { type: int }] }`
    );
    expect(symbols.get('db.dbo.table.t')).toBeDefined();
  });

  it('def entity in a model db file is keyed by its kind, not the directive (D12)', () => {
    const symbols = buildSymbols(
      'file.ttrm',
      `model db
       def entity e { attributes: [def attribute a { type: int }] }`
    );
    // D12: the model is derived from the def kind (entity ⇒ er), so the file
    // `model db` directive no longer pulls an entity into the db model.
    expect(symbols.get('er.entity.e')).toBeDefined();
    expect(symbols.get('db.dbo.e')).toBeUndefined();
  });
});

describe('db schema without explicit schema resolves db.dbo.* references', () => {
  it("a `def fk` column reference `db.dbo.<table>.<col>` resolves when the file omits `schema dbo`", () => {
    // The canonical SQL form references columns as db.dbo.T.C. A db file that
    // omits `schema dbo` must still register columns under dbo so these
    // resolve (regression: previously they registered under db.table.* and the
    // reference was flagged "Unresolved reference").
    const ast = parseString(`model db
def table web_page { columns: [ def column wp_web_page_sk { type: int } ] }
def fk fk_x { from: [db.dbo.web_page.wp_web_page_sk], to: [db.dbo.web_page.wp_web_page_sk] }
`).ast!;
    const symbols = new ProjectSymbolTable();
    symbols.upsertDocument('file:///db.ttrm', ast, 'db', '');

    // The column is addressable at the dbo-namespaced qname...
    expect(symbols.get('db.dbo.table.web_page.wp_web_page_sk')).toBeDefined();

    // ...and the fk's fully-qualified references resolve to it.
    const resolver = new Resolver(symbols);
    for (const { ref, ownerDef } of collectAllReferences(ast)) {
      const res = resolver.resolveReference(
        { path: ref.path, parts: ref.parts },
        { schemaCode: 'db', namespace: '', enclosingQname: enclosingQnameOf(ownerDef, 'db', ''), packageName: '' },
      );
      expect(res.resolved, `${ref.path} should resolve`).toBe(true);
    }
  });
});
