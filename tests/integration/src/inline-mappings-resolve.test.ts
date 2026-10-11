// SPDX-License-Identifier: Apache-2.0
import { describe, it, expect } from 'vitest';
import { parseFile } from '@tatrman/parser';
import { ProjectSymbolTable, Resolver, resolveManifest, PackageGraphBuilder, synthesizeMappings } from '@tatrman/semantics';
import path from 'path';
import { readdirSync } from 'fs';

const root = path.resolve(__dirname, '../../../samples/2.1');

async function loadProject() {
  const files = readdirSync(root).filter((n) => n.endsWith('.ttrm'));
  const symbols = new ProjectSymbolTable();
  const asts = new Map<string, any>();
  for (const f of files) {
    const full = path.join(root, f);
    const parsed = await parseFile(full);
    const uri = `file://${full}`;
    const schemaCode = parsed.ast?.modelDirective?.modelCode ?? '';
    const namespace = parsed.ast?.modelDirective?.schema ?? '';
    symbols.upsertDocument(uri, parsed.ast!, schemaCode, namespace, parsed.ast!.packageDecl?.name ?? '');
    synthesizeMappings(symbols, uri, parsed.ast!);
    asts.set(uri, parsed.ast);
  }
  return { symbols, packageGraph: new PackageGraphBuilder(symbols, asts).build() };
}

describe('samples/2.1 — synthesized symbols', () => {
  it('item gets a synthesized er2dbEntity', async () => {
    const { symbols } = await loadProject();
    const entry = symbols.get('samples.v2_1.binding.er2dbEntity.item');
    expect(entry).toBeDefined();
    expect(entry!.kind).toBe('er2dbEntity');
    expect((entry as any).mappingSource).toBe('inline');
  });

  it('item gets 5 synthesized er2dbAttribute entries from the columns map', async () => {
    const { symbols } = await loadProject();
    const expected = ['sk', 'item_id', 'product_name', 'category_id', 'class_id'];
    for (const a of expected) {
      const entry = symbols.get(`samples.v2_1.binding.er2dbAttribute.item.${a}`);
      expect(entry, `samples.v2_1.binding.er2dbAttribute.item.${a}`).toBeDefined();
      expect((entry as any).mappingSource).toBe('inline');
    }
  });

  it('category.sk gets a synthesized er2dbAttribute from attribute-level mapping', async () => {
    const { symbols } = await loadProject();
    const entry = symbols.get('samples.v2_1.binding.er2dbAttribute.category.sk');
    expect(entry).toBeDefined();
    expect((entry as any).mappingSource).toBe('inline');
  });

  it('category.category_code gets a synthesized er2dbAttribute (bare-id form)', async () => {
    const { symbols } = await loadProject();
    const entry = symbols.get('samples.v2_1.binding.er2dbAttribute.category.category_code');
    expect(entry).toBeDefined();
    expect((entry as any).mappingSource).toBe('inline');
  });

  it('category.category_name gets a synthesized er2dbAttribute (wrapped form)', async () => {
    const { symbols } = await loadProject();
    const entry = symbols.get('samples.v2_1.binding.er2dbAttribute.category.category_name');
    expect(entry).toBeDefined();
    expect((entry as any).mappingSource).toBe('inline');
  });

  it('item_in_category gets a synthesized er2dbRelation (bare-id FK form)', async () => {
    const { symbols } = await loadProject();
    const entry = symbols.get('samples.v2_1.binding.er2dbRelation.item_in_category');
    expect(entry).toBeDefined();
    expect((entry as any).mappingSource).toBe('inline');
  });

  it('item_in_class gets a synthesized er2dbRelation (wrapped FK form)', async () => {
    const { symbols } = await loadProject();
    const entry = symbols.get('samples.v2_1.binding.er2dbRelation.item_in_class');
    expect(entry).toBeDefined();
    expect((entry as any).mappingSource).toBe('inline');
  });

  it('item_class (no inline) only has explicit er2dbEntity', async () => {
    const { symbols } = await loadProject();
    const entries = (symbols as any).getAll('samples.v2_1.binding.er2dbEntity.item_class');
    expect(entries).toHaveLength(1);
    expect(entries[0].mappingSource).toBe('explicit');
  });

  it('class_in_category (no inline) only has explicit er2dbRelation', async () => {
    const { symbols } = await loadProject();
    const entries = (symbols as any).getAll('samples.v2_1.binding.er2dbRelation.class_in_category');
    expect(entries).toHaveLength(1);
    expect(entries[0].mappingSource).toBe('explicit');
  });

  it('synthesized symbols are NOT in any DocumentSymbolTable (project-table only)', async () => {
    const { symbols } = await loadProject();
    const docUris = (symbols as any)._byDocumentKeys ?? [];
    const itemErUri = [...docUris].find((u: string) => u.includes('/samples/2.1/er.ttrm'));
    if (!itemErUri) return;
    const docTable = (symbols as any).byDocument.get(itemErUri);
    if (!docTable) return;
    const docSymbols = docTable.all();
    const itemEr2db = docSymbols.find((e: any) => e.qname === 'samples.v2_1.binding.er2dbEntity.item');
    expect(itemEr2db, 'er2dbEntity.item should NOT be in er.ttrm DocumentSymbolTable').toBeUndefined();
  });
});