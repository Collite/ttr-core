// SPDX-License-Identifier: Apache-2.0
import { describe, it, expect } from 'vitest';
import { parseString } from '../index.js';
import type { Definition, BindingColumnValue } from '../index.js';

// Narrows any kind-discriminated value (Definition, BindingProperty, BindingColumnValue,
// PropertyValue, …) to the variant matching `kind`. Throws if it doesn't match.
function assertKind<T extends { kind: string }, K extends T['kind']>(
  value: T | undefined,
  kind: K,
): Extract<T, { kind: K }> {
  if (!value) throw new Error(`expected kind=${kind}, got undefined`);
  if (value.kind !== kind) throw new Error(`expected kind=${kind}, got ${value.kind}`);
  return value as Extract<T, { kind: K }>;
}

describe('inline mappings — entity level', () => {
  it('parses entity with full inline mapping + columns map', () => {
    const result = parseString(`
      model er
      def entity item {
        binding: {
          target: { table: db.dbo.item },
          columns: {
            sk: i_item_sk,
            item_id: { target: i_item_id },
            product_name: { target: { column: i_product_name } }
          }
        },
        attributes: [
          def attribute sk { type: int, isKey: true },
          def attribute item_id { type: text },
          def attribute product_name { type: text }
        ]
      }
    `);
    expect(result.errors, `parse errors: ${result.errors.map(e => e.message).join(', ')}`).toEqual([]);
    const entity = assertKind<Definition, 'entity'>(result.ast!.definitions[0], 'entity');
    expect(entity.kind).toBe('entity');
    expect(entity.binding).toBeDefined();
    const mapping = assertKind(entity.binding, 'block');
    expect(mapping.kind).toBe('block');
    expect(mapping.target).toBeDefined();
    expect(mapping.columns).toHaveLength(3);
    const columns = mapping.columns!;
    expect(columns[0].name).toBe('sk');
    expect(columns[0].value.kind).toBe('bareId');

    // form (b): { target: i_item_id } — target wrapper preserved
    expect(columns[1].name).toBe('item_id');
    const col1 = assertKind<BindingColumnValue, 'object'>(columns[1].value, 'object');
    expect(col1.object.entries[0].key).toBe('target');
    const col1Inner = assertKind(col1.object.entries[0].value, 'id');
    expect(col1Inner.path).toBe('i_item_id');

    // form (c): { target: { column: i_product_name } } — target wrapper preserved, inner is an object
    expect(columns[2].name).toBe('product_name');
    const col2 = assertKind<BindingColumnValue, 'object'>(columns[2].value, 'object');
    expect(col2.object.entries[0].key).toBe('target');
    expect(col2.object.entries[0].value.kind).toBe('object');
  });
});

describe('inline mappings — attribute level', () => {
  it('parses attribute with bare-id mapping', () => {
    const result = parseString(`
      model er
      def attribute category_id { type: int, binding: i_category_id }
    `);
    expect(result.errors, `parse errors: ${result.errors.map(e => e.message).join(', ')}`).toEqual([]);
    const attr = assertKind<Definition, 'attribute'>(result.ast!.definitions[0], 'attribute');
    expect(attr.binding).toBeDefined();
    const mapping = assertKind(attr.binding, 'bareId');
    expect(mapping.kind).toBe('bareId');
    expect(mapping.id.path).toBe('i_category_id');
  });

  it('parses attribute with full mapping block', () => {
    const result = parseString(`
      model er
      def attribute product_name {
        type: text,
        binding: { target: { column: i_product_name } }
      }
    `);
    expect(result.errors, `parse errors: ${result.errors.map(e => e.message).join(', ')}`).toEqual([]);
    const attr = assertKind<Definition, 'attribute'>(result.ast!.definitions[0], 'attribute');
    const mapping = assertKind(attr.binding, 'block');
    expect(mapping.kind).toBe('block');
    expect(mapping.target).toBeDefined();
  });
});

describe('inline mappings — relation level', () => {
  it('parses relation with bare-fk mapping', () => {
    const result = parseString(`
      model er
      def entity a {}
      def entity b {}
      def relation r {
        from: er.entity.a, to: er.entity.b,
        cardinality: { from: "0..*", to: "1" },
        join: [{ from: er.entity.a.x, to: er.entity.b.x }],
        binding: db.dbo.fk_a_b
      }
    `);
    expect(result.errors, `parse errors: ${result.errors.map(e => e.message).join(', ')}`).toEqual([]);
    const rel = assertKind<Definition, 'relation'>(result.ast!.definitions[2], 'relation');
    const mapping = assertKind(rel.binding, 'bareId');
    expect(mapping.kind).toBe('bareId');
    expect(mapping.id.path).toBe('db.dbo.fk_a_b');
  });

  it('parses relation with fk block', () => {
    const result = parseString(`
      model er
      def entity a {}
      def entity b {}
      def relation r {
        from: er.entity.a, to: er.entity.b,
        cardinality: { from: "0..*", to: "1" },
        join: [{ from: er.entity.a.x, to: er.entity.b.x }],
        binding: { fk: db.dbo.fk_a_b }
      }
    `);
    expect(result.errors, `parse errors: ${result.errors.map(e => e.message).join(', ')}`).toEqual([]);
    const rel = assertKind<Definition, 'relation'>(result.ast!.definitions[2], 'relation');
    const mapping = assertKind(rel.binding, 'block');
    expect(mapping.kind).toBe('block');
    expect(mapping.fk).toBeDefined();
  });
});

describe('targetProperty bare-id relaxation', () => {
  it('accepts bare id in target on explicit er2db_attribute', () => {
    const result = parseString(`
      model binding
      def er2db_attribute foo { attribute: er.entity.a.b, target: SOMECOL }
    `);
    expect(result.errors, `parse errors: ${result.errors.map(e => e.message).join(', ')}`).toEqual([]);
  });
});

describe('source locations', () => {
  it('mapping source location points at the value, not the keyword', () => {
    const result = parseString(`model er
  def attribute id { type: int, binding: IDX }`);
    expect(result.errors).toEqual([]);
    const attr = assertKind<Definition, 'attribute'>(result.ast!.definitions[0], 'attribute');
    expect(attr.binding).toBeDefined();
    // Source location should cover the value span
    expect(attr.binding!.source.line).toBe(2);
    const fileText = `model er\n  def attribute id { type: int, binding: IDX }`;
    const offset = attr.binding!.source.offsetStart;
    expect(fileText.slice(offset, offset + 3)).toBe('IDX');
  });
});
