// SPDX-License-Identifier: Apache-2.0
import { describe, it, expect } from 'vitest';
import { parseString } from '../index.js';

describe('inline mapping grammar — all surface forms', () => {
  function errors(src: string): string[] {
    const r = parseString(src);
    return r.errors.map((e) => e.message);
  }

  it('entity full — forms (a), (b), (c) with entity target shorthand', () => {
    const src = `model er schema entity
def entity item {
  binding: {
    target: { table: db.dbo.item },
    columns: {
      sk:           i_item_sk,
      item_id:      { target: i_item_id },
      product_name: { target: { column: i_product_name } }
    }
  },
  attributes: [ def attribute sk { type: int, isKey: true } ]
}`;
    expect(errors(src), 'entity full a+b+c must parse').toHaveLength(0);
  });

  it('attribute bare-id — binding: <bareId>', () => {
    const src = `model er schema entity
def entity a { attributes: [
  def attribute category_id { type: int, binding: i_category_id }
] }`;
    expect(errors(src), 'attribute bare-id must parse').toHaveLength(0);
  });

  it('attribute full — binding: { target: { column: ... } }', () => {
    const src = `model er schema entity
def entity a { attributes: [
  def attribute product_name { type: text, binding: { target: { column: i_product_name } } }
] }`;
    expect(errors(src), 'attribute full must parse').toHaveLength(0);
  });

  it('relation bare-fk — binding: <bareId>', () => {
    const src = `model er schema entity
def entity a {}
def entity b {}
def relation r { from: er.entity.a, to: er.entity.b, binding: db.dbo.fk_item_category }`;
    expect(errors(src), 'relation bare-fk must parse').toHaveLength(0);
  });

  it('relation full — binding: { fk: ... }', () => {
    const src = `model er schema entity
def entity a {}
def entity b {}
def relation r { from: er.entity.a, to: er.entity.b, binding: { fk: db.dbo.fk_item_category } }`;
    expect(errors(src), 'relation full must parse').toHaveLength(0);
  });

  it('target: shorthand in explicit er2db_attribute', () => {
    const src = `model binding schema m
def er2db_attribute foo { target: i_item_id }`;
    expect(errors(src), 'target shorthand in er2db_attribute must parse').toHaveLength(0);
  });
});