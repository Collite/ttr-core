// SPDX-License-Identifier: Apache-2.0
import { describe, it, expect } from 'vitest';
import { parseString } from '@tatrman/parser';
import { ProjectSymbolTable } from '../project-symbols.js';
import { DocumentSymbolTable } from '../symbol-table.js';
import { synthesizeMappings } from '../mapping-synthesizer.js';

function setup(ttr: string, uri = 'file:///t/er.ttrm') {
  const parsed = parseString(ttr);
  if (parsed.errors.length) throw new Error(`parse errors: ${JSON.stringify(parsed.errors)}`);
  const symbols = new ProjectSymbolTable();
  symbols.upsertDocument(uri, parsed.ast!, 'er', 'entity', parsed.ast!.packageDecl?.name ?? '');
  synthesizeMappings(symbols, uri, parsed.ast!);
  return symbols;
}

describe('mapping-synthesizer — entity', () => {
  it('synthesizes one er2dbEntity + N er2dbAttribute from entity-level mapping', () => {
    const symbols = setup(`
      package billing.products
      model er
      def entity item {
        binding: {
          target: { table: db.dbo.item },
          columns: { sk: i_item_sk, product_name: i_product_name }
        },
        attributes: [
          def attribute sk { type: int, isKey: true },
          def attribute product_name { type: text }
        ]
      }
    `);

    const entityEntry = symbols.get('billing.products.binding.er2dbEntity.item');
    expect(entityEntry).toBeDefined();
    expect(entityEntry!.kind).toBe('er2dbEntity');
    expect(entityEntry!.source.line).toBeGreaterThan(0);
    expect(entityEntry!.mappingSource).toBe('inline');

    const attrA = symbols.get('billing.products.binding.er2dbAttribute.item.sk');
    expect(attrA).toBeDefined();
    const attrB = symbols.get('billing.products.binding.er2dbAttribute.item.product_name');
    expect(attrB).toBeDefined();
  });
});

describe('mapping-synthesizer — attribute', () => {
  it('synthesizes er2dbAttribute from attribute bare-id mapping', () => {
    const symbols = setup(`
      package billing.products
      model er
      def entity foo {
        attributes: [
          def attribute id { type: int, binding: IDX, isKey: true }
        ]
      }
    `);
    const entry = symbols.get('billing.products.binding.er2dbAttribute.foo.id');
    expect(entry).toBeDefined();
    expect(entry!.mappingSource).toBe('inline');
  });
});

describe('mapping-synthesizer — relation', () => {
  it('synthesizes er2dbRelation from relation bare-fk mapping', () => {
    const symbols = setup(`
      package billing.products
      model er
      def relation r {
        from: er.entity.a, to: er.entity.b,
        cardinality: { from: "0..*", to: "1" },
        join: [{ from: er.entity.a.x, to: er.entity.b.x }],
        binding: db.dbo.fk_a_b
      }
    `);
    const entry = symbols.get('billing.products.binding.er2dbRelation.r');
    expect(entry).toBeDefined();
    expect(entry!.mappingSource).toBe('inline');
  });
});

describe('mapping-synthesizer — source location', () => {
  it('synthesized entry points at the inline mapping value', () => {
    const symbols = setup(`
  package p
  model er
  def entity e {
    attributes: [def attribute id { type: int, binding: IDX }]
  }
  `);
    const entry = symbols.get('p.binding.er2dbAttribute.e.id');
    expect(entry).toBeDefined();
    expect(entry!.source.line).toBe(5);
  });
});

describe('mapping-synthesizer — schemaless (project-table only, not in per-file table)', () => {
  it('synthesized er2db_* symbols do NOT appear in the host file DocumentSymbolTable', () => {
    const parsed = parseString(`package p
  model er
  def entity e {
    binding: { target: { table: db.dbo.T }, columns: { id: IDX } }
  }`);
    if (parsed.errors.length) throw new Error('fixture parse errors');

    const uri = 'file:///er.ttrm';
    const symbols = new ProjectSymbolTable();
    symbols.upsertDocument(uri, parsed.ast!, 'er', '', 'p');
    synthesizeMappings(symbols, uri, parsed.ast!);

    expect(symbols.get('p.binding.er2dbEntity.e')).toBeDefined();
    expect(symbols.get('p.binding.er2dbAttribute.e.id')).toBeDefined();

    const docTable = new DocumentSymbolTable(uri, parsed.ast!, 'er', '');
    const er2 = docTable.all().filter((e) => String(e.kind).startsWith('er2db'));
    expect(er2, `unexpected er2db_* in per-file table: ${er2.map((e) => e.qname).join(', ')}`).toHaveLength(0);
  });
});

describe('mapping-synthesizer — collision with explicit def', () => {
  it('duplicates() reports inline+explicit collision at the same qname', () => {
    const er = parseString(`package billing.products
  model er
  def entity item {
    binding: { target: { table: db.dbo.item }, columns: { id: i_item_sk } }
  }`);
    const map = parseString(`package billing.products
  model binding
  def er2db_entity item { entity: er.entity.item, target: { table: db.dbo.item } }`);
    if (er.errors.length || map.errors.length) throw new Error('fixture parse errors');

    const symbols = new ProjectSymbolTable();
    symbols.upsertDocument('file:///er.ttrm', er.ast!, 'er', '', 'billing.products');
    synthesizeMappings(symbols, 'file:///er.ttrm', er.ast!);
    symbols.upsertDocument('file:///map.ttrm', map.ast!, 'binding', '', 'billing.products');

    const dupes = symbols.duplicates();
    const entityDup = dupes.find((d) => d.qname === 'billing.products.binding.er2dbEntity.item');
    expect(entityDup, `duplicates(): ${JSON.stringify(dupes)}`).toBeDefined();
    const sources = entityDup!.entries.map((e) => e.mappingSource).sort();
    expect(sources).toEqual(['explicit', 'inline']);
  });
});